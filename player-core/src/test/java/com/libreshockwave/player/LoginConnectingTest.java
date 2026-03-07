package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.input.DirectorKeyCodes;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.StageRenderer;
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
 * Integration test: verifies that after clicking OK on the login dialog,
 * the "Connecting..." element becomes visible and the OK button hides.
 * Captures screenshots before/after to visualize the blink effect.
 *
 * Run: ./gradlew player-core:runLoginConnectingTest
 */
public class LoginConnectingTest {

    private static final String MOVIE_PATH = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final String OUTPUT_DIR = "build/login-connecting";

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

        // Track handler calls and errors
        List<String> handlerTrace = new ArrayList<>();
        List<String> errorTrace = new ArrayList<>();
        player.getVM().setTraceListener(new TraceListener() {
            @Override public boolean needsInstructionTrace() { return false; }
            @Override public void onHandlerEnter(HandlerInfo info) {
                handlerTrace.add(info.handlerName());
            }
            @Override public void onError(String message, Exception error) {
                errorTrace.add(message);
            }
        });

        // Warm up until login dialog is ready
        System.out.println("=== Warming up (500 ticks) ===");
        for (int tick = 0; tick < 500; tick++) {
            if (!player.tick()) break;
            Thread.sleep(5);
        }

        CastLibManager clm = player.getCastLibManager();
        StageRenderer renderer = player.getStageRenderer();
        SpriteRegistry registry = renderer.getSpriteRegistry();

        // --- Find login sprites ---
        SpriteState okButton = null;
        SpriteState usernameField = null;
        SpriteState passwordField = null;
        SpriteState connectingElem = null;

        List<SpriteState> dynamicSprites = registry.getDynamicSprites();
        for (SpriteState ss : dynamicSprites) {
            CastMember dm = clm.getDynamicMember(ss.getEffectiveCastLib(), ss.getEffectiveCastMember());
            if (dm != null) {
                String name = dm.getName();
                if (name == null) continue;
                if (name.contains("login_ok")) okButton = ss;
                if (name.startsWith("login_name") && dm.isEditable()) usernameField = ss;
                if (name.startsWith("login_password") && dm.isEditable()) passwordField = ss;
                if (name.contains("login_connecting")) connectingElem = ss;
            }
        }

        if (okButton == null || usernameField == null || passwordField == null) {
            System.err.println("ERROR: Could not find all login sprites!");
            return;
        }

        System.out.printf("Found: username ch=%d, password ch=%d, ok ch=%d%n",
                usernameField.getChannel(), passwordField.getChannel(), okButton.getChannel());
        if (connectingElem != null) {
            System.out.printf("Found: login_connecting ch=%d blend=%d visible=%b%n",
                    connectingElem.getChannel(), connectingElem.getBlend(), connectingElem.isVisible());
        }

        // --- Screenshot 1: Before typing ---
        saveScreenshot(player, OUTPUT_DIR + "/1_before_typing.png");
        System.out.println("Saved: 1_before_typing.png");

        // --- Type credentials ---
        System.out.println("\n=== Typing username ===");
        clickSprite(player, usernameField);
        typeString(player, "testuser");
        for (int i = 0; i < 5; i++) player.tick();

        System.out.println("=== Typing password ===");
        clickSprite(player, passwordField);
        typeString(player, "pass123");
        for (int i = 0; i < 5; i++) player.tick();

        // --- Screenshot 2: After typing, before click ---
        saveScreenshot(player, OUTPUT_DIR + "/2_after_typing.png");
        System.out.println("Saved: 2_after_typing.png");

        // --- Click OK ---
        System.out.println("\n=== Clicking OK button ===");
        handlerTrace.clear();
        errorTrace.clear();
        clickSprite(player, okButton);

        // --- Screenshot 3: Immediately after click (button down state) ---
        saveScreenshot(player, OUTPUT_DIR + "/3_after_click.png");
        System.out.println("Saved: 3_after_click.png");

