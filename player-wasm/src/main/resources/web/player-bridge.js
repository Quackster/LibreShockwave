/**
 * LibreShockwave Web Player Bridge
 *
 * JavaScript bridge between the browser and the WASM player engine.
 * Uses a WebWorker for WASM execution so the UI thread stays responsive.
 * Renders sprites via Canvas 2D (drawImage, fillText, fillRect).
 * Supports full debug panel with instruction-level stepping via SharedArrayBuffer.
 */
class LibreShockwavePlayer {
    constructor() {
        this.worker = null;
        this.canvas = null;
        this.ctx = null;
        this.playing = false;
        this.lastFrameTime = 0;
        this.stageWidth = 640;
        this.stageHeight = 480;
        this.animFrameId = null;
        this.movieLoaded = false;

        // SharedArrayBuffer for debug pause/resume
        this.debugSab = null;
        this.debugView = null;

        // Bitmap cache: memberId -> ImageBitmap
        this.bitmapCache = new Map();
        // Pending bitmap requests
        this.pendingBitmaps = new Set();

        // Callbacks
        this._onMovieLoaded = null;
        this._onFrameUpdate = null;
        this._onDebugPaused = null;
        this._onDebugResumed = null;
        this._onStateChange = null;
        this._onError = null;

        // Debug panel manager (set externally)
        this.debugPanel = null;

        // Promise resolvers for request/response commands
        this._pendingCallbacks = {};
        this._callbackId = 0;
    }

    setCanvas(canvas) {
        this.canvas = canvas;
        this.ctx = canvas.getContext('2d');
    }

    /**
     * Initialize the WebWorker and WASM module.
     */
    async init() {
        var self = this;

        // Create SharedArrayBuffer for debug blocking (requires COOP/COEP headers)
        try {
            this.debugSab = new SharedArrayBuffer(4);
            this.debugView = new Int32Array(this.debugSab);
        } catch (e) {
            console.warn('[LibreShockwave] SharedArrayBuffer not available — debug stepping will not block:', e.message);
        }

        return new Promise(function(resolve, reject) {
            self.worker = new Worker('worker.js');
            self.worker.onmessage = function(e) {
                self._onWorkerMessage(e.data);
            };
            self.worker.onerror = function(e) {
                reject(new Error('Worker error: ' + e.message));
            };

            // Store resolver for 'ready' event
            self._initResolve = resolve;
            self._initReject = reject;

            // Send init command with SharedArrayBuffer
            self.worker.postMessage({ cmd: 'init', sab: self.debugSab });
        });
    }

    // === Worker message handler ===

    _onWorkerMessage(msg) {
        switch (msg.type) {
            case 'ready':
                console.log('[LibreShockwave] WASM player initialized via worker');
                if (this._initResolve) {
                    this._initResolve();
                    this._initResolve = null;
                }
                break;

            case 'error':
                console.error('[LibreShockwave]', msg.message);
                if (this._initReject) {
                    this._initReject(new Error(msg.message));
                    this._initReject = null;
                }
                if (this._onError) this._onError(msg.message);
                break;

            case 'movieLoaded':
                this.stageWidth = msg.width;
                this.stageHeight = msg.height;
                this.canvas.width = msg.width;
                this.canvas.height = msg.height;
                this.movieLoaded = true;
                if (this._onMovieLoaded) {
                    this._onMovieLoaded(msg);
                }
                // Request first frame
                this._sendCmd('tick');
                break;

            case 'frameData':
                this._onFrameData(msg);
                break;

            case 'bitmapData':
                this._onBitmapData(msg);
                break;

            case 'stateChange':
                if (this._onStateChange) this._onStateChange(msg.state);
                break;

            case 'debugPaused':
                this.playing = false;
                this._stopAnimationLoop();
                if (this._onDebugPaused) this._onDebugPaused(msg.snapshot);
                if (this.debugPanel) this.debugPanel.onDebugPaused(msg.snapshot);
                break;

            case 'debugResumed':
                if (this._onDebugResumed) this._onDebugResumed();
                if (this.debugPanel) this.debugPanel.onDebugResumed();
                break;

            case 'debugEnabled':
                if (this.debugPanel) this.debugPanel.onDebugEnabled();
                break;

            case 'scriptList':
                if (this.debugPanel) this.debugPanel.onScriptList(msg.scripts);
                break;

            case 'handlerBytecode':
                if (this.debugPanel) this.debugPanel.onHandlerBytecode(msg);
                break;

            case 'handlerDetails':
                if (this.debugPanel) this.debugPanel.onHandlerDetails(msg.details);
                break;

            case 'watchList':
                if (this.debugPanel) this.debugPanel.onWatchList(msg.watches);
                break;

            case 'debugSnapshot':
                if (this.debugPanel) this.debugPanel.onDebugSnapshot(msg.snapshot);
                break;

            case 'breakpointToggled':
                if (this.debugPanel) this.debugPanel.onBreakpointToggled(msg);
                break;

            case 'breakpointsCleared':
                if (this.debugPanel) this.debugPanel.onBreakpointsCleared();
                break;

            case 'fetchRequest':
                this._handleFetchRelay(msg);
                break;

            case 'preloadStarted':
                if (this._onPreloadStarted) this._onPreloadStarted(msg.count);
                break;

            case 'log':
                console.log('[Worker]', msg.message);
                break;
        }
    }

