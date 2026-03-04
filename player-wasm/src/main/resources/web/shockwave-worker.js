'use strict';

// Forward Worker console messages to main thread for debugging
var _origLog = console.log;
var _origErr = console.error;
console.log = function() {
    _origLog.apply(console, arguments);
    try { self.postMessage({ type: 'log', msg: Array.prototype.join.call(arguments, ' ') }); } catch(e) {}
};
console.error = function() {
    _origErr.apply(console, arguments);
    try { self.postMessage({ type: 'log', msg: '[ERR] ' + Array.prototype.join.call(arguments, ' ') }); } catch(e) {}
};

/**
 * LibreShockwave Web Worker — runs the WASM engine off the main thread.
 *
 * Message protocol (main → worker):
 *   {type:'init',    basePath}
 *   {type:'loadMovie', data:ArrayBuffer, basePath}
 *   {type:'setParam',  key, value}
 *   {type:'clearParams'}
 *   {type:'preloadCasts'}
 *   {type:'play'|'pause'|'stop'}
 *   {type:'tick'}
 *   {type:'goToFrame', frame}
 *   {type:'stepForward'}
 *   {type:'stepBackward'}
 *
 * Message protocol (worker → main):
 *   {type:'ready'}
 *   {type:'movieLoaded',  info:{width,height,frameCount,tempo} | null}
 *   {type:'castsDone'}
 *   {type:'frame', playing, enginePlaying, tempo, lastFrame, frameCount,
 *                  rgba:Uint8ClampedArray, width, height}
 *   {type:'error', msg}
 */

var _e = null;          // WasmEngine instance
var _isTicking = false; // guard against overlapping ticks

// --- Non-blocking fetch delivery queue ---
var _fetchQueue = [];   // [{taskId, data: ArrayBuffer}] or [{taskId, error: number}]
var _inFlight = 0;      // number of fetches currently in-progress
var _loadStartTime = 0; // timestamp when loading began (for perf logging)

// ============================================================
// WasmEngine — mirrors the main-thread version but without Canvas
// ============================================================

function WasmEngine() {
    this.teavm    = null;
    this.exports  = null;
    this.playing  = false;
    this._lastTempo      = 15;
    this._lastFrame      = 0;
    this._lastFrameCount = 0;
}

WasmEngine.prototype._mem = function() { return this.teavm.memory.buffer; };

WasmEngine.prototype._readString = function(addr, len) {
    return new TextDecoder().decode(new Uint8Array(this._mem(), addr, len));
};

WasmEngine.prototype._writeBytes = function(addr, bytes, maxLen) {
    new Uint8Array(this._mem(), addr, maxLen).set(bytes.subarray(0, Math.min(bytes.length, maxLen)));
};

WasmEngine.prototype._clearEx = function() {
    var ex = this.teavm.instance && this.teavm.instance.exports;
    if (ex && ex.teavm_catchException) ex.teavm_catchException();
};

WasmEngine.prototype.loadMovie = function(bytes, basePath) {
    var bp = new TextEncoder().encode(basePath || '');
    this._writeBytes(this.exports.getStringBufferAddress(), bp, 4096);
    var bufAddr = this.exports.allocateBuffer(bytes.length);
    new Uint8Array(this._mem(), bufAddr, bytes.length).set(bytes);
    var result = this.exports.loadMovie(bytes.length, bp.length);
    this._clearEx();
    if (result === 0) return null;
    var w = (result >> 16) & 0xFFFF, h = result & 0xFFFF;
    this._lastFrameCount = this.exports.getFrameCount();
    this._lastTempo      = this.exports.getTempo();
    return { width: w, height: h, frameCount: this._lastFrameCount, tempo: this._lastTempo };
};

WasmEngine.prototype.setExternalParam = function(key, value) {
    var kb = new TextEncoder().encode(key), vb = new TextEncoder().encode(value);
    var sbuf = new Uint8Array(this._mem(), this.exports.getStringBufferAddress(), 4096);
    sbuf.set(kb); sbuf.set(vb, kb.length);
    this.exports.setExternalParam(kb.length, vb.length);
    this._clearEx();
};

