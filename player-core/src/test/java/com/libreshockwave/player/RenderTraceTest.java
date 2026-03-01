package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.TraceListener;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Traces rendering/drawing activity in habbo.dcr beyond startup.
 * Captures alert() calls, sprite state changes, and rendering-related handler invocations.
 *
 * Run: ./gradlew :player-core:runRenderTraceTest
 * Requires: C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr
 */
public class RenderTraceTest {

    private static final String TEST_FILE = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final int MAX_RESULT_LEN = 80;
    private static final int MAX_FRAMES = 2000;
    private static final int POST_ALERT_FRAMES = 500;
    private static final int IDLE_REPORT_INTERVAL = 50;

    // --- Milestones ---
    private static volatile boolean createCoreReached = false;
    private static volatile boolean prepareFrameFired = false;
    private static volatile boolean firstAlertReached = false;
    private static int firstAlertFrame = -1;
    private static volatile boolean firstSpritesReached = false;
    private static int firstSpritesFrame = -1;
    private static volatile boolean firstRenderErrorReached = false;
    private static int firstRenderErrorFrame = -1;
    private static String firstRenderErrorDetail = "";

    // --- New milestones ---
    private static volatile boolean alertHookSetReached = false;
    private static volatile boolean alertHookCalledReached = false;
    private static int alertHookCalledFrame = -1;
    private static volatile boolean showErrorDialogReached = false;
    private static int showErrorDialogFrame = -1;
    private static volatile boolean createWindowReached = false;
    private static int createWindowFrame = -1;

    // --- State machine tracking ---
    private static final List<String> stateTransitions = new ArrayList<>();
    private static String lastUpdateState = "";

    // --- Alert capture ---
    private static final List<String> alertMessages = new ArrayList<>();

    // --- Sprite state tracking ---
    private static final List<String> spriteStateChanges = new ArrayList<>();
    private static String lastSpriteSnapshot = "";

    // --- Rendering errors ---
    private static final Map<String, Integer> renderingErrors = new LinkedHashMap<>();

    // --- Rendering-related handler patterns ---
    private static final Pattern RENDER_HANDLER_PATTERN = Pattern.compile(
            "(?i)(alert|alertHook|puppet|puppetSprite|constructVisualizerManager|createWindow|drawRect|" +
            "image|sprite|bitmap|draw|render|display|screen|pixel|rect|quad|" +
            "visualiz|window|room|view|loading|bar|stage|updateState|showErrorDialog|download|" +
            "initAll|initThread|executeMessage|construct|resetClient|startClient|create)");

    // --- Call tree ---
    private static int[] currentFrame = {0};

    record CallEntry(int depth, boolean isEnter, String text, CallKind kind) {
        enum CallKind {
            HANDLER_ENTER, HANDLER_EXIT, ERROR, FRAME_MARKER,
            MILESTONE_MARKER, ALERT, SPRITE_CHANGE, RENDER_RELATED
        }

        @Override
        public String toString() {
            return "  ".repeat(depth) + text;
        }
    }

    public static void main(String[] args) throws IOException {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            return;
        }

        System.out.println("=== RenderTraceTest: Loading habbo.dcr ===");
        DirectorFile file = DirectorFile.load(path);
        Player player = new Player(file);

