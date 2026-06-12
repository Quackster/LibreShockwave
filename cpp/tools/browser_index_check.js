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
const timeoutMs = Number(args.timeoutMs || 240000);

function resolveRepoPath(value, fallback) {
    if (!value) {
        return fallback;
    }
    return path.isAbsolute(value) ? value : path.resolve(repoRoot, value);
}

const distPath = resolveRepoPath(args.dist, path.join(repoRoot, 'cmake-build-wasm/cpp/wasm-dist'));
const screenshotPath = resolveRepoPath(args.screenshot, path.join(repoRoot, 'cmake-build-wasm/habbo-index-login-check.png'));
const indexPath = path.join(distPath, 'index.html');

const MIME = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'application/javascript',
    '.wasm': 'application/wasm',
    '.dcr': 'application/x-director',
    '.cct': 'application/x-director',
    '.cst': 'application/x-director',
    '.txt': 'text/plain',
};

function assertFile(filePath) {
    if (!fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
        throw new Error(`Required file not found: ${filePath}`);
    }
}

function send(res, status, body, contentType) {
    const buffer = Buffer.isBuffer(body) ? body : Buffer.from(String(body), 'utf8');
    res.writeHead(status, {
        'Content-Type': contentType || 'text/plain; charset=utf-8',
        'Content-Length': buffer.length,
    });
    res.end(buffer);
}

function createServer() {
    const server = http.createServer((req, res) => {
        const pathname = decodeURIComponent((req.url || '/').split('?')[0]);
        if (pathname === '/favicon.ico') {
            send(res, 204, Buffer.alloc(0), 'text/plain');
            return;
        }

        const relativePath = pathname === '/' || pathname === '/cmake-build-wasm/cpp/wasm-dist/'
            ? 'index.html'
            : pathname.replace(/^\/cmake-build-wasm\/cpp\/wasm-dist\/?/, '');
        const filePath = path.resolve(distPath, relativePath || 'index.html');
        const relative = path.relative(distPath, filePath);
        if (!relative || relative.startsWith('..') || path.isAbsolute(relative) ||
                !fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) {
            send(res, 404, `Not found: ${pathname}`);
            return;
        }

        send(res, 200, fs.readFileSync(filePath), MIME[path.extname(filePath).toLowerCase()] || 'application/octet-stream');
    });

    return new Promise(resolve => {
        server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
    });
}

function closeServer(server) {
    return new Promise(resolve => server.close(resolve));
}

async function createBrowser() {
    const puppeteer = requireFromCandidates('puppeteer');
    return puppeteer.launch({
        headless: true,
        executablePath: process.env.CHROME_PATH || undefined,
        args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'],
        defaultViewport: { width: 1240, height: 1260, deviceScaleFactor: 1 },
    });
}

async function collectState(page) {
    return page.evaluate(() => {
        const player = window.__libreshockwaveCppPlayer || null;
        const frame = window.__libreshockwaveCppLastFrame || null;
        const canvas = document.getElementById('stage');
        const ctx = canvas && canvas.getContext('2d');
        const counts = {
            nonBlack: 0,
            yellowLogo: 0,
            orangeBuilding: 0,
            loginBlue: 0,
            whiteFields: 0,
        };
        if (ctx && canvas.width && canvas.height) {
            const rgba = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
            for (let i = 0; i < rgba.length; i += 4) {
                const r = rgba[i];
                const g = rgba[i + 1];
                const b = rgba[i + 2];
                const a = rgba[i + 3];
                if (a && (r > 12 || g > 12 || b > 12)) counts.nonBlack++;
                if (r > 210 && g > 155 && b < 60) counts.yellowLogo++;
                if (r > 190 && g > 80 && g < 175 && b < 80) counts.orangeBuilding++;
                if (r > 70 && r < 160 && g > 110 && g < 190 && b > 120 && b < 215) counts.loginBlue++;
                if (r > 220 && g > 220 && b > 220) counts.whiteFields++;
            }
        }

        return {
            status: document.getElementById('status')?.textContent || '',
            frameInfo: document.getElementById('frame-info')?.textContent || '',
            runtimeState: document.getElementById('runtime-state')?.textContent || '',
            error: window.__libreshockwaveCppLastError || null,
            frame: frame ? {
                frame: frame.frame,
                frameCount: frame.frameCount,
                spriteCount: frame.spriteCount,
                tempo: frame.tempo,
            } : null,
            canvas: canvas ? {
                width: canvas.width,
                height: canvas.height,
                clientWidth: canvas.clientWidth,
                clientHeight: canvas.clientHeight,
                displayWidth: canvas.style.getPropertyValue('--stage-display-width'),
                aspectRatio: canvas.style.aspectRatio,
            } : null,
            counts,
        };
    });
}

