package com.libreshockwave.player.debug.ui;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.vm.trace.InstructionAnnotator;
import com.libreshockwave.vm.util.StringUtils;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Non-modal dialog showing detailed information about a handler.
 * Contains tabs for Overview, Bytecode, Literals, Properties, and Globals.
 */
public class HandlerDetailsDialog extends JDialog {

    private final ScriptChunk script;
    private final ScriptChunk.Handler handler;
    private final DirectorFile directorFile;

    public HandlerDetailsDialog(Window owner, ScriptChunk script, ScriptChunk.Handler handler,
                                 DirectorFile directorFile, String handlerName) {
        super(owner, "Handler: " + handlerName, ModalityType.MODELESS);
        this.script = script;
        this.handler = handler;
        this.directorFile = directorFile;
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout());
        setSize(600, 500);

        JTabbedPane tabs = new JTabbedPane();

        tabs.addTab("Overview", createOverviewPanel());
        tabs.addTab("Bytecode", createBytecodePanel());

        if (!script.literals().isEmpty()) {
            tabs.addTab("Literals", createLiteralsPanel());
        }
        if (!script.properties().isEmpty()) {
            tabs.addTab("Properties", createPropertiesPanel());
        }
        if (!script.globals().isEmpty()) {
            tabs.addTab("Globals", createGlobalsPanel());
        }

        add(tabs, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dispose());
        buttonPanel.add(closeBtn);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private JPanel createOverviewPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='font-family: monospace; font-size: 11px;'>");

        sb.append("<h3>").append(StringUtils.escapeHtml(script.getHandlerName(handler))).append("</h3>");
        sb.append("<b>Script:</b> ").append(StringUtils.escapeHtml(script.getDisplayName())).append("<br>");
        sb.append("<b>Script Type:</b> ").append(script.getScriptType()).append("<br>");
        sb.append("<b>Script ID:</b> ").append(script.id()).append("<br><br>");

        sb.append("<b>Bytecode Length:</b> ").append(handler.bytecodeLength()).append(" bytes<br>");
        sb.append("<b>Instruction Count:</b> ").append(handler.instructions().size()).append("<br><br>");

        sb.append("<b>Arguments (").append(handler.argCount()).append("):</b><br>");
        if (handler.argCount() > 0) {
            sb.append("<ul>");
            for (int i = 0; i < handler.argNameIds().size(); i++) {
                String argName = script.resolveName(handler.argNameIds().get(i));
                sb.append("<li>").append(StringUtils.escapeHtml(argName)).append("</li>");
            }
            sb.append("</ul>");
        } else {
            sb.append("&nbsp;&nbsp;(none)<br>");
        }

        sb.append("<b>Local Variables (").append(handler.localCount()).append("):</b><br>");
        if (handler.localCount() > 0) {
            sb.append("<ul>");
            for (int i = 0; i < handler.localNameIds().size(); i++) {
                String localName = script.resolveName(handler.localNameIds().get(i));
                sb.append("<li>").append(StringUtils.escapeHtml(localName)).append("</li>");
            }
            sb.append("</ul>");
        } else {
            sb.append("&nbsp;&nbsp;(none)<br>");
        }

        sb.append("<b>Globals Used:</b> ").append(handler.globalsCount()).append("<br>");
        sb.append("</body></html>");

        JLabel infoLabel = new JLabel(sb.toString());
        infoLabel.setVerticalAlignment(JLabel.TOP);

