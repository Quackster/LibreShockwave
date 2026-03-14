package com.libreshockwave.editor;

import com.libreshockwave.editor.docking.DockingManager;
import com.libreshockwave.editor.docking.LayoutPersistence;
import com.libreshockwave.editor.panel.*;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.beans.PropertyVetoException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Main editor window with JDesktopPane MDI container and IDE-style docking.
 * Panels can be docked to edges, corners, or center via drag or right-click.
 * Layout is persisted to ~/.libreshockwave/layout.json.
 */
public class EditorFrame extends JFrame {

    private final EditorContext context;
    private final JDesktopPane desktop;
    private final Map<String, EditorPanel> panels = new LinkedHashMap<>();
    private DockingManager dockingManager;

    public EditorFrame() {
        super("LibreShockwave Editor - Director MX 2004");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        context = new EditorContext();

        // MDI desktop
        desktop = new JDesktopPane();
        desktop.setBackground(new Color(58, 68, 75));

        // Create all panels (adds them to desktop)
        createPanels();

        // Docking manager wraps desktop in split panes for dock zones
        dockingManager = new DockingManager(this, desktop, panels);

        // Layout: toolbar on top, docking layout fills rest
        setLayout(new BorderLayout());
        add(new EditorToolBar(context), BorderLayout.NORTH);
        add(dockingManager.getComponent(), BorderLayout.CENTER);

        // Menu bar
        EditorMenuBar menuBar = new EditorMenuBar(this, context);
        setJMenuBar(menuBar);

        // Set default floating positions first, then try to load saved layout
        arrangeDefaultLayout();

        // Try loading saved layout after the frame is displayed
        SwingUtilities.invokeLater(() -> {
            if (!dockingManager.loadLayout()) {
                // No saved layout — keep the default floating layout
            }
        });

        // Wire the DetailedStackWindow as a debug listener when files open/close
        context.addPropertyChangeListener(evt -> {
            if (EditorContext.PROP_FILE.equals(evt.getPropertyName())) {
                if (evt.getNewValue() != null && context.getCurrentPath() != null) {
                    setTitle("LibreShockwave Editor - " + context.getCurrentPath().getFileName());
                    if (context.getDebugController() != null) {
                        context.getDebugController().addListener(menuBar.getDetailedStackWindow());
                    }
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

    public DockingManager getDockingManager() {
        return dockingManager;
    }

    private void createPanels() {
        addPanel(new StageWindow(context));
        addPanel(new ScoreWindow(context));
        addPanel(new CastWindow(context));
        addPanel(new PropertyInspectorWindow(context));
        addPanel(new ScriptEditorWindow(context));
        addPanel(new MessageWindow(context));
        addPanel(new ToolPaletteWindow(context));
        addPanel(new PaintWindow(context));
        addPanel(new VectorShapeWindow(context));
        addPanel(new TextEditorWindow(context));
        addPanel(new FieldEditorWindow(context));
        addPanel(new ColorPalettesWindow(context));
        addPanel(new BytecodeDebuggerWindow(context));
    }

    private void addPanel(EditorPanel panel) {
        panels.put(panel.getTitle(), panel);
        desktop.add(panel);
        panel.setVisible(true);
    }

    /**
     * Arrange panels in a default floating layout resembling Director MX 2004.
     */
    private void arrangeDefaultLayout() {
        // Core panels visible and positioned
        setPanel("Stage", 170, 10, 660, 500);
        setPanel("Score", 170, 520, 700, 300);
        setPanel("Cast", 880, 520, 400, 300);
        setPanel("Property Inspector", 880, 10, 280, 400);
        setPanel("Script", 170, 520, 500, 400);
        setPanel("Message", 880, 420, 400, 200);
        setPanel("Tool Palette", 5, 10, 160, 350);

        // Media and advanced panels - hidden by default
        hidePanel("Paint");
        hidePanel("Vector Shape");
        hidePanel("Text");
        hidePanel("Field");
        hidePanel("Color Palettes");
        hidePanel("Bytecode Debugger");

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

        String lastDir = Preferences.get().getLastOpenDirectory();
        if (lastDir != null) {
            java.io.File dir = new java.io.File(lastDir);
            if (dir.isDirectory()) {
                chooser.setCurrentDirectory(dir);
            }
        }

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            java.io.File selected = chooser.getSelectedFile();
            Preferences.get().setLastOpenDirectory(selected.getParent());
            context.openFile(selected.toPath());
        }
    }

    /**
     * Toggle panel visibility. Opening a closed panel docks it to center.
     * Closing a panel undocks it first (resetting the view) then hides.
     */
    public void togglePanel(String title, boolean visible) {
        if (!visible) {
            // Closing: undock if docked (resets view), then hide
            if (dockingManager.isDocked(title)) {
                dockingManager.undock(title);
            }
            EditorPanel panel = panels.get(title);
            if (panel != null) panel.setVisible(false);
        } else {
            // Opening: dock to center
            dockingManager.dockCenter(title);
        }
    }

    /** Check if a panel is currently visible (floating or docked). */
    public boolean isPanelVisible(String title) {
        if (dockingManager.isDocked(title)) return true;
        EditorPanel panel = panels.get(title);
        return panel != null && panel.isVisible();
    }

    /** Reset layout to defaults: undock everything, delete saved layout, restore default positions. */
    public void resetLayout() {
        dockingManager.undockAll();
        LayoutPersistence.delete();
        arrangeDefaultLayout();
    }
}
