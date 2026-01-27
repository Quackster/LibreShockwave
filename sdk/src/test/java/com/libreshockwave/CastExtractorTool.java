package com.libreshockwave;

import com.libreshockwave.chunks.*;
import com.libreshockwave.bitmap.Bitmap;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * GUI Tool to extract bitmaps and text from .cct cast files.
 *
 * Features:
 * - Browse for input directory containing .cct files
 * - Preview list of found .cct files
 * - Progress bar and detailed log during extraction
 * - Each .cct file gets its own output folder
 */
public class CastExtractorTool extends JFrame {

    private static final String PREF_LAST_DIR = "lastDirectory";
    private final Preferences prefs = Preferences.userNodeForPackage(CastExtractorTool.class);

    private JTextField directoryField;
    private JButton browseButton;
    private JButton extractButton;
    private JList<String> fileList;
    private DefaultListModel<String> fileListModel;
    private JTextArea logArea;
    private JProgressBar progressBar;
    private JLabel statusLabel;

    private List<Path> cctFiles = new ArrayList<>();
    private volatile boolean extracting = false;

    public CastExtractorTool() {
        super("Cast Extractor - LibreShockwave");
        initUI();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setMinimumSize(new Dimension(600, 400));
        setLocationRelativeTo(null);

        // Load last used directory
        String lastDir = prefs.get(PREF_LAST_DIR, System.getProperty("user.home"));
        directoryField.setText(lastDir);
        scanDirectory(new File(lastDir));
    }

    private void initUI() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Top panel - Directory selection
        JPanel topPanel = createDirectoryPanel();
        mainPanel.add(topPanel, BorderLayout.NORTH);

