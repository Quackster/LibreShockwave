'use strict';

/**
 * Node.js WASM integration test for LibreShockwave.
 *
 * Simulates exactly what the browser's WasmEngine does:
 *   loadMovie → setExternalParam → preloadCasts → pumpNetwork → play → tick loop
 *
 * Network requests are intercepted:
 *   - file:// URLs → read directly from disk (cast files resolved via file:// basePath)
 *   - http(s):// URLs → forwarded to native Node.js fetch (XAMPP/local server must be running)
 *   - Other paths   → read as local file paths (WASM binary)
 *
 * Usage: node wasm-test.js <distDir> <dcrFile> <castDir> [sw1]
 * Exit 0 = PASS, Exit 1 = FAIL
 */

const fs   = require('fs');
const path = require('path');
const vm   = require('vm');

// ---------------------------------------------------------------------------
// Args
// ---------------------------------------------------------------------------
const [distDir, dcrFile, castDir, sw1 = ''] = process.argv.slice(2);

if (!distDir || !dcrFile || !castDir) {
    console.error('Usage: wasm-test.js <distDir> <dcrFile> <castDir> [sw1]');
    process.exit(1);
}

function requireFile(p, label) {
    if (!fs.existsSync(p)) {
        console.error(`[TEST] FAIL: ${label} not found: ${p}`);
        process.exit(1);
    }
}

const wasmBinaryPath  = path.join(distDir, 'player-wasm.wasm');
const wasmRuntimePath = path.join(distDir, 'player-wasm.wasm-runtime.js');

requireFile(wasmBinaryPath,  'WASM binary');
requireFile(wasmRuntimePath, 'WASM runtime');
requireFile(dcrFile,         'DCR file');

// Derive the movie base URL as a file:// URL so QueuedNetProvider resolves cast
// files as "file:///castDir/cast.cct" — the fetch override reads these directly.
const dcrFileUrl      = 'file:///' + dcrFile.replace(/\\/g, '/').replace(/^\/+/, '');
const castDirResolved = path.resolve(castDir);

// ---------------------------------------------------------------------------
// Browser shims — must be set up before loading the TeaVM runtime
// ---------------------------------------------------------------------------

// Node 18+ has these globally, but be defensive
global.performance  = global.performance  || require('perf_hooks').performance;
global.TextEncoder  = global.TextEncoder  || require('util').TextEncoder;
global.TextDecoder  = global.TextDecoder  || require('util').TextDecoder;

// Minimal document shim so the TeaVM runtime loads without crashing
global.document = {
    createElement:        () => ({ set src(v) {}, set onload(v) {}, set onerror(v) {} }),
    head:                 { appendChild: () => {} },
    getElementsByTagName: () => [],
    readyState:           'complete',
    currentScript:        null
};
global.window = global;

// Save native fetch (Node 18+) before we override it.
// Used later for real http(s):// requests (e.g. external_variables.txt from localhost).
const _nativeFetch = typeof global.fetch === 'function' ? global.fetch.bind(global) : null;

/**
 * Override fetch() for WASM binary loading and file:// cast URLs.
 * HTTP(S) URLs fall through to native fetch so XAMPP / localhost can serve them.
 *
 * URL routing:
 *   file:///...   → read the path directly from disk
 *   http(s)://... → native fetch first; castDir basename as fallback
 *   anything else → treat as a local file path (WASM binary, absolute Windows path)
 */
global.fetch = async (url, opts) => {
    const urlStr = String(url);

    if (urlStr.startsWith('file:///')) {
        return fsResponse(decodeURIComponent(urlStr.slice(8)));
    }
    if (urlStr.startsWith('file://')) {
        return fsResponse(decodeURIComponent(urlStr.slice(7)));
    }
    if (urlStr.startsWith('http://') || urlStr.startsWith('https://')) {
        // Try native fetch first (requires localhost server)
        if (_nativeFetch) {
            try {
                const r = await _nativeFetch(url, opts);
                return r;
            } catch (_e) { /* server not running — fall through */ }
        }
        // Fallback: basename in castDir
        const filename = urlStr.split('/').pop().split('?')[0];
        return fsResponse(path.join(castDirResolved, filename));
    }
    // Absolute or relative local path (WASM binary loaded by TeaVM)
    return fsResponse(urlStr);
};

