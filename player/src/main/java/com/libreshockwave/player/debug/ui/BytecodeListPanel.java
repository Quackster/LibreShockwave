package com.libreshockwave.player.debug.ui;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.player.debug.Breakpoint;
import com.libreshockwave.player.debug.DebugController;
import com.libreshockwave.vm.trace.InstructionAnnotator;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel containing the bytecode JList with mouse handling and highlighting.
 */
public class BytecodeListPanel extends JPanel {

    /**
     * Listener for bytecode list events.
     */
    public interface BytecodeListListener {
        void onBreakpointToggleRequested(int offset);
        void onNavigateToHandler(String handlerName);
        void onShowHandlerDetails(String handlerName);
    }

    private final DefaultListModel<InstructionDisplayItem> bytecodeModel;
    private final JList<InstructionDisplayItem> bytecodeList;
    private final BytecodeContextMenu contextMenu;
    private final List<InstructionDisplayItem> currentInstructions = new ArrayList<>();

    private DebugController controller;
    private HandlerNavigator navigator;
    private int currentScriptId = -1;
    private String currentHandlerName = null;
    private int currentInstructionIndex = -1;
    private BytecodeListListener listener;

    public BytecodeListPanel() {
        setLayout(new BorderLayout());
        setBorder(new TitledBorder("Bytecode"));

        bytecodeModel = new DefaultListModel<>();
        bytecodeList = new JList<>(bytecodeModel);
        bytecodeList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        bytecodeList.setCellRenderer(new BytecodeCellRenderer());
        bytecodeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        initMouseListeners();

        contextMenu = new BytecodeContextMenu(bytecodeList, bytecodeModel);

        JScrollPane bytecodeScroll = new JScrollPane(bytecodeList);
        bytecodeScroll.setPreferredSize(new Dimension(480, 200));
        add(bytecodeScroll, BorderLayout.CENTER);

        // Legend
        JLabel legend = new JLabel("<html>" +
            "<font color='red'>\u25CF</font>=breakpoint &nbsp; " +
            "<font color='gray'>\u25CB</font>=disabled &nbsp; " +
            "<font color='#DAA520'>\u25B6</font>=current &nbsp; " +
            "<font color='blue'><u>blue</u></font>=navigate</html>");
        legend.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        add(legend, BorderLayout.SOUTH);
    }

    private void initMouseListeners() {
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
                    if (controller != null && currentScriptId >= 0 && currentHandlerName != null) {
                        controller.toggleBreakpoint(currentScriptId, currentHandlerName, item.getOffset());
                        item.setHasBreakpoint(controller.hasBreakpoint(currentScriptId, currentHandlerName, item.getOffset()));
                        bytecodeList.repaint();
                    }
                    return;
                }

