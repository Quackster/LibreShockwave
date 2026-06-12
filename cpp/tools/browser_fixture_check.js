#!/usr/bin/env node
'use strict';

const fs = require('fs');
const http = require('http');
const path = require('path');

const repoRoot = path.resolve(__dirname, '../..');

function parseArgs(argv) {
    const result = {};
    for (let i = 0; i < argv.length; i++) {
        const arg = argv[i];
        if (!arg.startsWith('--')) {
            continue;
        }
        const eq = arg.indexOf('=');
        if (eq >= 0) {
            result[arg.substring(2, eq)] = arg.substring(eq + 1);
        } else {
            result[arg.substring(2)] = argv[i + 1];
            i++;
        }
    }
    return result;
}

function requireFromCandidates(name) {
    const candidates = [
        name,
        path.join(repoRoot, 'node_modules', name),
        path.join(repoRoot, 'player-wasm', 'node_modules', name),
    ];
    const errors = [];
    for (const candidate of candidates) {
        try {
            return require(candidate);
        } catch (error) {
            errors.push(`${candidate}: ${error.message}`);
        }
    }
    throw new Error(`Could not load ${name}. Tried:\n${errors.join('\n')}`);
}

const args = parseArgs(process.argv.slice(2));
const distPath = path.resolve(args.dist || path.join(repoRoot, 'cmake-build-wasm/cpp/wasm-dist'));
const fixtureRoot = path.resolve(args.fixtures || '/var/www/html');
const moviePath = args.movie || '/dcr0910/loader.dcr';
const inboundExpected = args.inbound || 'STATUS##';
const inboundReplyExpected = args.reply || 'STATUSOK';
const timeoutMs = Number(args.timeoutMs || 120000);
const screenshotPath = args.screenshot ? path.resolve(args.screenshot) : '';

const MIME = {
    '.html': 'text/html',
    '.js': 'application/javascript',
    '.wasm': 'application/wasm',
    '.dcr': 'application/x-director',
    '.cct': 'application/x-director',
    '.cst': 'application/x-director',
    '.dir': 'application/x-director',
    '.dxr': 'application/x-director',
    '.txt': 'text/plain',
};

function assertFile(filePath) {
    if (!fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
        throw new Error(`Required file not found: ${filePath}`);
    }
}

function sendHeaders(res, status, contentType, length) {
    const headers = {
        'Content-Type': contentType,
        'Access-Control-Allow-Origin': '*',
        'Cross-Origin-Opener-Policy': 'same-origin',
        'Cross-Origin-Embedder-Policy': 'require-corp',
        'Cross-Origin-Resource-Policy': 'same-origin',
    };
    if (length != null) {
        headers['Content-Length'] = length;
    }
    res.writeHead(status, headers);
}

function testHtml() {
    return `<!doctype html><html><body style="margin:0;background:#000">
<canvas id="stage" width="720" height="540"></canvas>
<script src="/libreshockwave-cpp-player.js"></script>
<script>
window.__state = { ready: false, loaded: false, errors: [], frames: 0, gotos: [] };
window.player = LibreShockwaveCppPlayer.create('stage', {
  basePath: '/',
  params: { 'websocket.mode': 'ws' },
  debugPlayback: true,
  scriptTimeoutMs: 1000,
  onReady: function() { window.__state.ready = true; },
  onLoad: function(info) { window.__state.loaded = true; window.__state.info = info; window.player.play(); },
  onFrame: function(frame) { window.__state.frames++; window.__state.lastFrame = frame; },
  onGotoNetMovie: function(url) {
    window.__state.gotos.push(String(url));
    window.player.load(new URL(url, window.player.loadedMovieUrl || location.href).href);
  },
  onError: function(message) { window.__state.errors.push(String(message)); console.error(message); }
});
</script></body></html>`;
}

