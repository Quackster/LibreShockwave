package com.libreshockwave.player.debug.ui;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.DatumFormatter;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Table model for named variables display (locals, globals) in the bytecode debugger.
 */
public class VariablesTableModel extends AbstractTableModel {
    private final List<Map.Entry<String, Datum>> variables = new ArrayList<>();
    private final String[] columns = {"Name", "Type", "Value"};

    public void setVariables(Map<String, Datum> variablesMap) {
        this.variables.clear();
        if (variablesMap != null) {
            this.variables.addAll(variablesMap.entrySet());
        }
        fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
        return variables.size();
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public String getColumnName(int column) {
        return columns[column];
    }

    public Datum getDatum(int row) {
        if (row >= 0 && row < variables.size()) {
            return variables.get(row).getValue();
        }
        return null;
    }

    public String getName(int row) {
        if (row >= 0 && row < variables.size()) {
            return variables.get(row).getKey();
        }
        return null;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (rowIndex >= variables.size()) return "";
        Map.Entry<String, Datum> entry = variables.get(rowIndex);
        Datum d = entry.getValue();
        return switch (columnIndex) {
            case 0 -> entry.getKey();
            case 1 -> DatumFormatter.getTypeName(d);
            case 2 -> DatumFormatter.format(d);
            default -> "";
        };
    }
}
