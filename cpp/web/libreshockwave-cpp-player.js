'use strict';

var LibreShockwaveCppPlayer = (function() {
    var _autoBasePath = '';

(function() {
    var scripts = document.getElementsByTagName('script');
        for (var i = scripts.length - 1; i >= 0; i--) {
            var src = scripts[i].src || '';
            if (src.indexOf('libreshockwave-cpp-player.js') !== -1) {
                _autoBasePath = src.substring(0, src.lastIndexOf('/') + 1);
                break;
            }
        }
    })();

    function fetchWithTimeout(url, options, timeoutMs) {
        var controller = new AbortController();
        var timer = setTimeout(function() {
            controller.abort();
        }, timeoutMs || 30000);
        var requestOptions = options || {};
        requestOptions.signal = controller.signal;
        return fetch(url, requestOptions).finally(function() {
            clearTimeout(timer);
        });
    }

    function create(canvas, options) {
        return new Player(canvas, options || {});
    }

    function Player(canvas, options) {
        this.canvas = typeof canvas === 'string' ? document.getElementById(canvas) : canvas;
        if (!this.canvas) {
            throw new Error('LibreShockwave C++ canvas not found');
        }
        this.ctx = this.canvas.getContext('2d');
        this.options = options;
        this.basePath = new URL(options.basePath || _autoBasePath || './', document.baseURI).href;
        this.worker = null;
        this.ready = false;
        this.pending = [];
        this.playing = false;
        this.loadSeq = 0;
        this.lastFrame = null;
        this.tickMs = options.tickMs || 33;
        this.scriptTimeoutMs = options.scriptTimeoutMs == null ? 1000 : options.scriptTimeoutMs;
        this.params = options.params || {};
        this.movieProperties = options.movieProperties || {};
        this.initialBuiltinSymbols = options.initialBuiltinSymbols || {};
        this.debugLogsEnabled = !!options.debugLogsEnabled;
        this.debugPlayback = !!options.debugPlayback;
        this.traceHandlers = options.traceHandlers ? options.traceHandlers.slice(0) : [];
        this.diagnosticSeq = 0;
        this.diagnosticResolvers = {};
        this.onReady = options.onReady || null;
        this.onLoad = options.onLoad || null;
        this.onFrame = options.onFrame || null;
        this.onError = options.onError || null;
        this.onDebugLog = options.onDebugLog || null;
        this.onGotoNetPage = options.onGotoNetPage || null;
        this.onGotoNetMovie = options.onGotoNetMovie || null;
        this.loadedMovieUrl = null;
        this.audioCtx = null;
        this.audioChannels = {};
        this.audioChannelTokens = {};
        this.audioTokenSeq = 0;
        this._sharedFrameBuffer = null;
        this._sharedFrameControl = null;
        this._sharedFrameBytes = null;
        this._sharedFrameCapacity = 0;
        this.baseFrame = null;
        this.compositeData = null;
        this.cursorBitmap = null;
        this.caretInfo = null;
        this.selectionRects = null;
        this.cursorDirty = true;
        this.cursorRafId = null;
        this.cursorUsingInterval = false;
        this.cursorFps = options.cursorFps || 0;
        this.mouseX = 0;
        this.mouseY = 0;
        this._initWorker();
        this._initInput();
    }

    Player.prototype._initWorker = function() {
        var self = this;
        this.worker = new Worker(new URL('libreshockwave-cpp-worker.js', this.basePath).href);
        this.worker.onmessage = function(event) {
            self._handleWorkerMessage(event.data || {});
        };
        this.worker.onerror = function(event) {
            self._emitError(event.message || 'C++ WASM worker error');
        };
        var initMessage = {
            type: 'init',
            basePath: this.basePath,
            pageProtocol: location.protocol,
            debugLogsEnabled: this.debugLogsEnabled,
            debugPlayback: this.debugPlayback
        };
        this._initSharedFrameTransport(initMessage);
        this.worker.postMessage(initMessage);
    };

    Player.prototype._initSharedFrameTransport = function(initMessage) {
        this._sharedFrameBuffer = null;
        this._sharedFrameControl = null;
        this._sharedFrameBytes = null;
        this._sharedFrameCapacity = 0;
        if (this.options.sharedFrameBuffer === false ||
                !window.crossOriginIsolated ||
                typeof SharedArrayBuffer !== 'function' ||
                typeof Atomics !== 'object') {
            return;
        }
        var capacity = this.options.sharedFrameCapacity || 2048 * 2048 * 4;
        this._sharedFrameBuffer = new SharedArrayBuffer(capacity);
        this._sharedFrameControl = new Int32Array(new SharedArrayBuffer(16));
        this._sharedFrameBytes = new Uint8ClampedArray(this._sharedFrameBuffer);
        this._sharedFrameCapacity = capacity;
        initMessage.sharedFrameBuffer = this._sharedFrameBuffer;
        initMessage.sharedFrameControl = this._sharedFrameControl.buffer;
        initMessage.sharedFrameCapacity = capacity;
    };

    Player.prototype._handleWorkerMessage = function(message) {
        switch (message.type) {
            case 'ready':
                this.ready = true;
                this._flushPending();
                if (this.onReady) {
                    this.onReady();
                }
                break;
            case 'loaded':
                if (this._isStaleLoadMessage(message)) {
                    break;
                }
                this.canvas.width = message.width || this.canvas.width;
                this.canvas.height = message.height || this.canvas.height;
                if (this.onLoad) {
                    this.onLoad(message);
                }
                break;
            case 'frame':
                if (this._isStaleLoadMessage(message)) {
                    break;
                }
                this._drawFrame(message);
                break;
            case 'audio':
                this._handleAudio(message);
                break;
            case 'gotoNetPage':
                this._handleGotoNetPage(message.url, message.target);
                break;
            case 'gotoNetMovie':
                this._handleGotoNetMovie(message.url);
                break;
            case 'fetchRelay':
                this._handleFetchRelay(message);
                break;
            case 'selectedText':
            case 'cutText':
                this._writeClipboardText(message.text || '');
                break;
            case 'debugLog':
                if (this.onDebugLog) {
                    this.onDebugLog(message.message || message.msg || '');
                }
                break;
            case 'callStack':
                this._resolveDiagnostic(message.requestId, message.callStack || '');
                break;
            case 'windowSpriteDiagnostics':
            case 'visibleTextDiagnostics':
            case 'bootstrapDiagnostics':
            case 'scriptDiagnostics':
                this._resolveDiagnostic(message.requestId, message.diagnostics || '');
                break;
            case 'runtimeDiagnostics':
            case 'musWebSocketSelfTest':
            case 'cxxSmusBridgeSelfTest':
                this._resolveDiagnostic(message.requestId, message.diagnostics || {});
                break;
            case 'testError':
                this._resolveDiagnostic(message.requestId, !!message.handled);
                break;
            case 'error':
                if (this._isStaleLoadMessage(message)) {
                    break;
                }
                this._emitError(message.message || message.msg || 'C++ WASM runtime error');
                break;
        }
    };

    Player.prototype._post = function(message, transfer) {
        if (!this.ready && message.type !== 'init') {
            this.pending.push({ message: message, transfer: transfer || [] });
            return;
        }
        this.worker.postMessage(message, transfer || []);
    };

    Player.prototype._flushPending = function() {
        while (this.pending.length > 0) {
            var item = this.pending.shift();
            this.worker.postMessage(item.message, item.transfer);
        }
    };

    Player.prototype._emitError = function(message) {
        if (this.onError) {
            this.onError(message);
        } else {
            console.error('[LibreShockwave C++] ' + message);
        }
    };

    Player.prototype._isStaleLoadMessage = function(message) {
        return message.loadSeq && message.loadSeq !== this.loadSeq;
    };

    Player.prototype._writeClipboardText = function(text) {
        if (!text || !navigator.clipboard || !navigator.clipboard.writeText) {
            return;
        }
        navigator.clipboard.writeText(text).catch(function() {});
    };

    Player.prototype._requestDiagnostic = function(type, payload) {
        var self = this;
        if (!this.worker || !this.ready) {
            return Promise.resolve('');
        }
        var requestId = ++this.diagnosticSeq;
        return new Promise(function(resolve) {
            self.diagnosticResolvers[requestId] = resolve;
            self.worker.postMessage(Object.assign({}, payload || {}, { type: type, requestId: requestId }));
        });
    };

    Player.prototype._resolveDiagnostic = function(requestId, value) {
        var resolver = this.diagnosticResolvers[requestId];
        if (!resolver) {
            return;
        }
        delete this.diagnosticResolvers[requestId];
        resolver(value);
    };

    Player.prototype._drawFrame = function(message) {
        var width = message.width | 0;
        var height = message.height | 0;
        if (width <= 0 || height <= 0) {
            return;
        }
        if (this.canvas.width !== width) {
            this.canvas.width = width;
        }
        if (this.canvas.height !== height) {
            this.canvas.height = height;
        }
        var rgba = null;
        if (message.sharedFrame && this._sharedFrameBytes && this._sharedFrameControl) {
            var sharedLength = width * height * 4;
            var sharedSeq = Atomics.load(this._sharedFrameControl, 0);
            if (sharedSeq === message.sharedSeq && sharedLength <= this._sharedFrameCapacity) {
                rgba = new Uint8ClampedArray(sharedLength);
                rgba.set(new Uint8ClampedArray(this._sharedFrameBuffer, 0, sharedLength));
            }
        }
        if (!rgba && message.rgba) {
            rgba = message.rgba instanceof Uint8ClampedArray
                ? message.rgba
                : new Uint8ClampedArray(message.rgba);
        }
        if (!rgba) {
            return;
        }
        this.baseFrame = new ImageData(rgba, width, height);
        this.cursorBitmap = message.cursorBitmap || null;
        this.caretInfo = message.caretInfo || null;
        this.selectionRects = message.selectionRects || null;
        this.cursorDirty = true;
        this._updateCssCursor(message.cursorType);
        if (this.cursorBitmap) {
            this._startCursorLoop();
        } else {
            this._stopCursorLoop();
        }
        this._compositeOverlayAndBlit();
        this.lastFrame = message;
        if (this.onFrame) {
            this.onFrame(message);
        }
    };

    Player.prototype._updateCssCursor = function(cursorType) {
        var cursorMap = {
            '-1': 'default',
            '0': 'default',
            '1': 'text',
            '2': 'crosshair',
            '3': 'move',
            '4': 'wait',
            '5': 'none',
            '6': 'pointer'
        };
        var cursor = cursorMap[String(cursorType)] || 'default';
        if (this.canvas.style.cursor !== cursor) {
            this.canvas.style.cursor = cursor;
        }
    };

    Player.prototype._compositeOverlayAndBlit = function() {
        if (!this.baseFrame) {
            return;
        }
        var cursor = this.cursorBitmap;
        if (!cursor && !this.caretInfo && !this.selectionRects) {
            this.ctx.putImageData(this.baseFrame, 0, 0);
            this.cursorDirty = false;
            return;
        }

        var width = this.baseFrame.width;
        var height = this.baseFrame.height;
        if (!this.compositeData ||
                this.compositeData.width !== width ||
                this.compositeData.height !== height) {
            this.compositeData = this.ctx.createImageData(width, height);
        }
        var dst = this.compositeData.data;
        dst.set(this.baseFrame.data);

        if (cursor) {
            this._overlayCursor(dst, width, height, cursor);
        }
        this._overlaySelection(dst, width, height);
        this._overlayCaret(dst, width, height);

        this.ctx.putImageData(this.compositeData, 0, 0);
        this.cursorDirty = false;
    };

    Player.prototype._overlayCursor = function(dst, width, height, cursor) {
        var drawX = this.mouseX - cursor.regX;
        var drawY = this.mouseY - cursor.regY;
        var crgba = cursor.rgba instanceof Uint8ClampedArray
            ? cursor.rgba
            : new Uint8ClampedArray(cursor.rgba);
        for (var cy = 0; cy < cursor.h; cy++) {
            var dstY = drawY + cy;
            if (dstY < 0 || dstY >= height) {
                continue;
            }
            for (var cx = 0; cx < cursor.w; cx++) {
                var dstX = drawX + cx;
                if (dstX < 0 || dstX >= width) {
                    continue;
                }
                var srcOff = (cy * cursor.w + cx) * 4;
                var alpha = crgba[srcOff + 3];
                if (alpha === 0) {
                    continue;
                }
                var dstOff = (dstY * width + dstX) * 4;
                if (alpha === 255) {
                    dst[dstOff] = crgba[srcOff];
                    dst[dstOff + 1] = crgba[srcOff + 1];
                    dst[dstOff + 2] = crgba[srcOff + 2];
                    dst[dstOff + 3] = 255;
                    continue;
                }
                var invAlpha = 255 - alpha;
                dst[dstOff] = (crgba[srcOff] * alpha + dst[dstOff] * invAlpha) / 255 | 0;
                dst[dstOff + 1] = (crgba[srcOff + 1] * alpha + dst[dstOff + 1] * invAlpha) / 255 | 0;
                dst[dstOff + 2] = (crgba[srcOff + 2] * alpha + dst[dstOff + 2] * invAlpha) / 255 | 0;
                dst[dstOff + 3] = 255;
            }
        }
    };

    Player.prototype._overlaySelection = function(dst, width, height) {
        var rects = this.selectionRects;
        if (!rects) {
            return;
        }
        for (var i = 0; i < rects.length; i++) {
            var rect = rects[i];
            for (var y = 0; y < rect.h; y++) {
                var py = rect.y + y;
                if (py < 0 || py >= height) {
                    continue;
                }
                for (var x = 0; x < rect.w; x++) {
                    var px = rect.x + x;
                    if (px < 0 || px >= width) {
                        continue;
                    }
                    var off = (py * width + px) * 4;
                    dst[off] = 255 - dst[off];
                    dst[off + 1] = 255 - dst[off + 1];
                    dst[off + 2] = 255 - dst[off + 2];
                }
            }
        }
    };

    Player.prototype._overlayCaret = function(dst, width, height) {
        var caret = this.caretInfo;
        if (!caret || caret.h <= 0) {
            return;
        }
        for (var y = 0; y < caret.h; y++) {
            var py = caret.y + y;
            if (py < 0 || py >= height || caret.x < 0 || caret.x >= width) {
                continue;
            }
            var off = (py * width + caret.x) * 4;
            dst[off] = 0;
            dst[off + 1] = 0;
            dst[off + 2] = 0;
            dst[off + 3] = 255;
        }
    };

    Player.prototype._startCursorLoop = function() {
        if (this.cursorRafId) {
            return;
        }
        var self = this;
        if (this.cursorFps > 0) {
            this.cursorUsingInterval = true;
            this.cursorRafId = setInterval(function() {
                if (self.cursorDirty) {
                    self._compositeOverlayAndBlit();
                }
            }, 1000 / this.cursorFps);
            return;
        }
        this.cursorUsingInterval = false;
        function cursorFrame() {
            if (self.cursorDirty) {
                self._compositeOverlayAndBlit();
            }
            self.cursorRafId = requestAnimationFrame(cursorFrame);
        }
        this.cursorRafId = requestAnimationFrame(cursorFrame);
    };

    Player.prototype._stopCursorLoop = function() {
        if (!this.cursorRafId) {
            return;
        }
        if (this.cursorUsingInterval) {
            clearInterval(this.cursorRafId);
        } else {
            cancelAnimationFrame(this.cursorRafId);
        }
        this.cursorRafId = null;
    };

    Player.prototype._initInput = function() {
        var self = this;
        var canvas = this.canvas;
        if (!canvas.hasAttribute('tabindex')) {
            canvas.setAttribute('tabindex', '0');
        }
        canvas.style.outline = 'none';

        function point(event) {
            var rect = canvas.getBoundingClientRect();
            var scaleX = rect.width > 0 ? canvas.width / rect.width : 1;
            var scaleY = rect.height > 0 ? canvas.height / rect.height : 1;
            return {
                x: Math.max(0, Math.min(canvas.width - 1, Math.floor((event.clientX - rect.left) * scaleX))),
                y: Math.max(0, Math.min(canvas.height - 1, Math.floor((event.clientY - rect.top) * scaleY)))
            };
        }

        function keyChar(event) {
            if (event.key === 'Enter') {
                return '\r';
            }
            if (event.key === 'Tab') {
                return '\t';
            }
            return event.key && event.key.length === 1 ? event.key : '';
        }

        function modifiers(event) {
            return (event.shiftKey ? 1 : 0) | (event.ctrlKey ? 2 : 0) | (event.altKey ? 4 : 0);
        }

        canvas.addEventListener('mousemove', function(event) {
            var p = point(event);
            self.mouseX = p.x;
            self.mouseY = p.y;
            self.cursorDirty = true;
            if (self.cursorBitmap && self.baseFrame) {
                self._compositeOverlayAndBlit();
            }
            self._post({ type: 'mouseMove', x: p.x, y: p.y });
        });
        canvas.addEventListener('mousedown', function(event) {
            canvas.focus();
            var p = point(event);
            self.mouseX = p.x;
            self.mouseY = p.y;
            self._post({ type: 'mouseDown', x: p.x, y: p.y, button: event.button || 0 });
        });
        canvas.addEventListener('mouseup', function(event) {
            var p = point(event);
            self.mouseX = p.x;
            self.mouseY = p.y;
            self._post({ type: 'mouseUp', x: p.x, y: p.y, button: event.button || 0 });
        });
        canvas.addEventListener('keydown', function(event) {
            if (event.ctrlKey || event.metaKey) {
                if (event.key === 'c' || event.key === 'C') {
                    event.preventDefault();
                    self._post({ type: 'getSelectedText' });
                } else if (event.key === 'x' || event.key === 'X') {
                    event.preventDefault();
                    self._post({ type: 'cutSelectedText' });
                } else if (event.key === 'a' || event.key === 'A') {
                    event.preventDefault();
                    self._post({ type: 'selectAll' });
                }
                return;
            }
            event.preventDefault();
            self._post({
                type: 'keyDown',
                keyCode: event.keyCode || 0,
                key: keyChar(event),
                modifiers: modifiers(event)
            });
        });
        canvas.addEventListener('keyup', function(event) {
            if (event.ctrlKey || event.metaKey) {
                return;
            }
            event.preventDefault();
            self._post({
                type: 'keyUp',
                keyCode: event.keyCode || 0,
                key: keyChar(event),
                modifiers: modifiers(event)
            });
        });
        window.addEventListener('blur', function() {
            self._post({ type: 'blur' });
        });
        document.addEventListener('paste', function(event) {
            if (document.activeElement !== canvas || !self.worker) {
                return;
            }
            var text = (event.clipboardData || window.clipboardData).getData('text');
            if (text) {
                event.preventDefault();
                self._post({ type: 'paste', text: text });
            }
        });
    };

    Player.prototype.load = function(url, options) {
        var self = this;
        var loadSeq = ++this.loadSeq;
        var loadOptions = Object.assign({}, options || {}, { loadSeq: loadSeq });
        return fetch(url).then(function(response) {
            if (!response.ok) {
                throw new Error('Movie fetch failed: HTTP ' + response.status);
            }
            return response.arrayBuffer();
        }).then(function(bytes) {
            if (loadSeq !== self.loadSeq) {
                return;
            }
            var basePath = loadOptions.basePath || new URL('.', new URL(url, document.baseURI)).href;
            self.loadedMovieUrl = new URL(url, document.baseURI).href;
            self.loadBytes(bytes, basePath, loadOptions);
        }).catch(function(error) {
            if (loadSeq !== self.loadSeq) {
                return;
            }
            self._emitError(error && error.message ? error.message : String(error));
        });
    };

    Player.prototype.loadBytes = function(bytes, basePath, options) {
        var loadOptions = options || {};
        var loadSeq = loadOptions.loadSeq || ++this.loadSeq;
        var buffer = bytes instanceof ArrayBuffer ? bytes : bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
        this._post({
            type: 'loadMovie',
            loadSeq: loadSeq,
            data: buffer,
            basePath: basePath || this.basePath,
            autoplay: loadOptions.autoplay !== false,
            scriptTimeoutMs: loadOptions.scriptTimeoutMs == null ? this.scriptTimeoutMs : loadOptions.scriptTimeoutMs,
            params: loadOptions.params || this.params,
            movieProperties: loadOptions.movieProperties || this.movieProperties,
            initialBuiltinSymbols: loadOptions.initialBuiltinSymbols || this.initialBuiltinSymbols,
            debugPlayback: loadOptions.debugPlayback == null ? this.debugPlayback : !!loadOptions.debugPlayback,
            traceHandlers: loadOptions.traceHandlers || this.traceHandlers,
            tickMs: this.tickMs
        }, [buffer]);
        this.playing = loadOptions.autoplay !== false;
    };

    Player.prototype.startTicks = function() {
        this.playing = true;
        this._post({ type: 'setTickInterval', tickMs: this.tickMs });
    };

    Player.prototype.stopTicks = function() {
        this.playing = false;
    };

    Player.prototype.play = function() {
        this.playing = true;
        this._post({ type: 'play', tickMs: this.tickMs });
    };

    Player.prototype.setFps = function(fps) {
        var numericFps = Number(fps);
        if (!isFinite(numericFps) || numericFps <= 0) {
            numericFps = 12;
        }
        numericFps = Math.max(1, Math.min(120, numericFps));
        this.tickMs = Math.max(1, Math.round(1000 / numericFps));
        this._post({ type: 'setTickInterval', tickMs: this.tickMs });
    };

    Player.prototype.pause = function() {
        this.stopTicks();
        this._post({ type: 'pause' });
    };

    Player.prototype.stop = function() {
        this.stopTicks();
        this._post({ type: 'stop' });
    };

    Player.prototype.stepForward = function() {
        this._post({ type: 'stepForward' });
    };

    Player.prototype.stepBackward = function() {
        this._post({ type: 'stepBackward' });
    };

    Player.prototype.goToFrame = function(frame) {
        this._post({ type: 'goToFrame', frame: frame | 0 });
    };

    Player.prototype.setParam = function(key, value) {
        this.params[key] = value == null ? '' : String(value);
        this._post({ type: 'setParam', key: key, value: this.params[key] });
    };

    Player.prototype.clearParams = function() {
        this.params = {};
        this._post({ type: 'clearParams' });
    };

    Player.prototype.setMovieProperty = function(key, value) {
        this.movieProperties[key] = value == null ? '' : String(value);
        this._post({ type: 'setMovieProperty', key: key, value: this.movieProperties[key] });
    };

    Player.prototype.setInitialBuiltinSymbol = function(key, value) {
        this.initialBuiltinSymbols[key] = value == null ? '' : String(value);
        this._post({ type: 'setInitialBuiltinSymbol', key: key, value: this.initialBuiltinSymbols[key] });
    };

    Player.prototype.setDebugLogsEnabled = function(enabled) {
        this.debugLogsEnabled = !!enabled;
        this._post({ type: 'setDebugLogsEnabled', enabled: this.debugLogsEnabled });
    };

    Player.prototype.setDebugPlaybackEnabled = function(enabled) {
        this.debugPlayback = !!enabled;
        this._post({ type: 'setDebugPlaybackEnabled', enabled: this.debugPlayback });
    };

    Player.prototype.addTraceHandler = function(name) {
        var traceName = String(name || '');
        if (!traceName) {
            return;
        }
        if (this.traceHandlers.indexOf(traceName) === -1) {
            this.traceHandlers.push(traceName);
        }
        this._post({ type: 'addTraceHandler', name: traceName });
    };

    Player.prototype.removeTraceHandler = function(name) {
        var traceName = String(name || '');
        var index = this.traceHandlers.indexOf(traceName);
        if (index !== -1) {
            this.traceHandlers.splice(index, 1);
        }
        this._post({ type: 'removeTraceHandler', name: traceName });
    };

    Player.prototype.clearTraceHandlers = function() {
        this.traceHandlers = [];
        this._post({ type: 'clearTraceHandlers' });
    };

    Player.prototype.getCallStack = function() {
        return this._requestDiagnostic('getCallStack');
    };

    Player.prototype.getWindowSpriteDiagnostics = function() {
        return this._requestDiagnostic('getWindowSpriteDiagnostics');
    };

    Player.prototype.getVisibleTextDiagnostics = function() {
        return this._requestDiagnostic('getVisibleTextDiagnostics');
    };

    Player.prototype.getBootstrapDiagnostics = function() {
        return this._requestDiagnostic('getBootstrapDiagnostics');
    };

    Player.prototype.getScriptDiagnostics = function() {
        return this._requestDiagnostic('getScriptDiagnostics');
    };

    Player.prototype.getRuntimeDiagnostics = function() {
        if (!this.worker || !this.ready) {
            return Promise.resolve({});
        }
        return this._requestDiagnostic('getRuntimeDiagnostics');
    };

    Player.prototype.triggerTestError = function() {
        if (!this.worker || !this.ready) {
            return Promise.resolve(false);
        }
        return this._requestDiagnostic('triggerTestError');
    };

    Player.prototype.runMusWebSocketSelfTest = function(options) {
        if (!this.worker || !this.ready) {
            return Promise.resolve({ ok: false, error: 'runtime not ready' });
        }
        return this._requestDiagnostic('runMusWebSocketSelfTest', options || {});
    };

    Player.prototype.runCxxSmusBridgeSelfTest = function(options) {
        if (!this.worker || !this.ready) {
            return Promise.resolve({ ok: false, error: 'runtime not ready' });
        }
        return this._requestDiagnostic('runCxxSmusBridgeSelfTest', options || {});
    };

    Player.prototype._handleGotoNetPage = function(url, target) {
        if (this.onGotoNetPage) {
            this.onGotoNetPage(url, target || '');
            return;
        }
        if (!url) {
            return;
        }
        var normalizedTarget = target || '_self';
        if (normalizedTarget === '_blank' || normalizedTarget === 'new') {
            window.open(url, '_blank', 'noopener');
        } else {
            window.location.assign(url);
        }
    };

    Player.prototype._handleGotoNetMovie = function(url) {
        if (this.onGotoNetMovie) {
            this.onGotoNetMovie(url);
            return;
        }
        if (url) {
            this.load(new URL(url, this.loadedMovieUrl || document.baseURI).href);
        }
    };

    Player.prototype._handleFetchRelay = function(message) {
        var worker = this.worker;
        var options = {};
        if (message.method === 'POST') {
            options.method = 'POST';
            options.body = message.postData || null;
            options.headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
        }
        fetchWithTimeout(message.url, options, 30000)
            .then(function(response) {
                if (!response.ok) {
                    throw { status: response.status };
                }
                return response.arrayBuffer();
            })
            .then(function(buffer) {
                if (worker) {
                    worker.postMessage({
                        type: 'fetchRelayResult',
                        relayId: message.relayId,
                        data: buffer
                    }, [buffer]);
                }
            })
            .catch(function(error) {
                if (worker) {
                    worker.postMessage({
                        type: 'fetchRelayResult',
                        relayId: message.relayId,
                        error: true,
                        status: error && error.status ? error.status : 0
                    });
                }
            });
    };

    Player.prototype._handleAudio = function(message) {
        if (!this.audioCtx) {
            try {
                this.audioCtx = new (window.AudioContext || window.webkitAudioContext)();
            } catch (e) {
                return;
            }
        }
        var channel = message.channel;
        var channelKey = String(channel);
        if (message.action === 'stop') {
            this.audioChannelTokens[channelKey] = ++this.audioTokenSeq;
            if (this.audioChannels[channel]) {
                try {
                    this.audioChannels[channel].source.onended = null;
                    this.audioChannels[channel].source.stop();
                } catch (e1) {}
                this.audioChannels[channel] = null;
            }
            return;
        }
        if (message.action === 'volume') {
            if (this.audioChannels[channel] && this.audioChannels[channel].gain) {
                this.audioChannels[channel].gain.gain.value = (message.volume || 0) / 255.0;
            }
            return;
        }
        if (message.action !== 'play' || !message.data) {
            return;
        }
        var token = ++this.audioTokenSeq;
        this.audioChannelTokens[channelKey] = token;
        if (this.audioChannels[channel]) {
            try {
                this.audioChannels[channel].source.onended = null;
                this.audioChannels[channel].source.stop();
            } catch (e2) {}
            this.audioChannels[channel] = null;
        }
        var self = this;
        var worker = this.worker;
        var audioData = message.data.slice ? message.data.slice(0) : message.data;
        var loopCount = message.loopCount || 1;
        var volume = (message.volume == null ? 255 : message.volume) / 255.0;
        this.audioCtx.decodeAudioData(audioData).then(function(buffer) {
            if (self.audioChannelTokens[channelKey] !== token) {
                return;
            }
            var source = self.audioCtx.createBufferSource();
            var gain = self.audioCtx.createGain();
            source.buffer = buffer;
            source.loop = loopCount === 0 || loopCount > 1;
            gain.gain.value = volume;
            source.connect(gain);
            gain.connect(self.audioCtx.destination);
            source.onended = function() {
                if (self.audioChannelTokens[channelKey] !== token) {
                    return;
                }
                self.audioChannels[channel] = null;
                if (worker) {
                    worker.postMessage({ type: 'audioStopped', channel: channel });
                }
            };
            if (loopCount > 1) {
                setTimeout(function() {
                    if (self.audioChannelTokens[channelKey] !== token) {
                        return;
                    }
                    try {
                        source.stop();
                    } catch (e3) {}
                }, buffer.duration * loopCount * 1000);
            }
            source.start();
            self.audioChannels[channel] = { source: source, gain: gain, token: token };
        }).catch(function() {});
    };

    return {
        create: create
    };
})();