    // === Movie loading ===

    loadMovie(bytes, basePath) {
        var movieBytes = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
        this.bitmapCache.clear();
        this.pendingBitmaps.clear();
        this.worker.postMessage(
            { cmd: 'loadMovie', bytes: movieBytes.buffer, basePath: basePath || '' },
            [movieBytes.buffer]
        );
        return true;
    }

    // === Playback controls ===

    play() {
        this._sendCmd('play');
        if (!this.playing) {
            this.playing = true;
            this.lastFrameTime = 0;
            this._startAnimationLoop();
        }
    }

    pause() {
        this._sendCmd('pause');
        this.playing = false;
        this._stopAnimationLoop();
    }

    stop() {
        this._sendCmd('stop');
        this.playing = false;
        this._stopAnimationLoop();
    }

    goToFrame(frame) {
        this._sendCmd('goToFrame', { frame: frame });
    }

    stepForward() {
        this._sendCmd('stepForward');
    }

    stepBackward() {
        this._sendCmd('stepBackward');
    }

    getCurrentFrame() {
        return this._lastFrame || 0;
    }

    getFrameCount() {
        return this._lastFrameCount || 0;
    }

    getTempo() {
        return this._lastTempo || 15;
    }

    // === Debug commands ===

    enableDebug() {
        this._sendCmd('enableDebug');
    }

    toggleBreakpoint(scriptId, offset) {
        this._sendCmd('toggleBreakpoint', { scriptId: scriptId, offset: offset });
    }

    clearBreakpoints() {
        this._sendCmd('clearBreakpoints');
    }

    debugStepInto() {
        this._debugResume();
        this._sendCmd('debugStepInto');
    }

    debugStepOver() {
        this._debugResume();
        this._sendCmd('debugStepOver');
    }

    debugStepOut() {
        this._debugResume();
        this._sendCmd('debugStepOut');
    }

    debugContinue() {
        this._debugResume();
        this._sendCmd('debugContinue');
    }

    debugPause() {
        this._sendCmd('debugPause');
    }

    getScriptList() {
        this._sendCmd('getScriptList');
    }

    getHandlerBytecode(scriptId, handlerIndex) {
        this._sendCmd('getHandlerBytecode', { scriptId: scriptId, handlerIndex: handlerIndex });
    }

    getHandlerDetails(scriptId, handlerIndex) {
        this._sendCmd('getHandlerDetails', { scriptId: scriptId, handlerIndex: handlerIndex });
    }

    addWatch(expression) {
        this._sendCmd('addWatch', { expression: expression });
    }

    removeWatch(id) {
        this._sendCmd('removeWatch', { id: id });
    }

    clearWatches() {
        this._sendCmd('clearWatches');
    }

    getDebugSnapshot() {
        this._sendCmd('getDebugSnapshot');
    }

    serializeBreakpoints() {
        this._sendCmd('serializeBreakpoints');
    }

    deserializeBreakpoints(data) {
        this._sendCmd('deserializeBreakpoints', { data: data });
    }

    preloadAllCasts() {
        this._sendCmd('preloadAllCasts');
    }

    requestBitmap(memberId) {
        if (this.bitmapCache.has(memberId) || this.pendingBitmaps.has(memberId)) return;
        this.pendingBitmaps.add(memberId);
        this._sendCmd('getBitmapData', { memberId: memberId });
    }

