package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.render.pipeline.StageRenderer;
import com.libreshockwave.player.render.SpriteRegistry;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.TraceListener;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Integration test: clicks "Create User" on the login screen and ticks
 * until the registration/age-check screen appears.
 *
 * Run: ./gradlew player-core:runHabboCreateUserTest
 */
public class HabboCreateUserTest {

    private static final String MOVIE_PATH = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final String OUTPUT_DIR = "build/create-user";

    public static void main(String[] args) throws Exception {
        new File(OUTPUT_DIR).mkdirs();
        DebugConfig.setDebugPlaybackEnabled(true);

        DirectorFile file = DirectorFile.load(Path.of(MOVIE_PATH));
        Player player = new Player(file);
        player.setExternalParams(Map.of(
                "sw1", "site.url=http://www.habbo.co.uk;url.prefix=http://www.habbo.co.uk",
                "sw2", "connection.info.host=localhost;connection.info.port=30087",
                "sw3", "client.reload.url=https://sandbox.h4bbo.net/",
                "sw4", "connection.mus.host=localhost;connection.mus.port=38101",
                "sw5", "external.variables.txt=http://localhost/gamedata/external_variables.txt;external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));
        player.getNetManager().setLocalHttpRoot("C:/xampp/htdocs");
        player.preloadAllCasts();
        player.play();

        // Track handler calls
        List<String> handlerTrace = new ArrayList<>();
        player.getVM().setTraceListener(new TraceListener() {
            @Override public boolean needsInstructionTrace() { return false; }
            @Override public void onHandlerEnter(HandlerInfo info) {
                handlerTrace.add(info.handlerName());
                // Log eventProcLogin arguments to see what tSprID is
                if (info.handlerName().equalsIgnoreCase("eventProcLogin")) {
                    System.out.println("  >>> eventProcLogin called with args: " + info.arguments());
                }
                if (info.handlerName().equalsIgnoreCase("redirectEvent")) {
                    System.out.println("  >>> redirectEvent called with args: " + info.arguments());
                }
            }
        });

        // Warm up until login dialog is ready
        System.out.println("=== Warming up (500 ticks) ===");
        for (int tick = 0; tick < 500; tick++) {
            if (!player.tick()) break;
            Thread.sleep(5);
        }

        saveScreenshot(player, OUTPUT_DIR + "/1_login_screen.png");
        System.out.println("Saved: 1_login_screen.png");

        CastLibManager clm = player.getCastLibManager();
        StageRenderer renderer = player.getStageRenderer();
        SpriteRegistry registry = renderer.getSpriteRegistry();

        // --- Find login_createUser sprite ---
        SpriteState createUserBtn = null;
        List<SpriteState> dynamicSprites = registry.getDynamicSprites();
        System.out.println("\n=== Dynamic sprites with 'login' in name ===");
        for (SpriteState ss : dynamicSprites) {
            CastMember dm = clm.getDynamicMember(ss.getEffectiveCastLib(), ss.getEffectiveCastMember());
            if (dm != null) {
                String name = dm.getName();
                if (name == null) continue;
                if (name.toLowerCase().contains("login") || name.toLowerCase().contains("create")) {
                    System.out.printf("  ch=%d name='%s' type=%s loc=(%d,%d) w=%d h=%d visible=%b blend=%d%n",
                            ss.getChannel(), name, dm.getMemberType(),
                            ss.getLocH(), ss.getLocV(), ss.getWidth(), ss.getHeight(),
                            ss.isVisible(), ss.getBlend());
                }
                if (name.contains("login_createUser") || name.contains("login_create")
                        || name.contains("create_one_here") || name.contains("createUser")) {
                    createUserBtn = ss;
                }
            }
        }

        if (createUserBtn == null) {
            System.err.println("ERROR: Could not find login_createUser sprite!");
            System.err.println("Listing ALL dynamic sprite names:");
            for (SpriteState ss : dynamicSprites) {
                CastMember dm = clm.getDynamicMember(ss.getEffectiveCastLib(), ss.getEffectiveCastMember());
                if (dm != null && dm.getName() != null) {
                    System.err.println("  " + dm.getName());
                }
            }
            return;
        }

        System.out.printf("\nFound createUser button: ch=%d loc=(%d,%d) w=%d h=%d%n",
                createUserBtn.getChannel(),
                createUserBtn.getLocH(), createUserBtn.getLocV(),
                createUserBtn.getWidth(), createUserBtn.getHeight());

        // --- Probe hit-test to find clickable area of login_createUser ---
        System.out.println("\n=== Hit-test probe for createUser area ===");
        int targetCh = createUserBtn.getChannel();
        int sx = createUserBtn.getLocH();
        int sy = createUserBtn.getLocV();
        int sw = createUserBtn.getWidth();
        int sh = createUserBtn.getHeight();
        int hitX = -1, hitY = -1;
        for (int py = sy; py < sy + sh; py += 2) {
            for (int px = sx; px < sx + sw; px += 4) {
                int hitCh = com.libreshockwave.player.input.HitTester.hitTest(
                        renderer, player.getCurrentFrame(), px, py);
                if (hitCh == targetCh) {
                    if (hitX < 0) {
                        hitX = px;
                        hitY = py;
                    }
                }
            }
        }
        if (hitX >= 0) {
            System.out.printf("Found clickable pixel for ch=%d at (%d, %d)%n", targetCh, hitX, hitY);
        } else {
            System.out.println("WARNING: No clickable pixel found for ch=" + targetCh + "!");
            System.out.println("Sprite bounds: (" + sx + "," + sy + ") " + sw + "x" + sh);
        }

        // --- Click Create User ---
        int cx = hitX >= 0 ? hitX : sx + sw / 2;
        int cy = hitY >= 0 ? hitY : sy + sh / 2;
        System.out.printf("\n=== Clicking Create User at (%d, %d) ===%n", cx, cy);
        handlerTrace.clear();
        player.onMouseDown(cx, cy, false);
        player.tick();
        player.onMouseUp(cx, cy, false);
        player.tick();

        // Tick and watch for registration-related handlers
        System.out.println("\n=== Ticking after click (up to 5000 ticks) ===");
        for (int tick = 0; tick < 5000; tick++) {
            if (!player.tick()) break;
            Thread.sleep(5);

            // Check for new registration-related sprites periodically
            if (tick == 50 || tick == 200 || tick == 500 || tick == 1000 || tick == 2000 || tick == 3000 || tick == 4999) {
                saveScreenshot(player, OUTPUT_DIR + "/2_tick_" + tick + ".png");
                System.out.println("Saved screenshot at tick " + tick);

                // List any new registration-related dynamic sprites
                List<SpriteState> currentSprites = registry.getDynamicSprites();
                for (SpriteState ss : currentSprites) {
                    CastMember dm = clm.getDynamicMember(ss.getEffectiveCastLib(), ss.getEffectiveCastMember());
                    if (dm != null && dm.getName() != null) {
                        String name = dm.getName();
                        if (name.contains("reg_") || name.contains("agree") || name.contains("age")
                                || name.contains("birth") || name.contains("coppa")) {
                            System.out.printf("  [tick %d] reg sprite: ch=%d name='%s' visible=%b%n",
                                    tick, ss.getChannel(), name, ss.isVisible());
                        }
                    }
                }
            }
        }

        // Print unique handler names from the trace
        java.util.Set<String> unique = new java.util.LinkedHashSet<>(handlerTrace);
        System.out.println("\n=== Unique handlers fired (" + unique.size() + " unique, " + handlerTrace.size() + " total) ===");
        for (String h : unique) {
            String lower = h.toLowerCase();
            if (lower.contains("eventproc") || lower.contains("login") || lower.contains("registr")
                    || lower.contains("figure") || lower.contains("coppa") || lower.contains("age")
                    || lower.contains("agree") || lower.contains("mouseup") || lower.contains("mousedown")
                    || lower.contains("show") || lower.contains("open") || lower.contains("create")) {
                System.out.println("  [KEY] " + h);
            }
        }
        System.out.println("eventProcLogin fired: " + unique.contains("eventProcLogin"));

        // Final screenshot
        saveScreenshot(player, OUTPUT_DIR + "/3_final.png");
        System.out.println("\nSaved: 3_final.png");
        System.out.println("Done.");
    }

    private static void clickSprite(Player player, SpriteState sprite) {
        int cx = sprite.getLocH() + sprite.getWidth() / 2;
        int cy = sprite.getLocV() + sprite.getHeight() / 2;
        player.onMouseDown(cx, cy, false);
        player.tick();
        player.onMouseUp(cx, cy, false);
        player.tick();
    }

    private static void saveScreenshot(Player player, String path) {
        try {
            var snap = player.getFrameSnapshot();
            ImageIO.write(snap.renderFrame().toBufferedImage(), "PNG", new File(path));
        } catch (Exception e) {
            System.err.println("Failed to save screenshot: " + e.getMessage());
        }
    }
}
