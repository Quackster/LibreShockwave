package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.player.cast.CastLib;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.debug.DebugController;
import com.libreshockwave.player.debug.DebugSnapshot;
import com.libreshockwave.player.debug.DebugStateListener;
import com.libreshockwave.player.format.DatumFormatter;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.TraceListener;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * Bytecode-level debugger panel for the Lingo VM.
 * Provides step/continue controls, breakpoint management, and state inspection.
 */
public class BytecodeDebuggerPanel extends JPanel implements DebugStateListener, TraceListener {

    // Controller
    private DebugController controller;

    // Player reference for preloading casts
    private Player player;

    // Director file for script browsing
    private DirectorFile directorFile;
    private List<ScriptChunk> allScripts = new ArrayList<>();

    // UI Components - Script browser
    private JComboBox<ScriptItem> scriptCombo;
    private JComboBox<HandlerItem> handlerCombo;
    private DefaultComboBoxModel<ScriptItem> scriptModel;
    private DefaultComboBoxModel<HandlerItem> handlerModel;

    // UI Components - Bytecode display
    private JList<InstructionDisplayItem> bytecodeList;
    private DefaultListModel<InstructionDisplayItem> bytecodeModel;
    private JTable stackTable;
    private StackTableModel stackTableModel;
    private JTable localsTable;
    private LocalsTableModel localsTableModel;
    private JTable globalsTable;
    private GlobalsTableModel globalsTableModel;
    private JLabel statusLabel;
    private JLabel handlerLabel;

    // Toolbar buttons
    private JButton stepIntoBtn;
    private JButton stepOverBtn;
    private JButton stepOutBtn;
    private JButton continueBtn;
    private JButton pauseBtn;

    // Current handler info (for building instruction list)
    private volatile TraceListener.HandlerInfo currentHandlerInfo;
    private final List<InstructionDisplayItem> currentInstructions = new ArrayList<>();
    private int currentInstructionIndex = -1;

    // Track current script ID for breakpoints
    private int currentScriptId = -1;

    // Track if we're in "browse mode" (user selected a handler) vs "trace mode" (following execution)
    private boolean browseMode = false;
    private ScriptChunk browseScript = null;
    private ScriptChunk.Handler browseHandler = null;

