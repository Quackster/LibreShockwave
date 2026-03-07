package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.input.InputState;
import com.libreshockwave.player.render.StageRenderer;
import com.libreshockwave.player.render.SpriteRegistry;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.Datum;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Simulates clicking on the OK button using Player.onMouseDown/onMouseUp.
 * Run: java -cp "..." com.libreshockwave.player.ClickTraceTest
 */
public class ClickTraceTest {

    private static final String MOVIE_PATH = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";

    public static void main(String[] args) throws Exception {
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

        // Run ticks until login dialog is up
        for (int tick = 0; tick < 500; tick++) {
            boolean alive = player.tick();
            if (!alive) break;
            Thread.sleep(5);
        }

        CastLibManager clm = player.getCastLibManager();
        StageRenderer renderer = player.getStageRenderer();
        SpriteRegistry registry = renderer.getSpriteRegistry();

        // Find the OK button sprite
        List<SpriteState> dynamicSprites = registry.getDynamicSprites();
        SpriteState okButton = null;
        for (SpriteState ss : dynamicSprites) {
            CastMember dm = clm.getDynamicMember(ss.getEffectiveCastLib(), ss.getEffectiveCastMember());
            if (dm != null) {
                String name = dm.getName();
                System.out.printf("  ch=%-3d name='%s' behaviors=%d pos=(%d,%d) %dx%d%n",
                        ss.getChannel(), name, ss.getScriptInstanceList().size(),
                        ss.getLocH(), ss.getLocV(), ss.getWidth(), ss.getHeight());
                if (name != null && name.contains("login_ok")) {
                    okButton = ss;
                }
            }
        }

        if (okButton == null) {
            System.err.println("ERROR: Could not find OK button sprite!");
            return;
        }

        // Calculate center of OK button
        int cx = okButton.getLocH() + okButton.getWidth() / 2;
        int cy = okButton.getLocV() + okButton.getHeight() / 2;
        System.out.printf("%nOK button: ch=%d center=(%d,%d)%n", okButton.getChannel(), cx, cy);

        // Use the proper Player API path (like the browser does)
        System.err.println("\n========== MOUSE DOWN via Player.onMouseDown at (" + cx + "," + cy + ") ==========");
        player.onMouseDown(cx, cy, false);
        System.err.println("clickOnSprite = " + player.getInputState().getClickOnSprite());

        // Tick to process mouseDown
        System.err.println("========== TICK (process mouseDown) ==========");
        player.tick();
        System.err.println("========== TICK DONE ==========");

        // Mouse up
        System.err.println("\n========== MOUSE UP via Player.onMouseUp at (" + cx + "," + cy + ") ==========");
        player.onMouseUp(cx, cy, false);

        // Tick to process mouseUp
        System.err.println("========== TICK (process mouseUp) ==========");
        player.tick();
        System.err.println("========== MOUSEUP TICK DONE ==========");

        System.out.println("\nDone.");
    }
}
