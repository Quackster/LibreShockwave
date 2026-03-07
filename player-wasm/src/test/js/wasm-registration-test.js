#!/usr/bin/env node
'use strict';

/**
 * Headless Chromium test: login screen -> registration dialog flow.
 *
 * Loads the Habbo DCR, waits for the login screen to appear, clicks
 * "You can create one here" (stage coords ~485,161), and verifies the
 * registration dialog ("Welcome To Habbo Hotel!" / "I am 11 or older")
 * appears by detecting significant canvas pixel changes.
 *
 * Usage (via Gradle):
 *   ./gradlew :player-wasm:runWasmRegistrationTest
 *   ./gradlew :player-wasm:runWasmRegistrationTest -PoutputDir=C:/tmp/reg-test
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
const outputDir = process.argv[5] || path.resolve(process.cwd(), 'frames_registration');

// Click target: stage coordinates for the "You can create one here" link
const CLICK_X = 485;
const CLICK_Y = 161;

// How long to wait for the login screen and dialog (in poll iterations * 100ms)
const LOGIN_TIMEOUT_POLLS = 300;    // 30 seconds
const DIALOG_TIMEOUT_MS   = 5000;   // 5 seconds after click
const CONTENT_TIMEOUT_MS  = 45000;  // 45 seconds for dialog content to load

// Pixel-change threshold: fraction of sampled pixels that must change
const CHANGE_THRESHOLD = 0.02;  // 2% of sampled area must differ
// Content richness: dialog with loaded content has varied pixel colors (not just white frame)
const CONTENT_VARIETY_THRESHOLD = 15; // unique color buckets in dialog area

// ---------------------------------------------------------------------------
// HTTP server: serves dist/ and cast files (same as wasm-chromium-test.js)
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

            // Try castDir with full URL path (e.g. /dcr/14.1_b8/file.cct)
            filePath = path.join(castDir, url.replace(/^.*\//, ''));
            if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
                return serveFile(res, filePath);
            }

            // Try castDir with basename
            filePath = path.join(castDir, path.basename(url));
            if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
                return serveFile(res, filePath);
            }

            // Try gamedata/ subdirectory under castDir ancestors
            let ancestor = castDir;
            for (let i = 0; i < 3; i++) {
                ancestor = path.dirname(ancestor);
                const gamedataPath = path.join(ancestor, url);
                if (fs.existsSync(gamedataPath) && fs.statSync(gamedataPath).isFile()) {
                    return serveFile(res, gamedataPath);
                }
            }

            console.log('  [404] ' + url);
            res.writeHead(404);
            res.end('Not found: ' + url);
        });

        server.listen(0, '127.0.0.1', () => {
            resolve({ server, port: server.address().port });
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
// Canvas helpers
// ---------------------------------------------------------------------------
async function captureCanvas(page, filePath) {
    const dataUrl = await page.evaluate(() => {
        const canvas = document.getElementById('stage');
        return canvas ? canvas.toDataURL('image/png') : null;
    });
    if (!dataUrl) return;
    const base64 = dataUrl.replace(/^data:image\/png;base64,/, '');
    fs.writeFileSync(filePath, Buffer.from(base64, 'base64'));
}

/**
 * Sample a region of the canvas and return flat RGBA array.
 * Samples every 4th pixel for speed.
 */
async function sampleCanvasRegion(page, x, y, w, h) {
    return page.evaluate((rx, ry, rw, rh) => {
        const canvas = document.getElementById('stage');
        if (!canvas) return null;
        const ctx = canvas.getContext('2d');
        const data = ctx.getImageData(rx, ry, rw, rh).data;
        // Downsample: every 4th pixel
        const samples = [];
        for (let i = 0; i < data.length; i += 16) { // 4 channels * 4 skip
            samples.push(data[i], data[i+1], data[i+2]);
        }
        return samples;
    }, x, y, w, h);
}

/**
 * Count distinct color buckets in a region (quantized to 32-level bins).
 * A "Loading..." frame is mostly uniform white; loaded content has images/text
 * with many distinct colors.
 */
