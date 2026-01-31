package com.libreshockwave.tools;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.cast.ShapeInfo;
import com.libreshockwave.cast.FilmLoopInfo;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.Chunk;
import com.libreshockwave.chunks.KeyTableChunk;
import com.libreshockwave.chunks.PaletteChunk;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptContextChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.chunks.SoundChunk;
import com.libreshockwave.chunks.TextChunk;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.audio.SoundConverter;
import com.libreshockwave.lingo.Opcode;

import javax.sound.sampled.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A Swing application for browsing and viewing cast members
 * from Shockwave/Director files, including scripts and bytecode.
 */
public class CastExtractorTool extends JFrame {

    private static final String PREF_INPUT_DIR = "lastInputDirectory";
    private static final String PREF_OUTPUT_DIR = "lastOutputDirectory";

    private final Preferences prefs = Preferences.userNodeForPackage(CastExtractorTool.class);

    private JTextField inputDirField;
    private JTextField outputDirField;
    private JTree fileTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private JLabel previewLabel;
    private JTextArea detailsTextArea;
    private JScrollPane previewScrollPane;
    private JScrollPane detailsScrollPane;
    private JPanel previewContainer;
    private JLabel statusLabel;
    private JButton extractButton;
    private JButton extractAllButton;
    private JProgressBar progressBar;
    private JTextField searchField;
    private JComboBox<String> typeFilterCombo;
    private JButton playButton;
    private JButton stopButton;
    private JSlider positionSlider;
    private JLabel timeLabel;
    private JPanel audioPanel;
    private Clip currentClip;
    private javax.swing.Timer playbackTimer;

    // Lazy loading cache using soft references to allow GC when memory is low
    private final Map<BitmapKey, SoftReference<BufferedImage>> imageCache = new ConcurrentHashMap<>();
    private final Map<String, DirectorFile> loadedFiles = new ConcurrentHashMap<>();

