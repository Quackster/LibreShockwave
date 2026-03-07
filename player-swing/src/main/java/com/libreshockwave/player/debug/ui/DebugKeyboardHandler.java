package com.libreshockwave.player.debug.ui;

import com.libreshockwave.player.debug.DebugController;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Registers debug-related keyboard shortcuts on a root pane.
 * Provides F5/F6/F10/F11 shortcuts for debugging operations.
 */
public class DebugKeyboardHandler {

    private final DebugController controller;

    public DebugKeyboardHandler(DebugController controller) {
        this.controller = controller;
    }

    /**
     * Register keyboard shortcuts on the given root pane.
     */
    public void register(JRootPane rootPane) {
        InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = rootPane.getActionMap();

        // F5 - Continue
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "debug.continue");
        actionMap.put("debug.continue", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (controller != null && controller.isPaused()) {
                    controller.continueExecution();
                }
            }
        });

        // F6 - Pause
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), "debug.pause");
        actionMap.put("debug.pause", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (controller != null && !controller.isPaused()) {
                    controller.pause();
                }
            }
        });

        // F10 - Step Over
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, 0), "debug.stepOver");
        actionMap.put("debug.stepOver", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (controller != null && controller.isPaused()) {
                    controller.stepOver();
                }
            }
        });

        // F11 - Step Into
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), "debug.stepInto");
        actionMap.put("debug.stepInto", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (controller != null && controller.isPaused()) {
                    controller.stepInto();
                }
            }
        });

        // Shift+F11 - Step Out
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F11, InputEvent.SHIFT_DOWN_MASK), "debug.stepOut");
        actionMap.put("debug.stepOut", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (controller != null && controller.isPaused()) {
                    controller.stepOut();
                }
            }
        });
    }

    /**
     * Static convenience method to register shortcuts.
     */
    public static void registerShortcuts(JRootPane rootPane, DebugController controller) {
        new DebugKeyboardHandler(controller).register(rootPane);
    }
}
