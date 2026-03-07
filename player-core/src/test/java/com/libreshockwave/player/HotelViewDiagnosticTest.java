package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.render.*;
import com.libreshockwave.vm.DebugConfig;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Diagnostic: dump full hotel view sprite list and render PNG.
 */
public class HotelViewDiagnosticTest {

    private static final String MOVIE_PATH = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final String OUTPUT_DIR = "build/hotel-view-diag";

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

        int maxTicks = 500;
        int maxSprites = 0;
        int maxSpriteTick = 0;

        boolean errorDialogCaptured = false;
        for (int tick = 0; tick < maxTicks; tick++) {
            boolean alive = player.tick();
            if (!alive) break;

            int frame = player.getCurrentFrame();
            List<RenderSprite> sprites = renderer.getSpritesForFrame(frame);
            if (sprites.size() > maxSprites) {
                maxSprites = sprites.size();
                maxSpriteTick = tick;
            }
            // Capture error dialog phase (window sprites appear around tick 10-30)
            if (!errorDialogCaptured && sprites.size() >= 8 && tick >= 10) {
                // Warmup bake to trigger async bitmap decode
                FrameSnapshot.capture(renderer, frame, "warmup", baker, player);
                Thread.sleep(2000); // Wait for async decoders
                // Now bake with decoded bitmaps
                FrameSnapshot errSnap = FrameSnapshot.capture(renderer, frame, "error-dialog", baker, player);
                Bitmap errImg = errSnap.renderFrame(RenderType.SOFTWARE);
                ImageIO.write(errImg.toBufferedImage(), "png",
                        new File(OUTPUT_DIR + "/error_dialog.png"));
                System.out.printf("Captured error dialog at tick %d with %d sprites%n", tick, sprites.size());
                errorDialogCaptured = true;
            }
            // Simulate ~20fps tempo so real-time timeouts (delay 500ms) can fire
            Thread.sleep(5);
        }

        System.out.printf("Max sprites: %d at tick %d%n", maxSprites, maxSpriteTick);
        System.out.printf("Current frame: %d, bg=0x%06X, stageImg=%s%n",
                player.getCurrentFrame(), renderer.getBackgroundColor(), renderer.hasStageImage());

        int frame = player.getCurrentFrame();
        List<RenderSprite> sprites = renderer.getSpritesForFrame(frame);

        System.out.printf("%nTotal sprites: %d (sorted by locZ/channel)%n", sprites.size());
        System.out.println("---");
        for (RenderSprite s : sprites) {
            String memberInfo = s.getMemberName() != null ? s.getMemberName() : "null";
            if (s.getCastMember() != null) {
                memberInfo += " [" + s.getCastMember().memberType() + "]";
            }
            if (s.getDynamicMember() != null) {
                memberInfo += " [dyn:" + s.getDynamicMember().getMemberType() + "]";
            }
            System.out.printf("  ch=%d z=%d type=%s pos=(%d,%d) %dx%d ink=%d(%s) blend=%d visible=%s fg=0x%06X bg=0x%06X hasFg=%s hasBg=%s member=%s%n",
                    s.getChannel(), s.getLocZ(), s.getType(),
                    s.getX(), s.getY(), s.getWidth(), s.getHeight(),
                    s.getInk(), s.getInkMode(), s.getBlend(), s.isVisible(),
                    s.getForeColor() & 0xFFFFFF, s.getBackColor() & 0xFFFFFF,
                    s.hasForeColor(), s.hasBackColor(),
                    memberInfo);
        }

        // Check missing channel 37 (fountain)
        var ch37state = renderer.getSpriteRegistry().get(37);
        if (ch37state != null) {
            System.out.printf("ch37: visible=%s castLib=%d castMember=%d hasDyn=%s ink=%d%n",
                    ch37state.isVisible(), ch37state.getEffectiveCastLib(),
                    ch37state.getEffectiveCastMember(), ch37state.hasDynamicMember(),
                    ch37state.getInk());
        } else {
            System.out.println("ch37: NOT IN REGISTRY");
        }

