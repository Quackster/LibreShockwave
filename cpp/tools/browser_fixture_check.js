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
        path.join(repoRoot, 'cpp', 'tools', 'node_modules', name),
        path.join(repoRoot, 'node_modules', name),
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
const moviePath = args.movie || '';
const timeoutMs = Number(args.timeoutMs || 120000);
const minFrames = Number(args.minFrames || 1);
const minNonBlack = Number(args.minNonBlack || 1);
const screenshotPath = args.screenshot ? path.resolve(args.screenshot) : '';
const screenshotDir = (args.screenshotDir || args['screenshot-dir'])
    ? path.resolve(args.screenshotDir || args['screenshot-dir'])
    : '';
const movieListPath = (args.movieList || args['movie-list'])
    ? path.resolve(args.movieList || args['movie-list'])
    : '';
const moviesArg = args.movies || '';
const discoverRootArg = args.discoverRoot || args['discover-root'] || args.discover || '';
const movieLimit = Number(args.limit || 0);
const summaryOnly = !!(args.summaryOnly || args['summary-only']);

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

function safeDecodeUriPath(value) {
    try {
        return decodeURIComponent(value);
    } catch (error) {
        return value;
    }
}

function isInsideRoot(root, candidate) {
    const relative = path.relative(root, candidate);
    return relative === '' || (!!relative && !relative.startsWith('..') && !path.isAbsolute(relative));
}

function addCandidate(candidates, root, ...segments) {
    if (!root || segments.some(segment => segment == null || segment === '')) {
        return;
    }
    const candidate = path.resolve(root, ...segments);
    if (!isInsideRoot(root, candidate)) {
        return;
    }
    if (!candidates.includes(candidate)) {
        candidates.push(candidate);
    }
}

function normalizedRequestPath(requestPath) {
    return requestPath.replace(/\\/g, '/').replace(/^\/+/, '');
}

function requestFileName(requestPath) {
    const normalized = normalizedRequestPath(requestPath).replace(/\/+$/, '');
    if (!normalized) {
        return '';
    }
    let fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
    const driveOrScheme = fileName.lastIndexOf(':');
    if (driveOrScheme >= 0) {
        fileName = fileName.substring(driveOrScheme + 1);
    }
    return fileName;
}

function requestDirectory(requestPath) {
    const normalized = normalizedRequestPath(requestPath);
    const parts = normalized.split('/').filter(Boolean);
    parts.pop();
    if (parts.some(part => part.includes(':'))) {
        return '';
    }
    return parts.join('/');
}

function fileCandidatesForRequest(requestPath) {
    const relative = normalizedRequestPath(requestPath);
    const fileName = requestFileName(requestPath);
    const stem = fileName ? path.basename(fileName, path.extname(fileName)) : '';
    const directory = requestDirectory(requestPath);
    const candidates = [];

    addCandidate(candidates, distPath, relative);
    addCandidate(candidates, fixtureRoot, relative);
    if (!fileName) {
        return candidates;
    }

    addCandidate(candidates, fixtureRoot, fileName);
    if (stem) {
        addCandidate(candidates, fixtureRoot, stem, fileName);
    }
    if (directory) {
        addCandidate(candidates, fixtureRoot, directory, fileName);
        if (stem) {
            addCandidate(candidates, fixtureRoot, directory, stem, fileName);
        }
    }
    return candidates;
}

const MOVIE_EXTENSIONS = new Set(['.dcr', '.dir', '.dxr']);

