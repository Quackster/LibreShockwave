package com.libreshockwave.player.swing;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.execution.DirPlayer;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.player.*;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.xtras.XtraManager;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Swing-based Director player for debugging and testing.
 * Uses SDK's DirPlayer for playback logic.
 */
public class SwingPlayer extends JFrame {

    private final StagePanel stagePanel;
    private final JTextArea consoleArea;
    private final JLabel statusLabel;
    private final JLabel frameLabel;
    private final JButton playButton;
    private final JButton stopButton;
    private final JButton prevButton;
    private final JButton nextButton;
    private final JCheckBox debugCheckbox;
    private final JTextField urlField;
    private final JButton loadUrlButton;

    // Use SDK's DirPlayer for playback
    private DirPlayer dirPlayer;
    private XtraManager xtraManager;
    private javax.swing.Timer playTimer;

    private final Map<String, BufferedImage> bitmapCache = new HashMap<>();
    private File currentMovieDir;
    private String currentBaseUrl;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Preferences prefs = Preferences.userNodeForPackage(SwingPlayer.class);
    private static final String PREF_LAST_MOVIE = "lastMoviePath";
    private static final String PREF_LAST_URL = "lastMovieUrl";

    public SwingPlayer() {
        super("LibreShockwave Swing Player");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Create menu bar
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem openItem = new JMenuItem("Open...");
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        openItem.addActionListener(e -> openFile());
        fileMenu.add(openItem);
        fileMenu.addSeparator();
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        fileMenu.add(exitItem);
        menuBar.add(fileMenu);
        setJMenuBar(menuBar);

        // Create main layout
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Stage panel
        stagePanel = new StagePanel();
        stagePanel.setPreferredSize(new Dimension(640, 480));
        stagePanel.setBackground(Color.WHITE);
        JScrollPane stageScroll = new JScrollPane(stagePanel);
        stageScroll.setBorder(BorderFactory.createTitledBorder("Stage"));

        // URL panel
        JPanel urlPanel = new JPanel(new BorderLayout(5, 0));
        urlPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        urlField = new JTextField();
        loadUrlButton = new JButton("Load URL");

        String lastUrl = prefs.get(PREF_LAST_URL, "http://localhost:8080/assets/movie.dcr");
        urlField.setText(lastUrl);

        urlField.addActionListener(e -> loadFromUrl(urlField.getText().trim()));
        loadUrlButton.addActionListener(e -> loadFromUrl(urlField.getText().trim()));

        urlPanel.add(new JLabel("URL: "), BorderLayout.WEST);
        urlPanel.add(urlField, BorderLayout.CENTER);
        urlPanel.add(loadUrlButton, BorderLayout.EAST);

        // Control panel
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        playButton = new JButton("Play");
        stopButton = new JButton("Stop");
        prevButton = new JButton("< Prev");
        nextButton = new JButton("Next >");
        frameLabel = new JLabel("Frame: 0 / 0");
        debugCheckbox = new JCheckBox("Debug VM", true);

        playButton.addActionListener(e -> play());
        stopButton.addActionListener(e -> stop());
        prevButton.addActionListener(e -> prevFrame());
        nextButton.addActionListener(e -> nextFrame());
        debugCheckbox.addActionListener(e -> {
            if (dirPlayer != null && dirPlayer.getVM() != null) {
                dirPlayer.getVM().setDebugMode(debugCheckbox.isSelected());
            }
        });

        controlPanel.add(playButton);
        controlPanel.add(stopButton);
        controlPanel.add(prevButton);
        controlPanel.add(nextButton);
        controlPanel.add(Box.createHorizontalStrut(20));
        controlPanel.add(frameLabel);
        controlPanel.add(Box.createHorizontalStrut(20));
        controlPanel.add(debugCheckbox);

        // Top panel combines URL and controls
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(urlPanel, BorderLayout.NORTH);
        topPanel.add(controlPanel, BorderLayout.SOUTH);

        // Console panel
        consoleArea = new JTextArea(10, 60);
        consoleArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        consoleArea.setEditable(false);
        JScrollPane consoleScroll = new JScrollPane(consoleArea);
        consoleScroll.setBorder(BorderFactory.createTitledBorder("Console"));

        JButton clearConsole = new JButton("Clear");
        clearConsole.addActionListener(e -> consoleArea.setText(""));
        JPanel consolePanel = new JPanel(new BorderLayout());
        consolePanel.add(consoleScroll, BorderLayout.CENTER);
        JPanel consoleButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        consoleButtons.add(clearConsole);
        consolePanel.add(consoleButtons, BorderLayout.SOUTH);

        // Status bar
        statusLabel = new JLabel("Ready - Open a .dcr, .dir, or .dxr file");
        statusLabel.setBorder(BorderFactory.createLoweredBevelBorder());

        // Layout
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stageScroll, consolePanel);
        splitPane.setResizeWeight(0.7);