    // Store all file nodes for filtering
    private List<FileNode> allFileNodes = new ArrayList<>();

    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1)
    );

    public CastExtractorTool() {
        super("Cast Extractor Tool - LibreShockwave");
        initializeUI();
        loadSavedPreferences();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
    }

    private void initializeUI() {
        setLayout(new BorderLayout(5, 5));

        // Top panel with directory selection
        JPanel topPanel = createDirectoryPanel();
        add(topPanel, BorderLayout.NORTH);

        // Split pane with tree and preview
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(400);

        // Left: File/Cast member tree
        JPanel treePanel = createTreePanel();
        splitPane.setLeftComponent(treePanel);

        // Right: Preview panel
        JPanel previewPanel = createPreviewPanel();
        splitPane.setRightComponent(previewPanel);

        add(splitPane, BorderLayout.CENTER);

        // Bottom: Status and progress
        JPanel bottomPanel = createBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private JPanel createDirectoryPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Input directory
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Input Directory:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        inputDirField = new JTextField(30);
        inputDirField.setEditable(false);
        panel.add(inputDirField, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        JButton browseInputBtn = new JButton("Browse...");
        browseInputBtn.addActionListener(this::browseInputDirectory);
        panel.add(browseInputBtn, gbc);

        gbc.gridx = 3;
        JButton scanBtn = new JButton("Scan");
        scanBtn.addActionListener(this::scanDirectory);
        panel.add(scanBtn, gbc);

        // Output directory
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        panel.add(new JLabel("Output Directory:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        outputDirField = new JTextField(30);
        outputDirField.setEditable(false);
        panel.add(outputDirField, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        JButton browseOutputBtn = new JButton("Browse...");
        browseOutputBtn.addActionListener(this::browseOutputDirectory);
        panel.add(browseOutputBtn, gbc);

        // Extract buttons
        gbc.gridx = 3;
        extractButton = new JButton("Extract Selected");
        extractButton.setEnabled(false);
        extractButton.addActionListener(this::extractSelected);
        panel.add(extractButton, gbc);

        gbc.gridx = 4;
        extractAllButton = new JButton("Extract All");
        extractAllButton.setEnabled(false);
        extractAllButton.addActionListener(this::extractAll);
        panel.add(extractAllButton, gbc);

        return panel;
    }

    private JPanel createTreePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Files & Cast Members"));

        // Search and filter panel
        JPanel filterPanel = new JPanel(new BorderLayout(5, 5));
        filterPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Search field
        searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Search by name...");
        searchField.addActionListener(e -> applyFilter());
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });

        // Type filter combo
        typeFilterCombo = new JComboBox<>(new String[]{
                "All Types", "bitmap", "script", "sound", "text", "shape",
                "palette", "filmLoop", "button", "digitalVideo", "flash"
        });
        typeFilterCombo.addActionListener(e -> applyFilter());

        filterPanel.add(searchField, BorderLayout.CENTER);
        filterPanel.add(typeFilterCombo, BorderLayout.EAST);

        rootNode = new DefaultMutableTreeNode("Files");
        treeModel = new DefaultTreeModel(rootNode);
        fileTree = new JTree(treeModel);
        fileTree.setRootVisible(false);
        fileTree.setCellRenderer(new FileTreeCellRenderer());
        fileTree.addTreeSelectionListener(this::onTreeSelection);

        JScrollPane scrollPane = new JScrollPane(fileTree);

        panel.add(filterPanel, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createPreviewPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Preview / Details"));

        previewContainer = new JPanel(new CardLayout());

        // Image preview for bitmaps
        previewLabel = new JLabel("Select a cast member to view details", SwingConstants.CENTER);
        previewLabel.setPreferredSize(new Dimension(400, 400));
        previewScrollPane = new JScrollPane(previewLabel);
        previewScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        previewScrollPane.getHorizontalScrollBar().setUnitIncrement(16);

        // Text area for scripts and other member details
        detailsTextArea = new JTextArea();
        detailsTextArea.setEditable(false);
        detailsTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detailsScrollPane = new JScrollPane(detailsTextArea);
        detailsScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        previewContainer.add(previewScrollPane, "image");
        previewContainer.add(detailsScrollPane, "text");

        panel.add(previewContainer, BorderLayout.CENTER);

        // Audio controls panel
        audioPanel = new JPanel(new BorderLayout(5, 5));
        audioPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        // Buttons panel
        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        playButton = new JButton("Play");
        playButton.setEnabled(false);
        playButton.addActionListener(e -> playCurrentSound());

        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> stopSound());

        buttonsPanel.add(playButton);
        buttonsPanel.add(stopButton);

        // Position slider
        positionSlider = new JSlider(0, 100, 0);
        positionSlider.setEnabled(false);
        positionSlider.addChangeListener(e -> {
            if (positionSlider.getValueIsAdjusting() && currentClip != null) {
                long newPos = (long) ((positionSlider.getValue() / 100.0) * currentClip.getMicrosecondLength());
                currentClip.setMicrosecondPosition(newPos);
            }
        });

        // Time label
        timeLabel = new JLabel("0:00 / 0:00");
        timeLabel.setPreferredSize(new Dimension(100, 20));

        audioPanel.add(buttonsPanel, BorderLayout.WEST);
        audioPanel.add(positionSlider, BorderLayout.CENTER);
        audioPanel.add(timeLabel, BorderLayout.EAST);
        audioPanel.setVisible(false);

        panel.add(audioPanel, BorderLayout.SOUTH);

        // Timer for updating playback position
        playbackTimer = new javax.swing.Timer(100, e -> updatePlaybackPosition());

        return panel;
    }

    private MemberNodeData currentSoundMember = null;

    private void updatePlaybackPosition() {
        if (currentClip != null && currentClip.isRunning()) {
            long pos = currentClip.getMicrosecondPosition();
            long len = currentClip.getMicrosecondLength();
            int percent = (int) ((pos * 100.0) / len);
            positionSlider.setValue(percent);

            // Update time label
            int posSec = (int) (pos / 1_000_000);
            int lenSec = (int) (len / 1_000_000);
            timeLabel.setText(String.format("%d:%02d / %d:%02d",
                    posSec / 60, posSec % 60, lenSec / 60, lenSec % 60));
        }
    }

    private void playCurrentSound() {
        if (currentSoundMember == null) return;

        stopSound(); // Stop any playing sound

        DirectorFile dirFile = loadedFiles.get(currentSoundMember.filePath);
        if (dirFile == null) return;

        SoundChunk soundChunk = findSoundForMember(dirFile, currentSoundMember.memberInfo.member);
        if (soundChunk == null) return;

        try {
            byte[] audioData;

            if (soundChunk.isMp3()) {
                // Extract MP3 data - with mp3spi library, AudioSystem can play MP3
                audioData = SoundConverter.extractMp3(soundChunk);
                if (audioData == null || audioData.length == 0) {
                    statusLabel.setText("Failed to extract MP3 data");
                    return;
                }
            } else {
                // Convert PCM to WAV
                audioData = SoundConverter.toWav(soundChunk);
            }

            if (audioData == null || audioData.length <= 44) {
                statusLabel.setText("No audio data to play");
                return;
            }

            // Play the audio (mp3spi adds MP3 support to AudioSystem)
            AudioInputStream audioStream = AudioSystem.getAudioInputStream(
                    new ByteArrayInputStream(audioData));

            // For MP3, we need to convert to PCM format that Clip can handle
            AudioFormat baseFormat = audioStream.getFormat();
            if (baseFormat.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
                AudioFormat decodedFormat = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        baseFormat.getSampleRate(),
                        16,
                        baseFormat.getChannels(),
                        baseFormat.getChannels() * 2,
                        baseFormat.getSampleRate(),
                        false
                );
                audioStream = AudioSystem.getAudioInputStream(decodedFormat, audioStream);
            }

            currentClip = AudioSystem.getClip();
            currentClip.open(audioStream);
            currentClip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    SwingUtilities.invokeLater(() -> {
                        stopButton.setEnabled(false);
                        playButton.setText("Play");
                        playbackTimer.stop();
                        positionSlider.setValue(0);
                        timeLabel.setText("0:00 / 0:00");
                    });
                }
            });

            // Update UI
            positionSlider.setEnabled(true);
            positionSlider.setValue(0);
            long lenSec = currentClip.getMicrosecondLength() / 1_000_000;
            timeLabel.setText(String.format("0:00 / %d:%02d", lenSec / 60, lenSec % 60));

            currentClip.start();
            playButton.setText("Pause");
            stopButton.setEnabled(true);
            playbackTimer.start();
            statusLabel.setText("Playing: " + currentSoundMember.memberInfo.name);

        } catch (Exception ex) {
            statusLabel.setText("Playback error: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void stopSound() {
        playbackTimer.stop();
        if (currentClip != null) {
            currentClip.stop();
            currentClip.close();
            currentClip = null;
        }
        stopButton.setEnabled(false);
        playButton.setText("Play");
        positionSlider.setValue(0);
        positionSlider.setEnabled(false);
        timeLabel.setText("0:00 / 0:00");
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));

        statusLabel = new JLabel("Ready");
        panel.add(statusLabel, BorderLayout.WEST);

        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        panel.add(progressBar, BorderLayout.CENTER);

        return panel;
    }

    private void loadSavedPreferences() {
        String inputDir = prefs.get(PREF_INPUT_DIR, "");
        String outputDir = prefs.get(PREF_OUTPUT_DIR, "");

        if (!inputDir.isEmpty()) {
            inputDirField.setText(inputDir);
        }
        if (!outputDir.isEmpty()) {
            outputDirField.setText(outputDir);
        }
    }

    private void browseInputDirectory(ActionEvent e) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        String currentDir = inputDirField.getText();
        if (!currentDir.isEmpty()) {
            chooser.setCurrentDirectory(new File(currentDir));
        }

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String path = chooser.getSelectedFile().getAbsolutePath();
            inputDirField.setText(path);
            prefs.put(PREF_INPUT_DIR, path);
        }
    }

    private void browseOutputDirectory(ActionEvent e) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        String currentDir = outputDirField.getText();
        if (!currentDir.isEmpty()) {
            chooser.setCurrentDirectory(new File(currentDir));
        }

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String path = chooser.getSelectedFile().getAbsolutePath();
            outputDirField.setText(path);
            prefs.put(PREF_OUTPUT_DIR, path);
        }
    }

    private void scanDirectory(ActionEvent e) {
        String inputDir = inputDirField.getText();
        if (inputDir.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select an input directory first.",
                    "No Directory", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Path inputPath = Path.of(inputDir);
        if (!Files.isDirectory(inputPath)) {
            JOptionPane.showMessageDialog(this, "The input path is not a valid directory.",
                    "Invalid Directory", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Clear previous data
        clearData();

        // Show progress bar
        progressBar.setVisible(true);
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);

        // Scan in background with multi-threading
        SwingWorker<List<FileNode>, Integer> worker = new SwingWorker<>() {
            @Override
            protected List<FileNode> doInBackground() {
                List<FileNode> fileNodes = Collections.synchronizedList(new ArrayList<>());

                try (Stream<Path> paths = Files.walk(inputPath)) {
                    List<Path> files = paths.filter(Files::isRegularFile).toList();
                    int total = files.size();
                    AtomicInteger processed = new AtomicInteger(0);

                    // Use parallel stream for multi-threaded processing
                    files.parallelStream().forEach(file -> {
                        try {
                            FileNode node = processFile(file);
                            if (node != null) {
                                fileNodes.add(node);
                            }
                        } catch (Exception ignored) {
                            // Silently ignore files that fail to parse
                        }

                        int current = processed.incrementAndGet();
                        // Update progress every 10 files or at the end
                        if (current % 10 == 0 || current == total) {
                            int percent = (int) ((current * 100.0) / total);
                            publish(percent);
                        }
                    });

                } catch (IOException ex) {
                    SwingUtilities.invokeLater(() ->
                        statusLabel.setText("Error scanning directory: " + ex.getMessage()));
                }

                // Sort by filename for consistent ordering
                fileNodes.sort((a, b) -> a.fileName.compareToIgnoreCase(b.fileName));
                return fileNodes;
            }

            @Override
            protected void process(List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int latest = chunks.get(chunks.size() - 1);
                    progressBar.setValue(latest);
                    statusLabel.setText("Scanning... " + latest + "%");
                }
            }

            @Override
            protected void done() {
                progressBar.setVisible(false);
                try {
                    List<FileNode> fileNodes = get();
                    populateTree(fileNodes);

                    int totalMembers = fileNodes.stream()
                            .mapToInt(f -> f.members.size())
                            .sum();

                    // Count by type
                    Map<MemberType, Long> typeCounts = fileNodes.stream()
                            .flatMap(f -> f.members.stream())
                            .collect(Collectors.groupingBy(m -> m.memberType, Collectors.counting()));

                    StringBuilder sb = new StringBuilder();
                    sb.append("Found ").append(fileNodes.size()).append(" files with ").append(totalMembers).append(" members");
                    if (!typeCounts.isEmpty()) {
                        sb.append(" (");
                        List<String> parts = new ArrayList<>();
                        for (Map.Entry<MemberType, Long> entry : typeCounts.entrySet()) {
                            parts.add(entry.getValue() + " " + entry.getKey().getName());
                        }
                        sb.append(String.join(", ", parts));
                        sb.append(")");
                    }

                    statusLabel.setText(sb.toString());
                    extractAllButton.setEnabled(!fileNodes.isEmpty() && !outputDirField.getText().isEmpty());

                } catch (Exception ex) {
                    statusLabel.setText("Error: " + ex.getMessage());
                }
            }
        };

        statusLabel.setText("Scanning...");
        worker.execute();
    }

    /**
     * Process a single file and return a FileNode, or null if no valid members.
     * This method is thread-safe.
     */
    private FileNode processFile(Path file) {
        try {
            DirectorFile dirFile = DirectorFile.load(file);
            List<CastMemberInfo> members = new ArrayList<>();

            // Get all cast members
            for (CastMemberChunk member : dirFile.getCastMembers()) {
                if (member.memberType() == MemberType.NULL) {
                    continue; // Skip null members
                }

                String name = member.name();
                if (name == null || name.isEmpty()) {
                    name = "Unnamed #" + member.id();
                }

                String details = "";
                MemberType type = member.memberType();

                // Parse type-specific details
                if (type == MemberType.BITMAP && member.specificData().length > 0) {
                    try {
                        BitmapInfo info = BitmapInfo.parse(member.specificData());
                        details = String.format("%dx%d, %d-bit", info.width(), info.height(), info.bitDepth());
                    } catch (Exception ignored) {}
                } else if (type == MemberType.SHAPE && member.specificData().length > 0) {
                    try {
                        ShapeInfo info = ShapeInfo.parse(member.specificData());
                        details = String.format("%s %dx%d", info.shapeType(), info.width(), info.height());
                    } catch (Exception ignored) {}
                } else if (type == MemberType.FILM_LOOP && member.specificData().length > 0) {
                    try {
                        FilmLoopInfo info = FilmLoopInfo.parse(member.specificData());
                        details = String.format("%dx%d", info.width(), info.height());
                    } catch (Exception ignored) {}
                } else if (type == MemberType.SCRIPT) {
                    // Find the script chunk for this member
                    ScriptChunk script = findScriptForMember(dirFile, member);
                    if (script != null) {
                        ScriptNamesChunk scriptNames = dirFile.getScriptNames();
                        String scriptTypeName = getScriptTypeName(script.scriptType());
                        // Build handler list summary
                        List<String> handlerNames = new ArrayList<>();
                        if (scriptNames != null) {
                            for (ScriptChunk.Handler h : script.handlers()) {
                                String hName = scriptNames.getName(h.nameId());
                                if (!hName.startsWith("<")) {
                                    handlerNames.add(hName);
                                }
                            }
                        }
                        // Format: "Movie Script" or "Parent Script" with handler names
                        if (!handlerNames.isEmpty()) {
                            String handlers = handlerNames.size() <= 3 ?
                                    String.join(", ", handlerNames) :
                                    handlerNames.get(0) + ", " + handlerNames.get(1) + "... +" + (handlerNames.size() - 2);
                            details = String.format("%s [%s]", scriptTypeName, handlers);
                        } else {
                            details = scriptTypeName;
                        }
                    }
                } else if (type == MemberType.SOUND) {
                    // Try to get sound info from associated chunk
                    try {
                        SoundChunk soundChunk = findSoundForMember(dirFile, member);
                        if (soundChunk != null) {
                            String codec = soundChunk.isMp3() ? "MP3" : "PCM";
                            double duration = soundChunk.durationSeconds();
                            details = String.format("%s, %dHz, %.1fs",
                                    codec, soundChunk.sampleRate(), duration);
                        } else {
                            details = "sound data";
                        }
                    } catch (Exception ignored) {
                        details = "sound data";
                    }
                } else if (type == MemberType.PALETTE) {
                    // Try to get palette info
                    PaletteChunk paletteChunk = findPaletteForMember(dirFile, member);
                    if (paletteChunk != null) {
                        details = paletteChunk.colors().length + " colors";
                    } else {
                        details = "palette";
                    }
                } else if (type == MemberType.TEXT || type == MemberType.BUTTON) {
                    // Try to get text preview
                    TextChunk textChunk = findTextForMember(dirFile, member);
                    if (textChunk != null) {
                        String text = textChunk.text()
                                .replace("\r\n", " ")
                                .replace("\r", " ")
                                .replace("\n", " ")
                                .trim();
                        if (text.length() > 50) {
                            text = text.substring(0, 47) + "...";
                        }
                        details = "\"" + text + "\"";
                    }
                }

                members.add(new CastMemberInfo(
                        member.id(), name, member, type, details
                ));
            }

            if (!members.isEmpty()) {
                loadedFiles.put(file.toString(), dirFile);
                return new FileNode(file.toString(), file.getFileName().toString(), members);
            }
        } catch (Exception ignored) {
            // Silently ignore files that fail to parse
        }
        return null;
    }

    private ScriptChunk findScriptForMember(DirectorFile dirFile, CastMemberChunk member) {
        // Scripts are linked via ScriptContextChunk
        // The cast member's scriptId points to an index in the context entries (1-based)
        int scriptId = member.scriptId();
        ScriptContextChunk context = dirFile.getScriptContext();

        if (context != null && scriptId > 0) {
            List<ScriptContextChunk.ScriptEntry> entries = context.entries();
            if (scriptId <= entries.size()) {
                int chunkId = entries.get(scriptId - 1).id();
                // Find the script chunk with this ID
                for (ScriptChunk script : dirFile.getScripts()) {
                    if (script.id() == chunkId) {
                        return script;
                    }
                }
            }
        }

        // Fallback: try to find by member ID directly (for some file formats)
        for (ScriptChunk script : dirFile.getScripts()) {
            if (script.id() == member.id()) {
                return script;
            }
        }

        // Also try via KeyTable
        var keyTable = dirFile.getKeyTable();
        if (keyTable != null) {
            for (var entry : keyTable.getEntriesForOwner(member.id())) {
                String fourcc = entry.fourccString().trim();
                if (fourcc.equals("Lscr") || fourcc.equals("rcsL")) {
                    for (ScriptChunk script : dirFile.getScripts()) {
                        if (script.id() == entry.sectionId()) {
                            return script;
                        }
                    }
                }
            }
        }

        return null;
    }

    private String getScriptTypeName(ScriptChunk.ScriptType scriptType) {
        return switch (scriptType) {
            case MOVIE_SCRIPT -> "Movie Script";
            case BEHAVIOR -> "Behavior";
            case PARENT -> "Parent Script";
            default -> "Script";
        };
    }

    private SoundChunk findSoundForMember(DirectorFile dirFile, CastMemberChunk member) {
        // Sound data is linked via KeyTableChunk (same approach as SdkFeatureTest)
        var keyTable = dirFile.getKeyTable();
        if (keyTable == null) {
            return null;
        }

        for (var entry : keyTable.getEntriesForOwner(member.id())) {
            Chunk chunk = dirFile.getChunk(entry.sectionId());
            if (chunk instanceof SoundChunk sc) {
                return sc;
            }
        }

        return null;
    }

    private void clearData() {
        rootNode.removeAllChildren();
        treeModel.reload();
        imageCache.clear();
        loadedFiles.clear();
        allFileNodes.clear();
        previewLabel.setIcon(null);
        previewLabel.setText("Select a cast member to view details");
        detailsTextArea.setText("");
        searchField.setText("");
        typeFilterCombo.setSelectedIndex(0);
        showImagePreview();
        extractButton.setEnabled(false);
        extractAllButton.setEnabled(false);
    }

    private void showImagePreview() {
        CardLayout cl = (CardLayout) previewContainer.getLayout();
        cl.show(previewContainer, "image");
    }

    private void showTextDetails() {
        CardLayout cl = (CardLayout) previewContainer.getLayout();
        cl.show(previewContainer, "text");
    }

    private void populateTree(List<FileNode> fileNodes) {
        this.allFileNodes = new ArrayList<>(fileNodes);
        applyFilter();
    }

    private void applyFilter() {
        String searchText = searchField.getText().toLowerCase().trim();
        String typeFilter = (String) typeFilterCombo.getSelectedItem();
        boolean filterAll = "All Types".equals(typeFilter);

        rootNode.removeAllChildren();
        int totalShown = 0;

        for (FileNode fileNode : allFileNodes) {
            List<CastMemberInfo> filteredMembers = new ArrayList<>();

            for (CastMemberInfo memberInfo : fileNode.members) {
                // Check type filter
                if (!filterAll && !memberInfo.memberType.getName().equals(typeFilter)) {
                    continue;
                }

                // Check search text
                if (!searchText.isEmpty()) {
                    String name = memberInfo.name.toLowerCase();
                    String details = memberInfo.details.toLowerCase();
                    if (!name.contains(searchText) && !details.contains(searchText)) {
                        continue;
                    }
                }

                filteredMembers.add(memberInfo);
            }

            if (!filteredMembers.isEmpty()) {
                DefaultMutableTreeNode fileTreeNode = new DefaultMutableTreeNode(
                        new FileNode(fileNode.filePath, fileNode.fileName, filteredMembers)
                );

                for (CastMemberInfo memberInfo : filteredMembers) {
                    DefaultMutableTreeNode memberNode = new DefaultMutableTreeNode(
                            new MemberNodeData(fileNode.filePath, memberInfo)
                    );
                    fileTreeNode.add(memberNode);
                }

                rootNode.add(fileTreeNode);
                totalShown += filteredMembers.size();
            }
        }

        treeModel.reload();

        // Update status
        if (!searchText.isEmpty() || !filterAll) {
            int totalMembers = allFileNodes.stream().mapToInt(f -> f.members.size()).sum();
            statusLabel.setText("Showing " + totalShown + " of " + totalMembers + " members");
        }
    }

    private void onTreeSelection(TreeSelectionEvent e) {
        TreePath path = e.getPath();
        if (path == null) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObject = node.getUserObject();

        // Stop any playing sound when selection changes
        stopSound();

        // Hide audio controls by default
        audioPanel.setVisible(false);
        currentSoundMember = null;

        if (userObject instanceof MemberNodeData memberData) {
            MemberType type = memberData.memberInfo.memberType;

            // Enable extract only for bitmaps
            extractButton.setEnabled(type == MemberType.BITMAP && !outputDirField.getText().isEmpty());

            if (type == MemberType.BITMAP) {
                loadAndDisplayBitmap(memberData);
            } else if (type == MemberType.SCRIPT) {
                displayScriptDetails(memberData);
            } else if (type == MemberType.PALETTE) {
                displayPalettePreview(memberData);
            } else if (type == MemberType.SOUND) {
                displaySoundDetails(memberData);
            } else if (type == MemberType.TEXT || type == MemberType.BUTTON) {
                displayTextDetails(memberData);
            } else {
                displayMemberDetails(memberData);
            }
        } else {
            extractButton.setEnabled(false);
            previewLabel.setIcon(null);
            previewLabel.setText("Select a cast member to view details");
            showImagePreview();
        }
    }

    private void loadAndDisplayBitmap(MemberNodeData memberData) {
        showImagePreview();
        BitmapKey key = new BitmapKey(memberData.filePath, memberData.memberInfo.memberNum);

        // Check cache first
        SoftReference<BufferedImage> ref = imageCache.get(key);
        if (ref != null) {
            BufferedImage cached = ref.get();
            if (cached != null) {
                displayBitmapImage(cached, memberData.memberInfo);
                return;
            }
        }

        // Load in background
        previewLabel.setIcon(null);
        previewLabel.setText("Loading...");

        SwingWorker<BufferedImage, Void> worker = new SwingWorker<>() {
            @Override
            protected BufferedImage doInBackground() {
                DirectorFile dirFile = loadedFiles.get(memberData.filePath);
                if (dirFile == null) return null;

                return dirFile.decodeBitmap(memberData.memberInfo.member)
                        .map(Bitmap::toBufferedImage)
                        .orElse(null);
            }

            @Override
            protected void done() {
                try {
                    BufferedImage image = get();
                    if (image != null) {
                        imageCache.put(key, new SoftReference<>(image));
                        displayBitmapImage(image, memberData.memberInfo);
                    } else {
                        previewLabel.setText("Failed to decode bitmap");
                    }
                } catch (Exception ex) {
                    previewLabel.setText("Error: " + ex.getMessage());
                }
            }
        };

        worker.execute();
    }

    private void displayBitmapImage(BufferedImage image, CastMemberInfo info) {
        ImageIcon icon = new ImageIcon(image);
        previewLabel.setIcon(icon);
        previewLabel.setText(null);

        // Get palette info if available
        String paletteDesc = "N/A";
        try {
            BitmapInfo bmpInfo = BitmapInfo.parse(info.member.specificData());
            paletteDesc = getPaletteDescription(bmpInfo.paletteId());
            statusLabel.setText(String.format("%s - %dx%d, %d-bit, Palette: %s",
                    info.name, image.getWidth(), image.getHeight(), bmpInfo.bitDepth(), paletteDesc));
        } catch (Exception e) {
            statusLabel.setText(String.format("%s - %dx%d", info.name, image.getWidth(), image.getHeight()));
        }
    }

    private void displayPalettePreview(MemberNodeData memberData) {
        showImagePreview();
        DirectorFile dirFile = loadedFiles.get(memberData.filePath);
        if (dirFile == null) {
            previewLabel.setIcon(null);
            previewLabel.setText("Error: Could not load file");
            return;
        }

        // Find the palette chunk for this member
        PaletteChunk paletteChunk = findPaletteForMember(dirFile, memberData.memberInfo.member);
        if (paletteChunk != null) {
            // Create a swatch image from the palette colors
            int[] colors = paletteChunk.colors();
            Bitmap swatch = Bitmap.createPaletteSwatch(colors, 16, 16);
            BufferedImage image = swatch.toBufferedImage();

            ImageIcon icon = new ImageIcon(image);
            previewLabel.setIcon(icon);
            previewLabel.setText(null);
            statusLabel.setText(String.format("Palette: %s - %d colors", memberData.memberInfo.name, colors.length));
        } else {
            previewLabel.setIcon(null);
            previewLabel.setText("Palette data not found");
            statusLabel.setText("Palette: " + memberData.memberInfo.name);
        }
    }

    private PaletteChunk findPaletteForMember(DirectorFile dirFile, CastMemberChunk member) {
        // Try to find via KeyTable
        var keyTable = dirFile.getKeyTable();
        if (keyTable != null) {
            for (var entry : keyTable.getEntriesForOwner(member.id())) {
                String fourcc = entry.fourccString().trim();
                if (fourcc.equals("CLUT") || fourcc.equals("TULC")) {
                    var chunk = dirFile.getChunk(entry.sectionId(), PaletteChunk.class);
                    if (chunk.isPresent()) {
                        return chunk.get();
                    }
                }
            }
        }

        // Also try to match by palette ID in the palettes list
        for (PaletteChunk palette : dirFile.getPalettes()) {
            if (palette.id() == member.id()) {
                return palette;
            }
        }

        return null;
    }

    private void displaySoundDetails(MemberNodeData memberData) {
        showTextDetails();
        currentSoundMember = memberData;

        DirectorFile dirFile = loadedFiles.get(memberData.filePath);
        if (dirFile == null) {
            detailsTextArea.setText("Error: Could not load file");
            return;
        }

        SoundChunk soundChunk = findSoundForMember(dirFile, memberData.memberInfo.member);

        StringBuilder sb = new StringBuilder();
        sb.append("=== SOUND: ").append(memberData.memberInfo.name).append(" ===\n\n");
        sb.append("Member ID: ").append(memberData.memberInfo.memberNum).append("\n\n");

        if (soundChunk != null) {
            sb.append("--- Audio Properties ---\n");
            sb.append("Codec: ").append(soundChunk.isMp3() ? "MP3" : "PCM (16-bit)").append("\n");
            sb.append("Sample Rate: ").append(soundChunk.sampleRate()).append(" Hz\n");
            sb.append("Bits Per Sample: ").append(soundChunk.bitsPerSample()).append("\n");
            sb.append("Channels: ").append(soundChunk.channelCount() == 1 ? "Mono" : "Stereo").append("\n");
            sb.append("Duration: ").append(String.format("%.2f", soundChunk.durationSeconds())).append(" seconds\n");
            sb.append("Audio Data Size: ").append(soundChunk.audioData().length).append(" bytes\n");

            // Show play controls - enabled for all sounds now (mp3spi supports MP3)
            audioPanel.setVisible(true);
            playButton.setEnabled(true);
            playButton.setText("Play");
        } else {
            sb.append("[Sound data not found]\n");
            audioPanel.setVisible(false);
        }

        detailsTextArea.setText(sb.toString());
        detailsTextArea.setCaretPosition(0);
        statusLabel.setText("Sound: " + memberData.memberInfo.name);
    }

    private void displayTextDetails(MemberNodeData memberData) {
        showTextDetails();

        DirectorFile dirFile = loadedFiles.get(memberData.filePath);
        if (dirFile == null) {
            detailsTextArea.setText("Error: Could not load file");
            return;
        }

        StringBuilder sb = new StringBuilder();
        String typeName = memberData.memberInfo.memberType == MemberType.BUTTON ? "BUTTON" : "TEXT";
        sb.append("=== ").append(typeName).append(": ").append(memberData.memberInfo.name).append(" ===\n\n");
        sb.append("Member ID: ").append(memberData.memberInfo.memberNum).append("\n\n");

        // Find the text chunk for this member
        TextChunk textChunk = findTextForMember(dirFile, memberData.memberInfo.member);

        if (textChunk != null) {
            String text = textChunk.text();
            // Normalize line endings: \r\n -> \n, \r -> \n
            text = text.replace("\r\n", "\n").replace("\r", "\n");

            sb.append("--- Text Content ---\n");
            sb.append(text);
            sb.append("\n\n");

            // Show formatting info if present
            if (!textChunk.runs().isEmpty()) {
                sb.append("--- Formatting Runs ---\n");
                for (TextChunk.TextRun run : textChunk.runs()) {
                    sb.append(String.format("  Offset %d: Font #%d, Size %d, Style 0x%02X\n",
                            run.startOffset(), run.fontId(), run.fontSize(), run.fontStyle()));
                }
            }
        } else {
            sb.append("[Text data not found]\n");
        }

        detailsTextArea.setText(sb.toString());
        detailsTextArea.setCaretPosition(0);
        statusLabel.setText(typeName + ": " + memberData.memberInfo.name);
    }

    private TextChunk findTextForMember(DirectorFile dirFile, CastMemberChunk member) {
        // Text data is linked via KeyTableChunk
        var keyTable = dirFile.getKeyTable();
        if (keyTable != null) {
            for (var entry : keyTable.getEntriesForOwner(member.id())) {
                String fourcc = entry.fourccString().trim();
                if (fourcc.equals("STXT") || fourcc.equals("TXTS")) {
                    var chunk = dirFile.getChunk(entry.sectionId(), TextChunk.class);
                    if (chunk.isPresent()) {
                        return chunk.get();
                    }
                }
            }

            // Also check for any TextChunk linked to this member
            for (var entry : keyTable.getEntriesForOwner(member.id())) {
                var chunk = dirFile.getChunk(entry.sectionId());
                if (chunk instanceof TextChunk tc) {
                    return tc;
                }
            }
        }

        return null;
    }

    private void displayScriptDetails(MemberNodeData memberData) {
        showTextDetails();
        DirectorFile dirFile = loadedFiles.get(memberData.filePath);
        if (dirFile == null) {
            detailsTextArea.setText("Error: Could not load file");
            return;
        }

        ScriptChunk script = findScriptForMember(dirFile, memberData.memberInfo.member);
        ScriptNamesChunk names = dirFile.getScriptNames();

        StringBuilder sb = new StringBuilder();
        sb.append("=== SCRIPT: ").append(memberData.memberInfo.name).append(" ===\n\n");
        sb.append("Member ID: ").append(memberData.memberInfo.memberNum).append("\n");

        if (script == null) {
            sb.append("\n[No bytecode found for this script member]\n");
        } else {
            sb.append("Script Type: ").append(getScriptTypeName(script.scriptType())).append("\n");
            sb.append("Behavior Flags: 0x").append(Integer.toHexString(script.behaviorFlags())).append("\n\n");

            // Properties
            if (!script.properties().isEmpty()) {
                sb.append("--- PROPERTIES ---\n");
                for (ScriptChunk.PropertyEntry prop : script.properties()) {
                    String propName = names != null ? names.getName(prop.nameId()) : "#" + prop.nameId();
                    sb.append("  property ").append(propName).append("\n");
                }
                sb.append("\n");
            }

            // Globals
            if (!script.globals().isEmpty()) {
                sb.append("--- GLOBALS ---\n");
                for (ScriptChunk.GlobalEntry global : script.globals()) {
                    String globalName = names != null ? names.getName(global.nameId()) : "#" + global.nameId();
                    sb.append("  global ").append(globalName).append("\n");
                }
                sb.append("\n");
            }

            // Handlers
            sb.append("--- HANDLERS (").append(script.handlers().size()).append(") ---\n\n");
            for (ScriptChunk.Handler handler : script.handlers()) {
                String handlerName = names != null ? names.getName(handler.nameId()) : "#" + handler.nameId();

                // Build argument list
                List<String> argNames = new ArrayList<>();
                for (int argId : handler.argNameIds()) {
                    argNames.add(names != null ? names.getName(argId) : "#" + argId);
                }
                String argsStr = String.join(", ", argNames);

                sb.append("on ").append(handlerName);
                if (!argsStr.isEmpty()) {
                    sb.append(" ").append(argsStr);
                }
                sb.append("\n");

                // Local variables
                if (!handler.localNameIds().isEmpty()) {
                    List<String> localNames = new ArrayList<>();
                    for (int localId : handler.localNameIds()) {
                        localNames.add(names != null ? names.getName(localId) : "#" + localId);
                    }
                    sb.append("  -- locals: ").append(String.join(", ", localNames)).append("\n");
                }

                // Bytecode instructions
                sb.append("  -- bytecode (").append(handler.bytecodeLength()).append(" bytes, ")
                        .append(handler.instructions().size()).append(" instructions):\n");

                for (ScriptChunk.Handler.Instruction instr : handler.instructions()) {
                    sb.append("    ").append(formatInstruction(instr, script, names)).append("\n");
                }

                sb.append("end\n\n");
            }

            // Literals
            if (!script.literals().isEmpty()) {
                sb.append("--- LITERALS (").append(script.literals().size()).append(") ---\n");
                int idx = 0;
                for (ScriptChunk.LiteralEntry lit : script.literals()) {
                    String typeStr = switch (lit.type()) {
                        case 1 -> "string";
                        case 4 -> "int";
                        case 9 -> "float";
                        default -> "type" + lit.type();
                    };
                    String valueStr = lit.value() instanceof String ?
                            "\"" + lit.value() + "\"" : String.valueOf(lit.value());
                    sb.append("  [").append(idx++).append("] ").append(typeStr).append(": ").append(valueStr).append("\n");
                }
            }
        }

        detailsTextArea.setText(sb.toString());
        detailsTextArea.setCaretPosition(0);
        statusLabel.setText("Script: " + memberData.memberInfo.name);
    }

    private String formatInstruction(ScriptChunk.Handler.Instruction instr, ScriptChunk script, ScriptNamesChunk names) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%04d] %-16s", instr.offset(), instr.opcode().getMnemonic()));

        if (instr.rawOpcode() >= 0x40) {
            sb.append(" ");

            // Try to provide meaningful argument interpretation
            String argDesc = formatArgument(instr, script, names);
            sb.append(argDesc);
        }

        return sb.toString();
    }

    private String formatArgument(ScriptChunk.Handler.Instruction instr, ScriptChunk script, ScriptNamesChunk names) {
        int arg = instr.argument();
        var opcode = instr.opcode();
        String opName = opcode.name();

        // PUSH_CONS - literal constant (string, int, float)
        if (opcode == Opcode.PUSH_CONS) {
            if (arg >= 0 && arg < script.literals().size()) {
                var lit = script.literals().get(arg);
                Object val = lit.value();
                String typeStr = switch (lit.type()) {
                    case 1 -> "str";
                    case 4 -> "int";
                    case 9 -> "float";
                    default -> "lit";
                };
                if (val instanceof String s) {
                    // Truncate long strings
                    String display = s.length() > 40 ? s.substring(0, 37) + "..." : s;
                    return arg + " <" + typeStr + "> \"" + display + "\"";
                } else {
                    return arg + " <" + typeStr + "> " + val;
                }
            }
        }

        // PUSH_SYMB - symbol name
        if (opcode == Opcode.PUSH_SYMB) {
            if (names != null && arg >= 0 && arg < names.names().size()) {
                return arg + " #" + names.getName(arg);
            }
        }

        // GET/SET variables - resolve names
        if (opName.startsWith("GET_") || opName.startsWith("SET_")) {
            if (opcode == Opcode.GET_GLOBAL ||
                opcode == Opcode.SET_GLOBAL ||
                opcode == Opcode.GET_GLOBAL2 ||
                opcode == Opcode.SET_GLOBAL2 ||
                opcode == Opcode.GET_PROP ||
                opcode == Opcode.SET_PROP ||
                opcode == Opcode.GET_OBJ_PROP ||
                opcode == Opcode.SET_OBJ_PROP ||
                opcode == Opcode.GET_MOVIE_PROP ||
                opcode == Opcode.SET_MOVIE_PROP ||
                opcode == Opcode.GET_TOP_LEVEL_PROP ||
                opcode == Opcode.GET_CHAINED_PROP) {
                if (names != null && arg >= 0 && arg < names.names().size()) {
                    return arg + " (" + names.getName(arg) + ")";
                }
            }
        }

        // Call opcodes - show function/handler name
        if (opcode == Opcode.LOCAL_CALL ||
            opcode == Opcode.EXT_CALL ||
            opcode == Opcode.OBJ_CALL ||
            opcode == Opcode.OBJ_CALL_V4 ||
            opcode == Opcode.TELL_CALL) {
            if (names != null && arg >= 0 && arg < names.names().size()) {
                return arg + " [" + names.getName(arg) + "]";
            }
        }

        // PUT/GET - resolve name
        if (opcode == Opcode.PUT ||
            opcode == Opcode.GET ||
            opcode == Opcode.SET) {
            if (names != null && arg >= 0 && arg < names.names().size()) {
                return arg + " (" + names.getName(arg) + ")";
            }
        }

        // THE_BUILTIN - show builtin name
        if (opcode == Opcode.THE_BUILTIN) {
            if (names != null && arg >= 0 && arg < names.names().size()) {
                return arg + " the " + names.getName(arg);
            }
        }

        // NEW_OBJ - parent script name
        if (opcode == Opcode.NEW_OBJ) {
            if (names != null && arg >= 0 && arg < names.names().size()) {
                return arg + " new(" + names.getName(arg) + ")";
            }
        }

        // PUSH_VAR_REF - variable reference
        if (opcode == Opcode.PUSH_VAR_REF) {
            if (names != null && arg >= 0 && arg < names.names().size()) {
                return arg + " @" + names.getName(arg);
            }
        }

        // Jump offsets - show target
        if (opcode.isJump()) {
            return arg + " -> offset " + arg;
        }

        return String.valueOf(arg);
    }

    private void displayMemberDetails(MemberNodeData memberData) {
        showTextDetails();

        StringBuilder sb = new StringBuilder();
        CastMemberInfo info = memberData.memberInfo;
        sb.append("=== ").append(info.memberType.getName().toUpperCase()).append(": ").append(info.name).append(" ===\n\n");
        sb.append("Member ID: ").append(info.memberNum).append("\n");
        sb.append("Type: ").append(info.memberType.getName()).append(" (").append(info.memberType.getCode()).append(")\n");

        if (!info.details.isEmpty()) {
            sb.append("Details: ").append(info.details).append("\n");
        }

        // Type-specific info
        CastMemberChunk member = info.member;
        if (member.specificData().length > 0) {
            sb.append("\nSpecific Data: ").append(member.specificData().length).append(" bytes\n");

            switch (info.memberType) {
                case SHAPE -> {
                    try {
                        ShapeInfo shapeInfo = ShapeInfo.parse(member.specificData());
                        sb.append("\n--- Shape Info ---\n");
                        sb.append("Shape Type: ").append(shapeInfo.shapeType()).append("\n");
                        sb.append("Dimensions: ").append(shapeInfo.width()).append("x").append(shapeInfo.height()).append("\n");
                        sb.append("Reg Point: (").append(shapeInfo.regX()).append(", ").append(shapeInfo.regY()).append(")\n");
                        sb.append("Color: ").append(shapeInfo.color()).append("\n");
                    } catch (Exception e) {
                        sb.append("Error parsing shape info: ").append(e.getMessage()).append("\n");
                    }
                }
                case FILM_LOOP -> {
                    try {
                        FilmLoopInfo loopInfo = FilmLoopInfo.parse(member.specificData());
                        sb.append("\n--- Film Loop Info ---\n");
                        sb.append("Dimensions: ").append(loopInfo.width()).append("x").append(loopInfo.height()).append("\n");
                        sb.append("Reg Point: (").append(loopInfo.regX()).append(", ").append(loopInfo.regY()).append(")\n");
                    } catch (Exception e) {
                        sb.append("Error parsing film loop info: ").append(e.getMessage()).append("\n");
                    }
                }
                case RTE -> {
                    sb.append("\n[Rich Text member - content not decoded]\n");
                }
                case SOUND -> {
                    DirectorFile dirFile = loadedFiles.get(memberData.filePath);
                    if (dirFile != null) {
                        SoundChunk soundChunk = findSoundForMember(dirFile, member);
                        if (soundChunk != null) {
                            sb.append("\n--- Sound Info ---\n");
                            sb.append("Codec: ").append(soundChunk.isMp3() ? "MP3" : "PCM").append("\n");
                            sb.append("Sample Rate: ").append(soundChunk.sampleRate()).append(" Hz\n");
                            sb.append("Bits Per Sample: ").append(soundChunk.bitsPerSample()).append("\n");
                            sb.append("Channels: ").append(soundChunk.channelCount()).append("\n");
                            sb.append("Duration: ").append(String.format("%.2f", soundChunk.durationSeconds())).append(" seconds\n");
                            sb.append("Audio Data Size: ").append(soundChunk.audioData().length).append(" bytes\n");
                        } else {
                            sb.append("\n[Sound data not found]\n");
                        }
                    }
                }
                case PALETTE -> {
                    DirectorFile dirFile = loadedFiles.get(memberData.filePath);
                    if (dirFile != null) {
                        PaletteChunk paletteChunk = findPaletteForMember(dirFile, member);
                        if (paletteChunk != null) {
                            int[] colors = paletteChunk.colors();
                            sb.append("\n--- Palette Info ---\n");
                            sb.append("Color Count: ").append(colors.length).append("\n");
                            sb.append("\n--- Colors ---\n");
                            for (int i = 0; i < colors.length; i++) {
                                int c = colors[i];
                                int r = (c >> 16) & 0xFF;
                                int g = (c >> 8) & 0xFF;
                                int b = c & 0xFF;
                                sb.append(String.format("[%3d] #%02X%02X%02X (R:%3d G:%3d B:%3d)\n", i, r, g, b, r, g, b));
                            }
                        } else {
                            sb.append("\n[Palette data not found]\n");
                        }
                    }
                }
                case DIGITAL_VIDEO, FLASH -> {
                    sb.append("\n[Video/Flash member]\n");
                }
                default -> {
                    // Show hex dump of first 256 bytes
                    sb.append("\n--- Hex Dump (first 256 bytes) ---\n");
                    byte[] data = member.specificData();
                    int len = Math.min(data.length, 256);
                    for (int i = 0; i < len; i += 16) {
                        sb.append(String.format("%04X: ", i));
                        for (int j = 0; j < 16 && i + j < len; j++) {
                            sb.append(String.format("%02X ", data[i + j] & 0xFF));
                        }
                        sb.append("\n");
                    }
                }
            }
        }

        detailsTextArea.setText(sb.toString());
        detailsTextArea.setCaretPosition(0);
        statusLabel.setText(info.memberType.getName() + ": " + info.name);
    }

    private String getPaletteDescription(int paletteId) {
        return switch (paletteId) {
            case -1 -> "System Mac";
            case -2 -> "Rainbow";
            case -3 -> "Grayscale";
            case -4 -> "Pastels";
            case -5 -> "Vivid";
            case -6 -> "NTSC";
            case -7 -> "Metallic";
            case -101 -> "System Windows";
            case -102 -> "System Windows (D4)";
            default -> paletteId >= 0 ? "Cast Member #" + (paletteId + 1) : "Unknown (" + paletteId + ")";
        };
    }

    private void extractSelected(ActionEvent e) {
        String outputDir = outputDirField.getText();
        if (outputDir.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select an output directory first.",
                    "No Output Directory", JOptionPane.WARNING_MESSAGE);
            return;
        }

        TreePath path = fileTree.getSelectionPath();
        if (path == null) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObject = node.getUserObject();

        List<ExtractionTask> tasks = new ArrayList<>();

        if (userObject instanceof MemberNodeData memberData) {
            if (memberData.memberInfo.memberType == MemberType.BITMAP) {
                tasks.add(new ExtractionTask(memberData.filePath, memberData.memberInfo));
            }
        } else if (userObject instanceof FileNode fileNode) {
            for (CastMemberInfo member : fileNode.members) {
                if (member.memberType == MemberType.BITMAP) {
                    tasks.add(new ExtractionTask(fileNode.filePath, member));
                }
            }
        }

        if (!tasks.isEmpty()) {
            extractBitmaps(tasks, Path.of(outputDir));
        }
    }

    private void extractAll(ActionEvent e) {
        String outputDir = outputDirField.getText();
        if (outputDir.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select an output directory first.",
                    "No Output Directory", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<ExtractionTask> tasks = new ArrayList<>();

        for (int i = 0; i < rootNode.getChildCount(); i++) {
            DefaultMutableTreeNode fileNode = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            FileNode fileData = (FileNode) fileNode.getUserObject();

            for (CastMemberInfo member : fileData.members) {
                if (member.memberType == MemberType.BITMAP) {
                    tasks.add(new ExtractionTask(fileData.filePath, member));
                }
            }
        }

        if (!tasks.isEmpty()) {
            extractBitmaps(tasks, Path.of(outputDir));
        }
    }

    private void extractBitmaps(List<ExtractionTask> tasks, Path outputDir) {
        progressBar.setVisible(true);
        progressBar.setMaximum(tasks.size());
        progressBar.setValue(0);
        extractButton.setEnabled(false);
        extractAllButton.setEnabled(false);

        SwingWorker<Integer, Integer> worker = new SwingWorker<>() {
            @Override
            protected Integer doInBackground() {
                int successful = 0;
                int processed = 0;

                for (ExtractionTask task : tasks) {
                    try {
                        DirectorFile dirFile = loadedFiles.get(task.filePath);
                        if (dirFile != null) {
                            var bitmapOpt = dirFile.decodeBitmap(task.memberInfo.member);
                            if (bitmapOpt.isPresent()) {
                                BufferedImage image = bitmapOpt.get().toBufferedImage();

                                // Create subdirectory for each source file
                                String sourceFileName = Path.of(task.filePath).getFileName().toString();
                                String baseName = sourceFileName.contains(".")
                                        ? sourceFileName.substring(0, sourceFileName.lastIndexOf('.'))
                                        : sourceFileName;

                                Path subDir = outputDir.resolve(baseName);
                                Files.createDirectories(subDir);

                                // Sanitize filename
                                String safeName = task.memberInfo.name
                                        .replaceAll("[^a-zA-Z0-9._-]", "_");
                                if (safeName.isEmpty()) {
                                    safeName = "bitmap_" + task.memberInfo.memberNum;
                                }

                                Path outputFile = subDir.resolve(safeName + ".png");

                                // Handle duplicates
                                int counter = 1;
                                while (Files.exists(outputFile)) {
                                    outputFile = subDir.resolve(safeName + "_" + counter + ".png");
                                    counter++;
                                }

                                ImageIO.write(image, "PNG", outputFile.toFile());
                                successful++;
                            }
                        }
                    } catch (Exception ignored) {
                        // Silently ignore extraction failures
                    }

                    processed++;
                    publish(processed);
                }

                return successful;
            }

            @Override
            protected void process(List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int latest = chunks.get(chunks.size() - 1);
                    progressBar.setValue(latest);
                    statusLabel.setText("Extracting... " + latest + "/" + tasks.size());
                }
            }

            @Override
            protected void done() {
                try {
                    int successful = get();
                    statusLabel.setText("Extracted " + successful + " of " + tasks.size() + " bitmaps to " + outputDir);
                } catch (Exception ex) {
                    statusLabel.setText("Error during extraction: " + ex.getMessage());
                }

                progressBar.setVisible(false);
                extractButton.setEnabled(true);
                extractAllButton.setEnabled(true);
            }
        };

        worker.execute();
    }

    @Override
    public void dispose() {
        stopSound();
        if (playbackTimer != null) {
            playbackTimer.stop();
        }
        executor.shutdownNow();
        super.dispose();
    }

    // Data classes

    private record FileNode(String filePath, String fileName, List<CastMemberInfo> members) {
        @Override
        public String toString() {
            return fileName + " (" + members.size() + " members)";
        }
    }

    private record CastMemberInfo(int memberNum, String name, CastMemberChunk member,
                                  MemberType memberType, String details) {
    }

    private record MemberNodeData(String filePath, CastMemberInfo memberInfo) {
        @Override
        public String toString() {
            // For scripts, show the script type from details instead of generic "script"
            if (memberInfo.memberType == MemberType.SCRIPT && !memberInfo.details.isEmpty()) {
                // Details format: "Movie Script [handler1, handler2]" or just "Movie Script"
                return memberInfo.name + " - " + memberInfo.details;
            }

            String base = memberInfo.name + " [" + memberInfo.memberType.getName() + "]";
            if (!memberInfo.details.isEmpty()) {
                return base + " (" + memberInfo.details + ")";
            }
            return base;
        }
    }

    private record BitmapKey(String filePath, int memberNum) {
    }

    private record ExtractionTask(String filePath, CastMemberInfo memberInfo) {
    }

    // Custom tree cell renderer with type-specific icons

    private static class FileTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {

            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

            if (value instanceof DefaultMutableTreeNode node) {
                Object userObject = node.getUserObject();
                if (userObject instanceof FileNode) {
                    setIcon(UIManager.getIcon("FileView.directoryIcon"));
                } else if (userObject instanceof MemberNodeData memberData) {
                    // Color-code by member type
                    MemberType type = memberData.memberInfo.memberType;
                    switch (type) {
                        case BITMAP -> setForeground(new Color(0, 100, 0)); // Dark green
                        case SCRIPT -> setForeground(new Color(0, 0, 180)); // Blue
                        case SOUND -> setForeground(new Color(180, 0, 180)); // Purple
                        case TEXT, BUTTON, RTE -> setForeground(new Color(100, 100, 0)); // Olive
                        case SHAPE -> setForeground(new Color(180, 100, 0)); // Orange
                        case PALETTE -> setForeground(new Color(100, 0, 100)); // Dark purple
                        case FILM_LOOP -> setForeground(new Color(0, 100, 100)); // Teal
                        default -> {} // Default color
                    }
                    setIcon(UIManager.getIcon("FileView.fileIcon"));
                }
            }

            return this;
        }
    }

    public static void main(String[] args) {
        // Set system look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        SwingUtilities.invokeLater(() -> {
            CastExtractorTool tool = new CastExtractorTool();
            tool.setVisible(true);
        });
    }
}