WasmEngine.prototype.clearExternalParams = function() {
    this.exports.clearExternalParams(); this._clearEx();
};

WasmEngine.prototype.preloadCasts = function() {
    var n = this.exports.preloadCasts(); this._clearEx(); return n;
};

WasmEngine.prototype.tick = function() {
    var r = this.exports.tick(); this._clearEx(); return r !== 0;
};

/**
 * Render the current frame into an RGBA buffer via SoftwareRenderer (WASM-side).
 * Returns { w, h, rgba: Uint8ClampedArray } or null on failure.
 */
WasmEngine.prototype.renderFrame = function() {
    var len = this.exports.render(); this._clearEx();
    if (len <= 0) return null;

    var w = this.exports.getStageWidth();
    var h = this.exports.getStageHeight();
    if (w <= 0 || h <= 0) return null;

    var ptr = this.exports.getRenderBufferAddress(); this._clearEx();
    if (ptr === 0) return null;

    // Update cached frame metadata
    this._lastFrame      = this.exports.getCurrentFrame();
    this._lastFrameCount = this.exports.getFrameCount();

    // Copy RGBA out of WASM heap (buffer may move after GC)
    var rgba = new Uint8ClampedArray(w * h * 4);
    rgba.set(new Uint8ClampedArray(this._mem(), ptr, rgba.length));
    return { w: w, h: h, rgba: rgba };
};

WasmEngine.prototype._drainRequests = function() {
    var count = this.exports.getPendingFetchCount(); this._clearEx();
    if (count === 0) return null;
    var reqs = [];
    var strAddr = this.exports.getStringBufferAddress();
    for (var i = 0; i < count; i++) {
        var taskId = this.exports.getPendingFetchTaskId(i); this._clearEx();
        var urlLen = this.exports.getPendingFetchUrl(i); this._clearEx();
        var url = this._readString(strAddr, urlLen);
        var method = this.exports.getPendingFetchMethod(i); this._clearEx();
        var postData = null;
        if (method === 1) {
            var pdLen = this.exports.getPendingFetchPostData(i); this._clearEx();
            if (pdLen > 0) postData = this._readString(strAddr, pdLen);
        }
        var fbCount = this.exports.getPendingFetchFallbackCount(i); this._clearEx();
        var fallbacks = [];
        for (var j = 0; j < fbCount; j++) {
            var fbLen = this.exports.getPendingFetchFallbackUrl(i, j); this._clearEx();
            fallbacks.push(this._readString(strAddr, fbLen));
        }
        reqs.push({taskId: taskId, url: url, method: method === 1 ? 'POST' : 'GET',
                   postData: postData, fallbacks: fallbacks});
    }
    this.exports.drainPendingFetches(); this._clearEx();
    return reqs;
};

WasmEngine.prototype._deliverResult = function(taskId, arrayBuffer) {
    var bytes = new Uint8Array(arrayBuffer);
    var addr  = this.exports.allocateNetBuffer(bytes.length);
    new Uint8Array(this._mem(), addr, bytes.length).set(bytes);
    this.exports.deliverFetchResult(taskId, bytes.length);
    this._clearEx();
};

WasmEngine.prototype._deliverError = function(taskId, status) {
    this.exports.deliverFetchError(taskId, status || 0); this._clearEx();
};

WasmEngine.prototype._doFetch = function(taskId, url, method, postData, fallbacks) {
    var self = this;
    var opts = {};
    if (method === 'POST') {
        opts.method  = 'POST';
        opts.body    = postData;
        opts.headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
    }
    return fetch(url, opts)
        .then(function(r) { if (!r.ok) throw { status: r.status }; return r.arrayBuffer(); })
        .then(function(buf) { self._deliverResult(taskId, buf); })
        .catch(function(e) {
            if (fallbacks.length > 0)
                return self._doFetch(taskId, fallbacks[0], method, postData, fallbacks.slice(1));
            else
                self._deliverError(taskId, (e && e.status) || 0);
        });
};

