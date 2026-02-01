package com.libreshockwave.player;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.format.ScriptFormatUtils;
import com.libreshockwave.player.format.DatumFormatter;
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
    private final JTextPane bytecodePane;
    private final StyledDocument bytecodeDoc;

    private final Style normalStyle;
    private final Style handlerStyle;
    private final Style instructionStyle;
    private final Style variableStyle;
    private final Style errorStyle;
    private final Style eventStyle;
    private final Style bcOpcodeStyle;
    private final Style bcStackStyle;
    private final Style bcHandlerStyle;

    private static final int MAX_LOG_LINES = 1000;
    private static final int MAX_BYTECODE_HANDLERS = 10;
    private int lineCount = 0;
    private int bytecodeHandlerCount = 0;

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

        eventStyle = logDoc.addStyle("event", null);
        StyleConstants.setForeground(eventStyle, new Color(128, 128, 0));  // Olive/dark yellow
        StyleConstants.setItalic(eventStyle, true);

        // Bytecode execution pane
        bytecodePane = new JTextPane();
        bytecodePane.setEditable(false);
        bytecodePane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        bytecodeDoc = bytecodePane.getStyledDocument();

        bcOpcodeStyle = bytecodeDoc.addStyle("opcode", null);
        StyleConstants.setForeground(bcOpcodeStyle, new Color(0, 0, 128));
        StyleConstants.setFontFamily(bcOpcodeStyle, Font.MONOSPACED);

        bcStackStyle = bytecodeDoc.addStyle("stack", null);
        StyleConstants.setForeground(bcStackStyle, new Color(128, 0, 128));
        StyleConstants.setFontSize(bcStackStyle, 10);

        bcHandlerStyle = bytecodeDoc.addStyle("bchandler", null);
        StyleConstants.setForeground(bcHandlerStyle, new Color(0, 100, 0));
        StyleConstants.setBold(bcHandlerStyle, true);

        JScrollPane logScroll = new JScrollPane(logPane);
        logScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        tabbedPane.addTab("Execution Log", logScroll);

        // Bytecode execution tab with stack history
        JScrollPane bytecodeScroll = new JScrollPane(bytecodePane);
        bytecodeScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        tabbedPane.addTab("Bytecode", bytecodeScroll);


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
            bytecodeDoc.remove(0, bytecodeDoc.getLength());
            lineCount = 0;
            bytecodeHandlerCount = 0;
        } catch (BadLocationException e) {
            // Ignore
        }
    }

    // TraceListener implementation

    @Override
    public void onHandlerEnter(HandlerInfo info) {
        if (!enabled) return;

        SwingUtilities.invokeLater(() -> {
            // dirplayer-rs format: == Script: (member X of castLib Y) Handler: name
            String entry = "== Script: (#" + info.scriptId() + " " + info.scriptType() + ") Handler: " + info.handlerName();
            appendLog(entry + "\n", handlerStyle);

            // Log handler to bytecode pane, trim old handlers if needed
            bytecodeHandlerCount++;
            if (bytecodeHandlerCount > MAX_BYTECODE_HANDLERS) {
                trimBytecodePane();
            }
            String bcEntry = "\n== " + info.handlerName() + " (script #" + info.scriptId() + " " + info.scriptType() + ")\n";
            appendBytecode(bcEntry, bcHandlerStyle);

            // Update handler info panel with detailed info
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
            sb.append("\nLiterals (").append(info.literals().size()).append("):\n");
            List<ScriptChunk.LiteralEntry> literals = info.literals();
            for (int i = 0; i < literals.size(); i++) {
                ScriptChunk.LiteralEntry lit = literals.get(i);
                sb.append("  [").append(i).append("] ").append(ScriptFormatUtils.formatLiteral(lit)).append("\n");
            }
            handlerInfoArea.setText(sb.toString());

            // Update globals
            updateGlobals(info.globals());

            // Clear locals for new handler
            localsArea.setText("");
        });
    }

    @Override
    public void onHandlerExit(HandlerInfo info, Datum returnValue) {
        if (!enabled) return;

        SwingUtilities.invokeLater(() -> {
            // Only log non-void returns (dirplayer-rs style)
            if (!(returnValue instanceof Datum.Void)) {
                appendLog("== " + info.handlerName() + " returned " + formatDatum(returnValue) + "\n", handlerStyle);
                appendBytecode("  => returned " + formatDatum(returnValue) + "\n", bcStackStyle);
            }
        });
    }

    @Override
    public void onInstruction(InstructionInfo info) {
        if (!enabled) return;

        SwingUtilities.invokeLater(() -> {
            // Update stack display (side panel)
            updateStack(info.stackSnapshot());

            // Log opcode to execution log
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  [%3d] %-12s", info.offset(), info.opcode()));
            if (info.argument() != 0) {
                sb.append(String.format(" %d", info.argument()));
            }
            if (!info.annotation().isEmpty()) {
                sb.append(" ").append(info.annotation());
            }
            sb.append("\n");
            appendLog(sb.toString(), instructionStyle);

            // Log opcode to bytecode pane with stack history
            StringBuilder bcSb = new StringBuilder();
            bcSb.append(String.format("  [%3d] %-12s", info.offset(), info.opcode()));
            if (info.argument() != 0) {
                bcSb.append(String.format(" %-4d", info.argument()));
            } else {
                bcSb.append("     ");
            }
            if (!info.annotation().isEmpty()) {
                bcSb.append(" ").append(info.annotation());
            }
            bcSb.append("\n");
            appendBytecode(bcSb.toString(), bcOpcodeStyle);

            // Show stack state after instruction
            if (!info.stackSnapshot().isEmpty()) {
                StringBuilder stackSb = new StringBuilder();
                stackSb.append("         stack: [");
                for (int i = 0; i < info.stackSnapshot().size(); i++) {
                    if (i > 0) stackSb.append(", ");
                    stackSb.append(formatDatum(info.stackSnapshot().get(i)));
                }
                stackSb.append("]\n");
                appendBytecode(stackSb.toString(), bcStackStyle);
            }
        });
    }

    @Override
    public void onVariableSet(String type, String name, Datum value) {
        if (!enabled) return;

        SwingUtilities.invokeLater(() -> {
            // Update locals panel only (not main execution log)
            if ("local".equals(type)) {
                String current = localsArea.getText();
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

    @Override
    public void onDebugMessage(String message) {
        if (!enabled) return;

        SwingUtilities.invokeLater(() -> {
            appendLog(message + "\n", eventStyle);
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

    private void appendBytecode(String text, Style style) {
        try {
            bytecodeDoc.insertString(bytecodeDoc.getLength(), text, style);
            // Auto-scroll to bottom
            bytecodePane.setCaretPosition(bytecodeDoc.getLength());
        } catch (BadLocationException e) {
            // Ignore
        }
    }

    private void trimBytecodePane() {
        try {
            // Find and remove the oldest handler (first "==" after start)
            String content = bytecodeDoc.getText(0, bytecodeDoc.getLength());
            int firstHandler = content.indexOf("\n==");
            if (firstHandler > 0) {
                int secondHandler = content.indexOf("\n==", firstHandler + 1);
                if (secondHandler > 0) {
                    bytecodeDoc.remove(0, secondHandler);
                    bytecodeHandlerCount--;
                }
            }
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
        return DatumFormatter.format(d);
    }
}