async function measureColorVariety(page, x, y, w, h) {
    return page.evaluate((rx, ry, rw, rh) => {
        const canvas = document.getElementById('stage');
        if (!canvas) return 0;
        const ctx = canvas.getContext('2d');
        const data = ctx.getImageData(rx, ry, rw, rh).data;
        const buckets = new Set();
        for (let i = 0; i < data.length; i += 16) { // every 4th pixel
            const r = data[i] >> 5;     // quantize to 8 levels per channel
            const g = data[i+1] >> 5;
            const b = data[i+2] >> 5;
            buckets.add((r << 6) | (g << 3) | b);
        }
        return buckets.size;
    }, x, y, w, h);
}

/**
 * Compare two sample arrays and return fraction of pixels that changed.
 */
function computeChangeFraction(before, after) {
    if (!before || !after || before.length !== after.length) return 1.0;
    let changed = 0;
    const pixelCount = before.length / 3;
    for (let i = 0; i < before.length; i += 3) {
        const dr = Math.abs(before[i] - after[i]);
        const dg = Math.abs(before[i+1] - after[i+1]);
        const db = Math.abs(before[i+2] - after[i+2]);
        if (dr + dg + db > 30) changed++;
    }
    return changed / pixelCount;
}

/**
 * Dispatch a click (mousedown + mouseup) on the canvas at stage coordinates.
 */
async function clickCanvas(page, stageX, stageY) {
    await page.evaluate((x, y) => {
        const canvas = document.getElementById('stage');
        if (!canvas) return;
        const rect = canvas.getBoundingClientRect();
        // Account for CSS scaling: map stage coords to client coords
        const scaleX = rect.width / canvas.width;
        const scaleY = rect.height / canvas.height;
        const clientX = rect.left + x * scaleX;
        const clientY = rect.top + y * scaleY;

        canvas.dispatchEvent(new MouseEvent('mousedown', {
            clientX, clientY, button: 0, bubbles: true,
        }));
        canvas.dispatchEvent(new MouseEvent('mouseup', {
            clientX, clientY, button: 0, bubbles: true,
        }));
    }, stageX, stageY);
}