        // Check specific member lookups
        System.out.println("\n--- Member lookup checks ---");
        for (String name : new String[]{"brassivesiputousb", "Logo", "habbo ES fountain1", "corner_element"}) {
            com.libreshockwave.vm.Datum ref = clm.getMemberByName(0, name);
            System.out.printf("  '%s': %s%n", name, ref != null && !ref.isVoid() ? ref.toStr() : "NOT FOUND");
        }
        // Check what castLib 16 member 52 resolves to
        var m52 = clm.getCastMember(16, 52);
        System.out.printf("  castLib=16 member=52: %s%n", m52 != null ? m52.name() : "null");
        // Check sprite state for ch=33
        var ch33state = renderer.getSpriteRegistry().get(33);
        if (ch33state != null) {
            System.out.printf("  ch33 sprite: castLib=%d castMember=%d hasDyn=%s%n",
                    ch33state.getEffectiveCastLib(), ch33state.getEffectiveCastMember(),
                    ch33state.hasDynamicMember());
        }

        // Trigger async bitmap decode by baking once, then wait for decoders
        System.out.println("\n--- Triggering bitmap decode ---");
        FrameSnapshot.capture(renderer, frame, "warmup", baker, player);
        Thread.sleep(2000);  // Wait for async bitmap decoders

        // Now bake again with decoded bitmaps
        System.out.println("--- Rendering ---");
        FrameSnapshot snapshot = FrameSnapshot.capture(renderer, frame, "hotel-view", baker, player);
        for (RenderSprite bs : snapshot.sprites()) {
            Bitmap bk = bs.getBakedBitmap();
            String bakeInfo = bk != null ? bk.getWidth() + "x" + bk.getHeight() : "NULL";
            System.out.printf("  baked ch=%d %s type=%s ink=%s member=%s%n",
                    bs.getChannel(), bakeInfo, bs.getType(), bs.getInkMode(), bs.getMemberName());
        }

        // Dump light1 bitmap details
        System.out.println("\n--- Light1 bitmap analysis ---");
        for (RenderSprite bs : snapshot.sprites()) {
            if ("light1".equals(bs.getMemberName())) {
                Bitmap bk = bs.getBakedBitmap();
                if (bk != null) {
                    int[] px = bk.getPixels();
                    int transparent = 0, white = 0, black = 0, other = 0;
                    int minR = 255, minG = 255, minB = 255, maxR = 0, maxG = 0, maxB = 0;
                    for (int p : px) {
                        int a = (p >>> 24);
                        if (a == 0) { transparent++; continue; }
                        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                        if (r == 255 && g == 255 && b == 255) white++;
                        else if (r == 0 && g == 0 && b == 0) black++;
                        else other++;
                        minR = Math.min(minR, r); minG = Math.min(minG, g); minB = Math.min(minB, b);
                        maxR = Math.max(maxR, r); maxG = Math.max(maxG, g); maxB = Math.max(maxB, b);
                    }
                    System.out.printf("  baked: %dx%d transparent=%d white=%d black=%d other=%d%n",
                            bk.getWidth(), bk.getHeight(), transparent, white, black, other);
                    System.out.printf("  color range: R[%d-%d] G[%d-%d] B[%d-%d]%n",
                            minR, maxR, minG, maxG, minB, maxB);
                    // Sample center and corners
                    int cx = bk.getWidth()/2, cy = bk.getHeight()/2;
                    for (int[] pt : new int[][]{{0,0},{cx,0},{bk.getWidth()-1,0},{0,cy},{cx,cy},{bk.getWidth()-1,cy},{0,bk.getHeight()-1},{cx,bk.getHeight()-1}}) {
                        int idx = pt[1] * bk.getWidth() + pt[0];
                        if (idx >= 0 && idx < px.length) {
                            int p = px[idx];
                            System.out.printf("  (%d,%d): ARGB=(%d,%d,%d,%d)%n", pt[0], pt[1],
                                    (p>>>24), (p>>16)&0xFF, (p>>8)&0xFF, p&0xFF);
                        }
                    }
                }
                // Also dump raw bitmap (before ink processing)
                if (bs.getCastMember() != null) {
                    var rawOpt = player.decodeBitmap(bs.getCastMember());
                    if (rawOpt.isPresent()) {
                        Bitmap raw = rawOpt.get();
                        int[] rpx = raw.getPixels();
                        int rt = 0, rw = 0, rb = 0, ro = 0;
                        for (int p : rpx) {
                            int a = (p >>> 24);
                            if (a == 0) { rt++; continue; }
                            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                            if (r == 255 && g == 255 && b == 255) rw++;
                            else if (r == 0 && g == 0 && b == 0) rb++;
                            else ro++;
                        }
                        System.out.printf("  raw: %dx%d bitDepth=%d transparent=%d white=%d black=%d other=%d%n",
                                raw.getWidth(), raw.getHeight(), raw.getBitDepth(), rt, rw, rb, ro);
                        // BitmapInfo
                        byte[] sd = bs.getCastMember().specificData();
                        if (sd != null && sd.length >= 10) {
                            var bi = com.libreshockwave.cast.BitmapInfo.parse(sd);
                            System.out.printf("  BitmapInfo: %dx%d bitDepth=%d useAlpha=%s paletteId=%d%n",
                                    bi.width(), bi.height(), bi.bitDepth(), bi.useAlpha(), bi.paletteId());
                        }
                        // Sample raw center
                        int cx = raw.getWidth()/2, cy = raw.getHeight()/2;
                        int idx = cy * raw.getWidth() + cx;
                        if (idx >= 0 && idx < rpx.length) {
                            int p = rpx[idx];
                            System.out.printf("  raw center (%d,%d): ARGB=(%d,%d,%d,%d)%n", cx, cy,
                                    (p>>>24), (p>>16)&0xFF, (p>>8)&0xFF, p&0xFF);
                        }
                        // Save raw as PNG
                        ImageIO.write(raw.toBufferedImage(), "png", new File(OUTPUT_DIR + "/light1_raw.png"));
                    }
                }
                break;
            }
        }

