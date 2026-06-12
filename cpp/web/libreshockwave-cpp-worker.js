'use strict';

var _module = null;
var _exports = null;
var _memory = null;
var _capturedWasmMemory = null;
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
var _musCloseDetails = {};
var _musHistory = [];
var _fetchRelayCounter = 0;
var _fetchRelayMap = {};
var _fetchResultCache = {};
var _fetchInflight = {};
var _fetchDiagnostics = {
    active: null,
    attempts: 0,
    delivered: 0,
    failed: 0,
    lastStatus: 0,
    lastUrl: '',
    deliveredTaskIds: [],
    failedTaskIds: []
};
var _sharedFrameBytes = null;
var _sharedFrameControl = null;
var _sharedFrameCapacity = 0;
var _sharedFrameSeq = 0;
var _activeLoadSeq = 0;
var _loadInProgress = false;
var _tickInProgress = false;
var _tickTimer = null;
var _tickIntervalMs = 33;
var _nextTickAt = 0;
var _diagnosticDepth = 0;
var _lastNativeOperation = null;

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
    getScriptDiagnostics: 'get_script_diagnostics',
    getNetDebugStatus: 'get_net_debug_status',
    netDone: 'net_done',
    netError: 'net_error',
    netTaskCount: 'net_task_count',
    netLastTaskId: 'net_last_task_id',
    netPendingRequestCount: 'net_pending_request_count',
    netPendingMovieNavigationTaskCount: 'net_pending_movie_navigation_task_count',
    netLatestTaskDone: 'net_latest_task_done',
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
    debugMusRequestConnect: 'debug_mus_request_connect',
    debugMusRequestSendText: 'debug_mus_request_send_text',
    debugMusRequestDisconnect: 'debug_mus_request_disconnect',
    debugMusIsConnected: 'debug_mus_is_connected',
    debugMusDestroyInstance: 'debug_mus_destroy_instance',
    debugMusPollMessageCount: 'debug_mus_poll_message_count',
    debugMusMessageError: 'debug_mus_message_error',
    debugMusMessageSender: 'debug_mus_message_sender',
    debugMusMessageSubject: 'debug_mus_message_subject',
    debugMusMessageContent: 'debug_mus_message_content',
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
    function rootProperty(value, name) {
        if (!value || (typeof value !== 'object' && typeof value !== 'function')) {
            return null;
        }
        var descriptor = Object.getOwnPropertyDescriptor(value, name);
        if (!descriptor || !Object.prototype.hasOwnProperty.call(descriptor, 'value')) {
            return null;
        }
        return descriptor.value;
    }
    add(source);
    add(rootProperty(source, 'exports'));
    add(rootProperty(source, 'asm'));
    add(rootProperty(source, 'wasmExports'));
    add(rootProperty(rootProperty(source, 'instance'), 'exports'));
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

function _findExportedMemory(exports) {
    if (!exports) {
        return null;
    }
    for (var name in exports) {
        var value = exports[name];
        if (value && value.buffer && value.buffer instanceof ArrayBuffer) {
            return value;
        }
    }
    return null;
}

function _findMemory(source) {
    if (_capturedWasmMemory && _capturedWasmMemory.buffer) {
        return _capturedWasmMemory;
    }
    var roots = _exportRoots(source);
    for (var r = 0; r < roots.length; r++) {
        if (roots[r].memory && roots[r].memory.buffer) {
            return roots[r].memory;
        }
        if (roots[r].wasmMemory && roots[r].wasmMemory.buffer) {
            return roots[r].wasmMemory;
        }
        if (roots[r].HEAPU8 && roots[r].HEAPU8.buffer) {
            return {
                get buffer() {
                    return roots[r].HEAPU8.buffer;
                }
            };
        }
    }
    return null;
}

function _loadWasmBinary(path) {
    var request = new XMLHttpRequest();
    request.open('GET', _resolveUrl(path), false);
    request.responseType = 'arraybuffer';
    request.send(null);
    if (request.status < 200 || request.status >= 300) {
        throw new Error('Failed to load C++ WASM binary: ' + path + ' status=' + request.status);
    }
    return new Uint8Array(request.response);
}

function _instantiateWasm(imports, receiveInstance) {
    var module = new WebAssembly.Module(_loadWasmBinary('libreshockwave-cpp-wasm.wasm'));
    var instance = new WebAssembly.Instance(module, imports);
    _capturedWasmMemory = _findExportedMemory(instance.exports);
    receiveInstance(instance, module);
    return instance.exports;
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

function _writeStringSegments(values) {
    var encoder = new TextEncoder();
    var encoded = [];
    var total = 0;
    for (var i = 0; i < values.length; i++) {
        var bytes = encoder.encode(values[i] == null ? '' : String(values[i]));
        encoded.push(bytes);
        total += bytes.length;
    }
    var buffer = _stringBufferView();
    if (total > buffer.length) {
        throw new Error('C++ WASM string buffer too small for Multiuser diagnostic payload');
    }
    var offset = 0;
    var lengths = [];
    for (var j = 0; j < encoded.length; j++) {
        buffer.set(encoded[j], offset);
        lengths.push(encoded[j].length);
        offset += encoded[j].length;
    }
    return lengths;
}

function _hexPrefix(bytes, maxLength) {
    var parts = [];
    var count = Math.min(bytes ? bytes.length : 0, maxLength);
    for (var i = 0; i < count; i++) {
        parts.push(bytes[i].toString(16).padStart(2, '0'));
    }
    return parts.join(' ');
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
        loadSeq: _activeLoadSeq,
        scheduler: {
            tickIntervalMs: _tickIntervalMs,
            timerActive: _tickTimer !== null,
            tickInProgress: _tickInProgress,
            nextTickInMs: _nextTickAt > 0 ? Math.max(0, Math.round(_nextTickAt - _nowMs())) : 0
        },
        params: Object.assign({}, _externalParams),
        jpeg: {
            queuedResults: _jpegDecodeQueue.length,
            inFlight: Object.keys(_jpegDecodeInFlight).length
        },
        mus: {
            sockets: Object.keys(_musSockets).length,
            socketStates: _musSocketStates(),
            connectedEvents: Object.keys(_musConnected).length,
            disconnectedEvents: Object.keys(_musDisconnected).length,
            errorEvents: Object.keys(_musErrors).length,
            inboundInstances: Object.keys(_musInbound).length,
            pendingCount: _exports ? _exports.getMusPendingCount() : 0,
            pending: _snapshotPendingMusRequests(8),
            closeDetails: Object.assign({}, _musCloseDetails),
            history: _musHistory.slice(-20)
        },
        fetchRelay: {
            pending: Object.keys(_fetchRelayMap).length
        },
        fetch: Object.assign({}, _fetchDiagnostics),
        cxxPendingFetches: _exports ? _exports.getPendingFetchCount() : 0,
        net: {
            done: _exports ? _exports.netDone() !== 0 : false,
            error: _exports ? _exports.netError() : 0,
            taskCount: _exports ? _exports.netTaskCount() : 0,
            lastTaskId: _exports ? _exports.netLastTaskId() : 0,
            pendingRequests: _exports ? _exports.netPendingRequestCount() : 0,
            pendingMovieNavigationTasks: _exports ? _exports.netPendingMovieNavigationTaskCount() : 0,
            latestTaskDone: _exports ? _exports.netLatestTaskDone() !== 0 : false,
            debugStatus: _exports ? _readExportString(_exports.getNetDebugStatus) : ''
        },
        sharedFrame: {
            enabled: !!_sharedFrameBytes,
            capacity: _sharedFrameCapacity,
            sequence: _sharedFrameSeq
        }
    };
}