    /**
     * Unblock the worker's Atomics.wait() for debug resume.
     */
    _debugResume() {
        if (this.debugView) {
            Atomics.store(this.debugView, 0, 1);
            Atomics.notify(this.debugView, 0);
        }
    }

    // === Animation loop ===

    _startAnimationLoop() {
        var self = this;
        function loop(timestamp) {
            if (!self.playing) return;

            var tempo = self._lastTempo || 15;
            var msPerFrame = 1000.0 / (tempo > 0 ? tempo : 15);

            if (self.lastFrameTime === 0) {
                self.lastFrameTime = timestamp;
            }

            var elapsed = timestamp - self.lastFrameTime;
            if (elapsed >= msPerFrame) {
                self.lastFrameTime = timestamp - (elapsed % msPerFrame);
                self._sendCmd('tick');
            }

            self.animFrameId = requestAnimationFrame(loop);
        }
        this.animFrameId = requestAnimationFrame(loop);
    }

    _stopAnimationLoop() {
        if (this.animFrameId) {
            cancelAnimationFrame(this.animFrameId);
            this.animFrameId = null;
        }
    }

    // === Frame rendering ===

    _onFrameData(msg) {
        this._lastFrame = msg.frame;
        this._lastFrameCount = msg.frameCount;

        if (this._onFrameUpdate) {
            this._onFrameUpdate(msg.frame, msg.frameCount);
        }

        if (!msg.stillPlaying && this.playing) {
            this.playing = false;
            this._stopAnimationLoop();
        }

        // Prefer sprite-based rendering, fall back to pixel buffer
        if (msg.frameData && msg.frameData.sprites) {
            this._renderSprites(msg.frameData);
        } else if (msg.pixels) {
            this._renderPixels(msg.pixels);
        }
    }

    /**
     * Render sprites via Canvas 2D.
     */
    _renderSprites(frameData) {
        var ctx = this.ctx;
        if (!ctx) return;

        // Background
        var bg = frameData.bg || 0xFFFFFF;
        ctx.fillStyle = '#' + (bg & 0xFFFFFF).toString(16).padStart(6, '0');
        ctx.fillRect(0, 0, this.stageWidth, this.stageHeight);

        var sprites = frameData.sprites;
        if (!sprites) return;

        // Request any missing bitmaps
        for (var i = 0; i < sprites.length; i++) {
            var s = sprites[i];
            if (s.type === 'BITMAP' && s.memberId > 0 && s.visible) {
                this.requestBitmap(s.memberId);
            }
        }

        // Draw sprites in order (already sorted by channel from player-core)
        for (var i = 0; i < sprites.length; i++) {
            var sprite = sprites[i];
            if (!sprite.visible) continue;

            switch (sprite.type) {
                case 'BITMAP':
                    this._drawBitmap(ctx, sprite);
                    break;
                case 'TEXT':
                case 'BUTTON':
                    this._drawText(ctx, sprite);
                    break;
                case 'SHAPE':
                    this._drawShape(ctx, sprite);
                    break;
            }
        }
    }

    _drawBitmap(ctx, sprite) {
        var bmp = this.bitmapCache.get(sprite.memberId);
        if (bmp) {
            var w = sprite.w > 0 ? sprite.w : bmp.width;
            var h = sprite.h > 0 ? sprite.h : bmp.height;
            ctx.drawImage(bmp, sprite.x, sprite.y, w, h);
        } else {
            // Placeholder while bitmap loads
            ctx.fillStyle = '#c8c8c8';
            ctx.globalAlpha = 0.5;
            ctx.fillRect(sprite.x, sprite.y,
                sprite.w > 0 ? sprite.w : 50,
                sprite.h > 0 ? sprite.h : 50);
            ctx.globalAlpha = 1.0;
        }
    }

    _drawText(ctx, sprite) {
        var text = sprite.textContent;
        if (text) {
            var fontSize = sprite.fontSize || 12;
            ctx.font = fontSize + 'px serif';
            var fc = sprite.foreColor || 0;
            ctx.fillStyle = '#' + (fc & 0xFFFFFF).toString(16).padStart(6, '0');
            // Simple line splitting
            var lines = text.split(/\r\n|\r|\n/);
            for (var j = 0; j < lines.length; j++) {
                ctx.fillText(lines[j], sprite.x, sprite.y + fontSize + j * (fontSize + 2));
            }
        } else {
            // Placeholder for text without content
            ctx.fillStyle = '#c8c8c8';
            ctx.globalAlpha = 0.5;
            ctx.fillRect(sprite.x, sprite.y,
                sprite.w > 0 ? sprite.w : 50,
                sprite.h > 0 ? sprite.h : 20);
            ctx.globalAlpha = 1.0;
        }
    }

