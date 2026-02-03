package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.player.cast.CastLib;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.debug.Breakpoint;
import com.libreshockwave.player.debug.DebugController;
import com.libreshockwave.player.debug.DebugSnapshot;
import com.libreshockwave.player.debug.DebugStateListener;
import com.libreshockwave.player.debug.WatchExpression;
import com.libreshockwave.player.debug.ui.BytecodeCellRenderer;
import com.libreshockwave.player.debug.ui.HandlerItem;
import com.libreshockwave.player.debug.ui.InstructionDisplayItem;
import com.libreshockwave.player.debug.ui.ScriptItem;
import com.libreshockwave.player.debug.ui.StackTableModel;
import com.libreshockwave.player.debug.ui.VariablesTableModel;
import com.libreshockwave.player.debug.ui.WatchesTableModel;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.TraceListener;
import com.libreshockwave.vm.trace.InstructionAnnotator;
import com.libreshockwave.vm.util.StringUtils;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.Dialog;
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
    private List<ScriptItem> allScriptItems = new ArrayList<>();  // All scripts with source info
    private List<HandlerItem> allHandlerItems = new ArrayList<>();  // All handlers for current script
    private JTextField scriptFilterField;  // Filter field for script combo
    private JTextField handlerFilterField;  // Filter field for handler combo

    // UI Components - Script browser
    private JComboBox<ScriptItem> scriptCombo;
    private JComboBox<HandlerItem> handlerCombo;
    private DefaultComboBoxModel<ScriptItem> scriptModel;
    private DefaultComboBoxModel<HandlerItem> handlerModel;

    // UI Components - Bytecode display
    private JList<InstructionDisplayItem> bytecodeList;
    private DefaultListModel<InstructionDisplayItem> bytecodeModel;
    private JPopupMenu bytecodeContextMenu;
    private JTable stackTable;
    private StackTableModel stackTableModel;
    private JTable localsTable;
    private VariablesTableModel localsTableModel;
    private JTable globalsTable;
    private VariablesTableModel globalsTableModel;
    private JLabel statusLabel;
    private JLabel handlerLabel;

    // UI Components - Watches
    private JTable watchesTable;
    private WatchesTableModel watchesTableModel;

    // State tabs reference for adding watches/log tabs
    private JTabbedPane stateTabs;

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

    // Debounce timers for filter text fields
    private javax.swing.Timer scriptFilterTimer;
    private javax.swing.Timer handlerFilterTimer;
    private static final int FILTER_DEBOUNCE_MS = 150;

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

        // Script/Handler browser panel - use BoxLayout for two rows
        JPanel browserPanel = new JPanel();
        browserPanel.setLayout(new BoxLayout(browserPanel, BoxLayout.Y_AXIS));

        // Row 1: Script filter and combo
        JPanel scriptRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        scriptRow.add(new JLabel("Script:"));
        scriptFilterField = new JTextField(10);
        scriptFilterField.setToolTipText("Type to filter scripts");
        scriptFilterTimer = new javax.swing.Timer(FILTER_DEBOUNCE_MS, e -> filterScripts());
        scriptFilterTimer.setRepeats(false);
        scriptFilterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { scriptFilterTimer.restart(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { scriptFilterTimer.restart(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { scriptFilterTimer.restart(); }
        });
        scriptRow.add(scriptFilterField);

        scriptModel = new DefaultComboBoxModel<>();
        scriptCombo = new JComboBox<>(scriptModel);
        scriptCombo.setPreferredSize(new Dimension(350, 24));
        scriptCombo.setMaximumRowCount(20);
        scriptCombo.addActionListener(e -> onScriptSelected());
        scriptRow.add(scriptCombo);
        browserPanel.add(scriptRow);

        // Row 2: Handler filter, combo and details button
        JPanel handlerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        handlerRow.add(new JLabel("Handler:"));

        handlerFilterField = new JTextField(10);
        handlerFilterField.setToolTipText("Type to filter handlers");
        handlerFilterTimer = new javax.swing.Timer(FILTER_DEBOUNCE_MS, e -> filterHandlers());
        handlerFilterTimer.setRepeats(false);
        handlerFilterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { handlerFilterTimer.restart(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { handlerFilterTimer.restart(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { handlerFilterTimer.restart(); }
        });
        handlerRow.add(handlerFilterField);

        handlerModel = new DefaultComboBoxModel<>();
        handlerCombo = new JComboBox<>(handlerModel);
        handlerCombo.setPreferredSize(new Dimension(200, 24));
        handlerCombo.addActionListener(e -> onHandlerSelected());
        handlerRow.add(handlerCombo);

        JButton viewHandlerDetailsBtn = new JButton("\u2139");  // Info symbol
        viewHandlerDetailsBtn.setToolTipText("View Handler Details");
        viewHandlerDetailsBtn.setMargin(new Insets(2, 6, 2, 6));
        viewHandlerDetailsBtn.addActionListener(e -> {
            HandlerItem selected = (HandlerItem) handlerCombo.getSelectedItem();
            if (selected != null) {
                showHandlerDetailsDialog(selected.getScript().getHandlerName(selected.getHandler()));
            }
        });
        handlerRow.add(viewHandlerDetailsBtn);
        browserPanel.add(handlerRow);

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
                        controller.toggleBreakpoint(currentScriptId, item.getOffset());
                        item.setHasBreakpoint(controller.hasBreakpoint(currentScriptId, item.getOffset()));
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

        // Context menu for call instructions
        initBytecodeContextMenu();

        JScrollPane bytecodeScroll = new JScrollPane(bytecodeList);
        bytecodeScroll.setPreferredSize(new Dimension(480, 200));
        bytecodePanel.add(bytecodeScroll, BorderLayout.CENTER);

        // Legend
        JLabel legend = new JLabel("<html>" +
            "<font color='red'>\u25CF</font>=breakpoint &nbsp; " +
            "<font color='tomato'>\u25CF</font>=conditional &nbsp; " +
            "<font color='gray'>\u25CB</font>=disabled &nbsp; " +
            "<font color='orange'>\u25C6</font>=logpoint &nbsp; " +
            "<font color='#DAA520'>\u25B6</font>=current &nbsp; " +
            "<font color='blue'><u>blue</u></font>=navigate</html>");
        legend.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        bytecodePanel.add(legend, BorderLayout.SOUTH);

        mainSplit.setTopComponent(bytecodePanel);

        // State panels in tabs
        stateTabs = new JTabbedPane(JTabbedPane.TOP);

        // Stack table
        stackTableModel = new StackTableModel();
        stackTable = new JTable(stackTableModel);
        stackTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        stackTable.getColumnModel().getColumn(0).setPreferredWidth(40);
        stackTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        stackTable.getColumnModel().getColumn(2).setPreferredWidth(200);
        // Double-click to show detailed Datum info
        stackTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = stackTable.getSelectedRow();
                    if (row >= 0) {
                        Datum d = stackTableModel.getDatum(row);
                        if (d != null) {
                            showDatumDetailsDialog(d, "Stack[" + row + "]");
                        }
                    }
                }
            }
        });
        stateTabs.addTab("Stack", new JScrollPane(stackTable));

        // Locals table
        localsTableModel = new VariablesTableModel();
        localsTable = new JTable(localsTableModel);
        localsTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        localsTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        localsTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        localsTable.getColumnModel().getColumn(2).setPreferredWidth(200);
        stateTabs.addTab("Locals", new JScrollPane(localsTable));

        // Globals table
        globalsTableModel = new VariablesTableModel();
        globalsTable = new JTable(globalsTableModel);
        globalsTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        globalsTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        globalsTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        globalsTable.getColumnModel().getColumn(2).setPreferredWidth(200);
        stateTabs.addTab("Globals", new JScrollPane(globalsTable));

        // Watches table
        stateTabs.addTab("Watches", createWatchesPanel());

        mainSplit.setBottomComponent(stateTabs);

        mainPanel.add(mainSplit, BorderLayout.CENTER);
        add(mainPanel, BorderLayout.CENTER);
    }

    /**
     * Initialize the right-click context menu for bytecode list.
     */
    private void initBytecodeContextMenu() {
        bytecodeContextMenu = new JPopupMenu();

        // Breakpoint actions
        JMenuItem toggleBpItem = new JMenuItem("Toggle Breakpoint");
        toggleBpItem.addActionListener(e -> {
            int index = bytecodeList.getSelectedIndex();
            if (index >= 0 && index < bytecodeModel.size() && controller != null && currentScriptId >= 0) {
                InstructionDisplayItem item = bytecodeModel.get(index);
                controller.toggleBreakpoint(currentScriptId, item.getOffset());
                updateBreakpointDisplay(item);
            }
        });
        bytecodeContextMenu.add(toggleBpItem);

        JMenuItem enableDisableItem = new JMenuItem("Enable/Disable Breakpoint");
        enableDisableItem.addActionListener(e -> {
            int index = bytecodeList.getSelectedIndex();
            if (index >= 0 && index < bytecodeModel.size() && controller != null && currentScriptId >= 0) {
                InstructionDisplayItem item = bytecodeModel.get(index);
                Breakpoint bp = controller.getBreakpoint(currentScriptId, item.getOffset());
                if (bp != null) {
                    controller.toggleBreakpointEnabled(currentScriptId, item.getOffset());
                    updateBreakpointDisplay(item);
                }
            }
        });
        bytecodeContextMenu.add(enableDisableItem);

        JMenuItem editBpItem = new JMenuItem("Edit Breakpoint...");
        editBpItem.addActionListener(e -> {
            int index = bytecodeList.getSelectedIndex();
            if (index >= 0 && index < bytecodeModel.size() && controller != null && currentScriptId >= 0) {
                InstructionDisplayItem item = bytecodeModel.get(index);
                showBreakpointPropertiesDialog(item.getOffset());
            }
        });
        bytecodeContextMenu.add(editBpItem);

        JMenuItem addLogPointItem = new JMenuItem("Add Log Point...");
        addLogPointItem.addActionListener(e -> {
            int index = bytecodeList.getSelectedIndex();
            if (index >= 0 && index < bytecodeModel.size() && controller != null && currentScriptId >= 0) {
                InstructionDisplayItem item = bytecodeModel.get(index);
                showAddLogPointDialog(item.getOffset());
            }
        });
        bytecodeContextMenu.add(addLogPointItem);

        JMenuItem resetHitCountItem = new JMenuItem("Reset Hit Count");
        resetHitCountItem.addActionListener(e -> {
            int index = bytecodeList.getSelectedIndex();
            if (index >= 0 && index < bytecodeModel.size() && controller != null && currentScriptId >= 0) {
                InstructionDisplayItem item = bytecodeModel.get(index);
                controller.resetBreakpointHitCount(currentScriptId, item.getOffset());
            }
        });
        bytecodeContextMenu.add(resetHitCountItem);

        bytecodeContextMenu.addSeparator();

        // Navigation actions for call instructions
        JMenuItem goToDefItem = new JMenuItem("Go to Definition");
        goToDefItem.addActionListener(e -> {
            int index = bytecodeList.getSelectedIndex();
            if (index >= 0 && index < bytecodeModel.size()) {
                InstructionDisplayItem item = bytecodeModel.get(index);
                String targetName = item.getCallTargetName();
                if (targetName != null) {
                    navigateToHandler(targetName);
                }
            }
        });
        bytecodeContextMenu.add(goToDefItem);

        JMenuItem viewDetailsItem = new JMenuItem("View Handler Details...");
        viewDetailsItem.addActionListener(e -> {
            int index = bytecodeList.getSelectedIndex();
            if (index >= 0 && index < bytecodeModel.size()) {
                InstructionDisplayItem item = bytecodeModel.get(index);
                String targetName = item.getCallTargetName();
                if (targetName != null) {
                    showHandlerDetailsDialog(targetName);
                }
            }
        });
        bytecodeContextMenu.add(viewDetailsItem);

        // Add mouse listener for right-click
        bytecodeList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            private void maybeShowPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int index = bytecodeList.locationToIndex(e.getPoint());
                    if (index >= 0 && index < bytecodeModel.size()) {
                        bytecodeList.setSelectedIndex(index);
                        bytecodeContextMenu.show(bytecodeList, e.getX(), e.getY());
                    }
                }
            }
        });
    }

    /**
     * Update breakpoint display for an item after a change.
     */
    private void updateBreakpointDisplay(InstructionDisplayItem item) {
        if (controller != null && currentScriptId >= 0) {
            Breakpoint bp = controller.getBreakpoint(currentScriptId, item.getOffset());
            item.setBreakpoint(bp);
            item.setHasBreakpoint(bp != null);
            bytecodeList.repaint();
        }
    }

    /**
     * Show the breakpoint properties dialog.
     */
    private void showBreakpointPropertiesDialog(int offset) {
        Breakpoint bp = controller.getBreakpoint(currentScriptId, offset);
        if (bp == null) {
            // Create a new breakpoint first
            bp = Breakpoint.simple(currentScriptId, offset);
            controller.setBreakpoint(bp);
        }

        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
            "Breakpoint Properties", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(400, 300);
        dialog.setLocationRelativeTo(this);

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Enabled checkbox
        gbc.gridx = 0; gbc.gridy = 0;
        formPanel.add(new JLabel("Enabled:"), gbc);
        JCheckBox enabledCheck = new JCheckBox();
        enabledCheck.setSelected(bp.enabled());
        gbc.gridx = 1;
        formPanel.add(enabledCheck, gbc);

        // Condition field
        gbc.gridx = 0; gbc.gridy = 1;
        formPanel.add(new JLabel("Condition:"), gbc);
        JTextField conditionField = new JTextField(bp.condition() != null ? bp.condition() : "", 25);
        conditionField.setToolTipText("Lingo expression, e.g., i > 5");
        gbc.gridx = 1;
        formPanel.add(conditionField, gbc);

        // Hit count threshold
        gbc.gridx = 0; gbc.gridy = 2;
        formPanel.add(new JLabel("Break after hit count:"), gbc);
        JSpinner hitThresholdSpinner = new JSpinner(new SpinnerNumberModel(bp.hitCountThreshold(), 0, 10000, 1));
        hitThresholdSpinner.setToolTipText("0 = always break, >0 = break after N hits");
        gbc.gridx = 1;
        formPanel.add(hitThresholdSpinner, gbc);

        // Current hit count (read-only)
        gbc.gridx = 0; gbc.gridy = 3;
        formPanel.add(new JLabel("Current hit count:"), gbc);
        JLabel hitCountLabel = new JLabel(String.valueOf(bp.hitCount()));
        gbc.gridx = 1;
        formPanel.add(hitCountLabel, gbc);

        // Reset hit count button
        JButton resetHitBtn = new JButton("Reset");
        final Breakpoint bpRef = bp;
        resetHitBtn.addActionListener(e -> {
            controller.resetBreakpointHitCount(currentScriptId, offset);
            hitCountLabel.setText("0");
        });
        gbc.gridx = 2;
        formPanel.add(resetHitBtn, gbc);

        // Log message field
        gbc.gridx = 0; gbc.gridy = 4;
        formPanel.add(new JLabel("Log message:"), gbc);
        JTextField logMessageField = new JTextField(bp.logMessage() != null ? bp.logMessage() : "", 25);
        logMessageField.setToolTipText("If set, logs message instead of pausing. Use {var} for interpolation.");
        gbc.gridx = 1; gbc.gridwidth = 2;
        formPanel.add(logMessageField, gbc);

        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 3;
        formPanel.add(new JLabel("<html><small>Log message converts breakpoint to log point (no pause).</small></html>"), gbc);

        dialog.add(formPanel, BorderLayout.CENTER);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okBtn = new JButton("OK");
        okBtn.addActionListener(e -> {
            String condition = conditionField.getText().trim();
            String logMessage = logMessageField.getText().trim();
            int hitThreshold = (Integer) hitThresholdSpinner.getValue();

            Breakpoint updated = new Breakpoint(
                currentScriptId,
                offset,
                enabledCheck.isSelected(),
                condition.isEmpty() ? null : condition,
                logMessage.isEmpty() ? null : logMessage,
                bpRef.hitCount(),
                hitThreshold
            );
            controller.setBreakpoint(updated);
            dialog.dispose();
            bytecodeList.repaint();
        });
        buttonPanel.add(okBtn);

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dialog.dispose());
        buttonPanel.add(cancelBtn);

        JButton removeBtn = new JButton("Remove Breakpoint");
        removeBtn.addActionListener(e -> {
            controller.removeBreakpoint(currentScriptId, offset);
            dialog.dispose();
        });
        buttonPanel.add(removeBtn);

        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    /**
     * Show dialog to add a log point.
     */
    private void showAddLogPointDialog(int offset) {
        String message = JOptionPane.showInputDialog(
            this,
            "Enter log message (use {variable} for interpolation):\nExample: Loop iteration {i}, value = {x}",
            "Add Log Point",
            JOptionPane.PLAIN_MESSAGE
        );

        if (message != null && !message.trim().isEmpty()) {
            Breakpoint bp = Breakpoint.logPoint(currentScriptId, offset, message.trim());
            controller.setBreakpoint(bp);
            bytecodeList.repaint();
        }
    }

    /**
     * Create the watches panel with add/remove buttons.
     */
    private JPanel createWatchesPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        watchesTableModel = new WatchesTableModel();
        watchesTable = new JTable(watchesTableModel);
        watchesTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        watchesTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        watchesTable.getColumnModel().getColumn(1).setPreferredWidth(60);
        watchesTable.getColumnModel().getColumn(2).setPreferredWidth(200);

        // Double-click to edit
        watchesTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = watchesTable.getSelectedRow();
                    if (row >= 0 && controller != null) {
                        List<WatchExpression> watches = controller.getWatchExpressions();
                        if (row < watches.size()) {
                            WatchExpression watch = watches.get(row);
                            String newExpr = JOptionPane.showInputDialog(
                                BytecodeDebuggerPanel.this,
                                "Edit watch expression:",
                                watch.expression()
                            );
                            if (newExpr != null && !newExpr.trim().isEmpty()) {
                                controller.updateWatchExpression(watch.id(), newExpr.trim());
                                refreshWatches();
                            }
                        }
                    }
                }
            }
        });

        panel.add(new JScrollPane(watchesTable), BorderLayout.CENTER);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addBtn = new JButton("+");
        addBtn.setToolTipText("Add watch expression");
        addBtn.addActionListener(e -> {
            String expr = JOptionPane.showInputDialog(
                this,
                "Enter watch expression:",
                "Add Watch",
                JOptionPane.PLAIN_MESSAGE
            );
            if (expr != null && !expr.trim().isEmpty() && controller != null) {
                controller.addWatchExpression(expr.trim());
                refreshWatches();
            }
        });
        buttonPanel.add(addBtn);

        JButton removeBtn = new JButton("-");
        removeBtn.setToolTipText("Remove selected watch");
        removeBtn.addActionListener(e -> {
            int row = watchesTable.getSelectedRow();
            if (row >= 0 && controller != null) {
                List<WatchExpression> watches = controller.getWatchExpressions();
                if (row < watches.size()) {
                    controller.removeWatchExpression(watches.get(row).id());
                    refreshWatches();
                }
            }
        });
        buttonPanel.add(removeBtn);

        JButton clearBtn = new JButton("Clear");
        clearBtn.setToolTipText("Clear all watches");
        clearBtn.addActionListener(e -> {
            if (controller != null) {
                controller.clearWatchExpressions();
                refreshWatches();
            }
        });
        buttonPanel.add(clearBtn);

        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * Refresh watch expressions display.
     */
    private void refreshWatches() {
        if (controller != null) {
            List<WatchExpression> watches = controller.evaluateWatchExpressions();
            watchesTableModel.setWatches(watches);
        }
    }

    /**
     * Show a dialog with detailed information about a Datum value.
     */
    private void showDatumDetailsDialog(Datum d, String title) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
            "Datum Details: " + title, Dialog.ModalityType.MODELESS);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(500, 400);
        dialog.setLocationRelativeTo(this);

        JTextArea textArea = new JTextArea();
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);

        // Build detailed display
        StringBuilder sb = new StringBuilder();
        sb.append("Type: ").append(com.libreshockwave.vm.DatumFormatter.getTypeName(d)).append("\n\n");
        sb.append("Value:\n");
        sb.append(com.libreshockwave.vm.DatumFormatter.formatDetailed(d, 0));

        textArea.setText(sb.toString());
        textArea.setCaretPosition(0);

        dialog.add(new JScrollPane(textArea), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dialog.dispose());
        buttonPanel.add(closeBtn);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    /**
     * Show a dialog with details about a handler.
     */
    private void showHandlerDetailsDialog(String handlerName) {
        // Find the handler
        ScriptChunk targetScript = null;
        ScriptChunk.Handler targetHandler = null;

        for (ScriptChunk script : allScripts) {
            ScriptChunk.Handler handler = script.findHandler(handlerName);
            if (handler != null) {
                targetScript = script;
                targetHandler = handler;
                break;
            }
        }

        if (targetScript == null || targetHandler == null) {
            JOptionPane.showMessageDialog(this,
                "Handler '" + handlerName + "' not found.",
                "Handler Not Found",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Build the details dialog
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this),
            "Handler: " + handlerName, Dialog.ModalityType.MODELESS);
        dialog.setLayout(new BorderLayout());
        dialog.setSize(600, 500);
        dialog.setLocationRelativeTo(this);

        JTabbedPane tabs = new JTabbedPane();

        // Overview tab
        tabs.addTab("Overview", createOverviewPanel(targetScript, targetHandler));

        // Bytecode tab
        tabs.addTab("Bytecode", createBytecodePanel(targetScript, targetHandler));

        // Literals tab (if any)
        if (!targetScript.literals().isEmpty()) {
            tabs.addTab("Literals", createLiteralsPanel(targetScript));
        }

        // Properties tab (if any)
        if (!targetScript.properties().isEmpty()) {
            tabs.addTab("Properties", createPropertiesPanel(targetScript));
        }

        // Globals tab (if any)
        if (!targetScript.globals().isEmpty()) {
            tabs.addTab("Globals", createGlobalsPanel(targetScript));
        }

        dialog.add(tabs, BorderLayout.CENTER);

        // Close button
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dialog.dispose());
        buttonPanel.add(closeBtn);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    /**
     * Create the overview panel for handler details dialog.
     */
    private JPanel createOverviewPanel(ScriptChunk script, ScriptChunk.Handler handler) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family: monospace; font-size: 11px;'>");

        // Handler name and script
        sb.append("<h3>").append(StringUtils.escapeHtml(script.getHandlerName(handler))).append("</h3>");
        sb.append("<b>Script:</b> ").append(StringUtils.escapeHtml(script.getDisplayName())).append("<br>");
        sb.append("<b>Script Type:</b> ").append(script.getScriptType()).append("<br>");
        sb.append("<b>Script ID:</b> ").append(script.id()).append("<br><br>");

        // Handler info
        sb.append("<b>Bytecode Length:</b> ").append(handler.bytecodeLength()).append(" bytes<br>");
        sb.append("<b>Instruction Count:</b> ").append(handler.instructions().size()).append("<br><br>");

        // Arguments
        sb.append("<b>Arguments (").append(handler.argCount()).append("):</b><br>");
        if (handler.argCount() > 0) {
            sb.append("<ul>");
            for (int i = 0; i < handler.argNameIds().size(); i++) {
                String argName = script.resolveName(handler.argNameIds().get(i));
                sb.append("<li>").append(StringUtils.escapeHtml(argName)).append("</li>");
            }
            sb.append("</ul>");
        } else {
            sb.append("&nbsp;&nbsp;(none)<br>");
        }

        // Local variables
        sb.append("<b>Local Variables (").append(handler.localCount()).append("):</b><br>");
        if (handler.localCount() > 0) {
            sb.append("<ul>");
            for (int i = 0; i < handler.localNameIds().size(); i++) {
                String localName = script.resolveName(handler.localNameIds().get(i));
                sb.append("<li>").append(StringUtils.escapeHtml(localName)).append("</li>");
            }
            sb.append("</ul>");
        } else {
            sb.append("&nbsp;&nbsp;(none)<br>");
        }

        // Globals used
        sb.append("<b>Globals Used:</b> ").append(handler.globalsCount()).append("<br>");

        sb.append("</body></html>");

        JLabel infoLabel = new JLabel(sb.toString());
        infoLabel.setVerticalAlignment(JLabel.TOP);

        JScrollPane scroll = new JScrollPane(infoLabel);
        scroll.setBorder(null);
        panel.add(scroll, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Create the bytecode panel for handler details dialog.
     */
    private JPanel createBytecodePanel(ScriptChunk script, ScriptChunk.Handler handler) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        DefaultListModel<String> model = new DefaultListModel<>();
        for (ScriptChunk.Handler.Instruction instr : handler.instructions()) {
            String annotation = InstructionAnnotator.annotate(script, handler, instr, true);
            StringBuilder line = new StringBuilder();
            line.append(String.format("[%3d] %-14s", instr.offset(), instr.opcode().name()));
            if (instr.argument() != 0 || instr.rawOpcode() >= 0x40) {
                line.append(String.format(" %-4d", instr.argument()));
            } else {
                line.append("     ");
            }
            if (annotation != null && !annotation.isEmpty()) {
                line.append(" ").append(annotation);
            }
            model.addElement(line.toString());
        }

        JList<String> list = new JList<>(model);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane scroll = new JScrollPane(list);
        panel.add(scroll, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Create the literals panel for handler details dialog.
     */
    private JPanel createLiteralsPanel(ScriptChunk script) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        String[] columns = {"#", "Type", "Value"};
        Object[][] data = new Object[script.literals().size()][3];

        for (int i = 0; i < script.literals().size(); i++) {
            ScriptChunk.LiteralEntry lit = script.literals().get(i);
            data[i][0] = i;
            data[i][1] = getLiteralTypeName(lit.type());
            data[i][2] = formatLiteralValue(lit);
        }

        JTable table = new JTable(data, columns);
        table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        table.getColumnModel().getColumn(0).setPreferredWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(300);

        JScrollPane scroll = new JScrollPane(table);
        panel.add(scroll, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Create the properties panel for handler details dialog.
     */
    private JPanel createPropertiesPanel(ScriptChunk script) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        DefaultListModel<String> model = new DefaultListModel<>();
        List<String> propNames = script.getPropertyNames(directorFile != null ? directorFile.getScriptNames() : null);
        for (int i = 0; i < propNames.size(); i++) {
            model.addElement(String.format("[%d] %s", i, propNames.get(i)));
        }

        JList<String> list = new JList<>(model);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        JScrollPane scroll = new JScrollPane(list);
        panel.add(scroll, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Create the globals panel for handler details dialog.
     */
    private JPanel createGlobalsPanel(ScriptChunk script) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        DefaultListModel<String> model = new DefaultListModel<>();
        List<String> globalNames = script.getGlobalNames(directorFile != null ? directorFile.getScriptNames() : null);
        for (int i = 0; i < globalNames.size(); i++) {
            model.addElement(String.format("[%d] %s", i, globalNames.get(i)));
        }

        JList<String> list = new JList<>(model);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        JScrollPane scroll = new JScrollPane(list);
        panel.add(scroll, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Get a human-readable name for a literal type.
     */
    private String getLiteralTypeName(int type) {
        return switch (type) {
            case 1 -> "String";
            case 4 -> "Int";
            case 9 -> "Float";
            default -> "Type " + type;
        };
    }

    /**
     * Format a literal value for display.
     */
    private String formatLiteralValue(ScriptChunk.LiteralEntry lit) {
        Object value = lit.value();
        if (value == null) {
            return "(null)";
        }
        if (value instanceof String s) {
            // Escape and quote strings
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                         .replace("\n", "\\n").replace("\r", "\\r") + "\"";
        }
        if (value instanceof byte[] bytes) {
            if (bytes.length <= 20) {
                StringBuilder sb = new StringBuilder("bytes[");
                for (int i = 0; i < bytes.length; i++) {
                    if (i > 0) sb.append(" ");
                    sb.append(String.format("%02X", bytes[i] & 0xFF));
                }
                sb.append("]");
                return sb.toString();
            }
            return "bytes[" + bytes.length + "]";
        }
        return String.valueOf(value);
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
        this.allScriptItems.clear();

        scriptModel.removeAllElements();
        handlerModel.removeAllElements();
        bytecodeModel.clear();
        scriptFilterField.setText("");

        if (file == null) {
            return;
        }

        // Collect all scripts with their source info, in load order
        Set<Integer> seenScriptIds = new HashSet<>();
        int loadOrder = 0;

        // Get main file name from base path
        String mainFileName = "Main";
        String basePath = file.getBasePath();
        if (basePath != null && !basePath.isEmpty()) {
            // Extract just the filename
            if (basePath.contains("/")) {
                mainFileName = basePath.substring(basePath.lastIndexOf('/') + 1);
            } else if (basePath.contains("\\")) {
                mainFileName = basePath.substring(basePath.lastIndexOf('\\') + 1);
            } else {
                mainFileName = basePath;
            }
        }

        // Collect scripts from main file first
        for (ScriptChunk script : file.getScripts()) {
            if (!seenScriptIds.contains(script.id())) {
                allScripts.add(script);
                seenScriptIds.add(script.id());
                if (!script.handlers().isEmpty()) {
                    allScriptItems.add(new ScriptItem(script, mainFileName, loadOrder++));
                }
            }
        }

        // Then collect scripts from all cast libraries (in their load order)
        if (castLibManager != null) {
            for (CastLib castLib : castLibManager.getCastLibs().values()) {
                // Determine source name: prefer file name, then cast name
                String sourceName = castLib.getFileName();
                if (sourceName == null || sourceName.isEmpty()) {
                    sourceName = castLib.getName();
                }
                if (sourceName == null || sourceName.isEmpty()) {
                    sourceName = "Cast " + castLib.getNumber();
                }
                // Extract just the filename without path
                if (sourceName.contains("/")) {
                    sourceName = sourceName.substring(sourceName.lastIndexOf('/') + 1);
                }
                if (sourceName.contains("\\")) {
                    sourceName = sourceName.substring(sourceName.lastIndexOf('\\') + 1);
                }

                for (ScriptChunk script : castLib.getAllScripts()) {
                    if (!seenScriptIds.contains(script.id())) {
                        allScripts.add(script);
                        seenScriptIds.add(script.id());
                        if (!script.handlers().isEmpty()) {
                            allScriptItems.add(new ScriptItem(script, sourceName, loadOrder++));
                        }
                    }
                }
            }
        }

        // Populate script combo (no sorting - use load order)
        for (ScriptItem item : allScriptItems) {
            scriptModel.addElement(item);
        }

        // Auto-select first script if available
        if (scriptModel.getSize() > 0) {
            scriptCombo.setSelectedIndex(0);
        }
    }

    // Keep references for refresh
    private CastLibManager castLibManager;

    /**
     * Filter scripts based on the filter text field.
     */
    private void filterScripts() {
        String filter = scriptFilterField.getText().trim();

        // Remember current selection
        ScriptItem currentSelection = (ScriptItem) scriptCombo.getSelectedItem();
        int currentScriptId = currentSelection != null ? currentSelection.getScript().id() : -1;

        // Temporarily remove action listener to avoid triggering events during update
        ActionListener[] listeners = scriptCombo.getActionListeners();
        for (ActionListener l : listeners) {
            scriptCombo.removeActionListener(l);
        }

        // Clear and repopulate with filtered items
        scriptModel.removeAllElements();
        ScriptItem firstMatch = null;
        ScriptItem selectedMatch = null;

        for (ScriptItem item : allScriptItems) {
            if (item.matchesFilter(filter)) {
                scriptModel.addElement(item);
                if (firstMatch == null) {
                    firstMatch = item;
                }
                if (item.getScript().id() == currentScriptId) {
                    selectedMatch = item;
                }
            }
        }

        // Restore listeners
        for (ActionListener l : listeners) {
            scriptCombo.addActionListener(l);
        }

        // Select appropriate item
        if (selectedMatch != null) {
            scriptCombo.setSelectedItem(selectedMatch);
        } else if (firstMatch != null) {
            scriptCombo.setSelectedItem(firstMatch);
            onScriptSelected();  // Trigger handler update
        }
    }

    /**
     * Filter handlers based on the filter text field.
     */
    private void filterHandlers() {
        String filter = handlerFilterField.getText().trim();

        // Remember current selection
        HandlerItem currentSelection = (HandlerItem) handlerCombo.getSelectedItem();
        String currentHandlerName = currentSelection != null ?
            currentSelection.getScript().getHandlerName(currentSelection.getHandler()) : null;

        // Temporarily remove action listener to avoid triggering events during update
        ActionListener[] listeners = handlerCombo.getActionListeners();
        for (ActionListener l : listeners) {
            handlerCombo.removeActionListener(l);
        }

        // Clear and repopulate with filtered items
        handlerModel.removeAllElements();
        HandlerItem firstMatch = null;
        HandlerItem selectedMatch = null;

        for (HandlerItem item : allHandlerItems) {
            if (item.matchesFilter(filter)) {
                handlerModel.addElement(item);
                if (firstMatch == null) {
                    firstMatch = item;
                }
                if (currentHandlerName != null &&
                    item.getScript().getHandlerName(item.getHandler()).equals(currentHandlerName)) {
                    selectedMatch = item;
                }
            }
        }

        // Restore listeners
        for (ActionListener l : listeners) {
            handlerCombo.addActionListener(l);
        }

        // Select appropriate item
        if (selectedMatch != null) {
            handlerCombo.setSelectedItem(selectedMatch);
        } else if (firstMatch != null) {
            handlerCombo.setSelectedItem(firstMatch);
            onHandlerSelected();  // Trigger bytecode update
        }
    }

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
        int selectedScriptId = selectedScript != null ? selectedScript.getScript().id() : -1;
        String selectedHandlerName = selectedHandler != null ? selectedHandler.getScript().getHandlerName(selectedHandler.getHandler()) : null;

        // Reload scripts
        setDirectorFile(directorFile, castLibManager);

        // Try to restore selection
        if (selectedScriptId >= 0) {
            for (int i = 0; i < scriptModel.getSize(); i++) {
                if (scriptModel.getElementAt(i).getScript().id() == selectedScriptId) {
                    scriptCombo.setSelectedIndex(i);
                    // Also try to restore handler selection
                    if (selectedHandlerName != null) {
                        for (int j = 0; j < handlerModel.getSize(); j++) {
                            HandlerItem h = handlerModel.getElementAt(j);
                            if (h.getScript().getHandlerName(h.getHandler()).equals(selectedHandlerName)) {
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
        allHandlerItems.clear();
        handlerFilterField.setText("");

        if (selected == null) {
            return;
        }

        ScriptChunk script = selected.getScript();
        for (ScriptChunk.Handler handler : script.handlers()) {
            HandlerItem item = new HandlerItem(script, handler);
            allHandlerItems.add(item);
            handlerModel.addElement(item);
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
        browseScript = selected.getScript();
        browseHandler = selected.getHandler();
        currentScriptId = browseScript.id();

        loadHandlerBytecode(selected.getScript(), selected.getHandler());
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
            Breakpoint bp = controller != null ? controller.getBreakpoint(script.id(), instr.offset()) : null;

            InstructionDisplayItem item = new InstructionDisplayItem(
                instr.offset(),
                handler.instructions().indexOf(instr),
                instr.opcode().name(),
                instr.argument(),
                annotation,
                bp != null
            );
            item.setBreakpoint(bp);
            bytecodeModel.addElement(item);
            currentInstructions.add(item);
        }
    }

    /**
     * Build annotation string for an instruction.
     */
    private String buildAnnotation(ScriptChunk script, ScriptChunk.Handler handler, ScriptChunk.Handler.Instruction instr) {
        return InstructionAnnotator.annotate(script, handler, instr, true);
    }

    /**
     * Clear the bytecode display and reset browse mode.
     */
    public void clear() {
        bytecodeModel.clear();
        currentInstructions.clear();
        allScriptItems.clear();
        allHandlerItems.clear();
        browseMode = false;
        browseScript = null;
        browseHandler = null;
        currentScriptId = -1;
        currentInstructionIndex = -1;
        statusLabel.setText("Status: Running");
        handlerLabel.setText("Handler: -");
        scriptFilterField.setText("");
        handlerFilterField.setText("");
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
            localsTableModel.setVariables(snapshot.locals());

            // Update globals
            globalsTableModel.setVariables(snapshot.globals());

            // Update watches
            if (snapshot.watchResults() != null) {
                watchesTableModel.setWatches(snapshot.watchResults());
            } else {
                refreshWatches();
            }

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
                    Breakpoint bp = controller.getBreakpoint(currentScriptId, item.getOffset());
                    item.setBreakpoint(bp);
                    item.setHasBreakpoint(bp != null);
                }
                bytecodeList.repaint();
            }
        });
    }

    @Override
    public void onLogPointHit(Breakpoint bp, String message) {
        // Log to console since debug log tab was removed
        System.out.println(String.format("[LogPoint] Script %d, offset %d: %s", bp.scriptId(), bp.offset(), message));
    }

    @Override
    public void onWatchExpressionsChanged() {
        refreshWatches();
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
            if (scriptModel.getElementAt(i).getScript() == script) {
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
            if (handlerModel.getElementAt(i).getHandler() == handler) {
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
            if (bytecodeModel.get(i).getOffset() == offset) {
                return i;
            }
        }
        return -1;
    }

    private void highlightCurrentInstruction(int bytecodeIndex) {
        // Find the instruction with matching bytecode index
        for (int i = 0; i < bytecodeModel.size(); i++) {
            InstructionDisplayItem item = bytecodeModel.get(i);
            boolean wasCurrent = item.isCurrent();
            item.setCurrent(item.getIndex() == bytecodeIndex);
            if (item.isCurrent() && !wasCurrent) {
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

}
