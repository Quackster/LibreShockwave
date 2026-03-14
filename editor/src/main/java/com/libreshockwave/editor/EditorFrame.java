package com.libreshockwave.editor;

import com.libreshockwave.editor.panel.*;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.beans.PropertyVetoException;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Main editor window with JDesktopPane MDI container.
 * Creates and manages all panel windows.
 */
public class EditorFrame extends JFrame {

    private final EditorContext context;
    private final JDesktopPane desktop;
    private final Map<String, EditorPanel> panels = new LinkedHashMap<>();

    public EditorFrame() {
        super("LibreShockwave Editor - Director MX 2004");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        context = new EditorContext();

        // MDI desktop
        desktop = new JDesktopPane();
        desktop.setBackground(new Color(58, 68, 75));

        // Layout: toolbar on top, desktop fills rest
        setLayout(new BorderLayout());
        add(new EditorToolBar(context), BorderLayout.NORTH);
        add(desktop, BorderLayout.CENTER);

        // Menu bar
        setJMenuBar(new EditorMenuBar(this, context));

        // Create all panels
        createPanels();
        arrangeDefaultLayout();

        // Update title on file open/close
        context.addPropertyChangeListener(evt -> {
            if (EditorContext.PROP_FILE.equals(evt.getPropertyName())) {
                if (evt.getNewValue() != null && context.getCurrentPath() != null) {
                    setTitle("LibreShockwave Editor - " + context.getCurrentPath().getFileName());
                } else {
                    setTitle("LibreShockwave Editor - Director MX 2004");
                }
            }
        });

        setSize(1280, 900);
        setLocationRelativeTo(null);
    }

    public EditorContext getContext() {
        return context;
    }

    private void createPanels() {
        addPanel(new StageWindow(context));
        addPanel(new ScoreWindow(context));
        addPanel(new CastWindow(context));
        addPanel(new PropertyInspectorWindow(context));
        addPanel(new ScriptEditorWindow(context));
        addPanel(new MessageWindow(context));
        addPanel(new PaintWindow(context));
        addPanel(new VectorShapeWindow(context));
        addPanel(new TextEditorWindow(context));
        addPanel(new FieldEditorWindow(context));
        addPanel(new ColorPalettesWindow(context));
        addPanel(new BehaviorInspectorWindow(context));
        addPanel(new LibraryPaletteWindow(context));
        addPanel(new ToolPaletteWindow(context));
        addPanel(new MarkersWindow(context));
        addPanel(new BytecodeDebuggerWindow(context));
    }

    private void addPanel(EditorPanel panel) {
        panels.put(panel.getTitle(), panel);
        desktop.add(panel);
        panel.setVisible(true);
    }

    /**
     * Arrange panels in a default layout resembling Director MX 2004.
     */
    private void arrangeDefaultLayout() {
        // Core panels visible and positioned
        setPanel("Stage", 170, 10, 660, 500);
        setPanel("Score", 170, 520, 700, 300);
        setPanel("Cast", 880, 520, 400, 300);
        setPanel("Property Inspector", 880, 10, 280, 400);
        setPanel("Script", 170, 520, 500, 400);  // Behind Score
        setPanel("Message", 880, 420, 400, 200);

        // Tool Palette on left
        setPanel("Tool Palette", 5, 10, 160, 350);

        // Media panels - hidden by default
        hidePanel("Paint");
        hidePanel("Vector Shape");
        hidePanel("Text");
        hidePanel("Field");
        hidePanel("Color Palettes");

        // Advanced panels - hidden by default
        hidePanel("Behavior Inspector");
        hidePanel("Library Palette");
        hidePanel("Markers");
        hidePanel("Bytecode Debugger");

        // Bring core panels to front in the right order
        try {
            EditorPanel stage = panels.get("Stage");
            if (stage != null) stage.setSelected(true);
        } catch (PropertyVetoException ignored) {}
    }

    private void setPanel(String title, int x, int y, int w, int h) {
        EditorPanel panel = panels.get(title);
        if (panel != null) {
            panel.setBounds(x, y, w, h);
            panel.setVisible(true);
        }
    }

    private void hidePanel(String title) {
        EditorPanel panel = panels.get(title);
        if (panel != null) {
            panel.setVisible(false);
        }
    }

    // ---- Public methods called from EditorMenuBar ----

    public void openFileDialog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open Director File");
        chooser.setFileFilter(new FileNameExtensionFilter(
            "Director Files (*.dir, *.dxr, *.dcr, *.cct, *.cst)",
            "dir", "dxr", "dcr", "cct", "cst"
        ));

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            context.openFile(chooser.getSelectedFile().toPath());
        }
    }

    public void togglePanel(String title, boolean visible) {
        EditorPanel panel = panels.get(title);
        if (panel != null) {
            panel.setVisible(visible);
            if (visible) {
                try {
                    panel.setSelected(true);
                } catch (PropertyVetoException ignored) {}
            }
        }
    }

    public void tileWindows() {
        JInternalFrame[] frames = desktop.getAllFrames();
        int visibleCount = 0;
        for (JInternalFrame f : frames) {
            if (f.isVisible() && !f.isIcon()) visibleCount++;
        }
        if (visibleCount == 0) return;

        int cols = (int) Math.ceil(Math.sqrt(visibleCount));
        int rows = (int) Math.ceil((double) visibleCount / cols);
        int w = desktop.getWidth() / cols;
        int h = desktop.getHeight() / rows;

        int idx = 0;
        for (JInternalFrame f : frames) {
            if (f.isVisible() && !f.isIcon()) {
                int row = idx / cols;
                int col = idx % cols;
                f.setBounds(col * w, row * h, w, h);
                idx++;
            }
        }
    }

    public void cascadeWindows() {
        JInternalFrame[] frames = desktop.getAllFrames();
        int offset = 0;
        for (JInternalFrame f : frames) {
            if (f.isVisible() && !f.isIcon()) {
                f.setBounds(offset, offset, 500, 400);
                offset += 30;
            }
        }
    }
}