    _drawShape(ctx, sprite) {
        var fc = sprite.foreColor || 0;
        ctx.fillStyle = '#' + (fc & 0xFFFFFF).toString(16).padStart(6, '0');
        ctx.fillRect(sprite.x, sprite.y,
            sprite.w > 0 ? sprite.w : 50,
            sprite.h > 0 ? sprite.h : 50);
    }

    /**
     * Fallback: render raw RGBA pixels.
     */
    _renderPixels(pixels) {
        if (!this.ctx) return;
        var imageData = this.ctx.createImageData(this.stageWidth, this.stageHeight);
        imageData.data.set(new Uint8ClampedArray(pixels));
        this.ctx.putImageData(imageData, 0, 0);
    }

    /**
     * Cache a received bitmap as ImageBitmap.
     */
    _onBitmapData(msg) {
        this.pendingBitmaps.delete(msg.memberId);
        var rgba = new Uint8ClampedArray(msg.rgba);
        var imageData = new ImageData(rgba, msg.width, msg.height);
        var self = this;
        createImageBitmap(imageData).then(function(bmp) {
            self.bitmapCache.set(msg.memberId, bmp);
        });
    }

    // === Network fetch relay ===

    _handleFetchRelay(msg) {
        var self = this;
        var opts = {};
        if (msg.method === 'POST') {
            opts.method = 'POST';
            opts.body = msg.postData;
            opts.headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
        }

        fetch(msg.url, opts)
            .then(function(r) {
                if (!r.ok) throw r.status;
                return r.arrayBuffer();
            })
            .then(function(buf) {
                self.worker.postMessage(
                    { cmd: 'fetchComplete', taskId: msg.taskId, data: buf },
                    [buf]
                );
            })
            .catch(function(e) {
                var status = typeof e === 'number' ? e : 0;
                self.worker.postMessage({ cmd: 'fetchError', taskId: msg.taskId, status: status });
            });
    }

    // === Internal ===

    _sendCmd(cmd, extra) {
        var msg = { cmd: cmd };
        if (extra) {
            for (var k in extra) msg[k] = extra[k];
        }
        this.worker.postMessage(msg);
    }
}

/**
 * Debug Panel Manager
 * Handles all debug UI interactions and data display.
 */
class DebugPanelManager {
    constructor(player) {
        this.player = player;
        this.visible = false;
        this.currentScriptId = null;
        this.currentHandlerIndex = null;
        this.currentSnapshot = null;
        this.scripts = null;
        this.activeTab = 'stack';
    }

    init() {
        var self = this;

        // Wire toolbar buttons
        this._on('debug-step-into', 'click', function() { self.player.debugStepInto(); });
        this._on('debug-step-over', 'click', function() { self.player.debugStepOver(); });
        this._on('debug-step-out', 'click', function() { self.player.debugStepOut(); });
        this._on('debug-continue', 'click', function() { self.player.debugContinue(); });
        this._on('debug-clear-bp', 'click', function() { self.player.clearBreakpoints(); });

        // Script browser
        this._on('script-select', 'change', function() {
            self.onScriptSelected(this.value);
        });
        this._on('handler-select', 'change', function() {
            var parts = this.value.split(':');
            if (parts.length === 2) {
                self.currentHandlerIndex = parseInt(parts[1]);
                self.player.getHandlerBytecode(parseInt(parts[0]), parseInt(parts[1]));
            }
        });
        this._on('script-filter', 'input', function() {
            self.filterScripts(this.value);
        });
        this._on('handler-info-btn', 'click', function() {
            if (self.currentScriptId != null && self.currentHandlerIndex != null) {
                self.player.getHandlerDetails(self.currentScriptId, self.currentHandlerIndex);
            }
        });

        // Tab bar
        var tabBtns = document.querySelectorAll('.tab-btn');
        for (var i = 0; i < tabBtns.length; i++) {
            tabBtns[i].addEventListener('click', function() {
                self.switchTab(this.dataset.tab);
            });
        }

        // Watch controls
        this._on('watch-add-btn', 'click', function() {
            var expr = prompt('Enter watch expression:');
            if (expr) self.player.addWatch(expr);
        });
        this._on('watch-clear-btn', 'click', function() {
            self.player.clearWatches();
        });
    }