function _recordMusEvent(event) {
    var entry = Object.assign({
        t: Math.round((typeof performance !== 'undefined' ? performance.now() : Date.now()) / 100) / 10
    }, event || {});
    _musHistory.push(entry);
    if (_musHistory.length > 50) {
        _musHistory.splice(0, _musHistory.length - 50);
    }
    if (_debugLogsEnabled) {
        self.postMessage({
            type: 'debugLog',
            message: '[MUS] ' + JSON.stringify(entry),
            msg: '[MUS] ' + JSON.stringify(entry)
        });
    }
}

function _musSocketStates() {
    var states = {};
    for (var id in _musSockets) {
        var socket = _musSockets[id];
        states[id] = socket ? socket.readyState : -1;
    }
    return states;
}

function _snapshotPendingMusRequests(limit) {
    if (!_exports) {
        return [];
    }
    var count = _exports.getMusPendingCount();
    var requests = [];
    var capped = Math.min(count, limit || count);
    for (var i = 0; i < capped; i++) {
        var type = _exports.getMusPendingType(i);
        var request = {
            index: i,
            type: type,
            instanceId: _exports.getMusPendingInstanceId(i)
        };
        if (type === 0) {
            request.host = _readString(_exports.getMusPendingHost(i));
            request.port = _exports.getMusPendingPort(i);
        } else if (type === 1) {
            var dataLength = _exports.getMusPendingSendData(i);
            request.dataLength = dataLength;
            request.dataHexPrefix = dataLength > 0
                ? _hexPrefix(_stringBufferView().subarray(0, dataLength), 24)
                : '';
        }
        requests.push(request);
    }
    return requests;
}

function _snapshotNativeOperation(label) {
    if (!_exports) {
        _lastNativeOperation = { label: label, exportsReady: false };
        return;
    }
    var snapshot = {
        label: label,
        exportsReady: true,
        playing: _playing,
        loadSeq: _activeLoadSeq
    };
    try {
        snapshot.frame = _exports.getCurrentFrame();
        snapshot.frameCount = _exports.getFrameCount();
        snapshot.tempo = _exports.getTempo();
        snapshot.spriteCount = _exports.getSpriteCount();
        snapshot.pendingFetches = _exports.getPendingFetchCount();
        snapshot.netDone = _exports.netDone() !== 0;
        snapshot.netError = _exports.netError();
        snapshot.netTaskCount = _exports.netTaskCount();
        snapshot.netLastTaskId = _exports.netLastTaskId();
        snapshot.netPendingRequests = _exports.netPendingRequestCount();
        snapshot.netPendingMovieNavigationTasks = _exports.netPendingMovieNavigationTaskCount();
        snapshot.netLatestTaskDone = _exports.netLatestTaskDone() !== 0;
    } catch (e) {
        snapshot.snapshotError = e && (e.stack || e.message) ? (e.stack || e.message) : String(e);
    }
    _lastNativeOperation = snapshot;
}

function _formatCaughtError(error) {
    if (error && (error.stack || error.message)) {
        return (error.stack || error.message) + '\nlastNativeOperation=' + JSON.stringify(_lastNativeOperation);
    }
    if (error && typeof error === 'object') {
        var fields = {};
        try {
            Object.getOwnPropertyNames(error).forEach(function(name) {
                fields[name] = error[name];
            });
        } catch (e) {
            fields.inspectError = String(e);
        }
        return JSON.stringify({
            value: String(error),
            name: error.name || '',
            constructor: error.constructor && error.constructor.name ? error.constructor.name : '',
            fields: fields,
            lastNativeOperation: _lastNativeOperation
        });
    }
    return String(error);
}

