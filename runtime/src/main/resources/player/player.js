/**
 * LibreShockwave Player UI Controller
 * Handles UI interactions and stage rendering
 * Works with libreshockwave-api.js (WASM or stub mode)
 */
(function() {
    'use strict';

    // DOM elements
    const elements = {
        stage: document.getElementById('stage'),
        loadingOverlay: document.getElementById('loading-overlay'),
        movieName: document.getElementById('movie-name'),
        movieSize: document.getElementById('movie-size'),
        btnLoad: document.getElementById('btn-load'),
        btnLoadUrl: document.getElementById('btn-load-url'),
        fileInput: document.getElementById('file-input'),
        urlInput: document.getElementById('url-input'),
        btnPlay: document.getElementById('btn-play'),
        btnStop: document.getElementById('btn-stop'),
        btnPause: document.getElementById('btn-pause'),
        btnPrev: document.getElementById('btn-prev'),
        btnNext: document.getElementById('btn-next'),
        frameInput: document.getElementById('frame-input'),
        frameDisplay: document.getElementById('frame-display'),
        tempoDisplay: document.getElementById('tempo-display'),
        status: document.getElementById('status'),
        playState: document.getElementById('play-state'),
        spriteList: document.getElementById('sprite-list'),
        consoleOutput: document.getElementById('console-output'),
        btnClearConsole: document.getElementById('btn-clear-console'),
        debugToggle: document.getElementById('debug-toggle')
    };

    // Canvas context
    const ctx = elements.stage.getContext('2d');

    // Bitmap cache for sprites
    const bitmapCache = new Map();

    // Playback timer
    let playbackTimer = null;

    /**
     * Initialize the player
     */
    function init() {
        setupEventListeners();
        setupApiListeners();

        // Wait for LibreShockwave to initialize
        LibreShockwave.on('ready', onReady);

        // Initialize the WASM runtime
        LibreShockwave.init();
    }

    /**
     * Called when LibreShockwave API is ready
     */
    function onReady(info) {
        if (info.mode === 'wasm') {
            log('info', 'LibreShockwave WASM runtime ready');

            // Apply saved debug mode
            const savedDebugMode = localStorage.getItem('libreshockwave-debug') === 'true';
            if (savedDebugMode) {
                LibreShockwave.setDebugMode(true);
            }
        } else {
            log('warn', 'Running in stub mode - WASM not available');
        }
        setStatus('Ready - Enter a URL or upload a file');
    }

    /**
     * Set up DOM event listeners
     */
    function setupEventListeners() {
        // Load button - triggers file input
        elements.btnLoad.addEventListener('click', () => {
            elements.fileInput.click();
        });

        // File input change
        elements.fileInput.addEventListener('change', async (e) => {
            const file = e.target.files[0];
            if (file) {
                await loadMovieFromFile(file);
            }
        });

        // Load from URL button
        elements.btnLoadUrl.addEventListener('click', async () => {
            const url = elements.urlInput.value.trim();
            if (url) {
                await loadMovieFromUrl(url);
            }
        });

        // URL input enter key
        elements.urlInput.addEventListener('keypress', async (e) => {
            if (e.key === 'Enter') {
                const url = elements.urlInput.value.trim();
                if (url) {
                    await loadMovieFromUrl(url);
                }
            }
        });

        // Playback controls
        elements.btnPlay.addEventListener('click', () => {
            LibreShockwave.play();
            startPlayback();
        });

        elements.btnStop.addEventListener('click', () => {
            LibreShockwave.stop();
            stopPlayback();
            renderFrame();
        });

        elements.btnPause.addEventListener('click', () => {
            LibreShockwave.pause();
            stopPlayback();
        });

        elements.btnPrev.addEventListener('click', () => {
            LibreShockwave.prevFrame();
            renderFrame();
        });

        elements.btnNext.addEventListener('click', () => {
            LibreShockwave.nextFrame();
            renderFrame();
        });

        // Frame input
        elements.frameInput.addEventListener('change', () => {
            const frame = parseInt(elements.frameInput.value, 10);
            if (!isNaN(frame)) {
                LibreShockwave.goToFrame(frame);
                renderFrame();
            }
        });

        // Clear console
        elements.btnClearConsole.addEventListener('click', () => {
            elements.consoleOutput.innerHTML = '';
        });

        // Debug toggle - load saved state
        const savedDebugMode = localStorage.getItem('libreshockwave-debug') === 'true';
        elements.debugToggle.checked = savedDebugMode;

        elements.debugToggle.addEventListener('change', () => {
            const enabled = elements.debugToggle.checked;
            localStorage.setItem('libreshockwave-debug', enabled ? 'true' : 'false');
            try {
                LibreShockwave.setDebugMode(enabled);
            } catch (e) {
                log('warn', 'Debug mode requires WASM to be initialized');
            }
        });
    }

    /**
     * Set up API event listeners
     */
    function setupApiListeners() {
        LibreShockwave.on('stateChange', updateUI);
        LibreShockwave.on('frameChange', onFrameChange);
        LibreShockwave.on('spriteUpdate', renderSprites);
        LibreShockwave.on('log', onLog);
        LibreShockwave.on('error', onError);
        LibreShockwave.on('render', renderFrame);
    }

    /**
     * Load a movie from URL
     */
    async function loadMovieFromUrl(url) {
        showLoading(true);
        setStatus('Loading movie...');

        try {
            await LibreShockwave.loadMovie(url);
            onMovieLoaded();
        } catch (error) {
            setStatus('Failed to load movie');
            log('error', error.message);
        } finally {
            showLoading(false);
        }
    }

    /**
     * Load a movie from file
     */
    async function loadMovieFromFile(file) {
        showLoading(true);
        setStatus('Loading movie...');

        try {
            const data = await file.arrayBuffer();
            await LibreShockwave.loadMovieFromData(data, file.name);
            onMovieLoaded();
        } catch (error) {
            setStatus('Failed to load movie');
            log('error', error.message);
        } finally {
            showLoading(false);
        }
    }

    /**
     * Called when a movie is successfully loaded
     */
    function onMovieLoaded() {
        const state = LibreShockwave.getState();

        // Update stage size
        elements.stage.width = state.stageWidth;
        elements.stage.height = state.stageHeight;

        // Update UI
        elements.movieName.textContent = state.movieName;
        elements.movieSize.textContent = `${state.stageWidth}x${state.stageHeight}`;

        // Enable controls
        setControlsEnabled(true);

        // Clear bitmap cache
        bitmapCache.clear();

        // Poll debug messages from movie loading (startMovie handlers etc)
        pollDebugMessages();

        // Render initial frame
        renderFrame();

        setStatus('Ready');
    }

    /**
     * Update UI based on player state
     */
    function updateUI(state) {
        // Update frame display
        elements.frameDisplay.textContent = `${state.currentFrame} / ${state.lastFrame}`;
        elements.frameInput.value = state.currentFrame;
        elements.frameInput.max = state.lastFrame;
        elements.tempoDisplay.textContent = `@ ${state.tempo} fps`;

        // Update play state indicator
        elements.playState.className = '';
        if (state.playing) {
            elements.playState.textContent = 'Playing';
            elements.playState.classList.add('state-playing');
        } else if (state.paused) {
            elements.playState.textContent = 'Paused';
            elements.playState.classList.add('state-paused');
        } else {
            elements.playState.textContent = 'Stopped';
            elements.playState.classList.add('state-stopped');
        }

        // Update button states
        elements.btnPlay.disabled = !state.loaded || state.playing;
        elements.btnPause.disabled = !state.loaded || !state.playing;
        elements.btnStop.disabled = !state.loaded;
        elements.btnPrev.disabled = !state.loaded;
        elements.btnNext.disabled = !state.loaded;
    }

    /**
     * Handle frame change
     */
    function onFrameChange(data) {
        renderFrame();
    }

    /**
     * Render the current frame
     */
    function renderFrame() {
        // Poll debug messages before rendering
        pollDebugMessages();

        // Clear the stage with white background
        ctx.fillStyle = '#FFFFFF';
        ctx.fillRect(0, 0, elements.stage.width, elements.stage.height);

        // Get and render sprites
        const sprites = LibreShockwave.getSprites();
        log('debug', `Frame ${LibreShockwave.getCurrentFrame()}: ${sprites.length} sprites`);
        renderSprites(sprites);
    }

    /**
     * Render sprites to the stage
     */
    function renderSprites(sprites) {
        // Update sprite list panel
        updateSpriteList(sprites);

        // Sort sprites by channel (rendering order)
        const sortedSprites = [...sprites].sort((a, b) => a.channel - b.channel);

        // Render each sprite
        for (const sprite of sortedSprites) {
            if (!sprite.visible) continue;
            renderSprite(sprite);
        }
    }

    /**
     * Render a single sprite
     */
    function renderSprite(sprite) {
        if (!sprite.member || sprite.member.memberNum <= 0) {
            // No member - draw placeholder
            log('debug', `  Sprite ch=${sprite.channel}: no member (memberNum=${sprite.member?.memberNum})`);
            drawPlaceholder(sprite);
            return;
        }

        const { castLib, memberNum } = sprite.member;
        const cacheKey = `${castLib}:${memberNum}`;

        // Try to get bitmap from cache
        let bitmap = bitmapCache.get(cacheKey);

        if (!bitmap) {
            // Try to get bitmap from WASM
            log('debug', `  Sprite ch=${sprite.channel}: fetching bitmap ${cacheKey}...`);
            const imageData = LibreShockwave.getBitmap(castLib, memberNum);
            if (imageData) {
                // Convert ImageData to ImageBitmap for better rendering
                // For now, store the ImageData directly
                bitmap = imageData;
                bitmapCache.set(cacheKey, bitmap);
                log('info', `  Sprite ch=${sprite.channel}: loaded bitmap ${cacheKey} (${imageData.width}x${imageData.height})`);
            } else {
                log('warn', `  Sprite ch=${sprite.channel}: failed to get bitmap ${cacheKey}`);
            }
        }

        if (bitmap instanceof ImageData) {
            // Render ImageData using a temporary canvas for proper positioning
            // This handles negative coordinates and alpha blending correctly
            const tempCanvas = document.createElement('canvas');
            tempCanvas.width = bitmap.width;
            tempCanvas.height = bitmap.height;
            const tempCtx = tempCanvas.getContext('2d');
            tempCtx.putImageData(bitmap, 0, 0);

            // Draw with proper positioning and blend
            ctx.globalAlpha = sprite.blend / 100;
            ctx.drawImage(tempCanvas, sprite.locH, sprite.locV);
            ctx.globalAlpha = 1.0;
        } else if (bitmap instanceof HTMLImageElement || bitmap instanceof HTMLCanvasElement) {
            // Render image/canvas element
            ctx.globalAlpha = sprite.blend / 100;
            ctx.drawImage(bitmap, sprite.locH, sprite.locV, sprite.width, sprite.height);
            ctx.globalAlpha = 1.0;
        } else {
            // Draw placeholder for sprites without bitmaps
            log('debug', `  Sprite ch=${sprite.channel}: drawing placeholder (no bitmap in cache)`);
            drawPlaceholder(sprite);
        }
    }

    /**
     * Draw a placeholder rectangle for a sprite
     */
    function drawPlaceholder(sprite) {
        const w = sprite.width || 32;
        const h = sprite.height || 32;

        // Draw filled rectangle with transparency
        ctx.fillStyle = 'rgba(0, 136, 255, 0.2)';
        ctx.fillRect(sprite.locH, sprite.locV, w, h);

        // Draw border
        ctx.strokeStyle = '#0088FF';
        ctx.lineWidth = 1;
        ctx.strokeRect(sprite.locH, sprite.locV, w, h);

        // Draw channel number
        ctx.fillStyle = '#0088FF';
        ctx.font = '10px monospace';
        ctx.fillText(`#${sprite.channel}`, sprite.locH + 2, sprite.locV + 10);

        // Draw member reference if available
        if (sprite.member) {
            ctx.fillText(`${sprite.member.castLib}:${sprite.member.memberNum}`,
                sprite.locH + 2, sprite.locV + 20);
        }
    }

    /**
     * Update the sprite list panel
     */
    function updateSpriteList(sprites) {
        const html = sprites
            .filter(s => s.visible)
            .map(s => {
                const memberInfo = s.member
                    ? `member(${s.member.castLib}:${s.member.memberNum})`
                    : 'no member';
                return `
                    <div class="sprite-item">
                        <span class="sprite-channel">#${s.channel}</span>
                        <span class="sprite-member">${memberInfo}</span>
                        <span class="sprite-pos">(${s.locH}, ${s.locV})</span>
                    </div>
                `;
            })
            .join('');

        elements.spriteList.innerHTML = html || '<div class="sprite-item">No visible sprites</div>';
    }

    /**
     * Start playback loop
     */
    function startPlayback() {
        stopPlayback();
        const state = LibreShockwave.getState();
        const interval = Math.floor(1000 / state.tempo);

        playbackTimer = setInterval(() => {
            LibreShockwave.tick();
            pollDebugMessages();
            renderFrame();
        }, interval);
    }

    /**
     * Poll and display debug messages from the VM
     */
    function pollDebugMessages() {
        if (!LibreShockwave.isDebugMode()) return;

        try {
            const messages = LibreShockwave.pollDebugMessages();
            for (const msg of messages) {
                // Add to console with special debug styling
                const entry = document.createElement('div');
                entry.className = 'console-line debug';
                entry.textContent = msg;
                elements.consoleOutput.appendChild(entry);
            }

            // Auto-scroll if there were new messages
            if (messages.length > 0) {
                elements.consoleOutput.scrollTop = elements.consoleOutput.scrollHeight;
            }
        } catch (e) {
            // Ignore errors when polling
        }
    }

    /**
     * Stop playback loop
     */
    function stopPlayback() {
        if (playbackTimer) {
            clearInterval(playbackTimer);
            playbackTimer = null;
        }
    }

    /**
     * Show/hide loading overlay
     */
    function showLoading(show) {
        elements.loadingOverlay.classList.toggle('hidden', !show);
    }

    /**
     * Set status bar text
     */
    function setStatus(text) {
        elements.status.textContent = text;
    }

    /**
     * Enable/disable playback controls
     */
    function setControlsEnabled(enabled) {
        elements.btnPlay.disabled = !enabled;
        elements.btnStop.disabled = !enabled;
        elements.btnPause.disabled = !enabled;
        elements.btnPrev.disabled = !enabled;
        elements.btnNext.disabled = !enabled;
        elements.frameInput.disabled = !enabled;
    }

    /**
     * Log to console panel
     */
    function log(level, message) {
        const line = document.createElement('div');
        line.className = `console-line ${level}`;
        line.textContent = `[${new Date().toLocaleTimeString()}] ${message}`;
        elements.consoleOutput.appendChild(line);
        elements.consoleOutput.scrollTop = elements.consoleOutput.scrollHeight;
    }

    /**
     * Handle log events from API
     */
    function onLog(data) {
        log(data.level, data.message);
    }

    /**
     * Handle error events from API
     */
    function onError(data) {
        log('error', `Error: ${data.message}`);
        setStatus(`Error: ${data.message}`);
    }

    // Initialize when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