function createFixtureServer() {
    const server = http.createServer((req, res) => {
        const requestPath = decodeURIComponent(req.url.split('?')[0]);
        if (requestPath === '/favicon.ico') {
            sendHeaders(res, 204, 'text/plain', 0);
            res.end();
            return;
        }
        if (requestPath === '/' || requestPath === '/test.html') {
            const body = Buffer.from(testHtml(), 'utf8');
            sendHeaders(res, 200, 'text/html', body.length);
            res.end(body);
            return;
        }
        const relative = requestPath.replace(/^\/+/, '');
        const candidates = [
            path.join(distPath, relative),
            path.join(fixtureRoot, relative),
        ];
        const filePath = candidates.find(candidate => fs.existsSync(candidate) && fs.statSync(candidate).isFile());
        if (!filePath) {
            const body = Buffer.from(`Not found: ${requestPath}`, 'utf8');
            sendHeaders(res, 404, 'text/plain', body.length);
            res.end(body);
            return;
        }
        const body = fs.readFileSync(filePath);
        sendHeaders(res, 200, MIME[path.extname(filePath).toLowerCase()] || 'application/octet-stream', body.length);
        res.end(body);
    });
    return new Promise(resolve => {
        server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
    });
}

function closeServer(server) {
    if (!server || !server.close) {
        return Promise.resolve();
    }
    return new Promise(resolve => server.close(() => resolve()));
}

async function createBrowser() {
    try {
        const puppeteer = requireFromCandidates('puppeteer');
        const browser = await puppeteer.launch({
            headless: true,
            args: ['--no-sandbox', '--disable-setuid-sandbox'],
        });
        return { browser, newPage: () => browser.newPage() };
    } catch (puppeteerError) {
        const { chromium } = requireFromCandidates('playwright');
        const browser = await chromium.launch({
            headless: true,
            executablePath: process.env.CHROME_PATH || undefined,
            args: ['--no-sandbox'],
        });
        return { browser, newPage: () => browser.newPage(), puppeteerError };
    }
}

function loginEvidenceReady(evidence) {
    return evidence.topLogo.nonBlack >= 1500 &&
        evidence.hero.nonBlack >= 20000 &&
        evidence.firstPanel.white >= 2000 &&
        evidence.firstPanel.blue >= 2000 &&
        evidence.firstPanel.black >= 500 &&
        evidence.loginPanel.white >= 11000 &&
        evidence.loginPanel.blue >= 12000;
}

async function waitForLoadedLogin(page) {
    const deadline = Date.now() + timeoutMs;
    let last = null;
    while (Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 250));
        last = await page.evaluate(() => {
            const canvas = document.getElementById('stage');
            const ctx = canvas.getContext('2d');
            const rgba = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
            let nonBlack = 0;
            const buckets = new Set();
            for (let p = 0; p < rgba.length; p += 80) {
                const r = rgba[p];
                const g = rgba[p + 1];
                const b = rgba[p + 2];
                if (r > 10 || g > 10 || b > 10) nonBlack++;
                buckets.add((r >> 5) + ',' + (g >> 5) + ',' + (b >> 5));
            }
            function count(x, y, w, h) {
                const region = ctx.getImageData(x, y, w, h).data;
                let black = 0, white = 0, blue = 0, nonBlackRegion = 0;
                for (let p = 0; p < region.length; p += 4) {
                    const r = region[p], g = region[p + 1], b = region[p + 2];
                    if (r < 35 && g < 35 && b < 35) black++;
                    if (r > 235 && g > 235 && b > 235) white++;
                    if (r > 60 && r < 160 && g > 100 && g < 190 && b > 130 && b < 215) blue++;
                    if (r > 10 || g > 10 || b > 10) nonBlackRegion++;
                }
                return { black, white, blue, nonBlack: nonBlackRegion };
            }
            return {
                state: {
                    loaded: window.__state.loaded,
                    errors: window.__state.errors.slice(),
                    frames: window.__state.frames,
                    gotos: window.__state.gotos.slice(),
                    frame: window.__state.lastFrame || null,
                    nonBlack,
                    colorBuckets: buckets.size,
                },
                evidence: {
                    topLogo: count(18, 18, 230, 120),
                    hero: count(0, 100, 360, 350),
                    firstPanel: count(437, 109, 214, 95),
                    loginPanel: count(437, 225, 214, 220),
                },
            };
        });
        if (last.state.errors.length > 0) {
            throw new Error(`Browser player error: ${last.state.errors.join('\n')}`);
        }
        if (last.state.loaded &&
                last.state.gotos.includes('habbo_entry.dcr') &&
                last.state.nonBlack > 2000 &&
                last.state.colorBuckets > 30 &&
                loginEvidenceReady(last.evidence)) {
            return last;
        }
    }
    throw new Error(`Habbo login screen was not visible before timeout: ${JSON.stringify(last, null, 2)}`);
}

