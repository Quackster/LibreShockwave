/**
 * LibreShockwave WebWorker
 *
 * Runs the WASM VM in a separate thread so the main thread stays responsive.
 * Uses SharedArrayBuffer + Atomics for debug pause/resume blocking.
 *
 * Protocol: main thread sends commands via postMessage, worker responds with events.
 */

var teavm = null;
var exports = null;
var debugSab = null;   // SharedArrayBuffer (4 bytes) for debug pause/resume
var debugView = null;  // Int32Array view of debugSab

/**
 * Get the WASM memory ArrayBuffer (must be refreshed after any allocation).
 */
function getMemory() {
    return teavm.memory.buffer;
}

/**
 * Read a string from the string buffer at the given address.
 */
function readStringFromBuffer(length) {
    var addr = exports.getStringBufferAddress();
    var bytes = new Uint8Array(getMemory(), addr, length);
    return new TextDecoder().decode(bytes);
}

/**
 * Read JSON from the large buffer.
 */
function readJsonFromLargeBuffer(length) {
    if (length <= 0) return null;
    var addr = exports.getLargeBufferAddress();
    var bytes = new Uint8Array(getMemory(), addr, length);
    var str = new TextDecoder().decode(bytes);
    try {
        return JSON.parse(str);
    } catch (e) {
        console.error('[Worker] JSON parse error:', e);
        return null;
    }
}

/**
 * Write a string to the string buffer.
 */
function writeStringToBuffer(str) {
    var bytes = new TextEncoder().encode(str);
    var addr = exports.getStringBufferAddress();
    var buf = new Uint8Array(getMemory(), addr, 4096);
    buf.set(bytes.subarray(0, Math.min(bytes.length, 4096)));
    return bytes.length;
}

/**
 * Handle fetch relay — worker cannot do fetch directly in all browsers,
 * so we relay fetch requests to the main thread.
 */
function handleFetchGet(taskId, urlLength) {
    var url = readStringFromBuffer(urlLength);
    self.postMessage({ type: 'fetchRequest', method: 'GET', taskId: taskId, url: url });
}

function handleFetchPost(taskId, urlLength, postDataLength) {
    var url = readStringFromBuffer(urlLength);
    var addr = exports.getStringBufferAddress();
    var postBytes = new Uint8Array(getMemory(), addr + urlLength, postDataLength);
    var postData = new TextDecoder().decode(postBytes);
    self.postMessage({ type: 'fetchRequest', method: 'POST', taskId: taskId, url: url, postData: postData });
}

/**
 * Debug: block the worker thread until main thread signals resume.
 * Uses Atomics.wait on SharedArrayBuffer.
 */
function debugWait() {
    if (!debugView) return;
    // Wait while value is 0 (paused state)
    while (Atomics.load(debugView, 0) === 0) {
        Atomics.wait(debugView, 0, 0);
    }
    // Reset for next pause
    Atomics.store(debugView, 0, 0);
}

function debugResume() {
    // No-op in worker — resume is triggered from main thread via Atomics.notify
}

function debugNotifyPaused(jsonLength) {
    // Read the paused snapshot JSON from the debug controller's bytes
    var jsonLen = exports.getDebugPausedJson();
    var snapshot = readJsonFromLargeBuffer(jsonLen);
    self.postMessage({ type: 'debugPaused', snapshot: snapshot });
}

/**
 * Initialize the WASM module.
 */
async function initWasm(sabuffer) {
    debugSab = sabuffer;
    if (debugSab) {
        debugView = new Int32Array(debugSab);
    }

    try {
        // Import the TeaVM runtime (self.importScripts is synchronous in workers)
        self.importScripts('player-wasm.wasm-runtime.js');

        teavm = await TeaVM.wasm.load('player-wasm.wasm', {
            installImports: function(importObj, controller) {
                importObj.libreshockwave = {
                    fetchGet: handleFetchGet,
                    fetchPost: handleFetchPost,
                    debugWait: debugWait,
                    debugResume: debugResume,
                    debugNotifyPaused: debugNotifyPaused
                };
            }
        });

        exports = teavm.instance.exports;
        await teavm.main([]);

        self.postMessage({ type: 'ready' });
    } catch (e) {
        self.postMessage({ type: 'error', message: 'WASM init failed: ' + e.message });
    }
}

