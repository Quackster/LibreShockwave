#!/usr/bin/env node
'use strict';

/**
 * Headless Chromium test: scrollbar arrow direction bug.
 * Navigates to TOS page, scrolls to bottom, captures screenshots.
 *
 * Usage: node wasm-scroll-test.js [distPath] [dcrFile] [castDir] [outputDir]
 */

const puppeteer = require('puppeteer');
const http = require('http');
const fs = require('fs');
const path = require('path');

const distPath  = process.argv[2] || path.resolve(__dirname, '../../../build/dist');
const dcrFile   = process.argv[3] || 'C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr';
const castDir   = process.argv[4] || 'C:/xampp/htdocs/dcr/14.1_b8';
const outputDir = process.argv[5] || path.resolve(process.cwd(), 'frames_scroll');

const MIME = {
    '.html': 'text/html', '.js': 'application/javascript', '.wasm': 'application/wasm',
    '.css': 'text/css', '.png': 'image/png', '.dcr': 'application/x-director',
    '.cct': 'application/x-director', '.cst': 'application/x-director', '.txt': 'text/plain',
};

function createServer() {
    return new Promise((resolve) => {
        const server = http.createServer((req, res) => {
            const url = decodeURIComponent(req.url.split('?')[0]);
            let filePath = path.join(distPath, url);
            if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) return serveFile(res, filePath);
            filePath = path.join(castDir, path.basename(url));
            if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) return serveFile(res, filePath);
            let ancestor = castDir;
            for (let i = 0; i < 3; i++) {
                ancestor = path.dirname(ancestor);
                const p = path.join(ancestor, url);
                if (fs.existsSync(p) && fs.statSync(p).isFile()) return serveFile(res, p);
            }
            res.writeHead(404); res.end('Not found');
        });
        server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
    });
}

function serveFile(res, filePath) {
    const ext = path.extname(filePath).toLowerCase();
    const data = fs.readFileSync(filePath);
    res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream', 'Content-Length': data.length, 'Access-Control-Allow-Origin': '*' });
    res.end(data);
}

async function captureCanvas(page, filePath) {
    const dataUrl = await page.evaluate(() => document.getElementById('stage')?.toDataURL('image/png'));
    if (dataUrl) fs.writeFileSync(filePath, Buffer.from(dataUrl.replace(/^data:image\/png;base64,/, ''), 'base64'));
}

async function clickCanvas(page, x, y) {
    await page.evaluate((x, y) => {
        const c = document.getElementById('stage');
        const r = c.getBoundingClientRect();
        const sx = r.width / c.width, sy = r.height / c.height;
        const cx = r.left + x * sx, cy = r.top + y * sy;
        c.dispatchEvent(new MouseEvent('mousedown', { clientX: cx, clientY: cy, button: 0, bubbles: true }));
        c.dispatchEvent(new MouseEvent('mouseup', { clientX: cx, clientY: cy, button: 0, bubbles: true }));
    }, x, y);
}

async function measureColorVariety(page, x, y, w, h) {
    return page.evaluate((rx, ry, rw, rh) => {
        const c = document.getElementById('stage');
        const data = c.getContext('2d').getImageData(rx, ry, rw, rh).data;
        const b = new Set();
        for (let i = 0; i < data.length; i += 16) b.add(((data[i]>>5)<<6)|((data[i+1]>>5)<<3)|(data[i+2]>>5));
        return b.size;
    }, x, y, w, h);
}

async function sampleRegion(page, x, y, w, h) {
    return page.evaluate((rx, ry, rw, rh) => {
        const data = document.getElementById('stage').getContext('2d').getImageData(rx, ry, rw, rh).data;
        const s = []; for (let i = 0; i < data.length; i += 16) s.push(data[i], data[i+1], data[i+2]); return s;
    }, x, y, w, h);
}

function changeFraction(a, b) {
    if (!a || !b || a.length !== b.length) return 1;
    let c = 0; const n = a.length / 3;
    for (let i = 0; i < a.length; i += 3) if (Math.abs(a[i]-b[i]) + Math.abs(a[i+1]-b[i+1]) + Math.abs(a[i+2]-b[i+2]) > 30) c++;
    return c / n;
}

