package com.libreshockwave.editor.panel;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.editor.EditorContext;
import com.libreshockwave.editor.cast.CastGridPanel;
import com.libreshockwave.editor.cast.CastListPanel;
import com.libreshockwave.editor.extraction.ExportHandler;
import com.libreshockwave.editor.model.CastMemberInfo;
import com.libreshockwave.editor.model.MemberNodeData;
import com.libreshockwave.editor.scanning.FileProcessor;
import com.libreshockwave.editor.selection.SelectionEvent;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Cast window - Director MX 2004 cast member browser.
 * Supports grid view (thumbnails) and list view with search/filter, context menu export,
 * and cast library tabs. Grid view is the default, matching Director MX 2004.
 */
public class CastWindow extends EditorPanel {

    private final JTabbedPane castTabs;
    private final JTextField searchField;
    private final JComboBox<String> typeFilter;
    private final JLabel statusLabel;

    private boolean gridView = true;
    private List<CastMemberInfo> allMembers = new ArrayList<>();
    private List<CastMemberInfo> filteredMembers = new ArrayList<>();

    public CastWindow(EditorContext context) {
        super("Cast", context, true, true, true, true);

        JPanel panel = new JPanel(new BorderLayout());

        // Toolbar with view toggle, search, and filter
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);

        JButton gridViewBtn = new JButton("Grid");
        JButton listViewBtn = new JButton("List");
        gridViewBtn.setFont(gridViewBtn.getFont().deriveFont(Font.BOLD));
        gridViewBtn.addActionListener(e -> {
            gridView = true;
            gridViewBtn.setFont(gridViewBtn.getFont().deriveFont(Font.BOLD));
            listViewBtn.setFont(listViewBtn.getFont().deriveFont(Font.PLAIN));
            rebuildView();
        });
        listViewBtn.addActionListener(e -> {
            gridView = false;
            listViewBtn.setFont(listViewBtn.getFont().deriveFont(Font.BOLD));
            gridViewBtn.setFont(gridViewBtn.getFont().deriveFont(Font.PLAIN));
            rebuildView();
        });
        toolbar.add(gridViewBtn);
        toolbar.add(listViewBtn);
        toolbar.addSeparator();

        toolbar.add(new JLabel(" Search: "));
        searchField = new JTextField(10);
        searchField.addActionListener(e -> applyFilterAndRebuild());
        toolbar.add(searchField);

        toolbar.addSeparator();
        toolbar.add(new JLabel(" Type: "));
        typeFilter = new JComboBox<>(getTypeFilterItems());
        typeFilter.addActionListener(e -> applyFilterAndRebuild());
        toolbar.add(typeFilter);

        // Cast library tabs (bottom tabs like Director MX 2004)
        castTabs = new JTabbedPane(JTabbedPane.BOTTOM);
        castTabs.addTab("Internal", new JLabel("No movie loaded", SwingConstants.CENTER));

        // Status bar
        statusLabel = new JLabel(" Ready");
        statusLabel.setBorder(BorderFactory.createLoweredBevelBorder());

        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(castTabs, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);