/**
 * Load a movie from transferred bytes.
 */
function loadMovie(movieBytes, basePath) {
    var basePathBytes = new TextEncoder().encode(basePath || '');

    // Write basePath to string buffer
    var stringBufAddr = exports.getStringBufferAddress();
    var stringBuf = new Uint8Array(getMemory(), stringBufAddr, 4096);
    stringBuf.set(basePathBytes);

    // Allocate and write movie bytes
    exports.allocateMovieBuffer(movieBytes.length);
    var movieBufAddr = exports.getMovieBufferAddress();
    var movieBuf = new Uint8Array(getMemory(), movieBufAddr, movieBytes.length);
    movieBuf.set(movieBytes);

    var result = exports.loadMovie(movieBytes.length, basePathBytes.length);
    if (result === 0) {
        self.postMessage({ type: 'error', message: 'Failed to load movie' });
        return;
    }

    var width = (result >> 16) & 0xFFFF;
    var height = result & 0xFFFF;

    self.postMessage({
        type: 'movieLoaded',
        width: width,
        height: height,
        frameCount: exports.getFrameCount(),
        tempo: exports.getTempo()
    });
}

/**
 * Tick one frame and return frame data.
 */
function tickFrame() {
    var stillPlaying = exports.tick();

    // Get frame data (sprite-based or pixel-based)
    var frameJsonLen = exports.getFrameDataJson();
    var frameData = null;
    if (frameJsonLen > 0) {
        frameData = readJsonFromLargeBuffer(frameJsonLen);
    }

    // Also get pixel buffer for fallback rendering
    var pixelPtr = exports.render();
    var pixels = null;
    if (pixelPtr > 0) {
        var w = exports.getStageWidth();
        var h = exports.getStageHeight();
        var src = new Uint8ClampedArray(getMemory(), pixelPtr, w * h * 4);
        pixels = new Uint8ClampedArray(src.length);
        pixels.set(src); // Copy since memory may be detached
    }

    self.postMessage({
        type: 'frameData',
        stillPlaying: stillPlaying !== 0,
        frame: exports.getCurrentFrame(),
        frameCount: exports.getFrameCount(),
        frameData: frameData,
        pixels: pixels
    }, pixels ? [pixels.buffer] : []);
}

/**
 * Get bitmap data for a specific member and send to main thread as transferable.
 */
function sendBitmapData(memberId) {
    var ptr = exports.getBitmapData(memberId);
    if (ptr === 0) return;

    var w = exports.getBitmapWidth(memberId);
    var h = exports.getBitmapHeight(memberId);
    if (w <= 0 || h <= 0) return;

    var src = new Uint8ClampedArray(getMemory(), ptr, w * h * 4);
    var rgba = new Uint8ClampedArray(src.length);
    rgba.set(src);

    self.postMessage({
        type: 'bitmapData',
        memberId: memberId,
        width: w,
        height: h,
        rgba: rgba.buffer
    }, [rgba.buffer]);
}

/**
 * Deliver fetch results from main thread to WASM.
 */
function deliverFetchResult(taskId, data) {
    exports.allocateNetBuffer(data.length);
    var netBufAddr = exports.getNetBufferAddress();
    var netBuf = new Uint8Array(getMemory(), netBufAddr, data.length);
    netBuf.set(new Uint8Array(data));
    exports.onFetchComplete(taskId, data.length);
}

function deliverFetchError(taskId, status) {
    exports.onFetchError(taskId, status || 0);
}

// === Message handler ===

