package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderConfig;
import com.libreshockwave.player.render.RenderSprite;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.TraceListener;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/**
 * Diagnostic + Fix test for login window rendering.
 *
 * ISSUE: Login windows have completely black backgrounds while
 *        the error alert window renders correctly.
 *
 * This test runs Habbo past the error dialog into the login phase,
 * captures all window sprites, and compares error dialog sprites
 * with login window sprites to identify rendering differences.
 *
 * Key investigation areas:
 *   1. Do login window sprites get created at all?
 *   2. Do their dynamic bitmap members have content?
 *   3. Are foreColor/backColor set correctly?
 *   4. Does ink processing (Matte) work for their bitmaps?
 *   5. Does colorization produce the right colors?
 *
 * Run: ./gradlew :player-core:runLoginWindowTest
 * Requires: C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr
 */
public class LoginWindowTest {

    private static final String TEST_FILE = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final int MAX_FRAMES = 5000;

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            System.exit(1);
        }

        PrintStream out = System.out;

        out.println("╔══════════════════════════════════════════════╗");
        out.println("║  LOGIN WINDOW DIAGNOSTIC TEST                ║");
        out.println("║  Comparing error dialog vs login windows     ║");
        out.println("╚══════════════════════════════════════════════╝\n");

        // ===== PHASE 1: Load DCR =====
        out.println("=== PHASE 1: Load & Initialize ===\n");

        DirectorFile file = DirectorFile.load(path);
        Player player = new Player(file);

        int configBg = file.getConfig().stageColorRGB();
        out.printf("  Stage background: 0x%06X%n", configBg);
        out.printf("  Stage size: %dx%d%n", file.getStageWidth(), file.getStageHeight());

        player.setExternalParams(Map.of(
            "sw1", "external.variables.txt=http://localhost/gamedata/external_variables.txt;" +
                   "external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));
        player.getNetManager().setLocalHttpRoot("C:/xampp/htdocs");

        LingoVM vm = player.getVM();
        vm.setStepLimit(2_000_000);

        // ===== PHASE 2: Track execution milestones =====
        out.println("\n=== PHASE 2: Run Lingo & Track Milestones ===\n");

        boolean[] frameProxyCreated = {false};
        Set<String> milestones = new LinkedHashSet<>();
        boolean[] errorDialogReached = {false};
        boolean[] loginReached = {false};
        boolean[] buildVisualSeen = {false};
        int[] errorDialogFrame = {-1};
        int[] loginFrame = {-1};
        int[] currentFrame = {0};
        int[] buildVisualCount = {0};
        List<String> windowCreateCalls = new ArrayList<>();

        vm.setTraceListener(new TraceListener() {
            @Override
            public void onHandlerEnter(HandlerInfo info) {
                String name = info.handlerName();

                // Track key milestones
                if (name.equals("showErrorDialog")) {
                    errorDialogReached[0] = true;
                    errorDialogFrame[0] = currentFrame[0];
                    milestones.add("showErrorDialog@" + currentFrame[0]);
                }
                if (name.equals("showLogin")) {
                    loginReached[0] = true;
                    loginFrame[0] = currentFrame[0];
                    milestones.add("showLogin@" + currentFrame[0]);
                }
                if (name.equals("buildVisual")) {
                    buildVisualSeen[0] = true;
                    buildVisualCount[0]++;
                    milestones.add("buildVisual#" + buildVisualCount[0] + "@" + currentFrame[0]);
                }
                if (name.equals("createWindow")) {
                    windowCreateCalls.add("createWindow@" + currentFrame[0]);
                    milestones.add("createWindow@" + currentFrame[0]);
                }
                if (name.equals("merge")) {
                    milestones.add("merge@" + currentFrame[0]);
                }
                if (name.equals("preIndexChannels") || name.equals("showLogo")
                        || name.equals("reserveSprite") || name.equals("createLoader")
                        || name.equals("showUserFound") || name.equals("showDisconnect")
                        || name.equals("initA") || name.equals("initB")) {
                    milestones.add(name + "@" + currentFrame[0]);
                }
            }

            @Override
            public void onHandlerExit(HandlerInfo info, Datum result) {
                if (!frameProxyCreated[0]
                        && info.handlerName().equals("constructObjectManager")
                        && result instanceof Datum.ScriptInstance) {
                    frameProxyCreated[0] = true;
                    player.getTimeoutManager().createTimeout(
                            "fuse_frameProxy", Integer.MAX_VALUE, "null", result);
                }
            }

            @Override
            public void onError(String message, Exception error) {}
        });

        player.setEventListener(info -> {
            if (info.event() == PlayerEvent.ENTER_FRAME) {
                currentFrame[0] = info.frame();
            }
        });

        // Preload external casts
        out.println("  Preloading external casts...");
        player.preloadAllCasts();
        for (int i = 0; i < 50; i++) {
            int loaded = 0;
            for (var castLib : player.getCastLibManager().getCastLibs().values()) {
                if (castLib.isExternal() && castLib.isLoaded()) loaded++;
            }
            if (loaded > 0 && i >= 10) break;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        player.play();

        // Don't suppress VM noise during execution - we need diagnostics
        // Run frames, capturing snapshots at key points
        FrameSnapshot errorDialogSnapshot = null;
        FrameSnapshot loginSnapshot = null;
        FrameSnapshot latestSnapshot = null;
        int framesAfterError = 0;
        int framesAfterLogin = 0;
        int framesExecuted = 0;
        String lastException = null;

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            try {
                player.stepFrame();
                framesExecuted++;
            } catch (Exception e) {
                lastException = e.getClass().getSimpleName() + ": " + e.getMessage();
                out.println("  [stepFrame EXCEPTION at frame " + frame + "] " + lastException);
                e.printStackTrace(out);
                break;
            }

            // Capture error dialog snapshot (50 frames after detection)
            if (errorDialogReached[0] && errorDialogSnapshot == null) {
                framesAfterError++;
                if (framesAfterError == 50) {
                    errorDialogSnapshot = player.getFrameSnapshot();
                }
            }

            // Capture login snapshot (50 frames after detection)
            if (loginReached[0] && loginSnapshot == null) {
                framesAfterLogin++;
                if (framesAfterLogin == 50) {
                    loginSnapshot = player.getFrameSnapshot();
                }
            }

            // If we have both snapshots, we're done
            if (errorDialogSnapshot != null && loginSnapshot != null) {
                break;
            }

            // Keep a rolling "latest" snapshot every 100 frames after error dialog
            if (errorDialogSnapshot != null && frame % 100 == 0) {
                latestSnapshot = player.getFrameSnapshot();
            }
        }

        // If no login snapshot, use the latest available
        if (loginSnapshot == null && latestSnapshot != null) {
            loginSnapshot = latestSnapshot;
            out.println("  NOTE: showLogin not detected, using latest snapshot at frame " +
                       latestSnapshot.frameNumber());
        }

        out.println("  Frames executed: " + framesExecuted);
        if (lastException != null) {
            out.println("  STOPPED BY EXCEPTION: " + lastException);
        }
        out.println("  Milestones: " + milestones);
        out.println("  Error dialog at frame: " + errorDialogFrame[0]);
        out.println("  Login at frame: " + loginFrame[0]);
        out.println("  buildVisual calls: " + buildVisualCount[0]);
        out.println("  createWindow calls: " + windowCreateCalls);

        // ===== PHASE 3: Analyze Error Dialog Sprites (REFERENCE) =====
        out.println("\n=== PHASE 3: Error Dialog Sprites (REFERENCE - works correctly) ===\n");

        check(out, "Error dialog snapshot captured",
              errorDialogSnapshot != null, "");

        if (errorDialogSnapshot != null) {
            analyzeWindowSprites(out, errorDialogSnapshot, "error", player);
        }

        // ===== PHASE 4: Analyze Login Window Sprites (BROKEN) =====
        out.println("\n=== PHASE 4: Login Window Sprites (INVESTIGATION) ===\n");

        if (loginSnapshot != null) {
            check(out, "Login/latest snapshot captured", true,
                  "frame=" + loginSnapshot.frameNumber());
            analyzeWindowSprites(out, loginSnapshot, "login", player);
        } else {
            fail(out, "No login/latest snapshot available", "");
        }

        // ===== PHASE 5: Compare sprites between snapshots =====
        out.println("\n=== PHASE 5: Error Dialog vs Login Window Comparison ===\n");

        if (errorDialogSnapshot != null && loginSnapshot != null) {
            compareSnapshots(out, errorDialogSnapshot, loginSnapshot, player);
        }

        // ===== PHASE 6: Check all dynamic member bitmaps =====
        out.println("\n=== PHASE 6: Dynamic Member Bitmap Analysis ===\n");

        FrameSnapshot targetSnap = loginSnapshot != null ? loginSnapshot : errorDialogSnapshot;
        if (targetSnap != null) {
            analyzeDynamicMemberBitmaps(out, targetSnap, player);
        }

        // ===== PHASE 7: Render PNGs =====
        out.println("\n=== PHASE 7: Render Output ===\n");

        if (errorDialogSnapshot != null) {
            renderToPng(errorDialogSnapshot, "build/render-login-test-error-dialog.png", out);
        }
        if (loginSnapshot != null) {
            renderToPng(loginSnapshot, "build/render-login-test-login-window.png", out);
        }

        // ===== PHASE 8: Key diagnostic checks =====
        out.println("\n=== PHASE 8: Login Window Fix Validation ===\n");

        if (loginSnapshot != null) {
            validateLoginWindows(out, loginSnapshot, player);
        } else {
            fail(out, "Cannot validate login windows - no snapshot", "");
        }

        // ===== SUMMARY =====
        printSummary(out);

        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * Analyze all window sprites in a snapshot.
     */
    private static void analyzeWindowSprites(PrintStream out, FrameSnapshot snapshot,
                                              String phase, Player player) {
        List<RenderSprite> all = snapshot.sprites();
        out.println("  Total sprites: " + all.size());
        out.println("  Background: 0x" + String.format("%06X", snapshot.backgroundColor()));

        // Categorize sprites
        int windowCount = 0;
        int bgCount = 0;
        int withBakedBitmap = 0;
        int withNullBitmap = 0;

        out.println("\n  --- ALL sprites with member names ---");
        for (RenderSprite s : all) {
            String name = s.getMemberName();
            if (name == null || name.isEmpty()) {
                name = "(unnamed ch" + s.getChannel() + ")";
            }

            boolean isWindow = name.contains("error_") || name.contains("modal")
                || name.contains("login_") || name.contains("_bg_")
                || name.contains("_title") || name.contains("_text")
                || name.contains("_close") || name.contains("_drag")
                || name.contains("_ok") || name.contains("_username")
                || name.contains("_password") || name.contains("_forgotten");

            Bitmap baked = s.getBakedBitmap();
            int nonTransparent = 0;
            int totalPixels = 0;
            if (baked != null) {
                totalPixels = baked.getWidth() * baked.getHeight();
                for (int y = 0; y < baked.getHeight(); y++) {
                    for (int x = 0; x < baked.getWidth(); x++) {
                        if ((baked.getPixel(x, y) >>> 24) > 10) nonTransparent++;
                    }
                }
            }
            double visiblePct = totalPixels > 0 ? 100.0 * nonTransparent / totalPixels : 0;

            String marker = isWindow ? "WIN" : "   ";
            out.printf("  [%s] ch%-3d %-35s %4dx%-4d pos=(%d,%d) z=%d " +
                       "ink=%d blend=%d fg=0x%06X bg=0x%06X hasFg=%-5s hasBg=%-5s " +
                       "baked=%-8s visible=%.0f%%%n",
                marker, s.getChannel(), "'" + name + "'",
                s.getWidth(), s.getHeight(), s.getX(), s.getY(), s.getLocZ(),
                s.getInk(), s.getBlend(),
                s.getForeColor(), s.getBackColor(),
                s.hasForeColor(), s.hasBackColor(),
                baked != null ? baked.getWidth() + "x" + baked.getHeight() : "null",
                visiblePct);

            if (isWindow) windowCount++;
            if (baked != null) withBakedBitmap++;
            else withNullBitmap++;
        }

        out.println("\n  Summary: " + windowCount + " window sprites, " +
                   withBakedBitmap + " with baked bitmap, " +
                   withNullBitmap + " without bitmap");
    }

    /**
     * Compare two snapshots to find differences.
     */
    private static void compareSnapshots(PrintStream out, FrameSnapshot errorSnap,
                                          FrameSnapshot loginSnap, Player player) {
        // Count window sprites in each
        int errorWindowCount = 0;
        int loginWindowCount = 0;
        int errorWithContent = 0;
        int loginWithContent = 0;

        for (RenderSprite s : errorSnap.sprites()) {
            String name = s.getMemberName();
            if (name != null && (name.contains("error_") || name.contains("modal"))) {
                errorWindowCount++;
                if (s.getBakedBitmap() != null && hasVisibleContent(s.getBakedBitmap())) {
                    errorWithContent++;
                }
            }
        }

        for (RenderSprite s : loginSnap.sprites()) {
            String name = s.getMemberName();
            if (name != null && (name.contains("login_") || name.contains("_bg_"))) {
                loginWindowCount++;
                if (s.getBakedBitmap() != null && hasVisibleContent(s.getBakedBitmap())) {
                    loginWithContent++;
                }
            }
        }

        out.printf("  Error dialog: %d window sprites, %d with visible content%n",
                  errorWindowCount, errorWithContent);
        out.printf("  Login window:  %d window sprites, %d with visible content%n",
                  loginWindowCount, loginWithContent);

        // Check: do login sprites have newer buildVisual calls?
        int loginBitmapSprites = 0;
        int loginBitmapWithContent = 0;
        int loginNullBitmaps = 0;

        out.println("\n  --- Login-specific sprite analysis ---");
        for (RenderSprite s : loginSnap.sprites()) {
            if (s.getType() != RenderSprite.SpriteType.BITMAP) continue;
            String name = s.getMemberName();
            if (name == null) continue;

            // Check if this sprite's bitmap has content
            Bitmap baked = s.getBakedBitmap();
            CastMember dyn = s.getDynamicMember();

            loginBitmapSprites++;
            if (baked == null) {
                loginNullBitmaps++;
                out.printf("  NULL-BMP: ch%-3d '%-30s' dyn=%s%n",
                    s.getChannel(), name, dyn != null ? dyn.toString() : "null");
            } else if (!hasVisibleContent(baked)) {
                out.printf("  EMPTY:    ch%-3d '%-30s' baked=%dx%d ink=%d fg=0x%06X bg=0x%06X%n",
                    s.getChannel(), name, baked.getWidth(), baked.getHeight(),
                    s.getInk(), s.getForeColor(), s.getBackColor());

                // Check the raw member bitmap (before ink processing)
                if (dyn != null) {
                    Bitmap raw = dyn.getBitmap();
                    if (raw != null) {
                        boolean rawHasContent = hasVisibleContent(raw);
                        out.printf("           raw member bitmap: %dx%d hasContent=%s%n",
                            raw.getWidth(), raw.getHeight(), rawHasContent);
                    } else {
                        out.println("           raw member bitmap: NULL");
                    }
                }
            } else {
                loginBitmapWithContent++;
            }
        }

        out.printf("\n  Login bitmap sprites: %d total, %d with content, %d null bitmap%n",
                  loginBitmapSprites, loginBitmapWithContent, loginNullBitmaps);
    }

    /**
     * Analyze dynamic member bitmaps (before ink processing).
     */
    private static void analyzeDynamicMemberBitmaps(PrintStream out, FrameSnapshot snapshot,
                                                     Player player) {
        int total = 0;
        int withBitmap = 0;
        int withContent = 0;

        for (RenderSprite s : snapshot.sprites()) {
            CastMember dyn = s.getDynamicMember();
            if (dyn == null) continue;

            total++;
            Bitmap raw = dyn.getBitmap();
            if (raw == null) {
                out.printf("  ch%-3d '%-30s' type=%-8s bitmap=NULL%n",
                    s.getChannel(), s.getMemberName(),
                    dyn.getMemberType() != null ? dyn.getMemberType().getName() : "?");
                continue;
            }

            withBitmap++;
            boolean hasContent = hasVisibleContent(raw);
            if (hasContent) withContent++;

            // Sample corner pixels
            int tl = raw.getPixel(0, 0);
            int tr = raw.getPixel(Math.max(0, raw.getWidth() - 1), 0);
            int center = raw.getPixel(raw.getWidth() / 2, raw.getHeight() / 2);

            out.printf("  ch%-3d '%-30s' type=%-8s raw=%dx%d content=%s  " +
                       "TL=0x%08X TR=0x%08X CTR=0x%08X%n",
                s.getChannel(), s.getMemberName(),
                dyn.getMemberType() != null ? dyn.getMemberType().getName() : "?",
                raw.getWidth(), raw.getHeight(), hasContent,
                tl, tr, center);
        }

        out.printf("\n  Dynamic members: %d total, %d with bitmap, %d with visible content%n",
                  total, withBitmap, withContent);

        check(out, "Dynamic members have bitmaps",
              total == 0 || withBitmap > total / 2,
              String.format("%d/%d", withBitmap, total));
    }

    /**
     * Validate that login window sprites render correctly.
     */
    private static void validateLoginWindows(PrintStream out, FrameSnapshot snapshot,
                                              Player player) {
        List<RenderSprite> all = snapshot.sprites();

        // Check 1: Any window sprites exist beyond the error dialog
        List<RenderSprite> loginSprites = new ArrayList<>();
        List<RenderSprite> errorSprites = new ArrayList<>();
        for (RenderSprite s : all) {
            String name = s.getMemberName();
            if (name == null) continue;
            if (name.startsWith("login_") || name.contains("login_")) {
                loginSprites.add(s);
            }
            if (name.startsWith("error_") || name.contains("error_") || name.contains("modal")) {
                errorSprites.add(s);
            }
        }

        out.printf("  Error dialog sprites: %d%n", errorSprites.size());
        out.printf("  Login window sprites: %d%n", loginSprites.size());

        // Check 2: If login sprites exist, check their background elements
        if (!loginSprites.isEmpty()) {
            check(out, "Login window sprites created", true,
                  "count=" + loginSprites.size());

            for (RenderSprite s : loginSprites) {
                String name = s.getMemberName();
                Bitmap baked = s.getBakedBitmap();

                if (name != null && name.contains("bg_")) {
                    // Background elements should NOT be all-transparent/all-black
                    if (baked != null) {
                        double visiblePct = visiblePercent(baked);
                        boolean isContentArea = name.contains("bg_c");

                        if (isContentArea) {
                            // bg_c (content area) should be mostly white/visible
                            check(out, name + " content area has visible content (>50%)",
                                  visiblePct > 50,
                                  String.format("%.1f%% visible, fg=0x%06X, bg=0x%06X, ink=%d",
                                      visiblePct, s.getForeColor(), s.getBackColor(), s.getInk()));
                        } else {
                            // bg_a/bg_b should have some visible content
                            check(out, name + " has visible content (>10%)",
                                  visiblePct > 10,
                                  String.format("%.1f%% visible, fg=0x%06X, bg=0x%06X, ink=%d",
                                      visiblePct, s.getForeColor(), s.getBackColor(), s.getInk()));
                        }
                    } else {
                        fail(out, name + " has no baked bitmap", "");
                    }
                }
            }
        } else {
            // If no login sprites, check whether ANY new window sprites exist
            // beyond the error dialog
            int nonErrorWindowSprites = 0;
            for (RenderSprite s : all) {
                String name = s.getMemberName();
                if (name == null) continue;
                if (!name.contains("error_") && !name.contains("modal")
                        && !name.contains("Logo") && !name.contains("tausta")
                        && !name.contains("hotel") && !name.contains("foregnd")
                        && !name.contains("cloud") && !name.contains("car")
                        && !name.contains("light") && !name.contains("corner")) {
                    nonErrorWindowSprites++;
                    out.println("  Other sprite: '" + name + "' ch=" + s.getChannel() +
                               " ink=" + s.getInk() + " fg=0x" + String.format("%06X", s.getForeColor()));
                }
            }
            out.println("  Non-error, non-background sprites: " + nonErrorWindowSprites);

            // This is expected to fail until login windows are reached
            check(out, "Login window sprites exist (or showLogin reached)",
                  loginSprites.size() > 0,
                  "showLogin not detected - may need more frames or state machine fix");
        }

        // Check 3: Rendered image analysis
        out.println("\n  --- Rendered frame analysis ---");
        int w = snapshot.stageWidth();
        int h = snapshot.stageHeight();
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(new Color(snapshot.backgroundColor()));
        g.fillRect(0, 0, w, h);
        if (snapshot.stageImage() != null) {
            g.drawImage(snapshot.stageImage().toBufferedImage(), 0, 0, null);
        }

        int drawn = 0;
        for (RenderSprite sprite : all) {
            if (!sprite.isVisible()) continue;
            Composite saved = null;
            int blend = sprite.getBlend();
            if (blend >= 0 && blend < 100) {
                saved = g.getComposite();
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, blend / 100f));
            }
            if (sprite.getType() == RenderSprite.SpriteType.BITMAP && sprite.getBakedBitmap() != null) {
                BufferedImage img = sprite.getBakedBitmap().toBufferedImage();
                int sw = sprite.getWidth() > 0 ? sprite.getWidth() : img.getWidth();
                int sh = sprite.getHeight() > 0 ? sprite.getHeight() : img.getHeight();
                g.drawImage(img, sprite.getX(), sprite.getY(), sw, sh, null);
                drawn++;
            } else if (sprite.getType() == RenderSprite.SpriteType.SHAPE) {
                if (sprite.getWidth() > 0 && sprite.getHeight() > 0) {
                    int fc = sprite.getForeColor();
                    g.setColor(new Color((fc >> 16) & 0xFF, (fc >> 8) & 0xFF, fc & 0xFF));
                    g.fillRect(sprite.getX(), sprite.getY(), sprite.getWidth(), sprite.getHeight());
                    drawn++;
                }
            }
            if (saved != null) g.setComposite(saved);
        }
        g.dispose();

        // Analyze specific regions
        // Login window area: around (444, 100-350) based on Lingo source positions
        int loginRegionLeft = 400;
        int loginRegionTop = 80;
        int loginRegionRight = Math.min(w, 700);
        int loginRegionBottom = Math.min(h, 400);

        int regionTotal = 0;
        int regionNonBlack = 0;
        for (int y = loginRegionTop; y < loginRegionBottom; y++) {
            for (int x = loginRegionLeft; x < loginRegionRight; x++) {
                regionTotal++;
                int rgb = canvas.getRGB(x, y) & 0xFFFFFF;
                if (rgb != 0x000000) regionNonBlack++;
            }
        }
        double regionNonBlackPct = regionTotal > 0 ? 100.0 * regionNonBlack / regionTotal : 0;

        out.printf("  Login region (%d,%d)-(%d,%d): %.1f%% non-black%n",
                  loginRegionLeft, loginRegionTop, loginRegionRight, loginRegionBottom,
                  regionNonBlackPct);

        check(out, "Login window region is NOT all black",
              regionNonBlackPct > 5,
              String.format("%.1f%% non-black pixels in expected login area", regionNonBlackPct));
    }

    // ===== Utility methods =====

    private static boolean hasVisibleContent(Bitmap bmp) {
        if (bmp == null) return false;
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                if ((bmp.getPixel(x, y) >>> 24) > 10) return true;
            }
        }
        return false;
    }

    private static double visiblePercent(Bitmap bmp) {
        if (bmp == null) return 0;
        int total = bmp.getWidth() * bmp.getHeight();
        if (total == 0) return 0;
        int visible = 0;
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                if ((bmp.getPixel(x, y) >>> 24) > 10) visible++;
            }
        }
        return 100.0 * visible / total;
    }

    private static void check(PrintStream out, String desc, boolean pass, String detail) {
        if (pass) {
            out.println("  PASS: " + desc + (detail.isEmpty() ? "" : " (" + detail + ")"));
            passed++;
        } else {
            out.println("  FAIL: " + desc + (detail.isEmpty() ? "" : " (" + detail + ")"));
            failed++;
        }
    }

    private static void fail(PrintStream out, String desc, String detail) {
        check(out, desc, false, detail);
    }

    private static void printSummary(PrintStream out) {
        out.println("\n╔══════════════════════════════════════════════╗");
        out.printf( "║  RESULTS: %d passed, %d failed                %n", passed, failed);
        out.println("╚══════════════════════════════════════════════╝");
        if (failed > 0) {
            out.println("\nFAILED - Login window rendering has issues");
        } else {
            out.println("\nALL TESTS PASSED - Login windows render correctly");
        }
    }

    private static void renderToPng(FrameSnapshot snapshot, String outputPath, PrintStream out) {
        int w = snapshot.stageWidth() > 0 ? snapshot.stageWidth() : 720;
        int h = snapshot.stageHeight() > 0 ? snapshot.stageHeight() : 540;
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        if (RenderConfig.isAntialias()) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        } else {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        }

        g.setColor(new Color(snapshot.backgroundColor()));
        g.fillRect(0, 0, w, h);
        if (snapshot.stageImage() != null) {
            g.drawImage(snapshot.stageImage().toBufferedImage(), 0, 0, null);
        }

        int drawn = 0;
        for (RenderSprite sprite : snapshot.sprites()) {
            if (!sprite.isVisible()) continue;

            Composite saved = null;
            int blend = sprite.getBlend();
            if (blend >= 0 && blend < 100) {
                saved = g.getComposite();
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, blend / 100f));
            }

            int x = sprite.getX(), y = sprite.getY();
            int sw = sprite.getWidth(), sh = sprite.getHeight();

            if (sprite.getType() == RenderSprite.SpriteType.BITMAP) {
                Bitmap baked = sprite.getBakedBitmap();
                if (baked != null) {
                    BufferedImage img = baked.toBufferedImage();
                    int iw = sw > 0 ? sw : img.getWidth();
                    int ih = sh > 0 ? sh : img.getHeight();
                    g.drawImage(img, x, y, iw, ih, null);
                    drawn++;
                }
            } else if (sprite.getType() == RenderSprite.SpriteType.SHAPE) {
                if (sw > 0 && sh > 0) {
                    int fc = sprite.getForeColor();
                    g.setColor(new Color((fc >> 16) & 0xFF, (fc >> 8) & 0xFF, fc & 0xFF));
                    g.fillRect(x, y, sw, sh);
                    drawn++;
                }
            } else if (sprite.getType() == RenderSprite.SpriteType.TEXT
                    || sprite.getType() == RenderSprite.SpriteType.BUTTON) {
                Bitmap baked = sprite.getBakedBitmap();
                if (baked != null) {
                    BufferedImage img = baked.toBufferedImage();
                    int iw = sw > 0 ? sw : img.getWidth();
                    int ih = sh > 0 ? sh : img.getHeight();
                    g.drawImage(img, x, y, iw, ih, null);
                    drawn++;
                }
            }

            if (saved != null) {
                g.setComposite(saved);
            }
        }

        g.dispose();
        out.println("  Rendered " + outputPath + ": " + drawn + " sprites drawn");

        try {
            Path pngPath = Path.of(outputPath);
            Files.createDirectories(pngPath.getParent());
            javax.imageio.ImageIO.write(canvas, "PNG", pngPath.toFile());
            out.println("  Saved " + w + "x" + h + " to: " + pngPath.toAbsolutePath());
        } catch (Exception e) {
            out.println("  Failed to save PNG: " + e.getMessage());
        }
    }
}
