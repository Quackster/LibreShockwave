package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.debug.Breakpoint;
import com.libreshockwave.player.debug.DebugController;
import com.libreshockwave.player.debug.DebugSnapshot;
import com.libreshockwave.player.debug.DebugStateListener;
import com.libreshockwave.player.debug.WatchExpression;
import com.libreshockwave.player.debug.ui.BreakpointPropertiesDialog;
import com.libreshockwave.player.debug.ui.BytecodeListPanel;
import com.libreshockwave.player.debug.ui.DatumDetailsDialog;
import com.libreshockwave.player.debug.ui.DebugKeyboardHandler;
import com.libreshockwave.player.debug.ui.DebugToolbar;
import com.libreshockwave.player.debug.ui.HandlerDetailsDialog;
import com.libreshockwave.player.debug.ui.HandlerItem;
import com.libreshockwave.player.debug.ui.HandlerNavigator;
import com.libreshockwave.player.debug.ui.ScriptBrowserPanel;
import com.libreshockwave.player.debug.ui.ScriptItem;
import com.libreshockwave.player.debug.ui.StateInspectionTabs;
import com.libreshockwave.player.debug.ui.WatchesPanel;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.TraceListener;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Bytecode-level debugger panel for the Lingo VM.
 * Provides step/continue controls, breakpoint management, and state inspection.
 */
public class BytecodeDebuggerPanel extends JPanel implements DebugStateListener, TraceListener {

    // Controller and player
    private DebugController controller;
    private Player player;

    // UI Components
    private final DebugToolbar toolbar;
    private final ScriptBrowserPanel scriptBrowser;
    private final BytecodeListPanel bytecodePanel;
    private final StateInspectionTabs stateTabs;
    private final JLabel statusLabel;
    private final JLabel handlerLabel;

    // Handler navigator
    private HandlerNavigator navigator;

    // Current handler info (for building instruction list)
    private volatile TraceListener.HandlerInfo currentHandlerInfo;

    // Track if we're in "browse mode" (user selected a handler) vs "trace mode" (following execution)
    private boolean browseMode = false;
    private ScriptChunk browseScript = null;
    private ScriptChunk.Handler browseHandler = null;

