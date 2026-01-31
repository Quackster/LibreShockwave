package com.libreshockwave.tools.ui.renderer;

import com.libreshockwave.cast.MemberType;
import com.libreshockwave.tools.model.FileNode;
import com.libreshockwave.tools.model.MemberNodeData;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.*;

/**
 * Custom tree cell renderer with type-specific colors for cast members.
 */
public class FileTreeCellRenderer extends DefaultTreeCellRenderer {
    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value,
            boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {

        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

        if (value instanceof DefaultMutableTreeNode node) {
            Object userObject = node.getUserObject();
            if (userObject instanceof FileNode) {
                setIcon(UIManager.getIcon("FileView.directoryIcon"));
            } else if (userObject instanceof MemberNodeData memberData) {
                // Color-code by member type
                MemberType type = memberData.memberInfo().memberType();
                switch (type) {
                    case BITMAP -> setForeground(new Color(0, 100, 0)); // Dark green
                    case SCRIPT -> setForeground(new Color(0, 0, 180)); // Blue
                    case SOUND -> setForeground(new Color(180, 0, 180)); // Purple
                    case TEXT, BUTTON, RTE -> setForeground(new Color(100, 100, 0)); // Olive
                    case SHAPE -> setForeground(new Color(180, 100, 0)); // Orange
                    case PALETTE -> setForeground(new Color(100, 0, 100)); // Dark purple
                    case FILM_LOOP -> setForeground(new Color(0, 100, 100)); // Teal
                    default -> {} // Default color
                }
                setIcon(UIManager.getIcon("FileView.fileIcon"));
            }
        }

        return this;
    }
}
