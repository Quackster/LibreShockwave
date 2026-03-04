#!/usr/bin/env node
/**
 * LibreShockwave — Chromium integration test via Puppeteer.
 *
 * Launches headless Chromium, loads the Habbo DCR through the real
 * shockwave-lib.js → Web Worker → WASM pipeline, and verifies that
 * frames render to the canvas with non-trivial pixel content.
 *
 * Usage:
 *   node wasm-chromium-test.js <distDir> <dcrFile> <castDir> [outputDir]
 *
 * Arguments:
 *   distDir   - Path to build/dist (WASM binary, shockwave-lib.js, etc.)
 *   dcrFile   - Path to the .dcr file (e.g. habbo.dcr)
 *   castDir   - Directory containing .cct/.cst files and external_variables.txt
 *   outputDir - (Optional) Directory for screenshot PNGs
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer');

// ---------------------------------------------------------------------------
// Args
// ---------------------------------------------------------------------------
const [distDir, dcrFile, castDir, outputDir] = process.argv.slice(2);
if (!distDir || !dcrFile || !castDir) {
    console.error('Usage: node wasm-chromium-test.js <distDir> <dcrFile> <castDir> [outputDir]');
    process.exit(1);
}

const DIST_DIR = path.resolve(distDir);
const DCR_FILE = path.resolve(dcrFile);
const CAST_DIR = path.resolve(castDir);
const OUTPUT_DIR = outputDir ? path.resolve(outputDir) : null;
const DCR_FILENAME = path.basename(DCR_FILE);

if (OUTPUT_DIR) fs.mkdirSync(OUTPUT_DIR, { recursive: true });

// ---------------------------------------------------------------------------
// MIME types
// ---------------------------------------------------------------------------
const MIME = {
    '.html': 'text/html',
    '.js':   'application/javascript',
    '.wasm': 'application/wasm',
    '.css':  'text/css',
    '.png':  'image/png',
    '.txt':  'text/plain',
    '.dcr':  'application/octet-stream',
    '.cct':  'application/octet-stream',
    '.cst':  'application/octet-stream',
};

function mimeFor(p) { return MIME[path.extname(p).toLowerCase()] || 'application/octet-stream'; }

// ---------------------------------------------------------------------------
// HTTP server
// ---------------------------------------------------------------------------
let serverPort = 0;

function createServer() {
    return new Promise((resolve) => {
        const server = http.createServer((req, res) => {
            const url = new URL(req.url, `http://127.0.0.1`);
            const pathname = decodeURIComponent(url.pathname);

            res.setHeader('Access-Control-Allow-Origin', '*');
            res.setHeader('Access-Control-Allow-Headers', '*');
            if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

            // /test.html — generated test page
            if (pathname === '/test.html') {
                res.writeHead(200, { 'Content-Type': 'text/html' });
                res.end(generateTestHtml());
                return;
            }

            // /dcr/<file> — serve from castDir (with locale patch for external_variables.txt)
            if (pathname.startsWith('/dcr/')) {
                const file = pathname.slice(5); // strip /dcr/
                if (file === DCR_FILENAME) {
                    return serveFile(res, DCR_FILE);
                }
                const filePath = path.join(CAST_DIR, file);
                if (file === 'external_variables.txt') {
                    return serveExternalVariables(res, filePath);
                }
                return serveFile(res, filePath);
            }

            // /* — serve from distDir (WASM, JS, etc.)
            const distFile = path.join(DIST_DIR, pathname === '/' ? 'index.html' : pathname);
            serveFile(res, distFile);
        });

        server.listen(0, '127.0.0.1', () => {
            serverPort = server.address().port;
            console.log(`[TEST] HTTP server on http://127.0.0.1:${serverPort}`);
            resolve(server);
        });
    });
}

function serveFile(res, filePath) {
    if (!fs.existsSync(filePath)) {
        res.writeHead(404); res.end('Not found: ' + filePath); return;
    }
    res.writeHead(200, { 'Content-Type': mimeFor(filePath) });
    fs.createReadStream(filePath).pipe(res);
}

function serveExternalVariables(res, filePath) {
    if (!fs.existsSync(filePath)) {
        res.writeHead(404); res.end('Not found'); return;
    }
    let content = fs.readFileSync(filePath, 'utf-8');
    // Override locale to AU (hh_entry_au.cct must exist in castDir)
    const auCast = path.join(CAST_DIR, 'hh_entry_au.cct');
    if (fs.existsSync(auCast)) {
        content = content.replace(/cast\.entry\.2=.*/, 'cast.entry.2=hh_entry_au');
    }
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end(content);
}

