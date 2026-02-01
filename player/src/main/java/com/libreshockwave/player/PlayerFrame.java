package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.vm.Datum;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.prefs.Preferences;

/**
 * Main window for the LibreShockwave Player.
 * Provides playback controls and stage rendering.
 */
public class PlayerFrame extends JFrame {

    private static final String PREF_LAST_FILE = "lastFile";
    private static final String PREF_LAST_DIR = "lastDirectory";
    private final Preferences prefs = Preferences.userNodeForPackage(PlayerFrame.class);

    private Player player;
    private Timer playbackTimer;
    private StagePanel stagePanel;
    private JLabel statusLabel;
    private JLabel frameLabel;
    private JButton playButton;
    private JButton pauseButton;
    private JButton stopButton;
    private JButton stepButton;
    private JSlider frameSlider;
    private DebugPanel debugPanel;
    private JSplitPane splitPane;
    private boolean debugVisible = true;
    private Path lastOpenedFile;

    public PlayerFrame() {
        super("LibreShockwave Player");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        initComponents();
        initMenuBar();
        loadLastFilePreference();
        pack();
        setLocationRelativeTo(null);
    }

    private void loadLastFilePreference() {
        String lastFilePath = prefs.get(PREF_LAST_FILE, null);
        if (lastFilePath != null) {
            lastOpenedFile = Path.of(lastFilePath);
            if (lastOpenedFile.toFile().exists()) {
                statusLabel.setText("Last file: " + lastOpenedFile.getFileName() +
                    "  \u2022  Press Ctrl+O to open, or drag & drop a file");
            }
        }
    }

    private void saveLastFilePreference(Path path) {
        lastOpenedFile = path;
        prefs.put(PREF_LAST_FILE, path.toAbsolutePath().toString());
        prefs.put(PREF_LAST_DIR, path.getParent().toAbsolutePath().toString());
    }