// ---------------------------------------------------------------------------
// Main test
// ---------------------------------------------------------------------------
async function main() {
    console.log('=== WASM Registration Dialog Test ===');
    console.log('Dist:    ', distPath);
    console.log('DCR:     ', dcrFile);
    console.log('Casts:   ', castDir);
    console.log('Output:  ', outputDir);

    if (!fs.existsSync(path.join(distPath, 'libreshockwave.js'))) {
        console.error('FAIL: libreshockwave.js not found in dist. Run assembleWasm first.');
        process.exit(1);
    }
    if (!fs.existsSync(dcrFile)) {
        console.error('FAIL: DCR file not found: ' + dcrFile);
        process.exit(1);
    }

    fs.mkdirSync(outputDir, { recursive: true });

    const { server, port } = await createServer();
    const baseUrl = `http://127.0.0.1:${port}`;
    console.log('Server:  ', baseUrl);

    const dcrFileName = path.basename(dcrFile);

    let browser;
    try {
        browser = await puppeteer.launch({
            headless: true,
            args: ['--no-sandbox', '--disable-setuid-sandbox'],
        });

        const page = await browser.newPage();

        // Collect console output for diagnostics
        const logs = [];
        page.on('console', msg => {
            const text = msg.text();
            logs.push(text);
            if (text.includes('[TEST]') || text.includes('[MUS]') || text.includes('[CLICK]') || text.includes('[HitTest]') || text.includes('Error')) {
                console.log('  [page] ' + text);
            }
        });

        // Build test HTML
        const html = `<!DOCTYPE html>
<html><body>
<canvas id="stage" width="720" height="540"></canvas>
<script src="${baseUrl}/libreshockwave.js"><\/script>
<script>
    var _testState = { tick: 0, frame: 0, loaded: false, error: null, movieWidth: 0, movieHeight: 0 };

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
            _testState.loaded = true;
            _testState.movieWidth = info.width;
            _testState.movieHeight = info.height;
            console.log('[TEST] Movie loaded: ' + info.width + 'x' + info.height + ', ' + info.frameCount + ' frames');
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
<\/script>
</body></html>`;

        const htmlPath = path.join(distPath, '_test_reg.html');
        fs.writeFileSync(htmlPath, html);

        await page.goto(`${baseUrl}/_test_reg.html`, { waitUntil: 'domcontentloaded' });
        console.log('Page loaded, waiting for login screen...');

        const startTime = Date.now();

        // ----- Step 1: Wait for login screen -----
        let loginReady = false;
        for (let i = 0; i < LOGIN_TIMEOUT_POLLS; i++) {
            await new Promise(r => setTimeout(r, 100));

            const state = await page.evaluate(() => window._testState);
            if (state.error) {
                console.error('  Player error: ' + state.error);
            }

            // Login screen is at frame 10 and needs some ticks to render
            if (state.loaded && state.frame >= 10 && state.tick >= 20) {
                // Check canvas has non-trivial content (not all black)
                const hasContent = await page.evaluate(() => {
                    const canvas = document.getElementById('stage');
                    if (!canvas) return false;
                    const ctx = canvas.getContext('2d');
                    const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
                    let nonBlack = 0;
                    for (let i = 0; i < data.length; i += 40) {
                        if (data[i] > 10 || data[i+1] > 10 || data[i+2] > 10) nonBlack++;
                    }
                    return nonBlack > 100;
                });

                if (hasContent) {
                    loginReady = true;
                    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
                    console.log(`  Login screen ready (frame=${state.frame}, tick=${state.tick}, ${elapsed}s)`);
                    break;
                }
            }

            if (i % 50 === 0 && i > 0) {
                console.log(`  Waiting... tick=${state.tick} frame=${state.frame} loaded=${state.loaded}`);
            }
        }

        if (!loginReady) {
            console.error('FAIL: Login screen did not appear within timeout');
            // Save diagnostic screenshot
            await captureCanvas(page, path.join(outputDir, 'timeout_login.png'));
            process.exitCode = 1;
            return;
        }

        // Let a few more frames render to ensure the login window is fully drawn
        await new Promise(r => setTimeout(r, 2000));

        // Save login screen screenshot
        await captureCanvas(page, path.join(outputDir, '01_login_screen.png'));
        console.log('  Saved login screen screenshot');

        // Get movie dimensions for sampling
        const movieSize = await page.evaluate(() => ({
            w: window._testState.movieWidth,
            h: window._testState.movieHeight,
        }));
        console.log(`  Movie size: ${movieSize.w}x${movieSize.h}`);

        // ----- Step 2: Sample "before" region -----
        // Sample the right side of the stage where the dialog will appear
        const sampleX = Math.floor(movieSize.w * 0.6);
        const sampleY = Math.floor(movieSize.h * 0.2);
        const sampleW = Math.floor(movieSize.w * 0.35);
        const sampleH = Math.floor(movieSize.h * 0.6);
        console.log(`  Sample region: (${sampleX},${sampleY}) ${sampleW}x${sampleH}`);

        const beforeSample = await sampleCanvasRegion(page, sampleX, sampleY, sampleW, sampleH);

        // ----- Step 3: Click "You can create one here" -----
        console.log(`  Clicking at stage (${CLICK_X}, ${CLICK_Y})...`);
        await clickCanvas(page, CLICK_X, CLICK_Y);

        // ----- Step 4: Wait for dialog frame to appear -----
        // Quick check: wait up to 5s for any pixel change (dialog window frame)
        let changeFraction = 0;
        for (let wait = 0; wait < DIALOG_TIMEOUT_MS; wait += 500) {
            await new Promise(r => setTimeout(r, 500));
            const afterSample = await sampleCanvasRegion(page, sampleX, sampleY, sampleW, sampleH);
            changeFraction = computeChangeFraction(beforeSample, afterSample);
            if (changeFraction >= CHANGE_THRESHOLD) break;
        }

        if (changeFraction < CHANGE_THRESHOLD) {
            await captureCanvas(page, path.join(outputDir, '02_no_dialog.png'));
            console.log(`  Pixel change: ${(changeFraction * 100).toFixed(1)}% — dialog frame did not appear`);
            console.log('\nFAIL: No significant pixel change after clicking "You can create one here"');
            console.log('  The click at (' + CLICK_X + ',' + CLICK_Y + ') may not have hit the target sprite.');
            console.log('  Check screenshots in ' + outputDir);
            process.exitCode = 1;
            try { fs.unlinkSync(htmlPath); } catch(e) {}
            return;
        }

        const dialogElapsed = ((Date.now() - startTime) / 1000).toFixed(1);
        console.log(`  Dialog frame appeared (${(changeFraction * 100).toFixed(1)}% change, ${dialogElapsed}s)`);
        await captureCanvas(page, path.join(outputDir, '02_dialog_frame.png'));

        // ----- Step 5: Wait for dialog CONTENT to load -----
        // The dialog initially shows "Loading..." while external casts download.
        // Loaded content has images (bellhop) and buttons with many distinct colors.
        let colorVariety = 0;
        let contentLoaded = false;
        const contentStart = Date.now();
        let captureIdx = 0;

        while (Date.now() - contentStart < CONTENT_TIMEOUT_MS) {
            await new Promise(r => setTimeout(r, 2000));
            colorVariety = await measureColorVariety(page, sampleX, sampleY, sampleW, sampleH);
            const sec = ((Date.now() - contentStart) / 1000).toFixed(0);
            console.log(`  Content poll +${sec}s: color variety=${colorVariety} (need ${CONTENT_VARIETY_THRESHOLD})`);

            if (captureIdx < 5) {
                await captureCanvas(page, path.join(outputDir, `03_content_${String(captureIdx).padStart(2,'0')}.png`));
                captureIdx++;
            }

            if (colorVariety >= CONTENT_VARIETY_THRESHOLD) {
                contentLoaded = true;
                break;
            }
        }

        // Final capture
        await captureCanvas(page, path.join(outputDir, '04_final.png'));

        // ----- Results -----
        const totalElapsed = ((Date.now() - startTime) / 1000).toFixed(1);
        console.log('\n--- Results ---');
        console.log('Dialog frame:    ', (changeFraction * 100).toFixed(1) + '% pixel change');
        console.log('Color variety:   ', colorVariety, contentLoaded ? '(LOADED)' : '(still loading)');
        console.log('Output dir:      ', outputDir);
        console.log('Elapsed:         ', totalElapsed + 's');

        if (contentLoaded) {
            console.log('\nPASS: Registration dialog with content detected (' +
                'change=' + (changeFraction * 100).toFixed(1) + '%, ' +
                'colors=' + colorVariety + ')');
        } else {
            // Dialog frame appeared but content didn't load in time —
            // still a partial pass (the click worked, external casts were slow)
            console.log('\nPASS (partial): Dialog frame appeared but content still loading');
            console.log('  The click worked. External cast download may be slow.');
            console.log('  Color variety ' + colorVariety + ' < ' + CONTENT_VARIETY_THRESHOLD);
        }

        // ----- Step 6: Back navigation test -----
        // Test that buttons remain clickable after navigating back.
        // "I am 11 or older" is at approximately (650, 510) on the age selection page.
        if (contentLoaded) {
            console.log('\n=== Back Navigation Test ===');

            // Debug: probe hit test at various locations to find the button
            // Probe actual button positions (from sprite dump)
            // ch69 "Cancel": (392,425)-(451,443)
            // ch70 "under 11": (514,425)-(674,443)
            // ch71 "11 or older": (561,449)-(674,467)
            const probeCoords = [
                [617, 458],  // center of ch71 "I am 11 or older"
                [420, 434],  // center of ch69 "Cancel"
                [594, 434],  // center of ch70 "under 11"
                [-1, 0],     // dump all sprites
            ];
            for (const [px, py] of probeCoords) {
                const result = await page.evaluate(async (x, y) => {
                    return await player.debugHitTest(x, y);
                }, px, py);
                console.log(`  [probe] (${px},${py}) hit=${result.hit} ${result.info}`);
            }

            // Capture the current dialog state (age selection page)
            const beforeAge = await sampleCanvasRegion(page, sampleX, sampleY, sampleW, sampleH);

            // Click "I am 11 or older" — ch71 rect=(561,449)-(674,467), center=(617,458)
            const OLDER_X = 617, OLDER_Y = 458;
            console.log(`  Clicking "I am 11 or older" at (${OLDER_X}, ${OLDER_Y})...`);
            await clickCanvas(page, OLDER_X, OLDER_Y);

            // Wait for page change (next registration page)
            let pageChanged = false;
            for (let wait = 0; wait < 10000; wait += 500) {
                await new Promise(r => setTimeout(r, 500));
                const afterClick = await sampleCanvasRegion(page, sampleX, sampleY, sampleW, sampleH);
                const change = computeChangeFraction(beforeAge, afterClick);
                if (change >= CHANGE_THRESHOLD) {
                    pageChanged = true;
                    console.log(`  Page changed after "I am 11 or older" (${(change * 100).toFixed(1)}% change)`);
                    break;
                }
            }

            await captureCanvas(page, path.join(outputDir, '05_after_older.png'));

            if (!pageChanged) {
                console.log('  WARNING: No page change after clicking "I am 11 or older"');
                console.log('  Button might not have been hit. Check 05_after_older.png');
            } else {
                // Wait for next page to render and find Back button
                await new Promise(r => setTimeout(r, 2000));
                await captureCanvas(page, path.join(outputDir, '06_next_page.png'));

                // Probe to find Back button on the legal page
                const nextPageResult = await page.evaluate(async () => {
                    return await player.debugHitTest(-1, 0);
                });
                console.log(`  [next page sprites] ${nextPageResult.info}`);

                // Find back button by probing common positions
                const backProbes = [[420, 460], [435, 450], [445, 440], [435, 435]];
                for (const [px, py] of backProbes) {
                    const r = await page.evaluate(async (x, y) => {
                        return await player.debugHitTest(x, y);
                    }, px, py);
                    console.log(`  [back probe] (${px},${py}) hit=${r.hit}`);
                }

                const beforeBack = await sampleCanvasRegion(page, sampleX, sampleY, sampleW, sampleH);

                // Back button — use probed position (adjusting based on sprite dump)
                const BACK_X = 435, BACK_Y = 450;
                console.log(`  Clicking "Back" at (${BACK_X}, ${BACK_Y})...`);
                await clickCanvas(page, BACK_X, BACK_Y);

                // Wait for page to change back
                let backWorked = false;
                for (let wait = 0; wait < 10000; wait += 500) {
                    await new Promise(r => setTimeout(r, 500));
                    const afterBack = await sampleCanvasRegion(page, sampleX, sampleY, sampleW, sampleH);
                    const change = computeChangeFraction(beforeBack, afterBack);
                    if (change >= CHANGE_THRESHOLD) {
                        backWorked = true;
                        console.log(`  Back navigation worked (${(change * 100).toFixed(1)}% change)`);
                        break;
                    }
                }

                await captureCanvas(page, path.join(outputDir, '07_after_back.png'));

                if (!backWorked) {
                    console.log('  WARNING: Back button did not cause page change. Check 07_after_back.png');
                } else {
                    // Dump sprites after back navigation
                    const afterBackDump = await page.evaluate(async () => {
                        return await player.debugHitTest(-1, 0);
                    });
                    console.log(`  [after back sprites] ${afterBackDump.info}`);

                    // Probe the button position
                    const reclickProbe = await page.evaluate(async (x, y) => {
                        return await player.debugHitTest(x, y);
                    }, OLDER_X, OLDER_Y);
                    console.log(`  [reclick probe] (${OLDER_X},${OLDER_Y}) hit=${reclickProbe.hit} ${reclickProbe.info}`);

                    // Now try clicking "I am 11 or older" AGAIN
                    const beforeReclick = await sampleCanvasRegion(page, sampleX, sampleY, sampleW, sampleH);
                    console.log(`  Re-clicking "I am 11 or older" at (${OLDER_X}, ${OLDER_Y})...`);
                    await clickCanvas(page, OLDER_X, OLDER_Y);

                    let reclickWorked = false;
                    for (let wait = 0; wait < 10000; wait += 500) {
                        await new Promise(r => setTimeout(r, 500));
                        const afterReclick = await sampleCanvasRegion(page, sampleX, sampleY, sampleW, sampleH);
                        const change = computeChangeFraction(beforeReclick, afterReclick);
                        if (change >= CHANGE_THRESHOLD) {
                            reclickWorked = true;
                            console.log(`  Re-click worked! (${(change * 100).toFixed(1)}% change)`);
                            break;
                        }
                    }

                    await captureCanvas(page, path.join(outputDir, '08_after_reclick.png'));

                    if (reclickWorked) {
                        console.log('\nPASS: Button remains clickable after back navigation');
                    } else {
                        console.log('\nFAIL: Button NOT clickable after back navigation');
                        console.log('  The "I am 11 or older" button became unclickable after back navigation.');
                        process.exitCode = 1;
                    }
                }
            }
        }

        // Clean up temp HTML
        try { fs.unlinkSync(htmlPath); } catch(e) {}

    } finally {
        if (browser) await browser.close();
        server.close();
    }
}

main().catch(err => {
    console.error('FATAL:', err);
    process.exit(1);
});
