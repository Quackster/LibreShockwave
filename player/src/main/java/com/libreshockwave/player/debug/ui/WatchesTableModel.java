package com.libreshockwave.player.debug.ui;

import com.libreshockwave.player.debug.WatchExpression;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Table model for watch expressions display in the bytecode debugger.
 */
public class WatchesTableModel extends AbstractTableModel {
    private List<WatchExpression> watches = new ArrayList<>();
    private final String[] columns = {"Expression", "Type", "Value"};

    public void setWatches(List<WatchExpression> watches) {
        this.watches = watches != null ? new ArrayList<>(watches) : new ArrayList<>();
        fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
        return watches.size();
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
        if (rowIndex >= watches.size()) return "";
        WatchExpression watch = watches.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> watch.expression();
            case 1 -> watch.getTypeName();
            case 2 -> watch.getResultDisplay();
            default -> "";
        };
    }
}
