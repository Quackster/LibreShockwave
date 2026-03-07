package com.libreshockwave.player;

import javax.swing.*;
import java.nio.file.Path;
import java.util.prefs.Preferences;

/**
 * Main application entry point for the LibreShockwave Player.
 * A Java Swing application for playing Director/Shockwave movies.
 * Supports both local file paths and HTTP/HTTPS URLs.
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

            // If a file/URL was passed as argument, open it
            if (args.length > 0) {
                String arg = args[0];
                if (isUrl(arg)) {
                    frame.openUrl(arg);
                } else {
                    frame.openFile(Path.of(arg));
                }
            } else {
                // No argument - check for previous session
                promptForPreviousSession(frame);
            }
        });
    }

    /**
     * Check if the argument is a URL.
     */
    private static boolean isUrl(String arg) {
        String lower = arg.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /**
     * Prompt user to resume from previous session if one exists.
     */
    private static void promptForPreviousSession(PlayerFrame frame) {
        Preferences prefs = Preferences.userNodeForPackage(PlayerFrame.class);
        String lastPath = prefs.get("lastFile", null);
        String lastUrl = prefs.get("lastUrl", null);

        // Prefer URL if it was more recently used, otherwise use file
        String lastSource = lastUrl != null ? lastUrl : lastPath;
        boolean isLastUrl = lastUrl != null;

        if (lastSource != null && !lastSource.isEmpty()) {
            // Check if file exists (for local files only)
            if (!isLastUrl && !Path.of(lastSource).toFile().exists()) {
                return;
            }

            String displayName = isLastUrl ? lastSource : Path.of(lastSource).getFileName().toString();
            int result = JOptionPane.showConfirmDialog(
                frame,
                "Resume playback from previous session?\n\n" + displayName,
                "Resume Previous Session",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            );

            if (result == JOptionPane.YES_OPTION) {
                if (isLastUrl) {
                    frame.openUrl(lastSource);
                } else {
                    frame.openFile(Path.of(lastSource));
                }
            }
        }
    }
}