    public BytecodeDebuggerPanel() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(500, 600));

        // Create toolbar
        toolbar = new DebugToolbar();
        add(toolbar, BorderLayout.NORTH);

        // Create main content panel
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Status panel
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusLabel = new JLabel("Status: Running");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        statusPanel.add(statusLabel, BorderLayout.NORTH);

        handlerLabel = new JLabel("Handler: -");
        handlerLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        statusPanel.add(handlerLabel, BorderLayout.SOUTH);

        mainPanel.add(statusPanel, BorderLayout.NORTH);

        // Split pane
        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        mainSplit.setResizeWeight(0.5);

        // Top: Bytecode panel with script browser
        JPanel topPanel = new JPanel(new BorderLayout());

        scriptBrowser = new ScriptBrowserPanel();
        scriptBrowser.setListener(new ScriptBrowserPanel.ScriptBrowserListener() {
            @Override
            public void onScriptSelected(ScriptItem script) {
                // Handler will be selected automatically
            }

            @Override
            public void onHandlerSelected(HandlerItem handler) {
                browseMode = true;
                browseScript = handler.getScript();
                browseHandler = handler.getHandler();
                bytecodePanel.setCurrentScriptId(browseScript.id());
                bytecodePanel.loadHandlerBytecode(browseScript, browseHandler);
                handlerLabel.setText("Handler: " + browseScript.getHandlerName(browseHandler) +
                    " (" + browseScript.getDisplayName() + ")");
            }
        });

        scriptBrowser.setViewHandlerDetailsAction(() -> {
            HandlerItem selected = scriptBrowser.getSelectedHandler();
            if (selected != null) {
                HandlerDetailsDialog.show(this, selected.getScript(), selected.getHandler(),
                    scriptBrowser.getDirectorFile());
            }
        });

        topPanel.add(scriptBrowser, BorderLayout.NORTH);

        bytecodePanel = new BytecodeListPanel();
        bytecodePanel.setParentComponent(this);
        bytecodePanel.setListener(new BytecodeListPanel.BytecodeListListener() {
            @Override
            public void onBreakpointToggleRequested(int offset) {
                bytecodePanel.refreshBreakpointMarkers();
            }

            @Override
            public void onNavigateToHandler(String handlerName) {
                navigateToHandler(handlerName);
            }

            @Override
            public void onShowBreakpointProperties(int offset) {
                if (controller != null && bytecodePanel.getCurrentScriptId() >= 0) {
                    BreakpointPropertiesDialog.show(BytecodeDebuggerPanel.this, controller,
                        bytecodePanel.getCurrentScriptId(), offset);
                    bytecodePanel.refreshBreakpointMarkers();
                }
            }

            @Override
            public void onShowHandlerDetails(String handlerName) {
                HandlerDetailsDialog.show(BytecodeDebuggerPanel.this, scriptBrowser.getAllScripts(),
                    scriptBrowser.getDirectorFile(), handlerName);
            }
        });

        topPanel.add(bytecodePanel, BorderLayout.CENTER);
        mainSplit.setTopComponent(topPanel);

        // Bottom: State inspection tabs
        stateTabs = new StateInspectionTabs();
        stateTabs.setDatumClickListener((datum, title) ->
            DatumDetailsDialog.show(this, datum, title));

        // Configure watches panel
        stateTabs.getWatchesPanel().setListener(new WatchesPanel.WatchesPanelListener() {
            @Override
            public void onAddWatch(String expression) {
                if (controller != null) {
                    controller.addWatchExpression(expression);
                    refreshWatches();
                }
            }

            @Override
            public void onRemoveWatch(String watchId) {
                if (controller != null) {
                    controller.removeWatchExpression(watchId);
                    refreshWatches();
                }
            }

            @Override
            public void onEditWatch(String watchId, String newExpression) {
                if (controller != null) {
                    controller.updateWatchExpression(watchId, newExpression);
                    refreshWatches();
                }
            }

            @Override
            public void onClearWatches() {
                if (controller != null) {
                    controller.clearWatchExpressions();
                    refreshWatches();
                }
            }
        });

        mainSplit.setBottomComponent(stateTabs);

        mainPanel.add(mainSplit, BorderLayout.CENTER);
        add(mainPanel, BorderLayout.CENTER);
    }

    private void refreshWatches() {
        if (controller != null) {
            List<WatchExpression> watches = controller.evaluateWatchExpressions();
            stateTabs.setWatches(watches);
        }
    }

    private void navigateToHandler(String handlerName) {
        if (navigator == null) {
            navigator = new HandlerNavigator(scriptBrowser.getAllScripts());
        }

        HandlerNavigator.HandlerLocation location = navigator.findHandler(handlerName);
        if (location.found()) {
            browseMode = true;
            browseScript = location.script();
            browseHandler = location.handler();
            bytecodePanel.setCurrentScriptId(location.scriptId());

            scriptBrowser.selectScriptSilently(location.script());
            scriptBrowser.selectHandlerSilently(location.script(), location.handler());
            bytecodePanel.loadHandlerBytecode(location.script(), location.handler());
            handlerLabel.setText("Handler: " + location.handlerName() +
                " (" + location.script().getDisplayName() + ")");
        } else {
            statusLabel.setText("Handler '" + handlerName + "' not found");
        }
    }

    // Public API methods

    /**
     * Set the debug controller.
     */
    public void setController(DebugController controller) {
        this.controller = controller;
        toolbar.setController(controller);
        bytecodePanel.setController(controller);
    }

    /**
     * Set the Player reference for preloading casts.
     */
    public void setPlayer(Player player) {
        this.player = player;
    }

    /**
     * Set the DirectorFile and populate the script browser.
     */
    public void setDirectorFile(DirectorFile file) {
        setDirectorFile(file, null);
    }

    /**
     * Set the DirectorFile and CastLibManager, populating the script browser
     * with scripts from all cast libraries.
     */
    public void setDirectorFile(DirectorFile file, CastLibManager castLibManager) {
        // Clear bytecode panel BEFORE populating script browser, since the browser
        // will auto-select the first handler which triggers bytecode loading
        bytecodePanel.clear();
        scriptBrowser.setDirectorFile(file, castLibManager);
        navigator = file != null ? new HandlerNavigator(scriptBrowser.getAllScripts()) : null;
        bytecodePanel.setNavigator(navigator);
    }

    /**
     * Refresh the script list from the current DirectorFile and CastLibManager.
     */
    public void refreshScriptList() {
        scriptBrowser.refreshScriptList();
        navigator = new HandlerNavigator(scriptBrowser.getAllScripts());
        bytecodePanel.setNavigator(navigator);
    }

    /**
     * Clear the bytecode display and reset browse mode.
     */
    public void clear() {
        bytecodePanel.clear();
        browseMode = false;
        browseScript = null;
        browseHandler = null;
        statusLabel.setText("Status: Running");
        handlerLabel.setText("Handler: -");
        scriptBrowser.clearFilters();
    }

    /**
     * Register keyboard shortcuts on the given root pane.
     */
    public void registerKeyboardShortcuts(JRootPane rootPane) {
        DebugKeyboardHandler.registerShortcuts(rootPane, controller);
    }

    // DebugStateListener implementation

    @Override
    public void onPaused(DebugSnapshot snapshot) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Status: PAUSED at offset " + snapshot.instructionOffset());
            handlerLabel.setText("Handler: " + snapshot.handlerName() + " (" + snapshot.scriptName() + ")");

            bytecodePanel.setCurrentScriptId(snapshot.scriptId());

            // Load the correct bytecode for the paused handler
            if (navigator != null) {
                HandlerNavigator.HandlerLocation location =
                    navigator.findHandlerInScript(snapshot.scriptId(), snapshot.handlerName());
                if (location.found()) {
                    browseMode = false;
                    bytecodePanel.loadHandlerBytecode(location.script(), location.handler());
                    scriptBrowser.selectScriptSilently(location.script());
                    scriptBrowser.selectHandlerSilently(location.script(), location.handler());
                }
            }

            bytecodePanel.highlightCurrentInstruction(snapshot.instructionIndex());

            stateTabs.setStack(snapshot.stack());
            stateTabs.setLocals(snapshot.locals());
            stateTabs.setGlobals(snapshot.globals());

            if (snapshot.watchResults() != null) {
                stateTabs.setWatches(snapshot.watchResults());
            } else {
                refreshWatches();
            }

            toolbar.setStepButtonsEnabled(true);
        });
    }

    @Override
    public void onResumed() {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Status: Running");
            toolbar.setStepButtonsEnabled(false);
            bytecodePanel.getBytecodeList().clearSelection();
        });
    }

    @Override
    public void onBreakpointsChanged() {
        SwingUtilities.invokeLater(() -> bytecodePanel.refreshBreakpointMarkers());
    }

    @Override
    public void onLogPointHit(Breakpoint bp, String message) {
        System.out.println(String.format("[LogPoint] Script %d, offset %d: %s",
            bp.scriptId(), bp.offset(), message));
    }

    @Override
    public void onWatchExpressionsChanged() {
        refreshWatches();
    }

    // TraceListener implementation

    @Override
    public void onHandlerEnter(HandlerInfo info) {
        currentHandlerInfo = info;
        bytecodePanel.setCurrentScriptId(info.scriptId());

        SwingUtilities.invokeLater(() -> {
            browseMode = false;

            if (navigator != null) {
                HandlerNavigator.HandlerLocation location =
                    navigator.findHandlerInScript(info.scriptId(), info.handlerName());
                if (location.found()) {
                    bytecodePanel.loadHandlerBytecode(location.script(), location.handler());
                    scriptBrowser.selectScriptSilently(location.script());
                    scriptBrowser.selectHandlerSilently(location.script(), location.handler());
                    return;
                }
            }

            bytecodePanel.clear();
            handlerLabel.setText("Handler: " + info.handlerName() + " (" + info.scriptDisplayName() + ")");
        });
    }

    @Override
    public void onHandlerExit(HandlerInfo info, Datum returnValue) {
        // Optional: could show return value
    }

    @Override
    public void onInstruction(InstructionInfo info) {
        SwingUtilities.invokeLater(() -> {
            bytecodePanel.highlightCurrentInstruction(info.bytecodeIndex());
            stateTabs.setStack(info.stackSnapshot());
        });
    }
}
