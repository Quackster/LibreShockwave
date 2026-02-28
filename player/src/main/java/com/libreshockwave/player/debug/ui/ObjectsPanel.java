package com.libreshockwave.player.debug.ui;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.DatumFormatter;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Panel displaying runtime objects: Timeouts, Globals, and Movie Properties.
 * Used as the "Objects" tab in StateInspectionTabs.
 */
public class ObjectsPanel extends JPanel {

    private final TimeoutTableModel timeoutTableModel;
    private final VariablesTableModel globalsTableModel;
    private final MoviePropsTableModel moviePropsTableModel;

    private final JTable timeoutTable;
    private final JTable globalsTable;
    private final JTable moviePropsTable;

    private StateInspectionTabs.DatumClickListener datumClickListener;

    public ObjectsPanel() {
        setLayout(new BorderLayout());

        JPanel sectionsPanel = new JPanel();
        sectionsPanel.setLayout(new BoxLayout(sectionsPanel, BoxLayout.Y_AXIS));

        // Timeouts section
        timeoutTableModel = new TimeoutTableModel();
        timeoutTable = new JTable(timeoutTableModel);
        timeoutTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        timeoutTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && datumClickListener != null) {
                    int row = timeoutTable.getSelectedRow();
                    if (row >= 0) {
                        Datum d = timeoutTableModel.getDatum(row);
                        String name = timeoutTableModel.getName(row);
                        if (d != null) {
                            datumClickListener.onDatumDoubleClicked(d, "Timeout: " + name);
                        }
                    }
                }
            }
        });
        JScrollPane timeoutScroll = new JScrollPane(timeoutTable);
        timeoutScroll.setPreferredSize(new Dimension(0, 120));
        JPanel timeoutSection = createSection("Timeouts", timeoutScroll);
        sectionsPanel.add(timeoutSection);

        // Globals section
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
        JScrollPane globalsScroll = new JScrollPane(globalsTable);
        globalsScroll.setPreferredSize(new Dimension(0, 150));
        JPanel globalsSection = createSection("Globals", globalsScroll);
        sectionsPanel.add(globalsSection);

        // Movie Properties section
        moviePropsTableModel = new MoviePropsTableModel();
        moviePropsTable = new JTable(moviePropsTableModel);
        moviePropsTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        moviePropsTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && datumClickListener != null) {
                    int row = moviePropsTable.getSelectedRow();
                    if (row >= 0) {
                        Datum d = moviePropsTableModel.getDatum(row);
                        String name = moviePropsTableModel.getName(row);
                        if (d != null) {
                            datumClickListener.onDatumDoubleClicked(d, "Movie: " + name);
                        }
                    }
                }
            }
        });
        JScrollPane moviePropsScroll = new JScrollPane(moviePropsTable);
        moviePropsScroll.setPreferredSize(new Dimension(0, 150));
        JPanel moviePropsSection = createSection("Movie Properties", moviePropsScroll);
        sectionsPanel.add(moviePropsSection);

        add(new JScrollPane(sectionsPanel), BorderLayout.CENTER);
    }

    private JPanel createSection(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    public void setDatumClickListener(StateInspectionTabs.DatumClickListener listener) {
        this.datumClickListener = listener;
    }

    public void setTimeouts(List<TimeoutTableModel.TimeoutSnapshot> timeouts) {
        timeoutTableModel.setTimeouts(timeouts);
    }

    public void setGlobals(Map<String, Datum> globals) {
        globalsTableModel.setVariables(globals);
    }

    public void setMovieProperties(List<Map.Entry<String, Datum>> props) {
        moviePropsTableModel.setProperties(props);
    }

    /**
     * Simple table model for movie property name/value pairs.
     */
    static class MoviePropsTableModel extends AbstractTableModel {
        private final List<Map.Entry<String, Datum>> properties = new ArrayList<>();
        private static final String[] COLUMNS = {"Property", "Value"};

        public void setProperties(List<Map.Entry<String, Datum>> props) {
            this.properties.clear();
            if (props != null) {
                this.properties.addAll(props);
            }
            fireTableDataChanged();
        }

        public Datum getDatum(int row) {
            if (row >= 0 && row < properties.size()) {
                return properties.get(row).getValue();
            }
            return null;
        }

        public String getName(int row) {
            if (row >= 0 && row < properties.size()) {
                return properties.get(row).getKey();
            }
            return null;
        }

        @Override
        public int getRowCount() {
            return properties.size();
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
            if (rowIndex >= properties.size()) return "";
            Map.Entry<String, Datum> entry = properties.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> entry.getKey();
                case 1 -> DatumFormatter.format(entry.getValue());
                default -> "";
            };
        }
    }
}