        JScrollPane scroll = new JScrollPane(infoLabel);
        scroll.setBorder(null);
        panel.add(scroll, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createBytecodePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        DefaultListModel<String> model = new DefaultListModel<>();
        for (ScriptChunk.Handler.Instruction instr : handler.instructions()) {
            String annotation = InstructionAnnotator.annotate(script, handler, instr, true);
            StringBuilder line = new StringBuilder();
            line.append(String.format("[%3d] %-14s", instr.offset(), instr.opcode().name()));
            if (instr.argument() != 0 || instr.rawOpcode() >= 0x40) {
                line.append(String.format(" %-4d", instr.argument()));
            } else {
                line.append("     ");
            }
            if (annotation != null && !annotation.isEmpty()) {
                line.append(" ").append(annotation);
            }
            model.addElement(line.toString());
        }

        JList<String> list = new JList<>(model);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createLiteralsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        String[] columns = {"#", "Type", "Value"};
        Object[][] data = new Object[script.literals().size()][3];

        for (int i = 0; i < script.literals().size(); i++) {
            ScriptChunk.LiteralEntry lit = script.literals().get(i);
            data[i][0] = i;
            data[i][1] = getLiteralTypeName(lit.type());
            data[i][2] = formatLiteralValue(lit);
        }

        JTable table = new JTable(data, columns);
        table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        table.getColumnModel().getColumn(0).setPreferredWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(300);

        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createPropertiesPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        DefaultListModel<String> model = new DefaultListModel<>();
        List<String> propNames = script.getPropertyNames(directorFile != null ? directorFile.getScriptNames() : null);
        for (int i = 0; i < propNames.size(); i++) {
            model.addElement(String.format("[%d] %s", i, propNames.get(i)));
        }

        JList<String> list = new JList<>(model);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createGlobalsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        DefaultListModel<String> model = new DefaultListModel<>();
        List<String> globalNames = script.getGlobalNames(directorFile != null ? directorFile.getScriptNames() : null);
        for (int i = 0; i < globalNames.size(); i++) {
            model.addElement(String.format("[%d] %s", i, globalNames.get(i)));
        }

        JList<String> list = new JList<>(model);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));

        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        return panel;
    }

    private String getLiteralTypeName(int type) {
        return switch (type) {
            case 1 -> "String";
            case 4 -> "Int";
            case 9 -> "Float";
            default -> "Type " + type;
        };
    }

    private String formatLiteralValue(ScriptChunk.LiteralEntry lit) {
        Object value = lit.value();
        if (value == null) {
            return "(null)";
        }
        if (value instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                         .replace("\n", "\\n").replace("\r", "\\r") + "\"";
        }
        if (value instanceof byte[] bytes) {
            if (bytes.length <= 20) {
                StringBuilder sb = new StringBuilder("bytes[");
                for (int i = 0; i < bytes.length; i++) {
                    if (i > 0) sb.append(" ");
                    sb.append(String.format("%02X", bytes[i] & 0xFF));
                }
                sb.append("]");
                return sb.toString();
            }
            return "bytes[" + bytes.length + "]";
        }
        return String.valueOf(value);
    }

    /**
     * Show handler details dialog for a handler found by name.
     * @return true if handler was found and dialog shown
     */
    public static boolean show(Component parent, List<ScriptChunk> allScripts,
                                DirectorFile directorFile, String handlerName) {
        ScriptChunk targetScript = null;
        ScriptChunk.Handler targetHandler = null;

        for (ScriptChunk script : allScripts) {
            ScriptChunk.Handler handler = script.findHandler(handlerName);
            if (handler != null) {
                targetScript = script;
                targetHandler = handler;
                break;
            }
        }

        if (targetScript == null || targetHandler == null) {
            JOptionPane.showMessageDialog(parent,
                "Handler '" + handlerName + "' not found.",
                "Handler Not Found",
                JOptionPane.WARNING_MESSAGE);
            return false;
        }

        show(parent, targetScript, targetHandler, directorFile);
        return true;
    }

    /**
     * Show handler details dialog for a known script and handler.
     */
    public static void show(Component parent, ScriptChunk script, ScriptChunk.Handler handler,
                            DirectorFile directorFile) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        String handlerName = script.getHandlerName(handler);
        HandlerDetailsDialog dialog = new HandlerDetailsDialog(owner, script, handler, directorFile, handlerName);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }
}
