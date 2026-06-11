'use strict';

var _module = null;
var _exports = null;
var _memory = null;
var _basePath = '';
var _ready = false;
var _playing = false;

var _requiredExports = {
    allocateBuffer: 'allocate_buffer',
    getStringBufferAddress: 'get_string_buffer_address',
    getStringBufferCapacity: 'get_string_buffer_capacity',
    loadMovie: 'load_movie',
    preloadCasts: 'preload_casts',
    play: 'play',
    tick: 'tick',
    pause: 'pause',
    stop: 'stop',
    stepForward: 'step_forward',
    stepBackward: 'step_backward',
    setScriptTimeoutMs: 'set_script_timeout_ms',
    getStageWidth: 'stage_width',
    getStageHeight: 'stage_height',
    getFrameCount: 'frame_count',
    getCurrentFrame: 'current_frame',
    getTempo: 'tempo',
    render: 'render',
    getRenderBufferAddress: 'get_render_buffer_address',
    getSpriteCount: 'get_sprite_count',
    getPendingFetchCount: 'get_pending_fetch_count',
    getPendingFetchTaskId: 'get_pending_fetch_task_id',
    getPendingFetchUrl: 'get_pending_fetch_url',
    getPendingFetchFallbackCount: 'get_pending_fetch_fallback_count',
    getPendingFetchFallbackUrl: 'get_pending_fetch_fallback_url',
    drainPendingFetches: 'drain_pending_fetches',
    allocateNetBuffer: 'allocate_net_buffer',
    deliverFetchResult: 'deliver_fetch_result',
    deliverFetchError: 'deliver_fetch_error',
    mouseMove: 'mouse_move',
    mouseDown: 'mouse_down',
    mouseUp: 'mouse_up',
    keyDown: 'key_down',
    keyUp: 'key_up',
    blur: 'blur',
    getLastError: 'get_last_error'
};

function _exportRoots(source) {
    var roots = [];
    function add(value) {
        if (value && roots.indexOf(value) === -1) {
            roots.push(value);
        }
    }
    add(source);
    add(source && source.exports);
    add(source && source.asm);
    add(source && source.wasmExports);
    add(source && source.instance && source.instance.exports);
    return roots;
}

function _findExport(source, suffix) {
    var fullName = 'libreshockwave_wasm_' + suffix;
    var names = [fullName, '_' + fullName];
    var roots = _exportRoots(source);
    for (var r = 0; r < roots.length; r++) {
        for (var n = 0; n < names.length; n++) {
            var fn = roots[r][names[n]];
            if (typeof fn === 'function') {
                return fn.bind(roots[r]);
            }
        }
    }
    return null;
}

function _findMemory(source) {
    var roots = _exportRoots(source);
    for (var r = 0; r < roots.length; r++) {
        if (roots[r].memory && roots[r].memory.buffer) {
            return roots[r].memory;
        }
        if (roots[r].wasmMemory && roots[r].wasmMemory.buffer) {
            return roots[r].wasmMemory;
        }
    }
    return null;
}

function _createExports(source) {
    var adapted = {};
    for (var publicName in _requiredExports) {
        var fn = _findExport(source, _requiredExports[publicName]);
        if (!fn) {
            throw new Error('Missing C++ WASM export: libreshockwave_wasm_' + _requiredExports[publicName]);
        }
        adapted[publicName] = fn;
    }
    return adapted;
}

function _mem() {
    return _memory.buffer;
}

function _stringBufferView() {
    var addr = _exports.getStringBufferAddress();
    var capacity = _exports.getStringBufferCapacity();
    return new Uint8Array(_mem(), addr, capacity);
}

function _writeString(value) {
    var bytes = new TextEncoder().encode(value || '');
    var buffer = _stringBufferView();
    var length = Math.min(bytes.length, buffer.length);
    buffer.set(bytes.subarray(0, length), 0);
    return length;
}

function _readString(length) {
    if (length <= 0) {
        return '';
    }
    var addr = _exports.getStringBufferAddress();
    return new TextDecoder().decode(new Uint8Array(_mem(), addr, length));
}

function _readLastError() {
    var length = _exports.getLastError();
    if (length <= 0) {
        return '';
    }
    return _readString(length);
}

function _postLastError() {
    var error = _readLastError();
    if (error) {
        self.postMessage({ type: 'error', message: error });
    }
}

function _resolveUrl(url) {
    return new URL(url || '', _basePath || self.location.href).href;
}

async function _fetchFirst(urls) {
    var lastStatus = 0;
    for (var i = 0; i < urls.length; i++) {
        var url = _resolveUrl(urls[i]);
        try {
            var response = await fetch(url);
            lastStatus = response.status || 0;
            if (response.ok) {
                return { data: await response.arrayBuffer(), url: url };
            }
        } catch (e) {
            lastStatus = 0;
        }
    }
    return { error: true, status: lastStatus || 404 };
}

async function _drainFetches(maxRounds) {
    var delivered = 0;
    var failed = 0;
    for (var round = 0; round < maxRounds; round++) {
        var count = _exports.getPendingFetchCount();
        if (count <= 0) {
            break;
        }
        var requests = [];
        for (var i = 0; i < count; i++) {
            var taskId = _exports.getPendingFetchTaskId(i);
            var urlLen = _exports.getPendingFetchUrl(i);
            var urls = [_readString(urlLen)];
            var fallbackCount = _exports.getPendingFetchFallbackCount(i);
            for (var f = 0; f < fallbackCount; f++) {
                urls.push(_readString(_exports.getPendingFetchFallbackUrl(i, f)));
            }
            requests.push({ taskId: taskId, urls: urls });
        }
        _exports.drainPendingFetches();
        for (var r = 0; r < requests.length; r++) {
            var result = await _fetchFirst(requests[r].urls);
            if (result.error) {
                _exports.deliverFetchError(requests[r].taskId, result.status);
                failed++;
                continue;
            }
            var bytes = new Uint8Array(result.data);
            var addr = _exports.allocateNetBuffer(bytes.length);
            new Uint8Array(_mem(), addr, bytes.length).set(bytes);
            _exports.deliverFetchResult(requests[r].taskId, bytes.length);
            delivered++;
        }
    }
    return { delivered: delivered, failed: failed };
}

