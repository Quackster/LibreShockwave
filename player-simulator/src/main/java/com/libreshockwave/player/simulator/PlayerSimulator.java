package com.libreshockwave.player.simulator;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.DebugConfig;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class PlayerSimulator {

    private static final String MOVIE_PATH = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final String OUTPUT_DIR = "frames";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--test-debug".equals(args[0])) {
            testDebugPlayback();
            return;
        }

        String moviePath = args.length > 0 ? args[0] : MOVIE_PATH;
        String outputDir = args.length > 1 ? args[1] : OUTPUT_DIR;

        DebugConfig.setDebugPlaybackEnabled(true);

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

    /**
     * Test that DebugConfig.setDebugPlaybackEnabled toggles Lingo debug output.
     * Loads Habbo, runs ticks with debug ON then OFF, and verifies output differs.
     */
    private static void testDebugPlayback() throws Exception {
        System.out.println("=== DebugConfig playback test ===");
        System.out.println("Loading: " + MOVIE_PATH);

        DirectorFile file = DirectorFile.load(Path.of(MOVIE_PATH));
        Player player = new Player(file);
        player.setExternalParams(Map.of(
                "sw1", "site.url=http://www.habbo.co.uk;url.prefix=http://www.habbo.co.uk",
                "sw2", "connection.info.host=localhost;connection.info.port=30001",
                "sw3", "client.reload.url=http://localhost/",
                "sw4", "connection.mus.host=localhost;connection.mus.port=38101",
                "sw5", "external.variables.txt=http://localhost/gamedata/external_variables.txt;" +
                       "external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));
        player.preloadAllCasts();
        player.play();

        PrintStream realOut = System.out;
        PrintStream realErr = System.err;
        var vm = player.getVM();

        // Phase 1: debug ENABLED — call put() via VM, capture stdout
        DebugConfig.setDebugPlaybackEnabled(true);
        ByteArrayOutputStream enabledOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(enabledOut));
        System.setErr(new PrintStream(enabledOut));

        vm.callHandler("put", List.of(Datum.of("hello from put")));
        // Also run a few ticks to capture any script-driven put() calls
        for (int i = 0; i < 20; i++) player.tick();

        System.setOut(realOut);
        System.setErr(realErr);
        String onOut = enabledOut.toString();

        // Phase 2: debug DISABLED — same put() call, capture stdout
        DebugConfig.setDebugPlaybackEnabled(false);
        ByteArrayOutputStream disabledOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(disabledOut));
        System.setErr(new PrintStream(disabledOut));

        vm.callHandler("put", List.of(Datum.of("hello from put")));
        for (int i = 0; i < 20; i++) player.tick();

        System.setOut(realOut);
        System.setErr(realErr);
        String offOut = disabledOut.toString();

        player.shutdown();

        // Report results
        boolean onHasPut = onOut.contains("hello from put");
        boolean offHasPut = offOut.contains("hello from put");

        System.out.println();
        System.out.println("--- Debug ENABLED ---");
        System.out.println("stdout bytes: " + onOut.length());
        System.out.println("Has put() output: " + onHasPut);
        if (!onOut.isEmpty()) {
            String[] lines = onOut.split("\n");
            int show = Math.min(lines.length, 10);
            for (int i = 0; i < show; i++) System.out.println("  " + lines[i]);
            if (lines.length > show) System.out.println("  ... (" + (lines.length - show) + " more lines)");
        }

        System.out.println();
        System.out.println("--- Debug DISABLED ---");
        System.out.println("stdout bytes: " + offOut.length());
        System.out.println("Has put() output: " + offHasPut);

        System.out.println();
        if (onHasPut && !offHasPut) {
            System.out.println("PASS: DebugConfig toggle works — put() prints when enabled, silent when disabled");
        } else if (!onHasPut) {
            System.out.println("FAIL: put() did not produce output even when debug enabled");
        } else {
            System.out.println("FAIL: put() output present even when debug disabled");
        }
    }
}
