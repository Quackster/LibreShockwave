package com.libreshockwave;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.chunks.CastMemberChunk;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
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
import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

/**
 * A Swing application for browsing and extracting bitmap cast members
 * from Shockwave/Director files.
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
    private JLabel statusLabel;
    private JButton extractButton;
    private JButton extractAllButton;
    private JProgressBar progressBar;

    // Lazy loading cache using soft references to allow GC when memory is low
    private final Map<BitmapKey, SoftReference<BufferedImage>> imageCache = new ConcurrentHashMap<>();
    private final Map<String, DirectorFile> loadedFiles = new ConcurrentHashMap<>();

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

        rootNode = new DefaultMutableTreeNode("Files");
        treeModel = new DefaultTreeModel(rootNode);
        fileTree = new JTree(treeModel);
        fileTree.setRootVisible(false);
        fileTree.setCellRenderer(new FileTreeCellRenderer());
        fileTree.addTreeSelectionListener(this::onTreeSelection);

        JScrollPane scrollPane = new JScrollPane(fileTree);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createPreviewPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Preview"));

        previewLabel = new JLabel("Select a bitmap to preview", SwingConstants.CENTER);
        previewLabel.setPreferredSize(new Dimension(400, 400));

        JScrollPane scrollPane = new JScrollPane(previewLabel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(16);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
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

        // Scan in background
        SwingWorker<List<FileNode>, String> worker = new SwingWorker<>() {
            @Override
            protected List<FileNode> doInBackground() {
                List<FileNode> fileNodes = new ArrayList<>();

                try (Stream<Path> paths = Files.walk(inputPath)) {
                    List<Path> files = paths.filter(Files::isRegularFile).toList();
                    int total = files.size();
                    int processed = 0;

                    for (Path file : files) {
                        processed++;
                        publish("Scanning: " + file.getFileName() + " (" + processed + "/" + total + ")");

                        try {
                            DirectorFile dirFile = DirectorFile.load(file);
                            List<BitmapMemberInfo> bitmaps = new ArrayList<>();

                            for (CastMemberChunk member : dirFile.getCastMembers()) {
                                if (member.isBitmap()) {
                                    String name = member.name();
                                    if (name == null || name.isEmpty()) {
                                        name = "Unnamed #" + member.id();
                                    }
                                    // Parse bitmap info to get dimensions and palette info
                                    BitmapInfo info = BitmapInfo.parse(member.specificData());
                                    bitmaps.add(new BitmapMemberInfo(
                                            member.id(), name, member,
                                            info.width(), info.height(),
                                            info.bitDepth(), info.paletteId()
                                    ));
                                }
                            }

                            if (!bitmaps.isEmpty()) {
                                loadedFiles.put(file.toString(), dirFile);
                                fileNodes.add(new FileNode(file.toString(), file.getFileName().toString(), bitmaps));
                            }
                        } catch (Exception ignored) {
                            // Silently ignore files that fail to parse
                        }
                    }
                } catch (IOException ex) {
                    publish("Error scanning directory: " + ex.getMessage());
                }

                return fileNodes;
            }

            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) {
                    statusLabel.setText(chunks.get(chunks.size() - 1));
                }
            }

            @Override
            protected void done() {
                try {
                    List<FileNode> fileNodes = get();
                    populateTree(fileNodes);

                    int totalBitmaps = fileNodes.stream()
                            .mapToInt(f -> f.bitmaps.size())
                            .sum();

                    statusLabel.setText("Found " + fileNodes.size() + " files with " + totalBitmaps + " bitmaps");
                    extractAllButton.setEnabled(!fileNodes.isEmpty() && !outputDirField.getText().isEmpty());

                } catch (Exception ex) {
                    statusLabel.setText("Error: " + ex.getMessage());
                }
            }
        };

        statusLabel.setText("Scanning...");
        worker.execute();
    }

    private void clearData() {
        rootNode.removeAllChildren();
        treeModel.reload();
        imageCache.clear();
        loadedFiles.clear();
        previewLabel.setIcon(null);
        previewLabel.setText("Select a bitmap to preview");
        extractButton.setEnabled(false);
        extractAllButton.setEnabled(false);
    }

    private void populateTree(List<FileNode> fileNodes) {
        rootNode.removeAllChildren();

        for (FileNode fileNode : fileNodes) {
            DefaultMutableTreeNode fileTreeNode = new DefaultMutableTreeNode(fileNode);

            for (BitmapMemberInfo bitmap : fileNode.bitmaps) {
                DefaultMutableTreeNode bitmapNode = new DefaultMutableTreeNode(
                        new BitmapNodeData(fileNode.filePath, bitmap)
                );
                fileTreeNode.add(bitmapNode);
            }

            rootNode.add(fileTreeNode);
        }

        treeModel.reload();
    }

    private void onTreeSelection(TreeSelectionEvent e) {
        TreePath path = e.getPath();
        if (path == null) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObject = node.getUserObject();

        if (userObject instanceof BitmapNodeData bitmapData) {
            extractButton.setEnabled(!outputDirField.getText().isEmpty());
            loadAndDisplayBitmap(bitmapData);
        } else {
            extractButton.setEnabled(false);
            previewLabel.setIcon(null);
            previewLabel.setText("Select a bitmap to preview");
        }
    }

    private void loadAndDisplayBitmap(BitmapNodeData bitmapData) {
        BitmapKey key = new BitmapKey(bitmapData.filePath, bitmapData.bitmapInfo.memberNum);

        // Check cache first
        SoftReference<BufferedImage> ref = imageCache.get(key);
        if (ref != null) {
            BufferedImage cached = ref.get();
            if (cached != null) {
                displayImage(cached, bitmapData.bitmapInfo);
                return;
            }
        }

        // Load in background
        previewLabel.setIcon(null);
        previewLabel.setText("Loading...");

        SwingWorker<BufferedImage, Void> worker = new SwingWorker<>() {
            @Override
            protected BufferedImage doInBackground() {
                DirectorFile dirFile = loadedFiles.get(bitmapData.filePath);
                if (dirFile == null) return null;

                return dirFile.decodeBitmap(bitmapData.bitmapInfo.member)
                        .map(Bitmap::toBufferedImage)
                        .orElse(null);
            }

            @Override
            protected void done() {
                try {
                    BufferedImage image = get();
                    if (image != null) {
                        imageCache.put(key, new SoftReference<>(image));
                        displayImage(image, bitmapData.bitmapInfo);
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

    private void displayImage(BufferedImage image, BitmapMemberInfo info) {
        ImageIcon icon = new ImageIcon(image);
        previewLabel.setIcon(icon);
        previewLabel.setText(null);

        String paletteDesc = getPaletteDescription(info.paletteId);
        statusLabel.setText(String.format("%s - %dx%d, %d-bit, Palette: %s",
                info.name, image.getWidth(), image.getHeight(), info.bitDepth, paletteDesc));
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

        if (userObject instanceof BitmapNodeData bitmapData) {
            tasks.add(new ExtractionTask(bitmapData.filePath, bitmapData.bitmapInfo));
        } else if (userObject instanceof FileNode fileNode) {
            for (BitmapMemberInfo bitmap : fileNode.bitmaps) {
                tasks.add(new ExtractionTask(fileNode.filePath, bitmap));
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

            for (BitmapMemberInfo bitmap : fileData.bitmaps) {
                tasks.add(new ExtractionTask(fileData.filePath, bitmap));
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
                            var bitmapOpt = dirFile.decodeBitmap(task.bitmapInfo.member);
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
                                String safeName = task.bitmapInfo.name
                                        .replaceAll("[^a-zA-Z0-9._-]", "_");
                                if (safeName.isEmpty()) {
                                    safeName = "bitmap_" + task.bitmapInfo.memberNum;
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
        executor.shutdownNow();
        super.dispose();
    }

    // Data classes

    private record FileNode(String filePath, String fileName, List<BitmapMemberInfo> bitmaps) {
        @Override
        public String toString() {
            return fileName + " (" + bitmaps.size() + " bitmaps)";
        }
    }

    private record BitmapMemberInfo(int memberNum, String name, CastMemberChunk member,
                                       int width, int height, int bitDepth, int paletteId) {
    }

    private record BitmapNodeData(String filePath, BitmapMemberInfo bitmapInfo) {
        @Override
        public String toString() {
            return String.format("%s (%dx%d, %d-bit)",
                    bitmapInfo.name, bitmapInfo.width, bitmapInfo.height, bitmapInfo.bitDepth);
        }
    }

    private record BitmapKey(String filePath, int memberNum) {
    }

    private record ExtractionTask(String filePath, BitmapMemberInfo bitmapInfo) {
    }

    // Custom tree cell renderer

    private static class FileTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {

            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

            if (value instanceof DefaultMutableTreeNode node) {
                Object userObject = node.getUserObject();
                if (userObject instanceof FileNode) {
                    setIcon(UIManager.getIcon("FileView.directoryIcon"));
                } else if (userObject instanceof BitmapNodeData) {
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