        mainPanel.add(topPanel, BorderLayout.NORTH);
        mainPanel.add(splitPane, BorderLayout.CENTER);
        mainPanel.add(statusLabel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
        pack();
        setLocationRelativeTo(null);

        setControlsEnabled(false);
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            consoleArea.append(message + "\n");
            consoleArea.setCaretPosition(consoleArea.getDocument().getLength());
        });
        System.out.println(message);
    }

    /**
     * Get the member name for a script, searching all casts.
     * Used for debug output to show "Client Initialization Script" instead of "#5".
     */
    private String getScriptMemberName(ScriptChunk script) {
        if (script == null || dirPlayer == null) return null;
        int scriptId = script.id();
        DirectorFile file = dirPlayer.getFile();
        CastManager castManager = dirPlayer.getCastManager();

        // First check the main file's cast members
        if (file != null) {
            for (var member : file.getCastMembers()) {
                if (member.id() == scriptId && member.isScript()) {
                    String name = member.name();
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        }

        // Then check all cast libraries
        if (castManager != null) {
            for (CastLib cast : castManager.getCasts()) {
                if (cast.getState() == CastLib.State.LOADED) {
                    var member = cast.getMember(scriptId);
                    if (member != null && member.isScript()) {
                        String name = member.name();
                        if (name != null && !name.isEmpty()) {
                            return name;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Format a script identifier for display (member name if available, otherwise "#id").
     */
    private String formatScriptId(ScriptChunk script) {
        String name = getScriptMemberName(script);
        if (name != null && !name.isEmpty()) {
            return "\"" + name + "\"";
        }
        return "#" + script.id();
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private void setControlsEnabled(boolean enabled) {
        playButton.setEnabled(enabled);
        stopButton.setEnabled(enabled);
        prevButton.setEnabled(enabled);
        nextButton.setEnabled(enabled);
    }

    private void openFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
            "Director Files (*.dcr, *.dir, *.dxr, *.cct, *.cst)",
            "dcr", "dir", "dxr", "cct", "cst"));

        String lastPath = prefs.get(PREF_LAST_MOVIE, null);
        if (lastPath != null) {
            File lastFile = new File(lastPath);
            if (lastFile.getParentFile() != null && lastFile.getParentFile().exists()) {
                chooser.setCurrentDirectory(lastFile.getParentFile());
                if (lastFile.exists()) {
                    chooser.setSelectedFile(lastFile);
                }
            }
        }

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadMovie(chooser.getSelectedFile());
        }
    }

    private void loadMovie(File file) {
        stop();
        consoleArea.setText("");
        bitmapCache.clear();
        currentBaseUrl = null;

        prefs.put(PREF_LAST_MOVIE, file.getAbsolutePath());

        setStatus("Loading " + file.getName() + "...");
        log("=== Loading: " + file.getAbsolutePath() + " ===");

        try {
            byte[] data = Files.readAllBytes(file.toPath());
            currentMovieDir = file.getParentFile();
            log("File size: " + data.length + " bytes");

            // Create DirPlayer and load movie
            dirPlayer = new DirPlayer();
            dirPlayer.loadMovie(data);

            // Set base path for external casts
            if (currentMovieDir != null) {
                dirPlayer.setBaseUrl(currentMovieDir.toPath().toUri().toString());
            }

            // Hook into VM for debug output
            LingoVM vm = dirPlayer.getVM();
            vm.setDebugMode(debugCheckbox.isSelected());
            vm.setDebugOutputCallback(this::log);

            // Set stage callback for window operations
            setupStageCallback(vm);

            // Initialize and register Xtras
            xtraManager = XtraManager.createWithStandardXtras();
            xtraManager.registerAll(vm);

            // Register Swing-specific builtins
            registerSwingBuiltins(vm);

            // Load external casts first so member counts are accurate
            loadExternalCasts();

            logMovieInfo();

            setControlsEnabled(true);
            updateFrameLabel();
            renderFrame();

            // Execute startup handlers via DirPlayer
            log("--- Executing startup handlers ---");
            dirPlayer.dispatchEvent(DirPlayer.MovieEvent.PREPARE_MOVIE);
            dirPlayer.dispatchEvent(DirPlayer.MovieEvent.START_MOVIE);

            setStatus("Loaded: " + file.getName());

        } catch (Exception e) {
            log("ERROR: " + e.getMessage());
            e.printStackTrace();
            setStatus("Failed to load: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                "Failed to load movie:\n" + e.getMessage(),
                "Load Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadFromUrl(String url) {
        if (url == null || url.isEmpty()) return;

        stop();
        consoleArea.setText("");
        bitmapCache.clear();
        currentMovieDir = null;

        prefs.put(PREF_LAST_URL, url);
        urlField.setText(url);

        currentBaseUrl = url.substring(0, url.lastIndexOf('/') + 1);

        setStatus("Loading from URL...");
        log("=== Loading: " + url + " ===");
        log("Base URL: " + currentBaseUrl);

        new Thread(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .build();
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                if (response.statusCode() != 200) {
                    SwingUtilities.invokeLater(() -> {
                        log("ERROR: HTTP " + response.statusCode());
                        setStatus("Failed to load: HTTP " + response.statusCode());
                    });
                    return;
                }

                byte[] data = response.body();
                SwingUtilities.invokeLater(() -> {
                    log("Downloaded: " + data.length + " bytes");
                    loadMovieFromData(data, url);
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    log("ERROR: " + e.getMessage());
                    setStatus("Failed to load: " + e.getMessage());
                    JOptionPane.showMessageDialog(this,
                        "Failed to load from URL:\n" + e.getMessage(),
                        "Load Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    private void loadMovieFromData(byte[] data, String sourceName) {
        try {
            // Create DirPlayer and load movie
            dirPlayer = new DirPlayer();
            dirPlayer.loadMovie(data);

            // Set base URL for external casts
            if (currentBaseUrl != null) {
                dirPlayer.setBaseUrl(currentBaseUrl);
            }

            // Hook into VM for debug output
            LingoVM vm = dirPlayer.getVM();
            vm.setDebugMode(debugCheckbox.isSelected());
            vm.setDebugOutputCallback(this::log);

            // Set stage callback for window operations
            setupStageCallback(vm);

            // Initialize and register Xtras
            xtraManager = XtraManager.createWithStandardXtras();
            xtraManager.registerAll(vm);

            // Register Swing-specific builtins
            registerSwingBuiltins(vm);

            // Update stage size
            ConfigChunk config = dirPlayer.getFile().getConfig();
            if (config != null) {
                int width = config.stageRight() - config.stageLeft();
                int height = config.stageBottom() - config.stageTop();
                stagePanel.setPreferredSize(new Dimension(width, height));
                stagePanel.revalidate();
            }

            // Load external casts first so member counts are accurate
            loadExternalCasts();

            logMovieInfo();

            setControlsEnabled(true);
            updateFrameLabel();
            renderFrame();

            // Execute startup handlers
            log("--- Executing startup handlers ---");
            dirPlayer.dispatchEvent(DirPlayer.MovieEvent.PREPARE_MOVIE);
            dirPlayer.dispatchEvent(DirPlayer.MovieEvent.START_MOVIE);

            setStatus("Loaded: " + sourceName);

        } catch (Exception e) {
            log("ERROR: " + e.getMessage());
            e.printStackTrace();
            setStatus("Failed to load: " + e.getMessage());
        }
    }

    private void logMovieInfo() {
        DirectorFile movieFile = dirPlayer.getFile();

        log("DirectorFile loaded, endian: " + movieFile.getEndian());

        ConfigChunk config = movieFile.getConfig();
        if (config != null) {
            int width = config.stageRight() - config.stageLeft();
            int height = config.stageBottom() - config.stageTop();
            stagePanel.setPreferredSize(new Dimension(width, height));
            stagePanel.revalidate();
            log("Config: " + width + "x" + height + " @ " + dirPlayer.getTempo() + " fps");
        }

        CastManager castManager = dirPlayer.getCastManager();
        log("CastManager: " + castManager.getCastCount() + " casts");

        for (CastLib cast : castManager.getCasts()) {
            log("  Cast #" + cast.getNumber() + " '" + cast.getName() + "': " +
                cast.getMemberCount() + " members" +
                (cast.isExternal() ? " [EXTERNAL: " + cast.getFileName() + "]" : ""));
        }

        Score score = dirPlayer.getScore();
        if (score != null) {
            log("Score: " + dirPlayer.getLastFrame() + " frames");
        } else {
            log("No score found");
        }

        log("LingoVM initialized, " + movieFile.getScripts().size() + " scripts");
        log("Xtras loaded: " + (xtraManager != null ? xtraManager.getXtras().size() : 0));

        ScriptNamesChunk names = movieFile.getScriptNames();
        log("ScriptNames chunk: " + (names != null ? names.names().size() + " names" : "NULL"));
        if (names != null && !names.names().isEmpty()) {
            log("  Names: " + names.names());
        }

        for (ScriptChunk script : movieFile.getScripts()) {
            log("  Script " + formatScriptId(script) + " (" + script.scriptType() + "): " +
                script.handlers().size() + " handlers");
            for (ScriptChunk.Handler h : script.handlers()) {
                String handlerName = names != null ? names.getName(h.nameId()) : "?" + h.nameId();
                log("    - " + handlerName + " (nameId=" + h.nameId() + ", " +
                    h.instructions().size() + " instructions)");
            }
        }
    }

    private void loadExternalCasts() {
        if (dirPlayer == null) return;

        CastManager castManager = dirPlayer.getCastManager();
        if (castManager == null) return;
        if (currentMovieDir == null && currentBaseUrl == null) return;

        for (CastLib cast : castManager.getCasts()) {
            if (cast.isExternal() && cast.getState() == CastLib.State.NONE) {
                String fileName = cast.getFileName();
                if (fileName.isEmpty()) continue;

                log("Loading external cast #" + cast.getNumber() + ": " + fileName);

                String[] extensions = {"", ".cct", ".cst", ".cxt"};
                boolean loaded = false;

                for (String ext : extensions) {
                    if (loaded) break;
                    String baseName = fileName.replaceAll("\\.(cct|cst|cxt)$", "");

                    // Try local file first
                    if (currentMovieDir != null) {
                        File castFile = new File(currentMovieDir, baseName + ext);
                        if (castFile.exists()) {
                            try {
                                byte[] data = Files.readAllBytes(castFile.toPath());
                                DirectorFile castDir = DirectorFile.load(data);
                                cast.loadFromDirectorFile(castDir);
                                log("  Loaded from file: " + castFile.getName() + " (" + cast.getMemberCount() + " members)");
                                loaded = true;
                                break;
                            } catch (Exception e) {
                                log("  Failed to load " + castFile.getName() + ": " + e.getMessage());
                            }
                        }
                    }

                    // Try URL
                    if (currentBaseUrl != null && !loaded) {
                        String castUrl = currentBaseUrl + baseName + ext;
                        try {
                            HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(castUrl))
                                .build();
                            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                            if (response.statusCode() == 200) {
                                byte[] data = response.body();
                                DirectorFile castDir = DirectorFile.load(data);
                                cast.loadFromDirectorFile(castDir);
                                log("  Loaded from URL: " + castUrl + " (" + cast.getMemberCount() + " members)");
                                loaded = true;
                                break;
                            }
                        } catch (Exception e) {
                            log("  Failed to load " + castUrl + ": " + e.getMessage());
                        }
                    }
                }

                if (!loaded) {
                    log("  WARNING: Could not load external cast");
                }
            }
        }
    }

    private void setupStageCallback(LingoVM vm) {
        vm.setStageCallback(new LingoVM.StageCallback() {
            @Override
            public void moveToFront() {
                SwingUtilities.invokeLater(() -> {
                    SwingPlayer.this.toFront();
                    SwingPlayer.this.requestFocus();
                });
            }

            @Override
            public void moveToBack() {
                SwingUtilities.invokeLater(() -> SwingPlayer.this.toBack());
            }

            @Override
            public void close() {
                SwingUtilities.invokeLater(() -> SwingPlayer.this.dispose());
            }
        });
    }

    private void registerSwingBuiltins(LingoVM vm) {
        // Swing-specific handlers that override DirPlayer's defaults
        vm.registerBuiltin("put", (vmRef, args) -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(" ");
                sb.append(args.get(i).toString());
            }
            log("[put] " + sb);
            return Datum.voidValue();
        });

        vm.registerBuiltin("trace", (vmRef, args) -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(" ");
                sb.append(args.get(i).toString());
            }
            log("[trace] " + sb);
            return Datum.voidValue();
        });

        vm.registerBuiltin("alert", (vmRef, args) -> {
            String msg = args.isEmpty() ? "" : args.get(0).toString();
            log("[alert] " + msg);
            JOptionPane.showMessageDialog(this, msg, "Alert", JOptionPane.INFORMATION_MESSAGE);
            return Datum.voidValue();
        });

        // Override updateStage to trigger Swing repaint
        vm.registerBuiltin("updateStage", (vmRef, args) -> {
            renderFrame();
            return Datum.voidValue();
        });
    }

    private void play() {
        if (dirPlayer == null) return;
        if (dirPlayer.getState() == DirPlayer.PlayState.PLAYING) return;

        dirPlayer.play();
        playButton.setEnabled(false);
        log("Play");

        if (playTimer != null) {
            playTimer.stop();
        }
        int interval = Math.max(1, 1000 / dirPlayer.getTempo());
        playTimer = new javax.swing.Timer(interval, e -> tick());
        playTimer.start();
    }

    private void stop() {
        if (dirPlayer == null) return;
        if (dirPlayer.getState() == DirPlayer.PlayState.STOPPED) return;

        dirPlayer.stop();
        playButton.setEnabled(true);
        log("Stop");

        if (playTimer != null) {
            playTimer.stop();
            playTimer = null;
        }
    }

    private void tick() {
        if (dirPlayer == null) return;

        dirPlayer.tick();
        updateFrameLabel();
        renderFrame();
    }

    private void prevFrame() {
        if (dirPlayer == null) return;

        dirPlayer.prevFrame();
        updateFrameLabel();
        renderFrame();
    }

    private void nextFrame() {
        if (dirPlayer == null) return;

        dirPlayer.nextFrame();
        updateFrameLabel();
        renderFrame();
    }

    private void updateFrameLabel() {
        if (dirPlayer != null) {
            frameLabel.setText("Frame: " + dirPlayer.getCurrentFrame() + " / " + dirPlayer.getLastFrame());
        }
    }

    private void renderFrame() {
        if (dirPlayer == null) {
            stagePanel.setSprites(Collections.emptyList());
            return;
        }

        Score score = dirPlayer.getScore();
        if (score == null) {
            stagePanel.setSprites(Collections.emptyList());
            return;
        }

        Score.Frame frame = score.getFrame(dirPlayer.getCurrentFrame());
        if (frame == null) {
            stagePanel.setSprites(Collections.emptyList());
            return;
        }

        List<SpriteRenderInfo> renderSprites = new ArrayList<>();
        for (Sprite sprite : frame.getSpritesSorted()) {
            if (sprite.getCastMember() <= 0 || !sprite.isVisible()) continue;

            SpriteRenderInfo info = new SpriteRenderInfo();
            info.channel = sprite.getChannel();
            info.x = sprite.getLocH();
            info.y = sprite.getLocV();
            info.width = sprite.getWidth();
            info.height = sprite.getHeight();
            info.castLib = sprite.getCastLib();
            info.castMember = sprite.getCastMember();
            info.ink = sprite.getInk();
            info.blend = sprite.getBlend();

            info.image = getBitmap(info.castLib, info.castMember);

            renderSprites.add(info);
        }

        stagePanel.setSprites(renderSprites);
    }

    private BufferedImage getBitmap(int castLibNum, int memberNum) {
        if (dirPlayer == null) return null;

        int effectiveCastLib = castLibNum > 0 ? castLibNum : 1;
        String key = effectiveCastLib + ":" + memberNum;

        if (bitmapCache.containsKey(key)) {
            return bitmapCache.get(key);
        }

        try {
            CastManager castManager = dirPlayer.getCastManager();
            CastLib castLib = castManager.getCast(effectiveCastLib);
            if (castLib == null) return null;

            var bitmapOpt = castLib.decodeBitmap(memberNum);
            if (bitmapOpt.isEmpty()) return null;

            BufferedImage image = bitmapOpt.get().toBufferedImage();
            bitmapCache.put(key, image);
            return image;

        } catch (Exception e) {
            log("Failed to decode bitmap " + key + ": " + e.getMessage());
            return null;
        }
    }

    static class SpriteRenderInfo {
        int channel;
        int x, y, width, height;
        int castLib, castMember;
        int ink, blend;
        BufferedImage image;
    }

    class StagePanel extends JPanel {
        private List<SpriteRenderInfo> sprites = new ArrayList<>();

        public void setSprites(List<SpriteRenderInfo> sprites) {
            this.sprites = sprites;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;

            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, getWidth(), getHeight());

            for (SpriteRenderInfo sprite : sprites) {
                if (sprite.image != null) {
                    Composite oldComposite = g2d.getComposite();
                    if (sprite.blend < 100) {
                        g2d.setComposite(AlphaComposite.getInstance(
                            AlphaComposite.SRC_OVER, sprite.blend / 100f));
                    }

                    g2d.drawImage(sprite.image, sprite.x, sprite.y, null);
                    g2d.setComposite(oldComposite);
                } else {
                    int w = sprite.width > 0 ? sprite.width : 32;
                    int h = sprite.height > 0 ? sprite.height : 32;

                    g2d.setColor(new Color(0, 136, 255, 50));
                    g2d.fillRect(sprite.x, sprite.y, w, h);
                    g2d.setColor(new Color(0, 136, 255));
                    g2d.drawRect(sprite.x, sprite.y, w, h);

                    g2d.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
                    g2d.drawString("#" + sprite.channel, sprite.x + 2, sprite.y + 10);
                    g2d.drawString(sprite.castLib + ":" + sprite.castMember,
                        sprite.x + 2, sprite.y + 20);
                }
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            SwingPlayer player = new SwingPlayer();
            player.setVisible(true);

            if (args.length > 0) {
                String arg = args[0];
                if (arg.startsWith("http://") || arg.startsWith("https://")) {
                    player.loadFromUrl(arg);
                } else {
                    player.loadMovie(new File(arg));
                }
            }
        });
    }
}
