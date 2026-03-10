package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.TraceListener;
import com.libreshockwave.vm.xtra.MultiuserNetBridge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Integration test: verifies that the Multiuser Xtra is registered,
 * that the Habbo login flow triggers connectToNetServer, and that
 * delivering a connection acknowledgment triggers the callback chain.
 *
 * Run: ./gradlew player-core:runMultiuserXtraTest
 */
public class MultiuserXtraTest {

    private static final String MOVIE_PATH = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";

    /** Simple test bridge that records connect/send requests and allows simulating responses. */
    static class TestBridge implements MultiuserNetBridge {
        record ConnectRequest(int instanceId, String host, int port) {}
        record SendRequest(int instanceId, String senderID, String subject, Datum content) {}

        final List<ConnectRequest> pendingConnects = new ArrayList<>();
        final List<SendRequest> pendingSends = new ArrayList<>();
        final Map<Integer, Boolean> connected = new HashMap<>();
        final Map<Integer, List<NetMessage>> pendingMessages = new HashMap<>();

        @Override public void requestConnect(int instanceId, String host, int port) {
            pendingConnects.add(new ConnectRequest(instanceId, host, port));
        }
        @Override public void requestSend(int instanceId, String senderID, String subject, Datum content) {
            pendingSends.add(new SendRequest(instanceId, senderID, subject, content));
        }
        @Override public void requestDisconnect(int instanceId) { connected.remove(instanceId); }
        @Override public boolean isConnected(int instanceId) { return connected.getOrDefault(instanceId, false); }
        @Override public List<NetMessage> pollMessages(int instanceId) {
            List<NetMessage> msgs = pendingMessages.remove(instanceId);
            return msgs != null ? msgs : List.of();
        }
        @Override public void destroyInstance(int instanceId) { connected.remove(instanceId); }

        void notifyConnected(int instanceId) {
            connected.put(instanceId, true);
            pendingMessages.computeIfAbsent(instanceId, k -> new ArrayList<>())
                    .add(new NetMessage(0, "system", "N:N:N:N:N:N:N", new Datum.Str("")));
        }
    }