async function waitForFixtureHandlers(page) {
    const deadline = Date.now() + timeoutMs;
    let diagnostics = '';
    while (Date.now() < deadline) {
        diagnostics = await page.evaluate(async () => window.player.getScriptDiagnostics());
        if (diagnostics.includes('handler Logon=yes') &&
                diagnostics.includes('sendFuseMsg=yes') &&
                diagnostics.includes('DefaultMessageHandler=yes')) {
            return diagnostics;
        }
        await new Promise(resolve => setTimeout(resolve, 250));
    }
    throw new Error(`Fixture script handlers were not visible before timeout:\n${diagnostics}`);
}

async function main() {
    assertFile(path.join(distPath, 'libreshockwave-cpp-player.js'));
    assertFile(path.join(distPath, 'libreshockwave-cpp-worker.js'));
    assertFile(path.join(distPath, 'libreshockwave-cpp-wasm.js'));
    assertFile(path.join(distPath, 'libreshockwave-cpp-wasm.wasm'));
    assertFile(path.join(fixtureRoot, moviePath.replace(/^\/+/, '')));

    const WebSocket = requireFromCandidates('ws');
    const { server, port: httpPort } = await createFixtureServer();
    const fixtureWss = new WebSocket.Server({ host: '127.0.0.1', port: 0 });
    const smusWss = new WebSocket.Server({ host: '127.0.0.1', port: 0 });
    await Promise.all([
        new Promise(resolve => fixtureWss.once('listening', resolve)),
        new Promise(resolve => smusWss.once('listening', resolve)),
    ]);
    const fixtureWsPort = fixtureWss.address().port;
    const smusWsPort = smusWss.address().port;
    const fixtureReceived = [];
    const smusReceived = [];
    fixtureWss.on('connection', socket => {
        let sentInbound = false;
        socket.on('message', data => {
            const text = Buffer.from(data).toString('binary');
            fixtureReceived.push(text);
            if (!sentInbound) {
                sentInbound = true;
                socket.send(inboundExpected);
            }
        });
    });
    smusWss.on('connection', socket => {
        socket.on('message', data => {
            const bytes = Buffer.from(data);
            smusReceived.push(bytes);
            if (socket.readyState === WebSocket.OPEN) {
                socket.send(bytes);
            }
        });
    });

    let browser;
    try {
        const loadedBrowser = await createBrowser();
        browser = loadedBrowser.browser;
        const page = await loadedBrowser.newPage();
        const pageErrors = [];
        page.on('pageerror', error => pageErrors.push(error.message || String(error)));
        await page.goto(`http://127.0.0.1:${httpPort}/test.html`, { waitUntil: 'load', timeout: timeoutMs });
        await page.waitForFunction(() => window.__state && window.__state.ready, { timeout: timeoutMs });
        await page.evaluate(url => window.player.load(url), moviePath);
        const visual = await waitForLoadedLogin(page);
        if (pageErrors.length > 0) {
            throw new Error(`Browser page errors:\n${pageErrors.join('\n')}`);
        }
        const diagnostics = await waitForFixtureHandlers(page);
        const multiuser = await page.evaluate(async options => {
            return window.player.runFixtureMultiuserScriptSelfTest(options);
        }, {
            host: '127.0.0.1',
            port: fixtureWsPort,
            message: inboundReplyExpected,
            inboundExpected,
            inboundReplyExpected,
            requireInbound: true,
            timeoutMs: Math.min(timeoutMs, 10000),
        });
        if (!multiuser || !multiuser.ok) {
            throw new Error(`Fixture Multiuser diagnostic failed: ${JSON.stringify(multiuser, null, 2)}`);
        }
        const smus = await page.evaluate(async options => {
            return window.player.runCxxSmusBridgeSelfTest(options);
        }, {
            host: '127.0.0.1',
            port: smusWsPort,
            sender: 'browser-fixture-check',
            subject: 'CHAT',
            content: 'hello-smus',
            messages: [
                { sender: 'browser-fixture-check', subject: 'CHAT', content: 'hello-smus-1' },
                { sender: 'browser-fixture-check', subject: 'CHAT', content: 'hello-smus-2' },
            ],
            timeoutMs: Math.min(timeoutMs, 10000),
        });
        if (!smus || !smus.ok) {
            throw new Error(`C++ SMUS bridge diagnostic failed: ${JSON.stringify(smus, null, 2)}`);
        }
        if (screenshotPath) {
            await page.screenshot({ path: screenshotPath });
        }
        console.log(JSON.stringify({
            ok: true,
            movie: moviePath,
            distPath,
            fixtureRoot,
            frames: visual.state.frames,
            gotoNetMovies: visual.state.gotos,
            lastFrame: visual.state.frame,
            evidence: visual.evidence,
            scriptHandlers: diagnostics.split('\n')[0],
            multiuser: {
                connected: multiuser.connected,
                scriptSendSent: multiuser.scriptSendSent,
                inboundReceived: multiuser.inboundReceived,
                handlerTickedAfterInbound: multiuser.handlerTickedAfterInbound,
                inboundReplySent: multiuser.inboundReplySent,
                inboundHandled: multiuser.inboundHandled,
            },
            smus: {
                connected: smus.connected,
                cppConnected: smus.cppConnected,
                logonQueued: smus.logonQueued,
                logonSent: smus.logonSent,
                smusSendQueued: smus.smusSendQueued,
                smusSendSent: smus.smusSendSent,
                smusSendCount: smus.smusSendCount,
                smusSequenceComplete: smus.smusSequenceComplete,
                connectedBetweenMessages: smus.connectedBetweenMessages,
                cppReceived: smus.cppReceived,
                cppReceivedCount: smus.cppReceivedCount,
                disconnectRequested: smus.disconnectRequested,
                socketClosedAfterDisconnect: smus.socketClosedAfterDisconnect,
                errorDelivered: smus.errorDelivered,
            },
            serverReceivedCount: fixtureReceived.length,
            serverReceivedPreview: fixtureReceived.map(text => text.slice(0, 32)),
            fixtureServerReceivedCount: fixtureReceived.length,
            fixtureServerReceivedPreview: fixtureReceived.map(text => text.slice(0, 32)),
            smusServerReceivedCount: smusReceived.length,
            smusServerReceivedLengths: smusReceived.map(bytes => bytes.length),
            smusServerReceivedPreview: smusReceived.map(bytes => bytes.subarray(0, 16).toString('hex')),
            screenshot: screenshotPath || undefined,
        }, null, 2));
    } finally {
        if (browser) {
            await browser.close();
        }
        await closeServer(fixtureWss);
        await closeServer(smusWss);
        await closeServer(server);
    }
}

main().catch(error => {
    console.error(error && error.stack ? error.stack : error);
    process.exit(1);
});