self.onmessage = function(e) {
    var msg = e.data;

    switch (msg.cmd) {
        case 'init':
            initWasm(msg.sab);
            break;

        case 'loadMovie':
            loadMovie(new Uint8Array(msg.bytes), msg.basePath);
            break;

        case 'play':
            exports.play();
            self.postMessage({ type: 'stateChange', state: 'playing' });
            break;

        case 'pause':
            exports.pause();
            self.postMessage({ type: 'stateChange', state: 'paused' });
            break;

        case 'stop':
            exports.stop();
            self.postMessage({ type: 'stateChange', state: 'stopped' });
            break;

        case 'tick':
            tickFrame();
            break;

        case 'goToFrame':
            exports.goToFrame(msg.frame);
            tickFrame(); // Return updated frame data
            break;

        case 'stepForward':
            exports.stepForward();
            tickFrame();
            break;

        case 'stepBackward':
            exports.stepBackward();
            tickFrame();
            break;

        case 'enableDebug':
            exports.enableDebug();
            self.postMessage({ type: 'debugEnabled' });
            break;

        case 'toggleBreakpoint':
            var added = exports.toggleBreakpoint(msg.scriptId, msg.offset);
            self.postMessage({ type: 'breakpointToggled', scriptId: msg.scriptId, offset: msg.offset, added: added === 1 });
            break;

        case 'clearBreakpoints':
            exports.clearBreakpoints();
            self.postMessage({ type: 'breakpointsCleared' });
            break;

        case 'debugStepInto':
            exports.debugStepInto();
            break;

        case 'debugStepOver':
            exports.debugStepOver();
            break;

        case 'debugStepOut':
            exports.debugStepOut();
            break;

        case 'debugContinue':
            exports.debugContinue();
            break;

        case 'debugPause':
            exports.debugPause();
            break;

        case 'getScriptList': {
            var len = exports.getScriptList();
            var data = readJsonFromLargeBuffer(len);
            self.postMessage({ type: 'scriptList', scripts: data });
            break;
        }

        case 'getHandlerBytecode': {
            var len = exports.getHandlerBytecode(msg.scriptId, msg.handlerIndex);
            var data = readJsonFromLargeBuffer(len);
            self.postMessage({ type: 'handlerBytecode', scriptId: msg.scriptId, handlerIndex: msg.handlerIndex, instructions: data });
            break;
        }

        case 'getHandlerDetails': {
            var len = exports.getHandlerDetails(msg.scriptId, msg.handlerIndex);
            var data = readJsonFromLargeBuffer(len);
            self.postMessage({ type: 'handlerDetails', details: data });
            break;
        }

        case 'getBitmapData':
            sendBitmapData(msg.memberId);
            break;

        case 'addWatch': {
            var wlen = writeStringToBuffer(msg.expression);
            exports.addWatch(wlen);
            var wjlen = exports.getWatches();
            self.postMessage({ type: 'watchList', watches: readJsonFromLargeBuffer(wjlen) });
            break;
        }

        case 'removeWatch': {
            var rlen = writeStringToBuffer(msg.id);
            exports.removeWatch(rlen);
            var rwlen = exports.getWatches();
            self.postMessage({ type: 'watchList', watches: readJsonFromLargeBuffer(rwlen) });
            break;
        }

        case 'clearWatches':
            exports.clearWatches();
            self.postMessage({ type: 'watchList', watches: [] });
            break;

        case 'getDebugSnapshot': {
            var slen = exports.getDebugSnapshot();
            self.postMessage({ type: 'debugSnapshot', snapshot: readJsonFromLargeBuffer(slen) });
            break;
        }

        case 'serializeBreakpoints': {
            var blen = exports.serializeBreakpoints();
            var bdata = readJsonFromLargeBuffer(blen);
            self.postMessage({ type: 'serializedBreakpoints', data: bdata });
            break;
        }

        case 'deserializeBreakpoints': {
            var dlen = writeStringToBuffer(msg.data);
            exports.deserializeBreakpoints(dlen);
            break;
        }

        case 'getState': {
            self.postMessage({
                type: 'state',
                frame: exports.getCurrentFrame(),
                frameCount: exports.getFrameCount(),
                tempo: exports.getTempo(),
                debugState: exports.getDebugState()
            });
            break;
        }

        case 'fetchComplete':
            deliverFetchResult(msg.taskId, msg.data);
            break;

        case 'fetchError':
            deliverFetchError(msg.taskId, msg.status);
            break;

        case 'preloadAllCasts': {
            var count = exports.preloadAllCasts();
            self.postMessage({ type: 'preloadStarted', count: count });
            break;
        }

        default:
            console.warn('[Worker] Unknown command:', msg.cmd);
    }
};