        // Run ticks to let the UI update propagate
        for (int i = 0; i < 20; i++) player.tick();

        // --- Screenshot 4: After UI settles (connecting blink state 1) ---
        saveScreenshot(player, OUTPUT_DIR + "/4_connecting_blink1.png");
        System.out.println("Saved: 4_connecting_blink1.png");

        // Run more ticks to see if the blink timeout fires, tracking blink calls
        int blinkCount = 0;
        for (String h : handlerTrace) {
            if (h.equalsIgnoreCase("blinkConnection")) blinkCount++;
        }
        System.out.println("blinkConnection calls so far: " + blinkCount);
        if (connectingElem != null) {
            System.out.printf("Connecting visible=%b (blink off phase)%n", connectingElem.isVisible());
        }

        // Run ticks until connecting element becomes visible (blink "on" phase)
        boolean capturedVisible = false;
        for (int i = 0; i < 400; i++) {
            player.tick();
            Thread.sleep(10);
            if (connectingElem != null && connectingElem.isVisible()) {
                saveScreenshot(player, OUTPUT_DIR + "/5_connecting_visible.png");
                System.out.println("Saved: 5_connecting_visible.png (captured at tick " + i + ")");
                capturedVisible = true;
                break;
            }
        }
        if (!capturedVisible) {
            System.out.println("WARNING: Never saw connecting element become visible!");
            saveScreenshot(player, OUTPUT_DIR + "/5_connecting_blink2.png");
            System.out.println("Saved: 5_connecting_blink2.png (fallback)");
        }

        int blinkCount2 = 0;
        for (String h : handlerTrace) {
            if (h.equalsIgnoreCase("blinkConnection")) blinkCount2++;
        }
        System.out.println("blinkConnection calls after blink wait: " + blinkCount2);
        if (connectingElem != null) {
            System.out.printf("Connecting visible=%b%n", connectingElem.isVisible());
        }

        // --- Re-scan for login_connecting ---
        if (connectingElem == null) {
            dynamicSprites = registry.getDynamicSprites();
            for (SpriteState ss : dynamicSprites) {
                CastMember dm = clm.getDynamicMember(ss.getEffectiveCastLib(), ss.getEffectiveCastMember());
                if (dm != null) {
                    String name = dm.getName();
                    if (name != null && name.contains("login_connecting")) {
                        connectingElem = ss;
                        break;
                    }
                }
            }
        }

        // --- Check handler flow ---
        boolean sawTryLogin = false;
        boolean sawConnect = false;
        boolean sawBlinkConnection = false;
        boolean sawSetLogMode = false;
        boolean sawHide = false;
        boolean sawSetProperty = false;
        for (String h : handlerTrace) {
            if (h.equalsIgnoreCase("tryLogin")) sawTryLogin = true;
            if (h.equalsIgnoreCase("connect")) sawConnect = true;
            if (h.equalsIgnoreCase("blinkConnection")) sawBlinkConnection = true;
            if (h.equalsIgnoreCase("setLogMode")) sawSetLogMode = true;
            if (h.equalsIgnoreCase("hide")) sawHide = true;
            if (h.equalsIgnoreCase("setProperty")) sawSetProperty = true;
        }

        // Check for setLogMode error
        boolean setLogModeError = false;
        for (String err : errorTrace) {
            if (err.contains("setLogMode")) {
                setLogModeError = true;
                break;
            }
        }

        // --- Report ---
        System.out.println("\n=== HANDLER TRACE ===");
        System.out.println("tryLogin fired:       " + sawTryLogin + result(sawTryLogin));
        System.out.println("blinkConnection:      " + sawBlinkConnection + result(sawBlinkConnection));
        System.out.println("connect fired:        " + sawConnect + result(sawConnect));
        System.out.println("setLogMode fired:     " + sawSetLogMode);
        System.out.println("hide fired:           " + sawHide + result(sawHide));
        System.out.println("setProperty fired:    " + sawSetProperty + result(sawSetProperty));

