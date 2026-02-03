package com.libreshockwave.player.debug.ui;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.DatumFormatter;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * Table model for stack display in the bytecode debugger.
 */
public class StackTableModel extends AbstractTableModel {
    private List<Datum> stack = new ArrayList<>();
    private final String[] columns = {"#", "Type", "Value"};

    public void setStack(List<Datum> stack) {
        this.stack = stack != null ? new ArrayList<>(stack) : new ArrayList<>();
        fireTableDataChanged();
    }

    /**
     * Get the Datum at a specific row index.
     */
    public Datum getDatum(int rowIndex) {
        if (rowIndex >= 0 && rowIndex < stack.size()) {
            return stack.get(rowIndex);
        }
        return null;
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
            case 1 -> DatumFormatter.getTypeName(d);
            case 2 -> DatumFormatter.format(d);
            default -> "";
        };
    }
}
