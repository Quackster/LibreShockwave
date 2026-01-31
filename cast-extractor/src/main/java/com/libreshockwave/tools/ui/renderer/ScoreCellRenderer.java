package com.libreshockwave.tools.ui.renderer;

import com.libreshockwave.tools.model.ScoreCellData;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

/**
 * Custom cell renderer for the score table.
 */
public class ScoreCellRenderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
            boolean isSelected, boolean hasFocus, int row, int column) {

        Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

        if (value instanceof ScoreCellData cellData) {
            setText(cellData.memberName());

            // Different tooltips for special channels vs sprite channels
            if (row < 6) {
                // Special channels - show channel-specific info
                String tooltip = switch (row) {
                    case 0 -> String.format("<html>Frame Script: %s</html>", cellData.memberName());
                    case 1 -> String.format("<html>Palette: %s</html>", cellData.memberName());
                    case 2 -> String.format("<html>Transition: #%d</html>", cellData.castMember());
                    case 3, 4 -> String.format("<html>Sound<br>Cast: %d, Member: %d<br>%s</html>",
                            cellData.castLib(), cellData.castMember(), cellData.memberName());
                    case 5 -> String.format("<html>Frame Script<br>Cast: %d, Member: %d<br>%s</html>",
                            cellData.castLib(), cellData.castMember(), cellData.memberName());
                    default -> cellData.memberName();
                };
                setToolTipText(tooltip);
            } else {
                // Regular sprite channels
                setToolTipText(String.format(
                        "<html>%s<br>Cast: %d, Member: %d<br>Type: %d, Ink: %d<br>Pos: (%d, %d)<br>Size: %dx%d</html>",
                        cellData.memberName(),
                        cellData.castLib(), cellData.castMember(), cellData.spriteType(), cellData.ink(),
                        cellData.posX(), cellData.posY(), cellData.width(), cellData.height()
                ));
            }

            if (!isSelected) {
                // Color based on channel type
                if (row < 6) {
                    // Special channels
                    setBackground(new Color(255, 255, 220)); // Light yellow
                } else {
                    setBackground(new Color(220, 240, 255)); // Light blue
                }
            }
        } else {
            setText("");
            setToolTipText(null);
            if (!isSelected) {
                setBackground(Color.WHITE);
            }
        }

        setHorizontalAlignment(SwingConstants.CENTER);
        return c;
    }
}
