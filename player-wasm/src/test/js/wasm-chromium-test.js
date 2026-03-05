#!/usr/bin/env node
'use strict';

/**
 * Headless Chromium integration test for the WASM player.
 *
 * Launches Puppeteer, serves the dist directory + cast files via a local
 * HTTP server, loads the Habbo DCR through the full
 *   shockwave-lib.js -> Web Worker -> WASM -> SoftwareRenderer -> canvas
 * pipeline, captures periodic PNG screenshots, and verifies that frames
 * render with non-trivial pixel content (hotel view reached).
 *
 * Usage (via Gradle):
 *   ./gradlew :player-wasm:runWasmChromiumTest
 *   ./gradlew :player-wasm:runWasmChromiumTest -PoutputDir=C:/tmp/frames
 *
 * Args: distPath dcrFile castDir [outputDir]
 */

const puppeteer = require('puppeteer');
const http = require('http');
const fs = require('fs');
const path = require('path');

// ---------------------------------------------------------------------------
// Args
// ---------------------------------------------------------------------------
const distPath  = process.argv[2] || path.resolve(__dirname, '../../../build/dist');
const dcrFile   = process.argv[3] || 'C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr';
const castDir   = process.argv[4] || 'C:/xampp/htdocs/dcr/14.1_b8';
const outputDir = process.argv[5] || path.resolve(process.cwd(), 'frames_wasm');

const MAX_TICKS = 600;          // Max ticks before giving up
const CAPTURE_INTERVAL = 50;    // Capture a PNG every N ticks
const HOTEL_SPRITE_THRESHOLD = 10; // Sprite count that indicates hotel view

// ---------------------------------------------------------------------------
// Tiny HTTP server: serves dist/ files AND cast files from castDir
// ---------------------------------------------------------------------------
const MIME = {
    '.html': 'text/html',
    '.js':   'application/javascript',
    '.wasm': 'application/wasm',
    '.css':  'text/css',
    '.png':  'image/png',
    '.dcr':  'application/x-director',
    '.cct':  'application/x-director',
    '.cst':  'application/x-director',
    '.txt':  'text/plain',
};

function createServer() {
    return new Promise((resolve) => {
        const server = http.createServer((req, res) => {
            const url = decodeURIComponent(req.url.split('?')[0]);

            // Try dist/ first
            let filePath = path.join(distPath, url);
            if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
                return serveFile(res, filePath);
            }

            // Try castDir (for .dcr, .cct, .cst, .txt files)
            filePath = path.join(castDir, path.basename(url));
            if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
                return serveFile(res, filePath);
            }

            // Try gamedata/ subdirectory under castDir parent
            const gamedataPath = path.join(path.dirname(castDir), url);
            if (fs.existsSync(gamedataPath) && fs.statSync(gamedataPath).isFile()) {
                return serveFile(res, gamedataPath);
            }

            res.writeHead(404);
            res.end('Not found: ' + url);
        });

        server.listen(0, '127.0.0.1', () => {
            const port = server.address().port;
            resolve({ server, port });
        });
    });
}

function serveFile(res, filePath) {
    const ext = path.extname(filePath).toLowerCase();
    const mime = MIME[ext] || 'application/octet-stream';
    const data = fs.readFileSync(filePath);
    res.writeHead(200, {
        'Content-Type': mime,
        'Content-Length': data.length,
        'Access-Control-Allow-Origin': '*',
    });
    res.end(data);
}

