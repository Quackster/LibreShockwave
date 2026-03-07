'use strict';

// Suppress console.log/warn in the worker. When DevTools is open, Chrome renders
// every message (including TeaVM's System.out via putwchar) which stalls the
// message loop and prevents 'frame' responses from being processed in time.
// Errors are kept since they only fire in exceptional cases.
console.log = function() {};
console.warn = function() {};

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
 *   {type:'mouseMove',  x, y}
 *   {type:'mouseDown',  x, y, button}  (button: 0=left, 2=right)
 *   {type:'mouseUp',    x, y, button}
 *   {type:'keyDown',    keyCode, key, modifiers}  (modifiers: 1=shift,2=ctrl,4=alt)
 *   {type:'keyUp',      keyCode, key, modifiers}
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

// --- Multiuser Xtra WebSocket connections ---
var _musSockets = {};      // instanceId -> WebSocket
var _musInbound = {};      // instanceId -> [string] (MUS message bodies, tab-separated)

var _musConnected = {};    // instanceId -> true (pending connect notifications)
var _musDisconnected = {}; // instanceId -> true (pending disconnect notifications)
var _musErrors = {};       // instanceId -> errorCode

// --- Non-blocking fetch delivery queue ---
var _fetchQueue = [];   // [{taskId, data: ArrayBuffer}] or [{taskId, error: number}]
var _inFlight = 0;      // number of fetches currently in-progress
var _loadStartTime = 0; // timestamp when loading began (for perf logging)

// --- Cross-origin fetch relay (main thread fetches on behalf of worker) ---
var _fetchRelayMap = {};  // relayId -> { engine, taskId, url, fallbacks }
var _fetchRelayCounter = 0;

// --- Fetch with timeout (prevents hanging requests on mobile) ---
function _fetchWithTimeout(url, opts, timeoutMs) {
    timeoutMs = timeoutMs || 30000;
    var controller = new AbortController();
    var timer = setTimeout(function() { controller.abort(); }, timeoutMs);
    opts = opts || {};
    opts.signal = controller.signal;
    return fetch(url, opts).finally(function() { clearTimeout(timer); });
}

// --- JS-side response cache (eliminates duplicate network requests) ---
var _urlCache = {};     // url -> ArrayBuffer

// --- External params stored locally for pre-fetch access ---
var _params = {};

// --- Diagnostic tick counter ---
var _tickNum = 0;

// --- Movie base path (set during loadMovie) ---
var _movieBasePath = '';

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

// --- Input forwarding ---

WasmEngine.prototype.mouseMove = function(x, y) {
    this.exports.mouseMove(x, y); this._clearEx();
};

WasmEngine.prototype.mouseDown = function(x, y, button) {
    this.exports.mouseDown(x, y, button); this._clearEx();
};

WasmEngine.prototype.mouseUp = function(x, y, button) {
    this.exports.mouseUp(x, y, button); this._clearEx();
};

WasmEngine.prototype.keyDown = function(browserKeyCode, keyChar, modifiers) {
    var kb = new TextEncoder().encode(keyChar || '');
    if (kb.length > 0) {
        var sbuf = new Uint8Array(this._mem(), this.exports.getStringBufferAddress(), 4096);
        sbuf.set(kb);
    }
    this.exports.keyDown(browserKeyCode, kb.length, modifiers); this._clearEx();
};

