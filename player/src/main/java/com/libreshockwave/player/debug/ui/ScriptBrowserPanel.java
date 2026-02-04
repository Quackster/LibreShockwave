package com.libreshockwave.player.debug.ui;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.player.cast.CastLib;
import com.libreshockwave.player.cast.CastLibManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Panel with Script/Handler combo boxes and filter fields for browsing scripts.
 */
public class ScriptBrowserPanel extends JPanel {

    /**
     * Listener for script browser selection events.
     */
    public interface ScriptBrowserListener {
        void onScriptSelected(ScriptItem script);
        void onHandlerSelected(HandlerItem handler);
    }

    private static final int FILTER_DEBOUNCE_MS = 150;

    private final JComboBox<ScriptItem> scriptCombo;
    private final JComboBox<HandlerItem> handlerCombo;
    private final DefaultComboBoxModel<ScriptItem> scriptModel;
    private final DefaultComboBoxModel<HandlerItem> handlerModel;
    private final JTextField scriptFilterField;
    private final JTextField handlerFilterField;
    private final JButton viewHandlerDetailsBtn;

    private final List<ScriptItem> allScriptItems = new ArrayList<>();
    private final List<HandlerItem> allHandlerItems = new ArrayList<>();
    private final List<ScriptChunk> allScripts = new ArrayList<>();

    private javax.swing.Timer scriptFilterTimer;
    private javax.swing.Timer handlerFilterTimer;

    private ScriptBrowserListener listener;
    private DirectorFile directorFile;
    private CastLibManager castLibManager;

    public ScriptBrowserPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        // Row 1: Script filter and combo
        JPanel scriptRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        scriptRow.add(new JLabel("Script:"));