    private void reopenLastFile() {
        if (lastOpenedFile != null && lastOpenedFile.toFile().exists()) {
            openFile(lastOpenedFile);
        } else {
            JOptionPane.showMessageDialog(this,
                "No recent file to reopen.",
                "Reopen Last File",
                JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void initComponents() {
        setLayout(new BorderLayout());

        // Stage panel (center)
        stagePanel = new StagePanel();
        stagePanel.setPreferredSize(new Dimension(640, 480));
        stagePanel.setBackground(Color.WHITE);

        // Debug panel (right side)
        debugPanel = new DebugPanel();

        // Split pane for stage and debug
        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, stagePanel, debugPanel);
        splitPane.setResizeWeight(0.6);
        splitPane.setOneTouchExpandable(true);
        add(splitPane, BorderLayout.CENTER);

        // Control panel (bottom)
        JPanel controlPanel = new JPanel(new BorderLayout());

        // Transport controls
        JPanel transportPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        playButton = new JButton("\u25B6");  // Play triangle
        playButton.setToolTipText("Play");
        playButton.addActionListener(e -> play());
        transportPanel.add(playButton);

        pauseButton = new JButton("\u23F8");  // Pause
        pauseButton.setToolTipText("Pause");
        pauseButton.addActionListener(e -> pause());
        pauseButton.setEnabled(false);
        transportPanel.add(pauseButton);

        stopButton = new JButton("\u23F9");  // Stop
        stopButton.setToolTipText("Stop");
        stopButton.addActionListener(e -> stop());
        stopButton.setEnabled(false);
        transportPanel.add(stopButton);

        transportPanel.add(Box.createHorizontalStrut(10));

        stepButton = new JButton("\u23ED");  // Next frame
        stepButton.setToolTipText("Step Frame");
        stepButton.addActionListener(e -> stepFrame());
        transportPanel.add(stepButton);

        controlPanel.add(transportPanel, BorderLayout.WEST);

        // Frame slider
        JPanel sliderPanel = new JPanel(new BorderLayout());
        sliderPanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

        frameSlider = new JSlider(1, 100, 1);
        frameSlider.setEnabled(false);
        frameSlider.addChangeListener(e -> {
            if (frameSlider.getValueIsAdjusting() && player != null) {
                player.goToFrame(frameSlider.getValue());
            }
        });
        sliderPanel.add(frameSlider, BorderLayout.CENTER);

        frameLabel = new JLabel("Frame: 1 / 1");
        frameLabel.setPreferredSize(new Dimension(120, 20));
        sliderPanel.add(frameLabel, BorderLayout.EAST);

        controlPanel.add(sliderPanel, BorderLayout.CENTER);

        add(controlPanel, BorderLayout.SOUTH);

        // Status bar
        statusLabel = new JLabel("No file loaded. Open a .dir, .dxr, or .dcr file.");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        add(statusLabel, BorderLayout.NORTH);

        // Keyboard shortcuts
        setupKeyboardShortcuts();
    }

    private void initMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // File menu
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);

        JMenuItem openItem = new JMenuItem("Open...");
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        openItem.addActionListener(e -> openFileDialog());
        fileMenu.add(openItem);

        JMenuItem reopenItem = new JMenuItem("Reopen Last File");
        reopenItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        reopenItem.addActionListener(e -> reopenLastFile());
        fileMenu.add(reopenItem);

        fileMenu.addSeparator();

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        fileMenu.add(exitItem);

        menuBar.add(fileMenu);

        // Playback menu
        JMenu playbackMenu = new JMenu("Playback");
        playbackMenu.setMnemonic(KeyEvent.VK_P);

        JMenuItem playItem = new JMenuItem("Play");
        playItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0));
        playItem.addActionListener(e -> togglePlayPause());
        playbackMenu.add(playItem);

        JMenuItem stopItem = new JMenuItem("Stop");
        stopItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        stopItem.addActionListener(e -> stop());
        playbackMenu.add(stopItem);

        playbackMenu.addSeparator();

        JMenuItem stepItem = new JMenuItem("Step Forward");
        stepItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0));
        stepItem.addActionListener(e -> stepFrame());
        playbackMenu.add(stepItem);

        JMenuItem stepBackItem = new JMenuItem("Step Backward");
        stepBackItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0));
        stepBackItem.addActionListener(e -> stepBackward());
        playbackMenu.add(stepBackItem);

        playbackMenu.addSeparator();

        JMenuItem goToFirstItem = new JMenuItem("Go to First Frame");
        goToFirstItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0));
        goToFirstItem.addActionListener(e -> goToFrame(1));
        playbackMenu.add(goToFirstItem);

        JMenuItem goToLastItem = new JMenuItem("Go to Last Frame");
        goToLastItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_END, 0));
        goToLastItem.addActionListener(e -> goToLastFrame());
        playbackMenu.add(goToLastItem);

        menuBar.add(playbackMenu);

        // View menu
        JMenu viewMenu = new JMenu("View");
        viewMenu.setMnemonic(KeyEvent.VK_V);

        JCheckBoxMenuItem debugItem = new JCheckBoxMenuItem("Debug Panel", true);
        debugItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK));
        debugItem.addActionListener(e -> toggleDebugPanel(debugItem.isSelected()));
        viewMenu.add(debugItem);

        viewMenu.addSeparator();

        JMenuItem clearDebugItem = new JMenuItem("Clear Debug Log");
        clearDebugItem.addActionListener(e -> debugPanel.clearLog());
        viewMenu.add(clearDebugItem);

        menuBar.add(viewMenu);

        // Help menu
        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);

        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> showAbout());
        helpMenu.add(aboutItem);

        menuBar.add(helpMenu);

        setJMenuBar(menuBar);
    }

    private void setupKeyboardShortcuts() {
        // Space toggles play/pause
        getRootPane().registerKeyboardAction(
            e -> togglePlayPause(),
            KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        );
    }

    // File operations

    public void openFileDialog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open Director File");
        chooser.setFileFilter(new FileNameExtensionFilter(
            "Director Files (*.dir, *.dxr, *.dcr, *.cct, *.cst)",
            "dir", "dxr", "dcr", "cct", "cst"
        ));

        // Start in the last used directory
        String lastDir = prefs.get(PREF_LAST_DIR, null);
        if (lastDir != null) {
            File dir = new File(lastDir);
            if (dir.exists() && dir.isDirectory()) {
                chooser.setCurrentDirectory(dir);
            }
        }

        // Pre-select the last file if it exists
        if (lastOpenedFile != null && lastOpenedFile.toFile().exists()) {
            chooser.setSelectedFile(lastOpenedFile.toFile());
        }

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openFile(chooser.getSelectedFile().toPath());
        }
    }

    public void openFile(Path path) {
        stop();

        try {
            DirectorFile file = DirectorFile.load(path);
            player = new Player(file);

            // Save this file as the last opened
            saveLastFilePreference(path);

            // Update UI
            setTitle("LibreShockwave Player - " + path.getFileName());
            statusLabel.setText("Loaded: " + path.getFileName() +
                " | Frames: " + player.getFrameCount() +
                " | Tempo: " + player.getTempo() + " fps");

            // Update stage size
            int width = file.getStageWidth();
            int height = file.getStageHeight();
            if (width > 0 && height > 0) {
                stagePanel.setPreferredSize(new Dimension(width, height));
                pack();
            }

            // Update frame slider
            int frameCount = player.getFrameCount();
            if (frameCount > 0) {
                frameSlider.setMaximum(frameCount);
                frameSlider.setValue(1);
                frameSlider.setEnabled(true);
            }

            updateFrameLabel();

            // Set up player event listener
            player.setEventListener(event -> {
                SwingUtilities.invokeLater(this::updateFrameLabel);
            });

            // Set stage background color from movie config
            if (file.getConfig() != null) {
                int stageColor = file.getConfig().stageColor();
                // Convert Director palette index to RGB (grayscale for now)
                int rgb = (stageColor & 0xFF) | ((stageColor & 0xFF) << 8) | ((stageColor & 0xFF) << 16);
                player.getStageRenderer().setBackgroundColor(rgb);
            }

            // Connect debug panel to VM trace
            player.getVM().setTraceListener(debugPanel);
            player.setDebugEnabled(true);

            stagePanel.setPlayer(player);

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                "Failed to load file: " + e.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }

    // Playback controls

    private void play() {
        if (player == null) return;

        player.play();
        startPlaybackTimer();
        updateButtonStates();
    }

    private void pause() {
        if (player == null) return;

        player.pause();
        stopPlaybackTimer();
        updateButtonStates();
    }

    private void stop() {
        if (player == null) return;

        player.stop();
        stopPlaybackTimer();
        updateButtonStates();
        updateFrameLabel();
        stagePanel.repaint();
    }

    private void togglePlayPause() {
        if (player == null) return;

        if (player.getState() == PlayerState.PLAYING) {
            pause();
        } else {
            play();
        }
    }

    private void stepFrame() {
        if (player == null) return;

        player.stepFrame();
        updateFrameLabel();
        stagePanel.repaint();
    }

    private void stepBackward() {
        if (player == null) return;

        int current = player.getCurrentFrame();
        if (current > 1) {
            player.goToFrame(current - 1);
            player.stepFrame();
            updateFrameLabel();
            stagePanel.repaint();
        }
    }

    private void goToFrame(int frame) {
        if (player == null) return;

        player.goToFrame(frame);
        if (player.getState() != PlayerState.PLAYING) {
            player.stepFrame();
        }
        updateFrameLabel();
        stagePanel.repaint();
    }

    private void goToLastFrame() {
        if (player == null) return;

        goToFrame(player.getFrameCount());
    }

    // Timer management

    private void startPlaybackTimer() {
        if (playbackTimer != null) {
            playbackTimer.stop();
        }

        int delay = 1000 / player.getTempo();
        playbackTimer = new Timer(delay, e -> {
            if (player != null && player.tick()) {
                updateFrameLabel();
                stagePanel.repaint();
            } else {
                stopPlaybackTimer();
                updateButtonStates();
            }
        });
        playbackTimer.start();
    }

    private void stopPlaybackTimer() {
        if (playbackTimer != null) {
            playbackTimer.stop();
            playbackTimer = null;
        }
    }

    // UI updates

    private void updateButtonStates() {
        boolean hasPlayer = player != null;
        PlayerState state = hasPlayer ? player.getState() : PlayerState.STOPPED;

        playButton.setEnabled(hasPlayer && state != PlayerState.PLAYING);
        pauseButton.setEnabled(hasPlayer && state == PlayerState.PLAYING);
        stopButton.setEnabled(hasPlayer && state != PlayerState.STOPPED);
        stepButton.setEnabled(hasPlayer);
    }

    private void updateFrameLabel() {
        if (player != null) {
            int current = player.getCurrentFrame();
            int total = player.getFrameCount();
            frameLabel.setText("Frame: " + current + " / " + total);

            if (!frameSlider.getValueIsAdjusting()) {
                frameSlider.setValue(current);
            }
        } else {
            frameLabel.setText("Frame: 1 / 1");
        }
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
            "LibreShockwave Player\n\n" +
            "A Java-based player for Adobe Director/Macromedia Shockwave files.\n\n" +
            "Supports .dir, .dxr, .dcr, .cct, and .cst files.\n\n" +
            "Part of the LibreShockwave project.",
            "About",
            JOptionPane.INFORMATION_MESSAGE);
    }

    private void toggleDebugPanel(boolean visible) {
        debugVisible = visible;
        if (visible) {
            splitPane.setRightComponent(debugPanel);
            splitPane.setDividerLocation(0.6);
        } else {
            splitPane.setRightComponent(null);
        }
        splitPane.revalidate();
    }

    public DebugPanel getDebugPanel() {
        return debugPanel;
    }
}