                // Single click on navigable call instruction -> navigate to handler definition
                if (e.getClickCount() == 1 && item.isNavigableCall()) {
                    String targetName = item.getCallTargetName();
                    if (targetName != null && listener != null) {
                        listener.onNavigateToHandler(targetName);
                    }
                }
            }
        });

        // Change cursor to hand when hovering over navigable call instructions
        bytecodeList.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int index = bytecodeList.locationToIndex(e.getPoint());
                if (index >= 0 && index < bytecodeModel.size()) {
                    InstructionDisplayItem item = bytecodeModel.get(index);
                    if (item.isNavigableCall() && e.getX() >= 20) {
                        bytecodeList.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                        return;
                    }
                }
                bytecodeList.setCursor(Cursor.getDefaultCursor());
            }
        });
    }

    /**
     * Load bytecode for a handler.
     */
    public void loadHandlerBytecode(ScriptChunk script, ScriptChunk.Handler handler) {
        bytecodeModel.clear();
        currentInstructions.clear();
        currentInstructionIndex = -1;
        currentScriptId = script.id();
        currentHandlerName = script.getHandlerName(handler);

        for (ScriptChunk.Handler.Instruction instr : handler.instructions()) {
            String annotation = InstructionAnnotator.annotate(script, handler, instr, true);
            Breakpoint bp = controller != null ? controller.getBreakpoint(script.id(), currentHandlerName, instr.offset()) : null;

            InstructionDisplayItem item = new InstructionDisplayItem(
                instr.offset(),
                handler.instructions().indexOf(instr),
                instr.opcode().name(),
                instr.argument(),
                annotation,
                bp != null
            );
            item.setBreakpoint(bp);

            // Check if call target exists in the CCT (not a builtin)
            if (item.isCallInstruction() && navigator != null) {
                String targetName = item.getCallTargetName();
                if (targetName != null) {
                    HandlerNavigator.HandlerLocation location = navigator.findHandler(targetName);
                    item.setNavigable(location.found());
                }
            }

            bytecodeModel.addElement(item);
            currentInstructions.add(item);
        }

        contextMenu.setCurrentScriptId(script.id());
        contextMenu.setCurrentHandlerName(currentHandlerName);
    }

    /**
     * Highlight the current instruction by bytecode index.
     */
    public void highlightCurrentInstruction(int bytecodeIndex) {
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

    /**
     * Update breakpoint markers for all instructions.
     */
    public void refreshBreakpointMarkers() {
        if (controller != null && currentScriptId >= 0 && currentHandlerName != null) {
            for (int i = 0; i < bytecodeModel.size(); i++) {
                InstructionDisplayItem item = bytecodeModel.get(i);
                Breakpoint bp = controller.getBreakpoint(currentScriptId, currentHandlerName, item.getOffset());
                item.setBreakpoint(bp);
                item.setHasBreakpoint(bp != null);
            }
            bytecodeList.repaint();
        }
    }

    /**
     * Clear the bytecode display.
     */
    public void clear() {
        bytecodeModel.clear();
        currentInstructions.clear();
        currentInstructionIndex = -1;
        currentScriptId = -1;
        currentHandlerName = null;
        bytecodeList.clearSelection();
    }

    /**
     * Set the debug controller.
     */
    public void setController(DebugController controller) {
        this.controller = controller;
        contextMenu.setController(controller);
    }

    /**
     * Set the handler navigator for checking if call targets exist.
     */
    public void setNavigator(HandlerNavigator navigator) {
        this.navigator = navigator;
    }

    /**
     * Set the current script ID.
     */
    public void setCurrentScriptId(int scriptId) {
        this.currentScriptId = scriptId;
        contextMenu.setCurrentScriptId(scriptId);
    }

    /**
     * Get the current script ID.
     */
    public int getCurrentScriptId() {
        return currentScriptId;
    }

    /**
     * Set the current handler name.
     */
    public void setCurrentHandlerName(String handlerName) {
        this.currentHandlerName = handlerName;
        contextMenu.setCurrentHandlerName(handlerName);
    }

    /**
     * Get the current handler name.
     */
    public String getCurrentHandlerName() {
        return currentHandlerName;
    }

    /**
     * Set the listener for bytecode list events.
     */
    public void setListener(BytecodeListListener listener) {
        this.listener = listener;
        contextMenu.setListener(new BytecodeContextMenu.BytecodeListListener() {
            @Override
            public void onBreakpointToggleRequested(int offset) {
                listener.onBreakpointToggleRequested(offset);
            }

            @Override
            public void onNavigateToHandler(String handlerName) {
                listener.onNavigateToHandler(handlerName);
            }
        });
    }

    /**
     * Get the bytecode list model.
     */
    public DefaultListModel<InstructionDisplayItem> getBytecodeModel() {
        return bytecodeModel;
    }

    /**
     * Get the bytecode list.
     */
    public JList<InstructionDisplayItem> getBytecodeList() {
        return bytecodeList;
    }

    /**
     * Get the current instruction index.
     */
    public int getCurrentInstructionIndex() {
        return currentInstructionIndex;
    }
}
