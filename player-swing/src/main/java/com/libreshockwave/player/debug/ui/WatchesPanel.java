package com.libreshockwave.player.debug.ui;

import com.libreshockwave.player.debug.DebugController;
import com.libreshockwave.player.debug.WatchExpression;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Panel displaying watch expressions with add/remove/clear buttons.
 */
public class WatchesPanel extends JPanel {

    /**
     * Listener for watch panel events.
     */
    public interface WatchesPanelListener {
        void onAddWatch(String expression);
        void onRemoveWatch(String watchId);
        void onEditWatch(String watchId, String newExpression);
        void onClearWatches();
    }

    private final WatchesTableModel tableModel;
    private final JTable table;
    private WatchesPanelListener listener;

    public WatchesPanel() {
        setLayout(new BorderLayout());

        tableModel = new WatchesTableModel();
        table = new JTable(tableModel);
        table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(60);
        table.getColumnModel().getColumn(2).setPreferredWidth(200);

        // Double-click to edit
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && listener != null) {
                    editSelectedWatch();
                }
            }
        });

        add(new JScrollPane(table), BorderLayout.CENTER);
        add(createButtonPanel(), BorderLayout.SOUTH);
    }

    private JPanel createButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JButton addBtn = new JButton("+");
        addBtn.setToolTipText("Add watch expression");
        addBtn.addActionListener(e -> showAddWatchDialog());
        buttonPanel.add(addBtn);

        JButton removeBtn = new JButton("-");
        removeBtn.setToolTipText("Remove selected watch");
        removeBtn.addActionListener(e -> removeSelectedWatch());
        buttonPanel.add(removeBtn);

        JButton clearBtn = new JButton("Clear");
        clearBtn.setToolTipText("Clear all watches");
        clearBtn.addActionListener(e -> {
            if (listener != null) {
                listener.onClearWatches();
            }
        });
        buttonPanel.add(clearBtn);

        return buttonPanel;
    }

    private void showAddWatchDialog() {
        String expr = JOptionPane.showInputDialog(
            this,
            "Enter watch expression:",
            "Add Watch",
            JOptionPane.PLAIN_MESSAGE
        );
        if (expr != null && !expr.trim().isEmpty() && listener != null) {
            listener.onAddWatch(expr.trim());
        }
    }

    private void removeSelectedWatch() {
        int row = table.getSelectedRow();
        if (row >= 0 && listener != null) {
            List<WatchExpression> watches = getWatchesFromModel();
            if (row < watches.size()) {
                listener.onRemoveWatch(watches.get(row).id());
            }
        }
    }

    private void editSelectedWatch() {
        int row = table.getSelectedRow();
        if (row >= 0) {
            List<WatchExpression> watches = getWatchesFromModel();
            if (row < watches.size()) {
                WatchExpression watch = watches.get(row);
                String newExpr = JOptionPane.showInputDialog(
                    this,
                    "Edit watch expression:",
                    watch.expression()
                );
                if (newExpr != null && !newExpr.trim().isEmpty() && listener != null) {
                    listener.onEditWatch(watch.id(), newExpr.trim());
                }
            }
        }
    }

    private List<WatchExpression> getWatchesFromModel() {
        // The model stores the watches internally
        // We need a way to access them - add a method to get from controller
        return List.of(); // Will be overridden by setWatches
    }

    private List<WatchExpression> currentWatches = List.of();

    /**
     * Set the listener for watch panel events.
     */
    public void setListener(WatchesPanelListener listener) {
        this.listener = listener;
    }

    /**
     * Update the watch expressions display.
     */
    public void setWatches(List<WatchExpression> watches) {
        this.currentWatches = watches != null ? watches : List.of();
        tableModel.setWatches(this.currentWatches);
    }

    /**
     * Get the currently displayed watches.
     */
    public List<WatchExpression> getWatches() {
        return currentWatches;
    }

    /**
     * Convenience method to create a WatchesPanel connected to a controller.
     */
    public static WatchesPanel createWithController(DebugController controller, Runnable onRefresh) {
        WatchesPanel panel = new WatchesPanel();
        panel.setListener(new WatchesPanelListener() {
            @Override
            public void onAddWatch(String expression) {
                if (controller != null) {
                    controller.addWatchExpression(expression);
                    onRefresh.run();
                }
            }

            @Override
            public void onRemoveWatch(String watchId) {
                if (controller != null) {
                    controller.removeWatchExpression(watchId);
                    onRefresh.run();
                }
            }

            @Override
            public void onEditWatch(String watchId, String newExpression) {
                if (controller != null) {
                    controller.updateWatchExpression(watchId, newExpression);
                    onRefresh.run();
                }
            }

            @Override
            public void onClearWatches() {
                if (controller != null) {
                    controller.clearWatchExpressions();
                    onRefresh.run();
                }
            }
        });
        return panel;
    }
}
