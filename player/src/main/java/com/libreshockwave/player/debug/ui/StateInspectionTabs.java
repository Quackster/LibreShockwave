package com.libreshockwave.player.debug.ui;

import com.libreshockwave.player.debug.WatchExpression;
import com.libreshockwave.vm.Datum;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;

/**
 * Tabbed pane containing Stack, Locals, Globals, and Watches tabs.
 */
public class StateInspectionTabs extends JTabbedPane {

    /**
     * Listener for datum double-click events.
     */
    public interface DatumClickListener {
        void onDatumDoubleClicked(Datum datum, String title);
    }

    private final StackTableModel stackTableModel;
    private final VariablesTableModel localsTableModel;
    private final VariablesTableModel globalsTableModel;
    private final WatchesPanel watchesPanel;

    private final JTable stackTable;
    private final JTable localsTable;
    private final JTable globalsTable;

    private DatumClickListener datumClickListener;

    public StateInspectionTabs() {
        super(JTabbedPane.TOP);

        // Stack table
        stackTableModel = new StackTableModel();
        stackTable = new JTable(stackTableModel);
        stackTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        stackTable.getColumnModel().getColumn(0).setPreferredWidth(40);
        stackTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        stackTable.getColumnModel().getColumn(2).setPreferredWidth(200);
        stackTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && datumClickListener != null) {
                    int row = stackTable.getSelectedRow();
                    if (row >= 0) {
                        Datum d = stackTableModel.getDatum(row);
                        if (d != null) {
                            datumClickListener.onDatumDoubleClicked(d, "Stack[" + row + "]");
                        }
                    }
                }
            }
        });
        addTab("Stack", new JScrollPane(stackTable));

        // Locals table
        localsTableModel = new VariablesTableModel();
        localsTable = new JTable(localsTableModel);
        localsTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        localsTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        localsTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        localsTable.getColumnModel().getColumn(2).setPreferredWidth(200);
        addTab("Locals", new JScrollPane(localsTable));

        // Globals table
        globalsTableModel = new VariablesTableModel();
        globalsTable = new JTable(globalsTableModel);
        globalsTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        globalsTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        globalsTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        globalsTable.getColumnModel().getColumn(2).setPreferredWidth(200);
        addTab("Globals", new JScrollPane(globalsTable));

        // Watches panel
        watchesPanel = new WatchesPanel();
        addTab("Watches", watchesPanel);
    }

    /**
     * Set listener for datum double-click events.
     */
    public void setDatumClickListener(DatumClickListener listener) {
        this.datumClickListener = listener;
    }

    /**
     * Update the stack display.
     */
    public void setStack(List<Datum> stack) {
        stackTableModel.setStack(stack);
    }

    /**
     * Update the locals display.
     */
    public void setLocals(Map<String, Datum> locals) {
        localsTableModel.setVariables(locals);
    }

    /**
     * Update the globals display.
     */
    public void setGlobals(Map<String, Datum> globals) {
        globalsTableModel.setVariables(globals);
    }

    /**
     * Update the watches display.
     */
    public void setWatches(List<WatchExpression> watches) {
        watchesPanel.setWatches(watches);
    }

    /**
     * Get the watches panel for additional configuration.
     */
    public WatchesPanel getWatchesPanel() {
        return watchesPanel;
    }

    /**
     * Get the stack table model.
     */
    public StackTableModel getStackTableModel() {
        return stackTableModel;
    }

    /**
     * Get the locals table model.
     */
    public VariablesTableModel getLocalsTableModel() {
        return localsTableModel;
    }

    /**
     * Get the globals table model.
     */
    public VariablesTableModel getGlobalsTableModel() {
        return globalsTableModel;
    }
}