        // Set external params (Shockwave PARAM tags) — these feed the Habbo client
        // the URLs for external_variables.txt and external_texts.txt, which override
        // the Finnish defaults baked into the DCR with the AU locale entries.
        player.setExternalParams(Map.of(
            "sw1", "external.variables.txt=http://localhost/gamedata/external_variables.txt;" +
                   "external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));

        // Resolve localhost HTTP URLs to local filesystem (no web server needed)
        player.getNetManager().setLocalHttpRoot("C:/xampp/htdocs");

        LingoVM vm = player.getVM();
        vm.setStepLimit(2_000_000);

        List<CallEntry> callTree = new ArrayList<>();
        int[] depth = {0};
        boolean[] frameProxyCreated = {false};

        // --- Intercept System.out to capture [ALERT] messages ---
        PrintStream originalOut = System.out;
        ByteArrayOutputStream alertBuffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(originalOut) {
            @Override
            public void println(String x) {
                if (x != null && x.startsWith("[ALERT]")) {
                    String msg = x.substring(7).trim();
                    String alertEntry = "[Frame " + currentFrame[0] + "] " + msg;
                    alertMessages.add(alertEntry);
                    if (!firstAlertReached) {
                        firstAlertReached = true;
                        firstAlertFrame = currentFrame[0];
                    }
                    callTree.add(new CallEntry(depth[0], false,
                            "*** ALERT: " + msg + " ***",
                            CallEntry.CallKind.ALERT));
                }
                super.println(x);
            }
        });

        // --- Track frame boundaries ---
        player.setEventListener(info -> {
            if (info.event() == PlayerEvent.EXIT_FRAME) {
                callTree.add(new CallEntry(0, false,
                        "--- Frame " + info.frame() + " EXIT_FRAME ---",
                        CallEntry.CallKind.FRAME_MARKER));
            } else if (info.event() == PlayerEvent.ENTER_FRAME) {
                currentFrame[0] = info.frame();
                callTree.add(new CallEntry(0, false,
                        "--- Frame " + info.frame() + " ENTER_FRAME ---",
                        CallEntry.CallKind.FRAME_MARKER));
            } else if (info.event() == PlayerEvent.PREPARE_FRAME) {
                callTree.add(new CallEntry(0, false,
                        "--- Frame " + info.frame() + " PREPARE_FRAME ---",
                        CallEntry.CallKind.FRAME_MARKER));
            }
        });

        // --- Trace listener ---
        vm.setTraceListener(new TraceListener() {
            @Override
            public void onHandlerEnter(HandlerInfo info) {
                String argsStr = formatArgs(info.arguments());
                String label = "-> " + info.handlerName() + "(" + argsStr + ")  [" + info.scriptDisplayName() + "]";
                callTree.add(new CallEntry(depth[0], true, label, CallEntry.CallKind.HANDLER_ENTER));
                depth[0]++;

                // Detect milestone: create(#core)
                if (!createCoreReached && info.handlerName().equals("create")) {
                    for (Datum arg : info.arguments()) {
                        if (arg instanceof Datum.Symbol sym && sym.name().equalsIgnoreCase("core")) {
                            createCoreReached = true;
                            callTree.add(new CallEntry(depth[0] - 1, false,
                                    "*** MILESTONE 1: create(#core) ***",
                                    CallEntry.CallKind.MILESTONE_MARKER));
                            break;
                        }
                    }
                }

                // Detect milestone: prepareFrame on Object Manager
                if (!prepareFrameFired && info.handlerName().equals("prepareFrame")
                        && info.scriptDisplayName().contains("Object Manager")) {
                    prepareFrameFired = true;
                    callTree.add(new CallEntry(depth[0] - 1, false,
                            "*** MILESTONE 2: prepareFrame fired on Object Manager ***",
                            CallEntry.CallKind.MILESTONE_MARKER));
                }

                // Detect alert() builtin call
                if (info.handlerName().equals("alert")) {
                    String alertMsg = argsStr.isEmpty() ? "(no args)" : argsStr;
                    String alertEntry = "[Frame " + currentFrame[0] + "] " + alertMsg;
                    alertMessages.add(alertEntry);
                    if (!firstAlertReached) {
                        firstAlertReached = true;
                        firstAlertFrame = currentFrame[0];
                    }
                    callTree.add(new CallEntry(depth[0] - 1, false,
                            "*** MILESTONE 3: alert() — " + alertMsg + " ***",
                            CallEntry.CallKind.ALERT));
                }

                // Detect updateState calls (state machine progression)
                if (info.handlerName().equals("updateState")) {
                    String stateArg = argsStr.isEmpty() ? "?" : argsStr;
                    String stateKey = "updateState(" + stateArg + ")";
                    if (!stateKey.equals(lastUpdateState)) {
                        lastUpdateState = stateKey;
                        String entry = "[Frame " + currentFrame[0] + "] " + stateKey;
                        stateTransitions.add(entry);
                        callTree.add(new CallEntry(depth[0] - 1, false,
                                "*** STATE: " + stateKey + " ***",
                                CallEntry.CallKind.MILESTONE_MARKER));
                    }
                }

                // Detect alertHook handler invocation
                if (info.handlerName().equals("alertHook")) {
                    if (!alertHookCalledReached) {
                        alertHookCalledReached = true;
                        alertHookCalledFrame = currentFrame[0];
                    }
                    callTree.add(new CallEntry(depth[0] - 1, false,
                            "*** MILESTONE: alertHook(" + argsStr + ") ***",
                            CallEntry.CallKind.MILESTONE_MARKER));
                }

                // Detect showErrorDialog
                if (info.handlerName().equals("showErrorDialog")) {
                    if (!showErrorDialogReached) {
                        showErrorDialogReached = true;
                        showErrorDialogFrame = currentFrame[0];
                    }
                    callTree.add(new CallEntry(depth[0] - 1, false,
                            "*** MILESTONE: showErrorDialog(" + argsStr + ") ***",
                            CallEntry.CallKind.MILESTONE_MARKER));
                }

                // Detect createWindow
                if (info.handlerName().equals("createWindow")) {
                    if (!createWindowReached) {
                        createWindowReached = true;
                        createWindowFrame = currentFrame[0];
                    }
                    callTree.add(new CallEntry(depth[0] - 1, false,
                            "*** MILESTONE: createWindow(" + argsStr + ") ***",
                            CallEntry.CallKind.MILESTONE_MARKER));
                }

                // Track rendering-related handlers
                String combined = info.handlerName() + " " + info.scriptDisplayName();
                if (RENDER_HANDLER_PATTERN.matcher(combined).find()) {
                    callTree.add(new CallEntry(depth[0] - 1, false,
                            ">>> RENDER: " + info.handlerName() + "(" + argsStr + ") [" + info.scriptDisplayName() + "]",
                            CallEntry.CallKind.RENDER_RELATED));
                }
            }

            @Override
            public void onHandlerExit(HandlerInfo info, Datum result) {
                depth[0] = Math.max(0, depth[0] - 1);
                String resultStr = truncate(result == null ? "void" : result.toString());
                callTree.add(new CallEntry(depth[0], false,
                        "<- " + info.handlerName() + " = " + resultStr,
                        CallEntry.CallKind.HANDLER_EXIT));

                // After constructObjectManager returns, create frameProxy timeout
                if (!frameProxyCreated[0]
                        && info.handlerName().equals("constructObjectManager")
                        && result instanceof Datum.ScriptInstance) {
                    frameProxyCreated[0] = true;
                    player.getTimeoutManager().createTimeout(
                            "fuse_frameProxy", Integer.MAX_VALUE, "null", result);
                }
            }

            @Override
            public void onError(String message, Exception error) {
                callTree.add(new CallEntry(depth[0], false,
                        "!! ERROR: " + message, CallEntry.CallKind.ERROR));

                // Track rendering-related errors
                if (RENDER_HANDLER_PATTERN.matcher(message).find()
                        || message.contains("sprite") || message.contains("puppet")
                        || message.contains("image") || message.contains("draw")
                        || message.contains("member") || message.contains("cast")) {
                    renderingErrors.merge(message, 1, Integer::sum);
                    if (!firstRenderErrorReached) {
                        firstRenderErrorReached = true;
                        firstRenderErrorFrame = currentFrame[0];
                        firstRenderErrorDetail = message;
                    }
                }
            }

            private String formatArgs(List<Datum> arguments) {
                if (arguments.isEmpty()) return "";
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arguments.size(); i++) {
                    if (i > 0) sb.append(", ");
                    Datum arg = arguments.get(i);
                    if (arg instanceof Datum.Str s) {
                        String val = s.value();
                        if (val.length() > 30) val = val.substring(0, 27) + "...";
                        sb.append("\"").append(val).append("\"");
                    } else {
                        sb.append(arg);
                    }
                }
                return sb.toString();
            }
        });

