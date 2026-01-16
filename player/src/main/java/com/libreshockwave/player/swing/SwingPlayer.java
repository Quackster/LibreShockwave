package com.libreshockwave.player.swing;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.player.*;
import com.libreshockwave.player.bitmap.Bitmap;
import com.libreshockwave.player.bitmap.BitmapDecoder;
import com.libreshockwave.cast.BitmapInfo;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.net.NetManager;
import com.libreshockwave.xtras.XtraManager;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Swing-based Director player for debugging and testing.
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

    private DirectorFile movieFile;
    private CastManager castManager;
    private Score score;
    private LingoVM vm;
    private NetManager netManager;
    private XtraManager xtraManager;

    private int currentFrame = 1;
    private int lastFrame = 1;
    private int tempo = 15;
    private boolean playing = false;
    private javax.swing.Timer playTimer;

    private final Map<String, BufferedImage> bitmapCache = new HashMap<>();
    private File currentMovieDir;
    private final Preferences prefs = Preferences.userNodeForPackage(SwingPlayer.class);
    private static final String PREF_LAST_MOVIE = "lastMoviePath";

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
            if (vm != null) {
                vm.setDebugMode(debugCheckbox.isSelected());
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

        mainPanel.add(controlPanel, BorderLayout.NORTH);
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

        // Start in last used directory
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

        // Remember this file
        prefs.put(PREF_LAST_MOVIE, file.getAbsolutePath());

        setStatus("Loading " + file.getName() + "...");
        log("=== Loading: " + file.getAbsolutePath() + " ===");

        try {
            byte[] data = Files.readAllBytes(file.toPath());
            currentMovieDir = file.getParentFile();
            log("File size: " + data.length + " bytes");

            movieFile = DirectorFile.load(data);
            log("DirectorFile loaded, endian: " + movieFile.getEndian());

            // Get config
            ConfigChunk config = movieFile.getConfig();
            if (config != null) {
                tempo = config.tempo();
                int width = config.stageRight() - config.stageLeft();
                int height = config.stageBottom() - config.stageTop();
                stagePanel.setPreferredSize(new Dimension(width, height));
                stagePanel.revalidate();
                log("Config: " + width + "x" + height + " @ " + tempo + " fps");
            }

            // Create cast manager
            castManager = movieFile.createCastManager();
            log("CastManager: " + castManager.getCastCount() + " casts");

            for (CastLib cast : castManager.getCasts()) {
                log("  Cast #" + cast.getNumber() + " '" + cast.getName() + "': " +
                    cast.getMemberCount() + " members" +
                    (cast.isExternal() ? " [EXTERNAL: " + cast.getFileName() + "]" : ""));
            }

            // Load external casts
            loadExternalCasts();

            // Create score
            if (movieFile.hasScore()) {
                score = movieFile.createScore();
                lastFrame = score.getFrameCount();
                log("Score: " + lastFrame + " frames");
            } else {
                lastFrame = 1;
                log("No score found");
            }

            // Create VM
            vm = new LingoVM(movieFile);
            vm.setDebugMode(debugCheckbox.isSelected());
            vm.setDebugOutputCallback(this::log);

            // Set cast manager so VM can find handlers in external casts
            vm.setCastManager(castManager);

            // Initialize NetManager for network operations
            netManager = new NetManager();
            if (currentMovieDir != null) {
                netManager.setBasePath(currentMovieDir.toURI());
            }
            vm.setNetManager(netManager);

            registerBuiltins();

            // Initialize Xtras (provides network functions, etc.)
            xtraManager = XtraManager.createWithStandardXtras();
            xtraManager.registerAll(vm);
            log("LingoVM initialized, " + movieFile.getScripts().size() + " scripts");
            log("Xtras loaded: " + xtraManager.getXtras().size());

            // List scripts
            ScriptNamesChunk names = movieFile.getScriptNames();
            log("ScriptNames chunk: " + (names != null ? names.names().size() + " names" : "NULL"));
            if (names != null && !names.names().isEmpty()) {
                log("  Names: " + names.names());
            }

            for (ScriptChunk script : movieFile.getScripts()) {
                log("  Script #" + script.id() + " (" + script.scriptType() + "): " +
                    script.handlers().size() + " handlers");
                for (ScriptChunk.Handler h : script.handlers()) {
                    String handlerName = names != null ? names.getName(h.nameId()) : "?" + h.nameId();
                    log("    - " + handlerName + " (nameId=" + h.nameId() + ", " +
                        h.instructions().size() + " instructions)");

                    // Show first few opcodes
                    if (debugCheckbox.isSelected() && !h.instructions().isEmpty()) {
                        int showCount = Math.min(5, h.instructions().size());
                        for (int i = 0; i < showCount; i++) {
                            ScriptChunk.Handler.Instruction instr = h.instructions().get(i);
                            log("      [" + i + "] " + instr.opcode() + " " + instr.argument());
                        }
                        if (h.instructions().size() > showCount) {
                            log("      ... (" + (h.instructions().size() - showCount) + " more)");
                        }
                    }
                }
            }

            currentFrame = 1;
            setControlsEnabled(true);
            updateFrameLabel();
            renderFrame();

            // Execute startup handlers
            log("--- Executing startup handlers ---");
            executeHandlerIfExists("prepareMovie");
            executeHandlerIfExists("startMovie");

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

    private void loadExternalCasts() {
        if (castManager == null || currentMovieDir == null) return;

        for (CastLib cast : castManager.getCasts()) {
            if (cast.isExternal() && cast.getState() == CastLib.State.NONE) {
                String fileName = cast.getFileName();
                if (fileName.isEmpty()) continue;

                log("Loading external cast #" + cast.getNumber() + ": " + fileName);

                // Try different extensions
                String[] extensions = {"", ".cct", ".cst", ".cxt"};
                for (String ext : extensions) {
                    String baseName = fileName.replaceAll("\\.(cct|cst|cxt)$", "");
                    File castFile = new File(currentMovieDir, baseName + ext);
                    if (castFile.exists()) {
                        try {
                            byte[] data = Files.readAllBytes(castFile.toPath());
                            DirectorFile castDir = DirectorFile.load(data);
                            cast.loadFromDirectorFile(castDir);
                            log("  Loaded from: " + castFile.getName() + " (" + cast.getMemberCount() + " members)");
                            break;
                        } catch (Exception e) {
                            log("  Failed to load " + castFile.getName() + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
    }

    private void registerBuiltins() {
        if (vm == null) return;

        vm.registerBuiltin("go", (vmRef, args) -> {
            if (!args.isEmpty()) {
                Datum target = args.get(0);
                if (target.isInt()) {
                    goToFrame(target.intValue());
                } else if (target.isString()) {
                    goToLabel(target.stringValue());
                }
            }
            return Datum.voidValue();
        });

        vm.registerBuiltin("play", (vmRef, args) -> {
            play();
            return Datum.voidValue();
        });

        vm.registerBuiltin("stop", (vmRef, args) -> {
            stop();
            return Datum.voidValue();
        });

        vm.registerBuiltin("frame", (vmRef, args) -> Datum.of(currentFrame));

        vm.registerBuiltin("updateStage", (vmRef, args) -> {
            renderFrame();
            return Datum.voidValue();
        });

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
    }

    private void executeHandlerIfExists(String handlerName) {
        if (vm == null || movieFile == null) {
            log("executeHandlerIfExists(" + handlerName + "): vm or movieFile is null");
            return;
        }

        ScriptNamesChunk names = movieFile.getScriptNames();
        if (names == null) {
            log("executeHandlerIfExists(" + handlerName + "): ScriptNamesChunk is null");
            // Try to find by iterating all handlers and checking names
            log("  Available scripts: " + movieFile.getScripts().size());
            return;
        }

        int nameId = names.findName(handlerName);
        if (nameId < 0) {
            log("executeHandlerIfExists(" + handlerName + "): name not found in Lnam");
            log("  Available names: " + names.names());
            return;
        }
        log("executeHandlerIfExists(" + handlerName + "): nameId=" + nameId);

        for (ScriptChunk script : movieFile.getScripts()) {
            for (ScriptChunk.Handler handler : script.handlers()) {
                String hName = names.getName(handler.nameId());
                if (handler.nameId() == nameId) {
                    log("Executing: " + handlerName + " (script#" + script.id() + ", " +
                        handler.instructions().size() + " instructions)");
                    try {
                        vm.execute(script, handler, new Datum[0]);
                        log("Execution completed successfully");
                    } catch (Exception e) {
                        log("Error in " + handlerName + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                    return;
                }
            }
        }
        log("executeHandlerIfExists(" + handlerName + "): no handler found with nameId=" + nameId);
    }

    private void goToLabel(String label) {
        if (score == null) return;
        int frame = score.getFrameByLabel(label);
        if (frame > 0) {
            log("go \"" + label + "\" -> frame " + frame);
            goToFrame(frame);
        } else {
            log("Label not found: " + label);
        }
    }

    private void goToFrame(int frame) {
        currentFrame = Math.max(1, Math.min(frame, lastFrame));
        updateFrameLabel();
        renderFrame();
        executeHandlerIfExists("enterFrame");
    }

    private void play() {
        if (playing) return;
        playing = true;
        playButton.setEnabled(false);
        log("Play");

        if (playTimer != null) {
            playTimer.stop();
        }
        int interval = Math.max(1, 1000 / tempo);
        playTimer = new javax.swing.Timer(interval, e -> tick());
        playTimer.start();
    }

    private void stop() {
        if (!playing) return;
        playing = false;
        playButton.setEnabled(true);
        log("Stop");

        if (playTimer != null) {
            playTimer.stop();
            playTimer = null;
        }
    }

    private void tick() {
        currentFrame++;
        if (currentFrame > lastFrame) {
            currentFrame = 1;
        }
        updateFrameLabel();
        renderFrame();
        executeHandlerIfExists("enterFrame");
    }

    private void prevFrame() {
        if (currentFrame > 1) {
            currentFrame--;
            updateFrameLabel();
            renderFrame();
        }
    }

    private void nextFrame() {
        if (currentFrame < lastFrame) {
            currentFrame++;
            updateFrameLabel();
            renderFrame();
            executeHandlerIfExists("enterFrame");
        }
    }

    private void updateFrameLabel() {
        frameLabel.setText("Frame: " + currentFrame + " / " + lastFrame);
    }

    private void renderFrame() {
        if (score == null) {
            stagePanel.setSprites(Collections.emptyList());
            return;
        }

        Score.Frame frame = score.getFrame(currentFrame);
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

            // Get bitmap
            info.image = getBitmap(info.castLib, info.castMember);

            renderSprites.add(info);
        }

        stagePanel.setSprites(renderSprites);
    }

    private BufferedImage getBitmap(int castLibNum, int memberNum) {
        int effectiveCastLib = castLibNum > 0 ? castLibNum : 1;
        String key = effectiveCastLib + ":" + memberNum;

        if (bitmapCache.containsKey(key)) {
            return bitmapCache.get(key);
        }

        try {
            CastLib castLib = castManager.getCast(effectiveCastLib);
            if (castLib == null) return null;

            CastMemberChunk member = castLib.getMember(memberNum);
            if (member == null || !member.isBitmap()) return null;

            BitmapInfo bitmapInfo = BitmapInfo.parse(member.specificData());

            // Find BITD chunk
            KeyTableChunk keyTable = movieFile.getKeyTable();
            if (keyTable == null) return null;

            BitmapChunk bitmapChunk = null;
            for (KeyTableChunk.KeyTableEntry entry : keyTable.getEntriesForOwner(member.id())) {
                String fourcc = entry.fourccString();
                if (fourcc.equals("BITD") || fourcc.equals("DTIB")) {
                    Chunk chunk = movieFile.getChunk(entry.sectionId());
                    if (chunk instanceof BitmapChunk bc) {
                        bitmapChunk = bc;
                        break;
                    }
                }
            }

            if (bitmapChunk == null) return null;

            // Get palette
            Palette palette;
            int paletteId = bitmapInfo.paletteId();
            if (paletteId < 0) {
                palette = Palette.getBuiltIn(paletteId);
            } else {
                palette = Palette.getBuiltIn(Palette.SYSTEM_MAC);
            }

            // Decode bitmap
            boolean bigEndian = movieFile.getEndian() == ByteOrder.BIG_ENDIAN;
            ConfigChunk config = movieFile.getConfig();
            int directorVersion = config != null ? config.directorVersion() : 500;

            Bitmap bitmap = BitmapDecoder.decode(
                bitmapChunk.data(),
                bitmapInfo.width(),
                bitmapInfo.height(),
                bitmapInfo.bitDepth(),
                palette,
                true,
                bigEndian,
                directorVersion
            );

            // Convert to BufferedImage
            int[] pixels = bitmap.getPixels();
            BufferedImage image = new BufferedImage(
                bitmap.getWidth(), bitmap.getHeight(), BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, bitmap.getWidth(), bitmap.getHeight(), pixels, 0, bitmap.getWidth());

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

            // White background
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, getWidth(), getHeight());

            // Render sprites
            for (SpriteRenderInfo sprite : sprites) {
                if (sprite.image != null) {
                    // Apply blend
                    Composite oldComposite = g2d.getComposite();
                    if (sprite.blend < 100) {
                        g2d.setComposite(AlphaComposite.getInstance(
                            AlphaComposite.SRC_OVER, sprite.blend / 100f));
                    }

                    g2d.drawImage(sprite.image, sprite.x, sprite.y, null);
                    g2d.setComposite(oldComposite);
                } else {
                    // Draw placeholder
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

            // If a file was passed as argument, load it
            if (args.length > 0) {
                player.loadMovie(new File(args[0]));
            }
        });
    }
}