        // Dump individual window sprite bitmaps for debugging
        System.out.println("\n--- Window sprite bitmap dumps ---");
        for (RenderSprite bs : snapshot.sprites()) {
            Bitmap bk = bs.getBakedBitmap();
            if (bk != null && bs.getMemberName() != null && (
                    bs.getMemberName().contains("login_b_login_b") ||
                    bs.getMemberName().contains("login_b_back") ||
                    bs.getMemberName().contains("login_a_login_a_title") ||
                    bs.getMemberName().contains("login_b_login_b_title") ||
                    bs.getMemberName().contains("login_b_login_ok") ||
                    bs.getMemberName().contains("login_a_login_a") ||
                    bs.getMemberName().startsWith("login_a_back") ||
                    bs.getMemberName().startsWith("login_name") ||
                    bs.getMemberName().startsWith("login_password"))) {
                String safeName = bs.getMemberName().replaceAll("[^a-zA-Z0-9_]", "_");
                ImageIO.write(bk.toBufferedImage(), "png",
                        new File(OUTPUT_DIR + "/sprite_" + safeName + ".png"));
                // Sample some pixels
                int[] px = bk.getPixels();
                int white = 0, black = 0, other = 0;
                for (int p : px) {
                    int rgb = p & 0xFFFFFF;
                    int a = (p >>> 24);
                    if (a == 0) continue;
                    if (rgb == 0xFFFFFF) white++;
                    else if (rgb == 0x000000) black++;
                    else other++;
                }
                System.out.printf("  %s: %dx%d white=%d black=%d other=%d%n",
                        bs.getMemberName(), bk.getWidth(), bk.getHeight(), white, black, other);
            }
        }

        Bitmap rendered = snapshot.renderFrame(RenderType.SOFTWARE);
        ImageIO.write(rendered.toBufferedImage(), "png",
                new File(OUTPUT_DIR + "/hotel_view.png"));

        // Check pixel content
        int[] px = rendered.getPixels();
        int nonBlack = 0, tealish = 0;
        for (int p : px) {
            if (p != 0xFF000000 && p != 0) nonBlack++;
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            if (g > 150 && b > 180 && r < 150) tealish++;
        }
        System.out.printf("%nPixel stats: nonBlack=%d/%d tealish=%d%n", nonBlack, px.length, tealish);
        System.out.println("Output: " + OUTPUT_DIR + "/hotel_view.png");

        player.shutdown();
    }
}
