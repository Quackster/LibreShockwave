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
 * Usage: node wasm-test.js <distDir> <dcrFile> <castDir> [sw1] [outputDir]
 * Exit 0 = PASS, Exit 1 = FAIL
 */

const fs   = require('fs');
const path = require('path');
const vm   = require('vm');
const zlib = require('zlib');

// ---------------------------------------------------------------------------
// Args
// ---------------------------------------------------------------------------
const [distDir, dcrFile, castDir, sw1 = '', outputDir = ''] = process.argv.slice(2);

if (!distDir || !dcrFile || !castDir) {
    console.error('Usage: wasm-test.js <distDir> <dcrFile> <castDir> [sw1] [outputDir]');
    process.exit(1);
}

if (outputDir) {
    fs.mkdirSync(outputDir, { recursive: true });
    console.log(`[FRAME] Saving PNG frames to: ${outputDir}`);
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
// PNG encoder (no external dependencies — uses built-in zlib)
// ---------------------------------------------------------------------------

(function buildCrcTable() {
    const t = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
        t[n] = c;
    }
    global._PNG_CRC_TABLE = t;
})();

function _crc32(buf) {
    let c = 0xFFFFFFFF;
    for (let i = 0; i < buf.length; i++) c = global._PNG_CRC_TABLE[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
}

function _pngChunk(type, data) {
    const tb  = Buffer.from(type, 'ascii');
    const len = Buffer.alloc(4);   len.writeUInt32BE(data.length, 0);
    const crc = Buffer.alloc(4);   crc.writeUInt32BE(_crc32(Buffer.concat([tb, data])), 0);
    return Buffer.concat([len, tb, data, crc]);
}

/** Encode a w×h RGBA pixel buffer as a PNG Buffer (RGB, level-1 deflate). */
function encodePng(w, h, rgbaPixels) {
    const row = 1 + w * 3;
    const raw = Buffer.alloc(h * row);
    for (let y = 0; y < h; y++) {
        raw[y * row] = 0; // filter byte: None
        for (let x = 0; x < w; x++) {
            const s = (y * w + x) * 4;
            const d = y * row + 1 + x * 3;
            raw[d] = rgbaPixels[s]; raw[d+1] = rgbaPixels[s+1]; raw[d+2] = rgbaPixels[s+2];
        }
    }
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
    ihdr[8] = 8; ihdr[9] = 2; // 8-bit RGB truecolor
    return Buffer.concat([
        Buffer.from([137,80,78,71,13,10,26,10]),
        _pngChunk('IHDR', ihdr),
        _pngChunk('IDAT', zlib.deflateSync(raw, { level: 1 })),
        _pngChunk('IEND', Buffer.alloc(0))
    ]);
}

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

    function readDebugLog() {
        if (!exp.getDebugLog) return null;
        const len = exp.getDebugLog();
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
    // Frame export helpers — uses render() (SoftwareRenderer in WASM)
    // -----------------------------------------------------------------------

    function renderFrame() {
        const len = exp.render(); clearException();
        if (len <= 0) return null;
        const ptr = exp.getRenderBufferAddress(); clearException();
        if (!ptr) return null;
        const rgba = new Uint8ClampedArray(stageW * stageH * 4);
        rgba.set(new Uint8ClampedArray(mem.buffer, ptr, rgba.length));
        return rgba;
    }

    function captureFrame(tickNum, label) {
        if (!outputDir) return;
        try {
            const pixels = renderFrame();
            if (!pixels) return;
            const png    = encodePng(stageW, stageH, pixels);
            const file   = path.join(outputDir, `frame_t${String(tickNum).padStart(4,'0')}_${label}.png`);
            fs.writeFileSync(file, png);
            console.log(`[FRAME] Saved ${path.basename(file)}`);
        } catch (e) {
            console.error(`[FRAME] Export error at tick ${tickNum}:`, e.message);
        }
    }

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
                let data = fs.readFileSync(filePath);
                // Prefer AU locale for external_variables.txt if hh_entry_au.cct is available.
                // The shared hh_entry.cct layout ("entry.visual") references AU-specific member
                // names (hh_au_tausta etc.), so FI/other locales produce 0 hotel sprites.
                const bn = path.basename(filePath);
                if (bn === 'external_variables.txt' &&
                    fs.existsSync(path.join(castDirResolved, 'hh_entry_au.cct'))) {
                    const original = data.toString('utf8');
                    const patched  = original.replace(/cast\.entry\.2=\S+/g, 'cast.entry.2=hh_entry_au');
                    if (patched !== original) {
                        data = Buffer.from(patched, 'utf8');
                        console.log(`[NET] Locale override: cast.entry.2 → hh_entry_au in ${bn}`);
                    }
                }
                const addr = exp.allocateNetBuffer(data.length);
                new Uint8Array(mem.buffer, addr, data.length).set(data);
                exp.deliverFetchResult(taskId, data.length);
                clearException();
                console.log(`[NET] OK  ${bn} (${data.length} bytes)`);
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

    // Helper to drain and print the WASM debug log
    function drainDebugLog(label) {
        const dbg = readDebugLog();
        if (dbg) {
            for (const line of dbg.split('\n')) {
                if (line.trim()) console.log(`[DBG ${label}] ${line}`);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Preload casts → pumpNetwork
    // -----------------------------------------------------------------------
    const castCount = exp.preloadCasts();
    clearException();
    if (castCount > 0) console.log(`[TEST] Preloading ${castCount} external cast(s)`);
    await pumpNetwork();
    drainDebugLog('preload');

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
    drainDebugLog('play');

    // -----------------------------------------------------------------------
    // Tick loop
    // -----------------------------------------------------------------------
    const MAX_TICKS             = 1000; // hotel view appears around tick 200
    const HOTEL_VIEW_MIN_SPRITES = 10;  // logo=2, hotel view=10+

    let maxSpriteCount    = 0;
    let finalFrame        = 0;
    let lastCapturedCount = -1;

    for (let i = 0; i < MAX_TICKS; i++) {
        const stillPlaying = exp.tick() !== 0;
        clearException();

        // Log any WASM-level errors on every tick
        const err = getLastError();
        if (err) console.error(`[TEST] tick ${i} error: ${err}`);

        // Log pending requests before pumpNetwork (verbose for first 20 ticks)
        const pendingCount = exp.getPendingFetchCount(); clearException();
        if (pendingCount > 0 && i < 30) {
            const pLen = exp.getPendingFetchJson(); clearException();
            const reqs = readJson(pLen);
            if (reqs) {
                for (const r of reqs) {
                    const name = r.url.split('/').pop().split('?')[0];
                    console.log(`[NET] tick ${i} queued: ${name} (task=${r.taskId})`);
                }
            }
        }

        await pumpNetwork();

        // Read debug log (print first 200 ticks, then every 50)
        if (i < 200 || i % 50 === 0) {
            const dbg = readDebugLog();
            if (dbg) {
                for (const line of dbg.split('\n')) {
                    if (line.trim()) console.log(`[DBG t${i}] ${line}`);
                }
            }
        }

        // Lightweight sprite count — no bitmap baking, no OOM risk.
        // Use getSpriteCount() + getCurrentFrame() for pass/fail criteria.
        const spriteCount = exp.getSpriteCount ? exp.getSpriteCount() : 0;
        clearException();
        const frameNum = exp.getCurrentFrame();
        clearException();
        if (spriteCount > maxSpriteCount) maxSpriteCount = spriteCount;
        if (frameNum > 0) finalFrame = frameNum;

        const tcStr = exp.getTimeoutCount ? `tc=${exp.getTimeoutCount()}` : '';
        if (i < 30 || i % 100 === 0) {
            const fc = exp.getFrameCount ? exp.getFrameCount() : '?';
            console.log(`[TEST] tick ${i}: frame=${frameNum}/${fc} sprites=${spriteCount} ${tcStr}`);
        }

        // Optional PNG export — only when outputDir is set.
        // render() composites the full frame in WASM (SoftwareRenderer).
        if (outputDir && (spriteCount > lastCapturedCount || (i > 0 && i % 200 === 0))) {
            try {
                captureFrame(i, `s${spriteCount}`);
                if (spriteCount > lastCapturedCount) lastCapturedCount = spriteCount;
            } catch (e) {
                console.error(`[FRAME] render failed at tick ${i} (OOM?):`, e.message);
            }
        }

        if (!stillPlaying) {
            console.log(`[TEST] Player stopped at tick ${i}`);
            break;
        }
    }

    // Attempt a final PNG capture if outputDir is set
    if (outputDir) {
        try {
            captureFrame(MAX_TICKS - 1, 'final');
        } catch (e) {
            console.error('[FRAME] Final frame capture failed (OOM?):', e.message);
        }
    }

    // -----------------------------------------------------------------------
    // Pass / fail
    // -----------------------------------------------------------------------
    const pass = maxSpriteCount >= HOTEL_VIEW_MIN_SPRITES && finalFrame > 0;
    if (pass) {
        console.log(`[TEST] PASS: Hotel view reached (maxSprites=${maxSpriteCount}, frame=${finalFrame})`);
        process.exit(0);
    } else {
        console.error(`[TEST] FAIL: Hotel view not reached (maxSprites=${maxSpriteCount}/${HOTEL_VIEW_MIN_SPRITES}, frame=${finalFrame})`);
        process.exit(1);
    }
}

runTest().catch(e => {
    console.error('[TEST] Uncaught error:', e);
    process.exit(1);
});