        scriptFilterField = new JTextField(10);
        scriptFilterField.setToolTipText("Type to filter scripts");
        scriptFilterTimer = new javax.swing.Timer(FILTER_DEBOUNCE_MS, e -> filterScripts());
        scriptFilterTimer.setRepeats(false);
        scriptFilterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { scriptFilterTimer.restart(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { scriptFilterTimer.restart(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { scriptFilterTimer.restart(); }
        });
        scriptRow.add(scriptFilterField);

        scriptModel = new DefaultComboBoxModel<>();
        scriptCombo = new JComboBox<>(scriptModel);
        scriptCombo.setPreferredSize(new Dimension(350, 24));
        scriptCombo.setMaximumRowCount(20);
        scriptCombo.addActionListener(e -> onScriptSelected());
        scriptRow.add(scriptCombo);
        add(scriptRow);

        // Row 2: Handler filter, combo and details button
        JPanel handlerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        handlerRow.add(new JLabel("Handler:"));

        handlerFilterField = new JTextField(10);
        handlerFilterField.setToolTipText("Type to filter handlers");
        handlerFilterTimer = new javax.swing.Timer(FILTER_DEBOUNCE_MS, e -> filterHandlers());
        handlerFilterTimer.setRepeats(false);
        handlerFilterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { handlerFilterTimer.restart(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { handlerFilterTimer.restart(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { handlerFilterTimer.restart(); }
        });
        handlerRow.add(handlerFilterField);

        handlerModel = new DefaultComboBoxModel<>();
        handlerCombo = new JComboBox<>(handlerModel);
        handlerCombo.setPreferredSize(new Dimension(200, 24));
        handlerCombo.addActionListener(e -> onHandlerSelected());
        handlerRow.add(handlerCombo);

        viewHandlerDetailsBtn = new JButton("\u2139");  // Info symbol
        viewHandlerDetailsBtn.setToolTipText("View Handler Details");
        viewHandlerDetailsBtn.setMargin(new Insets(2, 6, 2, 6));
        handlerRow.add(viewHandlerDetailsBtn);
        add(handlerRow);
    }

    /**
     * Set the listener for selection events.
     */
    public void setListener(ScriptBrowserListener listener) {
        this.listener = listener;
    }

    /**
     * Set action for the "View Handler Details" button.
     */
    public void setViewHandlerDetailsAction(Runnable action) {
        // Remove existing listeners
        for (ActionListener l : viewHandlerDetailsBtn.getActionListeners()) {
            viewHandlerDetailsBtn.removeActionListener(l);
        }
        viewHandlerDetailsBtn.addActionListener(e -> action.run());
    }

    /**
     * Populate the script browser from a DirectorFile and optional CastLibManager.
     */
    public void setDirectorFile(DirectorFile file, CastLibManager castLibManager) {
        this.directorFile = file;
        this.castLibManager = castLibManager;
        this.allScripts.clear();
        this.allScriptItems.clear();

        scriptModel.removeAllElements();
        handlerModel.removeAllElements();
        scriptFilterField.setText("");
        handlerFilterField.setText("");

        if (file == null) {
            return;
        }

        Set<Integer> seenScriptIds = new HashSet<>();
        int loadOrder = 0;

        // Get main file name from base path
        String mainFileName = "Main";
        String basePath = file.getBasePath();
        if (basePath != null && !basePath.isEmpty()) {
            if (basePath.contains("/")) {
                mainFileName = basePath.substring(basePath.lastIndexOf('/') + 1);
            } else if (basePath.contains("\\")) {
                mainFileName = basePath.substring(basePath.lastIndexOf('\\') + 1);
            } else {
                mainFileName = basePath;
            }
        }

        // Collect scripts from main file first
        for (ScriptChunk script : file.getScripts()) {
            if (!seenScriptIds.contains(script.id())) {
                allScripts.add(script);
                seenScriptIds.add(script.id());
                if (!script.handlers().isEmpty()) {
                    allScriptItems.add(new ScriptItem(script, mainFileName, loadOrder++));
                }
            }
        }

        // Then collect scripts from all cast libraries
        if (castLibManager != null) {
            for (CastLib castLib : castLibManager.getCastLibs().values()) {
                String sourceName = castLib.getFileName();
                if (sourceName == null || sourceName.isEmpty()) {
                    sourceName = castLib.getName();
                }
                if (sourceName == null || sourceName.isEmpty()) {
                    sourceName = "Cast " + castLib.getNumber();
                }
                if (sourceName.contains("/")) {
                    sourceName = sourceName.substring(sourceName.lastIndexOf('/') + 1);
                }
                if (sourceName.contains("\\")) {
                    sourceName = sourceName.substring(sourceName.lastIndexOf('\\') + 1);
                }

                for (ScriptChunk script : castLib.getAllScripts()) {
                    if (!seenScriptIds.contains(script.id())) {
                        allScripts.add(script);
                        seenScriptIds.add(script.id());
                        if (!script.handlers().isEmpty()) {
                            allScriptItems.add(new ScriptItem(script, sourceName, loadOrder++));
                        }
                    }
                }
            }
        }

        // Populate script combo
        for (ScriptItem item : allScriptItems) {
            scriptModel.addElement(item);
        }

        // Auto-select first script if available
        if (scriptModel.getSize() > 0) {
            scriptCombo.setSelectedIndex(0);
        }
    }

    /**
     * Refresh the script list from the current DirectorFile and CastLibManager.
     */
    public void refreshScriptList() {
        if (directorFile == null) {
            return;
        }

        ScriptItem selectedScript = (ScriptItem) scriptCombo.getSelectedItem();
        HandlerItem selectedHandler = (HandlerItem) handlerCombo.getSelectedItem();
        int selectedScriptId = selectedScript != null ? selectedScript.getScript().id() : -1;
        String selectedHandlerName = selectedHandler != null ?
            selectedHandler.getScript().getHandlerName(selectedHandler.getHandler()) : null;

        setDirectorFile(directorFile, castLibManager);

        // Try to restore selection
        if (selectedScriptId >= 0) {
            for (int i = 0; i < scriptModel.getSize(); i++) {
                if (scriptModel.getElementAt(i).getScript().id() == selectedScriptId) {
                    scriptCombo.setSelectedIndex(i);
                    if (selectedHandlerName != null) {
                        for (int j = 0; j < handlerModel.getSize(); j++) {
                            HandlerItem h = handlerModel.getElementAt(j);
                            if (h.getScript().getHandlerName(h.getHandler()).equals(selectedHandlerName)) {
                                handlerCombo.setSelectedIndex(j);
                                break;
                            }
                        }
                    }
                    break;
                }
            }
        }
    }

    private void filterScripts() {
        String filter = scriptFilterField.getText().trim();
        ScriptItem currentSelection = (ScriptItem) scriptCombo.getSelectedItem();
        int currentScriptId = currentSelection != null ? currentSelection.getScript().id() : -1;

        ActionListener[] listeners = scriptCombo.getActionListeners();
        for (ActionListener l : listeners) {
            scriptCombo.removeActionListener(l);
        }

        scriptModel.removeAllElements();
        ScriptItem firstMatch = null;
        ScriptItem selectedMatch = null;

        for (ScriptItem item : allScriptItems) {
            if (item.matchesFilter(filter)) {
                scriptModel.addElement(item);
                if (firstMatch == null) {
                    firstMatch = item;
                }
                if (item.getScript().id() == currentScriptId) {
                    selectedMatch = item;
                }
            }
        }

        for (ActionListener l : listeners) {
            scriptCombo.addActionListener(l);
        }

        if (selectedMatch != null) {
            scriptCombo.setSelectedItem(selectedMatch);
        } else if (firstMatch != null) {
            scriptCombo.setSelectedItem(firstMatch);
            onScriptSelected();
        }
    }

    private void filterHandlers() {
        String filter = handlerFilterField.getText().trim();
        HandlerItem currentSelection = (HandlerItem) handlerCombo.getSelectedItem();
        String currentHandlerName = currentSelection != null ?
            currentSelection.getScript().getHandlerName(currentSelection.getHandler()) : null;

        ActionListener[] listeners = handlerCombo.getActionListeners();
        for (ActionListener l : listeners) {
            handlerCombo.removeActionListener(l);
        }

        handlerModel.removeAllElements();
        HandlerItem firstMatch = null;
        HandlerItem selectedMatch = null;

        for (HandlerItem item : allHandlerItems) {
            if (item.matchesFilter(filter)) {
                handlerModel.addElement(item);
                if (firstMatch == null) {
                    firstMatch = item;
                }
                if (currentHandlerName != null &&
                    item.getScript().getHandlerName(item.getHandler()).equals(currentHandlerName)) {
                    selectedMatch = item;
                }
            }
        }

        for (ActionListener l : listeners) {
            handlerCombo.addActionListener(l);
        }

        if (selectedMatch != null) {
            handlerCombo.setSelectedItem(selectedMatch);
        } else if (firstMatch != null) {
            handlerCombo.setSelectedItem(firstMatch);
            onHandlerSelected();
        }
    }

    private void onScriptSelected() {
        ScriptItem selected = (ScriptItem) scriptCombo.getSelectedItem();
        handlerModel.removeAllElements();
        allHandlerItems.clear();
        handlerFilterField.setText("");

        if (selected == null) {
            return;
        }

        ScriptChunk script = selected.getScript();
        for (ScriptChunk.Handler handler : script.handlers()) {
            HandlerItem item = new HandlerItem(script, handler);
            allHandlerItems.add(item);
            handlerModel.addElement(item);
        }

        if (handlerModel.getSize() > 0) {
            handlerCombo.setSelectedIndex(0);
        }

        if (listener != null) {
            listener.onScriptSelected(selected);
        }
    }

    private void onHandlerSelected() {
        HandlerItem selected = (HandlerItem) handlerCombo.getSelectedItem();
        if (selected != null && listener != null) {
            listener.onHandlerSelected(selected);
        }
    }

    /**
     * Select a script in the combo box without triggering listener events.
     */
    public void selectScriptSilently(ScriptChunk script) {
        for (int i = 0; i < scriptModel.getSize(); i++) {
            if (scriptModel.getElementAt(i).getScript() == script) {
                ActionListener[] listeners = scriptCombo.getActionListeners();
                for (ActionListener l : listeners) {
                    scriptCombo.removeActionListener(l);
                }
                scriptCombo.setSelectedIndex(i);
                for (ActionListener l : listeners) {
                    scriptCombo.addActionListener(l);
                }
                break;
            }
        }
    }

    /**
     * Select a handler in the combo box without triggering listener events.
     * Also updates the handler model for the given script.
     */
    public void selectHandlerSilently(ScriptChunk script, ScriptChunk.Handler handler) {
        // Update handler model
        handlerModel.removeAllElements();
        allHandlerItems.clear();
        for (ScriptChunk.Handler h : script.handlers()) {
            HandlerItem item = new HandlerItem(script, h);
            allHandlerItems.add(item);
            handlerModel.addElement(item);
        }

        // Select handler
        for (int i = 0; i < handlerModel.getSize(); i++) {
            if (handlerModel.getElementAt(i).getHandler() == handler) {
                ActionListener[] listeners = handlerCombo.getActionListeners();
                for (ActionListener l : listeners) {
                    handlerCombo.removeActionListener(l);
                }
                handlerCombo.setSelectedIndex(i);
                for (ActionListener l : listeners) {
                    handlerCombo.addActionListener(l);
                }
                break;
            }
        }
    }

    /**
     * Clear the filter fields.
     */
    public void clearFilters() {
        scriptFilterField.setText("");
        handlerFilterField.setText("");
    }

    /**
     * Get the currently selected handler item.
     */
    public HandlerItem getSelectedHandler() {
        return (HandlerItem) handlerCombo.getSelectedItem();
    }

    /**
     * Get the currently selected script item.
     */
    public ScriptItem getSelectedScript() {
        return (ScriptItem) scriptCombo.getSelectedItem();
    }

    /**
     * Get all scripts.
     */
    public List<ScriptChunk> getAllScripts() {
        return allScripts;
    }

    /**
     * Get the current DirectorFile.
     */
    public DirectorFile getDirectorFile() {
        return directorFile;
    }
}
