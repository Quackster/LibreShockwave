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
 * Architecture: No Web Worker. No @Import. WASM is a pure computation engine.
 * JS owns networking (fetch), canvas rendering, and the animation loop.
 * Communication is JS -> WASM via @Export methods only.
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
    // WasmEngine: loads TeaVM runtime + WASM, provides memory helpers
    // ========================================================================

    function WasmEngine(basePath) {
        this._basePath = basePath;
        this.teavm = null;
        this.exports = null;
        this.playing = false;
        this.stageWidth = 640;
        this.stageHeight = 480;
        this.bitmapCache = new Map();
        this.pendingBitmaps = new Set();
        this._lastFrame = 0;
        this._lastFrameCount = 0;
        this._lastTempo = 15;
    }

    WasmEngine.prototype.init = function() {
        var self = this;
        return new Promise(function(resolve, reject) {
            var script = document.createElement('script');
            script.src = self._basePath + 'player-wasm.wasm-runtime.js';
            script.onload = function() {
                TeaVM.wasm.load(self._basePath + 'player-wasm.wasm')
                    .then(function(instance) {
                        self.teavm = instance;
                        self.exports = instance.instance.exports;
                        return instance.main([]);
                    })
                    .then(function() { resolve(); })
                    .catch(function(e) { reject(e); });
            };
            script.onerror = function() { reject(new Error('Failed to load WASM runtime')); };
            document.head.appendChild(script);
        });
    };

    WasmEngine.prototype._mem = function() {
        return this.teavm.memory.buffer;
    };

    WasmEngine.prototype._readString = function(addr, len) {
        return new TextDecoder().decode(new Uint8Array(this._mem(), addr, len));
    };

    WasmEngine.prototype._writeString = function(str) {
        var bytes = new TextEncoder().encode(str);
        var addr = this.exports.getStringBufferAddress();
        var buf = new Uint8Array(this._mem(), addr, 4096);
        buf.set(bytes.subarray(0, Math.min(bytes.length, 4096)));
        return bytes.length;
    };

    WasmEngine.prototype._readJson = function(len) {
        if (len <= 0) return null;
        var addr = this.exports.getLargeBufferAddress();
        var str = new TextDecoder().decode(new Uint8Array(this._mem(), addr, len));
        try { return JSON.parse(str); }
        catch (e) { console.error('[LS] JSON parse error:', e); return null; }
    };

    WasmEngine.prototype._clearException = function() {
        if (this.teavm && this.teavm.instance && this.teavm.instance.exports.teavm_catchException) {
            this.teavm.instance.exports.teavm_catchException();
        }
    };

    WasmEngine.prototype.loadMovie = function(bytes, basePath) {
        var bp = new TextEncoder().encode(basePath || '');
        var sbAddr = this.exports.getStringBufferAddress();
        new Uint8Array(this._mem(), sbAddr, 4096).set(bp);

        var bufAddr = this.exports.allocateBuffer(bytes.length);
        new Uint8Array(this._mem(), bufAddr, bytes.length).set(bytes);

        var result = this.exports.loadMovie(bytes.length, bp.length);
        this._clearException();

        if (result === 0) return null;

        this.stageWidth = (result >> 16) & 0xFFFF;
        this.stageHeight = result & 0xFFFF;
        this._lastFrameCount = this.exports.getFrameCount();
        this._lastTempo = this.exports.getTempo();

        return {
            width: this.stageWidth,
            height: this.stageHeight,
            frameCount: this._lastFrameCount,
            tempo: this._lastTempo
        };
    };

    WasmEngine.prototype.setExternalParam = function(key, value) {
        var keyBytes = new TextEncoder().encode(key);
        var valueBytes = new TextEncoder().encode(value);
        var sbAddr = this.exports.getStringBufferAddress();
        var sbuf = new Uint8Array(this._mem(), sbAddr, 4096);
        sbuf.set(keyBytes);
        sbuf.set(valueBytes, keyBytes.length);
        this.exports.setExternalParam(keyBytes.length, valueBytes.length);
        this._clearException();
    };

    WasmEngine.prototype.clearExternalParams = function() {
        this.exports.clearExternalParams();
        this._clearException();
    };

    WasmEngine.prototype.tick = function() {
        var result = this.exports.tick();
        this._clearException();
        return result !== 0;
    };

    WasmEngine.prototype.getFrameData = function() {
        var len = this.exports.getFrameDataJson();
        this._clearException();
        var fd = this._readJson(len);
        if (fd) {
            this._lastFrame = fd.frame;
            this._lastFrameCount = fd.frameCount;
        }
        return fd;
    };

    WasmEngine.prototype.getBitmapData = function(memberId) {
        var ptr = this.exports.getBitmapData(memberId);
        if (ptr === 0) return null;
        var w = this.exports.getBitmapWidth(memberId);
        var h = this.exports.getBitmapHeight(memberId);
        if (w <= 0 || h <= 0) return null;
        var rgba = new Uint8ClampedArray(w * h * 4);
        rgba.set(new Uint8ClampedArray(this._mem(), ptr, w * h * 4));
        return { width: w, height: h, rgba: rgba };
    };

    /**
     * Poll WASM for pending network requests, fire fetch(), deliver results back.
     */
    WasmEngine.prototype.pumpNetwork = function() {
        var count = this.exports.getPendingFetchCount();
        this._clearException();
        if (count === 0) return;

        var len = this.exports.getPendingFetchJson();
        this._clearException();
        var requests = this._readJson(len);
        this.exports.drainPendingFetches();
        this._clearException();
        if (!requests) return;

        var self = this;
        for (var i = 0; i < requests.length; i++) {
            (function(req) {
                var fb = req.fallbacks || [];
                self._doFetch(req.taskId, req.url, req.method, req.postData, fb);
            })(requests[i]);
        }
    };

    WasmEngine.prototype._doFetch = function(taskId, url, method, postData, fallbacks) {
        var self = this;
        var opts = {};
        if (method === 'POST') {
            opts.method = 'POST';
            opts.body = postData;
            opts.headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
        }

        fetch(url, opts)
            .then(function(r) {
                if (!r.ok) throw { status: r.status };
                return r.arrayBuffer();
            })
            .then(function(buf) {
                self._deliverResult(taskId, buf);
            })
            .catch(function(e) {
                // Try fallback URLs on any failure (HTTP error or network error)
                if (fallbacks.length > 0) {
                    var next = fallbacks[0];
                    var rest = fallbacks.slice(1);
                    self._doFetch(taskId, next, method, postData, rest);
                } else {
                    self._deliverError(taskId, (e && e.status) || 0);
                }
            });
    };

    WasmEngine.prototype._deliverResult = function(taskId, arrayBuffer) {
        var bytes = new Uint8Array(arrayBuffer);
        var addr = this.exports.allocateNetBuffer(bytes.length);
        new Uint8Array(this._mem(), addr, bytes.length).set(bytes);
        this.exports.deliverFetchResult(taskId, bytes.length);
        this._clearException();
        // Clear bitmap cache (new cast data may have arrived)
        this.bitmapCache.clear();
        this.pendingBitmaps.clear();
    };

    WasmEngine.prototype._deliverError = function(taskId, status) {
        this.exports.deliverFetchError(taskId, status || 0);
        this._clearException();
    };

    WasmEngine.prototype.getLastError = function() {
        var len = this.exports.getLastError();
        if (len <= 0) return null;
        var addr = this.exports.getStringBufferAddress();
        return this._readString(addr, len);
    };

    // ========================================================================
    // Canvas rendering
    // ========================================================================

    WasmEngine.prototype.renderToCanvas = function(ctx, fd) {
        if (!fd) return;

        // Background
        var bg = (typeof fd.bg === 'number') ? fd.bg : 0xFFFFFF;
        ctx.fillStyle = '#' + (bg & 0xFFFFFF).toString(16).padStart(6, '0');
        ctx.fillRect(0, 0, this.stageWidth, this.stageHeight);

        // Stage image (script-drawn content like loading bars)
        if (fd.stageImageId) {
            this._ensureBitmap(fd.stageImageId);
            var stageImg = this.bitmapCache.get(fd.stageImageId);
            if (stageImg) ctx.drawImage(stageImg, 0, 0, this.stageWidth, this.stageHeight);
        }

        var sprites = fd.sprites;
        if (!sprites) return;

        // Pre-request bitmaps
        for (var i = 0; i < sprites.length; i++) {
            var s = sprites[i];
            if (s.memberId > 0 && s.visible && s.hasBaked) this._ensureBitmap(s.memberId);
        }

        // Draw sprites
        for (var i = 0; i < sprites.length; i++) {
            var sp = sprites[i];
            if (!sp.visible) continue;
            this._drawSprite(ctx, sp);
        }
    };

    WasmEngine.prototype._ensureBitmap = function(memberId) {
        if (this.bitmapCache.has(memberId) || this.pendingBitmaps.has(memberId)) return;
        this.pendingBitmaps.add(memberId);

        var bmpData = this.getBitmapData(memberId);
        if (!bmpData) { this.pendingBitmaps.delete(memberId); return; }

        var self = this;
        var imgData = new ImageData(bmpData.rgba, bmpData.width, bmpData.height);
        createImageBitmap(imgData).then(function(bmp) {
            self.bitmapCache.set(memberId, bmp);
            self.pendingBitmaps.delete(memberId);
        });
    };

    WasmEngine.prototype._drawSprite = function(ctx, sp) {
        var prevAlpha = ctx.globalAlpha;
        if (sp.blend !== undefined && sp.blend < 100) ctx.globalAlpha = sp.blend / 100;

        // Baked bitmap (preferred — matches Swing exactly)
        if (sp.memberId > 0 && sp.hasBaked) {
            var bmp = this.bitmapCache.get(sp.memberId);
            if (bmp) {
                ctx.drawImage(bmp, sp.x, sp.y, sp.w > 0 ? sp.w : bmp.width, sp.h > 0 ? sp.h : bmp.height);
                ctx.globalAlpha = prevAlpha;
                return;
            }
        }

        // Fallback rendering while bitmap loads
        if (sp.type === 'SHAPE') {
            ctx.fillStyle = '#' + ((sp.foreColor || 0) & 0xFFFFFF).toString(16).padStart(6, '0');
            ctx.fillRect(sp.x, sp.y, sp.w > 0 ? sp.w : 50, sp.h > 0 ? sp.h : 50);
        } else if ((sp.type === 'TEXT' || sp.type === 'BUTTON') && sp.textContent) {
            var fs = sp.fontSize || 12;
            ctx.font = fs + 'px serif';
            ctx.fillStyle = '#' + ((sp.foreColor || 0) & 0xFFFFFF).toString(16).padStart(6, '0');
            var lines = sp.textContent.split(/\r\n|\r|\n/);
            for (var j = 0; j < lines.length; j++) ctx.fillText(lines[j], sp.x, sp.y + fs + j * (fs + 2));
        }

        ctx.globalAlpha = prevAlpha;
    };

    // ========================================================================
    // Public API: ShockwavePlayer
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
        this._ctx = el.getContext('2d');
        this._animFrameId = null;
        this._lastFrameTime = 0;

        // Restore remembered params
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
        var engine = new WasmEngine(this._basePath);
        this._engine = engine;

        engine.init().then(function() {
            self._ready = true;
            if (self._pendingUrl) { self.load(self._pendingUrl); self._pendingUrl = null; }
            if (self._pendingFile) { self.loadFile(self._pendingFile); self._pendingFile = null; }
        }).catch(function(e) {
            if (self._opts.onError) self._opts.onError(e.message);
        });
    };

    ShockwavePlayer.prototype._onMovieLoaded = function(info) {
        this._canvas.width = info.width;
        this._canvas.height = info.height;

        // Set external params
        this._engine.clearExternalParams();
        for (var k in this._params) {
            this._engine.setExternalParam(k, this._params[k]);
        }

        if (this._opts.onLoad) this._opts.onLoad(info);

        // Pump initial network requests (external casts queued during load)
        this._engine.pumpNetwork();

        if (this._autoplay) this.play();
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
            .then(function(buf) {
                var info = self._engine.loadMovie(new Uint8Array(buf), url);
                if (!info) {
                    if (self._opts.onError) self._opts.onError('Failed to load movie');
                    return;
                }
                self._onMovieLoaded(info);
            })
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
            var info = self._engine.loadMovie(new Uint8Array(reader.result), file.name);
            if (!info) {
                if (self._opts.onError) self._opts.onError('Failed to load movie');
                return;
            }
            self._onMovieLoaded(info);
        };
        reader.readAsArrayBuffer(file);
    };

    ShockwavePlayer.prototype.play = function() {
        if (!this._engine) return;
        this._engine.exports.play();
        this._engine._clearException();
        this._engine.playing = true;
        this._lastFrameTime = 0;
        this._startLoop();
    };

    ShockwavePlayer.prototype.pause = function() {
        if (!this._engine) return;
        this._engine.exports.pause();
        this._engine.playing = false;
        this._stopLoop();
    };

    ShockwavePlayer.prototype.stop = function() {
        if (!this._engine) return;
        this._engine.exports.stop();
        this._engine.playing = false;
        this._stopLoop();
    };

    ShockwavePlayer.prototype.goToFrame = function(f) {
        if (!this._engine) return;
        this._engine.exports.goToFrame(f);
        this._doRender();
    };

    ShockwavePlayer.prototype.stepForward = function() {
        if (!this._engine) return;
        this._engine.exports.stepForward();
        this._doRender();
    };

    ShockwavePlayer.prototype.stepBackward = function() {
        if (!this._engine) return;
        this._engine.exports.stepBackward();
        this._doRender();
    };

    ShockwavePlayer.prototype.getCurrentFrame = function() {
        return this._engine ? this._engine._lastFrame : 0;
    };

    ShockwavePlayer.prototype.getFrameCount = function() {
        return this._engine ? this._engine._lastFrameCount : 0;
    };

    ShockwavePlayer.prototype.setParam = function(key, value) {
        this._params[key] = value;
        if (this._engine && this._ready) this._engine.setExternalParam(key, value);
        if (this._remember) {
            try { localStorage.setItem('ls_extParams', JSON.stringify(this._params)); } catch(e) {}
        }
    };

    ShockwavePlayer.prototype.setParams = function(obj) {
        for (var k in obj) this.setParam(k, obj[k]);
    };

    ShockwavePlayer.prototype.destroy = function() {
        this._stopLoop();
        this._engine = null;
    };

    // --- Animation loop ---

    ShockwavePlayer.prototype._startLoop = function() {
        var self = this;
        function loop(ts) {
            if (!self._engine || !self._engine.playing) return;
            var tempo = self._engine._lastTempo || 15;
            var ms = 1000.0 / (tempo > 0 ? tempo : 15);
            if (self._lastFrameTime === 0) self._lastFrameTime = ts;
            if (ts - self._lastFrameTime >= ms) {
                self._lastFrameTime = ts - ((ts - self._lastFrameTime) % ms);
                self._doTick();
            }
            self._animFrameId = requestAnimationFrame(loop);
        }
        this._animFrameId = requestAnimationFrame(loop);
    };

    ShockwavePlayer.prototype._stopLoop = function() {
        if (this._animFrameId) { cancelAnimationFrame(this._animFrameId); this._animFrameId = null; }
    };

    ShockwavePlayer.prototype._doTick = function() {
        var engine = this._engine;
        if (!engine) return;

        var stillPlaying = engine.tick();
        engine.pumpNetwork();

        var fd = engine.getFrameData();
        engine.renderToCanvas(this._ctx, fd);

        if (fd && this._opts.onFrame) {
            this._opts.onFrame(fd.frame, fd.frameCount);
        }

        if (!stillPlaying && engine.playing) {
            engine.playing = false;
            this._stopLoop();
        }
    };

    ShockwavePlayer.prototype._doRender = function() {
        var engine = this._engine;
        if (!engine) return;
        engine.pumpNetwork();
        var fd = engine.getFrameData();
        engine.renderToCanvas(this._ctx, fd);
        if (fd && this._opts.onFrame) {
            this._opts.onFrame(fd.frame, fd.frameCount);
        }
    };

    function _clone(obj) {
        var r = {};
        for (var k in obj) r[k] = obj[k];
        return r;
    }

    return { create: create };
})();