// ---------------------------------------------------------------------------
// Main test
// ---------------------------------------------------------------------------
async function main() {
    console.log('=== WASM Chromium Integration Test ===');
    console.log('Dist:    ', distPath);
    console.log('DCR:     ', dcrFile);
    console.log('Casts:   ', castDir);
    console.log('Output:  ', outputDir);

    // Verify dist exists
    if (!fs.existsSync(path.join(distPath, 'libreshockwave.js'))) {
        console.error('FAIL: libreshockwave.js not found in dist. Run assembleWasm first.');
        process.exit(1);
    }

    // Verify DCR exists
    if (!fs.existsSync(dcrFile)) {
        console.error('FAIL: DCR file not found: ' + dcrFile);
        process.exit(1);
    }

    // Create output directory
    fs.mkdirSync(outputDir, { recursive: true });

    // Start server
    const { server, port } = await createServer();
    const baseUrl = `http://127.0.0.1:${port}`;
    console.log('Server:  ', baseUrl);

    // Copy DCR to castDir-relative location for serving
    const dcrFileName = path.basename(dcrFile);

    let browser;
    try {
        // Launch headless Chromium
        browser = await puppeteer.launch({
            headless: true,
            args: ['--no-sandbox', '--disable-setuid-sandbox'],
        });

        const page = await browser.newPage();

        // Collect console output
        const logs = [];
        page.on('console', msg => {
            const text = msg.text();
            logs.push(text);
            // Print worker/LS logs for visibility
            if (text.includes('[W]') || text.includes('[LS]')) {
                console.log('  ' + text);
            }
        });

        // Build a minimal HTML page that uses the bundled libreshockwave.js
        const html = `<!DOCTYPE html>
<html><body>
<canvas id="stage" width="720" height="540"></canvas>
<script src="${baseUrl}/libreshockwave.js"></script>
<script>
    var _testState = { tick: 0, maxSprites: 0, frame: 0, done: false, error: null };

    var player = LibreShockwave.create('stage', {
        basePath: '${baseUrl}/',
        params: {
            sw1: 'site.url=http://127.0.0.1:${port};url.prefix=http://127.0.0.1:${port}',
            sw2: 'connection.info.host=127.0.0.1;connection.info.port=30001',
            sw3: 'client.reload.url=http://127.0.0.1:${port}/',
            sw4: 'connection.mus.host=127.0.0.1;connection.mus.port=38101',
            sw5: 'external.variables.txt=http://127.0.0.1:${port}/gamedata/external_variables.txt;external.texts.txt=http://127.0.0.1:${port}/gamedata/external_texts.txt'
        },
        autoplay: true,
        onLoad: function(info) {
            console.log('[TEST] Movie loaded: ' + info.width + 'x' + info.height);
        },
        onFrame: function(frame, total) {
            _testState.tick++;
            _testState.frame = frame;
        },
        onError: function(msg) {
            _testState.error = msg;
            console.error('[TEST] Error: ' + msg);
        }
    });

    player.load('${baseUrl}/${dcrFileName}');
</script>
</body></html>`;

        // Navigate to data URL with the HTML
        // We need a real HTTP page for fetch() to work, so serve it
        const htmlPath = path.join(distPath, '_test.html');
        fs.writeFileSync(htmlPath, html);

        await page.goto(`${baseUrl}/_test.html`, { waitUntil: 'domcontentloaded' });
        console.log('Page loaded, waiting for playback...');

        // Poll tick count and capture frames
        let maxSprites = 0;
        let lastFrame = 0;
        let captures = 0;
        let hotelReached = false;
        const startTime = Date.now();

        for (let i = 0; i < MAX_TICKS; i++) {
            // Wait 100ms between polls (fast-loop runs much faster in-browser)
            await new Promise(r => setTimeout(r, 100));

            // Read test state from page
            const state = await page.evaluate(() => {
                var s = window._testState || {};
                // Also read sprite count from last frame info
                var el = document.getElementById('stage');
                return {
                    tick: s.tick || 0,
                    frame: s.frame || 0,
                    error: s.error,
                    done: s.done,
                };
            });

            if (state.error) {
                console.error('Error from player: ' + state.error);
            }

            lastFrame = state.frame;

            // Read sprite count from worker logs
            const recentLogs = logs.slice(-20);
            for (const log of recentLogs) {
                const m = log.match(/sprites=(\d+)/);
                if (m) {
                    const sc = parseInt(m[1]);
                    if (sc > maxSprites) {
                        maxSprites = sc;
                        console.log(`  [tick ~${state.tick}] sprites=${maxSprites} frame=${lastFrame}`);
                    }
                }
            }

            // Capture PNG periodically
            if (i > 0 && i % CAPTURE_INTERVAL === 0) {
                const pngPath = path.join(outputDir, `frame_${String(i).padStart(4, '0')}.png`);
                await page.screenshot({ path: pngPath, clip: { x: 0, y: 0, width: 720, height: 540 } });
                captures++;
                console.log(`  Captured ${pngPath} (tick ~${state.tick}, frame ${lastFrame})`);
            }

            // Check if hotel view reached
            if (maxSprites >= HOTEL_SPRITE_THRESHOLD && !hotelReached) {
                hotelReached = true;
                const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
                console.log(`  Hotel view reached! (${maxSprites} sprites in ${elapsed}s)`);

                // Capture the hotel frame
                const pngPath = path.join(outputDir, 'hotel_view.png');
                await page.screenshot({ path: pngPath, clip: { x: 0, y: 0, width: 720, height: 540 } });
                captures++;
                console.log(`  Captured ${pngPath}`);
            }

            // If hotel reached and we've done a few more ticks, we're done
            if (hotelReached && i > 10) {
                // Run a few more polls for additional captures
                for (let j = 0; j < 3; j++) {
                    await new Promise(r => setTimeout(r, 2000));
                    const pngPath = path.join(outputDir, `frame_final_${j}.png`);
                    await page.screenshot({ path: pngPath, clip: { x: 0, y: 0, width: 720, height: 540 } });
                    captures++;
                    console.log(`  Captured ${pngPath}`);
                }
                break;
            }
        }

        // Final capture
        const finalPng = path.join(outputDir, 'frame_final.png');
        await page.screenshot({ path: finalPng, clip: { x: 0, y: 0, width: 720, height: 540 } });
        captures++;

        // Clean up temp HTML
        try { fs.unlinkSync(htmlPath); } catch(e) {}

        // Results
        console.log('\n--- Results ---');
        console.log('Max sprites:    ', maxSprites);
        console.log('Last frame:     ', lastFrame);
        console.log('Captures:       ', captures);
        console.log('Output dir:     ', outputDir);
        console.log('Elapsed:        ', ((Date.now() - startTime) / 1000).toFixed(1) + 's');

        if (maxSprites >= HOTEL_SPRITE_THRESHOLD && lastFrame > 0) {
            console.log('\nPASS (maxSprites=' + maxSprites + ', frame=' + lastFrame + ')');
        } else {
            console.log('\nFAIL: maxSprites=' + maxSprites + ', frame=' + lastFrame);
            process.exitCode = 1;
        }

    } finally {
        if (browser) await browser.close();
        server.close();
    }
}

main().catch(err => {
    console.error('FATAL:', err);
    process.exit(1);
});
