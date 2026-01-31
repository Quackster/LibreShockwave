package com.libreshockwave.player;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.TraceListener;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.List;
import java.util.Map;

/**
 * Debug panel for displaying VM execution trace.
 * Shows instructions, stack, variables, and handler information.
 */
public class DebugPanel extends JPanel implements TraceListener {

    private final JTextPane logPane;
    private final StyledDocument logDoc;
    private final JTextArea handlerInfoArea;
    private final JTextArea stackArea;
    private final JTextArea globalsArea;
    private final JTextArea localsArea;

    private final Style normalStyle;
    private final Style handlerStyle;
    private final Style instructionStyle;
    private final Style variableStyle;
    private final Style errorStyle;

    private static final int MAX_LOG_LINES = 1000;
    private int lineCount = 0;

    private volatile boolean enabled = true;

    public DebugPanel() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(450, 480));

        // Create tabbed pane for different views
        JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

        // Execution log tab
        logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logDoc = logPane.getStyledDocument();

        // Create styles
        normalStyle = logDoc.addStyle("normal", null);
        StyleConstants.setForeground(normalStyle, Color.BLACK);

        handlerStyle = logDoc.addStyle("handler", null);
        StyleConstants.setForeground(handlerStyle, new Color(0, 100, 0));
        StyleConstants.setBold(handlerStyle, true);

        instructionStyle = logDoc.addStyle("instruction", null);
        StyleConstants.setForeground(instructionStyle, new Color(0, 0, 128));
        StyleConstants.setFontFamily(instructionStyle, Font.MONOSPACED);

        variableStyle = logDoc.addStyle("variable", null);
        StyleConstants.setForeground(variableStyle, new Color(128, 0, 128));

        errorStyle = logDoc.addStyle("error", null);
        StyleConstants.setForeground(errorStyle, Color.RED);
        StyleConstants.setBold(errorStyle, true);

        JScrollPane logScroll = new JScrollPane(logPane);
        logScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        tabbedPane.addTab("Execution Log", logScroll);

        // Handler info tab
        handlerInfoArea = createInfoArea();
        tabbedPane.addTab("Handler", new JScrollPane(handlerInfoArea));

        // Stack tab
        stackArea = createInfoArea();
        tabbedPane.addTab("Stack", new JScrollPane(stackArea));

        // Globals tab
        globalsArea = createInfoArea();
        tabbedPane.addTab("Globals", new JScrollPane(globalsArea));

        // Locals tab
        localsArea = createInfoArea();
        tabbedPane.addTab("Locals", new JScrollPane(localsArea));

        add(tabbedPane, BorderLayout.CENTER);

        // Control panel
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JCheckBox enableCheck = new JCheckBox("Enable Trace", true);
        enableCheck.addActionListener(e -> enabled = enableCheck.isSelected());
        controlPanel.add(enableCheck);

        JButton clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> clearLog());
        controlPanel.add(clearButton);

        add(controlPanel, BorderLayout.SOUTH);
    }

    private JTextArea createInfoArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        return area;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void clearLog() {
        try {
            logDoc.remove(0, logDoc.getLength());
            lineCount = 0;
        } catch (BadLocationException e) {
            // Ignore
        }
    }

    // TraceListener implementation

    @Override
    public void onHandlerEnter(HandlerInfo info) {
        if (!enabled) return;

        SwingUtilities.invokeLater(() -> {
            appendLog("\n=== ENTER: " + info.handlerName() + " ===\n", handlerStyle);

            // Update handler info panel
            StringBuilder sb = new StringBuilder();
            sb.append("Handler: ").append(info.handlerName()).append("\n");
            sb.append("Script ID: ").append(info.scriptId()).append("\n");
            sb.append("Script Type: ").append(info.scriptType()).append("\n");
            sb.append("Args: ").append(info.argCount()).append("\n");
            sb.append("Locals: ").append(info.localCount()).append("\n");
            sb.append("\nArguments:\n");
            for (int i = 0; i < info.arguments().size(); i++) {
                sb.append("  [").append(i).append("] ").append(formatDatum(info.arguments().get(i))).append("\n");
            }
            if (info.receiver() != null && !(info.receiver() instanceof Datum.Void)) {
                sb.append("\nReceiver (me):\n");
                sb.append("  ").append(formatDatum(info.receiver())).append("\n");
            }
            sb.append("\nLiterals:\n");
            List<ScriptChunk.LiteralEntry> literals = info.literals();
            for (int i = 0; i < Math.min(20, literals.size()); i++) {
                ScriptChunk.LiteralEntry lit = literals.get(i);
                sb.append("  [").append(i).append("] ").append(lit.value()).append("\n");
            }
            if (literals.size() > 20) {
                sb.append("  ... (").append(literals.size()).append(" total)\n");
            }
            handlerInfoArea.setText(sb.toString());

            // Update globals
            updateGlobals(info.globals());
        });
    }

    @Override
    public void onHandlerExit(HandlerInfo info, Datum returnValue) {
        if (!enabled) return;

        SwingUtilities.invokeLater(() -> {
            String retStr = formatDatum(returnValue);
            appendLog("=== EXIT: " + info.handlerName() + " => " + retStr + " ===\n\n", handlerStyle);
        });
    }

    @Override
    public void onInstruction(InstructionInfo info) {
        if (!enabled) return;

        SwingUtilities.invokeLater(() -> {
            // Format instruction line like dirplayer-rs
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[%3d] %-16s", info.offset(), info.opcode()));
            if (info.argument() != 0) {
                sb.append(String.format(" %d", info.argument()));
            }
            // Pad with dots
            while (sb.length() < 30) {
                sb.append('.');
            }
            if (!info.annotation().isEmpty()) {
                sb.append(' ').append(info.annotation());
            }
            sb.append(String.format(" (stk=%d)", info.stackSize()));
            sb.append("\n");

            appendLog(sb.toString(), instructionStyle);

            // Update stack display
            updateStack(info.stackSnapshot());
        });
    }

    @Override
    public void onVariableSet(String type, String name, Datum value) {
        if (!enabled) return;

        SwingUtilities.invokeLater(() -> {
            String msg = "  == " + name + " = " + formatDatum(value) + "\n";
            appendLog(msg, variableStyle);

            if ("local".equals(type)) {
                String current = localsArea.getText();
                // Update or append local
                localsArea.setText(current + name + " = " + formatDatum(value) + "\n");
            }
        });
    }

    @Override
    public void onError(String message, Exception error) {
        if (!enabled) return;

        SwingUtilities.invokeLater(() -> {
            appendLog("ERROR: " + message + "\n", errorStyle);
            if (error != null) {
                appendLog("  " + error.getMessage() + "\n", errorStyle);
            }
        });
    }

    // Helper methods

    private void appendLog(String text, Style style) {
        try {
            // Trim old lines if too many
            if (lineCount > MAX_LOG_LINES) {
                Element root = logDoc.getDefaultRootElement();
                if (root.getElementCount() > MAX_LOG_LINES / 2) {
                    Element firstLine = root.getElement(0);
                    logDoc.remove(0, firstLine.getEndOffset());
                    lineCount = root.getElementCount();
                }
            }

            logDoc.insertString(logDoc.getLength(), text, style);
            lineCount += text.split("\n", -1).length - 1;

            // Auto-scroll to bottom
            logPane.setCaretPosition(logDoc.getLength());
        } catch (BadLocationException e) {
            // Ignore
        }
    }

    private void updateStack(List<Datum> stack) {
        StringBuilder sb = new StringBuilder();
        sb.append("Stack (").append(stack.size()).append(" items):\n");
        sb.append("------------------------\n");
        for (int i = 0; i < stack.size(); i++) {
            sb.append(String.format("[%2d] %s\n", i, formatDatum(stack.get(i))));
        }
        stackArea.setText(sb.toString());
    }

    private void updateGlobals(Map<String, Datum> globals) {
        StringBuilder sb = new StringBuilder();
        sb.append("Globals (").append(globals.size()).append(" vars):\n");
        sb.append("------------------------\n");
        for (Map.Entry<String, Datum> entry : globals.entrySet()) {
            sb.append(entry.getKey()).append(" = ").append(formatDatum(entry.getValue())).append("\n");
        }
        globalsArea.setText(sb.toString());
    }

    private String formatDatum(Datum d) {
        if (d == null) return "<null>";
        if (d instanceof Datum.Void) return "<void>";
        if (d instanceof Datum.Int i) return String.valueOf(i.value());
        if (d instanceof Datum.Float f) return String.valueOf(f.value());
        if (d instanceof Datum.Str s) return "\"" + truncate(s.value(), 50) + "\"";
        if (d instanceof Datum.Symbol sym) return "#" + sym.name();
        if (d instanceof Datum.List list) return "[list:" + list.items().size() + "]";
        if (d instanceof Datum.PropList pl) return "[propList:" + pl.properties().size() + "]";
        if (d instanceof Datum.Point p) return "point(" + p.x() + ", " + p.y() + ")";
        if (d instanceof Datum.Rect r) return "rect(" + r.left() + ", " + r.top() + ", " + r.right() + ", " + r.bottom() + ")";
        if (d instanceof Datum.Color c) return "color(" + c.r() + ", " + c.g() + ", " + c.b() + ")";
        if (d instanceof Datum.ScriptInstance si) return "<script#" + si.scriptId() + ">";
        return d.toString();
    }

    private String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 3) + "...";
    }
}
