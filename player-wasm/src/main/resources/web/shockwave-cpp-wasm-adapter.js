(function(root, factory) {
    'use strict';
    var api = factory();
    root.LibreShockwaveCppWasmAdapter = api;
    if (typeof module === 'object' && module.exports) {
        module.exports = api;
    }
})(typeof self !== 'undefined' ? self : globalThis, function() {
    'use strict';

    function zero() { return 0; }
    function noop() {}

    var requiredExports = {
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
        getCurrentFrame: 'current_frame',
        getFrameCount: 'frame_count',
        getTempo: 'tempo',
        setPuppetTempo: 'set_puppet_tempo',
        getStageWidth: 'stage_width',
        getStageHeight: 'stage_height',
        addTraceHandler: 'add_trace_handler',
        removeTraceHandler: 'remove_trace_handler',
        clearTraceHandlers: 'clear_trace_handlers',
        setDebugPlaybackEnabled: 'set_debug_playback_enabled',
        getDebugLog: 'get_debug_log',
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
        triggerTestError: 'trigger_test_error',
        getCallStack: 'get_call_stack',
        getWindowSpriteDiagnostics: 'get_window_sprite_diagnostics',
        getVisibleTextDiagnostics: 'get_visible_text_diagnostics',
        getBootstrapDiagnostics: 'get_bootstrap_diagnostics',
        setExternalParam: 'set_external_param',
        clearExternalParams: 'clear_external_params',
        mouseMove: 'mouse_move',
        mouseDown: 'mouse_down',
        mouseUp: 'mouse_up',
        blur: 'blur',
        keyDown: 'key_down',
        keyUp: 'key_up',
        getPendingFetchCount: 'get_pending_fetch_count',
        getPendingFetchTaskId: 'get_pending_fetch_task_id',
        getPendingFetchUrl: 'get_pending_fetch_url',
        getPendingFetchMethod: 'get_pending_fetch_method',
        getPendingFetchPostData: 'get_pending_fetch_post_data',
        getPendingFetchFallbackCount: 'get_pending_fetch_fallback_count',
        getPendingFetchFallbackUrl: 'get_pending_fetch_fallback_url',
        drainPendingFetches: 'drain_pending_fetches',
        allocateNetBuffer: 'allocate_net_buffer',
        getNetBufferAddress: 'get_net_buffer_address',
        deliverFetchResult: 'deliver_fetch_result',
        deliverFetchStatus: 'deliver_fetch_status',
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
        getLastError: 'get_last_error'
    };

    var fallbackExports = {};

    function exportRoots(source) {
        var roots = [];
        function add(value) {
            if (value && roots.indexOf(value) === -1) roots.push(value);
        }
        add(source);
        add(source && source.exports);
        add(source && source.asm);
        add(source && source.wasmExports);
        add(source && source.instance && source.instance.exports);
        return roots;
    }

    function findExport(source, suffix) {
        var fullName = 'libreshockwave_wasm_' + suffix;
        var names = [fullName, '_' + fullName];
        var roots = exportRoots(source);
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

    function createExports(source) {
        if (!source) throw new Error('Missing C++ WASM module exports');
        var adapted = {};
        var publicName;
        for (publicName in requiredExports) {
            var fn = findExport(source, requiredExports[publicName]);
            if (!fn) {
                throw new Error('Missing C++ WASM export for ' + publicName +
                    ' (libreshockwave_wasm_' + requiredExports[publicName] + ')');
            }
            adapted[publicName] = fn;
        }
        for (publicName in fallbackExports) {
            adapted[publicName] = findExport(source, fallbackExports[publicName][0]) ||
                fallbackExports[publicName][1];
        }
        return adapted;
    }

    function missingRequiredExports(source) {
        var missing = [];
        for (var publicName in requiredExports) {
            if (!findExport(source, requiredExports[publicName])) {
                missing.push(publicName);
            }
        }
        return missing;
    }

    function findMemory(source) {
        var roots = exportRoots(source);
        for (var r = 0; r < roots.length; r++) {
            if (roots[r].memory && roots[r].memory.buffer) return roots[r].memory;
            if (roots[r].wasmMemory && roots[r].wasmMemory.buffer) return roots[r].wasmMemory;
        }
        return null;
    }

    function createTeaVmShim(source) {
        return {
            memory: findMemory(source),
            instance: {
                exports: source && source.instance && source.instance.exports
                    ? source.instance.exports
                    : (source && source.exports ? source.exports : source)
            }
        };
    }

    function createEngine(source) {
        return {
            teavm: createTeaVmShim(source),
            exports: createExports(source)
        };
    }

    return {
        createExports: createExports,
        createEngine: createEngine,
        createTeaVmShim: createTeaVmShim,
        missingRequiredExports: missingRequiredExports,
        requiredExportNames: Object.keys(requiredExports),
        fallbackExportNames: Object.keys(fallbackExports)
    };
});