WasmEngine.prototype.keyUp = function(browserKeyCode, keyChar, modifiers) {
    var kb = new TextEncoder().encode(keyChar || '');
    if (kb.length > 0) {
        var sbuf = new Uint8Array(this._mem(), this.exports.getStringBufferAddress(), 4096);
        sbuf.set(kb);
    }
    this.exports.keyUp(browserKeyCode, kb.length, modifiers); this._clearEx();
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

/**
 * Detect if a URL points to a cast file (.cct or .cst).
 */
function _isCastFile(url) {
    if (!url) return false;
    var lower = url.toLowerCase();
    var qi = lower.indexOf('?');
    if (qi > 0) lower = lower.substring(0, qi);
    return lower.endsWith('.cct') || lower.endsWith('.cst');
}

WasmEngine.prototype._deliverResult = function(taskId, arrayBuffer, url) {
    if (url && _isCastFile(url)) {
        // Cast files: mark net task done (status-only), no data in WASM.
        // Bytes stay in _urlCache; delivered on demand via deliverCastData.
        try {
            var urlBytes = new TextEncoder().encode(url);
            var addr = this.exports.allocateNetBuffer(0); this._clearEx();
            var sbuf = new Uint8Array(this._mem(), this.exports.getStringBufferAddress(), 4096);
            this._clearEx();
            sbuf.set(urlBytes);
            this.exports.deliverFetchStatus(taskId, urlBytes.length, arrayBuffer.byteLength);
            this._clearEx();
        } catch (e) {
            console.error('[WORKER] deliverFetchStatus error for ' + url + ': ' + e);
            this._clearEx();
        }
    } else {
        // Non-cast: deliver data normally
        try {
            var bytes = new Uint8Array(arrayBuffer);
            var addr  = this.exports.allocateNetBuffer(bytes.length); this._clearEx();
            new Uint8Array(this._mem(), addr, bytes.length).set(bytes);
            this.exports.deliverFetchResult(taskId, bytes.length);
            this._clearEx();
        } catch (e) {
            console.error('[WORKER] deliverFetchResult error for taskId=' + taskId + ': ' + e);
            this._clearEx();
        }
    }
};

/**
 * Deliver cast file data from JS _urlCache into WASM for parsing.
 * URL written to stringBuffer, data to netBuffer. Returns true if delivered.
 */
WasmEngine.prototype._wasmDead = false;

// Cast files known to trigger WASM hangs (infinite loops in DirectorFile.load).
// These are skipped entirely to prevent blocking the worker thread.
WasmEngine.prototype._castBlocklist = {};

WasmEngine.prototype._deliverCastData = function(url) {
    if (this._wasmDead) return false;
    // Check blocklist by base name
    var bn = url.replace(/.*\//, '').replace(/\.[^.]+$/, '');
    if (this._castBlocklist[bn]) {
        console.log('[WORKER] BLOCKED cast (known bad): ' + url);
        return false;
    }
    var data = _findCachedResponse(url, []);
    if (!data) return false;
    var urlBytes = new TextEncoder().encode(url);
    var addr;
    try {
        addr = this.exports.allocateNetBuffer(data.byteLength); this._clearEx();
    } catch (e) {
        console.error('[WORKER] allocateNetBuffer OOB for ' + url + ': ' + e);
        this._wasmDead = true; return false;
    }
    var mem = this._mem();
    var strAddr;
    try {
        strAddr = this.exports.getStringBufferAddress(); this._clearEx();
    } catch (e) {
        console.error('[WORKER] getStringBufferAddress failed: ' + e);
        this._wasmDead = true; return false;
    }
    try {
        new Uint8Array(mem, strAddr, urlBytes.length).set(urlBytes);
        new Uint8Array(mem, addr, data.byteLength).set(new Uint8Array(data));
    } catch (e) {
        console.error('[WORKER] memcpy OOB for ' + url + ': ' + e);
        return false;
    }
    try {
        this.exports.deliverCastData(urlBytes.length, data.byteLength);
        this._clearEx();
    } catch (e) {
        console.error('[WORKER] deliverCastData WASM error for ' + url + ': ' + e);
        // WASM OOB trap corrupts the instance — check if still alive
        try { this.exports.getStringBufferAddress(); this._clearEx(); } catch (e2) {
            console.error('[WORKER] WASM instance dead after ' + url);
            this._wasmDead = true;
        }
        return false;
    }
    // Verify WASM is still healthy after parsing
    try { this.exports.getStringBufferAddress(); this._clearEx(); } catch (e) {
        console.error('[WORKER] WASM instance dead after parsing ' + url);
        this._wasmDead = true;
        return false;
    }
    return true;
};

/**
 * Deliver all available static casts (fetched during preloadCasts) into WASM.
 */
WasmEngine.prototype._deliverAvailableCasts = function() {
    if (this._wasmDead) return 0;
    var count = this.exports.getAvailableCastCount(); this._clearEx();
    if (count === 0) return 0;
    var strAddr = this.exports.getStringBufferAddress();
    var delivered = 0;
    for (var i = 0; i < count; i++) {
        var urlLen = this.exports.getAvailableCastUrl(i); this._clearEx();
        if (urlLen <= 0) continue;
        var url = this._readString(strAddr, urlLen);
        console.log('[WORKER] delivering static cast: ' + url);
        if (this._deliverCastData(url)) {
            delivered++;
            console.log('[WORKER] delivered static cast: ' + url);
        }
    }
    this.exports.drainAvailableCasts(); this._clearEx();
    return delivered;
};

/**
 * Deliver pending dynamic cast requests (from Lingo setting castLib.fileName).
 */
// Track casts that caused WASM errors — never retry them
WasmEngine.prototype._failedCastUrls = {};

WasmEngine.prototype._deliverPendingCastRequests = function() {
    if (this._wasmDead) return 0;
    var count = this.exports.getPendingCastDataCount(); this._clearEx();
    if (count === 0) return 0;
    var strAddr = this.exports.getStringBufferAddress();
    var baseDir = _getBaseDir(_movieBasePath);
    var delivered = 0;
    for (var i = 0; i < count; i++) {
        if (this._wasmDead) break;
        var nameLen = this.exports.getPendingCastDataUrl(i); this._clearEx();
        if (nameLen <= 0) continue;
        var fileName = this._readString(strAddr, nameLen);
        // Try to find in cache. If fileName is already a full URL, check it directly.
        // Otherwise construct basePath + fileName with .cct/.cst extensions.
        var url = null;
        if (/^https?:\/\//i.test(fileName)) {
            // Full URL — check directly in cache (with and without extension variants)
            if (_findCachedResponse(fileName, [])) {
                url = fileName;
            } else {
                var baseName = fileName.replace(/\.[^.]+$/, '');
                var cctUrl = baseName + '.cct';
                var cstUrl = baseName + '.cst';
                url = _findCachedResponse(cctUrl, []) ? cctUrl :
                      _findCachedResponse(cstUrl, []) ? cstUrl : null;
            }
        } else {
            var baseName = fileName.replace(/\.[^.]+$/, '');
            var cctUrl = baseDir + baseName + '.cct';
            var cstUrl = baseDir + baseName + '.cst';
            url = _findCachedResponse(cctUrl, []) ? cctUrl :
                  _findCachedResponse(cstUrl, []) ? cstUrl : null;
        }
        if (!url) continue;
        if (this._failedCastUrls[url]) continue; // Skip known-bad casts
        console.log('[WORKER] delivering dynamic cast: ' + url);
        if (this._deliverCastData(url)) {
            delivered++;
            console.log('[WORKER] delivered dynamic cast: ' + url + ' (requested: ' + fileName + ')');
        } else {
            this._failedCastUrls[url] = true;
            console.log('[WORKER] FAILED dynamic cast: ' + url);
        }
    }
    this.exports.drainPendingCastDataRequests(); this._clearEx();
    return delivered;
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
    return _fetchWithTimeout(url, opts)
        .then(function(r) { if (!r.ok) throw { status: r.status }; return r.arrayBuffer(); })
        .then(function(buf) {
            _urlCache[url] = buf; // Cache response
            self._deliverResult(taskId, buf, url);
        })
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
            // Check JS-side cache first
            var cached = _findCachedResponse(req.url, req.fallbacks);
            if (cached) {
                self._deliverResult(req.taskId, cached, req.url);
                promises.push(Promise.resolve());
            } else {
                var p = self._doFetch(req.taskId, req.url, req.method, req.postData, req.fallbacks || []);
                promises.push(p.then(null, function() {}));
            }
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
    console.log('[WORKER] fetch: ' + url + (fallbacks.length ? ' (+' + fallbacks.length + ' fallbacks)' : ''));

    if (_isCrossOrigin(url)) {
        // Relay through main thread: cross-origin fetches from a Worker can hang
        // in Chrome. The main thread fetches without this issue.
        // NOTE: use postMessage() not self.postMessage() — 'self' is shadowed by 'var self = this'
        var relayId = ++_fetchRelayCounter;
        _fetchRelayMap[relayId] = {
            engine: self, taskId: taskId, url: url,
            method: method, postData: postData, fallbacks: fallbacks || []
        };
        postMessage({ type: 'fetchRelay', relayId: relayId,
                      url: url, method: method, postData: postData });
        return;
    }

    var opts = {};
    if (method === 'POST') {
        opts.method  = 'POST';
        opts.body    = postData;
        opts.headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
    }
    _fetchWithTimeout(url, opts)
        .then(function(r) { if (!r.ok) throw { status: r.status }; return r.arrayBuffer(); })
        .then(function(buf) {
            _urlCache[url] = buf;
            console.log('[WORKER] fetch OK: ' + url + ' (' + buf.byteLength + ' bytes)');
            _fetchQueue.push({ taskId: taskId, data: buf, url: url });
            _inFlight--;
        })
        .catch(function(e) {
            console.log('[WORKER] fetch ERR: ' + url + ' status=' + ((e && e.status) || 'network'));
            if (fallbacks.length > 0) {
                _inFlight--;
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
            this._deliverResult(item.taskId, item.data, item.url);
        } else {
            this._deliverError(item.taskId, item.error);
        }
        count++;
    }
    return count;
};

/**
 * Drain pending requests from WASM and fire them all non-blocking.
 * Checks JS-side cache first to avoid duplicate fetches.
 * @return number of requests fired
 */
WasmEngine.prototype.pumpNetworkFire = function() {
    var reqs = this._drainRequests();
    if (!reqs) return 0;
    for (var i = 0; i < reqs.length; i++) {
        var req = reqs[i];
        // Check JS-side cache first — deliver immediately if cached
        var cached = _findCachedResponse(req.url, req.fallbacks);
        if (cached) {
            console.log('[WORKER] cache HIT: ' + req.url);
            _fetchQueue.push({ taskId: req.taskId, data: cached, url: req.url });
        } else {
            this._doFetchAsync(req.taskId, req.url, req.method, req.postData, req.fallbacks || []);
        }
    }
    return reqs.length;
};

// ============================================================
// Multiuser Xtra — WebSocket bridge
// ============================================================

/**
 * Drain pending MUS requests from WASM and act on them:
 *   type 0 = connect → open WebSocket
 *   type 1 = send    → send on existing WebSocket
 *   type 2 = disconnect → close WebSocket
 *
 * WebSocket URL is built as ws://host:port (or wss:// if page is HTTPS).
 * A WebSocket proxy must be running at that address to bridge to the
 * real Multiuser Server TCP socket.
 */
WasmEngine.prototype.pumpMusRequests = function() {
    if (this._wasmDead) return;
    var count;
    try {
        count = this.exports.getMusPendingCount(); this._clearEx();
    } catch(e) { return; }
    if (count === 0) return;

    var strAddr = this.exports.getStringBufferAddress(); this._clearEx();

    for (var i = 0; i < count; i++) {
        var type = this.exports.getMusPendingType(i); this._clearEx();
        var instId = this.exports.getMusPendingInstanceId(i); this._clearEx();

        if (type === 0) {
            // CONNECT
            var hostLen = this.exports.getMusPendingHost(i); this._clearEx();
            var host = this._readString(strAddr, hostLen);
            var port = this.exports.getMusPendingPort(i); this._clearEx();

            var protocol = (self.location && self.location.protocol === 'https:') ? 'wss' : 'ws';
            var wsUrl = protocol + '://' + host + ':' + port;
            this._musConnect(instId, wsUrl);

        } else if (type === 1) {
            // SEND — raw content bytes (must be binary frame for websockify)
            var dataLen = this.exports.getMusPendingSendData(i); this._clearEx();
            var data = this._readString(strAddr, dataLen);
            var ws = _musSockets[instId];
            if (ws && ws.readyState === WebSocket.OPEN) {
                var bytes = new TextEncoder().encode(data);
                ws.send(bytes.buffer);
            }

        } else if (type === 2) {
            // DISCONNECT
            var ws2 = _musSockets[instId];
            if (ws2) {
                ws2.onclose = null; // prevent double-notification
                ws2.close();
                delete _musSockets[instId];
            }
        }
    }

    this.exports.drainMusPending(); this._clearEx();
};

/**
 * Open a WebSocket and wire up event handlers.
 * Messages are queued in JS and delivered to WASM during the next tick.
 */
WasmEngine.prototype._musConnect = function(instId, wsUrl) {
    // Close any existing connection for this instance
    if (_musSockets[instId]) {
        _musSockets[instId].onclose = null;
        _musSockets[instId].close();
    }

    var ws;
    try {
        ws = new WebSocket(wsUrl);
    } catch(e) {
        self.postMessage({type:'error', msg:'[MUS] WebSocket constructor failed: ' + e.message});
        _musErrors[instId] = -3; // connection refused
        return;
    }
    ws.binaryType = 'arraybuffer';
    _musSockets[instId] = ws;

    ws.onopen = function() {
        _musConnected[instId] = true;
    };

    ws.onmessage = function(evt) {
        // Receive raw message content
        if (!_musInbound[instId]) _musInbound[instId] = [];
        var data;
        if (typeof evt.data === 'string') {
            data = evt.data;
        } else {
            data = new TextDecoder().decode(new Uint8Array(evt.data));
        }
        _musInbound[instId].push(data);
    };

    ws.onclose = function() {
        _musDisconnected[instId] = true;
        delete _musSockets[instId];
    };

    ws.onerror = function() {
        _musErrors[instId] = -2; // network error
    };
};

/**
 * Deliver queued MUS events (connected/messages/disconnected/errors) to WASM.
 * Called at the start of each tick, before WASM tick().
 */
WasmEngine.prototype.deliverMusEvents = function() {
    if (this._wasmDead) return;
    var strAddr;
    try {
        strAddr = this.exports.getStringBufferAddress(); this._clearEx();
    } catch(e) { return; }

    // Deliver connect notifications
    for (var id in _musConnected) {
        try {
            this.exports.musDeliverConnected(parseInt(id)); this._clearEx();
        } catch(e) {}
    }
    _musConnected = {};

    // Deliver error notifications
    for (var eid in _musErrors) {
        try {
            this.exports.musDeliverError(parseInt(eid), _musErrors[eid]); this._clearEx();
        } catch(e) {}
    }
    _musErrors = {};

    // Deliver messages
    for (var mid in _musInbound) {
        var msgs = _musInbound[mid];
        var iid = parseInt(mid);
        for (var i = 0; i < msgs.length; i++) {
            var msgBytes = new TextEncoder().encode(msgs[i]);
            var len = Math.min(msgBytes.length, 4096);
            new Uint8Array(this._mem(), strAddr, len).set(msgBytes.subarray(0, len));
            try {
                this.exports.musDeliverMessage(iid, len); this._clearEx();
            } catch(e) {
                self.postMessage({type:'error', msg:'[MUS] musDeliverMessage error: ' + e});
            }
        }
    }
    _musInbound = {};

    // Deliver disconnect notifications
    for (var did in _musDisconnected) {
        try {
            this.exports.musDeliverDisconnected(parseInt(did)); this._clearEx();
        } catch(e) {}
    }
    _musDisconnected = {};
};

// ============================================================
// JS-side response cache helpers
// ============================================================

/**
 * Find a cached response for a URL, checking the primary URL and all fallbacks.
 * @return ArrayBuffer or null
 */
function _findCachedResponse(url, fallbacks) {
    if (_urlCache[url]) return _urlCache[url];
    // Also check without query string (for cache-busting params like ?281)
    var qi = url.indexOf('?');
    if (qi > 0 && _urlCache[url.substring(0, qi)]) return _urlCache[url.substring(0, qi)];
    if (fallbacks) {
        for (var i = 0; i < fallbacks.length; i++) {
            if (_urlCache[fallbacks[i]]) return _urlCache[fallbacks[i]];
            var fqi = fallbacks[i].indexOf('?');
            if (fqi > 0 && _urlCache[fallbacks[i].substring(0, fqi)]) return _urlCache[fallbacks[i].substring(0, fqi)];
        }
    }
    return null;
}

/**
 * Returns true if the URL is cross-origin relative to this worker.
 * Cross-origin fetches from a Web Worker can hang in Chrome when
 * the host:port differs from the page origin. We relay those through
 * the main thread, which has no such limitation.
 */
function _isCrossOrigin(url) {
    try {
        return new URL(url, self.location.href).origin !== self.location.origin;
    } catch(e) { return false; }
}

/**
 * Compute the base directory from a movie URL.
 * "http://host/path/movie.dcr" -> "http://host/path/"
 */
function _getBaseDir(url) {
    var i = url.lastIndexOf('/');
    return i >= 0 ? url.substring(0, i + 1) : '';
}

/**
 * Pre-fetch sw1 param URLs (external_variables, external_texts)
 * and any dynamic cast entries found in external_variables.txt.
 * This runs during preloadCasts, BEFORE play(), so the data is
 * cached and available instantly when the Lingo state machine needs it.
 */
async function _prefetchSw1Assets(basePath) {
    var baseDir = _getBaseDir(basePath);

    // Collect all HTTP URLs from ALL sw params (sw1-sw9).
    // Params use "key=value;key=value" format; values containing "://" are URLs.
    var urls = [];
    for (var i = 1; i <= 9; i++) {
        var sw = _params['sw' + i] || _params['SW' + i];
        if (!sw) continue;
        sw.split(';').forEach(function(pair) {
            pair = pair.trim();
            var eq = pair.indexOf('=');
            if (eq < 0) return;
            var val = pair.substring(eq + 1).trim();
            if (val.indexOf('://') !== -1) urls.push(val);
        });
    }
    if (urls.length === 0) return;

    console.log('[WORKER] Pre-fetching ' + urls.length + ' sw1 URLs');

    // Fetch all sw1 URLs in parallel to parse their content.
    // Apply locale override to external_variables.txt so hh_entry_au is used,
    // then cache the modified version (overrides any ORIGINAL cached by pumpNetworkCollect).
    var results = await Promise.all(urls.map(function(url) {
        return _fetchWithTimeout(url)
            .then(function(r) { if (!r.ok) throw { status: r.status }; return r.arrayBuffer(); })
            .then(function(buf) {
                if (url.indexOf('external_variables') !== -1) {
                    try {
                        var text = new TextDecoder().decode(new Uint8Array(buf));
                        text = text.replace(/cast\.entry\.2=.*/g, 'cast.entry.2=hh_entry_au');
                        buf = new TextEncoder().encode(text).buffer;
                        console.log('[WORKER] Applied hh_entry_au locale override');
                    } catch(ex) {}
                }
                _urlCache[url] = buf; // cache modified version; overwrites any stale original
                return buf;
            })
            .catch(function() { return null; });
    }));

    // Parse external_variables.txt to find cast entries
    var castNames = [];
    for (var i = 0; i < results.length; i++) {
        if (!results[i]) continue;
        if (urls[i].indexOf('external_variables') !== -1 || urls[i].indexOf('external_variable') !== -1) {
            var text = new TextDecoder().decode(new Uint8Array(results[i]));
            var lines = text.split('\n');
            for (var j = 0; j < lines.length; j++) {
                var m = lines[j].match(/^cast\.entry\.\d+=(.+)/);
                if (m) castNames.push(m[1].trim());
            }
        }
    }

    if (castNames.length === 0) return;
    console.log('[WORKER] Pre-fetching ' + castNames.length + ' dynamic cast files: ' + castNames.join(', '));

    // Fetch all dynamic cast .cct files in parallel (with .cst fallback)
    var castFetches = castNames.map(function(name) {
        var cctUrl = baseDir + name + '.cct';
        var cstUrl = baseDir + name + '.cst';
        if (_urlCache[cctUrl] || _urlCache[cstUrl]) return Promise.resolve();
        return _fetchWithTimeout(cctUrl)
            .then(function(r) { if (!r.ok) throw 'not found'; return r.arrayBuffer(); })
            .then(function(buf) { _urlCache[cctUrl] = buf; _urlCache[cstUrl] = buf; })
            .catch(function() {
                return _fetchWithTimeout(cstUrl)
                    .then(function(r) { if (!r.ok) throw 'not found'; return r.arrayBuffer(); })
                    .then(function(buf) { _urlCache[cctUrl] = buf; _urlCache[cstUrl] = buf; })
                    .catch(function() {});
            });
    });
    await Promise.all(castFetches);
    console.log('[WORKER] Pre-fetch complete, cache has ' + Object.keys(_urlCache).length + ' entries');
}

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
                _urlCache = {}; // Clear cache for new movie
                _tickNum = 0;
                _movieBasePath = msg.basePath || '';
                var info = _e.loadMovie(new Uint8Array(msg.data), msg.basePath);
                _e.playing  = false;
                self.postMessage({ type: 'movieLoaded', info: info });
                break;
            }

            case 'setParam':
                _e.setExternalParam(msg.key, msg.value);
                _params[msg.key] = msg.value; // Store locally for pre-fetch
                break;

            case 'setDebugPlayback':
                _e.exports.setDebugPlaybackEnabled(msg.enabled ? 1 : 0);
                _e._clearEx();
                break;

            case 'clearParams':
                _e.clearExternalParams();
                _params = {};
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

                // Pre-fetch sw1 assets (external_variables, external_texts, dynamic casts)
                // so they're cached before the Lingo state machine needs them
                try {
                    await _prefetchSw1Assets(_movieBasePath);
                } catch (prefetchErr) {
                    console.error('[WORKER] prefetch error: ' + prefetchErr);
                }

                // Deliver available static casts into WASM (one at a time, parsed then freed)
                try {
                    var castsDel = _e._deliverAvailableCasts();
                    if (castsDel > 0) console.log('[WORKER] delivered ' + castsDel + ' static casts');
                } catch (castErr) {
                    console.error('[WORKER] cast delivery error: ' + castErr);
                }

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

            // --- Input events ---
            case 'mouseMove':
                if (_e && !_e._wasmDead) try { _e.mouseMove(msg.x, msg.y); } catch(ie) {}
                break;
            case 'mouseDown':
                if (_e && !_e._wasmDead) try {
                    _e.mouseDown(msg.x, msg.y, msg.button);
                } catch(ie) {
                    console.error('[WORKER] mouseDown error:', ie);
                }
                break;
            case 'mouseUp':
                if (_e && !_e._wasmDead) try { _e.mouseUp(msg.x, msg.y, msg.button); } catch(ie) {
                    console.error('[WORKER] mouseUp error:', ie);
                }
                break;
            case 'keyDown':
                if (_e && !_e._wasmDead) try {
                    _e.keyDown(msg.keyCode, msg.key || '', msg.modifiers);
                } catch(ie) { console.error('[WORKER] keyDown error:', ie); }
                break;
            case 'keyUp':
                if (_e && !_e._wasmDead) try {
                    _e.keyUp(msg.keyCode, msg.key || '', msg.modifiers);
                } catch(ie) { console.error('[WORKER] keyUp error:', ie); }
                break;

            case 'fetchRelayResult': {
                // Main thread completed a cross-origin fetch on our behalf
                var relay = _fetchRelayMap[msg.relayId];
                if (!relay) break;
                delete _fetchRelayMap[msg.relayId];
                if (msg.error) {
                    console.log('[WORKER] relay ERR: ' + relay.url + ' status=' + (msg.status || 0));
                    if (relay.fallbacks.length > 0) {
                        _inFlight--; // retry will re-increment
                        relay.engine._doFetchAsync(relay.taskId, relay.fallbacks[0],
                                                   relay.method, relay.postData,
                                                   relay.fallbacks.slice(1));
                    } else {
                        _fetchQueue.push({ taskId: relay.taskId, error: msg.status || 0 });
                        _inFlight--;
                    }
                } else {
                    _urlCache[relay.url] = msg.data;
                    console.log('[WORKER] relay OK: ' + relay.url + ' (' + msg.data.byteLength + ' bytes)');
                    _fetchQueue.push({ taskId: relay.taskId, data: msg.data, url: relay.url });
                    _inFlight--;
                }
                break;
            }

            case 'tick': {
                if (_isTicking) {
                    // ALWAYS respond so main thread's _waitFor('frame') doesn't hang
                    self.postMessage({
                        type: 'frame', playing: true,
                        enginePlaying: _e ? _e.playing : false,
                        tempo: _e ? _e._lastTempo : 15,
                        lastFrame: _e ? _e._lastFrame : 0,
                        frameCount: _e ? _e._lastFrameCount : 0,
                        rgba: null, width: 0, height: 0, spriteCount: 0
                    });
                    return;
                }
                _isTicking = true;
                try {
                    var stillPlaying = true;
                    var frame = null;
                    _tickNum++;

                    // Phase 0: deliver pending dynamic cast requests from Lingo
                    // and any newly-available static casts
                    if (!_e._wasmDead) {
                        try {
                            _e._deliverPendingCastRequests();
                            _e._deliverAvailableCasts();
                        } catch (castErr) {
                            console.error('[WORKER] cast delivery error: ' + castErr);
                        }
                    }

                    // Phase 1: deliver completed network results from previous ticks.
                    // This runs BEFORE tick() so netDone() returns true for finished fetches.
                    var nDelivered = 0;
                    if (!_e._wasmDead) {
                        try {
                            nDelivered = _e.deliverQueuedResults();
                        } catch (deliverErr) {
                            console.error('[WORKER] deliver error: ' + deliverErr);
                        }
                    }

                    // Phase 1.5: deliver cast data for any casts just marked available
                    // by deliverQueuedResults. Without this, Lingo sees netDone()=true
                    // but cast members aren't loaded yet → "Cast number expected:" errors.
                    if (!_e._wasmDead) {
                        try {
                            _e._deliverAvailableCasts();
                            _e._deliverPendingCastRequests();
                        } catch (castErr2) {
                            console.error('[WORKER] post-deliver cast error: ' + castErr2);
                        }
                    }

                    // Phase 1.8: deliver pending Multiuser Xtra events (WebSocket)
                    if (!_e._wasmDead) {
                        try { _e.deliverMusEvents(); }
                        catch (musErr) { console.error('[WORKER] MUS deliver error: ' + musErr); }
                    }

                    // Phase 2: advance WASM by one Lingo frame
                    if (_e._wasmDead) {
                        console.error('[WORKER] WASM instance is dead, skipping tick');
                        stillPlaying = false;
                    } else {
                        var tickT0 = performance.now();
                        try {
                            stillPlaying = _e.tick();
                        } catch (tickErr) {
                            console.error('[WORKER] tick() error: ' + tickErr);
                            // Check if WASM is still alive
                            try { _e.exports.getStringBufferAddress(); _e._clearEx(); } catch(e) {
                                console.error('[WORKER] WASM instance dead after tick error');
                                _e._wasmDead = true;
                            }
                        }
                    }

                    // Phase 3: fire new network requests (non-blocking).
                    // Results are queued asynchronously and delivered at the start of
                    // the next tick. This decouples network I/O from frame execution,
                    // preventing deadlocks when Lingo polls netDone() in update loops.
                    var nFired = 0;
                    try {
                        nFired = _e.pumpNetworkFire();
                    } catch (netErr) {
                        console.error('[WORKER] pump error: ' + netErr);
                    }

                    // Phase 3.5: pump Multiuser Xtra WebSocket requests
                    try { _e.pumpMusRequests(); }
                    catch (musErr2) { console.error('[WORKER] MUS pump error: ' + musErr2); }

                    // Always update frame metadata from WASM (needed for fast-loop detection)
                    try {
                        _e._lastFrame      = _e.exports.getCurrentFrame();
                        _e._lastFrameCount = _e.exports.getFrameCount();
                    } catch (ignore) {}

                    // Phase 4: render (skip during fast-loading for performance)
                    if (!msg.skipRender) {
                        try {
                            frame = _e.renderFrame();
                        } catch (renderErr) {
                            console.error('[WORKER] render() error: ' + renderErr);
                        }
                    }

                    // Always read sprite count (needed for fast-loop detection on main thread)
                    var spriteCount = 0;
                    try { spriteCount = _e.exports.getSpriteCount(); _e._clearEx(); } catch(e4) {}

                    // Read cursor type for current mouse position
                    var cursorType = 0;
                    try { cursorType = _e.exports.getCursorType(); _e._clearEx(); } catch(e5) {}

                    // Drain debug log from WASM
                    var debugLog = null;
                    try {
                        var logLen = _e.exports.getDebugLog(); _e._clearEx();
                        if (logLen > 0) {
                            var strAddr = _e.exports.getStringBufferAddress(); _e._clearEx();
                            debugLog = _e._readString(strAddr, logLen);
                        }
                    } catch (logErr) {}

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
                        height:        frame ? frame.h : 0,
                        spriteCount:   spriteCount,
                        cursorType:    cursorType,
                        debugLog:      debugLog
                    }, frame ? [frame.rgba.buffer] : []);

                } finally {
                    _isTicking = false;
                }
                break;
            }

            case 'triggerTestError': {
                var result = 0;
                try {
                    result = _e.exports.triggerTestError(); _e._clearEx();
                } catch (err) {}
                // Re-render so the error dialog appears on screen
                var frame = null;
                try { frame = _e.renderFrame(); } catch (re) {}
                self.postMessage({
                    type: 'frame',
                    playing: true,
                    enginePlaying: _e.playing,
                    tempo: _e._lastTempo,
                    lastFrame: _e._lastFrame,
                    frameCount: _e._lastFrameCount,
                    rgba: frame ? frame.rgba : null,
                    width: frame ? frame.w : 0,
                    height: frame ? frame.h : 0,
                    spriteCount: 0
                }, frame ? [frame.rgba.buffer] : []);
                break;
            }

            case 'getCallStack': {
                var stackStr = '';
                try {
                    var len = _e.exports.getCallStack(); _e._clearEx();
                    if (len > 0) {
                        var addr = _e.exports.getStringBufferAddress(); _e._clearEx();
                        stackStr = _e._readString(addr, len);
                    }
                } catch (csErr) {}
                self.postMessage({ type: 'callStack', callStack: stackStr });
                break;
            }

            default:
                break;
        }
    } catch (err) {
        self.postMessage({ type: 'error', msg: String(err) });
    }
};