    _on(id, event, handler) {
        var el = document.getElementById(id);
        if (el) el.addEventListener(event, handler);
    }

    // === Debug events from worker ===

    onDebugEnabled() {
        this.player.getScriptList();
    }

    onDebugPaused(snapshot) {
        this.currentSnapshot = snapshot;
        this._updateDebugStatus('Paused at ' + (snapshot ? snapshot.handlerName + ' [' + snapshot.instructionOffset + ']' : '?'));
        this._setToolbarEnabled(true);

        if (snapshot) {
            // Navigate to the paused script/handler
            this._navigateToInstruction(snapshot.scriptId, snapshot.handlerName, snapshot.instructionOffset);
            this._updateTabs(snapshot);
        }
    }

    onDebugResumed() {
        this._updateDebugStatus('Running');
        this._setToolbarEnabled(false);
    }

    onScriptList(scripts) {
        this.scripts = scripts;
        var select = document.getElementById('script-select');
        if (!select) return;

        select.innerHTML = '<option value="">-- Select Script --</option>';
        if (scripts) {
            for (var i = 0; i < scripts.length; i++) {
                var s = scripts[i];
                var opt = document.createElement('option');
                opt.value = s.id;
                opt.textContent = '#' + s.id + ' ' + s.displayName;
                select.appendChild(opt);
            }
        }
    }

    onScriptSelected(scriptId) {
        this.currentScriptId = parseInt(scriptId);
        var select = document.getElementById('handler-select');
        if (!select || !this.scripts) return;

        select.innerHTML = '<option value="">-- Select Handler --</option>';
        var script = this.scripts.find(function(s) { return s.id === parseInt(scriptId); });
        if (script && script.handlers) {
            for (var i = 0; i < script.handlers.length; i++) {
                var h = script.handlers[i];
                var opt = document.createElement('option');
                opt.value = scriptId + ':' + h.index;
                opt.textContent = h.name + ' (' + h.instructionCount + ' instrs)';
                select.appendChild(opt);
            }
        }
    }

    onHandlerBytecode(msg) {
        this._renderBytecode(msg.instructions, msg.scriptId);
    }

    onHandlerDetails(details) {
        if (!details) return;
        this._showDetailsModal(details);
    }

    onWatchList(watches) {
        this._renderWatchesTable(watches);
    }

    onDebugSnapshot(snapshot) {
        this.currentSnapshot = snapshot;
        if (snapshot) this._updateTabs(snapshot);
    }

    onBreakpointToggled(msg) {
        // Refresh bytecode to show updated breakpoint state
        if (this.currentScriptId != null && this.currentHandlerIndex != null) {
            this.player.getHandlerBytecode(this.currentScriptId, this.currentHandlerIndex);
        }
    }

    onBreakpointsCleared() {
        if (this.currentScriptId != null && this.currentHandlerIndex != null) {
            this.player.getHandlerBytecode(this.currentScriptId, this.currentHandlerIndex);
        }
    }

    // === UI Rendering ===

    filterScripts(query) {
        var select = document.getElementById('script-select');
        if (!select || !this.scripts) return;
        var q = query.toLowerCase();

        select.innerHTML = '<option value="">-- Select Script --</option>';
        for (var i = 0; i < this.scripts.length; i++) {
            var s = this.scripts[i];
            if (q && s.displayName.toLowerCase().indexOf(q) === -1) continue;
            var opt = document.createElement('option');
            opt.value = s.id;
            opt.textContent = '#' + s.id + ' ' + s.displayName;
            select.appendChild(opt);
        }
    }

