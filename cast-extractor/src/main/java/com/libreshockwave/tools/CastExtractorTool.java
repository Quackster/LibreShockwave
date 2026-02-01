package com.libreshockwave.tools;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.tools.audio.AudioPlaybackController;
import com.libreshockwave.tools.extraction.AssetExtractor;
import com.libreshockwave.tools.extraction.ExportHandler;
import com.libreshockwave.tools.format.ChannelNames;
import com.libreshockwave.tools.format.PaletteDescriptions;
import com.libreshockwave.tools.model.*;
import com.libreshockwave.tools.preview.*;
import com.libreshockwave.tools.scanning.FileProcessor;
import com.libreshockwave.tools.scanning.MemberResolver;
import com.libreshockwave.tools.score.FrameAppearanceFinder;
import com.libreshockwave.tools.score.ScoreDataBuilder;
import com.libreshockwave.tools.ui.renderer.FileTreeCellRenderer;
import com.libreshockwave.tools.ui.renderer.RowHeaderRenderer;
import com.libreshockwave.tools.ui.renderer.ScoreCellRenderer;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
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

    // UI components
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
    private JTable scoreTable;
    private JScrollPane scoreScrollPane;
    private JPanel scorePanel;
    private JLabel scoreInfoLabel;
    private JButton viewScoreButton;

    // Data and caches
    private final Map<BitmapKey, SoftReference<BufferedImage>> imageCache = new ConcurrentHashMap<>();
    private final Map<String, DirectorFile> loadedFiles = new ConcurrentHashMap<>();
    private List<FileNode> allFileNodes = new ArrayList<>();

    // Service classes
    private final FileProcessor fileProcessor;
    private final ScoreDataBuilder scoreDataBuilder = new ScoreDataBuilder();
    private final FrameAppearanceFinder appearanceFinder = new FrameAppearanceFinder();
    private final ScriptPreview scriptPreview = new ScriptPreview();
    private final SoundPreview soundPreview = new SoundPreview();
    private final TextPreview textPreview = new TextPreview();
    private final PalettePreview palettePreview = new PalettePreview();
    private final GenericPreview genericPreview = new GenericPreview();
    private final AssetExtractor assetExtractor;
    private final ExportHandler exportHandler;
    private final AudioPlaybackController audioController;

    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1)
    );

    public CastExtractorTool() {
        super("Cast Extractor Tool - LibreShockwave");

        // Initialize services
        this.fileProcessor = new FileProcessor(loadedFiles);
        this.assetExtractor = new AssetExtractor(loadedFiles);
        this.exportHandler = new ExportHandler(loadedFiles);
        this.audioController = new AudioPlaybackController(loadedFiles);

        initializeUI();
        setupServiceCallbacks();
        loadSavedPreferences();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
    }

    private void setupServiceCallbacks() {
        exportHandler.setStatusCallback(this::setStatus);
        audioController.setStatusCallback(this::setStatus);
        audioController.setOnPlaybackStopped(() -> {
            stopButton.setEnabled(false);
            playButton.setText("Play");
        });
        audioController.setOnStateChanged(state -> {
            positionSlider.setValue(state.progressPercent());
            timeLabel.setText(state.timeLabel());
            if (state.isPlaying()) {
                playButton.setText("Pause");
                stopButton.setEnabled(true);
                positionSlider.setEnabled(true);
            }
        });
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

        // Add right-click context menu
        JPopupMenu contextMenu = new JPopupMenu();
        JMenuItem exportItem = new JMenuItem("Export...");
        exportItem.addActionListener(e -> exportSelectedMember());
        contextMenu.add(exportItem);
        JMenuItem viewScoreItem = new JMenuItem("View Score");
        viewScoreItem.addActionListener(e -> viewScoreForSelectedFile());
        contextMenu.add(viewScoreItem);

        fileTree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) showContextMenu(e, contextMenu, exportItem, viewScoreItem);
            }
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) showContextMenu(e, contextMenu, exportItem, viewScoreItem);
            }
        });

        JScrollPane scrollPane = new JScrollPane(fileTree);

        // Bottom panel with View Score button
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        viewScoreButton = new JButton("View Score");
        viewScoreButton.setEnabled(false);
        viewScoreButton.addActionListener(e -> viewScoreForSelectedFile());
        bottomPanel.add(viewScoreButton);

        panel.add(filterPanel, BorderLayout.NORTH);
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }

    private void showContextMenu(java.awt.event.MouseEvent e, JPopupMenu contextMenu,
                                  JMenuItem exportItem, JMenuItem viewScoreItem) {
        TreePath path = fileTree.getPathForLocation(e.getX(), e.getY());
        if (path != null) {
            fileTree.setSelectionPath(path);
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            Object userObj = node.getUserObject();
            exportItem.setEnabled(userObj instanceof MemberNodeData);
            viewScoreItem.setEnabled(userObj instanceof FileNode || userObj instanceof MemberNodeData);
            contextMenu.show(fileTree, e.getX(), e.getY());
        }
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

        // Score viewer panel
        scorePanel = new JPanel(new BorderLayout(5, 5));
        scoreInfoLabel = new JLabel("Select a file to view its score");
        scoreInfoLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        scorePanel.add(scoreInfoLabel, BorderLayout.NORTH);

        scoreTable = new JTable();
        scoreTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        scoreTable.setDefaultRenderer(Object.class, new ScoreCellRenderer());
        scoreTable.setRowHeight(24);
        scoreScrollPane = new JScrollPane(scoreTable);
        scoreScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scoreScrollPane.getHorizontalScrollBar().setUnitIncrement(50);
        scorePanel.add(scoreScrollPane, BorderLayout.CENTER);

        previewContainer.add(previewScrollPane, "image");
        previewContainer.add(detailsScrollPane, "text");
        previewContainer.add(scorePanel, "score");

        panel.add(previewContainer, BorderLayout.CENTER);

        // Audio controls panel
        audioPanel = new JPanel(new BorderLayout(5, 5));
        audioPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        playButton = new JButton("Play");
        playButton.setEnabled(false);
        playButton.addActionListener(e -> playCurrentSound());

        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> stopSound());

        buttonsPanel.add(playButton);
        buttonsPanel.add(stopButton);

        positionSlider = new JSlider(0, 100, 0);
        positionSlider.setEnabled(false);
        positionSlider.addChangeListener(e -> {
            if (positionSlider.getValueIsAdjusting()) {
                audioController.seekTo(positionSlider.getValue());
            }
        });

        timeLabel = new JLabel("0.0s / 0.0s");
        timeLabel.setPreferredSize(new Dimension(120, 20));

        audioPanel.add(buttonsPanel, BorderLayout.WEST);
        audioPanel.add(positionSlider, BorderLayout.CENTER);
        audioPanel.add(timeLabel, BorderLayout.EAST);
        audioPanel.setVisible(false);

        panel.add(audioPanel, BorderLayout.SOUTH);

        return panel;
    }

    private void playCurrentSound() {
        if (audioController.isPlaying()) {
            audioController.togglePause();
            playButton.setText("Play");
        } else {
            audioController.play();
        }
    }

    private void stopSound() {
        audioController.stop();
        stopButton.setEnabled(false);
        playButton.setText("Play");
        positionSlider.setValue(0);
        positionSlider.setEnabled(false);
        timeLabel.setText("0.0s / 0.0s");
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

        clearData();

        progressBar.setVisible(true);
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);

        SwingWorker<List<FileNode>, Integer> worker = new SwingWorker<>() {
            @Override
            protected List<FileNode> doInBackground() {
                List<FileNode> fileNodes = Collections.synchronizedList(new ArrayList<>());

                try (Stream<Path> paths = Files.walk(inputPath)) {
                    List<Path> files = paths.filter(Files::isRegularFile).toList();
                    int total = files.size();
                    AtomicInteger processed = new AtomicInteger(0);
                    AtomicInteger errors = new AtomicInteger(0);

                    // Use sequential stream - parallelStream can have issues with jpackage classloader
                    for (Path file : files) {
                        try {
                            FileNode node = fileProcessor.processFile(file);
                            if (node != null) {
                                fileNodes.add(node);
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }

                        int current = processed.incrementAndGet();
                        if (current % 10 == 0 || current == total) {
                            int percent = (int) ((current * 100.0) / total);
                            publish(percent);
                        }
                    }

                    if (errors.get() > 0) {
                        System.err.println("Scan completed with " + errors.get() + " errors");
                    }

                } catch (IOException ex) {
                    SwingUtilities.invokeLater(() ->
                        setStatus("Error scanning directory: " + ex.getMessage()));
                    ex.printStackTrace();
                }

                fileNodes.sort((a, b) -> a.fileName().compareToIgnoreCase(b.fileName()));
                return fileNodes;
            }

            @Override
            protected void process(List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int latest = chunks.get(chunks.size() - 1);
                    progressBar.setValue(latest);
                    setStatus("Scanning... " + latest + "%");
                }
            }

            @Override
            protected void done() {
                progressBar.setVisible(false);
                try {
                    List<FileNode> fileNodes = get();
                    populateTree(fileNodes);

                    int totalMembers = fileNodes.stream()
                            .mapToInt(f -> f.members().size())
                            .sum();

                    Map<MemberType, Long> typeCounts = fileNodes.stream()
                            .flatMap(f -> f.members().stream())
                            .collect(Collectors.groupingBy(CastMemberInfo::memberType, Collectors.counting()));

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

                    setStatus(sb.toString());
                    extractAllButton.setEnabled(!fileNodes.isEmpty() && !outputDirField.getText().isEmpty());

                } catch (Exception ex) {
                    setStatus("Error: " + ex.getMessage());
                }
            }
        };

        setStatus("Scanning...");
        worker.execute();
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

    private void showScoreView() {
        CardLayout cl = (CardLayout) previewContainer.getLayout();
        cl.show(previewContainer, "score");
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

            for (CastMemberInfo memberInfo : fileNode.members()) {
                if (!filterAll && !memberInfo.memberType().getName().equals(typeFilter)) {
                    continue;
                }

                if (!searchText.isEmpty()) {
                    String name = memberInfo.name().toLowerCase();
                    String details = memberInfo.details().toLowerCase();
                    if (!name.contains(searchText) && !details.contains(searchText)) {
                        continue;
                    }
                }

                filteredMembers.add(memberInfo);
            }

            if (!filteredMembers.isEmpty()) {
                DefaultMutableTreeNode fileTreeNode = new DefaultMutableTreeNode(
                        new FileNode(fileNode.filePath(), fileNode.fileName(), filteredMembers)
                );

                for (CastMemberInfo memberInfo : filteredMembers) {
                    DefaultMutableTreeNode memberNode = new DefaultMutableTreeNode(
                            new MemberNodeData(fileNode.filePath(), memberInfo)
                    );
                    fileTreeNode.add(memberNode);
                }

                rootNode.add(fileTreeNode);
                totalShown += filteredMembers.size();
            }
        }

        treeModel.reload();

        if (!searchText.isEmpty() || !filterAll) {
            int totalMembers = allFileNodes.stream().mapToInt(f -> f.members().size()).sum();
            setStatus("Showing " + totalShown + " of " + totalMembers + " members");
        }
    }

    private void onTreeSelection(TreeSelectionEvent e) {
        TreePath path = e.getPath();
        if (path == null) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObject = node.getUserObject();

        stopSound();
        audioPanel.setVisible(false);
        audioController.setCurrentMember(null);

        String filePath = null;
        if (userObject instanceof FileNode fileNode) {
            filePath = fileNode.filePath();
        } else if (userObject instanceof MemberNodeData memberData) {
            filePath = memberData.filePath();
        }
        boolean hasScore = false;
        if (filePath != null) {
            DirectorFile dirFile = loadedFiles.get(filePath);
            hasScore = dirFile != null && dirFile.hasScore();
        }
        viewScoreButton.setEnabled(hasScore);

        if (userObject instanceof MemberNodeData memberData) {
            MemberType type = memberData.memberInfo().memberType();

            boolean canExtract = (type == MemberType.BITMAP || type == MemberType.SOUND)
                    && !outputDirField.getText().isEmpty();
            extractButton.setEnabled(canExtract);

            DirectorFile dirFile = loadedFiles.get(memberData.filePath());
            if (dirFile == null) {
                showTextDetails();
                detailsTextArea.setText("Error: Could not load file");
                return;
            }

            if (type == MemberType.BITMAP) {
                loadAndDisplayBitmap(memberData);
            } else if (type == MemberType.SCRIPT) {
                displayScriptDetails(memberData, dirFile);
            } else if (type == MemberType.PALETTE) {
                displayPalettePreview(memberData, dirFile);
            } else if (type == MemberType.SOUND) {
                displaySoundDetails(memberData, dirFile);
            } else if (type == MemberType.TEXT || type == MemberType.BUTTON) {
                displayTextDetails(memberData, dirFile);
            } else {
                displayGenericDetails(memberData, dirFile);
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
        BitmapKey key = new BitmapKey(memberData.filePath(), memberData.memberInfo().memberNum());

        SoftReference<BufferedImage> ref = imageCache.get(key);
        if (ref != null) {
            BufferedImage cached = ref.get();
            if (cached != null) {
                displayBitmapImage(cached, memberData);
                return;
            }
        }

        previewLabel.setIcon(null);
        previewLabel.setText("Loading...");

        SwingWorker<BufferedImage, Void> worker = new SwingWorker<>() {
            @Override
            protected BufferedImage doInBackground() {
                DirectorFile dirFile = loadedFiles.get(memberData.filePath());
                if (dirFile == null) return null;

                return dirFile.decodeBitmap(memberData.memberInfo().member())
                        .map(Bitmap::toBufferedImage)
                        .orElse(null);
            }

            @Override
            protected void done() {
                try {
                    BufferedImage image = get();
                    if (image != null) {
                        imageCache.put(key, new SoftReference<>(image));
                        displayBitmapImage(image, memberData);
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

    private void displayBitmapImage(BufferedImage image, MemberNodeData memberData) {
        CastMemberInfo info = memberData.memberInfo();
        ImageIcon icon = new ImageIcon(image);
        previewLabel.setIcon(icon);
        previewLabel.setText(null);

        String paletteDesc = "N/A";
        String baseStatus;
        try {
            BitmapInfo bmpInfo = BitmapInfo.parse(info.member().specificData());
            paletteDesc = PaletteDescriptions.get(bmpInfo.paletteId());
            baseStatus = String.format("#%d %s - %dx%d, %d-bit, Palette: %s",
                    info.memberNum(), info.name(), image.getWidth(), image.getHeight(), bmpInfo.bitDepth(), paletteDesc);
        } catch (Exception e) {
            baseStatus = String.format("#%d %s - %dx%d", info.memberNum(), info.name(), image.getWidth(), image.getHeight());
        }

        DirectorFile dirFile = loadedFiles.get(memberData.filePath());
        if (dirFile != null) {
            List<FrameAppearance> appearances = appearanceFinder.find(dirFile, info.memberNum());
            if (!appearances.isEmpty()) {
                baseStatus += " | " + appearanceFinder.format(appearances);
            }
        }

        setStatus(baseStatus);
    }

    private void displayPalettePreview(MemberNodeData memberData, DirectorFile dirFile) {
        showImagePreview();

        PalettePreview.PaletteResult result = palettePreview.generateSwatch(dirFile, memberData.memberInfo());
        if (result != null) {
            ImageIcon icon = new ImageIcon(result.swatchImage());
            previewLabel.setIcon(icon);
            previewLabel.setText(null);
            setStatus(String.format("Palette: #%d %s - %d colors",
                    memberData.memberInfo().memberNum(), memberData.memberInfo().name(), result.colorCount()));
        } else {
            previewLabel.setIcon(null);
            previewLabel.setText("Palette data not found");
            setStatus("Palette: #" + memberData.memberInfo().memberNum() + " " + memberData.memberInfo().name());
        }
    }

    private void displayScriptDetails(MemberNodeData memberData, DirectorFile dirFile) {
        showTextDetails();
        String content = scriptPreview.format(dirFile, memberData.memberInfo());
        detailsTextArea.setText(content);
        detailsTextArea.setCaretPosition(0);
        setStatus("Script: #" + memberData.memberInfo().memberNum() + " " + memberData.memberInfo().name());
    }

    private void displaySoundDetails(MemberNodeData memberData, DirectorFile dirFile) {
        showTextDetails();
        audioController.setCurrentMember(memberData);

        String content = soundPreview.format(dirFile, memberData.memberInfo());
        detailsTextArea.setText(content);
        detailsTextArea.setCaretPosition(0);

        if (soundPreview.isPlayable(dirFile, memberData.memberInfo())) {
            audioPanel.setVisible(true);
            playButton.setEnabled(true);
            playButton.setText("Play");
        } else {
            audioPanel.setVisible(false);
        }

        setStatus("Sound: #" + memberData.memberInfo().memberNum() + " " + memberData.memberInfo().name());
    }

    private void displayTextDetails(MemberNodeData memberData, DirectorFile dirFile) {
        showTextDetails();
        String content = textPreview.format(dirFile, memberData.memberInfo());
        detailsTextArea.setText(content);
        detailsTextArea.setCaretPosition(0);
        String typeName = memberData.memberInfo().memberType() == MemberType.BUTTON ? "Button" : "Text";
        setStatus(typeName + ": #" + memberData.memberInfo().memberNum() + " " + memberData.memberInfo().name());
    }

    private void displayGenericDetails(MemberNodeData memberData, DirectorFile dirFile) {
        showTextDetails();
        String content = genericPreview.format(dirFile, memberData.memberInfo());
        detailsTextArea.setText(content);
        detailsTextArea.setCaretPosition(0);
        setStatus(memberData.memberInfo().memberType().getName() + ": #"
                + memberData.memberInfo().memberNum() + " " + memberData.memberInfo().name());
    }

    private void viewScoreForSelectedFile() {
        TreePath path = fileTree.getSelectionPath();
        if (path == null) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObject = node.getUserObject();

        String filePath = null;
        if (userObject instanceof FileNode fileNode) {
            filePath = fileNode.filePath();
        } else if (userObject instanceof MemberNodeData memberData) {
            filePath = memberData.filePath();
        }

        if (filePath == null) return;

        DirectorFile dirFile = loadedFiles.get(filePath);
        if (dirFile == null || !dirFile.hasScore()) {
            setStatus("No score data available for this file");
            return;
        }

        displayScoreForFile(dirFile, Path.of(filePath).getFileName().toString());
    }

    private void displayScoreForFile(DirectorFile dirFile, String fileName) {
        showScoreView();

        ScoreChunk scoreChunk = dirFile.getScoreChunk();
        if (scoreChunk == null) {
            scoreInfoLabel.setText("No score data");
            scoreTable.setModel(new javax.swing.table.DefaultTableModel());
            return;
        }

        int frameCount = scoreChunk.getFrameCount();
        int channelCount = scoreChunk.getChannelCount();

        ScoreCellData[][] data = scoreDataBuilder.buildScoreData(dirFile);
        String[] columnNames = scoreDataBuilder.buildColumnNames(dirFile);

        javax.swing.table.DefaultTableModel model = new javax.swing.table.DefaultTableModel(data, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        scoreTable.setModel(model);

        for (int i = 0; i < scoreTable.getColumnCount(); i++) {
            scoreTable.getColumnModel().getColumn(i).setMinWidth(60);
            scoreTable.getColumnModel().getColumn(i).setPreferredWidth(80);
        }

        JList<String> rowHeader = new JList<>(ChannelNames.createLabels(channelCount));
        rowHeader.setFixedCellWidth(80);
        rowHeader.setFixedCellHeight(scoreTable.getRowHeight());
        rowHeader.setCellRenderer(new RowHeaderRenderer(scoreTable));
        scoreScrollPane.setRowHeaderView(rowHeader);

        int spriteCount = 0;
        for (int ch = 0; ch < channelCount; ch++) {
            for (int fr = 0; fr < frameCount; fr++) {
                if (data[ch][fr] != null) spriteCount++;
            }
        }

        scoreInfoLabel.setText(String.format("Score: %s - %d frames, %d channels, %d sprite entries",
                fileName, frameCount, channelCount, spriteCount));
        setStatus("Viewing score for: " + fileName);
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
            MemberType type = memberData.memberInfo().memberType();
            if (type == MemberType.BITMAP || type == MemberType.SOUND) {
                tasks.add(new ExtractionTask(memberData.filePath(), memberData.memberInfo()));
            }
        } else if (userObject instanceof FileNode fileNode) {
            for (CastMemberInfo member : fileNode.members()) {
                MemberType type = member.memberType();
                if (type == MemberType.BITMAP || type == MemberType.SOUND) {
                    tasks.add(new ExtractionTask(fileNode.filePath(), member));
                }
            }
        }

        if (!tasks.isEmpty()) {
            extractAssets(tasks, Path.of(outputDir));
        } else {
            setStatus("No extractable assets selected (bitmaps or sounds)");
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

            for (CastMemberInfo member : fileData.members()) {
                MemberType type = member.memberType();
                if (type == MemberType.BITMAP || type == MemberType.SOUND) {
                    tasks.add(new ExtractionTask(fileData.filePath(), member));
                }
            }
        }

        if (!tasks.isEmpty()) {
            extractAssets(tasks, Path.of(outputDir));
        } else {
            setStatus("No extractable assets found (bitmaps or sounds)");
        }
    }

    private void extractAssets(List<ExtractionTask> tasks, Path outputDir) {
        progressBar.setVisible(true);
        progressBar.setMaximum(tasks.size());
        progressBar.setValue(0);
        extractButton.setEnabled(false);
        extractAllButton.setEnabled(false);

        SwingWorker<int[], Integer> worker = new SwingWorker<>() {
            @Override
            protected int[] doInBackground() {
                int bitmapsExtracted = 0;
                int soundsExtracted = 0;
                int processed = 0;

                for (ExtractionTask task : tasks) {
                    boolean success = assetExtractor.extract(task, outputDir);
                    if (success) {
                        if (task.memberInfo().memberType() == MemberType.BITMAP) {
                            bitmapsExtracted++;
                        } else if (task.memberInfo().memberType() == MemberType.SOUND) {
                            soundsExtracted++;
                        }
                    }
                    processed++;
                    publish(processed);
                }

                return new int[]{bitmapsExtracted, soundsExtracted};
            }

            @Override
            protected void process(List<Integer> chunks) {
                if (!chunks.isEmpty()) {
                    int latest = chunks.get(chunks.size() - 1);
                    progressBar.setValue(latest);
                    setStatus("Extracting... " + latest + "/" + tasks.size());
                }
            }

            @Override
            protected void done() {
                try {
                    int[] results = get();
                    int bitmaps = results[0];
                    int sounds = results[1];

                    StringBuilder msg = new StringBuilder("Extracted ");
                    if (bitmaps > 0 && sounds > 0) {
                        msg.append(bitmaps).append(" bitmaps and ").append(sounds).append(" sounds");
                    } else if (bitmaps > 0) {
                        msg.append(bitmaps).append(" bitmaps");
                    } else if (sounds > 0) {
                        msg.append(sounds).append(" sounds");
                    } else {
                        msg.append("0 assets");
                    }
                    msg.append(" to ").append(outputDir);
                    setStatus(msg.toString());
                } catch (Exception ex) {
                    setStatus("Error during extraction: " + ex.getMessage());
                }

                progressBar.setVisible(false);
                extractButton.setEnabled(true);
                extractAllButton.setEnabled(true);
            }
        };

        worker.execute();
    }

    private void exportSelectedMember() {
        TreePath path = fileTree.getSelectionPath();
        if (path == null) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObject = node.getUserObject();

        if (userObject instanceof MemberNodeData memberData) {
            exportHandler.export(this, memberData, outputDirField.getText());
        }
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    @Override
    public void dispose() {
        audioController.dispose();
        executor.shutdownNow();
        super.dispose();
    }

    public static void main(String[] args) {
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