        setContentPane(panel);
        setSize(400, 350);
    }

    private String[] getTypeFilterItems() {
        return new String[]{
            "All Types", "Bitmap", "Script", "Sound", "Text", "Button",
            "Shape", "Film Loop", "Palette", "Field", "Transition"
        };
    }

    @Override
    protected void onFileOpened(DirectorFile file) {
        FileProcessor processor = new FileProcessor();
        allMembers = processor.processMembers(file);
        filteredMembers = new ArrayList<>(allMembers);
        rebuildView();
        statusLabel.setText(" " + allMembers.size() + " members");
    }

    @Override
    protected void onFileClosed() {
        allMembers.clear();
        filteredMembers.clear();
        castTabs.removeAll();
        castTabs.addTab("Internal", new JLabel("No movie loaded", SwingConstants.CENTER));
        statusLabel.setText(" Ready");
        searchField.setText("");
        typeFilter.setSelectedIndex(0);
    }

    private void applyFilterAndRebuild() {
        String searchText = searchField.getText().toLowerCase().trim();
        String selectedType = (String) typeFilter.getSelectedItem();

        filteredMembers = new ArrayList<>();
        for (CastMemberInfo info : allMembers) {
            // Type filter
            if (!"All Types".equals(selectedType)) {
                String typeName = info.memberType().getName();
                if (!typeName.equalsIgnoreCase(selectedType)) continue;
            }
            // Search filter
            if (!searchText.isEmpty()) {
                String name = info.name().toLowerCase();
                String details = info.details().toLowerCase();
                if (!name.contains(searchText) && !details.contains(searchText)) continue;
            }
            filteredMembers.add(info);
        }

        rebuildView();
        statusLabel.setText(" " + filteredMembers.size() + " of " + allMembers.size() + " members");
    }

    private void rebuildView() {
        castTabs.removeAll();

        if (filteredMembers.isEmpty() && allMembers.isEmpty()) {
            castTabs.addTab("Internal", new JLabel("No movie loaded", SwingConstants.CENTER));
            return;
        }

        if (gridView) {
            castTabs.addTab("Internal", buildGridView());
        } else {
            castTabs.addTab("Internal", buildListView());
        }
    }

    private JScrollPane buildGridView() {
        CastGridPanel grid = new CastGridPanel();

        for (CastMemberInfo info : filteredMembers) {
            grid.addMemberCell(info.memberNum(), info.name(), info.memberType().getName());
        }

        // Click handler for selection and context menu
        grid.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleGridClick(grid, e);
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) handleGridClick(grid, e);
            }
        });

        JScrollPane sp = new JScrollPane(grid);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private void handleGridClick(CastGridPanel grid, MouseEvent e) {
        // Find which cell was clicked based on component at point
        Component comp = grid.getComponentAt(e.getPoint());
        if (comp instanceof JPanel cell) {
            int idx = -1;
            Component[] children = grid.getComponents();
            for (int i = 0; i < children.length; i++) {
                if (children[i] == cell) { idx = i; break; }
            }
            if (idx >= 0 && idx < filteredMembers.size()) {
                CastMemberInfo info = filteredMembers.get(idx);
                context.getSelectionManager().select(
                    SelectionEvent.castMember(0, info.memberNum()));

                // Highlight selected cell
                for (Component c : children) {
                    if (c instanceof JPanel p) {
                        p.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
                    }
                }
                cell.setBorder(BorderFactory.createLineBorder(new Color(0, 102, 204), 2));

                if (e.isPopupTrigger()) {
                    showExportPopup(grid, e, info);
                }
            }
        }
    }

    private JScrollPane buildListView() {
        CastListPanel listPanel = new CastListPanel();

        for (CastMemberInfo info : filteredMembers) {
            listPanel.addMember(info.memberNum(), info.name(), info.memberType().getName());
        }

        // Wire selection events from internal JList
        JList<String> jList = listPanel.getList();
        jList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int idx = jList.getSelectedIndex();
                if (idx >= 0 && idx < filteredMembers.size()) {
                    CastMemberInfo info = filteredMembers.get(idx);
                    context.getSelectionManager().select(
                        SelectionEvent.castMember(0, info.memberNum()));
                }
            }
        });

        // Context menu on list
        jList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showListPopup(jList, e);
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showListPopup(jList, e);
            }
        });

        JScrollPane sp = new JScrollPane(listPanel);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private void showListPopup(JList<String> jList, MouseEvent e) {
        int idx = jList.locationToIndex(e.getPoint());
        if (idx >= 0 && idx < filteredMembers.size()) {
            jList.setSelectedIndex(idx);
            CastMemberInfo info = filteredMembers.get(idx);
            showExportPopup(jList, e, info);
        }
    }

    private void showExportPopup(Component parent, MouseEvent e, CastMemberInfo info) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem exportItem = new JMenuItem("Export...");
        exportItem.addActionListener(ev -> exportMember(info));
        popup.add(exportItem);

        JMenuItem copyName = new JMenuItem("Copy Name");
        copyName.addActionListener(ev -> {
            java.awt.datatransfer.StringSelection sel =
                new java.awt.datatransfer.StringSelection(info.name());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
        });
        popup.add(copyName);

        popup.show(parent, e.getX(), e.getY());
    }

    private void exportMember(CastMemberInfo info) {
        DirectorFile dirFile = context.getFile();
        if (dirFile == null) return;

        String filePath = context.getCurrentPath() != null ? context.getCurrentPath().toString() : "";
        MemberNodeData memberData = new MemberNodeData(filePath, info);

        ExportHandler handler = new ExportHandler();
        handler.setStatusCallback(statusLabel::setText);

        JFrame parentFrame = (JFrame) SwingUtilities.getWindowAncestor(this);
        handler.export(parentFrame, dirFile, memberData, "");
    }
}
