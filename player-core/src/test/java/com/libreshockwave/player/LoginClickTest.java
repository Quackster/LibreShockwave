package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.input.DirectorKeyCodes;
import com.libreshockwave.player.render.StageRenderer;
import com.libreshockwave.player.render.SpriteRegistry;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.TraceListener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Integration test: types username/password into login fields, clicks OK,
 * and verifies that tryLogin fires and processes the credentials.
 *
 * Run: java -cp "..." com.libreshockwave.player.LoginClickTest
 */
public class LoginClickTest {

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

        // --- Trace handler calls to detect tryLogin / eventProcLogin ---
        List<String> handlerTrace = new ArrayList<>();
        player.getVM().setTraceListener(new TraceListener() {
            @Override public boolean needsInstructionTrace() { return false; }
            @Override public void onHandlerEnter(HandlerInfo info) {
                handlerTrace.add(info.handlerName());
            }
        });

        // Run ticks until login dialog is up
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

        List<SpriteState> dynamicSprites = registry.getDynamicSprites();
        for (SpriteState ss : dynamicSprites) {
            CastMember dm = clm.getDynamicMember(ss.getEffectiveCastLib(), ss.getEffectiveCastMember());
            if (dm != null) {
                String name = dm.getName();
                if (name == null) continue;
                if (name.contains("login_ok")) okButton = ss;
                // Username field is a dynamically created field member named "login_name<millis>"
                if (name.startsWith("login_name") && dm.isEditable()) usernameField = ss;
                // Password field is "login_password<millis>"
                if (name.startsWith("login_password") && dm.isEditable()) passwordField = ss;
            }
        }

        if (okButton == null || usernameField == null || passwordField == null) {
            System.err.println("ERROR: Could not find all login sprites!");
            return;
        }

        System.out.printf("Found: username ch=%d, password ch=%d, ok ch=%d%n",
                usernameField.getChannel(), passwordField.getChannel(), okButton.getChannel());

        // --- Step 1: Click on username field to focus it ---
        System.out.println("\n=== Step 1: Click username field to focus ===");
        clickSprite(player, usernameField);
        System.out.println("keyboardFocusSprite = " + player.getInputState().getKeyboardFocusSprite());

        // --- Step 2: Type username ---
        System.out.println("\n=== Step 2: Type username 'testuser' ===");
        typeString(player, "testuser");
        for (int i = 0; i < 5; i++) player.tick();

        CastMember userMember = clm.getDynamicMember(
                usernameField.getEffectiveCastLib(), usernameField.getEffectiveCastMember());
        String userText = userMember != null ? userMember.getTextContent() : "<null>";
        System.out.println("Username field text: '" + userText + "'");

        // --- Step 3: Click on password field to focus it ---
        System.out.println("\n=== Step 3: Click password field to focus ===");
        clickSprite(player, passwordField);
        System.out.println("keyboardFocusSprite = " + player.getInputState().getKeyboardFocusSprite());

        // --- Step 4: Type password ---
        System.out.println("\n=== Step 4: Type password 'pass123' ===");
        typeString(player, "pass123");
        for (int i = 0; i < 5; i++) player.tick();

        CastMember pwdMember = clm.getDynamicMember(
                passwordField.getEffectiveCastLib(), passwordField.getEffectiveCastMember());
        String pwdText = pwdMember != null ? pwdMember.getTextContent() : "<null>";
        System.out.println("Password field text: '" + pwdText + "'");

        // --- Step 5: Click OK button ---
        System.out.println("\n=== Step 5: Click OK button ===");
        handlerTrace.clear();
        clickSprite(player, okButton);
        for (int i = 0; i < 10; i++) player.tick();

        // --- Results ---
        boolean sawEventProcLogin = false;
        boolean sawTryLogin = false;
        boolean sawConnect = false;
        for (String h : handlerTrace) {
            if (h.equalsIgnoreCase("eventProcLogin")) sawEventProcLogin = true;
            if (h.equalsIgnoreCase("tryLogin")) sawTryLogin = true;
            if (h.equalsIgnoreCase("connect")) sawConnect = true;
        }

        System.out.println("\n=== RESULTS ===");
        System.out.println("eventProcLogin fired: " + sawEventProcLogin + (sawEventProcLogin ? " OK" : " FAIL"));
        System.out.println("tryLogin fired:       " + sawTryLogin + (sawTryLogin ? " OK" : " FAIL"));
        System.out.println("connect fired:        " + sawConnect + (sawConnect ? " OK (login succeeded)" : " FAIL"));

        if (sawTryLogin && !sawConnect) {
            System.err.println("  Username='" + userText + "' Password='" + pwdText + "'");
            System.err.println("  tryLogin returned 0 (empty field?)");
        }

        System.out.println("\nDone.");
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
