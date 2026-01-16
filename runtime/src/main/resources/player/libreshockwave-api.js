/**
 * LibreShockwave JavaScript API
 * Loads and interfaces with the LibreShockwave WASM runtime
 *
 * This is a stub that will be connected to the actual WASM module.
 * The WASM module contains the full Java SDK and Runtime compiled via TeaVM/GraalVM.
 */
const LibreShockwave = (function() {
    'use strict';

    // WASM module instance
    let wasmModule = null;
    let wasmMemory = null;
    let wasmExports = null;

    // Runtime state (mirrored from WASM)
    let state = {
        initialized: false,
        loaded: false,
        playing: false,
        paused: false,
        currentFrame: 1,
        lastFrame: 1,
        tempo: 15,
        stageWidth: 640,
        stageHeight: 480,
        movieName: '',
        sprites: []
    };

    // Event listeners
    const listeners = {
        ready: [],
        stateChange: [],
        frameChange: [],
        spriteUpdate: [],
        render: [],
        error: [],
        log: []
    };

    /**
     * Emit an event to all registered listeners
     */
    function emit(event, data) {
        if (listeners[event]) {
            listeners[event].forEach(callback => {
                try {
                    callback(data);
                } catch (e) {
                    console.error(`Error in ${event} listener:`, e);
                }
            });
        }
    }

    /**
     * Update internal state and emit changes
     */
    function updateState(newState) {
        const oldFrame = state.currentFrame;
        state = { ...state, ...newState };

        if (oldFrame !== state.currentFrame) {
            emit('frameChange', { frame: state.currentFrame, lastFrame: state.lastFrame });
        }

        emit('stateChange', state);
    }

    /**
     * Load the WASM module
     */
    async function loadWasm(wasmUrl = 'libreshockwave.wasm') {
        emit('log', { level: 'info', message: `Loading WASM from ${wasmUrl}...` });

        try {
            // Check if WASM file exists
            const response = await fetch(wasmUrl);
            if (!response.ok) {
                // WASM not available - use stub mode
                emit('log', { level: 'warn', message: 'WASM not found, running in stub mode' });
                state.initialized = true;
                emit('ready', { mode: 'stub' });
                return false;
            }

            const wasmBytes = await response.arrayBuffer();

            // Create memory for WASM
            wasmMemory = new WebAssembly.Memory({ initial: 256, maximum: 512 });

            // Import object for WASM module
            const importObject = {
                env: {
                    memory: wasmMemory,
                    // Console logging from WASM
                    consoleLog: (ptr, len) => {
                        const bytes = new Uint8Array(wasmMemory.buffer, ptr, len);
                        const text = new TextDecoder().decode(bytes);
                        emit('log', { level: 'info', message: text });
                    },
                    consoleError: (ptr, len) => {
                        const bytes = new Uint8Array(wasmMemory.buffer, ptr, len);
                        const text = new TextDecoder().decode(bytes);
                        emit('log', { level: 'error', message: text });
                    },
                    // Request render callback
                    requestRender: () => {
                        emit('render', {});
                    },
                    // Current time in milliseconds
                    currentTimeMillis: () => Date.now(),
                }
            };

            // Instantiate WASM module
            const wasmResult = await WebAssembly.instantiate(wasmBytes, importObject);
            wasmModule = wasmResult.module;
            wasmExports = wasmResult.instance.exports;

            // Initialize the runtime
            if (wasmExports.init) {
                wasmExports.init();
            }

            state.initialized = true;
            emit('log', { level: 'info', message: 'WASM runtime initialized' });
            emit('ready', { mode: 'wasm' });
            return true;

        } catch (error) {
            emit('log', { level: 'error', message: `WASM load error: ${error.message}` });
            // Fall back to stub mode
            state.initialized = true;
            emit('ready', { mode: 'stub' });
            return false;
        }
    }

    /**
     * Initialize in stub mode (no WASM)
     */
    function initStub() {
        state.initialized = true;
        emit('log', { level: 'info', message: 'LibreShockwave initialized (stub mode)' });
        emit('ready', { mode: 'stub' });
    }

    // =====================================================
    // Public API - These map to WASM exports when available
    // =====================================================

    return {
        /**
         * Initialize the runtime
         * @param {Object} options - { wasmUrl: 'path/to/libreshockwave.wasm' }
         */
        async init(options = {}) {
            if (state.initialized) return;

            if (options.wasmUrl) {
                await loadWasm(options.wasmUrl);
            } else {
                // Try default WASM location, fall back to stub
                const loaded = await loadWasm('libreshockwave.wasm');
                if (!loaded) {
                    initStub();
                }
            }
        },

        /**
         * Check if running in WASM mode
         */
        isWasmMode() {
            return wasmExports !== null;
        },

        /**
         * Get current player state
         */
        getState() {
            return { ...state };
        },

        /**
         * Register an event listener
         * Events: ready, stateChange, frameChange, spriteUpdate, render, error, log
         */
        on(event, callback) {
            if (listeners[event]) {
                listeners[event].push(callback);
            }
        },

        /**
         * Remove an event listener
         */
        off(event, callback) {
            if (listeners[event]) {
                const index = listeners[event].indexOf(callback);
                if (index > -1) {
                    listeners[event].splice(index, 1);
                }
            }
        },

        /**
         * Load a movie from URL
         * @param {string} url - URL to the .dcr/.dir file
         */
        async loadMovie(url) {
            emit('log', { level: 'info', message: `Loading movie: ${url}` });

            if (wasmExports && wasmExports.loadMovieFromUrl) {
                // Call WASM function
                const urlBytes = new TextEncoder().encode(url);
                const ptr = wasmExports.malloc(urlBytes.length);
                new Uint8Array(wasmMemory.buffer, ptr, urlBytes.length).set(urlBytes);

                const result = wasmExports.loadMovieFromUrl(ptr, urlBytes.length);
                wasmExports.free(ptr);

                if (result) {
                    // Read state from WASM
                    updateState({
                        loaded: true,
                        lastFrame: wasmExports.getLastFrame(),
                        tempo: wasmExports.getTempo(),
                        stageWidth: wasmExports.getStageWidth(),
                        stageHeight: wasmExports.getStageHeight(),
                        movieName: url.split('/').pop()
                    });
                }
            } else {
                // Stub mode - simulate loading
                await this._stubLoadMovie(url);
            }

            return state;
        },

        /**
         * Load a movie from ArrayBuffer
         * @param {ArrayBuffer} data - Movie file data
         * @param {string} name - Movie name
         */
        async loadMovieFromData(data, name = 'movie.dcr') {
            emit('log', { level: 'info', message: `Loading movie data: ${name} (${data.byteLength} bytes)` });

            if (wasmExports && wasmExports.loadMovieFromData) {
                // Copy data to WASM memory
                const ptr = wasmExports.malloc(data.byteLength);
                new Uint8Array(wasmMemory.buffer, ptr, data.byteLength).set(new Uint8Array(data));

                const result = wasmExports.loadMovieFromData(ptr, data.byteLength);
                wasmExports.free(ptr);

                if (result) {
                    updateState({
                        loaded: true,
                        lastFrame: wasmExports.getLastFrame(),
                        tempo: wasmExports.getTempo(),
                        stageWidth: wasmExports.getStageWidth(),
                        stageHeight: wasmExports.getStageHeight(),
                        movieName: name
                    });
                }
            } else {
                // Stub mode
                await this._stubLoadMovieData(data, name);
            }

            return state;
        },

        /**
         * Start playback
         */
        play() {
            if (wasmExports && wasmExports.play) {
                wasmExports.play();
                updateState({ playing: true, paused: false });
            } else {
                updateState({ playing: true, paused: false });
            }
            emit('log', { level: 'info', message: 'Playback started' });
        },

        /**
         * Stop playback
         */
        stop() {
            if (wasmExports && wasmExports.stop) {
                wasmExports.stop();
            }
            updateState({ playing: false, paused: false, currentFrame: 1 });
            emit('log', { level: 'info', message: 'Playback stopped' });
        },

        /**
         * Pause playback
         */
        pause() {
            if (wasmExports && wasmExports.pause) {
                wasmExports.pause();
            }
            updateState({ playing: false, paused: true });
            emit('log', { level: 'info', message: 'Playback paused' });
        },

        /**
         * Go to next frame
         */
        nextFrame() {
            if (wasmExports && wasmExports.nextFrame) {
                wasmExports.nextFrame();
                this._syncState();
            } else {
                const next = Math.min(state.currentFrame + 1, state.lastFrame);
                updateState({ currentFrame: next });
            }
            this._updateSprites();
        },

        /**
         * Go to previous frame
         */
        prevFrame() {
            if (wasmExports && wasmExports.prevFrame) {
                wasmExports.prevFrame();
                this._syncState();
            } else {
                const prev = Math.max(state.currentFrame - 1, 1);
                updateState({ currentFrame: prev });
            }
            this._updateSprites();
        },

        /**
         * Go to a specific frame
         * @param {number} frame - Frame number (1-based)
         */
        goToFrame(frame) {
            if (wasmExports && wasmExports.goToFrame) {
                wasmExports.goToFrame(frame);
                this._syncState();
            } else {
                const clamped = Math.max(1, Math.min(frame, state.lastFrame));
                updateState({ currentFrame: clamped });
            }
            this._updateSprites();
        },

        /**
         * Go to a frame by label
         * @param {string} label - Frame label name
         */
        goToLabel(label) {
            if (wasmExports && wasmExports.goToLabel) {
                const labelBytes = new TextEncoder().encode(label);
                const ptr = wasmExports.malloc(labelBytes.length);
                new Uint8Array(wasmMemory.buffer, ptr, labelBytes.length).set(labelBytes);

                wasmExports.goToLabel(ptr, labelBytes.length);
                wasmExports.free(ptr);
                this._syncState();
            } else {
                emit('log', { level: 'warn', message: `Label "${label}" not found (stub mode)` });
            }
            this._updateSprites();
        },

        /**
         * Execute one frame tick
         */
        tick() {
            if (!state.loaded || !state.playing) return;

            if (wasmExports && wasmExports.tick) {
                wasmExports.tick();
                this._syncState();
            } else {
                // Stub mode - simple frame advance
                let next = state.currentFrame + 1;
                if (next > state.lastFrame) {
                    next = 1;
                }
                updateState({ currentFrame: next });
            }
            this._updateSprites();
        },

        /**
         * Get sprite data for current frame
         */
        getSprites() {
            if (wasmExports && wasmExports.getSpriteCount) {
                return this._readSpritesFromWasm();
            }
            return state.sprites;
        },

        /**
         * Get bitmap data for a cast member (returns ImageData or null)
         * @param {number} castLib - Cast library number
         * @param {number} memberNum - Member number
         */
        getBitmap(castLib, memberNum) {
            if (wasmExports && wasmExports.getBitmapData) {
                return this._readBitmapFromWasm(castLib, memberNum);
            }
            return null;
        },

        /**
         * Call a Lingo handler
         * @param {string} name - Handler name
         * @param {array} args - Arguments
         */
        callHandler(name, args = []) {
            if (wasmExports && wasmExports.callHandler) {
                // Serialize args and call WASM
                const nameBytes = new TextEncoder().encode(name);
                const ptr = wasmExports.malloc(nameBytes.length);
                new Uint8Array(wasmMemory.buffer, ptr, nameBytes.length).set(nameBytes);

                const result = wasmExports.callHandler(ptr, nameBytes.length);
                wasmExports.free(ptr);

                return this._deserializeDatum(result);
            }
            emit('log', { level: 'warn', message: `Handler "${name}" called (stub mode)` });
            return null;
        },

        // =====================================================
        // Internal methods
        // =====================================================

        /**
         * Sync state from WASM
         */
        _syncState() {
            if (wasmExports) {
                updateState({
                    currentFrame: wasmExports.getCurrentFrame ? wasmExports.getCurrentFrame() : state.currentFrame,
                    playing: wasmExports.isPlaying ? wasmExports.isPlaying() !== 0 : state.playing,
                    paused: wasmExports.isPaused ? wasmExports.isPaused() !== 0 : state.paused
                });
            }
        },

        /**
         * Update sprites from WASM or stub
         */
        _updateSprites() {
            const sprites = this.getSprites();
            state.sprites = sprites;
            emit('spriteUpdate', sprites);
        },

        /**
         * Read sprites from WASM memory
         */
        _readSpritesFromWasm() {
            // TODO: Implement reading sprite data from WASM memory
            return [];
        },

        /**
         * Read bitmap from WASM memory
         */
        _readBitmapFromWasm(castLib, memberNum) {
            // TODO: Implement reading bitmap data from WASM memory
            return null;
        },

        /**
         * Deserialize a Datum from WASM
         */
        _deserializeDatum(ptr) {
            // TODO: Implement reading Datum from WASM memory
            return null;
        },

        // =====================================================
        // Stub mode implementations (for testing without WASM)
        // =====================================================

        async _stubLoadMovie(url) {
            // Fetch the file
            try {
                const response = await fetch(url);
                if (!response.ok) {
                    throw new Error(`HTTP ${response.status}`);
                }
                const data = await response.arrayBuffer();
                return this._stubLoadMovieData(data, url.split('/').pop());
            } catch (error) {
                emit('log', { level: 'error', message: `Failed to load: ${error.message}` });
                throw error;
            }
        },

        async _stubLoadMovieData(data, name) {
            // Parse basic file info (stub - just detect format)
            const view = new DataView(data);
            const magic = view.getUint32(0, false);

            let isRIFX = magic === 0x52494658; // 'RIFX'
            let isXFIR = magic === 0x58464952; // 'XFIR'

            if (!isRIFX && !isXFIR) {
                emit('log', { level: 'error', message: 'Invalid Director file format' });
                throw new Error('Invalid Director file');
            }

            // Simulate movie loading with stub data
            updateState({
                loaded: true,
                playing: false,
                paused: false,
                currentFrame: 1,
                lastFrame: 100, // Stub value
                tempo: 15,
                stageWidth: 640,
                stageHeight: 480,
                movieName: name,
                sprites: []
            });

            emit('log', { level: 'info', message: `Movie loaded (stub mode): ${name}` });
            emit('log', { level: 'warn', message: 'Running in stub mode - limited functionality' });

            return state;
        }
    };
})();

// Auto-initialize when DOM is ready
if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => LibreShockwave.init());
    } else {
        LibreShockwave.init();
    }
}

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = LibreShockwave;
}