async function main() {
    console.log('=== WASM Scroll Test ===');
    fs.mkdirSync(outputDir, { recursive: true });
    const { server, port } = await createServer();
    const baseUrl = `http://127.0.0.1:${port}`;
    const dcrFileName = path.basename(dcrFile);

    let browser;
    try {
        browser = await puppeteer.launch({ headless: true, args: ['--no-sandbox'] });
        const page = await browser.newPage();
        const debugLines = [];
        page.on('console', msg => {
            const t = msg.text();
            if (t.includes('[TEST]')) console.log('  [page] ' + t);
            if (t.includes('[DEBUG-') || t.includes('[wasm]')) { console.log('  ' + t.trim()); debugLines.push(t.trim()); }
        });

        const html = `<!DOCTYPE html><html><body>
<canvas id="stage" width="720" height="540"></canvas>
<script src="${baseUrl}/libreshockwave.js"><\/script>
<script>
var _ts = { tick: 0, frame: 0, loaded: false };
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
    onLoad: function(i) { _ts.loaded = true; console.log('[TEST] loaded ' + i.width + 'x' + i.height); },
    onFrame: function(f) { _ts.tick++; _ts.frame = f; },
    onError: function(m) { console.error('[TEST] Error: ' + m); },
    onDebugLog: function(msg) { console.log('  [wasm] ' + msg.trim()); }
});
player.load('${baseUrl}/${dcrFileName}');
<\/script></body></html>`;
        const htmlPath = path.join(distPath, '_test_scroll.html');
        fs.writeFileSync(htmlPath, html);
        await page.goto(`${baseUrl}/_test_scroll.html`, { waitUntil: 'domcontentloaded' });

        // Wait for login screen
        for (let i = 0; i < 300; i++) {
            await new Promise(r => setTimeout(r, 100));
            const s = await page.evaluate(() => _ts);
            if (s.loaded && s.frame >= 10 && s.tick >= 20) {
                const ok = await page.evaluate(() => {
                    const d = document.getElementById('stage').getContext('2d').getImageData(0,0,720,540).data;
                    let n=0; for(let i=0;i<d.length;i+=40) if(d[i]>10||d[i+1]>10||d[i+2]>10) n++;
                    return n>100;
                });
                if (ok) { console.log('Login ready'); break; }
            }
        }
        await new Promise(r => setTimeout(r, 2000));

        // Capture login screen (for "first time here?" font color bug)
        await captureCanvas(page, path.join(outputDir, '00_login_screen.png'));
        console.log('Saved: 00_login_screen.png');

        // Click "create one here" (the original working coordinate from registration test)
        console.log('Clicking "create one here" at (485, 161)...');
        await clickCanvas(page, 485, 161);
        await new Promise(r => setTimeout(r, 3000));

        // Wait for dialog content
        let loaded = false;
        for (let i = 0; i < 20; i++) {
            await new Promise(r => setTimeout(r, 2000));
            const v = await measureColorVariety(page, 432, 108, 252, 360);
            console.log(`Dialog content: variety=${v}`);
            if (v >= 15) { loaded = true; break; }
        }
        if (!loaded) { console.log('FAIL: Dialog content did not load'); process.exitCode = 1; return; }
        console.log('Dialog content loaded');

        // Capture state after dialog loads
        await captureCanvas(page, path.join(outputDir, '00b_after_create_click.png'));
        console.log('Saved: 00b_after_create_click.png');

        // Click "I am 11 or older"
        console.log('Clicking "I am 11 or older" at (617, 458)...');
        await clickCanvas(page, 617, 458);
        await new Promise(r => setTimeout(r, 5000));

        // Capture TOS page before scrolling
        await captureCanvas(page, path.join(outputDir, '01_tos_before_scroll.png'));
        console.log('Saved: 01_tos_before_scroll.png');

        // Sample the text area before scrolling
        const textBefore = await sampleRegion(page, 396, 160, 250, 160);

        // Scroll down by clicking the scrollbar down arrow 25 times
        // Scrollbar bottom arrow is approximately at (650, 320)
        // First, probe to find exact scrollbar position
        const hitInfo = await page.evaluate(async (x, y) => player.debugHitTest(x, y), 650, 320);
        console.log(`Hit test at scrollbar (650,320): hit=${hitInfo.hit}`);

        for (let i = 0; i < 25; i++) {
            await clickCanvas(page, 650, 320);
            await new Promise(r => setTimeout(r, 200));
        }
        await new Promise(r => setTimeout(r, 500));

        const textAfter = await sampleRegion(page, 396, 160, 250, 160);
        const change = changeFraction(textBefore, textAfter);
        console.log(`Text area change after scrolling: ${(change * 100).toFixed(1)}%`);

        // Capture TOS page after scrolling to bottom
        await captureCanvas(page, path.join(outputDir, '02_tos_scrolled_bottom.png'));
        console.log('Saved: 02_tos_scrolled_bottom.png');

        if (change < 0.02) {
            console.log('FAIL: Scrollbar did not work - no text change');
            process.exitCode = 1;
        } else {
            console.log('PASS: Scrollbar scroll-down works! Text area changed by ' + (change * 100).toFixed(1) + '%');
        }

        // === Test scrolling back up after reaching bottom ===
        console.log('\n--- Testing scroll-back-up after bottom ---');
        const textAtBottom = await sampleRegion(page, 396, 160, 250, 160);

        // Try clicking the UP arrow (top of scrollbar, approximately (650, 170))
        console.log('Clicking scroll UP arrow at (650, 170) 10 times...');
        for (let i = 0; i < 10; i++) {
            await clickCanvas(page, 650, 170);
            await new Promise(r => setTimeout(r, 200));
        }
        await new Promise(r => setTimeout(r, 500));

        const textAfterUpClick = await sampleRegion(page, 396, 160, 250, 160);
        const upClickChange = changeFraction(textAtBottom, textAfterUpClick);
        console.log(`Text change after clicking UP arrow: ${(upClickChange * 100).toFixed(1)}%`);
        await captureCanvas(page, path.join(outputDir, '03_after_up_click.png'));

        if (upClickChange < 0.02) {
            console.log('FAIL: Scroll UP arrow did not work after reaching bottom');
        } else {
            console.log('PASS: Scroll UP arrow works after reaching bottom');
        }

        // === Test drag: mousedown on lift, move up, mouseup ===
        // First scroll back to bottom
        for (let i = 0; i < 25; i++) {
            await clickCanvas(page, 650, 320);
            await new Promise(r => setTimeout(r, 150));
        }
        await new Promise(r => setTimeout(r, 500));
        const textBeforeDrag = await sampleRegion(page, 396, 160, 250, 160);
        await captureCanvas(page, path.join(outputDir, '04_before_drag.png'));
        console.log('Scrolled back to bottom for drag test');

        // Try to drag the lift from near bottom to middle
        // Lift should be near bottom of scrollbar (~315). Drag it to ~240.
        console.log('Attempting lift drag from (650,310) to (650,240)...');
        await page.evaluate((x1, y1, x2, y2) => {
            const c = document.getElementById('stage');
            const r = c.getBoundingClientRect();
            const sx = r.width / c.width, sy = r.height / c.height;
            // mouseDown at start position
            c.dispatchEvent(new MouseEvent('mousedown', {
                clientX: r.left + x1*sx, clientY: r.top + y1*sy, button: 0, bubbles: true
            }));
        }, 650, 310, 650, 240);
        await new Promise(r => setTimeout(r, 100));

        // Simulate drag by dispatching mousemove events
        for (let y = 310; y >= 240; y -= 5) {
            await page.evaluate((x, y) => {
                const c = document.getElementById('stage');
                const r = c.getBoundingClientRect();
                const sx = r.width / c.width, sy = r.height / c.height;
                c.dispatchEvent(new MouseEvent('mousemove', {
                    clientX: r.left + x*sx, clientY: r.top + y*sy, button: 0, bubbles: true
                }));
            }, 650, y);
            await new Promise(r => setTimeout(r, 50));
        }
        await new Promise(r => setTimeout(r, 100));

        // mouseUp
        await page.evaluate((x, y) => {
            const c = document.getElementById('stage');
            const r = c.getBoundingClientRect();
            const sx = r.width / c.width, sy = r.height / c.height;
            c.dispatchEvent(new MouseEvent('mouseup', {
                clientX: r.left + x*sx, clientY: r.top + y*sy, button: 0, bubbles: true
            }));
        }, 650, 240);
        await new Promise(r => setTimeout(r, 500));

        const textAfterDrag = await sampleRegion(page, 396, 160, 250, 160);
        const dragChange = changeFraction(textBeforeDrag, textAfterDrag);
        console.log(`Text change after drag: ${(dragChange * 100).toFixed(1)}%`);
        await captureCanvas(page, path.join(outputDir, '05_after_drag.png'));

        if (dragChange < 0.02) {
            console.log('FAIL: Lift drag did not work after reaching bottom');
        } else {
            console.log('PASS: Lift drag works after reaching bottom');
        }

        // === Test: drag again after releasing drag (the "click twice" bug) ===
        console.log('\n--- Testing re-drag after releasing lift ---');
        // First scroll back to bottom
        for (let i = 0; i < 30; i++) {
            await clickCanvas(page, 650, 320);
            await new Promise(r => setTimeout(r, 150));
        }
        await new Promise(r => setTimeout(r, 500));
        const textBeforeReDrag = await sampleRegion(page, 396, 160, 250, 160);
        await captureCanvas(page, path.join(outputDir, '06_before_redrag.png'));
        console.log('At bottom, attempting single-click drag...');

        // Do a complete drag (mousedown → mousemove → mouseup) — just 1 pixel to trigger the state
        await page.evaluate((x, y) => {
            const c = document.getElementById('stage');
            const r = c.getBoundingClientRect();
            const sx = r.width / c.width, sy = r.height / c.height;
            c.dispatchEvent(new MouseEvent('mousedown', {
                clientX: r.left + x*sx, clientY: r.top + y*sy, button: 0, bubbles: true
            }));
        }, 650, 310);
        await new Promise(r => setTimeout(r, 100));
        await page.evaluate((x, y) => {
            const c = document.getElementById('stage');
            const r = c.getBoundingClientRect();
            const sx = r.width / c.width, sy = r.height / c.height;
            c.dispatchEvent(new MouseEvent('mousemove', {
                clientX: r.left + x*sx, clientY: r.top + y*sy, button: 0, bubbles: true
            }));
        }, 650, 308);
        await new Promise(r => setTimeout(r, 100));
        await page.evaluate((x, y) => {
            const c = document.getElementById('stage');
            const r = c.getBoundingClientRect();
            const sx = r.width / c.width, sy = r.height / c.height;
            c.dispatchEvent(new MouseEvent('mouseup', {
                clientX: r.left + x*sx, clientY: r.top + y*sy, button: 0, bubbles: true
            }));
        }, 650, 308);
        await new Promise(r => setTimeout(r, 500));
        console.log('Released first drag. Now trying SECOND drag (single click)...');

        // Now try a SECOND drag — this is where the bug manifests.
        // With the old code, the first click would be "swallowed" by the still-active
        // invisible Event Agent sprite, requiring a second click.
        const textBeforeSecondDrag = await sampleRegion(page, 396, 160, 250, 160);
        await page.evaluate((x, y) => {
            const c = document.getElementById('stage');
            const r = c.getBoundingClientRect();
            const sx = r.width / c.width, sy = r.height / c.height;
            c.dispatchEvent(new MouseEvent('mousedown', {
                clientX: r.left + x*sx, clientY: r.top + y*sy, button: 0, bubbles: true
            }));
        }, 650, 308);
        await new Promise(r => setTimeout(r, 100));

        // Drag up significantly
        for (let y = 308; y >= 220; y -= 5) {
            await page.evaluate((x, y) => {
                const c = document.getElementById('stage');
                const r = c.getBoundingClientRect();
                const sx = r.width / c.width, sy = r.height / c.height;
                c.dispatchEvent(new MouseEvent('mousemove', {
                    clientX: r.left + x*sx, clientY: r.top + y*sy, button: 0, bubbles: true
                }));
            }, 650, y);
            await new Promise(r => setTimeout(r, 50));
        }
        await new Promise(r => setTimeout(r, 100));
        await page.evaluate((x, y) => {
            const c = document.getElementById('stage');
            const r = c.getBoundingClientRect();
            const sx = r.width / c.width, sy = r.height / c.height;
            c.dispatchEvent(new MouseEvent('mouseup', {
                clientX: r.left + x*sx, clientY: r.top + y*sy, button: 0, bubbles: true
            }));
        }, 650, 220);
        await new Promise(r => setTimeout(r, 500));

        const textAfterReDrag = await sampleRegion(page, 396, 160, 250, 160);
        const reDragChange = changeFraction(textBeforeSecondDrag, textAfterReDrag);
        console.log(`Text change after re-drag: ${(reDragChange * 100).toFixed(1)}%`);
        await captureCanvas(page, path.join(outputDir, '07_after_redrag.png'));

        if (reDragChange < 0.02) {
            console.log('FAIL: Re-drag did not work (click-twice bug)');
            process.exitCode = 1;
        } else {
            console.log('PASS: Re-drag works on single click after releasing');
        }

        // Save debug lines
        if (debugLines.length > 0) {
            fs.writeFileSync(path.join(outputDir, 'debug_log.txt'), debugLines.join('\n'));
            console.log(`Saved ${debugLines.length} debug lines to debug_log.txt`);
        }

        try { fs.unlinkSync(htmlPath); } catch(e) {}
    } finally {
        if (browser) await browser.close();
        server.close();
    }
}

main().catch(err => { console.error('FATAL:', err); process.exit(1); });
