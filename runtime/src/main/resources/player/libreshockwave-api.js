/**
 * LibreShockwave JavaScript API
 * Complete WASM implementation with embedded TeaVM runtime
 * Only requires: libreshockwave.wasm
 */

// =====================================================
// Embedded TeaVM WASM Runtime (Apache 2.0 License)
// =====================================================
var TeaVM = TeaVM || {};
TeaVM.wasm = function() {
    class JavaError extends Error {
        constructor(message) { super(message); }
    }

    let lineBuffer = "";
    function putwchar(charCode) {
        if (charCode === 10) {
            console.log(lineBuffer);
            lineBuffer = "";
        } else {
            lineBuffer += String.fromCharCode(charCode);
        }
    }
    function putwchars(controller, buffer, count) {
        let instance = controller.instance;
        let memory = new Int8Array(instance.exports.memory.buffer);
        for (let i = 0; i < count; ++i) {
            putwchar(memory[buffer++]);
        }
    }
    function currentTimeMillis() {
        return new Date().getTime();
    }
    function getNativeOffset(instant) {
        return new Date(instant).getTimezoneOffset();
    }
    function logString(string, controller) {
        let instance = controller.instance;
        let memory = instance.exports.memory.buffer;
        let arrayPtr = instance.exports.teavm_stringData(string);
        let length = instance.exports.teavm_arrayLength(arrayPtr);
        let arrayData = new Uint16Array(memory, instance.exports.teavm_charArrayData(arrayPtr), length * 2);
        for (let i = 0; i < length; ++i) {
            putwchar(arrayData[i]);
        }
    }
    function dateToString(timestamp, controller) {
        const s = new Date(timestamp).toString();
        let instance = controller.instance;
        let result = instance.exports.teavm_allocateString(s.length);
        if (result === 0) return 0;
        let resultAddress = instance.exports.teavm_objectArrayData(instance.exports.teavm_stringData(result));
        let resultView = new Uint16Array(instance.exports.memory.buffer, resultAddress, s.length);
        for (let i = 0; i < s.length; ++i) {
            resultView[i] = s.charCodeAt(i);
        }
        return result;
    }
    function logInt(i) {
        lineBuffer += i.toString();
    }
    function interrupt(controller) {
        if (controller.timer !== null) {
            clearTimeout(controller.timer);
            controller.timer = null;
        }
        controller.timer = setTimeout(() => process(controller), 0);
    }
    function process(controller) {
        let result = controller.instance.exports.teavm_processQueue();
        if (!controller.complete) {
            if (controller.instance.exports.teavm_stopped()) {
                controller.complete = true;
                controller.resolve();
            }
        }
        if (result >= 0) {
            controller.timer = setTimeout(() => process(controller), Number(result));
        }
    }

    function defaults(obj) {
        let controller = {
            instance: null,
            timer: null,
            resolve: null,
            reject: null,
            complete: false
        };
        obj.teavm = {
            currentTimeMillis: currentTimeMillis,
            nanoTime: () => performance.now(),
            putwcharsOut: (chars, count) => putwchars(controller, chars, count),
            putwcharsErr: (chars, count) => putwchars(controller, chars, count),
            getNativeOffset: getNativeOffset,
            logString: string => logString(string, controller),
            logInt: logInt,
            logOutOfMemory: () => console.log("Out of memory"),
            teavm_interrupt: () => interrupt(controller),
            dateToString: (timestamp) => dateToString(timestamp, controller)
        };
        obj.teavmMath = Math;
        obj.teavmHeapTrace = {
            allocate: function(address, size) {},
            free: function(address, size) {},
            assertFree: function(address, size) {},
            markStarted: function() {},
            mark: function(address) {},
            reportDirtyRegion: function(address) {},
            markCompleted: function() {},
            move: function(from, to, size) {},
            gcStarted: function(full) {},
            sweepStarted: function() {},
            sweepCompleted: function() {},
            defragStarted: function() {},
            defragCompleted: function() {},
            gcCompleted: function() {},
            init: function(maxHeap) {}
        };
        return controller;
    }

    function createTeaVM(instance) {
        let teavm = {
            memory: instance.exports.memory,
            instance,
            catchException: instance.exports.teavm_catchException
        };
        for (const name of ["allocateString", "stringData", "allocateObjectArray", "allocateStringArray",
            "allocateByteArray", "allocateShortArray", "allocateCharArray", "allocateIntArray",
            "allocateLongArray", "allocateFloatArray", "allocateDoubleArray",
            "objectArrayData", "byteArrayData", "shortArrayData", "charArrayData", "intArrayData",
            "longArrayData", "floatArrayData", "doubleArrayData", "arrayLength"]) {
            teavm[name] = wrapExport(instance.exports["teavm_" + name], instance);
        }
        teavm.main = createMain(teavm, instance.exports.main);
        return teavm;
    }

    function wrapExport(fn, instance) {
        return function() {
            let result = fn.apply(this, arguments);
            let ex = catchException(instance);
            if (ex !== null) throw ex;
            return result;
        };
    }

    function catchException(instance) {
        let ex = instance.exports.teavm_catchException();
        if (ex !== 0) return new JavaError("Uncaught exception occurred in Java");
        return null;
    }

    function load(path, options) {
        if (!options) options = {};
        const importObj = {};
        const controller = defaults(importObj);
        if (typeof options.installImports !== "undefined") {
            options.installImports(importObj, controller);
        }
        return WebAssembly.instantiateStreaming(fetch(path), importObj).then((obj => {
            controller.instance = obj.instance;
            let teavm = createTeaVM(obj.instance);
            teavm.main = createMain(teavm, controller);
            return teavm;
        }));
    }

    function createMain(teavm, controller) {
        return function(args) {
            if (typeof args === "undefined") args = [];
            return new Promise((resolve, reject) => {
                let javaArgs = teavm.allocateStringArray(args.length);
                let javaArgsData = new Int32Array(teavm.memory.buffer, teavm.objectArrayData(javaArgs), args.length);
                for (let i = 0; i < args.length; ++i) {
                    let arg = args[i];
                    let javaArg = teavm.allocateString(arg.length);
                    let javaArgAddress = teavm.objectArrayData(teavm.stringData(javaArg));
                    let javaArgData = new Uint16Array(teavm.memory.buffer, javaArgAddress, arg.length);
                    for (let j = 0; j < arg.length; ++j) {
                        javaArgData[j] = arg.charCodeAt(j);
                    }
                    javaArgsData[i] = javaArg;
                }
                controller.resolve = resolve;
                controller.reject = reject;
                try {
                    wrapExport(teavm.instance.exports.start, teavm.instance)(javaArgs);
                } catch (e) {
                    reject(e);
                    return;
                }
                process(controller);
            });
        };
    }

    return { JavaError, load };
}();

