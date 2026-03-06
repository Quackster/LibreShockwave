package com.libreshockwave.player.simulator;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.render.FrameSnapshot;

import javax.imageio.ImageIO;
import java.io.File;
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
                "sw1", "site.url=http://www.habbo.co.uk;url.prefix=http://www.habbo.co.uk",
                "sw2", "connection.info.host=localhost;connection.info.port=30001",
                "sw3", "client.reload.url=http://localhost/",
                "sw4", "connection.mus.host=localhost;connection.mus.port=38101",
                "sw5", "external.variables.txt=http://localhost/gamedata/external_variables.txt;" +
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

                int second = frameIndex / ticksPerCapture;
                String filename = String.format("%s/frame_%04d.png", outputDir, second);
                ImageIO.write(snap.renderFrame().toBufferedImage(), "png", new File(filename));
                System.out.println("Saved " + filename +
                        " (frame " + snap.frameNumber() + ", " + snap.sprites().size() + " sprites)");
            }

            frameIndex++;
            Thread.sleep(msPerTick);
        }

        player.shutdown();
        System.out.println("Done. Saved " + (frameIndex / ticksPerCapture) + " frames.");
    }
}