function _isStaleLoad(loadSeq) {
    return !!loadSeq && loadSeq !== _activeLoadSeq;
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

function _bytesToText(bytes) {
    try {
        return new TextDecoder().decode(bytes || new Uint8Array(0));
    } catch (e) {
        return '';
    }
}

function _hasQueuedMusEvent(map, instanceId) {
    return Object.prototype.hasOwnProperty.call(map, String(instanceId));
}

function _takeQueuedMusEvent(map, instanceId) {
    var key = String(instanceId);
    if (!Object.prototype.hasOwnProperty.call(map, key)) {
        return null;
    }
    var value = map[key];
    delete map[key];
    return value;
}

function _clearMusInstance(instanceId) {
    var socket = _musSockets[instanceId];
    if (socket) {
        socket.onopen = null;
        socket.onmessage = null;
        socket.onerror = null;
        socket.onclose = null;
        try {
            socket.close();
        } catch (e) {}
    }
    delete _musSockets[instanceId];
    delete _musConnected[instanceId];
    delete _musDisconnected[instanceId];
    delete _musErrors[instanceId];
    delete _musInbound[instanceId];
    delete _musCloseDetails[instanceId];
}

function _waitForMusState(instanceId, predicate, timeoutMs) {
    var deadline = Date.now() + Math.max(1, timeoutMs | 0);
    return new Promise(function(resolve) {
        function poll() {
            var state = predicate();
            if (state) {
                resolve(state);
                return;
            }
            if (Date.now() >= deadline) {
                resolve(null);
                return;
            }
            setTimeout(poll, 10);
        }
        poll();
    });
}

function _diagnosticActive() {
    return _diagnosticDepth > 0;
}

async function _runExclusiveDiagnostic(callback) {
    _diagnosticDepth++;
    try {
        return await callback();
    } finally {
        _diagnosticDepth--;
    }
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
        _fetchDiagnostics.active = url;
        _fetchDiagnostics.lastUrl = url;
        _fetchDiagnostics.attempts++;
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
            _fetchDiagnostics.delivered++;
            _fetchDiagnostics.active = null;
            _fetchDiagnostics.lastStatus = lastStatus || 200;
            return { data: await _decodeImageForImport(data, url), url: url };
        } catch (e) {
            lastStatus = e && e.status ? e.status : 0;
            _fetchDiagnostics.lastStatus = lastStatus;
        }
    }
    _fetchDiagnostics.failed++;
    _fetchDiagnostics.active = null;
    return { error: true, status: lastStatus || 404 };
}

function _fetchRequestKey(request) {
    if (!request || request.method !== 'GET') {
        return '';
    }
    var resolvedUrls = [];
    for (var i = 0; i < request.urls.length; i++) {
        resolvedUrls.push(_resolveUrl(request.urls[i]));
    }
    return [request.method, request.postData || '', resolvedUrls.join('\n')].join('\u0000');
}

async function _fetchGroupedRequests(requests, loadSeq) {
    var groups = [];
    var groupByKey = {};
    for (var i = 0; i < requests.length; i++) {
        var request = requests[i];
        var key = _fetchRequestKey(request);
        if (key && groupByKey[key]) {
            groupByKey[key].requests.push(request);
            continue;
        }
        var group = {
            key: key,
            prototype: request,
            requests: [request]
        };
        groups.push(group);
        if (key) {
            groupByKey[key] = group;
        }
    }

    await Promise.all(groups.map(async function(group) {
        if (_isStaleLoad(loadSeq)) {
            group.result = { error: true, status: 0 };
            return;
        }
        if (group.key && _fetchResultCache[group.key]) {
            group.result = _fetchResultCache[group.key];
            return;
        }
        if (group.key && _fetchInflight[group.key]) {
            group.result = await _fetchInflight[group.key];
            return;
        }
        var request = group.prototype;
        var promise = _fetchFirst(request.urls, request.method, request.postData);
        if (group.key) {
            _fetchInflight[group.key] = promise;
        }
        try {
            group.result = await promise;
            if (group.key && group.result && group.result.data) {
                _fetchResultCache[group.key] = group.result;
            }
        } finally {
            if (group.key) {
                delete _fetchInflight[group.key];
            }
        }
    }));

    return groups;
}