// ---------------------------------------------------------------------------
// Test HTML template
// ---------------------------------------------------------------------------
function generateTestHtml() {
    const dcrUrl = `http://127.0.0.1:${serverPort}/dcr/${DCR_FILENAME}`;
    const sw1 = [
        `external.variables.txt=http://127.0.0.1:${serverPort}/dcr/external_variables.txt`,
        `external.texts.txt=http://127.0.0.1:${serverPort}/dcr/external_texts.txt`,
    ].join(';');

    return `<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>LibreShockwave Chromium Test</title></head>
<body style="margin:0;background:#111">
<canvas id="stage" width="720" height="540"></canvas>
<script src="/shockwave-lib.js"></script>
<script>
window.__TEST_STATE = { ticks: 0, lastFrame: 0, errors: [], done: false };

var player = LibreShockwave.create('stage', {
    autoplay: true,
    params: { sw1: ${JSON.stringify(sw1)} },
    onFrame: function(frame, total) {
        window.__TEST_STATE.ticks++;
        window.__TEST_STATE.lastFrame = frame;
        if (window.__TEST_STATE.ticks % 50 === 0) {
            console.log('[TEST] ticks=' + window.__TEST_STATE.ticks +
                        ' frame=' + frame + '/' + total);
        }
    },
    onError: function(msg) {
        console.error('[TEST] onError: ' + msg);
        window.__TEST_STATE.errors.push(msg);
    },
    onLoad: function(info) {
        console.log('[TEST] Movie loaded: ' + info.width + 'x' + info.height +
                    ', ' + info.frameCount + ' frames, tempo=' + info.tempo);
    }
});

player.load(${JSON.stringify(dcrUrl)});
</script>
</body></html>`;
}

// ---------------------------------------------------------------------------
// Screenshot helper
// ---------------------------------------------------------------------------
async function saveScreenshot(page, label) {
    if (!OUTPUT_DIR) return;
    const filePath = path.join(OUTPUT_DIR, `chromium-${label}.png`);
    try {
        const dataUrl = await page.evaluate(() => {
            const c = document.getElementById('stage');
            return c ? c.toDataURL('image/png') : null;
        });
        if (dataUrl) {
            const base64 = dataUrl.replace(/^data:image\/png;base64,/, '');
            fs.writeFileSync(filePath, Buffer.from(base64, 'base64'));
            console.log(`[TEST] Screenshot: ${filePath}`);
        }
    } catch (e) {
        console.warn(`[TEST] Screenshot failed (${label}):`, e.message);
    }
}

// ---------------------------------------------------------------------------
// Canvas pixel sampling
// ---------------------------------------------------------------------------
async function sampleCanvasPixels(page) {
    return page.evaluate(() => {
        const c = document.getElementById('stage');
        if (!c) return { nonBlack: 0, total: 0 };
        const ctx = c.getContext('2d');
        const d = ctx.getImageData(0, 0, c.width, c.height).data;
        let nonBlack = 0;
        for (let i = 0; i < d.length; i += 4) {
            // Count pixels that aren't fully black (R+G+B > threshold)
            if (d[i] + d[i+1] + d[i+2] > 30) nonBlack++;
        }
        return { nonBlack, total: d.length / 4 };
    });
}