        // --- Preload external casts ---
        System.out.println("=== Preloading external casts ===");
        int preloadCount = player.preloadAllCasts();
        System.out.println("Preloading " + preloadCount + " external casts...");

        Set<String> uniqueFileNames = new java.util.HashSet<>();
        for (var castLib : player.getCastLibManager().getCastLibs().values()) {
            if (castLib.isExternal()) {
                String fn = castLib.getFileName();
                if (fn != null && !fn.isEmpty()) {
                    uniqueFileNames.add(fn.toLowerCase());
                }
            }
        }
        System.out.println("Unique external cast files: " + uniqueFileNames.size());

        for (int i = 0; i < 50; i++) {
            int loaded = 0;
            int external = 0;
            for (var castLib : player.getCastLibManager().getCastLibs().values()) {
                if (castLib.isExternal()) {
                    external++;
                    if (castLib.isLoaded()) loaded++;
                }
            }
            if (loaded > 0 && i >= 10) {
                System.out.println("  " + loaded + "/" + external + " external casts loaded (proceeding).");
                break;
            }
            if (i % 10 == 0) {
                System.out.println("  ... " + loaded + "/" + external + " external casts loaded");
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        // --- Start playback ---
        System.out.println("\n=== Starting playback ===\n");
        player.play();

        int totalFramesStepped = 0;
        int framesAfterMilestone = 0;
        boolean milestoneStopTriggered = false;

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            try {
                player.stepFrame();
            } catch (Exception e) {
                System.err.println("Error at frame step " + frame + ": " + e.getMessage());
                e.printStackTrace(System.err);
                break;
            }

            totalFramesStepped++;

            // --- Check sprite state after each frame ---
            try {
                FrameSnapshot snapshot = player.getFrameSnapshot();
                if (snapshot != null && snapshot.sprites() != null && !snapshot.sprites().isEmpty()) {
                    String spriteDesc = describeSpriteState(snapshot);
                    if (!spriteDesc.equals(lastSpriteSnapshot)) {
                        lastSpriteSnapshot = spriteDesc;
                        String entry = "[Frame " + currentFrame[0] + "] " + spriteDesc;
                        spriteStateChanges.add(entry);
                        callTree.add(new CallEntry(0, false,
                                ">>> SPRITES: " + spriteDesc,
                                CallEntry.CallKind.SPRITE_CHANGE));

                        if (!firstSpritesReached) {
                            firstSpritesReached = true;
                            firstSpritesFrame = currentFrame[0];
                            callTree.add(new CallEntry(0, false,
                                    "*** MILESTONE 4: First non-empty sprite list ***",
                                    CallEntry.CallKind.MILESTONE_MARKER));
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore snapshot errors — some frames may not have valid state
            }

            // Progress logging
            if (totalFramesStepped % IDLE_REPORT_INTERVAL == 0) {
                System.out.println("  [progress] Frame " + totalFramesStepped + "/" + MAX_FRAMES
                        + " | alerts: " + alertMessages.size()
                        + " | states: " + stateTransitions.size()
                        + " | sprite changes: " + spriteStateChanges.size()
                        + " | render errors: " + renderingErrors.size()
                        + " | callTree: " + callTree.size());

                // Give background threads (HTTP downloads) time to complete/fail
                // Without this, frames step faster than HTTP timeouts can expire
                if (totalFramesStepped >= 50 && stateTransitions.size() <= 1) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                }
            }

            // Stop conditions: stop N frames after the highest milestone is reached
            boolean hitMilestone = firstAlertReached || createWindowReached || firstSpritesReached;
            if (hitMilestone) {
                if (!milestoneStopTriggered) {
                    milestoneStopTriggered = true;
                    String reason = firstAlertReached ? "alert()" :
                                    createWindowReached ? "createWindow" :
                                    "sprites";
                    System.out.println("  [milestone] " + reason + " reached at frame " + currentFrame[0]
                            + ", running " + POST_ALERT_FRAMES + " more frames...");
                }
                framesAfterMilestone++;
                if (framesAfterMilestone >= POST_ALERT_FRAMES) {
                    System.out.println("  [stop] " + POST_ALERT_FRAMES + " frames after milestone.");
                    break;
                }
            }
        }

        // Restore original System.out
        System.setOut(originalOut);

        // ========================================
        //  REPORTS
        // ========================================

        // --- ALERT MESSAGES ---
        System.out.println("\n========================================");
        System.out.println("  ALERT MESSAGES");
        System.out.println("========================================\n");
        if (alertMessages.isEmpty()) {
            System.out.println("  (no alerts captured in " + totalFramesStepped + " frames)");
        } else {
            for (String alert : alertMessages) {
                System.out.println("  " + alert);
            }
        }

        // --- SPRITE STATE CHANGES ---
        System.out.println("\n========================================");
        System.out.println("  SPRITE STATE CHANGES");
        System.out.println("========================================\n");
        if (spriteStateChanges.isEmpty()) {
            System.out.println("  (no sprite state changes in " + totalFramesStepped + " frames)");
        } else {
            for (String change : spriteStateChanges) {
                System.out.println("  " + change);
            }
        }

        // --- RENDERING ERRORS ---
        System.out.println("\n========================================");
        System.out.println("  RENDERING ERRORS (deduplicated)");
        System.out.println("========================================\n");
        if (renderingErrors.isEmpty()) {
            System.out.println("  (no rendering-related errors)");
        } else {
            for (var entry : renderingErrors.entrySet()) {
                if (entry.getValue() > 1) {
                    System.out.println("  [x" + entry.getValue() + "] " + entry.getKey());
                } else {
                    System.out.println("  " + entry.getKey());
                }
            }
        }

        // --- CONDENSED CALL TREE (rendering-relevant entries only) ---
        System.out.println("\n========================================");
        System.out.println("  CALL TREE (rendering-relevant entries)");
        System.out.println("========================================\n");
        int renderedEntries = 0;
        int skippedEntries = 0;
        for (CallEntry entry : callTree) {
            if (entry.kind() == CallEntry.CallKind.MILESTONE_MARKER
                    || entry.kind() == CallEntry.CallKind.ALERT
                    || entry.kind() == CallEntry.CallKind.SPRITE_CHANGE
                    || entry.kind() == CallEntry.CallKind.RENDER_RELATED
                    || entry.kind() == CallEntry.CallKind.ERROR) {
                if (skippedEntries > 0) {
                    System.out.println("    ... (" + skippedEntries + " entries skipped)");
                    skippedEntries = 0;
                }
                System.out.println(entry);
                renderedEntries++;
            } else {
                skippedEntries++;
            }
        }
        if (skippedEntries > 0) {
            System.out.println("    ... (" + skippedEntries + " entries skipped)");
        }
        if (renderedEntries == 0) {
            System.out.println("  (no rendering-relevant entries)");
        }

        // --- ALL ERRORS (deduplicated) ---
        System.out.println("\n========================================");
        System.out.println("  ALL ERRORS (deduplicated)");
        System.out.println("========================================\n");
        var allErrors = new LinkedHashMap<String, Integer>();
        for (CallEntry e : callTree) {
            if (e.kind() == CallEntry.CallKind.ERROR) {
                allErrors.merge(e.text(), 1, Integer::sum);
            }
        }
        if (allErrors.isEmpty()) {
            System.out.println("  (no errors)");
        } else {
            for (var entry : allErrors.entrySet()) {
                if (entry.getValue() > 1) {
                    System.out.println("  [x" + entry.getValue() + "] " + entry.getKey());
                } else {
                    System.out.println("  " + entry.getKey());
                }
            }
        }

        // --- STATE TRANSITIONS ---
        System.out.println("\n========================================");
        System.out.println("  STATE MACHINE TRANSITIONS");
        System.out.println("========================================\n");
        if (stateTransitions.isEmpty()) {
            System.out.println("  (no updateState calls in " + totalFramesStepped + " frames)");
        } else {
            for (String st : stateTransitions) {
                System.out.println("  " + st);
            }
        }

        // --- MILESTONES ---
        System.out.println("\n========================================");
        System.out.println("  MILESTONES");
        System.out.println("========================================\n");
        System.out.println("  1. create(#core):           " + (createCoreReached ? "REACHED" : "not reached"));
        System.out.println("  2. prepareFrame fired:       " + (prepareFrameFired ? "REACHED" : "not reached"));
        System.out.println("  3. first alert():            " + (firstAlertReached
                ? "REACHED (frame " + firstAlertFrame + ")"
                : "not reached in " + totalFramesStepped + " frames"));
        System.out.println("  4. first non-empty sprites:  " + (firstSpritesReached
                ? "REACHED (frame " + firstSpritesFrame + ")"
                : "not reached in " + totalFramesStepped + " frames"));
        System.out.println("  5. first rendering error:    " + (firstRenderErrorReached
                ? "REACHED (frame " + firstRenderErrorFrame + ") — " + truncate(firstRenderErrorDetail)
                : "not reached in " + totalFramesStepped + " frames"));
        System.out.println("  6. alertHook called:         " + (alertHookCalledReached
                ? "REACHED (frame " + alertHookCalledFrame + ")"
                : "not reached"));
        System.out.println("  7. showErrorDialog:          " + (showErrorDialogReached
                ? "REACHED (frame " + showErrorDialogFrame + ")"
                : "not reached"));
        System.out.println("  8. createWindow:             " + (createWindowReached
                ? "REACHED (frame " + createWindowFrame + ")"
                : "not reached"));

        System.out.println("\n  Total frames stepped: " + totalFramesStepped);
        System.out.println("  Total alerts: " + alertMessages.size());
        System.out.println("  Total state transitions: " + stateTransitions.size());
        System.out.println("  Total sprite changes: " + spriteStateChanges.size());
        System.out.println("  Total rendering errors: " + renderingErrors.size());
        System.out.println("  Call tree entries: " + callTree.size());

        // --- BITMAP DECODING VERIFICATION ---
        System.out.println("\n========================================");
        System.out.println("  BITMAP DECODING VERIFICATION");
        System.out.println("========================================\n");
        FrameSnapshot finalSnapshot = player.getFrameSnapshot();
        if (finalSnapshot != null && finalSnapshot.sprites() != null) {
            int decoded = 0;
            int failed = 0;
            for (RenderSprite sprite : finalSnapshot.sprites()) {
                CastMemberChunk member = sprite.getCastMember();
                if (member != null && sprite.getType() == RenderSprite.SpriteType.BITMAP) {
                    java.util.Optional<Bitmap> bmp = player.decodeBitmap(member);
                    if (bmp.isPresent()) {
                        Bitmap b = bmp.get();
                        System.out.println("  ch" + sprite.getChannel() + ": DECODED "
                                + b.getWidth() + "x" + b.getHeight() + " (chunkId=" + member.id()
                                + " name=" + member.name() + ")");
                        decoded++;
                    } else {
                        System.out.println("  ch" + sprite.getChannel() + ": FAILED to decode"
                                + " (chunkId=" + member.id() + " name=" + member.name() + ")");
                        failed++;
                    }
                }
            }
            System.out.println("  Decoded: " + decoded + ", Failed: " + failed);

            // --- HEADLESS RENDER TO PNG ---
            if (decoded > 0) {
                System.out.println("\n  Rendering frame to PNG...");
                int stageW = finalSnapshot.stageWidth() > 0 ? finalSnapshot.stageWidth() : 640;
                int stageH = finalSnapshot.stageHeight() > 0 ? finalSnapshot.stageHeight() : 480;
                BufferedImage canvas = new BufferedImage(stageW, stageH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = canvas.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

                // Fill background
                g.setColor(new Color(finalSnapshot.backgroundColor()));
                g.fillRect(0, 0, stageW, stageH);

                // Draw sprites
                int spritesDrawn = 0;
                for (RenderSprite sprite : finalSnapshot.sprites()) {
                    if (!sprite.isVisible()) continue;
                    CastMemberChunk m = sprite.getCastMember();
                    if (m != null && sprite.getType() == RenderSprite.SpriteType.BITMAP) {
                        java.util.Optional<Bitmap> bmp = player.decodeBitmap(m);
                        if (bmp.isPresent()) {
                            BufferedImage img = bmp.get().toBufferedImage();
                            int x = sprite.getX();
                            int y = sprite.getY();
                            int w = sprite.getWidth() > 0 ? sprite.getWidth() : img.getWidth();
                            int h = sprite.getHeight() > 0 ? sprite.getHeight() : img.getHeight();
                            g.drawImage(img, x, y, w, h, null);
                            spritesDrawn++;
                            System.out.println("  Drew ch" + sprite.getChannel() + " at (" + x + "," + y + ") "
                                    + w + "x" + h);
                        }
                    }
                }
                g.dispose();

                // Save to PNG
                Path pngPath = Path.of("build/render-output.png");
                try {
                    javax.imageio.ImageIO.write(canvas, "PNG", pngPath.toFile());
                    System.out.println("  Saved " + stageW + "x" + stageH + " render to: " + pngPath.toAbsolutePath());
                    System.out.println("  Sprites drawn: " + spritesDrawn);
                } catch (Exception e) {
                    System.err.println("  Failed to save PNG: " + e.getMessage());
                }
            }
        } else {
            System.out.println("  (no sprites in final snapshot)");
        }

        player.shutdown();
    }

    /**
     * Build a compact description of the sprite state for a frame snapshot.
     */
    private static String describeSpriteState(FrameSnapshot snapshot) {
        List<RenderSprite> sprites = snapshot.sprites();
        if (sprites.isEmpty()) return "(empty)";

        StringBuilder sb = new StringBuilder();
        sb.append(sprites.size()).append(" sprites: ");
        int shown = 0;
        for (RenderSprite sprite : sprites) {
            if (shown > 0) sb.append(", ");
            if (shown >= 5) {
                sb.append("... (").append(sprites.size() - shown).append(" more)");
                break;
            }
            sb.append("ch").append(sprite.getChannel())
              .append("(").append(sprite.getType())
              .append(" m").append(sprite.getCastMemberId())
              .append(" @").append(sprite.getX()).append(",").append(sprite.getY())
              .append(sprite.isVisible() ? "" : " HIDDEN")
              .append(")");
            shown++;
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s.length() <= MAX_RESULT_LEN) return s;
        return s.substring(0, MAX_RESULT_LEN - 3) + "...";
    }
}
