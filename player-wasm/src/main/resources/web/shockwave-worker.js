'use strict';
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
 *   {type:'frame', playing, fd, tempo, lastFrame, bitmaps:{memberId:{w,h,rgba}},
 *                  castCacheCleared}
 *   {type:'error', msg}
 */

var _e = null;          // WasmEngine instance
var _isTicking = false; // guard against overlapping ticks
var _knownBitmaps = {}; // set of memberIds whose bytes were already sent to main thread

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
    this._castRevision   = 0; // incremented whenever _deliverResult loads a cast
}

WasmEngine.prototype._mem = function() { return this.teavm.memory.buffer; };

WasmEngine.prototype._readString = function(addr, len) {
    return new TextDecoder().decode(new Uint8Array(this._mem(), addr, len));
};

WasmEngine.prototype._writeBytes = function(addr, bytes, maxLen) {
    new Uint8Array(this._mem(), addr, maxLen).set(bytes.subarray(0, Math.min(bytes.length, maxLen)));
};

WasmEngine.prototype._readJson = function(len) {
    if (len <= 0) return null;
    var addr = this.exports.getLargeBufferAddress();
    var str = new TextDecoder().decode(new Uint8Array(this._mem(), addr, len));
    try { return JSON.parse(str); } catch(e) { return null; }
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

WasmEngine.prototype.getFrameData = function() {
    var len = this.exports.getFrameDataJson(); this._clearEx();
    var fd = this._readJson(len);
    if (fd) { this._lastFrame = fd.frame; this._lastFrameCount = fd.frameCount; }
    return fd;
};

WasmEngine.prototype.getBitmapData = function(memberId) {
    var ptr = this.exports.getBitmapData(memberId);
    if (ptr === 0) return null;
    var w = this.exports.getBitmapWidth(memberId);
    var h = this.exports.getBitmapHeight(memberId);
    if (w <= 0 || h <= 0) return null;
    // Copy out of WASM heap (the buffer may move after GC)
    var rgba = new Uint8ClampedArray(w * h * 4);
    rgba.set(new Uint8ClampedArray(this._mem(), ptr, rgba.length));
    return { w: w, h: h, rgba: rgba };
};

WasmEngine.prototype._drainRequests = function() {
    var count = this.exports.getPendingFetchCount(); this._clearEx();
    if (count === 0) return null;
    var len  = this.exports.getPendingFetchJson();   this._clearEx();
    var reqs = this._readJson(len);
    this.exports.drainPendingFetches();              this._clearEx();
    return reqs;
};

WasmEngine.prototype._deliverResult = function(taskId, arrayBuffer) {
    var bytes = new Uint8Array(arrayBuffer);
    var addr  = this.exports.allocateNetBuffer(bytes.length);
    new Uint8Array(this._mem(), addr, bytes.length).set(bytes);
    this.exports.deliverFetchResult(taskId, bytes.length);
    this._clearEx();
    this._castRevision++; // new data → bitmap cache will be invalidated on main
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
                _knownBitmaps = {};
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
                var n = _e.preloadCasts();
                if (n > 0) {
                    var p1 = _e.pumpNetworkCollect();
                    if (p1.length > 0) await Promise.all(p1);
                    // pump any secondary requests queued during prepareMovie
                    var p2 = _e.pumpNetworkCollect();
                    if (p2.length > 0) await Promise.all(p2);
                }
                self.postMessage({ type: 'castsDone' });
                break;
            }

            case 'play':
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
                var revBefore = _e._castRevision;
                try {
                    var stillPlaying = _e.tick();

                    // Await network requests queued during tick
                    var tp = _e.pumpNetworkCollect();
                    if (tp.length > 0) await Promise.all(tp);

                    var fd = _e.getFrameData();
                    var castCacheCleared = _e._castRevision > revBefore;

                    // When casts change, discard our known-bitmap set so main
                    // thread gets fresh bytes for all sprites
                    if (castCacheCleared) _knownBitmaps = {};

                    // Collect bitmap data for sprites the main thread hasn't seen yet
                    var bitmaps = {}, transferables = [];
                    if (fd) {
                        var memberIds = {};
                        if (fd.sprites) {
                            for (var i = 0; i < fd.sprites.length; i++) {
                                var sp = fd.sprites[i];
                                if (sp.memberId > 0 && sp.hasBaked) memberIds[sp.memberId] = true;
                            }
                        }
                        if (fd.stageImageId) memberIds[fd.stageImageId] = true;

                        for (var mid in memberIds) {
                            if (_knownBitmaps[mid]) continue; // main already has it
                            var bmpData = _e.getBitmapData(parseInt(mid, 10));
                            if (bmpData) {
                                bitmaps[mid] = bmpData;
                                transferables.push(bmpData.rgba.buffer);
                                _knownBitmaps[mid] = true;
                            }
                        }
                    }

                    self.postMessage({
                        type: 'frame',
                        playing:          stillPlaying,
                        enginePlaying:    _e.playing,
                        fd:               fd,
                        tempo:            _e._lastTempo,
                        lastFrame:        _e._lastFrame,
                        castCacheCleared: castCacheCleared,
                        bitmaps:          bitmaps
                    }, transferables);

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