function _renderFrame() {
    var length = _exports.render();
    if (length <= 0) {
        _postLastError();
        return null;
    }
    var width = _exports.getStageWidth();
    var height = _exports.getStageHeight();
    var ptr = _exports.getRenderBufferAddress();
    if (width <= 0 || height <= 0 || ptr === 0) {
        return null;
    }
    var rgba = new Uint8ClampedArray(length);
    rgba.set(new Uint8ClampedArray(_mem(), ptr, length));
    return {
        width: width,
        height: height,
        frame: _exports.getCurrentFrame(),
        frameCount: _exports.getFrameCount(),
        tempo: _exports.getTempo(),
        spriteCount: _exports.getSpriteCount(),
        rgba: rgba
    };
}

function _postFrame() {
    var frame = _renderFrame();
    if (!frame) {
        return;
    }
    self.postMessage({
        type: 'frame',
        width: frame.width,
        height: frame.height,
        frame: frame.frame,
        frameCount: frame.frameCount,
        tempo: frame.tempo,
        spriteCount: frame.spriteCount,
        rgba: frame.rgba
    }, [frame.rgba.buffer]);
}

async function _loadMovie(message) {
    var movieBytes = new Uint8Array(message.data || new ArrayBuffer(0));
    var basePathLength = _writeString(message.basePath || _basePath);
    var movieAddr = _exports.allocateBuffer(movieBytes.length);
    new Uint8Array(_mem(), movieAddr, movieBytes.length).set(movieBytes);
    var packedStage = _exports.loadMovie(movieBytes.length, basePathLength);
    if (message.scriptTimeoutMs != null) {
        _exports.setScriptTimeoutMs(message.scriptTimeoutMs | 0);
    }
    await _drainFetches(32);
    _exports.preloadCasts();
    await _drainFetches(32);
    if (!packedStage) {
        var loadError = _readLastError() || 'C++ WASM movie load failed';
        self.postMessage({ type: 'error', message: loadError });
        return;
    }
    var width = (packedStage >>> 16) & 0xffff;
    var height = packedStage & 0xffff;
    self.postMessage({
        type: 'loaded',
        width: width,
        height: height,
        frameCount: _exports.getFrameCount(),
        tempo: _exports.getTempo()
    });
    _postFrame();
    if (message.autoplay !== false) {
        _playing = true;
        _exports.play();
        await _drainFetches(32);
        _postFrame();
    }
}

async function _tick() {
    if (!_ready) {
        return;
    }
    if (_playing) {
        _exports.tick();
        await _drainFetches(8);
    }
    _postLastError();
    _postFrame();
}

async function _init(message) {
    _basePath = message.basePath || '';
    importScripts(_resolveUrl('libreshockwave-cpp-wasm.js'));
    if (typeof createLibreShockwaveCppWasm !== 'function') {
        throw new Error('createLibreShockwaveCppWasm was not exported by libreshockwave-cpp-wasm.js');
    }
    _module = await createLibreShockwaveCppWasm({
        locateFile: function(path) {
            return _resolveUrl(path);
        }
    });
    _exports = _createExports(_module);
    _memory = _findMemory(_module);
    if (!_memory) {
        throw new Error('C++ WASM memory export was not found');
    }
    _ready = true;
    self.postMessage({ type: 'ready' });
}

self.onmessage = function(event) {
    var message = event.data || {};
    Promise.resolve().then(async function() {
        switch (message.type) {
            case 'init':
                await _init(message);
                break;
            case 'loadMovie':
                await _loadMovie(message);
                break;
            case 'play':
                _playing = true;
                _exports.play();
                await _drainFetches(8);
                _postFrame();
                break;
            case 'pause':
                _playing = false;
                _exports.pause();
                break;
            case 'stop':
                _playing = false;
                _exports.stop();
                _postFrame();
                break;
            case 'stepForward':
                _exports.stepForward();
                await _drainFetches(8);
                _postFrame();
                break;
            case 'stepBackward':
                _exports.stepBackward();
                _postFrame();
                break;
            case 'tick':
                await _tick();
                break;
            case 'mouseMove':
                _exports.mouseMove(message.x | 0, message.y | 0);
                break;
            case 'mouseDown':
                _exports.mouseDown(message.x | 0, message.y | 0, message.button | 0);
                break;
            case 'mouseUp':
                _exports.mouseUp(message.x | 0, message.y | 0, message.button | 0);
                break;
            case 'keyDown': {
                var downLength = _writeString(message.key || '');
                _exports.keyDown(message.keyCode | 0, downLength, message.modifiers | 0);
                break;
            }
            case 'keyUp': {
                var upLength = _writeString(message.key || '');
                _exports.keyUp(message.keyCode | 0, upLength, message.modifiers | 0);
                break;
            }
            case 'blur':
                _exports.blur();
                break;
        }
        _postLastError();
    }).catch(function(error) {
        self.postMessage({ type: 'error', message: error && error.message ? error.message : String(error) });
    });
};
