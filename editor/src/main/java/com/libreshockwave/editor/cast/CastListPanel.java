package com.libreshockwave.editor.cast;

import javax.swing.*;

/**
 * List view of cast members as an alternative to the grid view.
 */
public class CastListPanel extends JPanel {

    private final DefaultListModel<String> listModel;
    private final JList<String> list;

    public CastListPanel() {
        setLayout(new java.awt.BorderLayout());

        listModel = new DefaultListModel<>();
        list = new JList<>(listModel);
        list.setFont(list.getFont().deriveFont(12f));

        add(new JScrollPane(list), java.awt.BorderLayout.CENTER);
    }

    public void addMember(int memberNum, String name, String type) {
        String display = memberNum + ": " + (name != null ? name : "(unnamed)") + " [" + type + "]";
        listModel.addElement(display);
    }

    public JList<String> getList() {
        return list;
    }

    public void clearMembers() {
        listModel.clear();
    }
}
