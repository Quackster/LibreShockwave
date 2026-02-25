/**
 * LibreShockwave Web Player Bridge
 *
 * JavaScript bridge between the browser and the WASM player engine.
 * Handles Canvas rendering, animation loop, file loading, and network requests.
 * The WASM module is a pure computation engine — all browser APIs are called from here.
 */
class LibreShockwavePlayer {
    constructor() {
        this.teavm = null;
        this.exports = null; // shortcut to teavm.instance.exports
        this.canvas = null;
        this.ctx = null;
        this.imageData = null;
        this.playing = false;
        this.lastFrameTime = 0;
        this.stageWidth = 640;
        this.stageHeight = 480;
        this.animFrameId = null;
    }

    /**
     * Set the canvas element for rendering.
     */
    setCanvas(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d');
    }

    /**
     * Initialize the WASM module. Must be called before any other method.
     */
    async init() {
        var self = this;

        this.teavm = await TeaVM.wasm.load('player-wasm.wasm', {
            installImports: function(importObj, controller) {
                // Provide the libreshockwave module imports expected by WasmNetManager
                importObj.libreshockwave = {
                    fetchGet: function(taskId, urlLength) {
                        self._handleFetchGet(taskId, urlLength);
                    },
                    fetchPost: function(taskId, urlLength, postDataLength) {
                        self._handleFetchPost(taskId, urlLength, postDataLength);
                    }
                };
            }
        });

        // teavm.instance.exports contains all WASM exports including @Export methods
        this.exports = this.teavm.instance.exports;

        // Initialize the Java runtime (main returns a Promise)
        await this.teavm.main([]);

        console.log('[LibreShockwave] WASM player initialized');
    }

    /**
     * Get the current ArrayBuffer from WASM memory.
     * Must be called fresh after any WASM call that might allocate memory,
     * since memory growth detaches the previous ArrayBuffer.
     */
    _getMemoryBuffer() {
        return this.teavm.memory.buffer;
    }

    /**
     * Load a movie from a Uint8Array or ArrayBuffer.
     * @param {Uint8Array|ArrayBuffer} bytes - The movie file bytes
     * @param {string} basePath - Base path for resolving relative resource URLs
     * @returns {boolean} true if loaded successfully
     */
    loadMovie(bytes, basePath) {
        var movieBytes = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
        var basePathStr = basePath || '';
        var basePathBytes = new TextEncoder().encode(basePathStr);

        // Write basePath to string buffer
        var stringBufAddr = this.exports.getStringBufferAddress();
        var stringBuf = new Uint8Array(this._getMemoryBuffer(), stringBufAddr, 4096);
        stringBuf.set(basePathBytes);

        // Allocate movie buffer and write movie bytes
        this.exports.allocateMovieBuffer(movieBytes.length);
        // Re-obtain memory buffer after allocation (may have grown)
        var movieBufAddr = this.exports.getMovieBufferAddress();
        var movieBuf = new Uint8Array(this._getMemoryBuffer(), movieBufAddr, movieBytes.length);
        movieBuf.set(movieBytes);

        // Load the movie
        var result = this.exports.loadMovie(movieBytes.length, basePathBytes.length);
        if (result === 0) {
            console.error('[LibreShockwave] Failed to load movie');
            return false;
        }

        this.stageWidth = (result >> 16) & 0xFFFF;
        this.stageHeight = result & 0xFFFF;

        // Set up canvas dimensions
        this.canvas.width = this.stageWidth;
        this.canvas.height = this.stageHeight;
        this.imageData = this.ctx.createImageData(this.stageWidth, this.stageHeight);

        // Render initial frame
        this._renderFrame();

        console.log('[LibreShockwave] Movie loaded: ' + this.stageWidth + 'x' + this.stageHeight);
        return true;
    }

    // === Playback controls ===

    play() {
        this.exports.play();
        if (!this.playing) {
            this.playing = true;
            this.lastFrameTime = 0;
            this._requestFrame();
        }
    }

    pause() {
        this.exports.pause();
        this.playing = false;
        if (this.animFrameId) {
            cancelAnimationFrame(this.animFrameId);
            this.animFrameId = null;
        }
    }

    stop() {
        this.exports.stop();
        this.playing = false;
        if (this.animFrameId) {
            cancelAnimationFrame(this.animFrameId);
            this.animFrameId = null;
        }
        this._renderFrame();
    }

    goToFrame(frame) {
        this.exports.goToFrame(frame);
        this._renderFrame();
    }

