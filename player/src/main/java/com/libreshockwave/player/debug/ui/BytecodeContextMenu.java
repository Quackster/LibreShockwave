package com.libreshockwave.player.debug.ui;

import com.libreshockwave.player.debug.Breakpoint;
import com.libreshockwave.player.debug.DebugController;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Right-click context menu for the bytecode list.
 * Provides breakpoint management and navigation actions.
 */
public class BytecodeContextMenu extends JPopupMenu {

    /**
     * Listener for bytecode list context menu actions.
     */
    public interface BytecodeListListener {
        void onBreakpointToggleRequested(int offset);
        void onNavigateToHandler(String handlerName);
    }

    private final JList<InstructionDisplayItem> bytecodeList;
    private final DefaultListModel<InstructionDisplayItem> bytecodeModel;
    private DebugController controller;
    private int currentScriptId = -1;
    private BytecodeListListener listener;

    public BytecodeContextMenu(JList<InstructionDisplayItem> bytecodeList,
                                DefaultListModel<InstructionDisplayItem> bytecodeModel) {
        this.bytecodeList = bytecodeList;
        this.bytecodeModel = bytecodeModel;
        initMenu();
        attachToList();
    }

    private void initMenu() {
        // Breakpoint actions
        JMenuItem toggleBpItem = new JMenuItem("Toggle Breakpoint");
        toggleBpItem.addActionListener(e -> {
            InstructionDisplayItem item = getSelectedItem();
            if (item != null && controller != null && currentScriptId >= 0) {
                controller.toggleBreakpoint(currentScriptId, item.getOffset());
                updateBreakpointDisplay(item);
            }
        });
        add(toggleBpItem);

        JMenuItem enableDisableItem = new JMenuItem("Enable/Disable Breakpoint");
        enableDisableItem.addActionListener(e -> {
            InstructionDisplayItem item = getSelectedItem();
            if (item != null && controller != null && currentScriptId >= 0) {
                Breakpoint bp = controller.getBreakpoint(currentScriptId, item.getOffset());
                if (bp != null) {
                    controller.toggleBreakpointEnabled(currentScriptId, item.getOffset());
                    updateBreakpointDisplay(item);
                }
            }
        });
        add(enableDisableItem);

        addSeparator();

        // Navigation actions for navigable call instructions (not builtins)
        JMenuItem goToDefItem = new JMenuItem("Go to Definition");
        goToDefItem.addActionListener(e -> {
            InstructionDisplayItem item = getSelectedItem();
            if (item != null && item.isNavigableCall()) {
                String targetName = item.getCallTargetName();
                if (targetName != null && listener != null) {
                    listener.onNavigateToHandler(targetName);
                }
            }
        });
        add(goToDefItem);

        JMenuItem viewDetailsItem = new JMenuItem("View Handler Details...");
        viewDetailsItem.addActionListener(e -> {
            InstructionDisplayItem item = getSelectedItem();
            if (item != null && item.isNavigableCall()) {
                String targetName = item.getCallTargetName();
                if (targetName != null && listener != null) {
                    listener.onNavigateToHandler(targetName);
                }
            }
        });
        add(viewDetailsItem);
    }

    private void attachToList() {
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
                        show(bytecodeList, e.getX(), e.getY());
                    }
                }
            }
        });
    }

    private InstructionDisplayItem getSelectedItem() {
        int index = bytecodeList.getSelectedIndex();
        if (index >= 0 && index < bytecodeModel.size()) {
            return bytecodeModel.get(index);
        }
        return null;
    }

    private void updateBreakpointDisplay(InstructionDisplayItem item) {
        if (controller != null && currentScriptId >= 0) {
            Breakpoint bp = controller.getBreakpoint(currentScriptId, item.getOffset());
            item.setBreakpoint(bp);
            item.setHasBreakpoint(bp != null);
            bytecodeList.repaint();
        }
    }

    /**
     * Set the debug controller.
     */
    public void setController(DebugController controller) {
        this.controller = controller;
    }

    /**
     * Set the current script ID.
     */
    public void setCurrentScriptId(int scriptId) {
        this.currentScriptId = scriptId;
    }

    /**
     * Set the listener for menu actions.
     */
    public void setListener(BytecodeListListener listener) {
        this.listener = listener;
    }
}
