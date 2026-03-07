package com.libreshockwave.player.debug.ui;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.DatumFormatter;

import javax.swing.*;
import java.awt.*;

/**
 * Non-modal dialog showing detailed information about a Datum value.
 */
public class DatumDetailsDialog extends JDialog {

    public DatumDetailsDialog(Window owner, Datum datum, String title) {
        super(owner, "Datum Details: " + title, ModalityType.MODELESS);
        initUI(datum);
    }

    private void initUI(Datum datum) {
        setLayout(new BorderLayout(10, 10));
        setSize(500, 400);

        JTextArea textArea = new JTextArea();
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);

        StringBuilder sb = new StringBuilder();
        sb.append("Type: ").append(DatumFormatter.getTypeName(datum)).append("\n\n");
        sb.append("Value:\n");
        sb.append(DatumFormatter.formatDetailed(datum, 0));

        textArea.setText(sb.toString());
        textArea.setCaretPosition(0);

        add(new JScrollPane(textArea), BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dispose());
        buttonPanel.add(closeBtn);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    /**
     * Show a datum details dialog.
     */
    public static void show(Component parent, Datum datum, String title) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        DatumDetailsDialog dialog = new DatumDetailsDialog(owner, datum, title);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }
}