    getCurrentFrame() {
        return this.exports.getCurrentFrame();
    }

    getFrameCount() {
        return this.exports.getFrameCount();
    }

    getTempo() {
        return this.exports.getTempo();
    }

    // === Animation loop ===

    _requestFrame() {
        var self = this;
        this.animFrameId = requestAnimationFrame(function(ts) {
            self._onAnimationFrame(ts);
        });
    }

    _onAnimationFrame(timestamp) {
        if (!this.playing) return;

        var tempo = this.exports.getTempo();
        var msPerFrame = 1000.0 / (tempo > 0 ? tempo : 15);

        if (this.lastFrameTime === 0) {
            this.lastFrameTime = timestamp;
        }

        var elapsed = timestamp - this.lastFrameTime;
        if (elapsed >= msPerFrame) {
            this.lastFrameTime = timestamp - (elapsed % msPerFrame);

            var stillPlaying = this.exports.tick();
            if (stillPlaying === 0) {
                this.playing = false;
                return;
            }

            this._renderFrame();
        }

        this._requestFrame();
    }

    /**
     * Read the RGBA pixel buffer from WASM memory and paint it to the canvas.
     */
    _renderFrame() {
        if (!this.ctx || !this.imageData) return;

        var ptr = this.exports.render();
        if (ptr === 0) return;

        // Re-obtain memory buffer (render may have allocated)
        var pixelCount = this.stageWidth * this.stageHeight * 4;
        var rgba = new Uint8ClampedArray(this._getMemoryBuffer(), ptr, pixelCount);

        this.imageData.data.set(rgba);
        this.ctx.putImageData(this.imageData, 0, 0);
    }

    // === Network fetch handling ===

    /**
     * Read a UTF-8 string from the shared string buffer.
     */
    _readStringFromBuffer(length) {
        var stringBufAddr = this.exports.getStringBufferAddress();
        var bytes = new Uint8Array(this._getMemoryBuffer(), stringBufAddr, length);
        return new TextDecoder().decode(bytes);
    }

    /**
     * Handle a GET fetch request from the WASM module.
     * Called via @Import from WasmNetManager.jsFetchGet().
     */
    _handleFetchGet(taskId, urlLength) {
        var url = this._readStringFromBuffer(urlLength);
        console.log('[LibreShockwave] Fetch GET: ' + url + ' (task ' + taskId + ')');

        var self = this;
        fetch(url)
            .then(function(r) {
                if (!r.ok) throw r.status;
                return r.arrayBuffer();
            })
            .then(function(buf) {
                self._deliverFetchResult(taskId, new Uint8Array(buf));
            })
            .catch(function(e) {
                var status = typeof e === 'number' ? e : 0;
                self.exports.onFetchError(taskId, status);
            });
    }

    /**
     * Handle a POST fetch request from the WASM module.
     * Called via @Import from WasmNetManager.jsFetchPost().
     */
    _handleFetchPost(taskId, urlLength, postDataLength) {
        var url = this._readStringFromBuffer(urlLength);

        // Read post data from string buffer after the URL
        var stringBufAddr = this.exports.getStringBufferAddress();
        var postBytes = new Uint8Array(this._getMemoryBuffer(), stringBufAddr + urlLength, postDataLength);
        var postData = new TextDecoder().decode(postBytes);

        console.log('[LibreShockwave] Fetch POST: ' + url + ' (task ' + taskId + ')');

        var self = this;
        fetch(url, {
            method: 'POST',
            body: postData,
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
        })
            .then(function(r) {
                if (!r.ok) throw r.status;
                return r.arrayBuffer();
            })
            .then(function(buf) {
                self._deliverFetchResult(taskId, new Uint8Array(buf));
            })
            .catch(function(e) {
                var status = typeof e === 'number' ? e : 0;
                self.exports.onFetchError(taskId, status);
            });
    }

    /**
     * Write fetch response data into the WASM net buffer and notify Java.
     */
    _deliverFetchResult(taskId, data) {
        // Allocate buffer in WASM for the response
        this.exports.allocateNetBuffer(data.length);

        // Re-obtain memory after allocation and write data
        var netBufAddr = this.exports.getNetBufferAddress();
        var netBuf = new Uint8Array(this._getMemoryBuffer(), netBufAddr, data.length);
        netBuf.set(data);

        // Notify Java that the fetch completed
        this.exports.onFetchComplete(taskId, data.length);
    }
}
