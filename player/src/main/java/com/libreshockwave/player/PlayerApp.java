package com.libreshockwave.player;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

/**
 * Main application entry point for the LibreShockwave Player.
 * A Java Swing application for playing Director/Shockwave movies.
 */
public class PlayerApp {

    public static void main(String[] args) {
        // Set system look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Fall back to default
        }

        // Launch on EDT
        SwingUtilities.invokeLater(() -> {
            PlayerFrame frame = new PlayerFrame();
            frame.setVisible(true);

            // If a file was passed as argument, open it
            if (args.length > 0) {
                frame.openFile(Path.of(args[0]));
            }
        });
    }
}