function assertLoginState(state, failures) {
    const failed = [];
    if (state.error) failed.push(`player error: ${state.error}`);
    if (!state.frame || state.frame.frame !== 10 || state.frame.frameCount !== 10) {
        failed.push(`expected frame 10 / 10, got ${JSON.stringify(state.frame)}`);
    }
    if (!state.frame || state.frame.spriteCount < 35) {
        failed.push(`expected at least 35 sprites, got ${state.frame ? state.frame.spriteCount : 0}`);
    }
    if (state.counts.nonBlack < 250000) failed.push(`expected login art, nonBlack=${state.counts.nonBlack}`);
    if (state.counts.yellowLogo < 10000) failed.push(`expected Habbo logo yellows, yellowLogo=${state.counts.yellowLogo}`);
    if (state.counts.orangeBuilding < 20000) failed.push(`expected hotel building oranges, orangeBuilding=${state.counts.orangeBuilding}`);
    if (state.counts.whiteFields < 20000) failed.push(`expected login/register white panels, whiteFields=${state.counts.whiteFields}`);
    if (!state.canvas || state.canvas.width !== 720 || state.canvas.height !== 540) {
        failed.push(`expected intrinsic canvas 720x540, got ${JSON.stringify(state.canvas)}`);
    }
    if (!state.canvas || state.canvas.displayWidth !== '720px') {
        failed.push(`expected resized stage display width 720px, got ${state.canvas ? state.canvas.displayWidth : '<missing>'}`);
    }
    if (failures.length > 0) {
        failed.push(`request failures: ${JSON.stringify(failures)}`);
    }
    if (failed.length > 0) {
        throw new Error(failed.join('\n'));
    }
}

async function main() {
    assertFile(indexPath);

    let server;
    let browser;
    try {
        const served = await createServer();
        server = served.server;
        const url = args.url || `http://127.0.0.1:${served.port}/cmake-build-wasm/cpp/wasm-dist/?autoload=1`;
        browser = await createBrowser();
        const page = await browser.newPage();
        const failures = [];
        page.on('response', response => {
            if (response.status() >= 400 && !response.url().endsWith('/favicon.ico')) {
                failures.push([response.status(), response.url()]);
            }
        });
        page.on('requestfailed', request => {
            failures.push([request.failure() ? request.failure().errorText : 'failed', request.url()]);
        });

        await page.goto(url, { waitUntil: 'domcontentloaded', timeout: timeoutMs });
        const deadline = Date.now() + timeoutMs;
        let state = null;
        while (Date.now() < deadline) {
            await new Promise(resolve => setTimeout(resolve, 1000));
            state = await collectState(page);
            if (state.error) {
                break;
            }
            if (state.frame && state.frame.frame === 10 && state.frame.spriteCount >= 35 &&
                    state.counts.yellowLogo >= 10000 && state.counts.orangeBuilding >= 20000 &&
                    state.counts.whiteFields >= 20000) {
                break;
            }
        }

        if (!state) {
            state = await collectState(page);
        }
        fs.mkdirSync(path.dirname(screenshotPath), { recursive: true });
        await page.screenshot({ path: screenshotPath, fullPage: true });
        assertLoginState(state, failures);
        console.log(JSON.stringify({
            ok: true,
            url,
            screenshot: screenshotPath,
            frame: state.frame,
            counts: state.counts,
            canvas: state.canvas,
        }, null, 2));
    } finally {
        if (browser) {
            await browser.close();
        }
        if (server) {
            await closeServer(server);
        }
    }
}

main().catch(error => {
    console.error(error && error.stack ? error.stack : String(error));
    process.exitCode = 1;
});
