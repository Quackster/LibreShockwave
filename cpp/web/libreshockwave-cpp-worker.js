'use strict';

var _module = null;
var _exports = null;
var _memory = null;
var _basePath = '';
var _ready = false;
var _playing = false;
var _pageProtocol = '';
var _debugLogsEnabled = false;
var _externalParams = {};
var _jpegDecodeQueue = [];
var _jpegDecodeInFlight = {};
var _jpegDecodeSeq = 0;
var _musSockets = {};
var _musConnected = {};
var _musDisconnected = {};
var _musErrors = {};
var _musInbound = {};
var _fetchRelayCounter = 0;
var _fetchRelayMap = {};

var _requiredExports = {
    allocateBuffer: 'allocate_buffer',
    getStringBufferAddress: 'get_string_buffer_address',
    getStringBufferCapacity: 'get_string_buffer_capacity',
    loadMovie: 'load_movie',
    setInitialBuiltinSymbol: 'set_initial_builtin_symbol',
    setMovieProperty: 'set_movie_property',
    readNextGotoNetPage: 'read_next_goto_net_page',
    readNextGotoNetMovie: 'read_next_goto_net_movie',
    preloadCasts: 'preload_casts',
    play: 'play',
    tick: 'tick',
    pause: 'pause',
    stop: 'stop',
    goToFrame: 'go_to_frame',
    stepForward: 'step_forward',
    stepBackward: 'step_backward',
    setScriptTimeoutMs: 'set_script_timeout_ms',
    setDebugPlaybackEnabled: 'set_debug_playback_enabled',
    addTraceHandler: 'add_trace_handler',
    removeTraceHandler: 'remove_trace_handler',
    clearTraceHandlers: 'clear_trace_handlers',
    getStageWidth: 'stage_width',
    getStageHeight: 'stage_height',
    getFrameCount: 'frame_count',
    getCurrentFrame: 'current_frame',
    getTempo: 'tempo',
    render: 'render',
    getRenderBufferAddress: 'get_render_buffer_address',
    getSpriteCount: 'get_sprite_count',
    getCursorType: 'get_cursor_type',
    updateCursorBitmap: 'update_cursor_bitmap',
    getCursorBitmapWidth: 'get_cursor_bitmap_width',
    getCursorBitmapHeight: 'get_cursor_bitmap_height',
    getCursorBitmapLength: 'get_cursor_bitmap_length',
    getCursorBitmapAddress: 'get_cursor_bitmap_address',
    getCursorRegPointX: 'get_cursor_reg_point_x',
    getCursorRegPointY: 'get_cursor_reg_point_y',
    isCaretVisible: 'is_caret_visible',
    getCaretX: 'get_caret_x',
    getCaretY: 'get_caret_y',
    getCaretHeight: 'get_caret_height',
    getSelectionRectCount: 'get_selection_rect_count',
    getSelectionRectX: 'get_selection_rect_x',
    getSelectionRectY: 'get_selection_rect_y',
    getSelectionRectW: 'get_selection_rect_w',
    getSelectionRectH: 'get_selection_rect_h',
    pasteText: 'paste_text',
    getSelectedTextLength: 'get_selected_text_length',
    cutSelectedText: 'cut_selected_text',
    selectAll: 'select_all',
    getDebugLog: 'get_debug_log',
    getCallStack: 'get_call_stack',
    getWindowSpriteDiagnostics: 'get_window_sprite_diagnostics',
    getVisibleTextDiagnostics: 'get_visible_text_diagnostics',
    getBootstrapDiagnostics: 'get_bootstrap_diagnostics',
    triggerTestError: 'trigger_test_error',
    getPendingFetchCount: 'get_pending_fetch_count',
    getPendingFetchTaskId: 'get_pending_fetch_task_id',
    getPendingFetchUrl: 'get_pending_fetch_url',
    getPendingFetchMethod: 'get_pending_fetch_method',
    getPendingFetchPostData: 'get_pending_fetch_post_data',
    getPendingFetchFallbackCount: 'get_pending_fetch_fallback_count',
    getPendingFetchFallbackUrl: 'get_pending_fetch_fallback_url',
    drainPendingFetches: 'drain_pending_fetches',
    allocateNetBuffer: 'allocate_net_buffer',
    deliverFetchResult: 'deliver_fetch_result',
    deliverFetchError: 'deliver_fetch_error',
    getPendingJpegDecodeCount: 'get_pending_jpeg_decode_count',
    getPendingJpegDecodeId: 'get_pending_jpeg_decode_id',
    getPendingJpegDecodeData: 'get_pending_jpeg_decode_data',
    getPendingJpegDecodeDataAddress: 'get_pending_jpeg_decode_data_address',
    deliverJpegDecodeResult: 'deliver_jpeg_decode_result',
    getAudioPendingCount: 'get_audio_pending_count',
    getAudioPendingAction: 'get_audio_pending_action',
    getAudioPendingChannel: 'get_audio_pending_channel',
    getAudioPendingFormat: 'get_audio_pending_format',
    getAudioPendingLoopCount: 'get_audio_pending_loop_count',
    getAudioPendingVolume: 'get_audio_pending_volume',
    getAudioPendingData: 'get_audio_pending_data',
    getAudioBufferAddress: 'get_audio_buffer_address',
    drainAudioPending: 'drain_audio_pending',
    audioNotifyStopped: 'audio_notify_stopped',
    getMusPendingCount: 'get_mus_pending_count',
    getMusPendingType: 'get_mus_pending_type',
    getMusPendingInstanceId: 'get_mus_pending_instance_id',
    getMusPendingHost: 'get_mus_pending_host',
    getMusPendingPort: 'get_mus_pending_port',
    getMusPendingSendData: 'get_mus_pending_send_data',
    drainMusPending: 'drain_mus_pending',
    musDeliverConnected: 'mus_deliver_connected',
    musDeliverDisconnected: 'mus_deliver_disconnected',
    musDeliverError: 'mus_deliver_error',
    musDeliverMessage: 'mus_deliver_message',
    setExternalParam: 'set_external_param',
    clearExternalParams: 'clear_external_params',
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

function _writeBytes(bytes) {
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

function _readExportString(exportFn) {
    var length = exportFn();
    return length > 0 ? _readString(length) : '';
}

function _flushDebugLog() {
    if (!_debugLogsEnabled || !_exports) {
        return '';
    }
    var debugLog = _readExportString(_exports.getDebugLog);
    if (debugLog) {
        self.postMessage({ type: 'debugLog', message: debugLog, msg: debugLog });
    }
    return debugLog;
}

function _postStringDiagnostic(message, type, property, exportFn) {
    var result = { type: type, requestId: message.requestId || 0 };
    result[property] = _readExportString(exportFn);
    self.postMessage(result);
}

function _runtimeDiagnostics() {
    return {
        ready: _ready,
        playing: _playing,
        params: Object.assign({}, _externalParams),
        jpeg: {
            queuedResults: _jpegDecodeQueue.length,
            inFlight: Object.keys(_jpegDecodeInFlight).length
        },
        mus: {
            sockets: Object.keys(_musSockets).length,
            connectedEvents: Object.keys(_musConnected).length,
            disconnectedEvents: Object.keys(_musDisconnected).length,
            errorEvents: Object.keys(_musErrors).length,
            inboundInstances: Object.keys(_musInbound).length
        },
        fetchRelay: {
            pending: Object.keys(_fetchRelayMap).length
        }
    };
}

function _resolveUrl(url) {
    return new URL(url || '', _basePath || self.location.href).href;
}

function _isCrossOrigin(url) {
    try {
        return new URL(url, self.location.href).origin !== self.location.origin;
    } catch (e) {
        return false;
    }
}

function _relayFetch(url, method, postData) {
    return new Promise(function(resolve, reject) {
        var relayId = ++_fetchRelayCounter;
        _fetchRelayMap[relayId] = {
            url: url,
            resolve: resolve,
            reject: reject
        };
        self.postMessage({
            type: 'fetchRelay',
            relayId: relayId,
            url: url,
            method: method || 'GET',
            postData: postData || null
        });
    });
}

function _binaryStringToBytes(value) {
    var bytes = new Uint8Array(value.length);
    for (var i = 0; i < value.length; i++) {
        bytes[i] = value.charCodeAt(i) & 0xff;
    }
    return bytes;
}

function _writeU32BE(bytes, offset, value) {
    bytes[offset] = (value >>> 24) & 0xff;
    bytes[offset + 1] = (value >>> 16) & 0xff;
    bytes[offset + 2] = (value >>> 8) & 0xff;
    bytes[offset + 3] = value & 0xff;
}

function _isImportableImageUrl(url) {
    if (!url) {
        return false;
    }
    var lower = String(url).toLowerCase();
    var query = lower.indexOf('?');
    if (query >= 0) {
        lower = lower.substring(0, query);
    }
    return lower.endsWith('.gif') || lower.endsWith('.png') ||
        lower.endsWith('.jpg') || lower.endsWith('.jpeg');
}

async function _decodeImageForImport(arrayBuffer, url) {
    if (!_isImportableImageUrl(url) ||
            typeof createImageBitmap !== 'function' ||
            typeof OffscreenCanvas === 'undefined') {
        return arrayBuffer;
    }
    try {
        var bitmap = await createImageBitmap(new Blob([arrayBuffer]));
        var canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
        var ctx = canvas.getContext('2d');
        ctx.drawImage(bitmap, 0, 0);
        if (bitmap.close) {
            bitmap.close();
        }
        var rgba = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        var out = new Uint8Array(12 + rgba.length);
        out[0] = 0x4c;
        out[1] = 0x53;
        out[2] = 0x57;
        out[3] = 0x49;
        _writeU32BE(out, 4, canvas.width);
        _writeU32BE(out, 8, canvas.height);
        out.set(rgba, 12);
        return out.buffer;
    } catch (e) {
        return arrayBuffer;
    }
}

async function _decodeImageToRgba(arrayBuffer) {
    if (typeof createImageBitmap !== 'function' || typeof OffscreenCanvas === 'undefined') {
        return null;
    }
    var bitmap = await createImageBitmap(new Blob([arrayBuffer], { type: 'image/jpeg' }));
    var canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
    var ctx = canvas.getContext('2d');
    ctx.drawImage(bitmap, 0, 0);
    if (bitmap.close) {
        bitmap.close();
    }
    var rgba = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
    var copy = new Uint8Array(rgba.length);
    copy.set(rgba);
    return { width: canvas.width, height: canvas.height, data: copy };
}

async function _fetchFirst(urls, method, postData) {
    var lastStatus = 0;
    for (var i = 0; i < urls.length; i++) {
        var url = _resolveUrl(urls[i]);
        try {
            var data;
            if (_isCrossOrigin(url)) {
                data = await _relayFetch(url, method, postData);
            } else {
                var options = {};
                if (method === 'POST') {
                    options.method = 'POST';
                    options.body = postData || null;
                    options.headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
                }
                var response = await fetch(url, options);
                lastStatus = response.status || 0;
                if (!response.ok) {
                    continue;
                }
                data = await response.arrayBuffer();
            }
            return { data: await _decodeImageForImport(data, url), url: url };
        } catch (e) {
            lastStatus = e && e.status ? e.status : 0;
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
            var method = _exports.getPendingFetchMethod(i) === 1 ? 'POST' : 'GET';
            var postData = '';
            if (method === 'POST') {
                postData = _readString(_exports.getPendingFetchPostData(i));
            }
            var fallbackCount = _exports.getPendingFetchFallbackCount(i);
            for (var f = 0; f < fallbackCount; f++) {
                urls.push(_readString(_exports.getPendingFetchFallbackUrl(i, f)));
            }
            requests.push({ taskId: taskId, urls: urls, method: method, postData: postData });
        }
        _exports.drainPendingFetches();
        for (var r = 0; r < requests.length; r++) {
            var result = await _fetchFirst(requests[r].urls, requests[r].method, requests[r].postData);
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

function _deliverJpegDecodeResults() {
    var delivered = 0;
    while (_jpegDecodeQueue.length > 0) {
        var item = _jpegDecodeQueue.shift();
        if (item.data.length > 0) {
            var addr = _exports.allocateNetBuffer(item.data.length);
            new Uint8Array(_mem(), addr, item.data.length).set(item.data);
        }
        _exports.deliverJpegDecodeResult(item.id, item.width, item.height, item.data.length);
        delivered++;
    }
    return delivered;
}

function _pumpJpegDecodeRequests() {
    var count = _exports.getPendingJpegDecodeCount();
    if (count <= 0) {
        return 0;
    }
    var seq = _jpegDecodeSeq;
    var started = 0;
    for (var i = 0; i < count; i++) {
        var id = _exports.getPendingJpegDecodeId(i);
        if (!id || _jpegDecodeInFlight[id]) {
            continue;
        }
        var length = _exports.getPendingJpegDecodeData(id);
        var addr = _exports.getPendingJpegDecodeDataAddress();
        if (length <= 0 || addr === 0) {
            continue;
        }
        var bytes = new Uint8Array(length);
        bytes.set(new Uint8Array(_mem(), addr, length));
        _jpegDecodeInFlight[id] = true;
        started++;
        (function(decodeId, data) {
            _decodeImageToRgba(data.buffer).then(function(decoded) {
                if (seq !== _jpegDecodeSeq) {
                    return;
                }
                if (decoded) {
                    _jpegDecodeQueue.push({
                        id: decodeId,
                        width: decoded.width,
                        height: decoded.height,
                        data: decoded.data
                    });
                } else {
                    _jpegDecodeQueue.push({ id: decodeId, width: 0, height: 0, data: new Uint8Array(0) });
                }
            }).catch(function() {
                if (seq !== _jpegDecodeSeq) {
                    return;
                }
                _jpegDecodeQueue.push({ id: decodeId, width: 0, height: 0, data: new Uint8Array(0) });
            }).finally(function() {
                if (seq === _jpegDecodeSeq) {
                    delete _jpegDecodeInFlight[decodeId];
                }
            });
        })(id, bytes);
    }
    return started;
}

function _pumpAudioCommands() {
    var count = _exports.getAudioPendingCount();
    if (count <= 0) {
        return 0;
    }
    for (var i = 0; i < count; i++) {
        var action = _readString(_exports.getAudioPendingAction(i));
        var channel = _exports.getAudioPendingChannel(i);
        if (action === 'play') {
            var format = _readString(_exports.getAudioPendingFormat(i));
            var loopCount = _exports.getAudioPendingLoopCount(i);
            var volume = _exports.getAudioPendingVolume(i);
            var dataLen = _exports.getAudioPendingData(i);
            if (dataLen > 0) {
                var dataAddr = _exports.getAudioBufferAddress();
                var audioData = new Uint8Array(dataLen);
                audioData.set(new Uint8Array(_mem(), dataAddr, dataLen));
                self.postMessage({
                    type: 'audio',
                    action: 'play',
                    channel: channel,
                    format: format,
                    loopCount: loopCount,
                    volume: volume,
                    data: audioData.buffer
                }, [audioData.buffer]);
            }
        } else if (action === 'stop') {
            self.postMessage({ type: 'audio', action: 'stop', channel: channel });
        } else if (action === 'volume') {
            self.postMessage({
                type: 'audio',
                action: 'volume',
                channel: channel,
                volume: _exports.getAudioPendingVolume(i)
            });
        }
    }
    _exports.drainAudioPending();
    return count;
}

function _buildMusWebSocketUrl(host, port) {
    var forcedMode = String(_externalParams['websocket.mode'] || '').toLowerCase();
    var secure = forcedMode === 'wss' || (!forcedMode && (_pageProtocol === 'https:' || String(port) === '443'));
    if (forcedMode === 'ws') {
        secure = false;
    }
    return (secure ? 'wss' : 'ws') + '://' + host + ':' + port;
}

function _musConnect(instanceId, url) {
    if (_musSockets[instanceId]) {
        _musSockets[instanceId].onclose = null;
        _musSockets[instanceId].close();
    }
    var socket;
    try {
        socket = new WebSocket(url);
    } catch (e) {
        _musErrors[instanceId] = -3;
        return;
    }
    socket.binaryType = 'arraybuffer';
    _musSockets[instanceId] = socket;
    socket.onopen = function() {
        _musConnected[instanceId] = true;
    };
    socket.onmessage = function(event) {
        if (!_musInbound[instanceId]) {
            _musInbound[instanceId] = [];
        }
        _musInbound[instanceId].push(typeof event.data === 'string'
            ? _binaryStringToBytes(event.data)
            : new Uint8Array(event.data));
    };
    socket.onerror = function() {
        _musErrors[instanceId] = -2;
    };
    socket.onclose = function() {
        _musDisconnected[instanceId] = true;
        delete _musSockets[instanceId];
    };
}

function _deliverMusEvents() {
    for (var id in _musConnected) {
        _exports.musDeliverConnected(parseInt(id, 10));
    }
    _musConnected = {};

    for (var errorId in _musErrors) {
        _exports.musDeliverError(parseInt(errorId, 10), _musErrors[errorId]);
    }
    _musErrors = {};

    for (var inboundId in _musInbound) {
        var instanceId = parseInt(inboundId, 10);
        var messages = _musInbound[inboundId];
        for (var i = 0; i < messages.length; i++) {
            var length = _writeBytes(messages[i]);
            _exports.musDeliverMessage(instanceId, length);
        }
    }
    _musInbound = {};

    for (var disconnectedId in _musDisconnected) {
        _exports.musDeliverDisconnected(parseInt(disconnectedId, 10));
    }
    _musDisconnected = {};
}

function _pumpMusRequests() {
    var count = _exports.getMusPendingCount();
    if (count <= 0) {
        return 0;
    }
    for (var i = 0; i < count; i++) {
        var type = _exports.getMusPendingType(i);
        var instanceId = _exports.getMusPendingInstanceId(i);
        if (type === 0) {
            var host = _readString(_exports.getMusPendingHost(i));
            var port = _exports.getMusPendingPort(i);
            _musConnect(instanceId, _buildMusWebSocketUrl(host, port));
        } else if (type === 1) {
            var dataLen = _exports.getMusPendingSendData(i);
            var data = new Uint8Array(_stringBufferView().subarray(0, dataLen));
            var socket = _musSockets[instanceId];
            if (socket && socket.readyState === WebSocket.OPEN) {
                socket.send(data.buffer);
            }
        } else if (type === 2) {
            var existing = _musSockets[instanceId];
            if (existing) {
                existing.onclose = null;
                existing.close();
                delete _musSockets[instanceId];
            }
        }
    }
    _exports.drainMusPending();
    return count;
}

function _drainNavigation() {
    while (true) {
        var packedPage = _exports.readNextGotoNetPage();
        if (!packedPage) {
            break;
        }
        var urlLen = (packedPage >>> 16) & 0xffff;
        var targetLen = packedPage & 0xffff;
        var buffer = _stringBufferView();
        var url = new TextDecoder().decode(buffer.subarray(0, urlLen));
        var target = new TextDecoder().decode(buffer.subarray(urlLen, urlLen + targetLen));
        self.postMessage({ type: 'gotoNetPage', url: url, target: target });
    }
    while (true) {
        var movieLen = _exports.readNextGotoNetMovie();
        if (!movieLen) {
            break;
        }
        self.postMessage({ type: 'gotoNetMovie', url: _readString(movieLen) });
    }
}

function _writeKeyValue(key, value) {
    var keyBytes = new TextEncoder().encode(key || '');
    var valueBytes = new TextEncoder().encode(value == null ? '' : String(value));
    var buffer = _stringBufferView();
    var keyLength = Math.min(keyBytes.length, buffer.length);
    buffer.set(keyBytes.subarray(0, keyLength), 0);
    var valueLength = Math.min(valueBytes.length, Math.max(0, buffer.length - keyLength));
    buffer.set(valueBytes.subarray(0, valueLength), keyLength);
    return { keyLength: keyLength, valueLength: valueLength };
}

function _setExternalParam(key, value) {
    _externalParams[key] = value == null ? '' : String(value);
    var lengths = _writeKeyValue(key, value);
    _exports.setExternalParam(lengths.keyLength, lengths.valueLength);
}

function _setMovieProperty(key, value) {
    var lengths = _writeKeyValue(key, value);
    _exports.setMovieProperty(lengths.keyLength, lengths.valueLength);
}

function _setInitialBuiltinSymbol(key, value) {
    var lengths = _writeKeyValue(key, value);
    _exports.setInitialBuiltinSymbol(lengths.keyLength, lengths.valueLength);
}

async function _driveHostQueues(fetchRounds) {
    _deliverMusEvents();
    _deliverJpegDecodeResults();
    await _drainFetches(fetchRounds);
    _pumpMusRequests();
    _pumpAudioCommands();
    _pumpJpegDecodeRequests();
    _drainNavigation();
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

function _collectOverlayState() {
    var cursorBitmap = null;
    if (_exports.updateCursorBitmap()) {
        var cursorLength = _exports.getCursorBitmapLength();
        var cursorAddress = _exports.getCursorBitmapAddress();
        if (cursorLength > 0 && cursorAddress !== 0) {
            var cursorRgba = new Uint8ClampedArray(cursorLength);
            cursorRgba.set(new Uint8ClampedArray(_mem(), cursorAddress, cursorLength));
            cursorBitmap = {
                rgba: cursorRgba,
                w: _exports.getCursorBitmapWidth(),
                h: _exports.getCursorBitmapHeight(),
                regX: _exports.getCursorRegPointX(),
                regY: _exports.getCursorRegPointY()
            };
        }
    }

    var caretInfo = null;
    if (_exports.isCaretVisible()) {
        caretInfo = {
            x: _exports.getCaretX(),
            y: _exports.getCaretY(),
            h: _exports.getCaretHeight()
        };
    }

    var selectionRects = null;
    var selectionCount = _exports.getSelectionRectCount();
    if (selectionCount > 0) {
        selectionRects = [];
        for (var i = 0; i < selectionCount; i++) {
            selectionRects.push({
                x: _exports.getSelectionRectX(i),
                y: _exports.getSelectionRectY(i),
                w: _exports.getSelectionRectW(i),
                h: _exports.getSelectionRectH(i)
            });
        }
    }

    return {
        cursorType: _exports.getCursorType(),
        cursorBitmap: cursorBitmap,
        caretInfo: caretInfo,
        selectionRects: selectionRects
    };
}

function _postFrame() {
    var frame = _renderFrame();
    if (!frame) {
        return;
    }
    var overlay = _collectOverlayState();
    var transfer = [frame.rgba.buffer];
    if (overlay.cursorBitmap) {
        transfer.push(overlay.cursorBitmap.rgba.buffer);
    }
    self.postMessage({
        type: 'frame',
        width: frame.width,
        height: frame.height,
        frame: frame.frame,
        frameCount: frame.frameCount,
        tempo: frame.tempo,
        spriteCount: frame.spriteCount,
        rgba: frame.rgba,
        cursorType: overlay.cursorType,
        cursorBitmap: overlay.cursorBitmap,
        caretInfo: overlay.caretInfo,
        selectionRects: overlay.selectionRects
    }, transfer);
}

async function _loadMovie(message) {
    _jpegDecodeSeq++;
    _jpegDecodeQueue = [];
    _jpegDecodeInFlight = {};
    var movieBytes = new Uint8Array(message.data || new ArrayBuffer(0));
    var basePathLength = _writeString(message.basePath || _basePath);
    var movieAddr = _exports.allocateBuffer(movieBytes.length);
    new Uint8Array(_mem(), movieAddr, movieBytes.length).set(movieBytes);
    var packedStage = _exports.loadMovie(movieBytes.length, basePathLength);
    if (message.scriptTimeoutMs != null) {
        _exports.setScriptTimeoutMs(message.scriptTimeoutMs | 0);
    }
    if (message.debugPlayback != null) {
        _exports.setDebugPlaybackEnabled(message.debugPlayback ? 1 : 0);
    }
    if (message.traceHandlers) {
        for (var traceIndex = 0; traceIndex < message.traceHandlers.length; traceIndex++) {
            _exports.addTraceHandler(_writeString(message.traceHandlers[traceIndex]));
        }
    }
    var key;
    if (message.params) {
        for (key in message.params) {
            _setExternalParam(key, message.params[key]);
        }
    }
    for (key in _externalParams) {
        _setExternalParam(key, _externalParams[key]);
    }
    if (message.movieProperties) {
        for (key in message.movieProperties) {
            _setMovieProperty(key, message.movieProperties[key]);
        }
    }
    if (message.initialBuiltinSymbols) {
        for (key in message.initialBuiltinSymbols) {
            _setInitialBuiltinSymbol(key, message.initialBuiltinSymbols[key]);
        }
    }
    await _driveHostQueues(32);
    _exports.preloadCasts();
    await _driveHostQueues(32);
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
        await _driveHostQueues(32);
        _postFrame();
    }
}

async function _tick() {
    if (!_ready) {
        return;
    }
    await _driveHostQueues(8);
    if (_playing) {
        _exports.tick();
        await _driveHostQueues(8);
    }
    _postLastError();
    _flushDebugLog();
    _postFrame();
}

async function _init(message) {
    _basePath = message.basePath || '';
    _pageProtocol = message.pageProtocol || '';
    _debugLogsEnabled = !!message.debugLogsEnabled;
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
    if (message.debugPlayback != null) {
        _exports.setDebugPlaybackEnabled(message.debugPlayback ? 1 : 0);
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
            case 'fetchRelayResult': {
                var relay = _fetchRelayMap[message.relayId];
                if (!relay) {
                    break;
                }
                delete _fetchRelayMap[message.relayId];
                if (message.error) {
                    relay.reject({ status: message.status || 0 });
                } else {
                    relay.resolve(message.data);
                }
                break;
            }
            case 'play':
                _playing = true;
                _exports.play();
                await _driveHostQueues(8);
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
            case 'goToFrame':
                _exports.goToFrame(message.frame | 0);
                await _driveHostQueues(8);
                _postFrame();
                break;
            case 'stepForward':
                _exports.stepForward();
                await _driveHostQueues(8);
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
            case 'paste': {
                var pasteLength = _writeString(message.text || '');
                _exports.pasteText(pasteLength);
                await _driveHostQueues(4);
                _postFrame();
                break;
            }
            case 'getSelectedText': {
                var selectedLength = _exports.getSelectedTextLength();
                self.postMessage({ type: 'selectedText', text: _readString(selectedLength) });
                break;
            }
            case 'cutSelectedText': {
                var cutLength = _exports.cutSelectedText();
                self.postMessage({ type: 'cutText', text: _readString(cutLength) });
                _postFrame();
                break;
            }
            case 'selectAll':
                _exports.selectAll();
                _postFrame();
                break;
            case 'blur':
                _exports.blur();
                break;
            case 'setParam':
                _setExternalParam(message.key || '', message.value == null ? '' : String(message.value));
                break;
            case 'clearParams':
                _externalParams = {};
                _exports.clearExternalParams();
                break;
            case 'setMovieProperty':
                _setMovieProperty(message.key || '', message.value == null ? '' : String(message.value));
                break;
            case 'setInitialBuiltinSymbol':
                _setInitialBuiltinSymbol(message.key || '', message.value == null ? '' : String(message.value));
                break;
            case 'audioStopped':
                _exports.audioNotifyStopped(message.channel | 0);
                break;
            case 'setDebugLogsEnabled':
                _debugLogsEnabled = !!message.enabled;
                break;
            case 'setDebugPlaybackEnabled':
                _exports.setDebugPlaybackEnabled(message.enabled ? 1 : 0);
                break;
            case 'addTraceHandler':
                _exports.addTraceHandler(_writeString(message.name || ''));
                break;
            case 'removeTraceHandler':
                _exports.removeTraceHandler(_writeString(message.name || ''));
                break;
            case 'clearTraceHandlers':
                _exports.clearTraceHandlers();
                break;
            case 'getCallStack':
                _postStringDiagnostic(message, 'callStack', 'callStack', _exports.getCallStack);
                break;
            case 'getWindowSpriteDiagnostics':
                _postStringDiagnostic(message,
                                      'windowSpriteDiagnostics',
                                      'diagnostics',
                                      _exports.getWindowSpriteDiagnostics);
                break;
            case 'getVisibleTextDiagnostics':
                _postStringDiagnostic(message,
                                      'visibleTextDiagnostics',
                                      'diagnostics',
                                      _exports.getVisibleTextDiagnostics);
                break;
            case 'getBootstrapDiagnostics':
                _postStringDiagnostic(message,
                                      'bootstrapDiagnostics',
                                      'diagnostics',
                                      _exports.getBootstrapDiagnostics);
                break;
            case 'getRuntimeDiagnostics':
                self.postMessage({
                    type: 'runtimeDiagnostics',
                    requestId: message.requestId || 0,
                    diagnostics: _runtimeDiagnostics()
                });
                break;
            case 'triggerTestError': {
                var handled = _exports.triggerTestError();
                _flushDebugLog();
                _postFrame();
                self.postMessage({
                    type: 'testError',
                    requestId: message.requestId || 0,
                    handled: handled !== 0
                });
                break;
            }
        }
        _postLastError();
        _flushDebugLog();
    }).catch(function(error) {
        self.postMessage({ type: 'error', message: error && error.message ? error.message : String(error) });
    });
};