    _renderBytecode(instructions, scriptId) {
        var container = document.getElementById('bytecode-list');
        if (!container) return;
        container.innerHTML = '';

        if (!instructions || instructions.length === 0) {
            container.innerHTML = '<div class="bytecode-empty">No instructions</div>';
            return;
        }

        var self = this;
        for (var i = 0; i < instructions.length; i++) {
            var instr = instructions[i];
            var row = document.createElement('div');
            row.className = 'bytecode-row';
            row.dataset.index = i;
            row.dataset.offset = instr.offset;

            // Current instruction marker
            if (this.currentSnapshot && this.currentSnapshot.scriptId === scriptId &&
                this.currentSnapshot.instructionOffset === instr.offset) {
                row.classList.add('current');
            }

            // Breakpoint gutter
            var gutter = document.createElement('span');
            gutter.className = 'bp-gutter';
            if (instr.hasBreakpoint) {
                gutter.classList.add(instr.bpEnabled !== false ? 'active' : 'disabled');
                gutter.textContent = '\u25CF';
            } else {
                gutter.textContent = '\u00A0';
            }
            gutter.addEventListener('click', (function(sid, off) {
                return function(e) {
                    e.stopPropagation();
                    self.player.toggleBreakpoint(sid, off);
                };
            })(scriptId, instr.offset));
            row.appendChild(gutter);

            // Current marker
            var marker = document.createElement('span');
            marker.className = 'current-marker';
            marker.textContent = row.classList.contains('current') ? '\u25B6' : '\u00A0';
            row.appendChild(marker);

            // Offset
            var offsetSpan = document.createElement('span');
            offsetSpan.className = 'offset';
            offsetSpan.textContent = '[' + String(instr.offset).padStart(3, ' ') + ']';
            row.appendChild(offsetSpan);

            // Opcode
            var opcodeSpan = document.createElement('span');
            opcodeSpan.className = 'opcode';
            opcodeSpan.textContent = ' ' + instr.opcode;
            row.appendChild(opcodeSpan);

            // Argument (only for opcodes with arguments)
            if (instr.argument !== 0 || instr.opcode.indexOf('PUSH') >= 0 || instr.opcode.indexOf('CALL') >= 0) {
                var argSpan = document.createElement('span');
                argSpan.className = 'arg';
                argSpan.textContent = ' ' + instr.argument;
                row.appendChild(argSpan);
            }

            // Annotation
            if (instr.annotation) {
                var annoSpan = document.createElement('span');
                annoSpan.className = 'annotation';
                if (instr.annotation.indexOf('\u2192') === 0) {
                    annoSpan.classList.add('navigable');
                }
                annoSpan.textContent = ' ' + instr.annotation;
                row.appendChild(annoSpan);
            }

            container.appendChild(row);
        }

        // Scroll to current instruction
        var current = container.querySelector('.bytecode-row.current');
        if (current) {
            current.scrollIntoView({ block: 'center', behavior: 'smooth' });
        }
    }

    _updateTabs(snapshot) {
        this._renderStackTable(snapshot.stack);
        this._renderVarsTable(snapshot.locals, 'tab-locals');
        this._renderVarsTable(snapshot.globals, 'tab-globals');
        this._renderWatchesTable(snapshot.watches);
        this._renderCallStack(snapshot.callStack);
    }

    _renderStackTable(stack) {
        var tbody = document.querySelector('#tab-stack tbody');
        if (!tbody) return;
        tbody.innerHTML = '';
        if (!stack) return;

        for (var i = stack.length - 1; i >= 0; i--) {
            var item = stack[i];
            var tr = document.createElement('tr');
            tr.innerHTML = '<td>' + i + '</td><td>' + this._esc(item.type) + '</td><td title="' +
                this._esc(item.value) + '">' + this._esc(item.value) + '</td>';
            tbody.appendChild(tr);
        }
    }

    _renderVarsTable(vars, containerId) {
        var tbody = document.querySelector('#' + containerId + ' tbody');
        if (!tbody) return;
        tbody.innerHTML = '';
        if (!vars) return;

        for (var i = 0; i < vars.length; i++) {
            var v = vars[i];
            var tr = document.createElement('tr');
            tr.innerHTML = '<td>' + this._esc(v.name) + '</td><td>' + this._esc(v.type) + '</td><td title="' +
                this._esc(v.value) + '">' + this._esc(v.value) + '</td>';
            tbody.appendChild(tr);
        }
    }

    _renderWatchesTable(watches) {
        var tbody = document.querySelector('#tab-watches tbody');
        if (!tbody) return;
        tbody.innerHTML = '';
        if (!watches) return;

        var self = this;
        for (var i = 0; i < watches.length; i++) {
            var w = watches[i];
            var tr = document.createElement('tr');
            tr.className = w.hasError ? 'watch-error' : '';
            tr.innerHTML = '<td>' + this._esc(w.expression) + '</td><td>' + this._esc(w.type) +
                '</td><td>' + this._esc(w.value) + '</td><td><button class="watch-remove-btn" data-id="' +
                this._esc(w.id) + '">\u00D7</button></td>';
            tbody.appendChild(tr);
        }

        // Wire remove buttons
        var removeBtns = tbody.querySelectorAll('.watch-remove-btn');
        for (var j = 0; j < removeBtns.length; j++) {
            removeBtns[j].addEventListener('click', function() {
                self.player.removeWatch(this.dataset.id);
            });
        }
    }

