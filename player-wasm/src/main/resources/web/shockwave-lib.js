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
 * The main thread owns only the canvas and the animation loop. All rendering,
 * WASM calls, and network I/O happen in the worker. Each tick, the worker
 * sends a pre-composited RGBA frame buffer which the main thread blits via
 * putImageData — no per-sprite drawing on the main thread.
 */
var LibreShockwave = (function() {

    // Fetch with timeout (prevents hanging requests on mobile)
    function _fetchWithTimeout(url, opts, timeoutMs) {
        timeoutMs = timeoutMs || 30000;
        var controller = new AbortController();
        var timer = setTimeout(function() { controller.abort(); }, timeoutMs);
        opts = opts || {};
        opts.signal = controller.signal;
        return fetch(url, opts).finally(function() { clearTimeout(timer); });
    }

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

        // Load deduplication: each load() increments this; stale loads bail out
        this._loadSeq     = 0;

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

        this._initAbout(el);
        this._initWorker();
    }

    // --- About dialog (built into lib so embedders cannot remove it) ---

    ShockwavePlayer.prototype._initAbout = function(canvas) {
        // --- Context menu ---
        var menu = document.createElement('div');
        menu.style.cssText = 'position:fixed;display:none;background:#fff;border:1px solid #999;padding:2px 0;z-index:10000;font-family:Verdana,Arial,Helvetica,sans-serif;font-size:11px;color:#333;min-width:160px;box-shadow:2px 2px 6px rgba(0,0,0,0.2);';

        function addMenuItem(label) {
            var item = document.createElement('div');
            item.textContent = label;
            item.style.cssText = 'padding:4px 12px;cursor:pointer;';
            item.addEventListener('mouseenter', function() { item.style.background = '#336699'; item.style.color = '#fff'; });
            item.addEventListener('mouseleave', function() { item.style.background = ''; item.style.color = '#333'; });
            menu.appendChild(item);
            return item;
        }

        var saveItem = addMenuItem('Save image as...');
        var sep = document.createElement('div');
        sep.style.cssText = 'border-top:1px solid #ccc;margin:2px 0;';
        menu.appendChild(sep);
        var aboutItem = addMenuItem('About LibreShockwave');
        document.body.appendChild(menu);

        function hideMenu() { menu.style.display = 'none'; }

        document.addEventListener('click', hideMenu);
        document.addEventListener('contextmenu', function(e) {
            if (e.target !== canvas) hideMenu();
        });

        canvas.addEventListener('contextmenu', function(e) {
            e.preventDefault();
            menu.style.left = e.clientX + 'px';
            menu.style.top = e.clientY + 'px';
            menu.style.display = 'block';
        });

        // --- About dialog ---
        var overlay = document.createElement('div');
        overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.4);display:none;align-items:center;justify-content:center;z-index:9999;';

        var win = document.createElement('div');
        win.style.cssText = 'background:#fff;border:1px solid #999;width:360px;max-width:90%;font-family:Verdana,Arial,Helvetica,sans-serif;font-size:11px;color:#333;';

        var titlebar = document.createElement('div');
        titlebar.style.cssText = 'display:flex;justify-content:space-between;align-items:center;padding:4px 8px;background:#336699;color:#fff;font-weight:bold;font-size:11px;';
        titlebar.innerHTML = '<span>About LibreShockwave</span>';

        var closeBtn = document.createElement('button');
        closeBtn.textContent = 'X';
        closeBtn.style.cssText = 'background:#eee;border:1px solid #999;color:#333;font-size:10px;font-weight:bold;padding:0 5px;cursor:pointer;font-family:Verdana,Arial,Helvetica,sans-serif;line-height:16px;';
        closeBtn.addEventListener('click', function() { overlay.style.display = 'none'; });
        titlebar.appendChild(closeBtn);

        var body = document.createElement('div');
        body.style.cssText = 'text-align:center;padding:16px 20px;line-height:1.6;';
        body.innerHTML =
            '<div style="font-size:16px;font-weight:bold;color:#336699;margin-bottom:8px;">LibreShockwave</div>' +
            '<p style="margin-bottom:6px;">An open-source Macromedia Shockwave player,<br>bringing Director movies back to life in the browser.</p>' +
            '<p style="margin-bottom:10px;color:#666;">Made by <strong>Alexandra Miller-Blake</strong></p>' +
            '<p><a href="https://github.com/Quackster/LibreShockwave" target="_blank" rel="noopener" style="color:#003399;">View on GitHub</a></p>';

        win.appendChild(titlebar);
        win.appendChild(body);
        overlay.appendChild(win);
        document.body.appendChild(overlay);

        overlay.addEventListener('click', function(e) {
            if (e.target === overlay) overlay.style.display = 'none';
        });

        saveItem.addEventListener('click', function() {
            hideMenu();
            try {
                var link = document.createElement('a');
                link.download = 'screenshot.png';
                link.href = canvas.toDataURL('image/png');
                link.click();
            } catch(e) {
                console.error('[LS] Save image failed:', e);
            }
        });

        aboutItem.addEventListener('click', function() {
            hideMenu();
            overlay.style.display = 'flex';
        });
    };

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
                if (this._resetResolve) {
                    var resolve = this._resetResolve;
                    this._resetResolve = null;
                    resolve();
                }
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

            case 'fetchRelay': {
                // Worker needs a cross-origin fetch; do it from main thread and relay back
                var worker = this._worker;
                var relayId = msg.relayId;
                var relayUrl = msg.url;

                // Check relay cache first (with and without query string for cache-busted URLs)
                var baseUrl = relayUrl.indexOf('?') >= 0 ? relayUrl.substring(0, relayUrl.indexOf('?')) : relayUrl;
                var relayBuf = this._relayCache && (this._relayCache[relayUrl] || this._relayCache[baseUrl]);
                if (relayBuf) {
                    var copy = relayBuf.slice(0); // copy so original stays reusable
                    worker.postMessage({ type: 'fetchRelayResult', relayId: relayId, data: copy }, [copy]);
                    break;
                }

                var opts = {};
                if (msg.method === 'POST') {
                    opts.method  = 'POST';
                    opts.body    = msg.postData;
                    opts.headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
                }
                _fetchWithTimeout(relayUrl, opts)
                    .then(function(r) { if (!r.ok) throw { status: r.status }; return r.arrayBuffer(); })
                    .then(function(buf) {
                        worker.postMessage({ type: 'fetchRelayResult', relayId: relayId, data: buf }, [buf]);
                    })
                    .catch(function(e) {
                        worker.postMessage({ type: 'fetchRelayResult', relayId: relayId,
                                             error: true, status: (e && e.status) || 0 });
                    });
                break;
            }

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

    ShockwavePlayer.prototype._waitFor = function(type, timeoutMs) {
        var self = this;
        timeoutMs = timeoutMs || 60000; // 60s safety default (mobile networks need more time)
        return new Promise(function(resolve) {
            var resolved = false;
            var pendingId = {}; // unique reference for this pending
            var timerId = setTimeout(function() {
                if (!resolved && self._pending && self._pending._id === pendingId) {
                    console.warn('[LS] _waitFor timeout for: ' + type);
                    self._pending = null;
                    resolved = true;
                    resolve(null);
                }
            }, timeoutMs);
            self._pending = {
                type: type,
                _id: pendingId,
                resolve: function(val) {
                    if (resolved) return;
                    resolved = true;
                    clearTimeout(timerId);
                    resolve(val);
                }
            };
        });
    };

    // --- Movie loading ---

    ShockwavePlayer.prototype.load = function(url) {
        console.log('[LS] load(' + url + '), ready=' + this._workerReady);
        if (!this._workerReady) { this._pendingUrl = url; return; }
        var self = this;
        var seq = ++this._loadSeq; // Deduplicate: only the latest load proceeds
        if (this._remember) {
            try { localStorage.setItem('ls_urlInput', url); } catch(e) {}
        }
        _fetchWithTimeout(url)
            .then(function(r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.arrayBuffer(); })
            .then(function(buf) {
                if (self._loadSeq !== seq) {
                    console.log('[LS] Ignoring superseded load #' + seq + ' (current=' + self._loadSeq + ')');
                    return;
                }
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
        var seq = ++this._loadSeq;
        var reader = new FileReader();
        reader.onload = function() {
            if (self._loadSeq !== seq) return;
            self._loadMovieBuffer(reader.result, file.name);
        };
        reader.readAsArrayBuffer(file);
    };

    ShockwavePlayer.prototype._loadMovieBuffer = async function(buf, basePath) {
        // Cancel any active loops and pending resolvers from a previous/concurrent load
        this._stopLoop();
        this._playing = false;
        this._pending = null;
        this._lastSpriteCount = 0; // Reset so fast loop doesn't skip immediately
        this._loadStartTime = performance.now();
        var mySeq = this._loadSeq;
        // Send movie bytes to worker (transfer ownership — zero copy)
        this._worker.postMessage({ type: 'loadMovie', data: buf, basePath: basePath },
                                 [buf]);
        var info = await this._waitFor('movieLoaded');
        if (this._loadSeq !== mySeq) return; // Superseded by newer load
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
        await this._onMovieLoaded(info);
    };

    ShockwavePlayer.prototype._onMovieLoaded = async function(info) {
        var mySeq = this._loadSeq;
        // Push external params into the worker
        this._worker.postMessage({ type: 'clearParams' });
        for (var k in this._params) {
            this._worker.postMessage({ type: 'setParam', key: k, value: this._params[k] });
        }

        // Send debug playback toggle to worker
        var dbg = this._opts.debugPlayback !== undefined ? this._opts.debugPlayback : true;
        this._worker.postMessage({ type: 'setDebugPlayback', enabled: dbg });

        if (this._opts.onLoad) this._opts.onLoad(info);

        // Preload external casts before starting; worker handles the network pump
        this._worker.postMessage({ type: 'preloadCasts' });
        await this._waitFor('castsDone');
        if (this._loadSeq !== mySeq) return; // Superseded by newer load

        // Pre-fetch sw1 URLs from main thread into relay cache.
        // The worker cannot reliably fetch cross-origin URLs (Chrome hang bug),
        // so we do it here and serve from cache when the relay arrives.
        this._relayCache = {};
        await this._prefetchRelayCache();
        if (this._loadSeq !== mySeq) return; // Superseded by newer load

        console.log('[LS] Ready to play after ' +
                    Math.round(performance.now() - this._loadStartTime) + 'ms');
        if (this._autoplay) this.play();
    };

    // Parse all sw1-sw9 params for URLs (key=value;key=value format)
    function _parseSwUrls(params) {
        var urls = [];
        for (var i = 1; i <= 9; i++) {
            var sw = params['sw' + i] || params['SW' + i] || '';
            if (!sw) continue;
            sw.split(';').forEach(function(pair) {
                pair = pair.trim();
                var eq = pair.indexOf('=');
                if (eq < 0) return;
                var val = pair.substring(eq + 1).trim();
                if (val.indexOf('://') !== -1) urls.push(val);
            });
        }
        return urls;
    }

    ShockwavePlayer.prototype._prefetchRelayCache = async function() {
        var urls = _parseSwUrls(this._params);
        if (urls.length === 0) return;
        console.log('[LS] Pre-fetching ' + urls.length + ' sw URLs for relay cache');
        var self = this;
        await Promise.all(urls.map(function(url) {
            return _fetchWithTimeout(url)
                .then(function(r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.arrayBuffer(); })
                .then(function(buf) {
                    self._relayCache[url] = buf;
                    console.log('[LS] Pre-fetched: ' + url + ' (' + buf.byteLength + ' bytes)');
                })
                .catch(function(e) {
                    console.warn('[LS] Pre-fetch failed: ' + url + ' — ' + e.message);
                });
        }));
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

    ShockwavePlayer.prototype.setDebugPlayback = function(enabled) {
        if (this._worker && this._workerReady) {
            this._worker.postMessage({ type: 'setDebugPlayback', enabled: enabled });
        }
    };

    /**
     * Fully reset the player: terminate the worker, clear all state,
     * and spin up a fresh WASM instance. Returns a Promise that resolves
     * when the new worker is ready to accept loadMovie.
     */
    ShockwavePlayer.prototype.reset = function() {
        var self = this;
        this._stopLoop();
        this._playing = false;
        this._pending = null;
        this._pendingUrl = null;
        this._pendingFile = null;
        this._relayCache = {};
        this._lastSpriteCount = 0;
        this._lastFrame = 0;
        this._lastFrameCount = 0;
        ++this._loadSeq; // Invalidate any in-flight loads
        if (this._worker) { this._worker.terminate(); this._worker = null; }
        this._workerReady = false;

        // Clear the canvas
        this._ctx.clearRect(0, 0, this._canvas.width, this._canvas.height);

        return new Promise(function(resolve) {
            self._resetResolve = resolve;
            self._initWorker();
        });
    };

    ShockwavePlayer.prototype.destroy = function() {
        this._stopLoop();
        if (this._worker) { this._worker.terminate(); this._worker = null; }
    };

    // --- Animation loop ---

    /**
     * Fast-loading mode: during the Director loading screen (frames 1-10),
     * the Lingo state machine needs hundreds of ticks to parse external data.
     * At the normal 15fps tempo that takes 30+ seconds. Instead, we send ticks
     * as fast as the WASM round-trip allows (~4-10ms each), reaching the hotel
     * view in a few seconds. Once loading completes, we switch to the normal
     * tempo-gated loop for smooth animation playback.
     */
    ShockwavePlayer.prototype._startLoop = function() {
        this._stopLoop(); // cancel any previous fast/normal loop before starting a new one
        var self = this;
        this._loadingPhase = true;
        this._tickCount = 0;
        this._lastRenderTime = 0;
        this._loopSeq = this._loadSeq; // tie this loop to the current load
        console.log('[LS] Starting fast-loading loop (seq=' + this._loopSeq + ')');
        this._runFastLoop();
    };

    ShockwavePlayer.prototype._runFastLoop = function() {
        var self = this;
        if (!this._playing) return;
        if (this._loadSeq !== this._loopSeq) return; // stale loop from old load

        this._doTick().then(function() {
            if (!self._playing) {
                console.log('[LS] fast loop: _playing is false after tick ' + self._tickCount + ', stopping');
                return;
            }
            if (self._loadSeq !== self._loopSeq) return; // stale

            self._tickCount++;

            // Detect loading complete: sprite count >= 10 means hotel view is
            // rendering (has 25+ sprites), OR after 5000 fast ticks bail out
            if (self._lastSpriteCount >= 10 || self._tickCount > 5000) {
                if (self._loadingPhase) {
                    console.log('[LS] Loading complete after ' + self._tickCount +
                                ' ticks (' + Math.round(performance.now() - self._loadStartTime) +
                                'ms), switching to normal loop');
                    self._loadingPhase = false;
                }
                self._startNormalLoop();
                return;
            }

            // Still loading — schedule next tick immediately (setTimeout(0) = ~1-4ms)
            self._fastTimerId = setTimeout(function() { self._runFastLoop(); }, 0);

        }).catch(function(err) {
            console.error('[LS] fast tick error:', err);
            // Fallback to normal loop on error
            self._loadingPhase = false;
            self._startNormalLoop();
        });
    };

    ShockwavePlayer.prototype._startNormalLoop = function() {
        var self = this;
        var ticking = false;
        this._lastFrameTime = 0;
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
        if (this._fastTimerId) {
            clearTimeout(this._fastTimerId);
            this._fastTimerId = null;
        }
    };

    // --- Tick: send work to the worker, await the frame response, then render ---

    ShockwavePlayer.prototype._doTick = async function() {
        // Always render — the fast-loading loop completes in few ticks so
        // skipping frames would hide the loading screen (Sulake logo).
        var skipRender = false;
        this._worker.postMessage({ type: 'tick', skipRender: skipRender });
        var result = await this._waitFor('frame');
        if (!result) return;

        // Update local state from worker response
        this._lastTempo       = result.tempo       || this._lastTempo;
        this._lastFrame       = result.lastFrame   || this._lastFrame;
        this._lastFrameCount  = result.frameCount  || this._lastFrameCount;
        this._lastSpriteCount = result.spriteCount || 0;

        // Blit the pre-composited RGBA frame to the canvas
        if (result.rgba && result.width > 0 && result.height > 0) {
            var imgData = new ImageData(result.rgba, result.width, result.height);
            this._ctx.putImageData(imgData, 0, 0);
        }

        // Debug overlay during loading phase (helps diagnose mobile issues without DevTools)
        if (this._loadingPhase && this._opts.debugOverlay) {
            var ctx = this._ctx;
            var text = 'tick:' + (this._tickCount || 0) +
                       ' sprites:' + this._lastSpriteCount +
                       ' frame:' + this._lastFrame;
            ctx.save();
            ctx.font = '11px monospace';
            ctx.fillStyle = 'rgba(0,0,0,0.5)';
            ctx.fillRect(0, 0, ctx.measureText(text).width + 8, 16);
            ctx.fillStyle = '#0f0';
            ctx.fillText(text, 4, 12);
            ctx.restore();
        }

        if (result.debugLog && this._opts.onDebugLog) {
            this._opts.onDebugLog(result.debugLog);
        }

        if (this._opts.onFrame) {
            this._opts.onFrame(this._lastFrame, this._lastFrameCount);
        }

        // Mirror worker's playing flag to drive our loop
        if (!result.playing && !result.enginePlaying && this._playing) {
            console.log('[LS] _doTick: stopping — playing=' + result.playing + ' enginePlaying=' + result.enginePlaying);
            this._playing = false;
            this._stopLoop();
        }
    };

    // --- Utilities ---

    function _clone(obj) {
        var r = {};
        for (var k in obj) r[k] = obj[k];
        return r;
    }

    return { create: create };
})();