// ---------------------------------------------------------------------------
// Main test
// ---------------------------------------------------------------------------
async function main() {
    const server = await createServer();
    let browser = null;
    let passed = false;

    try {
        console.log('[TEST] Launching Chromium...');
        browser = await puppeteer.launch({
            headless: 'new',
            args: [
                '--no-sandbox',
                '--disable-setuid-sandbox',
                '--disable-dev-shm-usage',
                '--disable-gpu',
            ],
        });

        const page = await browser.newPage();

        // Forward browser console to Node.js stdout
        page.on('console', (msg) => {
            const text = msg.text();
            const type = msg.type();
            if (type === 'error') {
                console.error(`[BROWSER] ${text}`);
            } else {
                console.log(`[BROWSER] ${text}`);
            }
        });

        // Monitor for OOM / fatal errors
        let oomDetected = false;
        page.on('console', (msg) => {
            const text = msg.text().toLowerCase();
            if (text.includes('out of memory') || text.includes('allocation failed') ||
                text.includes('rangeerror')) {
                console.error('[TEST] OOM detected in browser!');
                oomDetected = true;
            }
        });

        page.on('pageerror', (err) => {
            console.error(`[BROWSER] Page error: ${err.message}`);
        });

        console.log(`[TEST] Navigating to http://127.0.0.1:${serverPort}/test.html`);
        await page.goto(`http://127.0.0.1:${serverPort}/test.html`, {
            waitUntil: 'domcontentloaded',
            timeout: 30000,
        });

        // Poll for test completion
        const TIMEOUT_S = 120;
        const POLL_MS = 1000;
        let lastTicks = 0;
        let screenshotAt10 = false;
        let nextScreenshot = 100;

        console.log(`[TEST] Polling for up to ${TIMEOUT_S}s...`);

        for (let elapsed = 0; elapsed < TIMEOUT_S; elapsed++) {
            await new Promise(r => setTimeout(r, POLL_MS));

            if (oomDetected) {
                console.error('[TEST] FAIL: OOM detected, aborting');
                break;
            }

            const state = await page.evaluate(() => window.__TEST_STATE);
            if (!state) continue;

            const { ticks, lastFrame, errors } = state;

            // Screenshot at tick 10
            if (ticks >= 10 && !screenshotAt10) {
                screenshotAt10 = true;
                await saveScreenshot(page, 'tick-010');
            }

            // Screenshot every 100 ticks
            if (ticks >= nextScreenshot) {
                await saveScreenshot(page, `tick-${String(nextScreenshot).padStart(4, '0')}`);
                nextScreenshot += 100;
            }

            // Check canvas pixels periodically
            if (ticks > lastTicks && ticks % 20 === 0) {
                const pixels = await sampleCanvasPixels(page);
                if (elapsed % 10 === 0 || ticks >= 100) {
                    console.log(`[TEST] t=${elapsed}s ticks=${ticks} frame=${lastFrame} ` +
                                `nonBlackPx=${pixels.nonBlack}/${pixels.total} ` +
                                `errors=${errors.length}`);
                }

                // Pass criteria: enough ticks, frame advanced, substantial pixel content
                if (ticks >= 100 && lastFrame > 0 && pixels.nonBlack > 10000) {
                    console.log('[TEST] PASS: Hotel view rendered in Chromium ' +
                                `(ticks=${ticks}, frame=${lastFrame}, nonBlackPx=${pixels.nonBlack})`);
                    await saveScreenshot(page, 'final');
                    passed = true;
                    break;
                }
            }

            lastTicks = ticks;
        }

        if (!passed) {
            const finalState = await page.evaluate(() => window.__TEST_STATE);
            const finalPixels = await sampleCanvasPixels(page);
            await saveScreenshot(page, 'final');
            console.error(`[TEST] FAIL: Did not meet pass criteria after ${TIMEOUT_S}s`);
            console.error(`[TEST]   ticks=${finalState?.ticks || 0} frame=${finalState?.lastFrame || 0} ` +
                          `nonBlackPx=${finalPixels.nonBlack} errors=${finalState?.errors?.length || 0}`);
            if (finalState?.errors?.length > 0) {
                finalState.errors.slice(0, 5).forEach(e => console.error(`[TEST]   error: ${e}`));
            }
        }
    } catch (err) {
        console.error('[TEST] FAIL: Unexpected error:', err.message);
    } finally {
        if (browser) await browser.close().catch(() => {});
        server.close();
    }

    process.exit(passed ? 0 : 1);
}

main();
