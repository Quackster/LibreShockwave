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
 * Architecture: WASM runs entirely in a Web Worker (shockwave-worker.js).
 * The main thread owns only: canvas rendering, bitmap ImageBitmap cache, and
 * the animation loop. All WASM calls — including network I/O — happen in the
 * worker, so the main thread never blocks on slow Lingo script execution.
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

        this._opts        = opts;
        this._basePath    = opts.basePath || _autoBasePath;
        this._params      = opts.params ? _clone(opts.params) : {};
        this._autoplay    = opts.autoplay !== false;
        this._remember    = !!opts.remember;
        this._canvas      = el;
        this._ctx         = el.getContext('2d');
        this._animFrameId = null;
        this._lastFrameTime = 0;

        // Worker state
        this._worker      = null;
        this._workerReady = false;
        this._pendingUrl  = null;
        this._pendingFile = null;

        // Playback state (mirrors worker)
        this._playing     = false;
        this._lastTempo   = 15;
        this._lastFrame   = 0;
        this._lastFrameCount = 0;
        this._stageWidth  = 640;
        this._stageHeight = 480;

        // Bitmap cache: memberId → ImageBitmap
        this._bitmapCache = new Map();

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

        this._initWorker();
    }

    // --- Worker lifecycle ---

    ShockwavePlayer.prototype._initWorker = function() {
        var self = this;
        // Make the base path absolute so importScripts() in the worker resolves it correctly
        var absBase = new URL(this._basePath, document.baseURI).href;

        var worker = new Worker(absBase + 'shockwave-worker.js');
        this._worker = worker;

        worker.onmessage = function(e) { self._onWorkerMessage(e.data); };
        worker.onerror   = function(e) {
            console.error('[LS] Worker error:', e.message);
            if (self._opts.onError) self._opts.onError(e.message);
        };

        // Send init with absolute base path so importScripts/fetch work from the worker
        worker.postMessage({ type: 'init', basePath: absBase });
    };

    ShockwavePlayer.prototype._onWorkerMessage = function(msg) {
        switch (msg.type) {

            case 'ready':
                console.log('[LS] Worker ready');
                this._workerReady = true;
                if (this._pendingUrl)  { this.load(this._pendingUrl);  this._pendingUrl  = null; }
                if (this._pendingFile) { this.loadFile(this._pendingFile); this._pendingFile = null; }
                break;

            case 'movieLoaded':
                this._resolveOnce('movieLoaded', msg.info);
                break;

            case 'castsDone':
                this._resolveOnce('castsDone', null);
                break;

            case 'frame':
                this._resolveOnce('frame', msg);
                break;

            case 'error':
                console.error('[LS] Worker reported:', msg.msg);
                break;

            default:
                break;
        }
    };

    // Simple one-shot resolver map: type → resolve function
    ShockwavePlayer.prototype._resolveOnce = function(type, value) {
        if (this._pending && this._pending.type === type) {
            var resolve = this._pending.resolve;
            this._pending = null;
            resolve(value);
        }
    };

    ShockwavePlayer.prototype._waitFor = function(type) {
        var self = this;
        return new Promise(function(resolve) {
            self._pending = { type: type, resolve: resolve };
        });
    };

    // --- Movie loading ---

    ShockwavePlayer.prototype.load = function(url) {
        console.log('[LS] load(' + url + '), ready=' + this._workerReady);
        if (!this._workerReady) { this._pendingUrl = url; return; }
        var self = this;
        if (this._remember) {
            try { localStorage.setItem('ls_urlInput', url); } catch(e) {}
        }
        fetch(url)
            .then(function(r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.arrayBuffer(); })
            .then(function(buf) {
                console.log('[LS] Movie fetched, ' + buf.byteLength + ' bytes');
                self._loadMovieBuffer(buf, url);
            })
            .catch(function(e) {
                console.error('[LS] load error:', e);
                if (self._opts.onError) self._opts.onError(e.message);
            });
    };

    ShockwavePlayer.prototype.loadFile = function(file) {
        if (!this._workerReady) { this._pendingFile = file; return; }
        var self = this;
        var reader = new FileReader();
        reader.onload = function() { self._loadMovieBuffer(reader.result, file.name); };
        reader.readAsArrayBuffer(file);
    };

    ShockwavePlayer.prototype._loadMovieBuffer = async function(buf, basePath) {
        // Send movie bytes to worker (transfer ownership — zero copy)
        this._worker.postMessage({ type: 'loadMovie', data: buf, basePath: basePath },
                                 [buf]);
        var info = await this._waitFor('movieLoaded');
        if (!info) {
            console.error('[LS] loadMovie returned null');
            if (this._opts.onError) this._opts.onError('Failed to load movie');
            return;
        }
        console.log('[LS] Movie loaded:', info.width + 'x' + info.height +
                    ', ' + info.frameCount + ' frames');
        this._stageWidth  = info.width;
        this._stageHeight = info.height;
        this._lastTempo   = info.tempo;
        this._canvas.width  = info.width;
        this._canvas.height = info.height;
        this._bitmapCache.clear();
        await this._onMovieLoaded(info);
    };

    ShockwavePlayer.prototype._onMovieLoaded = async function(info) {
        // Push external params into the worker
        this._worker.postMessage({ type: 'clearParams' });
        for (var k in this._params) {
            this._worker.postMessage({ type: 'setParam', key: k, value: this._params[k] });
        }

        if (this._opts.onLoad) this._opts.onLoad(info);

        // Preload external casts before starting; worker handles the network pump
        this._worker.postMessage({ type: 'preloadCasts' });
        await this._waitFor('castsDone');

        if (this._autoplay) this.play();
    };

    // --- Playback control ---

    ShockwavePlayer.prototype.play = function() {
        this._worker.postMessage({ type: 'play' });
        this._playing = true;
        this._lastFrameTime = 0;
        this._startLoop();
    };

    ShockwavePlayer.prototype.pause = function() {
        this._worker.postMessage({ type: 'pause' });
        this._playing = false;
        this._stopLoop();
    };

    ShockwavePlayer.prototype.stop = function() {
        this._worker.postMessage({ type: 'stop' });
        this._playing = false;
        this._stopLoop();
    };

    ShockwavePlayer.prototype.goToFrame = function(f) {
        this._worker.postMessage({ type: 'goToFrame', frame: f });
    };

    ShockwavePlayer.prototype.stepForward = function() {
        this._worker.postMessage({ type: 'stepForward' });
    };

    ShockwavePlayer.prototype.stepBackward = function() {
        this._worker.postMessage({ type: 'stepBackward' });
    };

    ShockwavePlayer.prototype.getCurrentFrame = function() {
        return this._lastFrame;
    };

    ShockwavePlayer.prototype.getFrameCount = function() {
        return this._lastFrameCount;
    };

    ShockwavePlayer.prototype.setParam = function(key, value) {
        this._params[key] = value;
        if (this._worker && this._workerReady) {
            this._worker.postMessage({ type: 'setParam', key: key, value: value });
        }
        if (this._remember) {
            try { localStorage.setItem('ls_extParams', JSON.stringify(this._params)); } catch(e) {}
        }
    };

    ShockwavePlayer.prototype.setParams = function(obj) {
        for (var k in obj) this.setParam(k, obj[k]);
    };

    ShockwavePlayer.prototype.destroy = function() {
        this._stopLoop();
        if (this._worker) { this._worker.terminate(); this._worker = null; }
    };

    // --- Animation loop ---

    ShockwavePlayer.prototype._startLoop = function() {
        var self = this;
        var ticking = false;
        function loop(ts) {
            if (!self._playing) return;
            var tempo = self._lastTempo || 15;
            var ms = 1000.0 / (tempo > 0 ? tempo : 15);
            if (self._lastFrameTime === 0) self._lastFrameTime = ts;
            if (ts - self._lastFrameTime >= ms && !ticking) {
                self._lastFrameTime = ts - ((ts - self._lastFrameTime) % ms);
                ticking = true;
                self._doTick().then(function() {
                    ticking = false;
                }).catch(function(err) {
                    ticking = false;
                    console.error('[LS] tick error:', err);
                });
            }
            self._animFrameId = requestAnimationFrame(loop);
        }
        this._animFrameId = requestAnimationFrame(loop);
    };

    ShockwavePlayer.prototype._stopLoop = function() {
        if (this._animFrameId) {
            cancelAnimationFrame(this._animFrameId);
            this._animFrameId = null;
        }
    };

    // --- Tick: send work to the worker, await the frame response, then render ---

    ShockwavePlayer.prototype._doTick = async function() {
        this._worker.postMessage({ type: 'tick' });
        var result = await this._waitFor('frame');
        if (!result) return;

        // Update local state from worker response
        this._lastTempo      = result.tempo      || this._lastTempo;
        this._lastFrame      = result.lastFrame  || this._lastFrame;
        this._lastFrameCount = (result.fd && result.fd.frameCount) || this._lastFrameCount;

        // When a cast was loaded in the worker, clear our ImageBitmap cache
        if (result.castCacheCleared) this._bitmapCache.clear();

        // Materialise any new bitmaps the worker sent (transferred ArrayBuffers)
        var bitmaps = result.bitmaps || {};
        var self = this;
        var bitmapPromises = [];
        for (var mid in bitmaps) {
            (function(id, bmp) {
                var imgData = new ImageData(bmp.rgba, bmp.w, bmp.h);
                var p = createImageBitmap(imgData).then(function(ib) {
                    self._bitmapCache.set(parseInt(id, 10), ib);
                });
                bitmapPromises.push(p);
            })(mid, bitmaps[mid]);
        }
        if (bitmapPromises.length > 0) await Promise.all(bitmapPromises);

        // Render
        var fd = result.fd;
        this._renderFrame(fd);

        if (fd && this._opts.onFrame) {
            this._opts.onFrame(fd.frame, fd.frameCount);
        }

        // Mirror worker's playing flag to drive our loop
        if (!result.playing && !result.enginePlaying && this._playing) {
            this._playing = false;
            this._stopLoop();
        }
    };

    // --- Canvas rendering (main thread) ---

    ShockwavePlayer.prototype._renderFrame = function(fd) {
        if (!fd) return;
        var ctx = this._ctx;
        var sw  = this._stageWidth;
        var sh  = this._stageHeight;

        // Background
        var bg = (typeof fd.bg === 'number') ? fd.bg : 0xFFFFFF;
        ctx.fillStyle = '#' + (bg & 0xFFFFFF).toString(16).padStart(6, '0');
        ctx.fillRect(0, 0, sw, sh);

        // Stage image (script-drawn content)
        if (fd.stageImageId) {
            var stageImg = this._bitmapCache.get(fd.stageImageId);
            if (stageImg) ctx.drawImage(stageImg, 0, 0, sw, sh);
        }

        var sprites = fd.sprites;
        if (!sprites) return;

        for (var i = 0; i < sprites.length; i++) {
            var sp = sprites[i];
            if (!sp.visible) continue;
            this._drawSprite(ctx, sp);
        }
    };

    ShockwavePlayer.prototype._drawSprite = function(ctx, sp) {
        var prevAlpha = ctx.globalAlpha;
        if (sp.blend !== undefined && sp.blend < 100) ctx.globalAlpha = sp.blend / 100;

        // Baked bitmap (preferred — matches Swing exactly)
        if (sp.memberId > 0 && sp.hasBaked) {
            var bmp = this._bitmapCache.get(sp.memberId);
            if (bmp) {
                ctx.drawImage(bmp, sp.x, sp.y,
                    sp.w > 0 ? sp.w : bmp.width,
                    sp.h > 0 ? sp.h : bmp.height);
                ctx.globalAlpha = prevAlpha;
                return;
            }
        }

        // Fallback while bitmap is not yet cached
        if (sp.type === 'SHAPE') {
            ctx.fillStyle = '#' + ((sp.foreColor || 0) & 0xFFFFFF).toString(16).padStart(6, '0');
            ctx.fillRect(sp.x, sp.y, sp.w > 0 ? sp.w : 50, sp.h > 0 ? sp.h : 50);
        } else if ((sp.type === 'TEXT' || sp.type === 'BUTTON') && sp.textContent) {
            var fs = sp.fontSize || 12;
            ctx.font = fs + 'px serif';
            ctx.fillStyle = '#' + ((sp.foreColor || 0) & 0xFFFFFF).toString(16).padStart(6, '0');
            var lines = sp.textContent.split(/\r\n|\r|\n/);
            for (var j = 0; j < lines.length; j++) {
                ctx.fillText(lines[j], sp.x, sp.y + fs + j * (fs + 2));
            }
        }

        ctx.globalAlpha = prevAlpha;
    };

    // --- Utilities ---

    function _clone(obj) {
        var r = {};
        for (var k in obj) r[k] = obj[k];
        return r;
    }

    return { create: create };
})();