        // Center panel - Split between file list and log
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.3);

        // Left - File list
        JPanel fileListPanel = createFileListPanel();
        splitPane.setLeftComponent(fileListPanel);

        // Right - Log area
        JPanel logPanel = createLogPanel();
        splitPane.setRightComponent(logPanel);

        mainPanel.add(splitPane, BorderLayout.CENTER);

        // Bottom panel - Progress and controls
        JPanel bottomPanel = createBottomPanel();
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    private JPanel createDirectoryPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(new TitledBorder("Input Directory"));

        directoryField = new JTextField();
        directoryField.setEditable(false);
        directoryField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        browseButton = new JButton("Browse...");
        browseButton.addActionListener(this::onBrowse);

        panel.add(new JLabel("Directory:"), BorderLayout.WEST);
        panel.add(directoryField, BorderLayout.CENTER);
        panel.add(browseButton, BorderLayout.EAST);

        return panel;
    }

    private JPanel createFileListPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(new TitledBorder("Cast Files Found"));
        panel.setPreferredSize(new Dimension(200, 0));

        fileListModel = new DefaultListModel<>();
        fileList = new JList<>(fileListModel);
        fileList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane scrollPane = new JScrollPane(fileList);
        panel.add(scrollPane, BorderLayout.CENTER);

        JLabel countLabel = new JLabel("0 files");
        countLabel.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(countLabel, BorderLayout.SOUTH);

        // Update count label when model changes
        fileListModel.addListDataListener(new javax.swing.event.ListDataListener() {
            public void intervalAdded(javax.swing.event.ListDataEvent e) { updateCount(); }
            public void intervalRemoved(javax.swing.event.ListDataEvent e) { updateCount(); }
            public void contentsChanged(javax.swing.event.ListDataEvent e) { updateCount(); }
            private void updateCount() {
                countLabel.setText(fileListModel.size() + " file(s)");
            }
        });

        return panel;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(new TitledBorder("Extraction Log"));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);

        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        panel.add(scrollPane, BorderLayout.CENTER);

        JButton clearButton = new JButton("Clear Log");
        clearButton.addActionListener(e -> logArea.setText(""));
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(clearButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));

        // Progress bar
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Ready");

        // Status and buttons
        JPanel statusPanel = new JPanel(new BorderLayout(10, 5));

        statusLabel = new JLabel("Select a directory containing .cct files");
        statusPanel.add(statusLabel, BorderLayout.CENTER);

        extractButton = new JButton("Extract All");
        extractButton.setFont(extractButton.getFont().deriveFont(Font.BOLD, 14f));
        extractButton.setEnabled(false);
        extractButton.addActionListener(this::onExtract);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(extractButton);
        statusPanel.add(buttonPanel, BorderLayout.EAST);

        panel.add(progressBar, BorderLayout.NORTH);
        panel.add(statusPanel, BorderLayout.SOUTH);

        return panel;
    }

    private void onBrowse(ActionEvent e) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Directory with .cct Files");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);

        String lastDir = prefs.get(PREF_LAST_DIR, System.getProperty("user.home"));
        chooser.setCurrentDirectory(new File(lastDir));

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            directoryField.setText(selected.getAbsolutePath());
            prefs.put(PREF_LAST_DIR, selected.getAbsolutePath());
            scanDirectory(selected);
        }
    }

    private void scanDirectory(File dir) {
        fileListModel.clear();
        cctFiles.clear();

        if (!dir.exists() || !dir.isDirectory()) {
            statusLabel.setText("Invalid directory");
            extractButton.setEnabled(false);
            return;
        }

        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".cct"));
        if (files != null) {
            for (File f : files) {
                cctFiles.add(f.toPath());
                fileListModel.addElement(f.getName());
            }
        }

        if (cctFiles.isEmpty()) {
            statusLabel.setText("No .cct files found in this directory");
            extractButton.setEnabled(false);
        } else {
            statusLabel.setText("Found " + cctFiles.size() + " cast file(s). Click 'Extract All' to begin.");
            extractButton.setEnabled(true);
        }
    }

    private void onExtract(ActionEvent e) {
        if (extracting) return;
        if (cctFiles.isEmpty()) return;

        extracting = true;
        extractButton.setEnabled(false);
        browseButton.setEnabled(false);
        logArea.setText("");
        progressBar.setValue(0);

        // Run extraction in background thread
        SwingWorker<Void, String> worker = new SwingWorker<>() {
            int totalBitmaps = 0;
            int totalTexts = 0;
            int totalErrors = 0;

            @Override
            protected Void doInBackground() {
                int fileIndex = 0;
                for (Path cctFile : cctFiles) {
                    if (isCancelled()) break;

                    fileIndex++;
                    int progress = (fileIndex * 100) / cctFiles.size();

                    String fileName = cctFile.getFileName().toString();
                    publish("\n=== Processing: " + fileName + " ===");
                    setProgress(progress);

                    try {
                        processCastFile(cctFile);
                    } catch (Exception ex) {
                        totalErrors++;
                        publish("  ERROR: " + ex.getMessage());
                    }
                }
                return null;
            }

            private void processCastFile(Path cctFile) {
                String fileName = cctFile.getFileName().toString();
                String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                Path outputDir = cctFile.getParent().resolve(baseName);

                try {
                    Files.createDirectories(outputDir);
                    publish("  Output folder: " + outputDir.getFileName());

                    DirectorFile file = DirectorFile.load(cctFile);
                    publish("  Loaded " + file.getCastMembers().size() + " cast member(s)");

                    int bitmapCount = 0;
                    int textCount = 0;

                    for (CastMemberChunk member : file.getCastMembers()) {
                        try {
                            if (member.isBitmap()) {
                                if (extractBitmap(file, member, outputDir)) {
                                    bitmapCount++;
                                    publish("    [Bitmap] " + getSafeName(member));
                                }
                            } else if (member.isText()) {
                                if (extractText(file, member, outputDir)) {
                                    textCount++;
                                    publish("    [Text] " + getSafeName(member));
                                }
                            }
                        } catch (Exception e) {
                            totalErrors++;
                            publish("    ERROR: " + member.name() + " - " + e.getMessage());
                        }
                    }

                    totalBitmaps += bitmapCount;
                    totalTexts += textCount;
                    publish("  Extracted: " + bitmapCount + " bitmaps, " + textCount + " text files");

                } catch (IOException e) {
                    totalErrors++;
                    publish("  FAILED: " + e.getMessage());
                }
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    logArea.append(msg + "\n");
                }
                // Auto-scroll to bottom
                logArea.setCaretPosition(logArea.getDocument().getLength());
                progressBar.setValue(getProgress());
                progressBar.setString("Processing... " + getProgress() + "%");
            }

            @Override
            protected void done() {
                extracting = false;
                extractButton.setEnabled(true);
                browseButton.setEnabled(true);
                progressBar.setValue(100);
                progressBar.setString("Complete!");

                String summary = String.format(
                    "\n========================================\n" +
                    "EXTRACTION COMPLETE\n" +
                    "  Total bitmaps: %d\n" +
                    "  Total text files: %d\n" +
                    "  Errors: %d\n" +
                    "========================================",
                    totalBitmaps, totalTexts, totalErrors
                );
                logArea.append(summary);
                logArea.setCaretPosition(logArea.getDocument().getLength());

                statusLabel.setText("Extraction complete! " + totalBitmaps + " bitmaps, " +
                    totalTexts + " text files extracted.");

                // Show completion dialog
                JOptionPane.showMessageDialog(CastExtractorTool.this,
                    "Extraction complete!\n\n" +
                    "Bitmaps extracted: " + totalBitmaps + "\n" +
                    "Text files extracted: " + totalTexts + "\n" +
                    (totalErrors > 0 ? "Errors: " + totalErrors : ""),
                    "Extraction Complete",
                    totalErrors > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
            }
        };

        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                progressBar.setValue((Integer) evt.getNewValue());
            }
        });

        worker.execute();
    }

    private static boolean extractBitmap(DirectorFile file, CastMemberChunk member, Path outputDir)
            throws IOException {
        Optional<Bitmap> bitmapOpt = file.decodeBitmap(member);
        if (bitmapOpt.isEmpty()) {
            return false;
        }

        Bitmap bitmap = bitmapOpt.get();
        String safeName = sanitizeFileName(member.name(), member.id());
        Path outputFile = outputDir.resolve(safeName + ".png");

        ImageIO.write(bitmap.toBufferedImage(), "PNG", outputFile.toFile());
        return true;
    }

    private static boolean extractText(DirectorFile file, CastMemberChunk member, Path outputDir)
            throws IOException {
        KeyTableChunk keyTable = file.getKeyTable();
        if (keyTable == null) {
            return false;
        }

        // Find STXT chunk via key table
        TextChunk textChunk = null;
        for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(member.id())) {
            String fourcc = entry.fourccString();
            if (fourcc.equals("STXT") || fourcc.equals("TXTS")) {
                Chunk chunk = file.getChunk(entry.sectionId());
                if (chunk instanceof TextChunk tc) {
                    textChunk = tc;
                    break;
                }
            }
        }

        if (textChunk == null || textChunk.text().isEmpty()) {
            return false;
        }

        String safeName = sanitizeFileName(member.name(), member.id());
        Path outputFile = outputDir.resolve(safeName + ".txt");

        Files.writeString(outputFile, textChunk.text());
        return true;
    }

    private static String getSafeName(CastMemberChunk member) {
        String name = member.name();
        if (name == null || name.trim().isEmpty()) {
            return "member_" + member.id();
        }
        return name;
    }

    private static String sanitizeFileName(String name, int id) {
        if (name == null || name.trim().isEmpty()) {
            return "member_" + id;
        }

        String safeName = name.trim()
            .replaceAll("[\\\\/:*?\"<>|]", "_")
            .replaceAll("\\s+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");

        if (safeName.isEmpty()) {
            return "member_" + id;
        }

        return safeName + "_" + id;
    }

    public static void main(String[] args) {
        // Set look and feel to system default
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Fall back to default
        }

        // Enable anti-aliasing
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        SwingUtilities.invokeLater(() -> {
            CastExtractorTool tool = new CastExtractorTool();
            tool.setVisible(true);
        });
    }
}
