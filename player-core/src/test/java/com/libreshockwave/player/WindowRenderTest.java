package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.TraceListener;
import com.libreshockwave.vm.builtin.WindowProvider;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Diagnostic test for window and loading bar rendering.
 * Captures snapshots at key milestones and outputs build/render-windows.png.
 * Logs detailed sprite and window buffer diagnostics per frame.
 *
 * Run: ./gradlew :player-core:runWindowRenderTest
 * Requires: C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr
 */
public class WindowRenderTest {

    private static final String TEST_FILE = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final int MAX_FRAMES = 2000;
    private static final int POST_MILESTONE_FRAMES = 500;

    private static volatile int currentFrame = 0;
    private static volatile boolean createWindowReached = false;
    private static int createWindowFrame = -1;
    private static volatile boolean showErrorDialogReached = false;
    private static int showErrorDialogFrame = -1;

    public static void main(String[] args) throws IOException {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            return;
        }

        System.out.println("=== WindowRenderTest: Loading habbo.dcr ===");
        DirectorFile file = DirectorFile.load(path);
        Player player = new Player(file);

        // Stage background from config
        if (file.getConfig() != null) {
            int stageColor = file.getConfig().stageColor();
            int rgb = (stageColor & 0xFF) | ((stageColor & 0xFF) << 8) | ((stageColor & 0xFF) << 16);
            player.getStageRenderer().setBackgroundColor(rgb);
        }