// =====================================================
// LibreShockwave API
// =====================================================
const LibreShockwave = (function() {
    'use strict';

    let teavm = null;
    let exports = null;
    let memory = null;
    let initialized = false;
    let movieLoaded = false;

    const listeners = {
        ready: [],
        stateChange: [],
        frameChange: [],
        spriteUpdate: [],
        error: [],
        log: []
    };

    function emit(event, data) {
        if (listeners[event]) {
            listeners[event].forEach(cb => {
                try { cb(data); } catch (e) { console.error(`Event ${event} error:`, e); }
            });
        }
    }

    function log(level, message) {
        console.log(`[LibreShockwave] ${message}`);
        emit('log', { level, message });
    }

    // =====================================================
    // WASM Loading
    // =====================================================

    // Helper to convert Java string pointer to JS string
    function javaStringToJs(stringPtr, instance) {
        if (!instance || !stringPtr) return String(stringPtr);
        try {
            const memory = instance.exports.memory.buffer;
            const arrayPtr = instance.exports.teavm_stringData(stringPtr);
            const length = instance.exports.teavm_arrayLength(arrayPtr);
            const charArrayPtr = instance.exports.teavm_charArrayData(arrayPtr);
            const chars = new Uint16Array(memory, charArrayPtr, length);
            let str = '';
            for (let i = 0; i < length; i++) {
                str += String.fromCharCode(chars[i]);
            }
            return str;
        } catch (e) {
            return String(stringPtr);
        }
    }

    async function loadWasm(wasmUrl) {
        log('info', `Loading WASM from ${wasmUrl}...`);

        teavm = await TeaVM.wasm.load(wasmUrl, {
            installImports: function(importObj, controller) {
                importObj.env = importObj.env || {};
                importObj.env.consoleLog = function(stringPtr) {
                    const msg = javaStringToJs(stringPtr, controller.instance);
                    console.log(msg);
                };
                importObj.env.consoleError = function(stringPtr) {
                    const msg = javaStringToJs(stringPtr, controller.instance);
                    console.error(msg);
                };
            }
        });
        exports = teavm.instance.exports;
        memory = exports.memory;

        await teavm.main([]);

        if (exports.lsw_init) {
            exports.lsw_init();
        }

        initialized = true;
        log('info', 'WASM runtime initialized');
    }

    function emitStateChange() {
        emit('stateChange', {
            initialized: initialized,
            loaded: movieLoaded,
            playing: exports.isPlaying ? exports.isPlaying() === 1 : false,
            paused: exports.isPaused ? exports.isPaused() === 1 : false,
            currentFrame: exports.getCurrentFrame ? exports.getCurrentFrame() : 1,
            lastFrame: exports.getLastFrame ? exports.getLastFrame() : 1,
            tempo: exports.getTempo ? exports.getTempo() : 15,
            stageWidth: exports.getStageWidth ? exports.getStageWidth() : 640,
            stageHeight: exports.getStageHeight ? exports.getStageHeight() : 480
        });
    }

    // =====================================================
    // Public API - All TeaVMEntry functions exposed
    // =====================================================

    return {
        // Initialize WASM runtime
        async init(options = {}) {
            if (initialized) return;
            try {
                await loadWasm(options.wasmUrl || 'libreshockwave.wasm');
                emit('ready', { mode: 'wasm' });
            } catch (error) {
                log('error', `Init failed: ${error.message}`);
                emit('error', { message: error.message });
                throw error;
            }
        },

        // Event handling
        on(event, callback) {
            if (listeners[event]) listeners[event].push(callback);
        },
        off(event, callback) {
            if (listeners[event]) {
                const idx = listeners[event].indexOf(callback);
                if (idx > -1) listeners[event].splice(idx, 1);
            }
        },

        // State checks
        isInitialized() { return initialized; },
        isWasmMode() { return exports !== null; },

        // TeaVMEntry: lsw_init (called by init())

        // TeaVMEntry: allocateMovieBuffer
        allocateMovieBuffer(size) {
            if (!exports) throw new Error('Not initialized');
            exports.allocateMovieBuffer(size);
        },

        // TeaVMEntry: setMovieDataByte
        setMovieDataByte(index, value) {
            if (!exports) throw new Error('Not initialized');
            exports.setMovieDataByte(index, value);
        },

        // TeaVMEntry: loadMovieFromBuffer
        loadMovieFromBuffer() {
            if (!exports) throw new Error('Not initialized');
            const result = exports.loadMovieFromBuffer();
            if (result === 1) {
                movieLoaded = true;
                emitStateChange();
            }
            return result;
        },

        // TeaVMEntry: play
        play() {
            if (!exports) throw new Error('Not initialized');
            exports.play();
            emitStateChange();
        },

        // TeaVMEntry: stop
        stop() {
            if (!exports) throw new Error('Not initialized');
            exports.stop();
            emitStateChange();
            emit('frameChange', { frame: 1, lastFrame: this.getLastFrame() });
        },

        // TeaVMEntry: pause
        pause() {
            if (!exports) throw new Error('Not initialized');
            exports.pause();
            emitStateChange();
        },

        // TeaVMEntry: nextFrame
        nextFrame() {
            if (!exports) throw new Error('Not initialized');
            const before = exports.getCurrentFrame();
            exports.nextFrame();
            const after = exports.getCurrentFrame();
            if (before !== after) {
                emit('frameChange', { frame: after, lastFrame: exports.getLastFrame() });
            }
            emitStateChange();
        },

        // TeaVMEntry: prevFrame
        prevFrame() {
            if (!exports) throw new Error('Not initialized');
            const before = exports.getCurrentFrame();
            exports.prevFrame();
            const after = exports.getCurrentFrame();
            if (before !== after) {
                emit('frameChange', { frame: after, lastFrame: exports.getLastFrame() });
            }
            emitStateChange();
        },

        // TeaVMEntry: goToFrame
        goToFrame(frame) {
            if (!exports) throw new Error('Not initialized');
            const before = exports.getCurrentFrame();
            exports.goToFrame(frame);
            const after = exports.getCurrentFrame();
            if (before !== after) {
                emit('frameChange', { frame: after, lastFrame: exports.getLastFrame() });
            }
            emitStateChange();
        },

        // TeaVMEntry: tick
        tick() {
            if (!exports || !movieLoaded) return;
            if (exports.isPlaying() !== 1) return;
            const before = exports.getCurrentFrame();
            exports.tick();
            const after = exports.getCurrentFrame();
            if (before !== after) {
                emit('frameChange', { frame: after, lastFrame: exports.getLastFrame() });
            }
            emitStateChange();
        },

        // TeaVMEntry: getCurrentFrame
        getCurrentFrame() {
            return exports ? exports.getCurrentFrame() : 1;
        },

        // TeaVMEntry: getLastFrame
        getLastFrame() {
            return exports ? exports.getLastFrame() : 1;
        },

        // TeaVMEntry: getTempo
        getTempo() {
            return exports ? exports.getTempo() : 15;
        },

        // TeaVMEntry: getStageWidth
        getStageWidth() {
            return exports ? exports.getStageWidth() : 640;
        },

        // TeaVMEntry: getStageHeight
        getStageHeight() {
            return exports ? exports.getStageHeight() : 480;
        },

        // TeaVMEntry: isPlaying
        isPlaying() {
            return exports ? exports.isPlaying() === 1 : false;
        },

        // TeaVMEntry: isPaused
        isPaused() {
            return exports ? exports.isPaused() === 1 : false;
        },

        // TeaVMEntry: getSpriteCount
        getSpriteCount() {
            return exports ? exports.getSpriteCount() : 0;
        },

        // TeaVMEntry: prepareSpriteData
        prepareSpriteData() {
            return exports ? exports.prepareSpriteData() : 0;
        },

        // TeaVMEntry: getSpriteDataValue
        getSpriteDataValue(index) {
            return exports ? exports.getSpriteDataValue(index) : 0;
        },

        // =====================================================
        // Convenience methods (built on TeaVMEntry functions)
        // =====================================================

        getState() {
            return {
                initialized: initialized,
                loaded: movieLoaded,
                playing: this.isPlaying(),
                paused: this.isPaused(),
                currentFrame: this.getCurrentFrame(),
                lastFrame: this.getLastFrame(),
                tempo: this.getTempo(),
                stageWidth: this.getStageWidth(),
                stageHeight: this.getStageHeight(),
                movieName: 'movie'
            };
        },

        // Load movie from URL
        async loadMovie(url) {
            if (!initialized) throw new Error('Not initialized');
            log('info', `Fetching movie from ${url}...`);

            // Extract base URL for external casts
            const lastSlash = url.lastIndexOf('/');
            this._movieBaseUrl = lastSlash > 0 ? url.substring(0, lastSlash) : '';
            this._movieName = lastSlash > 0 ? url.substring(lastSlash + 1) : url;
            log('info', `Movie base URL: ${this._movieBaseUrl || '(current directory)'}`);

            const response = await fetch(url);
            if (!response.ok) throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            const data = await response.arrayBuffer();
            return this.loadMovieFromData(data, this._movieName);
        },

        // Load movie from ArrayBuffer
        async loadMovieFromData(data, name = 'movie.dcr') {
            if (!initialized) throw new Error('Not initialized');
            const bytes = new Uint8Array(data);
            log('info', `Loading movie data (${bytes.length} bytes)...`);

            // Store the movie name (base URL should already be set by loadMovie if applicable)
            this._movieName = name;
            if (this._movieBaseUrl === undefined) {
                this._movieBaseUrl = '';
            }

            // Use TeaVMEntry functions to transfer data
            this.allocateMovieBuffer(bytes.length);
            for (let i = 0; i < bytes.length; i++) {
                this.setMovieDataByte(i, bytes[i]);
            }
            const result = this.loadMovieFromBuffer();

            if (result !== 1) throw new Error('Failed to load movie in WASM');
            log('info', `Movie loaded: ${this.getLastFrame()} frames, ${this.getStageWidth()}x${this.getStageHeight()}`);

            // Load external casts
            await this.loadPendingExternalCasts();

            return this.getState();
        },

        // Get pending external cast count
        getPendingExternalCastCount() {
            return exports ? exports.getPendingExternalCastCount() : 0;
        },

        // Get pending external cast info
        getPendingExternalCastInfo(index) {
            if (!exports) return null;
            const castNumber = exports.getPendingExternalCastNumber(index);
            const fileNameLength = exports.getPendingExternalCastFileNameLength(index);
            if (castNumber === 0 || fileNameLength === 0) return null;

            let fileName = '';
            for (let i = 0; i < fileNameLength; i++) {
                fileName += String.fromCharCode(exports.getPendingExternalCastFileNameChar(index, i));
            }
            return { castNumber, fileName };
        },

        // Load external cast from data
        async loadExternalCastFromData(castNumber, data) {
            if (!exports) throw new Error('Not initialized');
            const bytes = new Uint8Array(data);
            // log('info', `Loading external cast #${castNumber} (${bytes.length} bytes)...`);

            exports.allocateExternalCastBuffer(bytes.length);
            for (let i = 0; i < bytes.length; i++) {
                exports.setExternalCastDataByte(i, bytes[i]);
            }
            const result = exports.loadExternalCastFromBuffer(castNumber);

            if (result !== 1) {
                // log('error', `Failed to load external cast #${castNumber}`);
                return false;
            }
            log('info', `External cast #${castNumber} loaded successfully`);
            return true;
        },

        // Load all pending external casts
        async loadPendingExternalCasts() {
            const count = this.getPendingExternalCastCount();
            if (count === 0) {
                log('info', 'No external casts to load');
                return;
            }

            // log('info', `Loading ${count} external cast(s)...`);

            for (let i = 0; i < count; i++) {
                const info = this.getPendingExternalCastInfo(i);
                if (!info) continue;

                // log('info', `  Fetching external cast #${info.castNumber}: ${info.fileName}`);

                try {
                    // Construct URL relative to movie or current page
                    let url = info.fileName;
                    if (this._movieBaseUrl) {
                        url = this._movieBaseUrl + '/' + info.fileName;
                    }

                    // Try different extensions
                    const extensions = ['', '.cct', '.cst', '.cxt'];
                    let data = null;
                    let loadedUrl = null;

                    for (const ext of extensions) {
                        const tryUrl = url.replace(/\.(cct|cst|cxt)$/i, '') + ext;
                        try {
                            // log('info', `    Trying: ${tryUrl}`);
                            const response = await fetch(tryUrl);
                            if (response.ok) {
                                data = await response.arrayBuffer();
                                loadedUrl = tryUrl;
                                break;
                            }
                        } catch (e) {
                            // Try next extension
                        }
                    }

                    if (data) {
                        log('info', `    Loaded from: ${loadedUrl} (${data.byteLength} bytes)`);
                        await this.loadExternalCastFromData(info.castNumber, data);
                    } else {
                        // log('error', `    Failed to fetch external cast: ${info.fileName}`);
                    }
                } catch (error) {
                   // log('error', `    Error loading external cast #${info.castNumber}: ${error.message}`);
                }
            }

            log('info', 'External cast loading complete');
        },

        // Get sprites for current frame (convenience)
        getSprites() {
            const count = this.prepareSpriteData();
            const sprites = [];
            for (let i = 0; i < count; i++) {
                const base = i * 10;
                sprites.push({
                    channel: this.getSpriteDataValue(base + 0),
                    locH: this.getSpriteDataValue(base + 1),
                    locV: this.getSpriteDataValue(base + 2),
                    width: this.getSpriteDataValue(base + 3),
                    height: this.getSpriteDataValue(base + 4),
                    castLib: this.getSpriteDataValue(base + 5),
                    castMember: this.getSpriteDataValue(base + 6),
                    ink: this.getSpriteDataValue(base + 7),
                    blend: this.getSpriteDataValue(base + 8),
                    visible: this.getSpriteDataValue(base + 9) === 1,
                    member: {
                        castLib: this.getSpriteDataValue(base + 5),
                        memberNum: this.getSpriteDataValue(base + 6)
                    }
                });
            }
            return sprites;
        },

        // TeaVMEntry: prepareBitmap
        prepareBitmap(castLib, memberNum) {
            return exports ? exports.prepareBitmap(castLib, memberNum) : 0;
        },

        // TeaVMEntry: getBitmapWidth
        getBitmapWidth() {
            return exports ? exports.getBitmapWidth() : 0;
        },

        // TeaVMEntry: getBitmapHeight
        getBitmapHeight() {
            return exports ? exports.getBitmapHeight() : 0;
        },

        // TeaVMEntry: getBitmapPixel
        getBitmapPixel(index) {
            return exports ? exports.getBitmapPixel(index) : 0;
        },

        // TeaVMEntry: getBitmapPixelCount
        getBitmapPixelCount() {
            return exports ? exports.getBitmapPixelCount() : 0;
        },

        // Get bitmap as ImageData for canvas rendering
        getBitmap(castLib, memberNum) {
            if (!exports) {
                console.log('[getBitmap] exports not available');
                return null;
            }

            // Prepare bitmap data in WASM
            console.log(`[getBitmap] Calling prepareBitmap(${castLib}, ${memberNum})`);
            const result = exports.prepareBitmap(castLib, memberNum);
            console.log(`[getBitmap] prepareBitmap returned ${result}`);
            if (result !== 1) {
                console.log('[getBitmap] Failed to prepare bitmap');
                return null;
            }

            const width = exports.getBitmapWidth();
            const height = exports.getBitmapHeight();
            const pixelCount = exports.getBitmapPixelCount();
            console.log(`[getBitmap] Dimensions: ${width}x${height}, ${pixelCount} pixels`);

            if (width <= 0 || height <= 0 || pixelCount <= 0) {
                console.log('[getBitmap] Invalid dimensions');
                return null;
            }

            // Create ImageData and copy pixels
            const imageData = new ImageData(width, height);
            const data = imageData.data;

            for (let i = 0; i < pixelCount; i++) {
                const argb = exports.getBitmapPixel(i);
                const idx = i * 4;
                // ARGB to RGBA
                data[idx + 0] = (argb >> 16) & 0xFF; // R
                data[idx + 1] = (argb >> 8) & 0xFF;  // G
                data[idx + 2] = argb & 0xFF;         // B
                data[idx + 3] = (argb >> 24) & 0xFF; // A
            }

            console.log(`[getBitmap] Successfully created ImageData ${width}x${height}`);
            return imageData;
        },

        // Direct access to WASM exports
        getExports() { return exports; },
        getMemory() { return memory; }
    };
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = LibreShockwave;
}