        System.out.println("\n=== LINGO ERRORS ===");
        if (errorTrace.isEmpty()) {
            System.out.println("(none) OK");
        } else {
            for (String err : errorTrace) {
                System.out.println("  ERROR: " + err);
            }
        }

        System.out.println("\n=== UI STATE AFTER CLICK ===");
        System.out.printf("OK button: blend=%d visible=%b%n", okButton.getBlend(), okButton.isVisible());
        if (connectingElem != null) {
            System.out.printf("Connecting: blend=%d visible=%b ch=%d loc=(%d,%d) size=(%dx%d)%n",
                    connectingElem.getBlend(), connectingElem.isVisible(),
                    connectingElem.getChannel(),
                    connectingElem.getLocH(), connectingElem.getLocV(),
                    connectingElem.getWidth(), connectingElem.getHeight());
            CastMember connMember = clm.getDynamicMember(
                    connectingElem.getEffectiveCastLib(), connectingElem.getEffectiveCastMember());
            if (connMember != null) {
                System.out.printf("Connecting member: name='%s' type=%s text='%s' hasBitmap=%b%n",
                        connMember.getName(), connMember.getMemberType(),
                        connMember.getTextContent(),
                        connMember.getBitmap() != null);
            } else {
                System.out.println("Connecting member: null (castLib=" + connectingElem.getEffectiveCastLib()
                        + " memberNum=" + connectingElem.getEffectiveCastMember() + ")");
            }
        } else {
            System.out.println("Connecting element: NOT FOUND");
        }

        // --- Assertions ---
        System.out.println("\n=== RESULTS ===");
        boolean allPassed = true;

        allPassed &= check("tryLogin reached", sawTryLogin);
        allPassed &= check("No setLogMode error (.ilk fix)", !setLogModeError);
        allPassed &= check("hide() called on OK button", sawHide);
        allPassed &= check("OK button hidden (visible=false)",
                !okButton.isVisible());
        allPassed &= check("blinkConnection started", sawBlinkConnection);
        if (connectingElem != null) {
            allPassed &= check("Connecting blend = 100 (activated)",
                    connectingElem.getBlend() == 100);
        } else {
            allPassed &= check("Connecting element found", false);
        }

        System.out.println("\n" + (allPassed ? "ALL PASSED" : "SOME CHECKS FAILED"));
        System.out.println("\nScreenshots saved to: " + new File(OUTPUT_DIR).getAbsolutePath());
    }

    private static void saveScreenshot(Player player, String path) throws Exception {
        FrameSnapshot snap = player.getFrameSnapshot();
        ImageIO.write(snap.renderFrame().toBufferedImage(), "png", new File(path));
    }

    private static boolean check(String label, boolean pass) {
        System.out.printf("  %-40s %s%n", label, pass ? "PASS" : "FAIL");
        return pass;
    }

    private static String result(boolean ok) {
        return ok ? " OK" : " FAIL";
    }

    private static void clickSprite(Player player, SpriteState sprite) {
        int cx = sprite.getLocH() + sprite.getWidth() / 2;
        int cy = sprite.getLocV() + sprite.getHeight() / 2;
        player.onMouseDown(cx, cy, false);
        player.tick();
        player.onMouseUp(cx, cy, false);
        player.tick();
    }

    private static void typeString(Player player, String text) {
        for (char c : text.toCharArray()) {
            int javaVK;
            if (c >= 'a' && c <= 'z') {
                javaVK = 65 + (c - 'a');
            } else if (c >= 'A' && c <= 'Z') {
                javaVK = 65 + (c - 'A');
            } else if (c >= '0' && c <= '9') {
                javaVK = 48 + (c - '0');
            } else {
                javaVK = (int) c;
            }
            int directorCode = DirectorKeyCodes.fromJavaKeyCode(javaVK);
            player.onKeyDown(directorCode, String.valueOf(c), false, false, false);
            player.onKeyUp(directorCode, String.valueOf(c), false, false, false);
            player.tick();
        }
    }
}
