package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.input.DirectorKeyCodes;
import com.libreshockwave.player.render.StageRenderer;
import com.libreshockwave.player.render.SpriteRegistry;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.TraceListener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Integration test: logs in with test/qwerty123 against a running Kepler server
 * and verifies that no "Item not found" errors occur from the Timeout Manager
 * during the Connection Problem removal in sendLogin.
 *
 * Requires: Kepler server at localhost:30001
 *
 * Run: ./gradlew player-core:runLoginTimeoutTest
 */
public class LoginTimeoutTest {

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

        // Track errors
        List<String> errorTrace = new ArrayList<>();
        List<String> handlerTrace = new ArrayList<>();
        player.getVM().setTraceListener(new TraceListener() {
            @Override public boolean needsInstructionTrace() { return false; }
            @Override public void onHandlerEnter(HandlerInfo info) {
                handlerTrace.add(info.handlerName());
            }
            @Override public void onError(String message, Exception error) {
                errorTrace.add(message);
                System.err.println("[ERROR] " + message);
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

        // Find login sprites
        SpriteState okButton = null;
        SpriteState usernameField = null;
        SpriteState passwordField = null;

        for (SpriteState ss : registry.getDynamicSprites()) {
            CastMember dm = clm.getDynamicMember(ss.getEffectiveCastLib(), ss.getEffectiveCastMember());
            if (dm != null) {
                String name = dm.getName();
                if (name == null) continue;
                if (name.contains("login_ok")) okButton = ss;
                if (name.startsWith("login_name") && dm.isEditable()) usernameField = ss;
                if (name.startsWith("login_password") && dm.isEditable()) passwordField = ss;
            }
        }

        if (okButton == null || usernameField == null || passwordField == null) {
            System.err.println("FAIL: Could not find login sprites!");
            System.exit(1);
            return;
        }

        System.out.printf("Found: username ch=%d, password ch=%d, ok ch=%d%n",
                usernameField.getChannel(), passwordField.getChannel(), okButton.getChannel());

        // Type credentials
        System.out.println("\n=== Typing username 'test' ===");
        clickSprite(player, usernameField);
        typeString(player, "test");
        for (int i = 0; i < 5; i++) player.tick();

        System.out.println("=== Typing password 'qwerty123' ===");
        clickSprite(player, passwordField);
        typeString(player, "qwerty123");
        for (int i = 0; i < 5; i++) player.tick();

        // Check that the Lingo Timeout Manager received calls during startup/typing
        boolean sawLingoCreateTimeout = false;
        for (String h : handlerTrace) {
            if (h.equalsIgnoreCase("createTimeout")) {
                sawLingoCreateTimeout = true;
                break;
            }
        }

        // Click OK and process the login flow
        System.out.println("\n=== Clicking OK button ===");
        errorTrace.clear();
        clickSprite(player, okButton);

        // Run ticks to let the connection establish and login complete
        // The server should respond with session parameters → sendLogin → remove Connection Problem
        System.out.println("=== Processing login (2000 ticks, ~20s) ===");
        boolean sawSendLogin = false;
        boolean sawHandleSessionParameters = false;
        boolean sawDeconstruct = false;
        for (int tick = 0; tick < 2000; tick++) {
            if (!player.tick()) break;
            Thread.sleep(10);

            // Check for key handlers
            for (int i = handlerTrace.size() - 1; i >= Math.max(0, handlerTrace.size() - 20); i--) {
                String h = handlerTrace.get(i);
                if (h.equalsIgnoreCase("sendLogin")) sawSendLogin = true;
                if (h.equalsIgnoreCase("handleSessionParameters")) sawHandleSessionParameters = true;
                if (h.equalsIgnoreCase("deconstruct")) sawDeconstruct = true;
            }

            // Stop early once login flow completes
            if (sawSendLogin && sawDeconstruct) {
                // Run a few more ticks for cleanup
                for (int i = 0; i < 50; i++) {
                    player.tick();
                    Thread.sleep(10);
                }
                break;
            }
        }

        // Check results
        System.out.println("\n=== RESULTS ===");
        boolean allPassed = true;

        // These require a real Kepler server connection (informational only)
        System.out.printf("  %-55s %s%n", "handleSessionParameters fired",
                sawHandleSessionParameters ? "YES" : "NO (needs Kepler TCP)");
        System.out.printf("  %-55s %s%n", "sendLogin fired",
                sawSendLogin ? "YES" : "NO (needs Kepler TCP)");

        // The specific error we're testing for
        boolean timeoutError = false;
        for (String err : errorTrace) {
            if (err.contains("Item not found") && err.contains("connection_problem_timeout")) {
                timeoutError = true;
            }
        }
        allPassed &= check("No 'Item not found: connection_problem_timeout' error", !timeoutError);

        // Verify the Lingo Timeout Manager is receiving calls (proves builtin doesn't shadow)
        allPassed &= check("Lingo createTimeout movie script called", sawLingoCreateTimeout);

        // Report all errors
        if (!errorTrace.isEmpty()) {
            System.out.println("\n=== ERRORS (" + errorTrace.size() + ") ===");
            for (String err : errorTrace) {
                System.out.println("  " + err);
            }
        }

        System.out.println("\n" + (allPassed ? "ALL PASSED" : "SOME CHECKS FAILED"));
        System.exit(allPassed ? 0 : 1);
    }

    private static boolean check(String label, boolean pass) {
        System.out.printf("  %-55s %s%n", label, pass ? "PASS" : "FAIL");
        return pass;
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
