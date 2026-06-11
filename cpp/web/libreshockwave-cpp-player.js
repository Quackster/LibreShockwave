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
        this.lastFrame = null;
        this.timer = null;
        this.tickMs = options.tickMs || 33;
        this.scriptTimeoutMs = options.scriptTimeoutMs == null ? 1000 : options.scriptTimeoutMs;
        this.onReady = options.onReady || null;
        this.onLoad = options.onLoad || null;
        this.onFrame = options.onFrame || null;
        this.onError = options.onError || null;
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
        this.worker.postMessage({ type: 'init', basePath: this.basePath });
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
                this.canvas.width = message.width || this.canvas.width;
                this.canvas.height = message.height || this.canvas.height;
                if (this.onLoad) {
                    this.onLoad(message);
                }
                break;
            case 'frame':
                this._drawFrame(message);
                break;
            case 'error':
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

    Player.prototype._drawFrame = function(message) {
        var width = message.width | 0;
        var height = message.height | 0;
        if (width <= 0 || height <= 0 || !message.rgba) {
            return;
        }
        if (this.canvas.width !== width) {
            this.canvas.width = width;
        }
        if (this.canvas.height !== height) {
            this.canvas.height = height;
        }
        var rgba = message.rgba instanceof Uint8ClampedArray
            ? message.rgba
            : new Uint8ClampedArray(message.rgba);
        var imageData = new ImageData(rgba, width, height);
        this.ctx.putImageData(imageData, 0, 0);
        this.lastFrame = message;
        if (this.onFrame) {
            this.onFrame(message);
        }
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
            self._post({ type: 'mouseMove', x: p.x, y: p.y });
        });
        canvas.addEventListener('mousedown', function(event) {
            canvas.focus();
            var p = point(event);
            self._post({ type: 'mouseDown', x: p.x, y: p.y, button: event.button || 0 });
        });
        canvas.addEventListener('mouseup', function(event) {
            var p = point(event);
            self._post({ type: 'mouseUp', x: p.x, y: p.y, button: event.button || 0 });
        });
        canvas.addEventListener('keydown', function(event) {
            if (event.ctrlKey || event.metaKey) {
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
    };

    Player.prototype.load = function(url, options) {
        var self = this;
        var loadOptions = options || {};
        return fetch(url).then(function(response) {
            if (!response.ok) {
                throw new Error('Movie fetch failed: HTTP ' + response.status);
            }
            return response.arrayBuffer();
        }).then(function(bytes) {
            var basePath = loadOptions.basePath || new URL('.', new URL(url, document.baseURI)).href;
            self.loadBytes(bytes, basePath, loadOptions);
        }).catch(function(error) {
            self._emitError(error && error.message ? error.message : String(error));
        });
    };

    Player.prototype.loadBytes = function(bytes, basePath, options) {
        var loadOptions = options || {};
        var buffer = bytes instanceof ArrayBuffer ? bytes : bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
        this._post({
            type: 'loadMovie',
            data: buffer,
            basePath: basePath || this.basePath,
            autoplay: loadOptions.autoplay !== false,
            scriptTimeoutMs: loadOptions.scriptTimeoutMs == null ? this.scriptTimeoutMs : loadOptions.scriptTimeoutMs
        }, [buffer]);
        if (loadOptions.autoplay !== false) {
            this.startTicks();
        }
    };

    Player.prototype.startTicks = function() {
        var self = this;
        this.playing = true;
        if (this.timer) {
            return;
        }
        this.timer = setInterval(function() {
            self._post({ type: 'tick' });
        }, this.tickMs);
    };

    Player.prototype.stopTicks = function() {
        this.playing = false;
        if (this.timer) {
            clearInterval(this.timer);
            this.timer = null;
        }
    };

    Player.prototype.play = function() {
        this._post({ type: 'play' });
        this.startTicks();
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

    return {
        create: create
    };
})();