    public BytecodeDebuggerPanel() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(500, 600));

        initToolbar();
        initMainContent();
    }

    private void initToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        toolbar.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));

        stepIntoBtn = new JButton("Step Into");
        stepIntoBtn.setToolTipText("Step Into (F11)");
        stepIntoBtn.setEnabled(false);
        stepIntoBtn.addActionListener(e -> {
            if (controller != null) controller.stepInto();
        });
        toolbar.add(stepIntoBtn);

        stepOverBtn = new JButton("Step Over");
        stepOverBtn.setToolTipText("Step Over (F10)");
        stepOverBtn.setEnabled(false);
        stepOverBtn.addActionListener(e -> {
            if (controller != null) controller.stepOver();
        });
        toolbar.add(stepOverBtn);

        stepOutBtn = new JButton("Step Out");
        stepOutBtn.setToolTipText("Step Out (Shift+F11)");
        stepOutBtn.setEnabled(false);
        stepOutBtn.addActionListener(e -> {
            if (controller != null) controller.stepOut();
        });
        toolbar.add(stepOutBtn);

        toolbar.add(Box.createHorizontalStrut(10));

        continueBtn = new JButton("Continue");
        continueBtn.setToolTipText("Continue (F5)");
        continueBtn.setEnabled(false);
        continueBtn.addActionListener(e -> {
            if (controller != null) controller.continueExecution();
        });
        toolbar.add(continueBtn);

        pauseBtn = new JButton("Pause");
        pauseBtn.setToolTipText("Pause (F6)");
        pauseBtn.addActionListener(e -> {
            if (controller != null) controller.pause();
        });
        toolbar.add(pauseBtn);

        toolbar.add(Box.createHorizontalStrut(20));

        JButton clearBpBtn = new JButton("Clear BPs");
        clearBpBtn.setToolTipText("Clear all breakpoints");
        clearBpBtn.addActionListener(e -> {
            if (controller != null) controller.clearAllBreakpoints();
        });
        toolbar.add(clearBpBtn);

        add(toolbar, BorderLayout.NORTH);
    }

    private void initMainContent() {
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Top: Status and handler info
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusLabel = new JLabel("Status: Running");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        statusPanel.add(statusLabel, BorderLayout.NORTH);

        handlerLabel = new JLabel("Handler: -");
        handlerLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        statusPanel.add(handlerLabel, BorderLayout.SOUTH);

        mainPanel.add(statusPanel, BorderLayout.NORTH);

        // Center: Split between bytecode and state
        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        mainSplit.setResizeWeight(0.5);

        // Bytecode panel
        JPanel bytecodePanel = new JPanel(new BorderLayout());
        bytecodePanel.setBorder(new TitledBorder("Bytecode"));

        // Script/Handler browser panel
        JPanel browserPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        browserPanel.add(new JLabel("Script:"));
        scriptModel = new DefaultComboBoxModel<>();
        scriptCombo = new JComboBox<>(scriptModel);
        scriptCombo.setPreferredSize(new Dimension(180, 24));
        scriptCombo.addActionListener(e -> onScriptSelected());
        browserPanel.add(scriptCombo);

        browserPanel.add(new JLabel("Handler:"));
        handlerModel = new DefaultComboBoxModel<>();
        handlerCombo = new JComboBox<>(handlerModel);
        handlerCombo.setPreferredSize(new Dimension(140, 24));
        handlerCombo.addActionListener(e -> onHandlerSelected());
        browserPanel.add(handlerCombo);

        bytecodePanel.add(browserPanel, BorderLayout.NORTH);

        bytecodeModel = new DefaultListModel<>();
        bytecodeList = new JList<>(bytecodeModel);
        bytecodeList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        bytecodeList.setCellRenderer(new BytecodeCellRenderer());
        bytecodeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Click to toggle breakpoints or navigate to call targets
        bytecodeList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = bytecodeList.locationToIndex(e.getPoint());
                if (index < 0 || index >= bytecodeModel.size()) {
                    return;
                }

                InstructionDisplayItem item = bytecodeModel.get(index);

                // Double-click or click on left margin (gutter) -> toggle breakpoint
                if (e.getClickCount() == 2 || e.getX() < 20) {
                    if (controller != null && currentScriptId >= 0) {
                        controller.toggleBreakpoint(currentScriptId, item.offset);
                        item.hasBreakpoint = controller.hasBreakpoint(currentScriptId, item.offset);
                        bytecodeList.repaint();
                    }
                    return;
                }

                // Single click on call instruction -> navigate to handler definition
                if (e.getClickCount() == 1 && item.isCallInstruction()) {
                    String targetName = item.getCallTargetName();
                    if (targetName != null) {
                        navigateToHandler(targetName);
                    }
                }
            }
        });

        // Change cursor to hand when hovering over clickable call instructions
        bytecodeList.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int index = bytecodeList.locationToIndex(e.getPoint());
                if (index >= 0 && index < bytecodeModel.size()) {
                    InstructionDisplayItem item = bytecodeModel.get(index);
                    if (item.isCallInstruction() && item.getCallTargetName() != null && e.getX() >= 20) {
                        bytecodeList.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                        return;
                    }
                }
                bytecodeList.setCursor(Cursor.getDefaultCursor());
            }
        });

        JScrollPane bytecodeScroll = new JScrollPane(bytecodeList);
        bytecodeScroll.setPreferredSize(new Dimension(480, 200));
        bytecodePanel.add(bytecodeScroll, BorderLayout.CENTER);

        // Legend
        JLabel legend = new JLabel("<html><font color='red'>\u25CF</font> = breakpoint &nbsp; <font color='#DAA520'>\u25B6</font> = current &nbsp; <font color='blue'><u>blue</u></font> = click to navigate &nbsp; (double-click gutter to toggle breakpoint)</html>");
        legend.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        bytecodePanel.add(legend, BorderLayout.SOUTH);

        mainSplit.setTopComponent(bytecodePanel);

        // State panels in tabs
        JTabbedPane stateTabs = new JTabbedPane(JTabbedPane.TOP);

        // Stack table
        stackTableModel = new StackTableModel();
        stackTable = new JTable(stackTableModel);
        stackTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        stackTable.getColumnModel().getColumn(0).setPreferredWidth(40);
        stackTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        stackTable.getColumnModel().getColumn(2).setPreferredWidth(200);
        stateTabs.addTab("Stack", new JScrollPane(stackTable));

        // Locals table
        localsTableModel = new LocalsTableModel();
        localsTable = new JTable(localsTableModel);
        localsTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        localsTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        localsTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        localsTable.getColumnModel().getColumn(2).setPreferredWidth(200);
        stateTabs.addTab("Locals", new JScrollPane(localsTable));

        // Globals table
        globalsTableModel = new GlobalsTableModel();
        globalsTable = new JTable(globalsTableModel);
        globalsTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        globalsTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        globalsTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        globalsTable.getColumnModel().getColumn(2).setPreferredWidth(200);
        stateTabs.addTab("Globals", new JScrollPane(globalsTable));

        mainSplit.setBottomComponent(stateTabs);

        mainPanel.add(mainSplit, BorderLayout.CENTER);
        add(mainPanel, BorderLayout.CENTER);
    }

    /**
     * Set the debug controller.
     */
    public void setController(DebugController controller) {
        this.controller = controller;
    }

    /**
     * Set the Player reference for preloading casts.
     */
    public void setPlayer(Player player) {
        this.player = player;
    }

    /**
     * Set the DirectorFile and populate the script browser.
     * Call this when a movie is loaded.
     */
    public void setDirectorFile(DirectorFile file) {
        setDirectorFile(file, null);
    }

    /**
     * Set the DirectorFile and CastLibManager, populating the script browser
     * with scripts from all cast libraries.
     * Call this when a movie is loaded.
     */
    public void setDirectorFile(DirectorFile file, CastLibManager castLibManager) {
        this.directorFile = file;
        this.castLibManager = castLibManager;
        this.allScripts.clear();

        scriptModel.removeAllElements();
        handlerModel.removeAllElements();
        bytecodeModel.clear();

        if (file == null) {
            return;
        }

        // Collect all scripts from main file
        Set<Integer> seenScriptIds = new HashSet<>();
        for (ScriptChunk script : file.getScripts()) {
            if (!seenScriptIds.contains(script.id())) {
                allScripts.add(script);
                seenScriptIds.add(script.id());
            }
        }

        // Also collect scripts from all cast libraries
        if (castLibManager != null) {
            for (CastLib castLib : castLibManager.getCastLibs().values()) {
                for (ScriptChunk script : castLib.getAllScripts()) {
                    if (!seenScriptIds.contains(script.id())) {
                        allScripts.add(script);
                        seenScriptIds.add(script.id());
                    }
                }
            }
        }

        // Sort scripts by type, then by name for easier navigation
        allScripts.sort((a, b) -> {
            // First by type (MOVIE_SCRIPT first, then BEHAVIOR, then PARENT)
            int typeOrder = getScriptTypeOrder(a.getScriptType()) - getScriptTypeOrder(b.getScriptType());
            if (typeOrder != 0) return typeOrder;
            // Then by name
            return a.getDisplayName().compareToIgnoreCase(b.getDisplayName());
        });

        // Populate script combo
        for (ScriptChunk script : allScripts) {
            if (!script.handlers().isEmpty()) {
                scriptModel.addElement(new ScriptItem(script));
            }
        }

        // Auto-select first script if available
        if (scriptModel.getSize() > 0) {
            scriptCombo.setSelectedIndex(0);
        }
    }

    private int getScriptTypeOrder(ScriptChunk.ScriptType type) {
        return switch (type) {
            case MOVIE_SCRIPT -> 0;
            case BEHAVIOR -> 1;
            case PARENT -> 2;
            case SCORE -> 3;
            default -> 4;
        };
    }

    // Keep references for refresh
    private CastLibManager castLibManager;

    /**
     * Refresh the script list from the current DirectorFile and CastLibManager.
     * Call this when external casts are loaded.
     */
    public void refreshScriptList() {
        if (directorFile == null) {
            return;
        }

        // Remember current selection
        ScriptItem selectedScript = (ScriptItem) scriptCombo.getSelectedItem();
        HandlerItem selectedHandler = (HandlerItem) handlerCombo.getSelectedItem();
        int selectedScriptId = selectedScript != null ? selectedScript.script.id() : -1;
        String selectedHandlerName = selectedHandler != null ? selectedHandler.script.getHandlerName(selectedHandler.handler) : null;

        // Reload scripts
        setDirectorFile(directorFile, castLibManager);

        // Try to restore selection
        if (selectedScriptId >= 0) {
            for (int i = 0; i < scriptModel.getSize(); i++) {
                if (scriptModel.getElementAt(i).script.id() == selectedScriptId) {
                    scriptCombo.setSelectedIndex(i);
                    // Also try to restore handler selection
                    if (selectedHandlerName != null) {
                        for (int j = 0; j < handlerModel.getSize(); j++) {
                            HandlerItem h = handlerModel.getElementAt(j);
                            if (h.script.getHandlerName(h.handler).equals(selectedHandlerName)) {
                                handlerCombo.setSelectedIndex(j);
                                break;
                            }
                        }
                    }
                    break;
                }
            }
        }
    }

    /**
     * Called when a script is selected in the combo box.
     */
    private void onScriptSelected() {
        ScriptItem selected = (ScriptItem) scriptCombo.getSelectedItem();
        handlerModel.removeAllElements();

        if (selected == null) {
            return;
        }

        ScriptChunk script = selected.script;
        for (ScriptChunk.Handler handler : script.handlers()) {
            handlerModel.addElement(new HandlerItem(script, handler));
        }

        // Auto-select first handler
        if (handlerModel.getSize() > 0) {
            handlerCombo.setSelectedIndex(0);
        }
    }

    /**
     * Called when a handler is selected in the combo box.
     */
    private void onHandlerSelected() {
        HandlerItem selected = (HandlerItem) handlerCombo.getSelectedItem();
        if (selected == null) {
            return;
        }

        browseMode = true;
        browseScript = selected.script;
        browseHandler = selected.handler;
        currentScriptId = browseScript.id();

        loadHandlerBytecode(selected.script, selected.handler);
    }

    /**
     * Load bytecode for a handler into the bytecode list.
     */
    private void loadHandlerBytecode(ScriptChunk script, ScriptChunk.Handler handler) {
        bytecodeModel.clear();
        currentInstructions.clear();
        currentInstructionIndex = -1;

        handlerLabel.setText("Handler: " + script.getHandlerName(handler) + " (" + script.getDisplayName() + ")");

        for (ScriptChunk.Handler.Instruction instr : handler.instructions()) {
            String annotation = buildAnnotation(script, handler, instr);
            boolean hasBp = controller != null && controller.hasBreakpoint(script.id(), instr.offset());

            InstructionDisplayItem item = new InstructionDisplayItem(
                instr.offset(),
                handler.instructions().indexOf(instr),
                instr.opcode().name(),
                instr.argument(),
                annotation,
                hasBp
            );
            bytecodeModel.addElement(item);
            currentInstructions.add(item);
        }
    }

    /**
     * Build annotation string for an instruction (similar to TracingHelper).
     */
    private String buildAnnotation(ScriptChunk script, ScriptChunk.Handler handler, ScriptChunk.Handler.Instruction instr) {
        Opcode op = instr.opcode();
        int arg = instr.argument();

        return switch (op) {
            case PUSH_INT8, PUSH_INT16, PUSH_INT32 -> "<" + arg + ">";
            case PUSH_FLOAT32 -> "<" + Float.intBitsToFloat(arg) + ">";
            case PUSH_CONS -> {
                List<ScriptChunk.LiteralEntry> literals = script.literals();
                if (arg >= 0 && arg < literals.size()) {
                    yield "<" + literals.get(arg).value() + ">";
                }
                yield "<literal#" + arg + ">";
            }
            case PUSH_SYMB -> "<#" + script.resolveName(arg) + ">";
            case GET_LOCAL, SET_LOCAL -> {
                // Try to get local name
                if (arg >= 0 && arg < handler.localNameIds().size()) {
                    yield "<" + script.resolveName(handler.localNameIds().get(arg)) + ">";
                }
                yield "<local" + arg + ">";
            }
            case GET_PARAM -> {
                if (arg >= 0 && arg < handler.argNameIds().size()) {
                    yield "<" + script.resolveName(handler.argNameIds().get(arg)) + ">";
                }
                yield "<param" + arg + ">";
            }
            case GET_GLOBAL, SET_GLOBAL, GET_GLOBAL2, SET_GLOBAL2 -> "<" + script.resolveName(arg) + ">";
            case GET_PROP, SET_PROP -> "<me." + script.resolveName(arg) + ">";
            case LOCAL_CALL -> {
                var handlers = script.handlers();
                if (arg >= 0 && arg < handlers.size()) {
                    yield "<" + script.getHandlerName(handlers.get(arg)) + "()>";
                }
                yield "<handler#" + arg + "()>";
            }
            case EXT_CALL, OBJ_CALL -> "<" + script.resolveName(arg) + "()>";
            case JMP, JMP_IF_Z -> "<offset " + arg + " -> " + (instr.offset() + arg) + ">";
            case END_REPEAT -> "<back " + arg + " -> " + (instr.offset() - arg) + ">";
            default -> "";
        };
    }

    /**
     * Clear the bytecode display and reset browse mode.
     */
    public void clear() {
        bytecodeModel.clear();
        currentInstructions.clear();
        browseMode = false;
        browseScript = null;
        browseHandler = null;
        currentScriptId = -1;
        currentInstructionIndex = -1;
        statusLabel.setText("Status: Running");
        handlerLabel.setText("Handler: -");
    }

    /**
     * Register keyboard shortcuts on the given root pane.
     */
    public void registerKeyboardShortcuts(JRootPane rootPane) {
        InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = rootPane.getActionMap();

        // F5 - Continue
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "debug.continue");
        actionMap.put("debug.continue", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (controller != null && controller.isPaused()) {
                    controller.continueExecution();
                }
            }
        });

        // F6 - Pause
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), "debug.pause");
        actionMap.put("debug.pause", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (controller != null && !controller.isPaused()) {
                    controller.pause();
                }
            }
        });

        // F10 - Step Over
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, 0), "debug.stepOver");
        actionMap.put("debug.stepOver", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (controller != null && controller.isPaused()) {
                    controller.stepOver();
                }
            }
        });

        // F11 - Step Into
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), "debug.stepInto");
        actionMap.put("debug.stepInto", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (controller != null && controller.isPaused()) {
                    controller.stepInto();
                }
            }
        });

        // Shift+F11 - Step Out
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F11, InputEvent.SHIFT_DOWN_MASK), "debug.stepOut");
        actionMap.put("debug.stepOut", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (controller != null && controller.isPaused()) {
                    controller.stepOut();
                }
            }
        });
    }

    // DebugStateListener implementation

    @Override
    public void onPaused(DebugSnapshot snapshot) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Status: PAUSED at offset " + snapshot.instructionOffset());
            handlerLabel.setText("Handler: " + snapshot.handlerName() + " (" + snapshot.scriptName() + ")");

            // Update current instruction highlight
            currentScriptId = snapshot.scriptId();
            highlightCurrentInstruction(snapshot.instructionIndex());

            // Update stack
            stackTableModel.setStack(snapshot.stack());

            // Update locals
            localsTableModel.setLocals(snapshot.locals());

            // Update globals
            globalsTableModel.setGlobals(snapshot.globals());

            // Enable step buttons
            setStepButtonsEnabled(true);
        });
    }

    @Override
    public void onResumed() {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Status: Running");
            setStepButtonsEnabled(false);
            currentInstructionIndex = -1;
            bytecodeList.clearSelection();
        });
    }

    @Override
    public void onBreakpointsChanged() {
        SwingUtilities.invokeLater(() -> {
            // Update breakpoint markers in the list
            if (controller != null && currentScriptId >= 0) {
                for (int i = 0; i < bytecodeModel.size(); i++) {
                    InstructionDisplayItem item = bytecodeModel.get(i);
                    item.hasBreakpoint = controller.hasBreakpoint(currentScriptId, item.offset);
                }
                bytecodeList.repaint();
            }
        });
    }

    // TraceListener implementation (to capture instruction list)

    @Override
    public void onHandlerEnter(HandlerInfo info) {
        currentHandlerInfo = info;
        currentScriptId = info.scriptId();

        SwingUtilities.invokeLater(() -> {
            // Switch to trace mode - show the handler being executed
            browseMode = false;

            // Find and load the script's full bytecode
            ScriptChunk script = findScriptById(info.scriptId());
            if (script != null) {
                ScriptChunk.Handler handler = script.findHandler(info.handlerName());
                if (handler != null) {
                    // Load full bytecode for the handler
                    loadHandlerBytecode(script, handler);

                    // Update combo boxes to reflect current handler (without triggering events)
                    selectScriptInCombo(script);
                    selectHandlerInCombo(script, handler);
                    return;
                }
            }

            // Fallback: clear and prepare for incremental instruction capture
            currentInstructions.clear();
            bytecodeModel.clear();
            currentInstructionIndex = -1;

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
            // Update current instruction highlight
            highlightCurrentInstruction(info.bytecodeIndex());

            // Update stack display
            stackTableModel.setStack(info.stackSnapshot());
        });
    }

    /**
     * Find a script by its ID.
     */
    private ScriptChunk findScriptById(int scriptId) {
        for (ScriptChunk script : allScripts) {
            if (script.id() == scriptId) {
                return script;
            }
        }
        return null;
    }

    /**
     * Select a script in the combo box without triggering handler reload.
     */
    private void selectScriptInCombo(ScriptChunk script) {
        for (int i = 0; i < scriptModel.getSize(); i++) {
            if (scriptModel.getElementAt(i).script == script) {
                // Remove listener temporarily to avoid triggering reload
                ActionListener[] listeners = scriptCombo.getActionListeners();
                for (ActionListener l : listeners) {
                    scriptCombo.removeActionListener(l);
                }
                scriptCombo.setSelectedIndex(i);
                for (ActionListener l : listeners) {
                    scriptCombo.addActionListener(l);
                }
                break;
            }
        }
    }

    /**
     * Select a handler in the combo box without triggering bytecode reload.
     */
    private void selectHandlerInCombo(ScriptChunk script, ScriptChunk.Handler handler) {
        // First update handler model to match script
        handlerModel.removeAllElements();
        for (ScriptChunk.Handler h : script.handlers()) {
            handlerModel.addElement(new HandlerItem(script, h));
        }

        // Find and select the handler
        for (int i = 0; i < handlerModel.getSize(); i++) {
            if (handlerModel.getElementAt(i).handler == handler) {
                ActionListener[] listeners = handlerCombo.getActionListeners();
                for (ActionListener l : listeners) {
                    handlerCombo.removeActionListener(l);
                }
                handlerCombo.setSelectedIndex(i);
                for (ActionListener l : listeners) {
                    handlerCombo.addActionListener(l);
                }
                break;
            }
        }
    }

    private int findInstructionByOffset(int offset) {
        for (int i = 0; i < bytecodeModel.size(); i++) {
            if (bytecodeModel.get(i).offset == offset) {
                return i;
            }
        }
        return -1;
    }

    private void highlightCurrentInstruction(int bytecodeIndex) {
        // Find the instruction with matching bytecode index
        for (int i = 0; i < bytecodeModel.size(); i++) {
            InstructionDisplayItem item = bytecodeModel.get(i);
            boolean wasCurrent = item.isCurrent;
            item.isCurrent = (item.index == bytecodeIndex);
            if (item.isCurrent && !wasCurrent) {
                currentInstructionIndex = i;
                bytecodeList.setSelectedIndex(i);
                bytecodeList.ensureIndexIsVisible(i);
            }
        }
        bytecodeList.repaint();
    }

    private void setStepButtonsEnabled(boolean enabled) {
        stepIntoBtn.setEnabled(enabled);
        stepOverBtn.setEnabled(enabled);
        stepOutBtn.setEnabled(enabled);
        continueBtn.setEnabled(enabled);
    }

    /**
     * Navigate to a handler by name, searching all scripts.
     */
    private void navigateToHandler(String handlerName) {
        // Search all scripts for a handler with this name
        for (ScriptChunk script : allScripts) {
            ScriptChunk.Handler handler = script.findHandler(handlerName);
            if (handler != null) {
                // Found it - update the combo boxes and load bytecode
                browseMode = true;
                browseScript = script;
                browseHandler = handler;
                currentScriptId = script.id();

                // Select in script combo (without triggering reload)
                selectScriptInCombo(script);

                // Update handler combo and select the handler
                handlerModel.removeAllElements();
                int handlerIndex = 0;
                int targetIndex = 0;
                for (ScriptChunk.Handler h : script.handlers()) {
                    handlerModel.addElement(new HandlerItem(script, h));
                    if (h == handler) {
                        targetIndex = handlerIndex;
                    }
                    handlerIndex++;
                }

                // Select handler without triggering reload
                ActionListener[] listeners = handlerCombo.getActionListeners();
                for (ActionListener l : listeners) {
                    handlerCombo.removeActionListener(l);
                }
                handlerCombo.setSelectedIndex(targetIndex);
                for (ActionListener l : listeners) {
                    handlerCombo.addActionListener(l);
                }

                // Load the bytecode
                loadHandlerBytecode(script, handler);
                return;
            }
        }

        // Not found - show message in status
        statusLabel.setText("Handler '" + handlerName + "' not found");
    }

    // Display item for bytecode list
    private static class InstructionDisplayItem {
        private static final Set<String> CALL_OPCODES = Set.of("EXT_CALL", "OBJ_CALL", "LOCAL_CALL");

        final int offset;
        final int index;
        final String opcode;
        final int argument;
        final String annotation;
        boolean hasBreakpoint;
        boolean isCurrent;

        InstructionDisplayItem(int offset, int index, String opcode, int argument, String annotation, boolean hasBreakpoint) {
            this.offset = offset;
            this.index = index;
            this.opcode = opcode;
            this.argument = argument;
            this.annotation = annotation;
            this.hasBreakpoint = hasBreakpoint;
            this.isCurrent = false;
        }

        /**
         * Check if this instruction is a call that can be navigated to.
         */
        boolean isCallInstruction() {
            return CALL_OPCODES.contains(opcode);
        }

        /**
         * Extract the handler name from the annotation (e.g., "<myHandler()>" -> "myHandler").
         */
        String getCallTargetName() {
            if (annotation == null || annotation.isEmpty()) {
                return null;
            }
            // Annotation format is "<handlerName()>"
            if (annotation.startsWith("<") && annotation.endsWith("()>")) {
                return annotation.substring(1, annotation.length() - 3);
            }
            return null;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[%3d] %-14s", offset, opcode));
            if (argument != 0) {
                sb.append(String.format(" %-4d", argument));
            } else {
                sb.append("     ");
            }
            if (annotation != null && !annotation.isEmpty()) {
                sb.append(" ").append(annotation);
            }
            return sb.toString();
        }
    }

    // Custom cell renderer for bytecode list
    private class BytecodeCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            if (value instanceof InstructionDisplayItem item) {
                // Build display text with markers using HTML for rich formatting
                StringBuilder sb = new StringBuilder("<html><pre style='margin:0;font-family:monospaced;'>");

                // Breakpoint marker (red)
                if (item.hasBreakpoint) {
                    sb.append("<font color='red'>\u25CF</font> ");
                } else {
                    sb.append("  ");
                }

                // Current instruction marker (gold)
                if (item.isCurrent) {
                    sb.append("<font color='#DAA520'>\u25B6</font> ");
                } else {
                    sb.append("  ");
                }

                // Instruction text
                sb.append(String.format("[%3d] %-14s", item.offset, item.opcode));
                if (item.argument != 0) {
                    sb.append(String.format(" %-4d", item.argument));
                } else {
                    sb.append("     ");
                }

                // Annotation - make call targets blue and underlined
                if (item.annotation != null && !item.annotation.isEmpty()) {
                    sb.append(" ");
                    if (item.isCallInstruction() && item.getCallTargetName() != null) {
                        sb.append("<font color='blue'><u>").append(escapeHtml(item.annotation)).append("</u></font>");
                    } else {
                        sb.append(escapeHtml(item.annotation));
                    }
                }

                sb.append("</pre></html>");
                label.setText(sb.toString());

                // Highlighting for current instruction
                if (item.isCurrent && !isSelected) {
                    label.setBackground(new Color(255, 255, 200));  // Light yellow
                    label.setOpaque(true);
                }
            }

            return label;
        }

        private String escapeHtml(String text) {
            return text.replace("&", "&amp;")
                       .replace("<", "&lt;")
                       .replace(">", "&gt;");
        }
    }

    // Table model for stack display
    private static class StackTableModel extends AbstractTableModel {
        private List<Datum> stack = new ArrayList<>();
        private final String[] columns = {"#", "Type", "Value"};

        void setStack(List<Datum> stack) {
            this.stack = stack != null ? new ArrayList<>(stack) : new ArrayList<>();
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return stack.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex >= stack.size()) return "";
            Datum d = stack.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> String.valueOf(rowIndex);
                case 1 -> getTypeName(d);
                case 2 -> DatumFormatter.format(d);
                default -> "";
            };
        }

        private String getTypeName(Datum d) {
            if (d == null) return "null";
            return d.getClass().getSimpleName().replace("$", ".");
        }
    }

    // Table model for locals display
    private static class LocalsTableModel extends AbstractTableModel {
        private final List<Map.Entry<String, Datum>> locals = new ArrayList<>();
        private final String[] columns = {"Name", "Type", "Value"};

        void setLocals(Map<String, Datum> localsMap) {
            this.locals.clear();
            if (localsMap != null) {
                this.locals.addAll(localsMap.entrySet());
            }
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return locals.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex >= locals.size()) return "";
            Map.Entry<String, Datum> entry = locals.get(rowIndex);
            Datum d = entry.getValue();
            return switch (columnIndex) {
                case 0 -> entry.getKey();
                case 1 -> d != null ? d.getClass().getSimpleName().replace("$", ".") : "null";
                case 2 -> DatumFormatter.format(d);
                default -> "";
            };
        }
    }

    // Table model for globals display
    private static class GlobalsTableModel extends AbstractTableModel {
        private final List<Map.Entry<String, Datum>> globals = new ArrayList<>();
        private final String[] columns = {"Name", "Type", "Value"};

        void setGlobals(Map<String, Datum> globalsMap) {
            this.globals.clear();
            if (globalsMap != null) {
                this.globals.addAll(globalsMap.entrySet());
            }
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return globals.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex >= globals.size()) return "";
            Map.Entry<String, Datum> entry = globals.get(rowIndex);
            Datum d = entry.getValue();
            return switch (columnIndex) {
                case 0 -> entry.getKey();
                case 1 -> d != null ? d.getClass().getSimpleName().replace("$", ".") : "null";
                case 2 -> DatumFormatter.format(d);
                default -> "";
            };
        }
    }

    // Combo box item for scripts
    private static class ScriptItem {
        final ScriptChunk script;

        ScriptItem(ScriptChunk script) {
            this.script = script;
        }

        @Override
        public String toString() {
            return script.getDisplayName();
        }
    }

    // Combo box item for handlers
    private static class HandlerItem {
        final ScriptChunk script;
        final ScriptChunk.Handler handler;

        HandlerItem(ScriptChunk script, ScriptChunk.Handler handler) {
            this.script = script;
            this.handler = handler;
        }

        @Override
        public String toString() {
            return script.getHandlerName(handler);
        }
    }
}
