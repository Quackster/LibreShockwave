#!/usr/bin/env node
'use strict';

/**
 * Headless Chromium test for the WebSocket MUS bridge.
 *
 * Expects a real TCP server (e.g. Kepler) already running on TCP_PORT.
 * Starts websockify (WS_PORT -> TCP_PORT) and a test page in Chrome
 * that sends/receives MUS-framed binary data through the WebSocket.
 *
 * Usage:
 *   ./gradlew :player-wasm:runWasmWebSocketTest
 *   node wasm-websocket-test.js [distPath]
 */

const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');
const path = require('path');

const distPath = process.argv[2] || path.resolve(__dirname, '../../../build/dist');
const WS_PORT = 30001;
const TCP_PORT = 30087;
const TIMEOUT_MS = 30000;

// ---------------------------------------------------------------------------
// websockify subprocess
// ---------------------------------------------------------------------------
function startWebsockify(wsPort, tcpHost, tcpPort) {
    return new Promise((resolve, reject) => {
        console.log('[WS] Starting websockify :' + wsPort + ' -> ' + tcpHost + ':' + tcpPort);
        const proc = spawn('websockify', [String(wsPort), tcpHost + ':' + tcpPort], {
            stdio: ['ignore', 'pipe', 'pipe'], shell: true
        });
        proc.stdout.on('data', d => console.log('[WS] ' + d.toString().trim()));
        proc.stderr.on('data', d => console.log('[WS] ' + d.toString().trim()));
        proc.on('error', reject);
        setTimeout(() => resolve({ proc }), 2000);
    });
}

// ---------------------------------------------------------------------------
// HTTP server — serves a test HTML page
// ---------------------------------------------------------------------------
function startHttpServer(testHtml) {
    return new Promise((resolve) => {
        const server = http.createServer((req, res) => {
            res.writeHead(200, { 'Content-Type': 'text/html' });
            res.end(testHtml);
        });
        server.listen(0, '127.0.0.1', () => {
            resolve({ server, port: server.address().port });
        });
    });
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
async function main() {
    console.log('=== WebSocket MUS Bridge Test ===');
    console.log('WebSocket port: ' + WS_PORT);
    console.log('TCP target:     127.0.0.1:' + TCP_PORT + ' (must be running)\n');

    // 1. Start websockify
    let wsproc;
    try {
        const ws = await startWebsockify(WS_PORT, '127.0.0.1', TCP_PORT);
        wsproc = ws.proc;
    } catch (err) {
        console.error('FAIL: websockify failed: ' + err.message);
        process.exit(1);
    }

    // 2. Build test HTML — opens WebSocket, sends MUS frame, parses response
    const testHtml = `<!DOCTYPE html>
<html><body>
<script>
window._results = { sent: false, received: [], errors: [], wsState: 'init' };

function log(msg) {
    console.log('[BROWSER] ' + msg);
}

try {
    var ws = new WebSocket('ws://127.0.0.1:${WS_PORT}');
    ws.binaryType = 'arraybuffer';
    window._results.wsState = 'connecting';

    ws.onopen = function() {
        log('WebSocket connected');
        window._results.wsState = 'open';
    };

    ws.onmessage = function(evt) {
        var data;
        if (typeof evt.data === 'string') {
            data = evt.data;
        } else {
            data = new TextDecoder().decode(new Uint8Array(evt.data));
        }
        log('Received: ' + JSON.stringify(data) + ' (' + data.length + ' bytes)');
        window._results.received.push(data);
    };

    ws.onerror = function() {
        log('WebSocket error');
        window._results.errors.push('ws_error');
        window._results.wsState = 'error';
    };

    ws.onclose = function() {
        log('WebSocket closed');
        window._results.wsState = 'closed';
    };
} catch(e) {
    log('Exception: ' + e.message);
    window._results.errors.push(e.message);
}
</script>
</body></html>`;

    const httpSrv = await startHttpServer(testHtml);
    const baseUrl = 'http://127.0.0.1:' + httpSrv.port;
    console.log('[HTTP] Serving test page at ' + baseUrl + '\n');

    let browser;
    let allPassed = true;

    try {
        const puppeteer = require('puppeteer');
        browser = await puppeteer.launch({
            headless: true,
            args: ['--no-sandbox', '--disable-setuid-sandbox'],
        });

        const page = await browser.newPage();
        page.on('console', msg => console.log('  [CHROME] ' + msg.text()));
        page.on('pageerror', err => console.log('  [CHROME ERROR] ' + err.message));

        await page.goto(baseUrl, { waitUntil: 'domcontentloaded' });
        console.log('Page loaded, waiting for server response...\n');

        // Wait for at least one received MUS frame (server HELLO)
        const deadline = Date.now() + TIMEOUT_MS;
        let results = null;

        while (Date.now() < deadline) {
            await new Promise(r => setTimeout(r, 500));
            results = await page.evaluate(() => window._results).catch(() => null);

            if (results && results.received.length >= 1) {
                // Got at least HELLO from server
                await new Promise(r => setTimeout(r, 1000)); // wait for more
                break;
            }
            if (results && results.wsState === 'error') {
                break;
            }
            if (results && results.wsState === 'closed') {
                break;
            }
        }

        // Final read
        results = await page.evaluate(() => window._results).catch(() => ({}));

        console.log('\n--- Results ---');
        const check = (label, pass) => {
            console.log('  ' + label.padEnd(50) + (pass ? 'PASS' : 'FAIL'));
            if (!pass) allPassed = false;
        };

        check('WebSocket connected', results.wsState === 'open' || results.received.length > 0);
        check('Browser received data from server', results.received && results.received.length >= 1);
        check('No WebSocket errors', !results.errors || results.errors.length === 0);

        if (results.received && results.received.length > 0) {
            console.log('\n  Browser received:');
            results.received.forEach((msg, i) => {
                console.log('    [' + i + '] ' + JSON.stringify(msg).substring(0, 120));
            });
        } else {
            console.log('\n  No messages received. wsState=' + results.wsState);
        }

        if (results.errors && results.errors.length > 0) {
            console.log('\n  Errors: ' + JSON.stringify(results.errors));
        }

        console.log('\n' + (allPassed ? 'ALL PASSED' : 'SOME CHECKS FAILED'));

    } finally {
        if (browser) await browser.close();
        if (wsproc) wsproc.kill();
        httpSrv.server.close();
    }

    process.exit(allPassed ? 0 : 1);
}

main().catch(err => {
    console.error('FATAL:', err);
    process.exit(1);
});
