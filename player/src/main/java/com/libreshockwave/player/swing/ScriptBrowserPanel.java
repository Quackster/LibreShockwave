package com.libreshockwave.player.swing;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;
import com.libreshockwave.vm.ScriptResolver;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.tree.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Panel for browsing cast members and scripts in a tree view.
 * Shows cast libraries, scripts, handlers, and bytecode.
 */
public class ScriptBrowserPanel extends JPanel {

    private final JTree tree;
    private final DefaultTreeModel treeModel;
    private final DefaultMutableTreeNode rootNode;
    private final JTextArea detailArea;
    private final JSplitPane splitPane;

    private CastManager castManager;
    private DirectorFile mainFile;
    private ScriptResolver scriptResolver;

    public ScriptBrowserPanel() {
        setLayout(new BorderLayout());

        // Create tree
        rootNode = new DefaultMutableTreeNode("Movie");
        treeModel = new DefaultTreeModel(rootNode);
        tree = new JTree(treeModel);
        tree.setRootVisible(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new ScriptTreeCellRenderer());

        // Create detail area
        detailArea = new JTextArea();
        detailArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        detailArea.setEditable(false);
        detailArea.setTabSize(2);

        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setPreferredSize(new Dimension(250, 400));

        JScrollPane detailScroll = new JScrollPane(detailArea);
        detailScroll.setPreferredSize(new Dimension(400, 400));

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, detailScroll);
        splitPane.setResizeWeight(0.4);

        add(splitPane, BorderLayout.CENTER);