/** Drain pending network requests; return array of Promises (each always resolves). */
WasmEngine.prototype.pumpNetworkCollect = function() {
    var reqs = this._drainRequests();
    if (!reqs) return [];
    var self = this, promises = [];
    for (var i = 0; i < reqs.length; i++) {
        (function(req) {
            var p = self._doFetch(req.taskId, req.url, req.method, req.postData, req.fallbacks || []);
            promises.push(p.then(null, function() {}));
        })(reqs[i]);
    }
    return promises;
};

// ============================================================
// Non-blocking fetch pipeline (used during tick for async I/O)
// ============================================================

/**
 * Fire a fetch without blocking. Result is pushed to _fetchQueue
 * when the fetch completes (between event loop turns).
 */
WasmEngine.prototype._doFetchAsync = function(taskId, url, method, postData, fallbacks) {
    var self = this;
    _inFlight++;
    var opts = {};
    if (method === 'POST') {
        opts.method  = 'POST';
        opts.body    = postData;
        opts.headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
    }
    fetch(url, opts)
        .then(function(r) { if (!r.ok) throw { status: r.status }; return r.arrayBuffer(); })
        .then(function(buf) {
            _fetchQueue.push({ taskId: taskId, data: buf });
            _inFlight--;
        })
        .catch(function(e) {
            if (fallbacks.length > 0) {
                _inFlight--; // this attempt done; retry will increment again
                return self._doFetchAsync(taskId, fallbacks[0], method, postData, fallbacks.slice(1));
            } else {
                _fetchQueue.push({ taskId: taskId, error: (e && e.status) || 0 });
                _inFlight--;
            }
        });
};

/**
 * Deliver all queued fetch results into WASM.
 * Called at the start of each tick before running Lingo.
 * @return number of results delivered
 */
WasmEngine.prototype.deliverQueuedResults = function() {
    var count = 0;
    while (_fetchQueue.length > 0) {
        var item = _fetchQueue.shift();
        if (item.data !== undefined) {
            this._deliverResult(item.taskId, item.data);
        } else {
            this._deliverError(item.taskId, item.error);
        }
        count++;
    }
    return count;
};

/**
 * Drain pending requests from WASM and fire them all non-blocking.
 * @return number of requests fired
 */
WasmEngine.prototype.pumpNetworkFire = function() {
    var reqs = this._drainRequests();
    if (!reqs) return 0;
    for (var i = 0; i < reqs.length; i++) {
        var req = reqs[i];
        this._doFetchAsync(req.taskId, req.url, req.method, req.postData, req.fallbacks || []);
    }
    return reqs.length;
};

// ============================================================
// Message handler
// ============================================================

