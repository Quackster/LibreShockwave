package com.libreshockwave.player;

import com.libreshockwave.player.xtra.SocketMultiuserBridge;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.xtra.MultiuserNetBridge;
import com.libreshockwave.vm.xtra.MultiuserXtra;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Self-contained socket test for SocketMultiuserBridge + MultiuserXtra.
 * Starts an embedded TCP server, connects the bridge, and verifies
 * the full connect → send → receive → callback flow over real sockets.
 *
 * Run: ./gradlew player-core:runSocketBridgeTest
 */
public class SocketMultiuserBridgeTest {

    public static void main(String[] args) throws Exception {
        boolean allPassed = true;

        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
            System.out.println("Test server on port " + port);

            // =============================================
            //  Test 1: Bridge-level connect
            // =============================================
            System.out.println("\n=== Test 1: Connection ===");
            SocketMultiuserBridge bridge = new SocketMultiuserBridge();
            bridge.requestConnect(1, "127.0.0.1", port);

            SocketChannel serverSide = server.accept();
            serverSide.configureBlocking(false);

            allPassed &= waitFor("Bridge connected", () -> bridge.isConnected(1));

            // =============================================
            //  Test 2: Client → Server send
            // =============================================
            System.out.println("\n=== Test 2: Client sends message ===");
            bridge.requestSend(1, "player1", "LOGIN", new Datum.Str("user=test"));

            ByteBuffer serverBuf = ByteBuffer.allocate(4096);
            allPassed &= waitFor("Server received data", () -> {
                try { return serverSide.read(serverBuf) > 0; }
                catch (IOException e) { return false; }
            });

            serverBuf.flip();
            String receivedBody = "";
            if (serverBuf.remaining() >= 4) {
                int len = serverBuf.getInt();
                if (serverBuf.remaining() >= len) {
                    byte[] body = new byte[len];
                    serverBuf.get(body);
                    receivedBody = new String(body, StandardCharsets.UTF_8);
                }
            }
            allPassed &= check("Body contains senderID", receivedBody.contains("player1"));
            allPassed &= check("Body contains subject",  receivedBody.contains("LOGIN"));
            allPassed &= check("Body contains content",  receivedBody.contains("user=test"));
            System.out.println("  Received: " + receivedBody);

            // =============================================
            //  Test 3: Server → Client receive
            // =============================================
            System.out.println("\n=== Test 3: Server sends message ===");
            sendMessage(serverSide, 0, "serverBot", "WELCOME", "Hello player!");

            List<MultiuserNetBridge.NetMessage> received = new ArrayList<>();
            allPassed &= waitFor("Client received message", () -> {
                received.addAll(bridge.pollMessages(1));
                return !received.isEmpty();
            });

            if (!received.isEmpty()) {
                MultiuserNetBridge.NetMessage msg = received.get(0);
                allPassed &= check("errorCode = 0",            msg.errorCode() == 0);
                allPassed &= check("senderID = serverBot",     "serverBot".equals(msg.senderID()));
                allPassed &= check("subject = WELCOME",        "WELCOME".equals(msg.subject()));
                allPassed &= check("content = Hello player!",  "Hello player!".equals(msg.content().toStr()));
            }

            // =============================================
            //  Test 4: Multiple messages in one read
            // =============================================
            System.out.println("\n=== Test 4: Batch receive ===");
            sendMessage(serverSide, 0, "sys", "MSG1", "first");
            sendMessage(serverSide, 0, "sys", "MSG2", "second");
            sendMessage(serverSide, 0, "sys", "MSG3", "third");

            List<MultiuserNetBridge.NetMessage> batch = new ArrayList<>();
            allPassed &= waitFor("Received 3 messages", () -> {
                batch.addAll(bridge.pollMessages(1));
                return batch.size() >= 3;
            });
            allPassed &= check("Batch count = 3", batch.size() == 3);
            if (batch.size() >= 3) {
                allPassed &= check("MSG1 subject", "MSG1".equals(batch.get(0).subject()));
                allPassed &= check("MSG2 subject", "MSG2".equals(batch.get(1).subject()));
                allPassed &= check("MSG3 subject", "MSG3".equals(batch.get(2).subject()));
            }

            // =============================================
            //  Test 5: Disconnect
            // =============================================
            System.out.println("\n=== Test 5: Disconnect ===");
            bridge.requestDisconnect(1);
            allPassed &= check("Disconnected", !bridge.isConnected(1));
            serverSide.close();

            // =============================================
            //  Test 6: Full Xtra integration with callbacks
            // =============================================
            System.out.println("\n=== Test 6: Xtra callback integration ===");

            SocketMultiuserBridge bridge2 = new SocketMultiuserBridge();
            List<String> callbackLog = new ArrayList<>();
            List<Datum> callbackMessages = new ArrayList<>();

            MultiuserXtra xtra = new MultiuserXtra(bridge2, (target, handlerName, callbackArgs) -> {
                callbackLog.add(handlerName);
                // target is the Datum we passed as callback target
            });

            // Create instance
            int instId = xtra.createInstance(List.of());

            // Set message handler: setNetMessageHandler(#onMessage, target)
            // In real Director, target is a ScriptInstance; here we use a symbol as a marker
            Datum callbackTarget = new Datum.Symbol("testTarget");
            xtra.callHandler(instId, "setNetMessageHandler",
                    List.of(new Datum.Symbol("onNetMessage"), callbackTarget));

            // Connect: connectToNetServer("*", "*", host, port, "*", 0)
            xtra.callHandler(instId, "connectToNetServer", List.of(
                    new Datum.Str("*"), new Datum.Str("*"),
                    new Datum.Str("127.0.0.1"), new Datum.Int(port),
                    new Datum.Str("*"), new Datum.Int(0)));

            SocketChannel serverSide2 = server.accept();
            allPassed &= waitFor("Xtra instance connected", () -> bridge2.isConnected(instId));

            // Send from Xtra: sendNetMessage("player1", "CHAT", "Hi!")
            xtra.callHandler(instId, "sendNetMessage", List.of(
                    new Datum.Str("player1"), new Datum.Str("CHAT"), new Datum.Str("Hi!")));

            ByteBuffer serverBuf2 = ByteBuffer.allocate(4096);
            serverSide2.configureBlocking(false);
            allPassed &= waitFor("Server got Xtra send", () -> {
                try { return serverSide2.read(serverBuf2) > 0; }
                catch (IOException e) { return false; }
            });
            serverBuf2.flip();
            String xtraSentBody = parseBody(serverBuf2);
            allPassed &= check("Xtra send has CHAT", xtraSentBody.contains("CHAT"));
            allPassed &= check("Xtra send has Hi!",  xtraSentBody.contains("Hi!"));

            // Server replies — should trigger auto-callback via tick()
            sendMessage(serverSide2, 0, "server", "REPLY", "Got it!");

            allPassed &= waitFor("Callback fired via tick()", () -> {
                xtra.tick();
                return !callbackLog.isEmpty();
            });
            allPassed &= check("Callback handler = onNetMessage",
                    !callbackLog.isEmpty() && "onNetMessage".equals(callbackLog.get(0)));

            // Verify getNetMessage works during callback by calling checkNetMessages
            // (send another message and use explicit checkNetMessages)
            callbackLog.clear();
            sendMessage(serverSide2, 0, "admin", "ALERT", "Server restart");
            Thread.sleep(50); // let data arrive

            Datum checkResult = xtra.callHandler(instId, "checkNetMessages",
                    List.of(new Datum.Int(1)));
            allPassed &= check("checkNetMessages processed 1", checkResult.toInt() == 1);
            allPassed &= check("Callback fired for checkNetMessages",
                    callbackLog.contains("onNetMessage"));

            // Verify getNetErrorString
            Datum errStr = xtra.callHandler(instId, "getNetErrorString",
                    List.of(new Datum.Int(0)));
            allPassed &= check("Error string for 0", "No error".equals(errStr.toStr()));

            Datum errStr2 = xtra.callHandler(instId, "getNetErrorString",
                    List.of(new Datum.Int(-3)));
            allPassed &= check("Error string for -3", "Connection refused".equals(errStr2.toStr()));

            // Cleanup
            xtra.destroyInstance(instId);
            serverSide2.close();
        }