        // External params for Habbo AU locale
        player.setExternalParams(Map.of(
            "sw1", "external.variables.txt=http://localhost/gamedata/external_variables.txt;" +
                   "external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));
        player.getNetManager().setLocalHttpRoot("C:/xampp/htdocs");

        LingoVM vm = player.getVM();
        vm.setStepLimit(2_000_000);

        // Suppress noisy output during frame stepping
        PrintStream originalOut = System.out;
        boolean[] frameProxyCreated = {false};

        // Track milestones
        vm.setTraceListener(new TraceListener() {
            int depth = 0;

            @Override
            public void onHandlerEnter(HandlerInfo info) {
                depth++;

                if (!createWindowReached && info.handlerName().equals("createWindow")) {
                    createWindowReached = true;
                    createWindowFrame = currentFrame;
                    originalOut.println("  [milestone] createWindow reached at frame " + currentFrame);
                }

                if (!showErrorDialogReached && info.handlerName().equals("showErrorDialog")) {
                    showErrorDialogReached = true;
                    showErrorDialogFrame = currentFrame;
                    originalOut.println("  [milestone] showErrorDialog reached at frame " + currentFrame);
                }

                // frameProxy timeout creation
                if (!frameProxyCreated[0]
                        && info.handlerName().equals("constructObjectManager")) {
                    // Will handle in onHandlerExit
                }
            }

            @Override
            public void onHandlerExit(HandlerInfo info, Datum result) {
                depth = Math.max(0, depth - 1);

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
                // Silently ignore errors during stepping
            }
        });

        // Track frame numbers
        player.setEventListener(info -> {
            if (info.event() == PlayerEvent.ENTER_FRAME) {
                currentFrame = info.frame();
            }
        });

        // Preload external casts
        System.out.println("=== Preloading external casts ===");
        int preloadCount = player.preloadAllCasts();
        System.out.println("Preloading " + preloadCount + " external casts...");

        for (int i = 0; i < 50; i++) {
            int loaded = 0, external = 0;
            for (var castLib : player.getCastLibManager().getCastLibs().values()) {
                if (castLib.isExternal()) {
                    external++;
                    if (castLib.isLoaded()) loaded++;
                }
            }
            if (loaded > 0 && i >= 10) {
                System.out.println("  " + loaded + "/" + external + " external casts loaded.");
                break;
            }
            if (i % 10 == 0) {
                System.out.println("  ... " + loaded + "/" + external + " external casts loaded");
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        // Start playback
        System.out.println("\n=== Starting playback ===\n");
        player.play();

        // Capture post-prepareMovie snapshot
        FrameSnapshot prepareSnapshot = null;
        try {
            prepareSnapshot = player.getFrameSnapshot();
            dumpSnapshot(prepareSnapshot, "POST-PREPARE");
        } catch (Exception e) {
            System.out.println("  (could not capture post-prepare snapshot: " + e.getMessage() + ")");
        }

        // Suppress VM noise during stepping
        System.setOut(new PrintStream(originalOut) {
            @Override public void println(String x) {
                if (x != null && (x.startsWith("[ALERT]") || x.startsWith("[milestone]"))) {
                    super.println(x);
                }
            }
        });

        // Step through frames, capturing snapshots at key milestones
        FrameSnapshot windowSnapshot = null;
        FrameSnapshot errorDialogSnapshot = null;
        FrameSnapshot latestWithWindows = null;
        int totalFramesStepped = 0;
        int framesAfterMilestone = 0;
        boolean milestoneStopTriggered = false;

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            try {
                player.stepFrame();
            } catch (Exception e) {
                originalOut.println("Error at frame step " + frame + ": " + e.getMessage());
                break;
            }
            totalFramesStepped++;

            // Capture snapshot
            try {
                FrameSnapshot snapshot = player.getFrameSnapshot();

                // Track window buffer appearance
                if (snapshot != null && snapshot.windowBuffers() != null && !snapshot.windowBuffers().isEmpty()) {
                    latestWithWindows = snapshot;
                    if (windowSnapshot == null) {
                        windowSnapshot = snapshot;
                        originalOut.println("  [window] First window buffer at frame " + currentFrame
                                + " (" + snapshot.windowBuffers().size() + " windows)");
                    }
                }

                // Capture after createWindow
                if (createWindowReached && windowSnapshot == null && snapshot != null) {
                    windowSnapshot = snapshot;
                }

                // Capture after showErrorDialog (give 5 frames for sprites to populate)
                if (showErrorDialogReached && errorDialogSnapshot == null
                        && totalFramesStepped > showErrorDialogFrame + 5) {
                    errorDialogSnapshot = snapshot;
                }

            } catch (Exception e) {
                // Ignore snapshot errors
            }

            // Progress
            if (totalFramesStepped % 100 == 0) {
                originalOut.println("  [progress] Frame " + totalFramesStepped + "/" + MAX_FRAMES);
            }

            // Stop after milestones
            boolean hitMilestone = createWindowReached || showErrorDialogReached;
            if (hitMilestone) {
                if (!milestoneStopTriggered) {
                    milestoneStopTriggered = true;
                    originalOut.println("  [milestone] Running " + POST_MILESTONE_FRAMES + " more frames...");
                }
                framesAfterMilestone++;
                if (framesAfterMilestone >= POST_MILESTONE_FRAMES) {
                    originalOut.println("  [stop] " + POST_MILESTONE_FRAMES + " frames after milestone.");
                    break;
                }
            }
        }

        // Restore System.out
        System.setOut(originalOut);

        // --- Diagnostic output ---
        System.out.println("\n=== DIAGNOSTIC SUMMARY ===\n");

        System.out.println("Total frames stepped: " + totalFramesStepped);
        System.out.println("createWindow reached: " + createWindowReached + " (frame " + createWindowFrame + ")");
        System.out.println("showErrorDialog reached: " + showErrorDialogReached + " (frame " + showErrorDialogFrame + ")");

        // Dump each captured snapshot
        if (prepareSnapshot != null) {
            System.out.println("\n--- POST-PREPARE snapshot ---");
            dumpSnapshot(prepareSnapshot, "POST-PREPARE");
        }
        if (windowSnapshot != null) {
            System.out.println("\n--- WINDOW snapshot (first windows) ---");
            dumpSnapshot(windowSnapshot, "FIRST-WINDOWS");
        }
        if (errorDialogSnapshot != null) {
            System.out.println("\n--- ERROR DIALOG snapshot ---");
            dumpSnapshot(errorDialogSnapshot, "ERROR-DIALOG");
        }

        // Use the best snapshot for rendering
        FrameSnapshot renderSnapshot = latestWithWindows != null ? latestWithWindows
                : errorDialogSnapshot != null ? errorDialogSnapshot
                : windowSnapshot != null ? windowSnapshot
                : prepareSnapshot;

        if (renderSnapshot != null) {
            System.out.println("\n=== Rendering to build/render-windows.png ===\n");
            renderToPng(renderSnapshot, player, "build/render-windows.png");
        } else {
            System.out.println("\nNo snapshot available for rendering.");
        }

        System.out.println("\n=== WindowRenderTest complete ===");
    }

    /**
     * Dump detailed diagnostics for a snapshot.
     */
    private static void dumpSnapshot(FrameSnapshot snapshot, String label) {
        if (snapshot == null) {
            System.out.println("  " + label + ": (null)");
            return;
        }

        System.out.println("  [" + label + "] Frame " + snapshot.frameNumber()
                + " | Stage " + snapshot.stageWidth() + "x" + snapshot.stageHeight()
                + " | bg=0x" + Integer.toHexString(snapshot.backgroundColor())
                + " | stageImage=" + (snapshot.stageImage() != null));

        // Sprites
        List<RenderSprite> sprites = snapshot.sprites();
        if (sprites == null || sprites.isEmpty()) {
            System.out.println("  [" + label + "] Sprites: (none)");
        } else {
            System.out.println("  [" + label + "] Sprites: " + sprites.size());
            for (RenderSprite s : sprites) {
                StringBuilder sb = new StringBuilder();
                sb.append("    ch").append(s.getChannel())
                  .append(": ").append(s.getType())
                  .append(" at (").append(s.getX()).append(",").append(s.getY()).append(")")
                  .append(" ").append(s.getWidth()).append("x").append(s.getHeight())
                  .append(s.isVisible() ? " VISIBLE" : " HIDDEN")
                  .append(" foreColor=").append(s.getForeColor())
                  .append("(0x").append(Integer.toHexString(s.getForeColor())).append(")")
                  .append(" backColor=").append(s.getBackColor())
                  .append("(0x").append(Integer.toHexString(s.getBackColor())).append(")")
                  .append(" ink=").append(s.getInk())
                  .append(" blend=").append(s.getBlend());

                if (s.getCastMember() != null) {
                    sb.append(" [FILE id=").append(s.getCastMember().id())
                      .append(" name=").append(s.getCastMember().name()).append("]");
                }
                if (s.getDynamicMember() != null) {
                    sb.append(" [DYN m").append(s.getDynamicMember().getMemberNumber())
                      .append(" name=").append(s.getDynamicMember().getName())
                      .append(" hasBitmap=").append(s.getDynamicMember().getBitmap() != null)
                      .append(" hasText=").append(s.getDynamicMember().getTextContent() != null)
                      .append("]");
                }
                if (s.getBakedBitmap() != null) {
                    sb.append(" [BAKED ").append(s.getBakedBitmap().getWidth())
                      .append("x").append(s.getBakedBitmap().getHeight()).append("]");
                }
                System.out.println(sb);
            }
        }

        // Window buffers
        Map<String, WindowProvider.WindowBuffer> windows = snapshot.windowBuffers();
        if (windows == null || windows.isEmpty()) {
            System.out.println("  [" + label + "] Windows: (none)");
        } else {
            System.out.println("  [" + label + "] Windows: " + windows.size());
            for (Map.Entry<String, WindowProvider.WindowBuffer> entry : windows.entrySet()) {
                WindowProvider.WindowBuffer wb = entry.getValue();
                Bitmap bmp = wb.bitmap();
                int nonTransparent = countNonTransparentPixels(bmp);
                System.out.println("    '" + entry.getKey() + "' at ("
                        + wb.x() + "," + wb.y() + ") "
                        + bmp.getWidth() + "x" + bmp.getHeight()
                        + " nonTransparentPixels=" + nonTransparent);
            }
        }

        // Z-ordering check: detect if any sprite could cover window buffers
        if (windows != null && !windows.isEmpty() && sprites != null) {
            for (Map.Entry<String, WindowProvider.WindowBuffer> wEntry : windows.entrySet()) {
                WindowProvider.WindowBuffer wb = wEntry.getValue();
                int wx = wb.x(), wy = wb.y();
                int ww = wb.bitmap().getWidth(), wh = wb.bitmap().getHeight();

                for (RenderSprite s : sprites) {
                    if (!s.isVisible()) continue;
                    int sx = s.getX(), sy = s.getY();
                    int sw = s.getWidth() > 0 ? s.getWidth() : 100;
                    int sh = s.getHeight() > 0 ? s.getHeight() : 100;

                    // Check overlap
                    if (sx < wx + ww && sx + sw > wx && sy < wy + wh && sy + sh > wy) {
                        boolean hasBaked = s.getBakedBitmap() != null;
                        System.out.println("  [Z-ORDER] ch" + s.getChannel() + " (" + s.getType()
                                + " " + sw + "x" + sh + " at " + sx + "," + sy
                                + " ink=" + s.getInk() + " backColor=" + s.getBackColor()
                                + " hasBaked=" + hasBaked
                                + ") OVERLAPS window '" + wEntry.getKey() + "'");
                    }
                }
            }
        }
    }

    /**
     * Count non-transparent pixels in a bitmap (detect if it has visible content).
     */
    private static int countNonTransparentPixels(Bitmap bmp) {
        if (bmp == null) return 0;
        int count = 0;
        int w = bmp.getWidth(), h = bmp.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = bmp.getPixel(x, y);
                if ((pixel >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Render a snapshot to a PNG file including window buffers on top.
     */
    private static void renderToPng(FrameSnapshot snapshot, Player player, String outputPath) {
        int w = snapshot.stageWidth() > 0 ? snapshot.stageWidth() : 640;
        int h = snapshot.stageHeight() > 0 ? snapshot.stageHeight() : 480;
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Background
        if (snapshot.stageImage() != null) {
            g.drawImage(snapshot.stageImage().toBufferedImage(), 0, 0, null);
        } else {
            g.setColor(new Color(snapshot.backgroundColor()));
            g.fillRect(0, 0, w, h);
        }

        // Draw sprites
        int spritesDrawn = 0;
        if (snapshot.sprites() != null) {
            for (RenderSprite sprite : snapshot.sprites()) {
                if (!sprite.isVisible()) continue;
                int x = sprite.getX(), y = sprite.getY();

                if (sprite.getType() == RenderSprite.SpriteType.BITMAP) {
                    Bitmap baked = sprite.getBakedBitmap();
                    if (baked != null) {
                        BufferedImage img = baked.toBufferedImage();
                        int sw = sprite.getWidth() > 0 ? sprite.getWidth() : img.getWidth();
                        int sh = sprite.getHeight() > 0 ? sprite.getHeight() : img.getHeight();

                        int blend = sprite.getBlend();
                        Composite oldComposite = null;
                        if (blend >= 0 && blend < 100) {
                            oldComposite = g.getComposite();
                            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, blend / 100f));
                        }

                        g.drawImage(img, x, y, sw, sh, null);

                        if (oldComposite != null) {
                            g.setComposite(oldComposite);
                        }
                        spritesDrawn++;
                        System.out.println("  Drew ch" + sprite.getChannel() + " at (" + x + "," + y + ") "
                                + sw + "x" + sh + " [BITMAP:" + sprite.getMemberName() + "]");
                    }
                } else if (sprite.getType() == RenderSprite.SpriteType.SHAPE) {
                    int sw = sprite.getWidth() > 0 ? sprite.getWidth() : 50;
                    int sh = sprite.getHeight() > 0 ? sprite.getHeight() : 50;
                    int fc = sprite.getForeColor();
                    int gray = fc > 255 ? fc : (255 - fc);
                    g.setColor(new Color((gray >> 16) & 0xFF, (gray >> 8) & 0xFF, gray & 0xFF));
                    g.fillRect(x, y, sw, sh);
                    spritesDrawn++;
                    System.out.println("  Drew ch" + sprite.getChannel() + " at (" + x + "," + y + ") "
                            + sw + "x" + sh + " [SHAPE fc=0x" + Integer.toHexString(fc) + "]");
                } else if (sprite.getType() == RenderSprite.SpriteType.TEXT
                        || sprite.getType() == RenderSprite.SpriteType.BUTTON) {
                    String text = null;
                    if (sprite.getDynamicMember() != null) {
                        text = sprite.getDynamicMember().getTextContent();
                    }
                    if ((text == null || text.isEmpty()) && sprite.getMemberName() != null) {
                        text = player.getCastLibManager().getFieldValue(sprite.getMemberName(), 0);
                    }
                    int sw = sprite.getWidth() > 0 ? sprite.getWidth() : 200;
                    int sh = sprite.getHeight() > 0 ? sprite.getHeight() : 20;
                    g.setColor(new Color(240, 240, 240));
                    g.fillRect(x, y, sw, sh);
                    g.setColor(Color.BLACK);
                    g.setFont(new Font("SansSerif", Font.PLAIN, 12));
                    if (text != null && !text.isEmpty()) {
                        g.drawString(text, x + 2, y + 14);
                    }
                    spritesDrawn++;
                    System.out.println("  Drew ch" + sprite.getChannel() + " at (" + x + "," + y + ") "
                            + sw + "x" + sh + " [TEXT:" + sprite.getMemberName() + "]");
                }
            }
        }

        // Draw window buffers ON TOP of sprites
        int windowsDrawn = 0;
        if (snapshot.windowBuffers() != null) {
            for (Map.Entry<String, WindowProvider.WindowBuffer> entry : snapshot.windowBuffers().entrySet()) {
                WindowProvider.WindowBuffer wb = entry.getValue();
                BufferedImage wbImg = wb.bitmap().toBufferedImage();
                g.drawImage(wbImg, wb.x(), wb.y(), null);
                windowsDrawn++;
                System.out.println("  Drew window '" + entry.getKey() + "' at (" + wb.x() + "," + wb.y() + ") "
                        + wbImg.getWidth() + "x" + wbImg.getHeight() + " [WINDOW]");
            }
        }

        g.dispose();

        System.out.println("  Sprites drawn: " + spritesDrawn + " | Windows drawn: " + windowsDrawn);

        try {
            Path pngPath = Path.of(outputPath);
            javax.imageio.ImageIO.write(canvas, "PNG", pngPath.toFile());
            System.out.println("  Saved " + w + "x" + h + " to: " + pngPath.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("  Failed to save PNG: " + e.getMessage());
        }
    }
}