function normalizeMoviePath(value) {
    let movie = String(value || '').trim();
    if (!movie) {
        return '';
    }
    movie = movie.replace(/\\/g, '/');
    if (path.isAbsolute(movie)) {
        const absolute = path.resolve(movie);
        if (isInsideRoot(fixtureRoot, absolute)) {
            movie = path.relative(fixtureRoot, absolute).split(path.sep).join('/');
        }
    }
    movie = movie.replace(/^\.\//, '');
    return movie.startsWith('/') ? movie : `/${movie}`;
}

function addMoviePath(movies, seen, value) {
    const movie = normalizeMoviePath(value);
    if (!movie || seen.has(movie)) {
        return;
    }
    seen.add(movie);
    movies.push(movie);
}

function discoverMoviePaths(root) {
    const found = [];
    function visit(directory) {
        const entries = fs.readdirSync(directory, { withFileTypes: true })
            .sort((a, b) => a.name.localeCompare(b.name));
        for (const entry of entries) {
            const entryPath = path.join(directory, entry.name);
            if (entry.isDirectory()) {
                visit(entryPath);
            } else if (entry.isFile() && MOVIE_EXTENSIONS.has(path.extname(entry.name).toLowerCase())) {
                found.push(entryPath);
            }
        }
    }
    visit(root);
    return found;
}

function requestedMoviePaths() {
    const movies = [];
    const seen = new Set();

    addMoviePath(movies, seen, moviePath);
    for (const movie of moviesArg.split(',')) {
        addMoviePath(movies, seen, movie);
    }
    if (movieListPath) {
        const lines = fs.readFileSync(movieListPath, 'utf8').split(/\r?\n/);
        for (const line of lines) {
            const trimmed = line.trim();
            if (trimmed && !trimmed.startsWith('#')) {
                addMoviePath(movies, seen, trimmed);
            }
        }
    }
    if (discoverRootArg) {
        const root = path.resolve(fixtureRoot, String(discoverRootArg).replace(/^\/+/, ''));
        if (!isInsideRoot(fixtureRoot, root) || !fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
            throw new Error(`Discover root not found under fixture root: ${root}`);
        }
        for (const found of discoverMoviePaths(root)) {
            addMoviePath(movies, seen, found);
        }
    }

    if (movieLimit > 0) {
        return movies.slice(0, movieLimit);
    }
    return movies;
}

function screenshotPathForMovie(movie, movieCount) {
    if (screenshotPath) {
        if (movieCount !== 1) {
            throw new Error('Use --screenshot-dir for batch checks; --screenshot is only valid with one movie');
        }
        return screenshotPath;
    }
    if (!screenshotDir) {
        return '';
    }
    const safeName = movie.replace(/^\/+/, '').replace(/[^A-Za-z0-9_.-]+/g, '_');
    return path.join(screenshotDir, `${safeName}.png`);
}

function createFixtureServer() {
    const server = http.createServer((req, res) => {
        const requestPath = safeDecodeUriPath(req.url.split('?')[0]);
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
        const candidates = fileCandidatesForRequest(requestPath);
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

function compactDiagnostics(value, maxStringLength = 6000) {
    if (typeof value === 'string') {
        if (value.length <= maxStringLength) {
            return value;
        }
        return `${value.substring(0, maxStringLength)}\n[truncated ${value.length - maxStringLength} chars]`;
    }
    if (Array.isArray(value)) {
        return value.map(item => compactDiagnostics(item, maxStringLength));
    }
    if (value && typeof value === 'object') {
        const result = {};
        for (const [key, item] of Object.entries(value)) {
            result[key] = compactDiagnostics(item, maxStringLength);
        }
        return result;
    }
    return value;
}

async function createBrowser() {
    try {
        const puppeteer = requireFromCandidates('puppeteer');
        const browser = await puppeteer.launch({
            headless: true,
            executablePath: process.env.CHROME_PATH || undefined,
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

async function waitForRenderedFrame(page) {
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
            for (let p = 0; p < rgba.length; p += 4) {
                const r = rgba[p];
                const g = rgba[p + 1];
                const b = rgba[p + 2];
                if (r > 10 || g > 10 || b > 10) {
                    nonBlack++;
                }
                if ((p / 4) % 257 === 0) {
                    buckets.add((r >> 5) + ',' + (g >> 5) + ',' + (b >> 5));
                }
            }
            return {
                loaded: window.__state.loaded,
                errors: window.__state.errors.slice(),
                frames: window.__state.frames,
                gotos: window.__state.gotos.slice(),
                frame: window.__state.lastFrame || null,
                nonBlack,
                colorBuckets: buckets.size,
            };
        });
        if (last.errors.length > 0) {
            throw new Error(`Browser player error: ${last.errors.join('\n')}`);
        }
        if (last.loaded && last.frames >= minFrames && last.nonBlack >= minNonBlack) {
            return last;
        }
    }
    let diagnostics = {};
    try {
        diagnostics = await page.evaluate(async () => {
            const result = {};
            const player = window.player;
            async function capture(name, method) {
                if (!player || typeof player[method] !== 'function') {
                    return;
                }
                try {
                    result[name] = await Promise.race([
                        player[method](),
                        new Promise(resolve => setTimeout(() => resolve('[diagnostic timeout]'), 2000)),
                    ]);
                } catch (error) {
                    result[name] = `[diagnostic error] ${error && error.message ? error.message : String(error)}`;
                }
            }
            await capture('runtime', 'getRuntimeDiagnostics');
            await capture('bootstrap', 'getBootstrapDiagnostics');
            await capture('scripts', 'getScriptDiagnostics');
            await capture('windowSprites', 'getWindowSpriteDiagnostics');
            await capture('visibleText', 'getVisibleTextDiagnostics');
            await capture('callStack', 'getCallStack');
            return result;
        });
    } catch (error) {
        diagnostics = { error: error && error.message ? error.message : String(error) };
    }
    diagnostics = compactDiagnostics(diagnostics);
    throw new Error(`Movie did not produce the expected rendered frame before timeout: ${JSON.stringify({ state: last, diagnostics }, null, 2)}`);
}

function summarizeMus(mus) {
    return {
        connected: mus.connected,
        sent: mus.sent,
        received: mus.received,
        disconnected: mus.disconnected,
        errorDelivered: mus.errorDelivered,
        receiveText: mus.receiveText,
    };
}

function summarizeSmus(smus) {
    return {
        connected: smus.connected,
        cppConnected: smus.cppConnected,
        logonQueued: smus.logonQueued,
        logonSent: smus.logonSent,
        smusSendCount: smus.smusSendCount,
        smusSequenceComplete: smus.smusSequenceComplete,
        connectedBetweenMessages: smus.connectedBetweenMessages,
        cppReceivedCount: smus.cppReceivedCount,
        disconnectRequested: smus.disconnectRequested,
        socketClosedAfterDisconnect: smus.socketClosedAfterDisconnect,
        errorDelivered: smus.errorDelivered,
    };
}

async function runMovieCheck(page, options) {
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message || String(error)));
    await page.goto(`http://127.0.0.1:${options.httpPort}/test.html`, { waitUntil: 'load', timeout: timeoutMs });
    await page.waitForFunction(() => window.__state && window.__state.ready, { timeout: timeoutMs });
    await page.evaluate(url => window.player.load(url), options.movie);
    const visual = await waitForRenderedFrame(page);
    if (pageErrors.length > 0) {
        throw new Error(`Browser page errors:\n${pageErrors.join('\n')}`);
    }

    const musStart = options.musReceived.length;
    const smusStart = options.smusReceived.length;
    const mus = await page.evaluate(async diagnosticOptions => {
        return window.player.runMusWebSocketSelfTest(diagnosticOptions);
    }, {
        host: '127.0.0.1',
        port: options.musWsPort,
        payload: 'client-ping',
        timeoutMs: Math.min(timeoutMs, 10000),
    });
    if (!mus || !mus.ok) {
        throw new Error(`Multiuser WebSocket diagnostic failed: ${JSON.stringify(mus, null, 2)}`);
    }

    const smus = await page.evaluate(async diagnosticOptions => {
        return window.player.runCxxSmusBridgeSelfTest(diagnosticOptions);
    }, {
        host: '127.0.0.1',
        port: options.smusWsPort,
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

    if (options.screenshotPath) {
        await page.screenshot({ path: options.screenshotPath });
    }

    const musReceived = options.musReceived.slice(musStart);
    const smusReceived = options.smusReceived.slice(smusStart);
    return {
        ok: true,
        movie: options.movie,
        distPath,
        fixtureRoot,
        visual,
        mus: summarizeMus(mus),
        smus: summarizeSmus(smus),
        musServerReceivedCount: musReceived.length,
        musServerReceivedPreview: musReceived.map(bytes => bytes.subarray(0, 32).toString('hex')),
        smusServerReceivedCount: smusReceived.length,
        smusServerReceivedLengths: smusReceived.map(bytes => bytes.length),
        smusServerReceivedPreview: smusReceived.map(bytes => bytes.subarray(0, 16).toString('hex')),
        screenshot: options.screenshotPath || undefined,
    };
}

function compactResult(result) {
    return {
        movie: result.movie,
        frames: result.visual.frames,
        frame: result.visual.frame ? result.visual.frame.frame : null,
        frameCount: result.visual.frame ? result.visual.frame.frameCount : null,
        spriteCount: result.visual.frame ? result.visual.frame.spriteCount : 0,
        nonBlack: result.visual.nonBlack,
        mus: result.mus.connected && result.mus.received,
        smus: result.smus.connected && result.smus.smusSequenceComplete,
    };
}

function summarizeBatch(results) {
    const summary = {
        ok: true,
        movies: results.length,
        distPath,
        fixtureRoot,
        totals: {
            frames: results.reduce((sum, result) => sum + result.visual.frames, 0),
            nonBlack: results.reduce((sum, result) => sum + result.visual.nonBlack, 0),
            sprites: results.reduce((sum, result) => sum + (result.visual.frame ? result.visual.frame.spriteCount : 0), 0),
        },
    };
    if (summaryOnly) {
        summary.results = results.map(compactResult);
    } else {
        summary.results = results;
    }
    return summary;
}

async function main() {
    const movies = requestedMoviePaths();
    if (movies.length === 0) {
        throw new Error('Pass --movie /path/to/movie.dcr, --movies comma,list, --movie-list file, or --discover-root path');
    }

    assertFile(path.join(distPath, 'libreshockwave-cpp-player.js'));
    assertFile(path.join(distPath, 'libreshockwave-cpp-worker.js'));
    assertFile(path.join(distPath, 'libreshockwave-cpp-wasm.js'));
    assertFile(path.join(distPath, 'libreshockwave-cpp-wasm.wasm'));
    for (const movie of movies) {
        assertFile(path.join(fixtureRoot, movie.replace(/^\/+/, '')));
    }
    if (screenshotDir) {
        fs.mkdirSync(screenshotDir, { recursive: true });
    }

    const WebSocket = requireFromCandidates('ws');
    const { server, port: httpPort } = await createFixtureServer();
    const musWss = new WebSocket.Server({ host: '127.0.0.1', port: 0 });
    const smusWss = new WebSocket.Server({ host: '127.0.0.1', port: 0 });
    await Promise.all([
        new Promise(resolve => musWss.once('listening', resolve)),
        new Promise(resolve => smusWss.once('listening', resolve)),
    ]);
    const musWsPort = musWss.address().port;
    const smusWsPort = smusWss.address().port;
    const musReceived = [];
    const smusReceived = [];
    musWss.on('connection', socket => {
        socket.on('message', data => {
            const bytes = Buffer.from(data);
            musReceived.push(bytes);
            if (socket.readyState === WebSocket.OPEN) {
                socket.send(bytes);
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
        const results = [];
        for (const movie of movies) {
            const page = await loadedBrowser.newPage();
            try {
                results.push(await runMovieCheck(page, {
                    movie,
                    httpPort,
                    musWsPort,
                    smusWsPort,
                    musReceived,
                    smusReceived,
                    screenshotPath: screenshotPathForMovie(movie, movies.length),
                }));
            } finally {
                await page.close();
            }
        }
        console.log(JSON.stringify(results.length === 1 ? results[0] : summarizeBatch(results), null, 2));
    } finally {
        if (browser) {
            await browser.close();
        }
        await closeServer(musWss);
        await closeServer(smusWss);
        await closeServer(server);
    }
}

main().catch(error => {
    console.error(error && error.stack ? error.stack : error);
    process.exit(1);
});