function fsResponse(localPath) {
    try {
        const data = fs.readFileSync(localPath);
        const buf  = data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);
        return { ok: true,  status: 200, arrayBuffer: async () => buf, text: async () => data.toString('utf8') };
    } catch (_e) {
        return { ok: false, status: 404, arrayBuffer: async () => new ArrayBuffer(0), text: async () => '' };
    }
}

/**
 * Override WebAssembly.instantiateStreaming to use the buffer path.
 * TeaVM 0.13's load() calls instantiateStreaming(fetch(path), imports).
 */
WebAssembly.instantiateStreaming = async (fetchPromise, imports) => {
    const response = await fetchPromise;
    const buf      = await response.arrayBuffer();
    return WebAssembly.instantiate(buf, imports);
};

// ---------------------------------------------------------------------------
// Load the TeaVM WASM runtime — sets global.TeaVM
// vm.runInThisContext executes in the global scope so 'var TeaVM = ...' is global
// ---------------------------------------------------------------------------
const runtimeCode = fs.readFileSync(wasmRuntimePath, 'utf8');
vm.runInThisContext(runtimeCode);

if (typeof TeaVM === 'undefined') {
    console.error('[TEST] FAIL: TeaVM global not set after loading runtime');
    process.exit(1);
}

// ---------------------------------------------------------------------------
// Main test
// ---------------------------------------------------------------------------
async function runTest() {
    console.log('[TEST] Initializing WASM engine...');

    // TeaVM.wasm.load(path) → calls fetch(path) → our override reads from disk
    const teavm = await TeaVM.wasm.load(wasmBinaryPath);
    await teavm.main([]);

    const exp = teavm.instance.exports; // Raw WebAssembly exports (@Export methods)
    const mem = teavm.memory;           // WebAssembly.Memory (has .buffer)

    console.log('[TEST] WASM engine ready');

    // -----------------------------------------------------------------------
    // Memory helpers — mirrors WasmEngine in shockwave-lib.js
    // -----------------------------------------------------------------------

    function clearException() {
        if (exp.teavm_catchException) exp.teavm_catchException();
    }

    function readStringBuffer(len) {
        const addr = exp.getStringBufferAddress();
        return new TextDecoder().decode(new Uint8Array(mem.buffer, addr, len));
    }

    function readJson(len) {
        if (!len || len <= 0) return null;
        const addr = exp.getLargeBufferAddress();
        if (!addr) return null;
        const str = new TextDecoder().decode(new Uint8Array(mem.buffer, addr, len));
        try {
            return JSON.parse(str);
        } catch (e) {
            console.error('[TEST] JSON parse error:', e.message, '  data:', str.slice(0, 120));
            return null;
        }
    }

    function getLastError() {
        const len = exp.getLastError();
        if (len <= 0) return null;
        return readStringBuffer(len);
    }

    // -----------------------------------------------------------------------
    // Load DCR
    // -----------------------------------------------------------------------
    console.log(`[TEST] Loading DCR: ${dcrFile}`);
    const dcrData = fs.readFileSync(dcrFile);

    // Set basePath as a file:// URL so QueuedNetProvider resolves cast file
    // URLs as file:///castDir/cast.cct — our fetch override reads them directly.
    const bpBytes = new TextEncoder().encode(dcrFileUrl);
    const sbAddr  = exp.getStringBufferAddress();
    const clamp   = Math.min(bpBytes.length, 4096);
    new Uint8Array(mem.buffer, sbAddr, clamp).set(bpBytes.subarray(0, clamp));

    const bufAddr = exp.allocateBuffer(dcrData.length);
    new Uint8Array(mem.buffer, bufAddr, dcrData.length).set(dcrData);

    const movieResult = exp.loadMovie(dcrData.length, bpBytes.length);
    clearException();

    if (!movieResult) {
        const err = getLastError();
        console.error(`[TEST] FAIL: loadMovie returned 0${err ? ': ' + err : ''}`);
        process.exit(1);
    }

    const stageW = (movieResult >>> 16) & 0xFFFF;
    const stageH =  movieResult         & 0xFFFF;
    console.log(`[TEST] Movie loaded: ${stageW}x${stageH}, frames=${exp.getFrameCount()}, tempo=${exp.getTempo()}`);

    // -----------------------------------------------------------------------
    // External params
    // -----------------------------------------------------------------------
    if (sw1) {
        const keyBytes = new TextEncoder().encode('sw1');
        const valBytes = new TextEncoder().encode(sw1);
        const sbuf     = new Uint8Array(mem.buffer, sbAddr, 4096);
        sbuf.set(keyBytes);
        sbuf.set(valBytes, keyBytes.length);
        exp.setExternalParam(keyBytes.length, valBytes.length);
        clearException();
        console.log(`[TEST] Set sw1 param (${sw1.length} chars)`);
    }

    // -----------------------------------------------------------------------
    // Network pump — reads pending requests from WASM, serves files
    //
    // file:// URLs → disk directly
    // http(s):// URLs → native Node fetch (localhost server) with castDir fallback
    // -----------------------------------------------------------------------

    /**
     * Resolve a file:// URL to a local file path.
     */
    function fileUrlToPath(url) {
        const u = String(url);
        if (u.startsWith('file:///')) return decodeURIComponent(u.slice(8));
        if (u.startsWith('file://'))  return decodeURIComponent(u.slice(7));
        return null; // not a file:// URL
    }

    /**
     * Deliver a network request result to WASM. Async to support real HTTP.
     */
    async function deliverFile(taskId, url, method, postData, fallbacks) {
        const u = String(url);

        // --- file:// URLs: read from disk directly ---
        const filePath = fileUrlToPath(u);
        if (filePath !== null) {
            try {
                const data = fs.readFileSync(filePath);
                const addr = exp.allocateNetBuffer(data.length);
                new Uint8Array(mem.buffer, addr, data.length).set(data);
                exp.deliverFetchResult(taskId, data.length);
                clearException();
                console.log(`[NET] OK  ${path.basename(filePath)} (${data.length} bytes)`);
                return;
            } catch (_e) {
                // fall through to fallbacks
            }
            return deliverFallback(taskId, fallbacks, method, postData);
        }

        // --- http(s):// URLs: use native fetch, then castDir basename ---
        if (u.startsWith('http://') || u.startsWith('https://')) {
            if (_nativeFetch) {
                try {
                    const r = await _nativeFetch(u);
                    if (r.ok) {
                        const buf  = await r.arrayBuffer();
                        const data = new Uint8Array(buf);
                        const addr = exp.allocateNetBuffer(data.length);
                        new Uint8Array(mem.buffer, addr, data.length).set(data);
                        exp.deliverFetchResult(taskId, data.length);
                        clearException();
                        const filename = u.split('/').pop().split('?')[0];
                        console.log(`[NET] OK  ${filename} (${data.length} bytes) [HTTP]`);
                        return;
                    }
                } catch (_e) { /* server down or network error */ }
            }
            // Fallback: basename in castDir
            const filename = u.split('/').pop().split('?')[0];
            const local = path.join(castDirResolved, filename);
            try {
                const data = fs.readFileSync(local);
                const addr = exp.allocateNetBuffer(data.length);
                new Uint8Array(mem.buffer, addr, data.length).set(data);
                exp.deliverFetchResult(taskId, data.length);
                clearException();
                console.log(`[NET] OK  ${filename} (${data.length} bytes) [castDir]`);
                return;
            } catch (_e) { /* not in castDir either */ }
            return deliverFallback(taskId, fallbacks, method, postData);
        }

        // --- other URLs (bare paths): try as-is ---
        try {
            const data = fs.readFileSync(u);
            const addr = exp.allocateNetBuffer(data.length);
            new Uint8Array(mem.buffer, addr, data.length).set(data);
            exp.deliverFetchResult(taskId, data.length);
            clearException();
            return;
        } catch (_e) { /* fall through */ }

        return deliverFallback(taskId, fallbacks, method, postData);
    }

    async function deliverFallback(taskId, fallbacks, method, postData) {
        if (fallbacks && fallbacks.length > 0) {
            return deliverFile(taskId, fallbacks[0], method, postData, fallbacks.slice(1));
        }
        exp.deliverFetchError(taskId, 404);
        clearException();
    }

    async function pumpNetwork() {
        const count = exp.getPendingFetchCount();
        clearException();
        if (count === 0) return;

        const len      = exp.getPendingFetchJson();
        clearException();
        const requests = readJson(len);
        exp.drainPendingFetches();
        clearException();

        if (!requests) return;
        await Promise.all(requests.map(req =>
            deliverFile(req.taskId, req.url, req.method, req.postData, req.fallbacks || [])
        ));
    }

    // -----------------------------------------------------------------------
    // Preload casts → pumpNetwork
    // -----------------------------------------------------------------------
    const castCount = exp.preloadCasts();
    clearException();
    if (castCount > 0) console.log(`[TEST] Preloading ${castCount} external cast(s)`);
    await pumpNetwork();

    // -----------------------------------------------------------------------
    // play() → pumpNetwork (delivers any requests queued during prepareMovie)
    // -----------------------------------------------------------------------
    exp.play();
    clearException();
    {
        const err = getLastError();
        if (err) console.error('[TEST] play error:', err);
    }
    await pumpNetwork();

    // -----------------------------------------------------------------------
    // Increase VM step limit for the test environment.
    // In the browser, 100k steps/tick prevents the animation loop from hanging.
    // In Node.js there is no animation loop, so we can safely run more steps
    // per tick — this lets the Lingo state machine (fuse_frameProxy.prepareFrame)
    // make network calls within a single tick instead of spreading across many.
    // -----------------------------------------------------------------------
    if (exp.setVmStepLimit) {
        exp.setVmStepLimit(2_000_000);
        clearException();
        console.log('[TEST] VM step limit set to 2,000,000 for testing');
    }

    // -----------------------------------------------------------------------
    // Tick loop
    // -----------------------------------------------------------------------
    let maxSpriteCount = 0;
    let finalFrame     = 0;
    const MAX_TICKS    = 500;

    for (let i = 0; i < MAX_TICKS; i++) {
        const stillPlaying = exp.tick() !== 0;
        clearException();
        await pumpNetwork();

        const fdLen = exp.getFrameDataJson();
        clearException();
        const fd = readJson(fdLen);

        if (fd) {
            finalFrame = fd.frame || 0;
            const spriteCount = (fd.sprites || []).length;
            if (spriteCount > maxSpriteCount) maxSpriteCount = spriteCount;

            if (i < 5 || i % 50 === 0) {
                console.log(`[TEST] tick ${i}: frame=${finalFrame}/${fd.frameCount || '?'} sprites=${spriteCount}`);
            }
        }

        // Log any WASM-level errors on the first few ticks
        if (i < 10) {
            const err = getLastError();
            if (err) console.error(`[TEST] tick ${i} error: ${err}`);
        }

        if (!stillPlaying) {
            console.log(`[TEST] Player stopped at tick ${i}`);
            break;
        }
    }

    // -----------------------------------------------------------------------
    // Pass / fail
    // -----------------------------------------------------------------------
    const pass = maxSpriteCount > 0 && finalFrame > 0;
    if (pass) {
        console.log(`[TEST] PASS: WASM rendering verified (maxSprites=${maxSpriteCount}, frame=${finalFrame})`);
        process.exit(0);
    } else {
        console.error(`[TEST] FAIL: maxSprites=${maxSpriteCount}, finalFrame=${finalFrame}`);
        process.exit(1);
    }
}

runTest().catch(e => {
    console.error('[TEST] Uncaught error:', e);
    process.exit(1);
});