    _renderCallStack(callStack) {
        var el = document.getElementById('tab-callstack-content');
        if (!el) return;
        if (!callStack || callStack.length === 0) {
            el.textContent = '(empty)';
            return;
        }

        var lines = [];
        for (var i = callStack.length - 1; i >= 0; i--) {
            var f = callStack[i];
            lines.push('#' + (callStack.length - i) + ' ' + f.handlerName + ' in ' + f.scriptName);
        }
        el.textContent = lines.join('\n');
    }

    _navigateToInstruction(scriptId, handlerName, offset) {
        // Select the script in the dropdown
        var scriptSelect = document.getElementById('script-select');
        if (scriptSelect) {
            scriptSelect.value = scriptId;
            this.onScriptSelected(scriptId);
        }

        // Find and select the handler
        if (this.scripts) {
            var script = this.scripts.find(function(s) { return s.id === scriptId; });
            if (script && script.handlers) {
                for (var i = 0; i < script.handlers.length; i++) {
                    if (script.handlers[i].name === handlerName) {
                        var handlerSelect = document.getElementById('handler-select');
                        if (handlerSelect) {
                            handlerSelect.value = scriptId + ':' + i;
                            this.currentHandlerIndex = i;
                        }
                        this.player.getHandlerBytecode(scriptId, i);
                        break;
                    }
                }
            }
        }
    }

    _showDetailsModal(details) {
        var overlay = document.getElementById('details-modal');
        if (!overlay) return;

        var content = '<h3>' + this._esc(details.name) + '</h3>';
        content += '<p>Script: ' + this._esc(details.scriptName) + ' (ID: ' + details.scriptId + ')</p>';
        content += '<p>Args: ' + details.argCount + ' | Locals: ' + details.localCount +
                   ' | Globals: ' + details.globalsCount + '</p>';
        content += '<p>Bytecode: ' + details.bytecodeLength + ' bytes, ' + details.instructionCount + ' instructions</p>';

        if (details.argNames && details.argNames.length > 0) {
            content += '<p>Arguments: ' + details.argNames.join(', ') + '</p>';
        }
        if (details.localNames && details.localNames.length > 0) {
            content += '<p>Locals: ' + details.localNames.join(', ') + '</p>';
        }
        if (details.literals && details.literals.length > 0) {
            content += '<p>Literals:</p><ul>';
            for (var i = 0; i < details.literals.length; i++) {
                var lit = details.literals[i];
                content += '<li>[' + i + '] type=' + lit.type + ' value=' + this._esc(lit.value) + '</li>';
            }
            content += '</ul>';
        }

        document.getElementById('details-modal-content').innerHTML = content;
        overlay.style.display = 'flex';
    }

    toggle() {
        this.visible = !this.visible;
        var panel = document.getElementById('debug-panel');
        if (panel) {
            panel.style.display = this.visible ? 'flex' : 'none';
        }
        var wrapper = document.getElementById('main-content');
        if (wrapper) {
            wrapper.classList.toggle('debug-open', this.visible);
        }

        if (this.visible && !this.scripts) {
            this.player.getScriptList();
        }
    }

    switchTab(tabName) {
        this.activeTab = tabName;
        var tabs = document.querySelectorAll('.tab-content');
        for (var i = 0; i < tabs.length; i++) {
            tabs[i].style.display = tabs[i].id === 'tab-' + tabName ? 'block' : 'none';
        }
        var btns = document.querySelectorAll('.tab-btn');
        for (var i = 0; i < btns.length; i++) {
            btns[i].classList.toggle('active', btns[i].dataset.tab === tabName);
        }
    }

    _updateDebugStatus(text) {
        var el = document.getElementById('debug-status');
        if (el) el.textContent = 'Status: ' + text;
    }

    _setToolbarEnabled(paused) {
        var ids = ['debug-step-into', 'debug-step-over', 'debug-step-out', 'debug-continue'];
        for (var i = 0; i < ids.length; i++) {
            var btn = document.getElementById(ids[i]);
            if (btn) btn.disabled = !paused;
        }
    }

    _esc(s) {
        if (s == null) return '';
        return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }
}
