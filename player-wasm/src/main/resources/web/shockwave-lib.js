/**
 * LibreShockwave - Embeddable Shockwave/Director player for the web.
 *
 * Usage:
 *   <canvas id="stage" width="640" height="480"></canvas>
 *   <script src="shockwave-lib.js"></script>
 *   <script>
 *     var player = LibreShockwave.create("stage");
 *     player.load("http://example.com/movie.dcr");
 *   </script>
 *
 * All WASM files (player-wasm.wasm, player-wasm.wasm-runtime.js)
 * must be in the same directory as this script unless basePath is specified.
 */
var LibreShockwave = (function() {

    // Auto-detect base path from <script src="...shockwave-lib.js">
    var _autoBasePath = '';
    (function() {
        var scripts = document.getElementsByTagName('script');
        for (var i = scripts.length - 1; i >= 0; i--) {
            var src = scripts[i].src || '';
            if (src.indexOf('shockwave-lib.js') !== -1) {
                _autoBasePath = src.substring(0, src.lastIndexOf('/') + 1);
                break;
            }
        }
    })();

    // ========================================================================
    // Inline Worker source code
    // ========================================================================

    var _workerCode = (function() {
    // --- BEGIN WORKER CODE ---
    // This string is evaluated inside a Web Worker via Blob URL.
    // The variable _basePath is injected before this code at runtime.
    return [
'var teavm = null;',
'var exports = null;',
'var debugSab = null;',
'var debugView = null;',
'var _tickCount = 0;',
'',
'function getMemory() {',
'    return teavm.memory.buffer;',
'}',
'',
'function clearPendingException() {',
'    if (teavm && teavm.instance && teavm.instance.exports.teavm_catchException) {',
'        var ex = teavm.instance.exports.teavm_catchException();',
'        if (ex !== 0) {',
'            console.warn("[Worker] Cleared pending Java exception (ptr=" + ex + ")");',
'        }',
'    }',
'}',
'',
'function readStringFromBuffer(length) {',
'    var addr = exports.getStringBufferAddress();',
'    var bytes = new Uint8Array(getMemory(), addr, length);',
'    return new TextDecoder().decode(bytes);',
'}',
'',
'function readJsonFromLargeBuffer(length) {',
'    if (length <= 0) return null;',
'    var addr = exports.getLargeBufferAddress();',
'    var bytes = new Uint8Array(getMemory(), addr, length);',
'    var str = new TextDecoder().decode(bytes);',
'    try {',
'        return JSON.parse(str);',
'    } catch (e) {',
'        console.error("[Worker] JSON parse error:", e);',
'        return null;',
'    }',
'}',
'',
'function writeStringToBuffer(str) {',
'    var bytes = new TextEncoder().encode(str);',
'    var addr = exports.getStringBufferAddress();',
'    var buf = new Uint8Array(getMemory(), addr, 4096);',
'    buf.set(bytes.subarray(0, Math.min(bytes.length, 4096)));',
'    return bytes.length;',
'}',
'',
'function handleFetchGet(taskId, urlLength) {',
'    var url = readStringFromBuffer(urlLength);',
'    self.postMessage({ type: "fetchRequest", method: "GET", taskId: taskId, url: url });',
'}',
'',
'function handleFetchPost(taskId, urlLength, postDataLength) {',
'    var url = readStringFromBuffer(urlLength);',
'    var addr = exports.getStringBufferAddress();',
'    var postBytes = new Uint8Array(getMemory(), addr + urlLength, postDataLength);',
'    var postData = new TextDecoder().decode(postBytes);',
'    self.postMessage({ type: "fetchRequest", method: "POST", taskId: taskId, url: url, postData: postData });',
'}',
'',
'function debugWait() {',
'    if (!debugView) return;',
'    while (Atomics.load(debugView, 0) === 0) {',
'        Atomics.wait(debugView, 0, 0);',
'    }',
'    Atomics.store(debugView, 0, 0);',
'}',
'',
'function debugResume() {}',
'',
'function debugNotifyPaused(jsonLength) {',
'    var jsonLen = exports.getDebugPausedJson();',
'    var snapshot = readJsonFromLargeBuffer(jsonLen);',
'    self.postMessage({ type: "debugPaused", snapshot: snapshot });',
'}',
'',
'async function initWasm(sabuffer) {',
'    debugSab = sabuffer;',
'    if (debugSab) {',
'        debugView = new Int32Array(debugSab);',
'    }',
'    try {',
'        self.importScripts(_basePath + "player-wasm.wasm-runtime.js");',
'        teavm = await TeaVM.wasm.load(_basePath + "player-wasm.wasm", {',
'            installImports: function(importObj, controller) {',
'                importObj.libreshockwave = {',
'                    fetchGet: handleFetchGet,',
'                    fetchPost: handleFetchPost,',
'                    debugWait: debugWait,',
'                    debugResume: debugResume,',
'                    debugNotifyPaused: debugNotifyPaused',
'                };',
'            }',
'        });',
'        exports = teavm.instance.exports;',
'        await teavm.main([]);',
'        self.postMessage({ type: "ready" });',
'    } catch (e) {',
'        self.postMessage({ type: "error", message: "WASM init failed: " + e.message });',
'    }',
'}',
'',
'function loadMovie(movieBytes, basePath) {',
'    try {',
'        console.log("[Worker] loadMovie start, bytes=" + movieBytes.length + " basePath=" + basePath);',
'        var basePathBytes = new TextEncoder().encode(basePath || "");',
'        var stringBufAddr = exports.getStringBufferAddress();',
'        var stringBuf = new Uint8Array(getMemory(), stringBufAddr, 4096);',
'        stringBuf.set(basePathBytes);',
'        exports.allocateMovieBuffer(movieBytes.length);',
'        var movieBufAddr = exports.getMovieBufferAddress();',
'        var movieBuf = new Uint8Array(getMemory(), movieBufAddr, movieBytes.length);',
'        movieBuf.set(movieBytes);',
'        var result = exports.loadMovie(movieBytes.length, basePathBytes.length);',
'        clearPendingException();',
'        if (result === 0) {',
'            self.postMessage({ type: "error", message: "Failed to load movie" });',
'            return;',
'        }',
'        var width = (result >> 16) & 0xFFFF;',
'        var height = result & 0xFFFF;',
'        console.log("[Worker] movie loaded: " + width + "x" + height);',
'        self.postMessage({',
'            type: "movieLoaded",',
'            width: width,',
'            height: height,',
'            frameCount: exports.getFrameCount(),',
'            tempo: exports.getTempo()',
'        });',
'    } catch (e) {',
'        console.error("[Worker] loadMovie exception:", e);',
'        self.postMessage({ type: "error", message: "loadMovie failed: " + e.message });',
'    }',
'}',
'',
'function checkAndLogError() {',
'    try {',
'        var errLen = exports.getLastError();',
'        if (errLen > 0) {',
'            var errMsg = readStringFromBuffer(errLen);',
'            console.error("[Worker] WASM error: " + errMsg);',
'            return errMsg;',
'        }',
'    } catch (e) {}',
'    return null;',
'}',
'',
'function tickFrame() {',
'    try {',
'        var stillPlaying = exports.tick();',
'        clearPendingException();',
'        if (stillPlaying === 0) {',
'            checkAndLogError();',
'        }',
'        var frameJsonLen = exports.getFrameDataJson();',
'        clearPendingException();',
'        var frameData = null;',
'        if (frameJsonLen > 0) {',
'            frameData = readJsonFromLargeBuffer(frameJsonLen);',
'        } else if (frameJsonLen === 0) {',
'            checkAndLogError();',
'        }',
'        if (_tickCount <= 5) {',
'            console.log("[Worker] tick=" + _tickCount + " frameJsonLen=" + frameJsonLen +',
'                " sprites=" + (frameData && frameData.sprites ? frameData.sprites.length : "null") +',
'                " stillPlaying=" + stillPlaying);',
'        }',
'        _tickCount++;',
'        var pixelPtr = exports.render();',
'        clearPendingException();',
'        var pixels = null;',
'        if (pixelPtr > 0) {',
'            var w = exports.getStageWidth();',
'            var h = exports.getStageHeight();',
'            var src = new Uint8ClampedArray(getMemory(), pixelPtr, w * h * 4);',
'            pixels = new Uint8ClampedArray(src.length);',
'            pixels.set(src);',
'        }',
'        self.postMessage({',
'            type: "frameData",',
'            stillPlaying: stillPlaying !== 0,',
'            frame: exports.getCurrentFrame(),',
'            frameCount: exports.getFrameCount(),',
'            frameData: frameData,',
'            pixels: pixels',
'        }, pixels ? [pixels.buffer] : []);',
'    } catch (e) {',
'        console.error("[Worker] tickFrame exception:", e);',
'        self.postMessage({ type: "frameData", stillPlaying: true, frame: 0, frameCount: 0, frameData: null, pixels: null });',
'    }',
'}',
'',
'function sendBitmapData(memberId) {',
'    var ptr = exports.getBitmapData(memberId);',
'    if (ptr === 0) {',
'        self.postMessage({ type: "bitmapNotFound", memberId: memberId });',
'        return;',
'    }',
'    var w = exports.getBitmapWidth(memberId);',
'    var h = exports.getBitmapHeight(memberId);',
'    if (w <= 0 || h <= 0) return;',
'    var src = new Uint8ClampedArray(getMemory(), ptr, w * h * 4);',
'    var rgba = new Uint8ClampedArray(src.length);',
'    rgba.set(src);',
'    self.postMessage({',
'        type: "bitmapData",',
'        memberId: memberId,',
'        width: w,',
'        height: h,',
'        rgba: rgba.buffer',
'    }, [rgba.buffer]);',
'}',
'',
'function deliverFetchResult(taskId, data) {',
'    var bytes = new Uint8Array(data);',
'    exports.allocateNetBuffer(bytes.length);',
'    var netBufAddr = exports.getNetBufferAddress();',
'    var netBuf = new Uint8Array(getMemory(), netBufAddr, bytes.length);',
'    netBuf.set(bytes);',
'    exports.onFetchComplete(taskId, bytes.length);',
'    clearPendingException();',
'}',
'',
'function deliverFetchError(taskId, status) {',
'    exports.onFetchError(taskId, status || 0);',
'    clearPendingException();',
'}',
'',
'self.onmessage = function(e) {',
'    var msg = e.data;',
'    switch (msg.cmd) {',
'        case "init":',
'            initWasm(msg.sab);',
'            break;',
'        case "loadMovie":',
'            loadMovie(new Uint8Array(msg.bytes), msg.basePath);',
'            break;',
'        case "play":',
'            _tickCount = 0;',
'            try {',
'                exports.play();',
'                clearPendingException();',
'                var playErr = checkAndLogError();',
'                console.log("[Worker] play() called" + (playErr ? " WITH ERROR: " + playErr : " successfully"));',
'            } catch(e) {',
'                console.error("[Worker] play() threw:", e);',
'                clearPendingException();',
'            }',
'            self.postMessage({ type: "stateChange", state: "playing" });',
'            break;',
'        case "pause":',
'            exports.pause();',
'            clearPendingException();',
'            self.postMessage({ type: "stateChange", state: "paused" });',
'            break;',
'        case "stop":',
'            exports.stop();',
'            clearPendingException();',
'            self.postMessage({ type: "stateChange", state: "stopped" });',
'            break;',
'        case "tick":',
'            tickFrame();',
'            break;',
'        case "goToFrame":',
'            exports.goToFrame(msg.frame);',
'            tickFrame();',
'            break;',
'        case "stepForward":',
'            exports.stepForward();',
'            tickFrame();',
'            break;',
'        case "stepBackward":',
'            exports.stepBackward();',
'            tickFrame();',
'            break;',
'        case "enableDebug":',
'            exports.enableDebug();',
'            self.postMessage({ type: "debugEnabled" });',
'            break;',
'        case "toggleBreakpoint":',
'            var added = exports.toggleBreakpoint(msg.scriptId, msg.handlerIndex, msg.offset);',
'            self.postMessage({ type: "breakpointToggled", scriptId: msg.scriptId, offset: msg.offset, added: added === 1 });',
'            break;',
'        case "clearBreakpoints":',
'            exports.clearBreakpoints();',
'            self.postMessage({ type: "breakpointsCleared" });',
'            break;',
'        case "debugStepInto":',
'            exports.debugStepInto();',
'            break;',
'        case "debugStepOver":',
'            exports.debugStepOver();',
'            break;',
'        case "debugStepOut":',
'            exports.debugStepOut();',
'            break;',
'        case "debugContinue":',
'            exports.debugContinue();',
'            break;',
'        case "debugPause":',
'            exports.debugPause();',
'            break;',
'        case "getScriptList": {',
'            var len = exports.getScriptList();',
'            var data = readJsonFromLargeBuffer(len);',
'            self.postMessage({ type: "scriptList", scripts: data });',
'            break;',
'        }',
'        case "getHandlerBytecode": {',
'            var len = exports.getHandlerBytecode(msg.scriptId, msg.handlerIndex);',
'            var data = readJsonFromLargeBuffer(len);',
'            self.postMessage({ type: "handlerBytecode", scriptId: msg.scriptId, handlerIndex: msg.handlerIndex, instructions: data });',
'            break;',
'        }',
'        case "getHandlerDetails": {',
'            var len = exports.getHandlerDetails(msg.scriptId, msg.handlerIndex);',
'            var data = readJsonFromLargeBuffer(len);',
'            self.postMessage({ type: "handlerDetails", details: data });',
'            break;',
'        }',
'        case "getBitmapData":',
'            sendBitmapData(msg.memberId);',
'            break;',
'        case "addWatch": {',
'            var wlen = writeStringToBuffer(msg.expression);',
'            exports.addWatch(wlen);',
'            var wjlen = exports.getWatches();',
'            self.postMessage({ type: "watchList", watches: readJsonFromLargeBuffer(wjlen) });',
'            break;',
'        }',
'        case "removeWatch": {',
'            var rlen = writeStringToBuffer(msg.id);',
'            exports.removeWatch(rlen);',
'            var rwlen = exports.getWatches();',
'            self.postMessage({ type: "watchList", watches: readJsonFromLargeBuffer(rwlen) });',
'            break;',
'        }',
'        case "clearWatches":',
'            exports.clearWatches();',
'            self.postMessage({ type: "watchList", watches: [] });',
'            break;',
'        case "getDebugSnapshot": {',
'            var slen = exports.getDebugSnapshot();',
'            self.postMessage({ type: "debugSnapshot", snapshot: readJsonFromLargeBuffer(slen) });',
'            break;',
'        }',
'        case "serializeBreakpoints": {',
'            var blen = exports.serializeBreakpoints();',
'            var bdata = readJsonFromLargeBuffer(blen);',
'            self.postMessage({ type: "serializedBreakpoints", data: bdata });',
'            break;',
'        }',
'        case "deserializeBreakpoints": {',
'            var dlen = writeStringToBuffer(msg.data);',
'            exports.deserializeBreakpoints(dlen);',
'            break;',
'        }',
'        case "getState": {',
'            self.postMessage({',
'                type: "state",',
'                frame: exports.getCurrentFrame(),',
'                frameCount: exports.getFrameCount(),',
'                tempo: exports.getTempo(),',
'                debugState: exports.getDebugState()',
'            });',
'            break;',
'        }',
'        case "fetchComplete":',
'            deliverFetchResult(msg.taskId, msg.data);',
'            break;',
'        case "fetchError":',
'            deliverFetchError(msg.taskId, msg.status);',
'            break;',
'        case "preloadAllCasts": {',
'            var count = exports.preloadAllCasts();',
'            self.postMessage({ type: "preloadStarted", count: count });',
'            break;',
'        }',
'        case "setExternalParam": {',
'            var keyBytes = new TextEncoder().encode(msg.key);',
'            var valueBytes = new TextEncoder().encode(msg.value);',
'            var sbAddr = exports.getStringBufferAddress();',
'            var sbuf = new Uint8Array(getMemory(), sbAddr, 4096);',
'            sbuf.set(keyBytes);',
'            sbuf.set(valueBytes, keyBytes.length);',
'            exports.setExternalParam(keyBytes.length, valueBytes.length);',
'            clearPendingException();',
'            break;',
'        }',
'        case "clearExternalParams":',
'            exports.clearExternalParams();',
'            clearPendingException();',
'            break;',
'        default:',
'            console.warn("[Worker] Unknown command:", msg.cmd);',
'    }',
'};'
    ].join('\n');
    // --- END WORKER CODE ---
    })();

    // ========================================================================
    // Low-level WASM player engine (handles Worker, Canvas rendering, fetch relay)
    // ========================================================================

    function PlayerEngine() {
        this.worker = null;
        this.canvas = null;
        this.ctx = null;
        this.playing = false;
        this.lastFrameTime = 0;
        this.stageWidth = 640;
        this.stageHeight = 480;
        this.animFrameId = null;
        this.movieLoaded = false;
        this.debugSab = null;
        this.debugView = null;
        this.bitmapCache = new Map();
        this.pendingBitmaps = new Set();
        this._lastFrame = 0;
        this._lastFrameCount = 0;
        this._lastTempo = 15;

        // Callbacks
        this.onMovieLoaded = null;
        this.onFrameUpdate = null;
        this.onError = null;
        this.onPreloadStarted = null;
    }

    PlayerEngine.prototype.setCanvas = function(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d');
    };

    PlayerEngine.prototype.init = function(basePath) {
        var self = this;
        try {
            this.debugSab = new SharedArrayBuffer(4);
            this.debugView = new Int32Array(this.debugSab);
        } catch (e) {}

        return new Promise(function(resolve, reject) {
            // Create worker from inline code with basePath injected
            var blob = new Blob([
                'var _basePath = ' + JSON.stringify(basePath) + ';\n' + _workerCode
            ], { type: 'application/javascript' });
            var workerUrl = URL.createObjectURL(blob);
            self.worker = new Worker(workerUrl);
            URL.revokeObjectURL(workerUrl);

            self.worker.onmessage = function(e) { self._onMsg(e.data); };
            self.worker.onerror = function(e) { reject(new Error('Worker error: ' + e.message)); };
            self._initResolve = resolve;
            self._initReject = reject;
            self.worker.postMessage({ cmd: 'init', sab: self.debugSab });
        });
    };

    PlayerEngine.prototype._onMsg = function(msg) {
        switch (msg.type) {
            case 'ready':
                if (this._initResolve) { this._initResolve(); this._initResolve = null; }
                break;
            case 'error':
                if (this._initReject) { this._initReject(new Error(msg.message)); this._initReject = null; }
                if (this.onError) this.onError(msg.message);
                break;
            case 'movieLoaded':
                this.stageWidth = msg.width;
                this.stageHeight = msg.height;
                this.canvas.width = msg.width;
                this.canvas.height = msg.height;
                this.movieLoaded = true;
                this._lastTempo = msg.tempo;
                this._lastFrameCount = msg.frameCount;
                if (this.onMovieLoaded) this.onMovieLoaded(msg);
                this._send('tick');
                break;
            case 'frameData':
                this._onFrameData(msg);
                break;
            case 'bitmapData':
                this._onBitmapData(msg);
                break;
            case 'bitmapNotFound':
                this.pendingBitmaps.delete(msg.memberId);
                break;
            case 'fetchRequest':
                this._handleFetch(msg);
                break;
            case 'preloadStarted':
                if (this.onPreloadStarted) this.onPreloadStarted(msg.count);
                break;
            case 'stateChange':
            case 'log':
                break;
        }
    };

    PlayerEngine.prototype.loadMovie = function(bytes, basePath) {
        var b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
        this.bitmapCache.clear();
        this.pendingBitmaps.clear();
        this.worker.postMessage({ cmd: 'loadMovie', bytes: b.buffer, basePath: basePath || '' }, [b.buffer]);
    };

    PlayerEngine.prototype.play = function() {
        this._send('play');
        if (!this.playing) { this.playing = true; this.lastFrameTime = 0; this._startLoop(); }
    };

    PlayerEngine.prototype.pause = function() {
        this._send('pause'); this.playing = false; this._stopLoop();
    };

    PlayerEngine.prototype.stop = function() {
        this._send('stop'); this.playing = false; this._stopLoop();
    };

    PlayerEngine.prototype.goToFrame = function(f) { this._send('goToFrame', { frame: f }); };
    PlayerEngine.prototype.stepForward = function() { this._send('stepForward'); };
    PlayerEngine.prototype.stepBackward = function() { this._send('stepBackward'); };
    PlayerEngine.prototype.getCurrentFrame = function() { return this._lastFrame || 0; };
    PlayerEngine.prototype.getFrameCount = function() { return this._lastFrameCount || 0; };

    PlayerEngine.prototype.setExternalParam = function(k, v) {
        this._send('setExternalParam', { key: k, value: v });
    };

    PlayerEngine.prototype.clearExternalParams = function() {
        this._send('clearExternalParams');
    };

    PlayerEngine.prototype._send = function(cmd, extra) {
        var m = { cmd: cmd };
        if (extra) { for (var k in extra) m[k] = extra[k]; }
        this.worker.postMessage(m);
    };

    PlayerEngine.prototype._startLoop = function() {
        var self = this;
        function loop(ts) {
            if (!self.playing) return;
            var tempo = self._lastTempo || 15;
            var ms = 1000.0 / (tempo > 0 ? tempo : 15);
            if (self.lastFrameTime === 0) self.lastFrameTime = ts;
            if (ts - self.lastFrameTime >= ms) {
                self.lastFrameTime = ts - ((ts - self.lastFrameTime) % ms);
                self._send('tick');
            }
            self.animFrameId = requestAnimationFrame(loop);
        }
        this.animFrameId = requestAnimationFrame(loop);
    };

    PlayerEngine.prototype._stopLoop = function() {
        if (this.animFrameId) { cancelAnimationFrame(this.animFrameId); this.animFrameId = null; }
    };

    PlayerEngine.prototype._onFrameData = function(msg) {
        this._lastFrame = msg.frame;
        this._lastFrameCount = msg.frameCount;
        if (this.onFrameUpdate) this.onFrameUpdate(msg.frame, msg.frameCount);
        if (!msg.stillPlaying && this.playing && msg.frameData) {
            this.playing = false; this._stopLoop();
        }
        if (msg.frameData && msg.frameData.sprites) this._renderSprites(msg.frameData);
        else if (msg.pixels) this._renderPixels(msg.pixels);
    };

    PlayerEngine.prototype._renderSprites = function(fd) {
        var ctx = this.ctx; if (!ctx) return;
        var bg = (typeof fd.bg === 'number') ? fd.bg : 0xFFFFFF;
        ctx.fillStyle = '#' + (bg & 0xFFFFFF).toString(16).padStart(6, '0');
        ctx.fillRect(0, 0, this.stageWidth, this.stageHeight);
        var sprites = fd.sprites; if (!sprites) return;
        for (var i = 0; i < sprites.length; i++) {
            var s = sprites[i];
            if (s.type === 'BITMAP' && s.memberId > 0 && s.visible) this._reqBitmap(s.memberId);
        }
        for (var i = 0; i < sprites.length; i++) {
            var sp = sprites[i]; if (!sp.visible) continue;
            if (sp.type === 'BITMAP') this._drawBitmap(ctx, sp);
            else if (sp.type === 'TEXT' || sp.type === 'BUTTON') this._drawText(ctx, sp);
            else if (sp.type === 'SHAPE') this._drawShape(ctx, sp);
        }
    };

    PlayerEngine.prototype._drawBitmap = function(ctx, sp) {
        var bmp = this.bitmapCache.get(sp.memberId);
        if (!bmp) return;
        var prevAlpha = ctx.globalAlpha;
        if (sp.blend !== undefined && sp.blend < 100) ctx.globalAlpha = sp.blend / 100;
        ctx.drawImage(bmp, sp.x, sp.y, sp.w > 0 ? sp.w : bmp.width, sp.h > 0 ? sp.h : bmp.height);
        ctx.globalAlpha = prevAlpha;
    };

    PlayerEngine.prototype._drawText = function(ctx, sp) {
        if (!sp.textContent) return;
        var prevAlpha = ctx.globalAlpha;
        if (sp.blend !== undefined && sp.blend < 100) ctx.globalAlpha = sp.blend / 100;
        var fs = sp.fontSize || 12;
        ctx.font = fs + 'px serif';
        ctx.fillStyle = '#' + ((sp.foreColor || 0) & 0xFFFFFF).toString(16).padStart(6, '0');
        var lines = sp.textContent.split(/\r\n|\r|\n/);
        for (var j = 0; j < lines.length; j++) ctx.fillText(lines[j], sp.x, sp.y + fs + j * (fs + 2));
        ctx.globalAlpha = prevAlpha;
    };

    PlayerEngine.prototype._drawShape = function(ctx, sp) {
        var prevAlpha = ctx.globalAlpha;
        if (sp.blend !== undefined && sp.blend < 100) ctx.globalAlpha = sp.blend / 100;
        ctx.fillStyle = '#' + ((sp.foreColor || 0) & 0xFFFFFF).toString(16).padStart(6, '0');
        ctx.fillRect(sp.x, sp.y, sp.w > 0 ? sp.w : 50, sp.h > 0 ? sp.h : 50);
        ctx.globalAlpha = prevAlpha;
    };

    PlayerEngine.prototype._renderPixels = function(px) {
        if (!this.ctx) return;
        var id = this.ctx.createImageData(this.stageWidth, this.stageHeight);
        id.data.set(new Uint8ClampedArray(px));
        this.ctx.putImageData(id, 0, 0);
    };

    PlayerEngine.prototype._onBitmapData = function(msg) {
        this.pendingBitmaps.delete(msg.memberId);
        var id = new ImageData(new Uint8ClampedArray(msg.rgba), msg.width, msg.height);
        var self = this;
        createImageBitmap(id).then(function(bmp) { self.bitmapCache.set(msg.memberId, bmp); });
    };

    PlayerEngine.prototype._reqBitmap = function(mid) {
        if (this.bitmapCache.has(mid) || this.pendingBitmaps.has(mid)) return;
        this.pendingBitmaps.add(mid);
        this._send('getBitmapData', { memberId: mid });
    };

    PlayerEngine.prototype._handleFetch = function(msg) {
        var self = this;
        var opts = {};
        if (msg.method === 'POST') { opts.method = 'POST'; opts.body = msg.postData; opts.headers = { 'Content-Type': 'application/x-www-form-urlencoded' }; }
        fetch(msg.url, opts)
            .then(function(r) { if (!r.ok) throw r.status; return r.arrayBuffer(); })
            .then(function(buf) { self.worker.postMessage({ cmd: 'fetchComplete', taskId: msg.taskId, data: buf }, [buf]); })
            .catch(function(e) { self.worker.postMessage({ cmd: 'fetchError', taskId: msg.taskId, status: typeof e === 'number' ? e : 0 }); });
    };

    PlayerEngine.prototype.destroy = function() {
        this._stopLoop();
        if (this.worker) { this.worker.terminate(); this.worker = null; }
        this.bitmapCache.clear();
    };

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * Create a player attached to a canvas element.
     *
     * @param {string|HTMLCanvasElement} canvas - Canvas element or its ID.
     * @param {Object} [options]
     * @param {string}  [options.basePath]  - Directory containing WASM files.
     * @param {Object}  [options.params]    - External parameters (e.g. { sw1: "..." }).
     * @param {boolean} [options.autoplay]  - Start playing after load (default: true).
     * @param {boolean} [options.remember]  - Persist params/URL in localStorage (default: false).
     * @param {Function} [options.onLoad]   - Called with { width, height, frameCount, tempo }.
     * @param {Function} [options.onError]  - Called with error message string.
     * @param {Function} [options.onFrame]  - Called with (frame, total) on each frame.
     * @returns {ShockwavePlayer}
     */
    function create(canvas, options) {
        return new ShockwavePlayer(canvas, options || {});
    }

    function ShockwavePlayer(canvas, opts) {
        var el = typeof canvas === 'string' ? document.getElementById(canvas) : canvas;
        if (!el) throw new Error('LibreShockwave: canvas "' + canvas + '" not found');

        this._opts = opts;
        this._basePath = opts.basePath || _autoBasePath;
        this._params = opts.params ? _clone(opts.params) : {};
        this._autoplay = opts.autoplay !== false;
        this._remember = !!opts.remember;
        this._engine = null;
        this._ready = false;
        this._canvas = el;

        // Restore remembered state
        if (this._remember) {
            try {
                var saved = JSON.parse(localStorage.getItem('ls_extParams'));
                if (saved && typeof saved === 'object') {
                    for (var k in saved) {
                        if (!(k in this._params)) this._params[k] = saved[k];
                    }
                }
            } catch(e) {}
        }

        this._initEngine();
    }

    ShockwavePlayer.prototype._initEngine = function() {
        var self = this;
        var engine = new PlayerEngine();
        engine.setCanvas(this._canvas);
        this._engine = engine;

        engine.onMovieLoaded = function(msg) {
            engine.clearExternalParams();
            for (var k in self._params) {
                engine.setExternalParam(k, self._params[k]);
            }
            if (self._opts.onLoad) self._opts.onLoad({ width: msg.width, height: msg.height, frameCount: msg.frameCount, tempo: msg.tempo });
            if (self._autoplay) engine.play();
        };

        engine.onFrameUpdate = function(f, t) {
            if (self._opts.onFrame) self._opts.onFrame(f, t);
        };

        engine.onError = function(m) {
            if (self._opts.onError) self._opts.onError(m);
        };

        engine.init(this._basePath).then(function() {
            self._ready = true;
            if (self._pendingUrl) { self.load(self._pendingUrl); self._pendingUrl = null; }
            if (self._pendingFile) { self.loadFile(self._pendingFile); self._pendingFile = null; }
        }).catch(function(e) {
            if (self._opts.onError) self._opts.onError(e.message);
        });
    };

    /**
     * Load a movie from a URL.
     */
    ShockwavePlayer.prototype.load = function(url) {
        if (!this._ready) { this._pendingUrl = url; return; }
        var self = this;
        if (this._remember) {
            try { localStorage.setItem('ls_urlInput', url); } catch(e) {}
        }
        fetch(url)
            .then(function(r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.arrayBuffer(); })
            .then(function(buf) { self._engine.loadMovie(new Uint8Array(buf), url); })
            .catch(function(e) { if (self._opts.onError) self._opts.onError(e.message); });
    };

    /**
     * Load a movie from a File object (from an <input type="file">).
     */
    ShockwavePlayer.prototype.loadFile = function(file) {
        if (!this._ready) { this._pendingFile = file; return; }
        var self = this;
        var reader = new FileReader();
        reader.onload = function() {
            self._engine.loadMovie(new Uint8Array(reader.result), file.name);
        };
        reader.readAsArrayBuffer(file);
    };

    /** Start playback. */
    ShockwavePlayer.prototype.play = function() { if (this._engine) this._engine.play(); };

    /** Pause playback. */
    ShockwavePlayer.prototype.pause = function() { if (this._engine) this._engine.pause(); };

    /** Stop playback. */
    ShockwavePlayer.prototype.stop = function() { if (this._engine) this._engine.stop(); };

    /** Jump to a specific frame. */
    ShockwavePlayer.prototype.goToFrame = function(f) { if (this._engine) this._engine.goToFrame(f); };

    /** Step one frame forward. */
    ShockwavePlayer.prototype.stepForward = function() { if (this._engine) this._engine.stepForward(); };

    /** Step one frame backward. */
    ShockwavePlayer.prototype.stepBackward = function() { if (this._engine) this._engine.stepBackward(); };

    /** Get current frame number. */
    ShockwavePlayer.prototype.getCurrentFrame = function() { return this._engine ? this._engine.getCurrentFrame() : 0; };

    /** Get total frame count. */
    ShockwavePlayer.prototype.getFrameCount = function() { return this._engine ? this._engine.getFrameCount() : 0; };

    /**
     * Set a single external parameter (Shockwave PARAM tag).
     */
    ShockwavePlayer.prototype.setParam = function(key, value) {
        this._params[key] = value;
        if (this._engine && this._engine.movieLoaded) this._engine.setExternalParam(key, value);
        if (this._remember) {
            try { localStorage.setItem('ls_extParams', JSON.stringify(this._params)); } catch(e) {}
        }
    };

    /**
     * Set multiple external parameters at once.
     */
    ShockwavePlayer.prototype.setParams = function(obj) {
        for (var k in obj) this.setParam(k, obj[k]);
    };

    /**
     * Tear down the player and terminate the worker.
     */
    ShockwavePlayer.prototype.destroy = function() {
        if (this._engine) { this._engine.destroy(); this._engine = null; }
    };

    function _clone(obj) {
        var r = {};
        for (var k in obj) r[k] = obj[k];
        return r;
    }

    return { create: create };
})();