async function _drainFetches(maxRounds, loadSeq) {
    var delivered = 0;
    var failed = 0;
    for (var round = 0; round < maxRounds; round++) {
        if (_isStaleLoad(loadSeq)) {
            break;
        }
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
        var groups = await _fetchGroupedRequests(requests, loadSeq);
        for (var g = 0; g < groups.length; g++) {
            if (_isStaleLoad(loadSeq)) {
                return { delivered: delivered, failed: failed };
            }
            var result = groups[g].result || { error: true, status: 0 };
            for (var r = 0; r < groups[g].requests.length; r++) {
                var request = groups[g].requests[r];
                if (result.error) {
                    _exports.deliverFetchError(request.taskId, result.status);
                    _fetchDiagnostics.failedTaskIds.push(request.taskId);
                    failed++;
                    continue;
                }
                var bytes = new Uint8Array(result.data);
                var addr = _exports.allocateNetBuffer(bytes.length);
                new Uint8Array(_mem(), addr, bytes.length).set(bytes);
                _exports.deliverFetchResult(request.taskId, bytes.length);
                _fetchDiagnostics.deliveredTaskIds.push(request.taskId);
                delivered++;
            }
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
    var count;
    try {
        count = _exports.getPendingJpegDecodeCount();
    } catch (e) {
        throw new Error('getPendingJpegDecodeCount failed: ' + (e && (e.stack || e.message) ? (e.stack || e.message) : String(e)));
    }
    if (count <= 0) {
        return 0;
    }
    var seq = _jpegDecodeSeq;
    var started = 0;
    for (var i = 0; i < count; i++) {
        var id;
        try {
            id = _exports.getPendingJpegDecodeId(i);
        } catch (e) {
            throw new Error('getPendingJpegDecodeId failed index=' + i + ': ' + (e && (e.stack || e.message) ? (e.stack || e.message) : String(e)));
        }
        if (!id || _jpegDecodeInFlight[id]) {
            continue;
        }
        var length;
        var addr;
        try {
            length = _exports.getPendingJpegDecodeData(id);
        } catch (e) {
            throw new Error('getPendingJpegDecodeData failed id=' + id + ': ' + (e && (e.stack || e.message) ? (e.stack || e.message) : String(e)));
        }
        try {
            addr = _exports.getPendingJpegDecodeDataAddress();
        } catch (e) {
            throw new Error('getPendingJpegDecodeDataAddress failed id=' + id + ': ' + (e && (e.stack || e.message) ? (e.stack || e.message) : String(e)));
        }
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
        _recordMusEvent({ type: 'connect', instanceId: instanceId, url: url });
        socket = new WebSocket(url);
    } catch (e) {
        _musErrors[instanceId] = -3;
        _recordMusEvent({
            type: 'connectException',
            instanceId: instanceId,
            url: url,
            message: e && e.message ? e.message : String(e)
        });
        return;
    }
    socket.binaryType = 'arraybuffer';
    _musSockets[instanceId] = socket;
    socket.onopen = function() {
        _musConnected[instanceId] = true;
        _recordMusEvent({ type: 'open', instanceId: instanceId, url: url });
    };
    socket.onmessage = function(event) {
        if (!_musInbound[instanceId]) {
            _musInbound[instanceId] = [];
        }
        var inbound = typeof event.data === 'string'
            ? _binaryStringToBytes(event.data)
            : new Uint8Array(event.data);
        _musInbound[instanceId].push(inbound);
        _recordMusEvent({
            type: 'message',
            instanceId: instanceId,
            url: url,
            length: inbound.byteLength,
            hexPrefix: _hexPrefix(inbound, 24),
            textPrefix: _bytesToText(inbound.slice(0, 24))
        });
    };
    socket.onerror = function(event) {
        _musErrors[instanceId] = -2;
        _recordMusEvent({
            type: 'error',
            instanceId: instanceId,
            url: url,
            message: event && event.message ? event.message : ''
        });
    };
    socket.onclose = function(event) {
        _musDisconnected[instanceId] = true;
        _musCloseDetails[instanceId] = {
            code: event && typeof event.code === 'number' ? event.code : 0,
            reason: event && event.reason ? String(event.reason) : '',
            wasClean: !!(event && event.wasClean)
        };
        _recordMusEvent({
            type: 'close',
            instanceId: instanceId,
            url: url,
            code: _musCloseDetails[instanceId].code,
            reason: _musCloseDetails[instanceId].reason,
            wasClean: _musCloseDetails[instanceId].wasClean
        });
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
                _recordMusEvent({
                    type: 'send',
                    instanceId: instanceId,
                    length: data.byteLength,
                    hexPrefix: _hexPrefix(data, 32),
                    textPrefix: _bytesToText(data.slice(0, 32))
                });
                socket.send(data.buffer);
            } else {
                _recordMusEvent({
                    type: 'sendSkipped',
                    instanceId: instanceId,
                    length: data.byteLength,
                    hexPrefix: _hexPrefix(data, 32),
                    textPrefix: _bytesToText(data.slice(0, 32)),
                    readyState: socket ? socket.readyState : -1
                });
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

function _debugMusRequestConnect(instanceId, host, port, mode) {
    var hostLength = _writeString(host || '');
    _exports.debugMusRequestConnect(instanceId, hostLength, port | 0, mode | 0);
}

function _debugMusRequestSendText(instanceId, sender, subject, content) {
    var lengths = _writeStringSegments([sender || '', subject || '', content || '']);
    _exports.debugMusRequestSendText(instanceId, lengths[0], lengths[1], lengths[2]);
}

function _debugMusPollMessages(instanceId) {
    var count = _exports.debugMusPollMessageCount(instanceId);
    var messages = [];
    for (var i = 0; i < count; i++) {
        messages.push({
            errorCode: _exports.debugMusMessageError(i),
            sender: _readString(_exports.debugMusMessageSender(i)),
            subject: _readString(_exports.debugMusMessageSubject(i)),
            content: _readString(_exports.debugMusMessageContent(i))
        });
    }
    return messages;
}

function _findPendingMusRequest(instanceId, type) {
    var count = _exports.getMusPendingCount();
    for (var i = 0; i < count; i++) {
        if (_exports.getMusPendingInstanceId(i) === instanceId &&
                _exports.getMusPendingType(i) === type) {
            var dataLength = type === 1 ? _exports.getMusPendingSendData(i) : 0;
            var dataPrefix = dataLength > 0
                ? _hexPrefix(_stringBufferView().subarray(0, dataLength), 16)
                : '';
            return {
                index: i,
                count: count,
                dataLength: dataLength,
                dataHexPrefix: dataPrefix
            };
        }
    }
    return { index: -1, count: count, dataLength: 0, dataHexPrefix: '' };
}

function _findFirstPendingMusRequest(type) {
    var count = _exports.getMusPendingCount();
    for (var i = 0; i < count; i++) {
        if (_exports.getMusPendingType(i) === type) {
            var instanceId = _exports.getMusPendingInstanceId(i);
            var hostLength = type === 0 ? _exports.getMusPendingHost(i) : 0;
            var dataLength = type === 1 ? _exports.getMusPendingSendData(i) : 0;
            return {
                index: i,
                count: count,
                instanceId: instanceId,
                host: hostLength > 0 ? _readString(hostLength) : '',
                port: type === 0 ? _exports.getMusPendingPort(i) : 0,
                dataLength: dataLength,
                dataText: dataLength > 0 ? _bytesToText(_stringBufferView().subarray(0, dataLength)) : '',
                dataHexPrefix: dataLength > 0 ? _hexPrefix(_stringBufferView().subarray(0, dataLength), 24) : ''
            };
        }
    }
    return { index: -1, count: count, instanceId: 0, host: '', port: 0, dataLength: 0, dataText: '', dataHexPrefix: '' };
}

async function _runMusWebSocketSelfTest(message) {
    var timeoutMs = message.timeoutMs || 4000;
    var instanceId = message.instanceId || 0x5a1701;
    var errorInstanceId = message.errorInstanceId || (instanceId + 1);
    var url = message.url || _buildMusWebSocketUrl(message.host || '127.0.0.1', message.port || 0);
    var payload = message.payload == null ? 'client-ping' : String(message.payload);
    var payloadBytes = new TextEncoder().encode(payload);
    var result = {
        ok: false,
        url: url,
        instanceId: instanceId,
        payload: payload,
        connected: false,
        sent: false,
        received: false,
        disconnected: false,
        errorDelivered: false
    };

    _clearMusInstance(instanceId);
    _clearMusInstance(errorInstanceId);

    try {
        _musConnect(instanceId, url);
        var openState = await _waitForMusState(instanceId, function() {
            if (_hasQueuedMusEvent(_musConnected, instanceId)) {
                return { type: 'connected' };
            }
            if (_hasQueuedMusEvent(_musErrors, instanceId)) {
                return { type: 'error', code: _musErrors[instanceId] };
            }
            if (_hasQueuedMusEvent(_musDisconnected, instanceId)) {
                return { type: 'disconnected' };
            }
            return null;
        }, timeoutMs);

        if (!openState || openState.type !== 'connected') {
            result.openState = openState ? openState.type : 'timeout';
            if (openState && openState.type === 'error') {
                result.errorCode = openState.code;
            }
            return result;
        }

        _takeQueuedMusEvent(_musConnected, instanceId);
        result.connected = true;
        var socket = _musSockets[instanceId];
        if (!socket || socket.readyState !== WebSocket.OPEN) {
            result.openState = 'socket-not-open';
            return result;
        }

        socket.send(payloadBytes.buffer.slice(0));
        result.sent = true;

        var receiveState = await _waitForMusState(instanceId, function() {
            var inbound = _musInbound[instanceId];
            if (inbound && inbound.length > 0) {
                return { type: 'message' };
            }
            if (_hasQueuedMusEvent(_musErrors, instanceId)) {
                return { type: 'error', code: _musErrors[instanceId] };
            }
            if (_hasQueuedMusEvent(_musDisconnected, instanceId)) {
                return { type: 'disconnected' };
            }
            return null;
        }, timeoutMs);

        if (receiveState && receiveState.type === 'message') {
            var messages = _takeQueuedMusEvent(_musInbound, instanceId) || [];
            var first = messages.length > 0 ? messages[0] : new Uint8Array(0);
            result.received = messages.length > 0;
            result.receiveCount = messages.length;
            result.receiveLength = first.length;
            result.receiveText = _bytesToText(first);
            result.receiveHexPrefix = _hexPrefix(first, 24);
        } else {
            result.receiveState = receiveState ? receiveState.type : 'timeout';
            if (receiveState && receiveState.type === 'error') {
                result.errorCode = receiveState.code;
            }
        }

        socket = _musSockets[instanceId];
        if (socket && socket.readyState === WebSocket.OPEN) {
            socket.close(1000, 'self-test');
        }
        var closeState = await _waitForMusState(instanceId, function() {
            if (_hasQueuedMusEvent(_musDisconnected, instanceId)) {
                return { type: 'disconnected' };
            }
            return null;
        }, timeoutMs);
        result.disconnected = !!closeState || !!_takeQueuedMusEvent(_musDisconnected, instanceId);
        _takeQueuedMusEvent(_musErrors, instanceId);

        if (message.errorUrl) {
            _musConnect(errorInstanceId, message.errorUrl);
            var errorState = await _waitForMusState(errorInstanceId, function() {
                if (_hasQueuedMusEvent(_musErrors, errorInstanceId)) {
                    return { type: 'error', code: _musErrors[errorInstanceId] };
                }
                if (_hasQueuedMusEvent(_musConnected, errorInstanceId)) {
                    return { type: 'connected' };
                }
                if (_hasQueuedMusEvent(_musDisconnected, errorInstanceId)) {
                    return { type: 'disconnected' };
                }
                return null;
            }, timeoutMs);
            result.errorState = errorState ? errorState.type : 'timeout';
            if (errorState && errorState.type === 'error') {
                result.errorDelivered = true;
                result.errorCode = errorState.code;
                _takeQueuedMusEvent(_musErrors, errorInstanceId);
            }
            result.errorDisconnected = !!_takeQueuedMusEvent(_musDisconnected, errorInstanceId);
            _takeQueuedMusEvent(_musConnected, errorInstanceId);
        } else {
            result.errorDelivered = true;
        }

        result.ok = result.connected && result.sent && result.received &&
            result.disconnected && result.errorDelivered;
        return result;
    } catch (e) {
        result.exception = e && e.message ? e.message : String(e);
        return result;
    } finally {
        _clearMusInstance(instanceId);
        _clearMusInstance(errorInstanceId);
    }
}

async function _runCxxSmusBridgeSelfTest(message) {
    var timeoutMs = message.timeoutMs || 4000;
    var instanceId = message.instanceId || 0x5a2701;
    var errorInstanceId = message.errorInstanceId || (instanceId + 1);
    var host = message.host || '127.0.0.1';
    var port = message.port | 0;
    var errorHost = message.errorHost || host;
    var errorPort = message.errorPort | 0;
    var sender = message.sender || 'alice';
    var subject = message.subject || 'CHAT';
    var content = message.content == null ? 'hello-smus' : String(message.content);
    var rawMessages = Array.isArray(message.messages) && message.messages.length > 0
        ? message.messages
        : [{ sender: sender, subject: subject, content: content }];
    var expectedMessages = rawMessages.map(function(raw, index) {
        var entry = raw && typeof raw === 'object' ? raw : {};
        return {
            sender: entry.sender == null ? sender : String(entry.sender),
            subject: entry.subject == null ? subject : String(entry.subject),
            content: entry.content == null ? (index === 0 ? content : '') : String(entry.content)
        };
    });
    var result = {
        ok: false,
        host: host,
        port: port,
        instanceId: instanceId,
        expectedMessages: expectedMessages,
        connected: false,
        cppConnected: false,
        logonQueued: false,
        logonSent: false,
        smusSendQueued: false,
        smusSendSent: false,
        smusSendCount: 0,
        smusSequenceComplete: false,
        connectedBetweenMessages: true,
        cppReceived: false,
        cppReceivedCount: 0,
        disconnectRequested: false,
        errorDelivered: false
    };

    if (!port) {
        result.error = 'missing WebSocket port';
        return result;
    }

    _clearMusInstance(instanceId);
    _clearMusInstance(errorInstanceId);
    _exports.debugMusDestroyInstance(instanceId);
    _exports.debugMusDestroyInstance(errorInstanceId);

    try {
        _debugMusRequestConnect(instanceId, host, port, 0);
        result.connectPending = _findPendingMusRequest(instanceId, 0);
        _pumpMusRequests();

        var openState = await _waitForMusState(instanceId, function() {
            if (_hasQueuedMusEvent(_musConnected, instanceId)) {
                return { type: 'connected' };
            }
            if (_hasQueuedMusEvent(_musErrors, instanceId)) {
                return { type: 'error', code: _musErrors[instanceId] };
            }
            if (_hasQueuedMusEvent(_musDisconnected, instanceId)) {
                return { type: 'disconnected' };
            }
            return null;
        }, timeoutMs);
        result.openState = openState ? openState.type : 'timeout';
        if (!openState || openState.type !== 'connected') {
            if (openState && openState.type === 'error') {
                result.errorCode = openState.code;
            }
            return result;
        }

        result.connected = true;
        _deliverMusEvents();
        result.cppConnected = _exports.debugMusIsConnected(instanceId) === 1;
        result.connectedMessages = _debugMusPollMessages(instanceId);

        var logonPending = _findPendingMusRequest(instanceId, 1);
        result.logonPending = logonPending;
        result.logonQueued = logonPending.index >= 0 && logonPending.dataLength === 80 &&
            logonPending.dataHexPrefix.indexOf('72 00') === 0;
        _pumpMusRequests();
        result.logonSent = result.logonQueued;

        result.smusSendPendings = [];
        result.smusSendQueuedByMessage = [];
        result.smusSendSentByMessage = [];
        result.receiveStates = [];
        result.receivedMessages = [];
        result.receivedMatches = [];
        for (var messageIndex = 0; messageIndex < expectedMessages.length; messageIndex++) {
            var expected = expectedMessages[messageIndex];
            _debugMusRequestSendText(instanceId, expected.sender, expected.subject, expected.content);
            var sendPending = _findPendingMusRequest(instanceId, 1);
            var queued = sendPending.index >= 0 && sendPending.dataLength > 6 &&
                sendPending.dataHexPrefix.indexOf('72 00') === 0;
            result.smusSendPendings.push(sendPending);
            result.smusSendQueuedByMessage.push(queued);
            if (messageIndex === 0) {
                result.smusSendPending = sendPending;
                result.smusSendQueued = queued;
            }
            _pumpMusRequests();
            result.smusSendSentByMessage.push(queued);
            if (messageIndex === 0) {
                result.smusSendSent = queued;
            }
            if (queued) {
                result.smusSendCount++;
            }

            var matched = false;
            while (!matched) {
                var receiveState = await _waitForMusState(instanceId, function() {
                    var inbound = _musInbound[instanceId];
                    if (inbound && inbound.length > 0) {
                        return { type: 'message' };
                    }
                    if (_hasQueuedMusEvent(_musErrors, instanceId)) {
                        return { type: 'error', code: _musErrors[instanceId] };
                    }
                    if (_hasQueuedMusEvent(_musDisconnected, instanceId)) {
                        return { type: 'disconnected' };
                    }
                    return null;
                }, timeoutMs);
                result.receiveStates.push(receiveState ? receiveState.type : 'timeout');
                if (messageIndex === 0 && !result.receiveState) {
                    result.receiveState = receiveState ? receiveState.type : 'timeout';
                }
                if (!receiveState || receiveState.type !== 'message') {
                    if (receiveState && receiveState.type === 'error') {
                        result.receiveErrorCode = receiveState.code;
                    }
                    break;
                }
                _deliverMusEvents();
                var receivedMessages = _debugMusPollMessages(instanceId);
                for (var receivedIndex = 0; receivedIndex < receivedMessages.length; receivedIndex++) {
                    var received = receivedMessages[receivedIndex];
                    result.receivedMessages.push(received);
                    if (received.errorCode === 0 && received.sender === expected.sender &&
                            received.subject === expected.subject &&
                            received.content === expected.content) {
                        matched = true;
                    }
                }
            }
            result.receivedMatches.push(matched);
            if (matched) {
                result.cppReceivedCount++;
            }
            if (messageIndex < expectedMessages.length - 1) {
                var activeSocket = _musSockets[instanceId];
                var stillConnected = _exports.debugMusIsConnected(instanceId) === 1 &&
                    !!activeSocket && activeSocket.readyState === WebSocket.OPEN;
                result.connectedBetweenMessages = result.connectedBetweenMessages && stillConnected;
            }
        }
        result.cppReceived = result.receivedMatches.length > 0 && result.receivedMatches[0] === true;
        result.smusSequenceComplete = result.smusSendCount === expectedMessages.length &&
            result.cppReceivedCount === expectedMessages.length &&
            result.receivedMatches.every(function(matched) { return matched; });

        _exports.debugMusRequestDisconnect(instanceId);
        result.disconnectRequested = true;
        _pumpMusRequests();
        result.cppConnectedAfterDisconnect = _exports.debugMusIsConnected(instanceId) === 1;
        result.socketClosedAfterDisconnect = !_musSockets[instanceId];

        if (errorPort) {
            _debugMusRequestConnect(errorInstanceId, errorHost, errorPort, 0);
            _pumpMusRequests();
            var errorState = await _waitForMusState(errorInstanceId, function() {
                if (_hasQueuedMusEvent(_musErrors, errorInstanceId)) {
                    return { type: 'error', code: _musErrors[errorInstanceId] };
                }
                if (_hasQueuedMusEvent(_musConnected, errorInstanceId)) {
                    return { type: 'connected' };
                }
                if (_hasQueuedMusEvent(_musDisconnected, errorInstanceId)) {
                    return { type: 'disconnected' };
                }
                return null;
            }, timeoutMs);
            result.errorState = errorState ? errorState.type : 'timeout';
            if (errorState && errorState.type === 'error') {
                result.errorCode = errorState.code;
                _deliverMusEvents();
                result.errorMessages = _debugMusPollMessages(errorInstanceId);
                result.errorDelivered = result.errorMessages.some(function(errorMessage) {
                    return errorMessage.errorCode === errorState.code &&
                        errorMessage.subject === 'ConnectionProblem';
                });
            }
        } else {
            result.errorDelivered = true;
        }

        result.ok = result.connected && result.cppConnected && result.logonQueued &&
            result.logonSent && result.smusSendQueued && result.smusSendSent &&
            result.cppReceived && result.smusSequenceComplete &&
            result.connectedBetweenMessages && result.disconnectRequested &&
            result.socketClosedAfterDisconnect && !result.cppConnectedAfterDisconnect &&
            result.errorDelivered;
        return result;
    } catch (e) {
        result.exception = e && e.message ? e.message : String(e);
        return result;
    } finally {
        _clearMusInstance(instanceId);
        _clearMusInstance(errorInstanceId);
        _exports.debugMusDestroyInstance(instanceId);
        _exports.debugMusDestroyInstance(errorInstanceId);
    }
}

function _drainNavigation() {
    while (true) {
        var packedPage = _exports.readNextGotoNetPage();
        if (!packedPage) {
            break;
        }
        var urlLen = (packedPage >>> 16) & 0xffff;
        var targetLen = packedPage & 0xffff;
        var totalLen = urlLen + targetLen;
        var addr = _exports.getStringBufferAddress();
        if (totalLen < 0 || addr + totalLen > _mem().byteLength) {
            throw new Error('C++ WASM gotoNetPage buffer out of range');
        }
        var buffer = new Uint8Array(_mem(), addr, totalLen);
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

function _yieldToEventLoop() {
    return new Promise(function(resolve) {
        setTimeout(resolve, 0);
    });
}

function _nowMs() {
    return typeof performance !== 'undefined' && performance.now ? performance.now() : Date.now();
}

function _normalizeTickIntervalMs(value) {
    var numeric = Number(value);
    if (!isFinite(numeric) || numeric <= 0) {
        return 33;
    }
    return Math.max(1, Math.min(1000, Math.round(numeric)));
}

function _clearPlaybackTimer() {
    if (_tickTimer !== null) {
        clearTimeout(_tickTimer);
        _tickTimer = null;
    }
}

function _schedulePlaybackTick(delayMs) {
    if (!_playing || _tickTimer !== null) {
        return;
    }
    var delay = Math.max(0, Math.min(2147483647, Math.round(delayMs || 0)));
    _tickTimer = setTimeout(_playbackTickLoop, delay);
}

function _startPlaybackTimer(immediate) {
    _clearPlaybackTimer();
    if (!_playing) {
        return;
    }
    var now = _nowMs();
    _nextTickAt = now + (immediate ? 0 : _tickIntervalMs);
    _schedulePlaybackTick(_nextTickAt - now);
}

function _stopPlaybackTimer() {
    _clearPlaybackTimer();
    _nextTickAt = 0;
}

function _setTickIntervalMs(value) {
    _tickIntervalMs = _normalizeTickIntervalMs(value);
    if (_playing) {
        _startPlaybackTimer(false);
    }
}

async function _playbackTickLoop() {
    _tickTimer = null;
    if (!_playing) {
        return;
    }
    await _tick();
    if (!_playing) {
        return;
    }
    var now = _nowMs();
    if (_nextTickAt <= 0) {
        _nextTickAt = now + _tickIntervalMs;
    }
    while (_nextTickAt <= now) {
        _nextTickAt += _tickIntervalMs;
    }
    _schedulePlaybackTick(_nextTickAt - now);
}

async function _driveHostQueues(fetchRounds, loadSeq) {
    if (_isStaleLoad(loadSeq)) {
        return;
    }
    _deliverMusEvents();
    _deliverJpegDecodeResults();
    await _drainFetches(fetchRounds, loadSeq);
    if (_isStaleLoad(loadSeq)) {
        return;
    }
    var musRequests = _pumpMusRequests();
    if (musRequests > 0) {
        await _yieldToEventLoop();
    }
    _pumpAudioCommands();
    _pumpJpegDecodeRequests();
    _drainNavigation();
}

function _renderFrame() {
    _snapshotNativeOperation('render');
    var length = _exports.render();
    if (length <= 0) {
        var renderError = _readExportString(_exports.getDebugLog);
        if (renderError) {
            self.postMessage({ type: 'error', message: 'C++ WASM render failed: ' + renderError.trim() });
        } else {
            _postLastError();
        }
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

function _postFrame(loadSeq) {
    if (_isStaleLoad(loadSeq)) {
        return;
    }
    var frame = _renderFrame();
    if (!frame) {
        return;
    }
    var overlay = _collectOverlayState();
    var sharedFrame = false;
    var sharedSeq = 0;
    if (_sharedFrameBytes && _sharedFrameControl && frame.rgba.length <= _sharedFrameCapacity) {
        _sharedFrameBytes.set(frame.rgba);
        sharedSeq = ++_sharedFrameSeq;
        Atomics.store(_sharedFrameControl, 0, sharedSeq);
        Atomics.store(_sharedFrameControl, 1, frame.rgba.length);
        Atomics.store(_sharedFrameControl, 2, frame.width);
        Atomics.store(_sharedFrameControl, 3, frame.height);
        Atomics.notify(_sharedFrameControl, 0, 1);
        sharedFrame = true;
    }
    var transfer = [];
    if (!sharedFrame) {
        transfer.push(frame.rgba.buffer);
    }
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
        loadSeq: loadSeq || _activeLoadSeq,
        rgba: sharedFrame ? null : frame.rgba,
        sharedFrame: sharedFrame,
        sharedSeq: sharedSeq,
        cursorType: overlay.cursorType,
        cursorBitmap: overlay.cursorBitmap,
        caretInfo: overlay.caretInfo,
        selectionRects: overlay.selectionRects
    }, transfer);
}

async function _loadMovie(message) {
    var loadSeq = message.loadSeq || (_activeLoadSeq + 1);
    _activeLoadSeq = loadSeq;
    _loadInProgress = true;
    _playing = false;
    _stopPlaybackTimer();
    if (message.tickMs != null) {
        _setTickIntervalMs(message.tickMs);
    }
    _jpegDecodeSeq++;
    _jpegDecodeQueue = [];
    _jpegDecodeInFlight = {};
    _fetchResultCache = {};
    _fetchInflight = {};
    _fetchDiagnostics = {
        active: null,
        attempts: 0,
        delivered: 0,
        failed: 0,
        lastStatus: 0,
        lastUrl: '',
        deliveredTaskIds: [],
        failedTaskIds: []
    };
    try {
        var movieBytes = new Uint8Array(message.data || new ArrayBuffer(0));
        var basePathLength = _writeString(message.basePath || _basePath);
        var movieAddr = _exports.allocateBuffer(movieBytes.length);
        new Uint8Array(_mem(), movieAddr, movieBytes.length).set(movieBytes);
        var copiedMoviePrefix = new Uint8Array(_mem(), movieAddr, Math.min(movieBytes.length, 16));
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
        await _driveHostQueues(32, loadSeq);
        if (_isStaleLoad(loadSeq)) {
            return;
        }
        _exports.preloadCasts();
        await _driveHostQueues(128, loadSeq);
        if (_isStaleLoad(loadSeq)) {
            return;
        }
        if (!packedStage) {
            var loadError = _readLastError() || ('C++ WASM movie load failed; bytes=' + movieBytes.length +
                ' addr=' + movieAddr +
                ' source=' + _hexPrefix(movieBytes, 16) +
                ' wasm=' + _hexPrefix(copiedMoviePrefix, 16));
            self.postMessage({ type: 'error', message: loadError, loadSeq: loadSeq });
            return;
        }
        var width = (packedStage >>> 16) & 0xffff;
        var height = packedStage & 0xffff;
        self.postMessage({
            type: 'loaded',
            loadSeq: loadSeq,
            width: width,
            height: height,
            frameCount: _exports.getFrameCount(),
            tempo: _exports.getTempo()
        });
        _postFrame(loadSeq);
        if (message.autoplay !== false) {
            _playing = true;
            _snapshotNativeOperation('play');
            _exports.play();
            await _driveHostQueues(128, loadSeq);
            _postFrame(loadSeq);
        }
    } finally {
        if (_activeLoadSeq === loadSeq) {
            _loadInProgress = false;
            if (_playing) {
                _startPlaybackTimer(true);
            }
        }
    }
}

async function _tick() {
    if (!_ready || _loadInProgress || _tickInProgress || _diagnosticActive()) {
        return false;
    }
    _tickInProgress = true;
    try {
        await _driveHostQueues(32);
        if (_playing) {
            _snapshotNativeOperation('tick');
            _exports.tick();
            await _driveHostQueues(32);
        }
        _postLastError();
        _flushDebugLog();
        _postFrame();
        return true;
    } finally {
        _tickInProgress = false;
    }
}

async function _init(message) {
    _basePath = message.basePath || '';
    _pageProtocol = message.pageProtocol || '';
    _debugLogsEnabled = !!message.debugLogsEnabled;
    if (message.sharedFrameBuffer && message.sharedFrameControl && typeof Atomics === 'object') {
        _sharedFrameBytes = new Uint8ClampedArray(message.sharedFrameBuffer);
        _sharedFrameControl = new Int32Array(message.sharedFrameControl);
        _sharedFrameCapacity = message.sharedFrameCapacity || _sharedFrameBytes.length;
    }
    importScripts(_resolveUrl('libreshockwave-cpp-wasm.js'));
    if (typeof createLibreShockwaveCppWasm !== 'function') {
        throw new Error('createLibreShockwaveCppWasm was not exported by libreshockwave-cpp-wasm.js');
    }
    _module = await createLibreShockwaveCppWasm({
        locateFile: function(path) {
            return _resolveUrl(path);
        },
        instantiateWasm: _instantiateWasm
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
                if (message.tickMs != null) {
                    _setTickIntervalMs(message.tickMs);
                }
                _playing = true;
                _snapshotNativeOperation('play-message');
                _exports.play();
                await _driveHostQueues(8);
                _postFrame();
                _startPlaybackTimer(true);
                break;
            case 'pause':
                _playing = false;
                _stopPlaybackTimer();
                _exports.pause();
                break;
            case 'stop':
                _playing = false;
                _stopPlaybackTimer();
                _exports.stop();
                _postFrame();
                break;
            case 'setTickInterval':
                _setTickIntervalMs(message.tickMs);
                break;
            case 'goToFrame':
                _snapshotNativeOperation('goToFrame');
                _exports.goToFrame(message.frame | 0);
                await _driveHostQueues(8);
                _postFrame();
                break;
            case 'stepForward':
                _snapshotNativeOperation('stepForward');
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
            case 'getScriptDiagnostics':
                _postStringDiagnostic(message,
                                      'scriptDiagnostics',
                                      'diagnostics',
                                      _exports.getScriptDiagnostics);
                break;
            case 'getRuntimeDiagnostics':
                self.postMessage({
                    type: 'runtimeDiagnostics',
                    requestId: message.requestId || 0,
                    diagnostics: _runtimeDiagnostics()
                });
                break;
            case 'runMusWebSocketSelfTest':
                self.postMessage({
                    type: 'musWebSocketSelfTest',
                    requestId: message.requestId || 0,
                    diagnostics: await _runExclusiveDiagnostic(function() {
                        return _runMusWebSocketSelfTest(message);
                    })
                });
                break;
            case 'runCxxSmusBridgeSelfTest':
                self.postMessage({
                    type: 'cxxSmusBridgeSelfTest',
                    requestId: message.requestId || 0,
                    diagnostics: await _runExclusiveDiagnostic(function() {
                        return _runCxxSmusBridgeSelfTest(message);
                    })
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
        var message = _formatCaughtError(error);
        self.postMessage({ type: 'error', message: message });
    });
};
