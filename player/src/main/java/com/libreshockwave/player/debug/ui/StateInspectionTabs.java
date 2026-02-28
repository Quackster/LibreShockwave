package com.libreshockwave.player.debug.ui;

import com.libreshockwave.player.debug.WatchExpression;
import com.libreshockwave.vm.Datum;

import javax.swing.*;
import javax.swing.event.ChangeListener;
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
    private final ObjectsPanel objectsPanel;

    private final JTable stackTable;
    private final JTable localsTable;
    private final JTable globalsTable;

    private DatumClickListener datumClickListener;
    private int objectsTabIndex;

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
        localsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && datumClickListener != null) {
                    int row = localsTable.getSelectedRow();
                    if (row >= 0) {
                        Datum d = localsTableModel.getDatum(row);
                        String name = localsTableModel.getName(row);
                        if (d != null) {
                            datumClickListener.onDatumDoubleClicked(d, "Local: " + name);
                        }
                    }
                }
            }
        });
        addTab("Locals", new JScrollPane(localsTable));

        // Globals table
        globalsTableModel = new VariablesTableModel();
        globalsTable = new JTable(globalsTableModel);
        globalsTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        globalsTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        globalsTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        globalsTable.getColumnModel().getColumn(2).setPreferredWidth(200);
        globalsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && datumClickListener != null) {
                    int row = globalsTable.getSelectedRow();
                    if (row >= 0) {
                        Datum d = globalsTableModel.getDatum(row);
                        String name = globalsTableModel.getName(row);
                        if (d != null) {
                            datumClickListener.onDatumDoubleClicked(d, "Global: " + name);
                        }
                    }
                }
            }
        });
        addTab("Globals", new JScrollPane(globalsTable));

        // Watches panel
        watchesPanel = new WatchesPanel();
        addTab("Watches", watchesPanel);

        // Objects panel
        objectsPanel = new ObjectsPanel();
        addTab("Objects", objectsPanel);
        objectsTabIndex = getTabCount() - 1;
    }

    /**
     * Set listener for datum double-click events.
     */
    public void setDatumClickListener(DatumClickListener listener) {
        this.datumClickListener = listener;
        objectsPanel.setDatumClickListener(listener);
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

    /**
     * Get the objects panel.
     */
    public ObjectsPanel getObjectsPanel() {
        return objectsPanel;
    }

    /**
     * Check if the Objects tab is currently selected.
     */
    public boolean isObjectsTabSelected() {
        return getSelectedIndex() == objectsTabIndex;
    }

    /**
     * Get the index of the Objects tab.
     */
    public int getObjectsTabIndex() {
        return objectsTabIndex;
    }

    /**
     * Update the Objects panel with snapshot data.
     */
    public void setObjects(List<TimeoutTableModel.TimeoutSnapshot> timeouts,
                           Map<String, Datum> globals,
                           List<Map.Entry<String, Datum>> movieProps) {
        objectsPanel.setTimeouts(timeouts);
        objectsPanel.setGlobals(globals);
        objectsPanel.setMovieProperties(movieProps);
    }
}