self.onmessage = async function(e) {
    var msg = e.data;
    try {
        switch (msg.type) {

            case 'init': {
                // importScripts is synchronous; TeaVM.wasm.load is async
                importScripts(msg.basePath + 'player-wasm.wasm-runtime.js');
                var instance = await TeaVM.wasm.load(msg.basePath + 'player-wasm.wasm');
                await instance.main([]);
                _e = new WasmEngine();
                _e.teavm   = instance;
                _e.exports = instance.instance.exports;
                self.postMessage({ type: 'ready' });
                break;
            }

            case 'loadMovie': {
                var info = _e.loadMovie(new Uint8Array(msg.data), msg.basePath);
                _e.playing  = false;
                self.postMessage({ type: 'movieLoaded', info: info });
                break;
            }

            case 'setParam':
                _e.setExternalParam(msg.key, msg.value);
                break;

            case 'clearParams':
                _e.clearExternalParams();
                break;

            case 'preloadCasts': {
                var castT0 = performance.now();
                _loadStartTime = castT0;
                var n = _e.preloadCasts();
                console.log('[WORKER] preloadCasts: ' + n + ' casts queued');
                if (n > 0) {
                    var p1 = _e.pumpNetworkCollect();
                    if (p1.length > 0) await Promise.all(p1);
                    // pump any secondary requests queued during prepareMovie
                    var p2 = _e.pumpNetworkCollect();
                    if (p2.length > 0) await Promise.all(p2);
                }
                // Continue pumping until no more requests
                while (true) {
                    var pn = _e.pumpNetworkCollect();
                    if (pn.length === 0) break;
                    await Promise.all(pn);
                }
                console.log('[WORKER] preloadCasts done in ' + Math.round(performance.now() - castT0) + 'ms');
                // Compact heap after heavy cast loading to reduce GC pressure during ticks
                try { _e.exports.forceGC(); _e._clearEx(); } catch(e) {}
                self.postMessage({ type: 'castsDone' });
                break;
            }

            case 'play':
                console.log('[WORKER] play() — starting animation');
                _e.exports.play(); _e._clearEx(); _e.playing = true;
                break;
            case 'pause':
                _e.exports.pause(); _e._clearEx(); _e.playing = false;
                break;
            case 'stop':
                _e.exports.stop(); _e._clearEx(); _e.playing = false;
                break;

            case 'goToFrame':
                _e.exports.goToFrame(msg.frame); _e._clearEx();
                break;
            case 'stepForward':
                _e.exports.stepForward(); _e._clearEx();
                break;
            case 'stepBackward':
                _e.exports.stepBackward(); _e._clearEx();
                break;

            case 'tick': {
                if (_isTicking) return; // drop if already busy
                _isTicking = true;
                try {
                    var stillPlaying = true;
                    var frame = null;
                    var t0 = performance.now();

                    // Phase 1: advance WASM by one Lingo frame
                    try {
                        stillPlaying = _e.tick();
                    } catch (tickErr) {
                        console.error('[WORKER] tick() error: ' + tickErr);
                        try { _e.exports.forceGC(); _e._clearEx(); } catch(e2) {}
                    }
                    var t1 = performance.now();

                    // After heavy ticks (text dump, cast load), force GC between phases.
                    // This runs OUTSIDE WASM so the heap is stable for compaction.
                    if (t1 - t0 > 100) {
                        try { _e.exports.forceGC(); _e._clearEx(); } catch(e3) {}
                    }

                    // Phase 2: fire network requests and AWAIT results (blocking)
                    var nReqs = 0;
                    try {
                        var tp = _e.pumpNetworkCollect();
                        nReqs = tp.length;
                        if (tp.length > 0) await Promise.all(tp);
                    } catch (netErr) {
                        console.error('[WORKER] pump error: ' + netErr);
                    }
                    var t2 = performance.now();

                    // Always update frame metadata from WASM (needed for fast-loop detection)
                    try {
                        _e._lastFrame      = _e.exports.getCurrentFrame();
                        _e._lastFrameCount = _e.exports.getFrameCount();
                    } catch (ignore) {}

                    // Phase 3: render (skip during fast-loading for performance)
                    if (!msg.skipRender) {
                        try {
                            frame = _e.renderFrame();
                        } catch (renderErr) {
                            console.error('[WORKER] render() error: ' + renderErr);
                        }
                    }
                    var t3 = performance.now();

                    // Log timing for slow ticks
                    var total = t3 - t0;
                    if (total > 100) {
                        console.log('[WORKER] SLOW tick: total=' + Math.round(total) +
                                    'ms tick=' + Math.round(t1-t0) +
                                    'ms net=' + Math.round(t2-t1) + 'ms(' + nReqs + ' reqs)' +
                                    ' render=' + Math.round(t3-t2) + 'ms');
                    }

                    // Always send a frame response to unblock main thread
                    self.postMessage({
                        type:          'frame',
                        playing:       stillPlaying,
                        enginePlaying: _e.playing,
                        tempo:         _e._lastTempo,
                        lastFrame:     _e._lastFrame,
                        frameCount:    _e._lastFrameCount,
                        rgba:          frame ? frame.rgba : null,
                        width:         frame ? frame.w : 0,
                        height:        frame ? frame.h : 0
                    }, frame ? [frame.rgba.buffer] : []);

                } finally {
                    _isTicking = false;
                }
                break;
            }

            default:
                break;
        }
    } catch (err) {
        self.postMessage({ type: 'error', msg: String(err) });
    }
};
