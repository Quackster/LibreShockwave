package com.libreshockwave.player.debug.ui;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.DatumFormatter;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Table model for displaying active timeouts in the Objects panel.
 */
public class TimeoutTableModel extends AbstractTableModel {

    public record TimeoutSnapshot(String name, int periodMs, String handler, Datum target, boolean persistent) {}

    private final List<TimeoutSnapshot> timeouts = new ArrayList<>();
    private static final String[] COLUMNS = {"Name", "Period (ms)", "Handler", "Target", "Persistent"};

    public void setTimeouts(List<TimeoutSnapshot> snapshots) {
        this.timeouts.clear();
        if (snapshots != null) {
            this.timeouts.addAll(snapshots);
        }
        fireTableDataChanged();
    }

    public Datum getDatum(int row) {
        if (row >= 0 && row < timeouts.size()) {
            return timeouts.get(row).target();
        }
        return null;
    }

    public String getName(int row) {
        if (row >= 0 && row < timeouts.size()) {
            return timeouts.get(row).name();
        }
        return null;
    }

    @Override
    public int getRowCount() {
        return timeouts.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (rowIndex >= timeouts.size()) return "";
        TimeoutSnapshot t = timeouts.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> t.name();
            case 1 -> t.periodMs();
            case 2 -> t.handler();
            case 3 -> DatumFormatter.format(t.target());
            case 4 -> t.persistent() ? "Yes" : "No";
            default -> "";
        };
    }
}