        // Tree selection listener
        tree.addTreeSelectionListener(e -> onTreeSelectionChanged());
    }

    public void loadMovie(DirectorFile file, CastManager castManager, ScriptResolver resolver) {
        this.mainFile = file;
        this.castManager = castManager;
        this.scriptResolver = resolver;

        rootNode.removeAllChildren();

        if (castManager == null) {
            treeModel.reload();
            return;
        }

        // Add cast libraries
        for (CastLib cast : castManager.getCasts()) {
            DefaultMutableTreeNode castNode = new DefaultMutableTreeNode(
                new CastNodeData(cast));

            // Add scripts from this cast
            if (cast.getState() == CastLib.State.LOADED) {
                addScriptsFromCast(castNode, cast);
            }

            rootNode.add(castNode);
        }

        // Add main file scripts if not already covered
        if (file != null && !file.getScripts().isEmpty()) {
            DefaultMutableTreeNode mainScriptsNode = new DefaultMutableTreeNode("Main Movie Scripts");
            for (ScriptChunk script : file.getScripts()) {
                addScriptNode(mainScriptsNode, script, file.getScriptNames());
            }
            if (mainScriptsNode.getChildCount() > 0) {
                rootNode.add(mainScriptsNode);
            }
        }

        treeModel.reload();
        expandAll();
    }

    private void addScriptsFromCast(DefaultMutableTreeNode castNode, CastLib cast) {
        // Group scripts by type
        List<ScriptChunk> movieScripts = new ArrayList<>();
        List<ScriptChunk> behaviors = new ArrayList<>();
        List<ScriptChunk> parentScripts = new ArrayList<>();
        List<ScriptChunk> otherScripts = new ArrayList<>();

        for (ScriptChunk script : cast.getAllScripts()) {
            ScriptChunk.ScriptType type = script.scriptType();
            if (type == null) {
                otherScripts.add(script);
            } else {
                switch (type) {
                    case MOVIE_SCRIPT -> movieScripts.add(script);
                    case BEHAVIOR -> behaviors.add(script);
                    case PARENT -> parentScripts.add(script);
                    default -> otherScripts.add(script);
                }
            }
        }

        ScriptNamesChunk names = cast.getScriptNames();

        if (!movieScripts.isEmpty()) {
            DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode("Movie Scripts");
            for (ScriptChunk script : movieScripts) {
                addScriptNode(groupNode, script, names);
            }
            castNode.add(groupNode);
        }

        if (!behaviors.isEmpty()) {
            DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode("Behaviors");
            for (ScriptChunk script : behaviors) {
                addScriptNode(groupNode, script, names);
            }
            castNode.add(groupNode);
        }

        if (!parentScripts.isEmpty()) {
            DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode("Parent Scripts");
            for (ScriptChunk script : parentScripts) {
                addScriptNode(groupNode, script, names);
            }
            castNode.add(groupNode);
        }

        if (!otherScripts.isEmpty()) {
            DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode("Other Scripts");
            for (ScriptChunk script : otherScripts) {
                addScriptNode(groupNode, script, names);
            }
            castNode.add(groupNode);
        }
    }

    private void addScriptNode(DefaultMutableTreeNode parent, ScriptChunk script, ScriptNamesChunk names) {
        String scriptName = getScriptDisplayName(script, names);
        DefaultMutableTreeNode scriptNode = new DefaultMutableTreeNode(
            new ScriptNodeData(script, scriptName, names));

        // Add handlers
        for (ScriptChunk.Handler handler : script.handlers()) {
            String handlerName = names != null ? names.getName(handler.nameId()) : "handler_" + handler.nameId();
            DefaultMutableTreeNode handlerNode = new DefaultMutableTreeNode(
                new HandlerNodeData(handler, handlerName, names));
            scriptNode.add(handlerNode);
        }

        parent.add(scriptNode);
    }

    private String getScriptDisplayName(ScriptChunk script, ScriptNamesChunk names) {
        // Try to get member name via ScriptResolver
        if (scriptResolver != null) {
            String memberName = scriptResolver.getScriptMemberName(script);
            if (memberName != null && !memberName.isEmpty()) {
                return memberName;
            }
        }

        // Fallback: try to find from cast members
        if (castManager != null) {
            for (CastLib cast : castManager.getCasts()) {
                if (cast.getState() == CastLib.State.LOADED) {
                    for (var entry : cast.getMemberEntries()) {
                        var member = entry.getValue();
                        if (member != null && member.isScript() && member.scriptId() == script.id()) {
                            String name = member.name();
                            if (name != null && !name.isEmpty()) {
                                return name;
                            }
                        }
                    }
                }
            }
        }

        return "Script #" + script.id();
    }

    private void onTreeSelectionChanged() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            detailArea.setText("");
            return;
        }

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object data = node.getUserObject();

        if (data instanceof ScriptNodeData scriptData) {
            showScriptDetails(scriptData);
        } else if (data instanceof HandlerNodeData handlerData) {
            showHandlerDetails(handlerData);
        } else if (data instanceof CastNodeData castData) {
            showCastDetails(castData);
        } else {
            detailArea.setText(data.toString());
        }
    }

    private void showCastDetails(CastNodeData data) {
        StringBuilder sb = new StringBuilder();
        CastLib cast = data.cast;

        sb.append("=== Cast Library ===\n");
        sb.append("Name: ").append(cast.getName()).append("\n");
        sb.append("Number: ").append(cast.getNumber()).append("\n");
        sb.append("State: ").append(cast.getState()).append("\n");
        sb.append("External: ").append(cast.isExternal()).append("\n");
        if (cast.isExternal()) {
            sb.append("File: ").append(cast.getFileName()).append("\n");
        }
        sb.append("Members: ").append(cast.getMemberCount()).append("\n");
        sb.append("Scripts: ").append(cast.getAllScripts().size()).append("\n");

        detailArea.setText(sb.toString());
        detailArea.setCaretPosition(0);
    }

    private void showScriptDetails(ScriptNodeData data) {
        StringBuilder sb = new StringBuilder();
        ScriptChunk script = data.script;
        ScriptNamesChunk names = data.names;

        sb.append("=== Script: ").append(data.displayName).append(" ===\n");
        sb.append("ID: ").append(script.id()).append("\n");
        sb.append("Type: ").append(script.scriptType() != null ? script.scriptType().name() : "UNKNOWN").append("\n");
        sb.append("Handlers: ").append(script.handlers().size()).append("\n");
        sb.append("Literals: ").append(script.literals().size()).append("\n");

        // Show properties (instance variables)
        if (!script.properties().isEmpty()) {
            sb.append("\n--- Properties ---\n");
            for (ScriptChunk.PropertyEntry prop : script.properties()) {
                String propName = names != null ? names.getName(prop.nameId()) : "prop_" + prop.nameId();
                sb.append("  property ").append(propName).append("\n");
            }
        }

        // Show globals
        if (!script.globals().isEmpty()) {
            sb.append("\n--- Globals ---\n");
            for (ScriptChunk.GlobalEntry global : script.globals()) {
                String globalName = names != null ? names.getName(global.nameId()) : "global_" + global.nameId();
                sb.append("  global ").append(globalName).append("\n");
            }
        }

        // Show handler signatures
        sb.append("\n--- Handlers ---\n");
        for (ScriptChunk.Handler handler : script.handlers()) {
            String handlerName = names != null ? names.getName(handler.nameId()) : "handler_" + handler.nameId();
            sb.append("  on ").append(handlerName);

            // Show arguments
            if (!handler.argNameIds().isEmpty()) {
                sb.append(" ");
                for (int i = 0; i < handler.argNameIds().size(); i++) {
                    if (i > 0) sb.append(", ");
                    int argNameId = handler.argNameIds().get(i);
                    String argName = names != null ? names.getName(argNameId) : "arg" + i;
                    sb.append(argName);
                }
            }
            sb.append("\n");
        }

        detailArea.setText(sb.toString());
        detailArea.setCaretPosition(0);
    }

    private void showHandlerDetails(HandlerNodeData data) {
        StringBuilder sb = new StringBuilder();
        ScriptChunk.Handler handler = data.handler;
        ScriptNamesChunk names = data.names;

        // Handler signature
        sb.append("on ").append(data.handlerName);
        if (!handler.argNameIds().isEmpty()) {
            sb.append(" ");
            for (int i = 0; i < handler.argNameIds().size(); i++) {
                if (i > 0) sb.append(", ");
                int argNameId = handler.argNameIds().get(i);
                String argName = names != null ? names.getName(argNameId) : "arg" + i;
                sb.append(argName);
            }
        }
        sb.append("\n");

        // Show local variables
        if (!handler.localNameIds().isEmpty()) {
            sb.append("  -- Locals: ");
            for (int i = 0; i < handler.localNameIds().size(); i++) {
                if (i > 0) sb.append(", ");
                int localNameId = handler.localNameIds().get(i);
                String localName = names != null ? names.getName(localNameId) : "local" + i;
                sb.append(localName);
            }
            sb.append("\n");
        }

        sb.append("\n");

        // Show bytecode
        for (ScriptChunk.Handler.Instruction instr : handler.instructions()) {
            sb.append(formatInstruction(instr, names));
            sb.append("\n");
        }

        sb.append("end\n");

        detailArea.setText(sb.toString());
        detailArea.setCaretPosition(0);
    }

    private String formatInstruction(ScriptChunk.Handler.Instruction instr, ScriptNamesChunk names) {
        StringBuilder sb = new StringBuilder();

        // Position
        sb.append(String.format("  [%03d] ", instr.offset()));

        // Opcode name
        Opcode op = instr.opcode();
        String opName = op.getMnemonic().toLowerCase();
        sb.append(String.format("%-20s", opName));

        // Argument formatting based on opcode
        int arg = instr.argument();
        if (instr.rawOpcode() >= 0x40) {
            switch (op) {
                case JMP, JMP_IF_Z -> {
                    // Jump target
                    int targetOffset = instr.offset() + arg;
                    sb.append(String.format("[%03d]", targetOffset));
                }
                case END_REPEAT -> {
                    // Jump back target
                    int targetOffset = instr.offset() - arg;
                    sb.append(String.format("[%03d]", targetOffset));
                }
                case PUSH_SYMB, EXT_CALL, GET_GLOBAL, SET_GLOBAL, GET_GLOBAL2, SET_GLOBAL2,
                     GET_PROP, SET_PROP, GET_OBJ_PROP, SET_OBJ_PROP, GET_MOVIE_PROP, SET_MOVIE_PROP,
                     THE_BUILTIN, GET_TOP_LEVEL_PROP, GET_CHAINED_PROP, OBJ_CALL, NEW_OBJ, TELL_CALL -> {
                    // Name reference
                    String name = names != null ? names.getName(arg) : "<name:" + arg + ">";
                    sb.append("'").append(name).append("'");
                }
                case PUSH_FLOAT32 -> {
                    float f = Float.intBitsToFloat(arg);
                    sb.append(f);
                }
                case PUSH_CONS -> {
                    sb.append("literal[").append(arg).append("]");
                }
                case LOCAL_CALL -> {
                    sb.append("handler[").append(arg).append("]");
                }
                default -> {
                    if (arg != 0) {
                        sb.append(arg);
                    }
                }
            }
        }

        return sb.toString();
    }

    private void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    // Node data classes
    record CastNodeData(CastLib cast) {
        @Override
        public String toString() {
            String name = cast.getName();
            if (name == null || name.isEmpty()) {
                name = "Cast #" + cast.getNumber();
            }
            return name + " (" + cast.getMemberCount() + " members)";
        }
    }

    record ScriptNodeData(ScriptChunk script, String displayName, ScriptNamesChunk names) {
        @Override
        public String toString() {
            return displayName + " (" + script.handlers().size() + " handlers)";
        }
    }

    record HandlerNodeData(ScriptChunk.Handler handler, String handlerName, ScriptNamesChunk names) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(handlerName);
            sb.append("(");
            for (int i = 0; i < handler.argNameIds().size(); i++) {
                if (i > 0) sb.append(", ");
                int argNameId = handler.argNameIds().get(i);
                String argName = names != null ? names.getName(argNameId) : "arg" + i;
                sb.append(argName);
            }
            sb.append(")");
            return sb.toString();
        }
    }

    // Custom tree cell renderer
    class ScriptTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {

            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

            if (value instanceof DefaultMutableTreeNode node) {
                Object data = node.getUserObject();
                if (data instanceof CastNodeData) {
                    setIcon(UIManager.getIcon("FileView.directoryIcon"));
                } else if (data instanceof ScriptNodeData) {
                    setIcon(UIManager.getIcon("FileView.fileIcon"));
                } else if (data instanceof HandlerNodeData) {
                    setIcon(UIManager.getIcon("Tree.leafIcon"));
                }
            }

            return this;
        }
    }
}
