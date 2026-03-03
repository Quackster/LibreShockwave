package com.libreshockwave.player.simulator;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class PlayerSimulator {

    private static final String MOVIE_PATH = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final String OUTPUT_DIR = "frames";

    public static void main(String[] args) throws Exception {
        String moviePath = args.length > 0 ? args[0] : MOVIE_PATH;
        String outputDir = args.length > 1 ? args[1] : OUTPUT_DIR;

        new File(outputDir).mkdirs();

        System.out.println("Loading: " + moviePath);
        DirectorFile file = DirectorFile.load(Path.of(moviePath));
        Player player = new Player(file);

        player.setExternalParams(Map.of(
                "sw1", "external.variables.txt=http://localhost/gamedata/external_variables.txt;" +
                       "external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));

        // Preload external casts (.cct/.cst files)
        int castCount = player.preloadAllCasts();
        System.out.println("Preloading " + castCount + " external casts...");

        player.play();

        int stageW = player.getStageRenderer().getStageWidth();
        int stageH = player.getStageRenderer().getStageHeight();
        int tempo = player.getTempo();
        if (tempo <= 0) tempo = 15;

        System.out.println("Stage: " + stageW + "x" + stageH + " @ " + tempo + " fps");

        int frameIndex = 0;
        int ticksPerCapture = tempo; // capture once per second
        long msPerTick = 1000L / tempo;

        while (player.tick()) {
            if (frameIndex % ticksPerCapture == 0) {
                FrameSnapshot snap = player.getFrameSnapshot();
                BufferedImage image = renderFrame(snap, stageW, stageH);

                int second = frameIndex / ticksPerCapture;
                String filename = String.format("%s/frame_%04d.png", outputDir, second);
                ImageIO.write(image, "png", new File(filename));
                System.out.println("Saved " + filename +
                        " (frame " + snap.frameNumber() + ", " + snap.sprites().size() + " sprites)");
            }

            frameIndex++;
            Thread.sleep(msPerTick);
        }

        player.shutdown();
        System.out.println("Done. Saved " + (frameIndex / ticksPerCapture) + " frames.");
    }

    private static BufferedImage renderFrame(FrameSnapshot snap, int stageW, int stageH) {
        BufferedImage image = new BufferedImage(stageW, stageH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        // Fill background
        int bg = snap.backgroundColor();
        g.setColor(new Color(bg));
        g.fillRect(0, 0, stageW, stageH);

        // Draw stage image if present (e.g. direct pixel drawing from Lingo)
        if (snap.stageImage() != null) {
            g.drawImage(snap.stageImage().toBufferedImage(), 0, 0, null);
        }

        // Draw sprites in order (already sorted by z-order)
        for (RenderSprite sprite : snap.sprites()) {
            if (!sprite.isVisible()) continue;

            Bitmap bmp = sprite.getBakedBitmap();
            if (bmp == null) continue;

            int blend = sprite.getBlend();
            if (blend < 100) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, blend / 100f));
            } else {
                g.setComposite(AlphaComposite.SrcOver);
            }

            g.drawImage(bmp.toBufferedImage(), sprite.getX(), sprite.getY(), null);
        }

        g.dispose();
        return image;
    }
}
