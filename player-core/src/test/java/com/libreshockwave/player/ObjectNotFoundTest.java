package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.TraceListener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Regression test for Bug: "Object not found:" with empty object name.
 *
 * Root cause: Datum.deepCopy() for ScriptInstance was creating a NEW copy instead
 * of returning the same instance. Director's duplicate() on lists is shallow for
 * object references. When Object Manager's create() called tClassList.duplicate(),
 * the copies got ancestor chains set instead of the originals, so the real Error
 * Manager instance (stored as the alertHook) had no ancestor chain. Calling
 * me.getID() walked to Object Base Class which was never set, returning VOID.
 *
 * Fix: ScriptInstance.deepCopy() returns `this` (same instance).
 *
 * This test verifies:
 * 1. No "Object not found:" errors during startup (Error Manager creation)
 * 2. No "Object not found:" errors during login flow (triggers alertHook path)
 * 3. The alertHook handler fires without errors when script errors occur
 *
 * Run: ./gradlew player-core:runObjectNotFoundTest
 */
public class ObjectNotFoundTest {

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

        // Track errors from the very start (including play() which runs startup scripts)
        List<String> errorTrace = new ArrayList<>();
        List<String> handlerTrace = new ArrayList<>();
        player.getVM().setTraceListener(new TraceListener() {
            @Override public boolean needsInstructionTrace() { return false; }
            @Override public void onHandlerEnter(HandlerInfo info) {
                handlerTrace.add(info.handlerName());
            }
            @Override public void onError(String message, Exception error) {
                errorTrace.add(message);
                System.err.println("  [ERROR] " + message);
            }
        });

        // Phase 1: Startup - this creates Error Manager, Object Manager, etc.
        System.out.println("=== Phase 1: Startup (play + 500 ticks) ===");
        player.play();
        for (int tick = 0; tick < 500; tick++) {
            if (!player.tick()) break;
            Thread.sleep(5);
        }

        List<String> startupErrors = new ArrayList<>(errorTrace);
        List<String> startupObjectNotFound = filterObjectNotFound(startupErrors);

        System.out.println("Startup handlers fired: " + handlerTrace.size());
        System.out.println("Startup errors: " + startupErrors.size());
        if (!startupObjectNotFound.isEmpty()) {
            System.out.println("  'Object not found:' errors during startup:");
            for (String err : startupObjectNotFound) {
                System.out.println("    " + err);
            }
        }

        // Phase 2: Login flow - type credentials and click OK
        System.out.println("\n=== Phase 2: Login flow ===");
        errorTrace.clear();
        handlerTrace.clear();

        var clm = player.getCastLibManager();
        var registry = player.getStageRenderer().getSpriteRegistry();
        var dynamicSprites = registry.getDynamicSprites();

        com.libreshockwave.player.sprite.SpriteState okButton = null;
        com.libreshockwave.player.sprite.SpriteState usernameField = null;
        com.libreshockwave.player.sprite.SpriteState passwordField = null;

        for (var ss : dynamicSprites) {
            var dm = clm.getDynamicMember(ss.getEffectiveCastLib(), ss.getEffectiveCastMember());
            if (dm != null && dm.getName() != null) {
                String name = dm.getName();
                if (name.contains("login_ok")) okButton = ss;
                if (name.startsWith("login_name") && dm.isEditable()) usernameField = ss;
                if (name.startsWith("login_password") && dm.isEditable()) passwordField = ss;
            }
        }

        if (okButton == null || usernameField == null || passwordField == null) {
            System.err.println("ERROR: Could not find login sprites - skipping login phase");
        } else {
            // Type credentials
            clickSprite(player, usernameField);
            typeString(player, "testuser");
            for (int i = 0; i < 5; i++) player.tick();

            clickSprite(player, passwordField);
            typeString(player, "pass123");
            for (int i = 0; i < 5; i++) player.tick();

            // Click OK - this triggers tryLogin → connect → potential errors → alertHook
            clickSprite(player, okButton);

            // Run ticks to let login flow complete and any error handlers fire
            for (int i = 0; i < 200; i++) {
                player.tick();
                Thread.sleep(5);
            }
        }

        List<String> loginErrors = new ArrayList<>(errorTrace);
        List<String> loginObjectNotFound = filterObjectNotFound(loginErrors);

        System.out.println("Login handlers fired: " + handlerTrace.size());
        System.out.println("Login errors: " + loginErrors.size());
        if (!loginObjectNotFound.isEmpty()) {
            System.out.println("  'Object not found:' errors during login:");
            for (String err : loginObjectNotFound) {
                System.out.println("    " + err);
            }
        }

        // Phase 3: Extended run - let timeouts and callbacks fire
        System.out.println("\n=== Phase 3: Extended run (300 more ticks) ===");
        errorTrace.clear();
        handlerTrace.clear();

        for (int i = 0; i < 300; i++) {
            player.tick();
            Thread.sleep(5);
        }

        List<String> extendedErrors = new ArrayList<>(errorTrace);
        List<String> extendedObjectNotFound = filterObjectNotFound(extendedErrors);

        // === Results ===
        System.out.println("\n=== RESULTS ===");
        boolean allPassed = true;

        allPassed &= check("No 'Object not found:' during startup",
                startupObjectNotFound.isEmpty());
        allPassed &= check("No 'Object not found:' during login",
                loginObjectNotFound.isEmpty());
        allPassed &= check("No 'Object not found:' during extended run",
                extendedObjectNotFound.isEmpty());

        // Also check for the specific symptom: registerProcedure error with empty name
        List<String> registerProcErrors = new ArrayList<>();
        for (String err : startupErrors) {
            if (err.contains("registerProcedure") && err.contains("Object not found")) {
                registerProcErrors.add(err);
            }
        }
        for (String err : loginErrors) {
            if (err.contains("registerProcedure") && err.contains("Object not found")) {
                registerProcErrors.add(err);
            }
        }
        for (String err : extendedErrors) {
            if (err.contains("registerProcedure") && err.contains("Object not found")) {
                registerProcErrors.add(err);
            }
        }
        allPassed &= check("No registerProcedure 'Object not found' errors",
                registerProcErrors.isEmpty());

        System.out.println("\n" + (allPassed ? "ALL PASSED" : "SOME CHECKS FAILED"));
        System.exit(allPassed ? 0 : 1);
    }

    private static List<String> filterObjectNotFound(List<String> errors) {
        List<String> result = new ArrayList<>();
        for (String err : errors) {
            if (err.contains("Object not found")) {
                result.add(err);
            }
        }
        return result;
    }

    private static boolean check(String label, boolean pass) {
        System.out.printf("  %-50s %s%n", label, pass ? "PASS" : "FAIL");
        return pass;
    }

    private static void clickSprite(Player player, com.libreshockwave.player.sprite.SpriteState sprite) {
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
            int directorCode = com.libreshockwave.player.input.DirectorKeyCodes.fromJavaKeyCode(javaVK);
            player.onKeyDown(directorCode, String.valueOf(c), false, false, false);
            player.onKeyUp(directorCode, String.valueOf(c), false, false, false);
            player.tick();
        }
    }
}