        System.out.println("\n" + (allPassed ? "ALL PASSED" : "SOME CHECKS FAILED"));
        System.exit(allPassed ? 0 : 1);
    }

    // --- Helpers ---

    private static void sendMessage(SocketChannel ch, int errorCode,
                                     String senderID, String subject, String content)
            throws IOException {
        String body = errorCode + "\t" + senderID + "\t" + subject + "\t" + content;
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + bodyBytes.length);
        buf.putInt(bodyBytes.length);
        buf.put(bodyBytes);
        buf.flip();
        ch.configureBlocking(true);
        while (buf.hasRemaining()) ch.write(buf);
    }

    private static String parseBody(ByteBuffer buf) {
        if (buf.remaining() < 4) return "";
        int len = buf.getInt();
        if (buf.remaining() < len) return "";
        byte[] body = new byte[len];
        buf.get(body);
        return new String(body, StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    interface BooleanSupplier {
        boolean check() throws Exception;
    }

    private static boolean waitFor(String label, BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (condition.check()) {
                return check(label, true);
            }
            Thread.sleep(10);
        }
        return check(label, false);
    }

    private static boolean check(String label, boolean pass) {
        System.out.printf("  %-45s %s%n", label, pass ? "PASS" : "FAIL");
        return pass;
    }
}
