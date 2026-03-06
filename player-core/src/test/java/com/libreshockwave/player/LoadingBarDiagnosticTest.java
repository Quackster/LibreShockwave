package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.render.*;
import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Diagnostic test: trace why the loading bar doesn't appear under the Sulake logo.
 */
public class LoadingBarDiagnosticTest {

    private static final String MOVIE_PATH = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final String OUTPUT_DIR = "build/loading-bar-diag";

    public static void main(String[] args) throws Exception {
        new File(OUTPUT_DIR).mkdirs();
        DebugConfig.setDebugPlaybackEnabled(true);

        System.out.println("=== Loading Bar Diagnostic Test ===");

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

        // Check if key members exist before playback
        System.out.println("\n--- Checking members in cast ---");
        CastLibManager clm = player.getCastLibManager();
        for (String name : new String[]{"system.window", "error.window", "System Props", "Logo",
                "Loading Bar Class", "Special Services Class", "Window Manager Class"}) {
            Datum ref = clm.getMemberByName(0, name);
            System.out.printf("  member '%s': %s%n", name,
                    ref != null && !ref.isVoid() ? ref.toStr() : "NOT FOUND");
        }

        System.out.println("\n--- Starting playback ---");
        player.play();

        SpriteBaker baker = new SpriteBaker(player.getBitmapCache(), clm, player);
        StageRenderer renderer = player.getStageRenderer();

        int maxTicks = 100;
        int maxSprites = 0;

        for (int tick = 0; tick < maxTicks; tick++) {
            boolean alive = player.tick();
            if (!alive) break;

            int frame = player.getCurrentFrame();
            List<RenderSprite> sprites = renderer.getSpritesForFrame(frame);
            int spriteCount = sprites.size();
            boolean hasStageImage = renderer.hasStageImage();

            if (spriteCount > maxSprites) {
                maxSprites = spriteCount;
                System.out.printf("  [tick %d] sprites: %d (frame=%d, stageImg=%s)%n",
                        tick, spriteCount, frame, hasStageImage);
                for (RenderSprite s : sprites) {
                    // Check bitmap data
                    String bmpInfo = "no-bmp";
                    Bitmap baked = null;
                    if (s.getDynamicMember() != null) {
                        Bitmap dynBmp = s.getDynamicMember().getBitmap();
                        if (dynBmp != null) {
                            int[] px = dynBmp.getPixels();
                            int nonZero = 0;
                            int nonBlackRaw = 0;
                            if (px != null) {
                                for (int p : px) {
                                    if (p != 0) nonZero++;
                                    if (p != 0xFF000000 && p != 0) nonBlackRaw++;
                                }
                            }
                            bmpInfo = String.format("dynBmp=%dx%d nonZero=%d/%d nonBlack=%d",
                                    dynBmp.getWidth(), dynBmp.getHeight(), nonZero, px != null ? px.length : 0, nonBlackRaw);
                            // Dump first few edge pixels for debugging
                            if (px != null && px.length > 0) {
                                System.out.printf("      edge pixels: [0]=0x%08X [1]=0x%08X [w-1]=0x%08X [w]=0x%08X%n",
                                        px[0], px.length > 1 ? px[1] : 0, px.length > dynBmp.getWidth()-1 ? px[dynBmp.getWidth()-1] : 0,
                                        px.length > dynBmp.getWidth() ? px[dynBmp.getWidth()] : 0);
                            }
                        } else {
                            bmpInfo = "dynMember-but-null-bmp";
                        }
                    } else if (s.getCastMember() != null) {
                        bmpInfo = "castMember=" + s.getCastMember().id();
                    }
                    System.out.printf("    ch=%d member=%s type=%s pos=(%d,%d) %dx%d ink=%d blend=%d [%s]%n",
                            s.getChannel(), s.getMemberName(), s.getType(),
                            s.getX(), s.getY(), s.getWidth(), s.getHeight(),
                            s.getInk(), s.getBlend(), bmpInfo);
                }
            }

            // Save EVERY tick during loading phase to capture loading bar
            if (tick <= 12) {
                try {
                    FrameSnapshot snapshot = FrameSnapshot.capture(renderer, frame,
                            "tick=" + tick, baker, player);
                    // Check baked sprites
                    for (RenderSprite bs : snapshot.sprites()) {
                        Bitmap bk = bs.getBakedBitmap();
                        if (bk != null) {
                            int[] px = bk.getPixels();
                            int nonBlack = 0;
                            if (px != null) for (int p : px) if (p != 0xFF000000 && p != 0) nonBlack++;
                            System.out.printf("    baked ch=%d: %dx%d nonBlack=%d%n",
                                    bs.getChannel(), bk.getWidth(), bk.getHeight(), nonBlack);
                        } else {
                            System.out.printf("    baked ch=%d: NULL%n", bs.getChannel());
                        }
                    }
                    Bitmap rendered = snapshot.renderFrame(RenderType.SOFTWARE);
                    ImageIO.write(rendered.toBufferedImage(), "png",
                            new File(String.format("%s/tick_%04d.png", OUTPUT_DIR, tick)));
                    // Check if any non-black pixels in render
                    int[] rp = rendered.getPixels();
                    int nonBlack = 0;
                    for (int p : rp) if (p != 0xFF000000 && p != 0) nonBlack++;
                    System.out.printf("  [tick %d] Saved: sprites=%d stageImg=%s nonBlackPx=%d%n",
                            tick, spriteCount, hasStageImage, nonBlack);
                } catch (Exception e) {
                    System.out.printf("  [tick %d] Render error: %s%n", tick, e.getMessage());
                }
            }
        }

        System.out.printf("%n=== Summary: max sprites = %d ===%n", maxSprites);
        player.shutdown();
    }
}