    public static void main(String[] args) throws Exception {
        DebugConfig.setDebugPlaybackEnabled(true);

        DirectorFile file = DirectorFile.load(Path.of(MOVIE_PATH));
        Player player = new Player(file);

        // Set up Multiuser Xtra with test bridge
        TestBridge musBridge = new TestBridge();
        player.registerMultiuserXtra(musBridge);

        player.setExternalParams(Map.of(
                "sw1", "site.url=http://www.habbo.co.uk;url.prefix=http://www.habbo.co.uk",
                "sw2", "connection.info.host=localhost;connection.info.port=30001",
                "sw3", "client.reload.url=http://localhost/",
                "sw4", "connection.mus.host=localhost;connection.mus.port=38101",
                "sw5", "external.variables.txt=http://localhost/gamedata/external_variables.txt;" +
                       "external.texts.txt=http://localhost/gamedata/external_texts.txt"
        ));
        player.getNetManager().setLocalHttpRoot("C:/xampp/htdocs");
        player.preloadAllCasts();
        player.play();

        // Track handler calls
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

        // Type credentials and click OK to trigger Multiuser connect
        System.out.println("\n=== Typing credentials and clicking OK ===");
        var registry = player.getStageRenderer().getSpriteRegistry();
        var clm = player.getCastLibManager();

        com.libreshockwave.player.sprite.SpriteState okButton = null;
        com.libreshockwave.player.sprite.SpriteState usernameField = null;
        com.libreshockwave.player.sprite.SpriteState passwordField = null;

        for (var ss : registry.getDynamicSprites()) {
            var dm = clm.getDynamicMember(ss.getEffectiveCastLib(), ss.getEffectiveCastMember());
            if (dm != null) {
                String name = dm.getName();
                if (name == null) continue;
                if (name.contains("login_ok")) okButton = ss;
                if (name.startsWith("login_name") && dm.isEditable()) usernameField = ss;
                if (name.startsWith("login_password") && dm.isEditable()) passwordField = ss;
            }
        }

        if (okButton == null || usernameField == null || passwordField == null) {
            System.err.println("ERROR: Could not find login sprites!");
            player.shutdown();
            return;
        }

        // Type username
        clickSprite(player, usernameField);
        typeString(player, "testuser");
        for (int i = 0; i < 5; i++) player.tick();

        // Type password
        clickSprite(player, passwordField);
        typeString(player, "pass123");
        for (int i = 0; i < 5; i++) player.tick();

        // Click OK
        handlerTrace.clear();
        clickSprite(player, okButton);
        for (int i = 0; i < 50; i++) {
            player.tick();
            Thread.sleep(5);
        }

        // === Check 1: Multiuser Xtra created a connect request ===
        var pendingConnects = musBridge.pendingConnects;
        boolean hasConnect = !pendingConnects.isEmpty();
        String connectHost = hasConnect ? pendingConnects.get(0).host() : "(none)";
        int connectPort = hasConnect ? pendingConnects.get(0).port() : 0;
        int connectInstanceId = hasConnect ? pendingConnects.get(0).instanceId() : 0;

        System.out.println("\n=== MULTIUSER CONNECT ===");
        System.out.printf("Pending connects: %d%n", pendingConnects.size());
        if (hasConnect) {
            System.out.printf("  instanceId=%d host=%s port=%d%n", connectInstanceId, connectHost, connectPort);
        }

        // === Check 2: Simulate connection established ===
        if (hasConnect) {
            pendingConnects.clear();
            musBridge.notifyConnected(connectInstanceId);

            // Run ticks so checkNetMessages fires the callback
            for (int i = 0; i < 50; i++) {
                player.tick();
                Thread.sleep(5);
            }
        }

        // Check pending sends (the client should have sent something after connecting)
        var pendingSends = musBridge.pendingSends;
        System.out.println("\n=== MULTIUSER SENDS ===");
        System.out.printf("Pending sends: %d%n", pendingSends.size());
        for (var send : pendingSends) {
            String contentStr = send.content().toString();
            System.out.printf("  instanceId=%d subject='%s' content='%s'%n",
                    send.instanceId(), send.subject(),
                    contentStr.length() > 80 ? contentStr.substring(0, 80) + "..." : contentStr);
        }

        // Check handler flow
        boolean sawConnect = handlerTrace.stream().anyMatch(h -> h.equalsIgnoreCase("connect"));
        boolean sawXtraMsgHandler = handlerTrace.stream().anyMatch(h -> h.equalsIgnoreCase("xtraMsgHandler"));
        boolean sawSetLogMode = handlerTrace.stream().anyMatch(h -> h.equalsIgnoreCase("setLogMode"));

        System.out.println("\n=== HANDLER TRACE ===");
        System.out.println("connect():         " + sawConnect);
        System.out.println("xtraMsgHandler():  " + sawXtraMsgHandler);
        System.out.println("setLogMode():      " + sawSetLogMode);

        if (!errorTrace.isEmpty()) {
            System.out.println("\n=== ERRORS ===");
            for (String err : errorTrace) {
                System.out.println("  " + err);
            }
        }

        // === Results ===
        System.out.println("\n=== RESULTS ===");
        boolean allPassed = true;
        allPassed &= check("Multiuser connect request created", hasConnect);
        allPassed &= check("Connect to localhost", "localhost".equals(connectHost));
        allPassed &= check("Connect to valid port", connectPort == 30001 || connectPort == 38101);
        allPassed &= check("connect() handler called", sawConnect);
        allPassed &= check("xtraMsgHandler() callback fired", sawXtraMsgHandler);

        System.out.println("\n" + (allPassed ? "ALL PASSED" : "SOME CHECKS FAILED"));

        player.shutdown();
    }

    private static boolean check(String label, boolean pass) {
        System.out.printf("  %-45s %s%n", label, pass ? "PASS" : "FAIL");
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
            if (c >= 'a' && c <= 'z') javaVK = 65 + (c - 'a');
            else if (c >= 'A' && c <= 'Z') javaVK = 65 + (c - 'A');
            else if (c >= '0' && c <= '9') javaVK = 48 + (c - '0');
            else javaVK = (int) c;
            int directorCode = com.libreshockwave.player.input.DirectorKeyCodes.fromJavaKeyCode(javaVK);
            player.onKeyDown(directorCode, String.valueOf(c), false, false, false);
            player.onKeyUp(directorCode, String.valueOf(c), false, false, false);
            player.tick();
        }
    }
}
