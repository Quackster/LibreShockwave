package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.input.HitTester;
import com.libreshockwave.player.render.*;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.datum.Datum;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Diagnostic test for click/hit testing on Habbo window elements.
 * Tests that text fields, buttons, and links in the login dialog are hittable.
 *
 * Run: ./gradlew player-core:test --tests "*HitTestDiagnostic*" -i
 * Or:  java -cp ... com.libreshockwave.player.HitTestDiagnosticTest
 */
public class HitTestDiagnosticTest {

    private static final String MOVIE_PATH = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final String OUTPUT_DIR = "build/hit-test-diag";

    public static void main(String[] args) throws Exception {
        new File(OUTPUT_DIR).mkdirs();
        DebugConfig.setDebugPlaybackEnabled(true);

        DirectorFile file = DirectorFile.load(Path.of(MOVIE_PATH));
        Player player = new Player(file);
        player.setExternalParams(Map.of(
                "sw1", "site.url=http://www.habbo.co.uk;url.prefix=http://www.habbo.co.uk",
                "sw2", "connection.info.host=localhost;connection.info.port=30001",
                "sw3", "client.reload.url=https://sandbox.h4bbo.net/",
                "sw4", "connection.mus.host=localhost;connection.mus.port=38101",
                "sw5", "external.variables.txt=http://localhost/gamedata/external_variables.txt;external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));
        player.getNetManager().setLocalHttpRoot("C:/xampp/htdocs");
        player.preloadAllCasts();
        player.play();

        CastLibManager clm = player.getCastLibManager();
        SpriteBaker baker = new SpriteBaker(player.getBitmapCache(), clm, player);
        StageRenderer renderer = player.getStageRenderer();
        SpriteRegistry registry = renderer.getSpriteRegistry();

        // Run ticks until we have a good number of dynamic sprites (login dialog)
        int maxTicks = 500;
        int bestDynamicCount = 0;
        int bestTick = 0;
        for (int tick = 0; tick < maxTicks; tick++) {
            boolean alive = player.tick();
            if (!alive) break;

            int dynamicCount = registry.getDynamicSprites().size();
            if (dynamicCount > bestDynamicCount) {
                bestDynamicCount = dynamicCount;
                bestTick = tick;
            }

            // Simulate tempo
            Thread.sleep(5);
        }

        System.out.printf("Best dynamic sprite count: %d at tick %d%n", bestDynamicCount, bestTick);

        int frame = player.getCurrentFrame();

        // ============================================================
        // 1. DUMP ALL DYNAMIC SPRITES WITH FULL DETAILS
        // ============================================================
        System.out.println("\n========================================");
        System.out.println("ALL DYNAMIC SPRITES");
        System.out.println("========================================");

        List<SpriteState> dynamicSprites = registry.getDynamicSprites();
        for (SpriteState ss : dynamicSprites) {
            int ch = ss.getChannel();
            int cm = ss.getEffectiveCastMember();
            int cl = ss.getEffectiveCastLib();
            CastMember dm = clm.getDynamicMember(cl, cm);

            String memberInfo;
            if (dm != null) {
                memberInfo = String.format("type=%s name='%s' editable=%s",
                        dm.getMemberType(), dm.getName(), dm.isEditable());
                if (dm.getMemberType() != null) {
                    memberInfo += String.format(" w=%s h=%s",
                            dm.getProp("width"), dm.getProp("height"));
                }
            } else {
                memberInfo = "NO_DYNAMIC_MEMBER";
            }

            System.out.printf("  ch=%-3d pos=(%d,%d) size=%dx%d locZ=%d vis=%s ink=%d puppet=%s " +
                            "hasDynMember=%s behaviors=%d castLib=%d member=%d %s%n",
                    ch,
                    ss.getLocH(), ss.getLocV(),
                    ss.getWidth(), ss.getHeight(),
                    ss.getLocZ(),
                    ss.isVisible(),
                    ss.getInk(),
                    ss.isPuppet(),
                    ss.hasDynamicMember(),
                    ss.getScriptInstanceList().size(),
                    cl, cm,
                    memberInfo);
        }

        // ============================================================
        // 2. BAKE SPRITES AND TEST HIT DETECTION
        // ============================================================
        System.out.println("\n========================================");
        System.out.println("BAKED SPRITES (render order)");
        System.out.println("========================================");

        // Trigger async decode warmup
        FrameSnapshot.capture(renderer, frame, "warmup", baker, player);
        Thread.sleep(2000);

        // Now bake for real
        FrameSnapshot snapshot = FrameSnapshot.capture(renderer, frame, "hit-test", baker, player);
        List<RenderSprite> bakedSprites = snapshot.sprites();

        for (RenderSprite rs : bakedSprites) {
            String bakeInfo = rs.getBakedBitmap() != null ?
                    rs.getBakedBitmap().getWidth() + "x" + rs.getBakedBitmap().getHeight() : "NULL";
            System.out.printf("  ch=%-3d pos=(%d,%d) size=%dx%d locZ=%d vis=%s ink=%s type=%s baked=%s member=%s%n",
                    rs.getChannel(),
                    rs.getX(), rs.getY(),
                    rs.getWidth(), rs.getHeight(),
                    rs.getLocZ(),
                    rs.isVisible(),
                    rs.getInkMode(),
                    rs.getType(),
                    bakeInfo,
                    rs.getMemberName());
        }

        // ============================================================
        // 3. RENDER AND SAVE IMAGE FOR VISUAL INSPECTION
        // ============================================================
        Bitmap rendered = snapshot.renderFrame(RenderType.SOFTWARE);
        ImageIO.write(rendered.toBufferedImage(), "png",
                new File(OUTPUT_DIR + "/hit_test_frame.png"));
        System.out.printf("%nRendered frame to %s/hit_test_frame.png%n", OUTPUT_DIR);

        // ============================================================
        // 4. HIT TEST AT GRID OF POINTS ACROSS THE STAGE
        // ============================================================
        System.out.println("\n========================================");
        System.out.println("HIT TEST GRID (every 20px across stage)");
        System.out.println("========================================");

        int stageW = renderer.getStageWidth();
        int stageH = renderer.getStageHeight();

        // Find the approximate dialog location by scanning for high-channel sprites
        int dialogLeft = stageW, dialogTop = stageH, dialogRight = 0, dialogBottom = 0;
        for (RenderSprite rs : bakedSprites) {
            if (rs.getChannel() >= 50 && rs.isVisible() && rs.getWidth() > 10) {
                dialogLeft = Math.min(dialogLeft, rs.getX());
                dialogTop = Math.min(dialogTop, rs.getY());
                dialogRight = Math.max(dialogRight, rs.getX() + rs.getWidth());
                dialogBottom = Math.max(dialogBottom, rs.getY() + rs.getHeight());
            }
        }

        if (dialogRight > dialogLeft) {
            System.out.printf("Dialog area estimate: (%d,%d)-(%d,%d)%n",
                    dialogLeft, dialogTop, dialogRight, dialogBottom);

            // Test hit detection in a grid within the dialog area
            System.out.println("\nHit map (channel numbers at each grid point):");
            int step = 10;
            // Header
            System.out.printf("%5s", "");
            for (int x = dialogLeft; x < dialogRight; x += step) {
                System.out.printf("%4d", x);
            }
            System.out.println();

            for (int y = dialogTop; y < dialogBottom; y += step) {
                System.out.printf("%4d ", y);
                for (int x = dialogLeft; x < dialogRight; x += step) {
                    int hit = HitTester.hitTest(renderer, frame, x, y);
                    if (hit > 0) {
                        System.out.printf("%4d", hit);
                    } else {
                        System.out.printf("   .");
                    }
                }
                System.out.println();
            }
        }

        // ============================================================
        // 5. TEST SPECIFIC POINTS THAT SHOULD HIT UI ELEMENTS
        // ============================================================
        System.out.println("\n========================================");
        System.out.println("SPECIFIC HIT TESTS");
        System.out.println("========================================");

        // For each dynamic sprite that's a text or button, test its center
        for (SpriteState ss : dynamicSprites) {
            if (!ss.isVisible() || !ss.hasDynamicMember()) continue;
            int w = ss.getWidth();
            int h = ss.getHeight();
            if (w <= 0 || h <= 0) {
                System.out.printf("  ch=%d: ZERO DIMENSIONS (%dx%d) - UNHITTABLE%n",
                        ss.getChannel(), w, h);
                continue;
            }

            // Center of the sprite
            int cx = ss.getLocH() + w / 2;
            int cy = ss.getLocV() + h / 2;

            int hit = HitTester.hitTest(renderer, frame, cx, cy);
            RenderSprite.SpriteType hitType = HitTester.hitTestType(renderer, frame, cx, cy);

            CastMember dm = clm.getDynamicMember(ss.getEffectiveCastLib(), ss.getEffectiveCastMember());
            String typeStr = dm != null ? String.valueOf(dm.getMemberType()) : "?";

            boolean correctHit = (hit == ss.getChannel());
            System.out.printf("  ch=%-3d center=(%d,%d) type=%s → hit=%d %s%s%n",
                    ss.getChannel(), cx, cy, typeStr, hit,
                    correctHit ? "OK" : "WRONG (expected ch=" + ss.getChannel() + ")",
                    hit > 0 && !correctHit ? " (hit type=" + hitType + ")" : "");

            // If wrong, find what's blocking it
            if (!correctHit && hit > 0) {
                // Find the blocking sprite
                for (RenderSprite rs : bakedSprites) {
                    if (rs.getChannel() == hit) {
                        System.out.printf("         BLOCKED BY: ch=%d pos=(%d,%d) %dx%d locZ=%d ink=%s type=%s%n",
                                rs.getChannel(), rs.getX(), rs.getY(),
                                rs.getWidth(), rs.getHeight(),
                                rs.getLocZ(), rs.getInkMode(), rs.getType());

                        // Check alpha at hit point
                        Bitmap bmp = rs.getBakedBitmap();
                        if (bmp != null) {
                            int px = cx - rs.getX(), py = cy - rs.getY();
                            int bw = bmp.getWidth(), bh = bmp.getHeight();
                            int sw = rs.getWidth(), sh = rs.getHeight();
                            int bx = (sw > 0 && sw != bw) ? (px * bw / sw) : px;
                            int by = (sh > 0 && sh != bh) ? (py * bh / sh) : py;
                            if (bx >= 0 && bx < bw && by >= 0 && by < bh) {
                                int pixel = bmp.getPixels()[by * bw + bx];
                                int alpha = (pixel >> 24) & 0xFF;
                                System.out.printf("         Pixel at (%d,%d) bitmap(%d,%d): alpha=%d ARGB=0x%08X%n",
                                        px, py, bx, by, alpha, pixel);
                            }
                        }
                        break;
                    }
                }

                // Also check if the target sprite exists in baked list
                boolean foundInBaked = false;
                for (RenderSprite rs : bakedSprites) {
                    if (rs.getChannel() == ss.getChannel()) {
                        foundInBaked = true;
                        System.out.printf("         TARGET IN BAKED: pos=(%d,%d) %dx%d locZ=%d ink=%s%n",
                                rs.getX(), rs.getY(), rs.getWidth(), rs.getHeight(),
                                rs.getLocZ(), rs.getInkMode());
                        break;
                    }
                }
                if (!foundInBaked) {
                    System.out.printf("         TARGET NOT IN BAKED LIST!%n");
                }
            }
        }

        System.out.println("\n========================================");
        System.out.println("DONE");
        System.out.println("========================================");
    }
}
