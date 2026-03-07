package com.libreshockwave.player.xtra;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.xtra.MultiuserNetBridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Socket-based MultiuserNetBridge using java.net.Socket.
 * Connects on a background thread; pollMessages reads non-blocking via available().
 * <p>
 * Wire protocol (length-prefixed, tab-separated fields):
 * <pre>
 *   [4 bytes big-endian length][UTF-8 body]
 *   body = errorCode \t senderID \t subject \t content
 * </pre>
 */
public class SocketMultiuserBridge implements MultiuserNetBridge {

    private static class Connection {
        Socket socket;
        InputStream in;
        OutputStream out;
        volatile boolean connected;
        volatile boolean connecting;
        final byte[] readBuf = new byte[8192];
        final List<Byte> pending = new ArrayList<>();
    }

    private final Map<Integer, Connection> connections = new HashMap<>();

    @Override
    public void requestConnect(int instanceId, String host, int port) {
        Connection conn = new Connection();
        conn.connecting = true;
        connections.put(instanceId, conn);

        Thread t = new Thread(() -> {
            try {
                Socket socket = new Socket(host, port);
                conn.socket = socket;
                conn.in = socket.getInputStream();
                conn.out = socket.getOutputStream();
                conn.connected = true;
            } catch (IOException e) {
                System.err.println("[SocketMultiuserBridge] Connect failed: " + e.getMessage());
            } finally {
                conn.connecting = false;
            }
        }, "MUS-connect-" + instanceId);
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void requestSend(int instanceId, String senderID, String subject, Datum content) {
        Connection conn = connections.get(instanceId);
        if (conn == null || !conn.connected) return;

        String body = "0\t" + senderID + "\t" + subject + "\t" + content.toStr();
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[4 + bodyBytes.length];
        frame[0] = (byte) (bodyBytes.length >> 24);
        frame[1] = (byte) (bodyBytes.length >> 16);
        frame[2] = (byte) (bodyBytes.length >> 8);
        frame[3] = (byte) bodyBytes.length;
        System.arraycopy(bodyBytes, 0, frame, 4, bodyBytes.length);

        try {
            conn.out.write(frame);
            conn.out.flush();
        } catch (IOException e) {
            System.err.println("[SocketMultiuserBridge] Send failed: " + e.getMessage());
        }
    }

    @Override
    public void requestDisconnect(int instanceId) {
        Connection conn = connections.remove(instanceId);
        if (conn != null && conn.socket != null) {
            try { conn.socket.close(); } catch (IOException ignored) {}
            conn.connected = false;
        }
    }

    @Override
    public boolean isConnected(int instanceId) {
        Connection conn = connections.get(instanceId);
        return conn != null && conn.connected;
    }

    @Override
    public List<NetMessage> pollMessages(int instanceId) {
        Connection conn = connections.get(instanceId);
        if (conn == null || !conn.connected) return List.of();

        // Read whatever is available without blocking
        try {
            int avail = conn.in.available();
            if (avail > 0) {
                int toRead = Math.min(avail, conn.readBuf.length);
                int read = conn.in.read(conn.readBuf, 0, toRead);
                if (read == -1) {
                    conn.connected = false;
                    return List.of();
                }
                for (int i = 0; i < read; i++) {
                    conn.pending.add(conn.readBuf[i]);
                }
            }
        } catch (IOException e) {
            return List.of();
        }

        // Parse complete length-prefixed messages
        List<NetMessage> messages = new ArrayList<>();
        while (conn.pending.size() >= 4) {
            int len = ((conn.pending.get(0) & 0xFF) << 24)
                    | ((conn.pending.get(1) & 0xFF) << 16)
                    | ((conn.pending.get(2) & 0xFF) << 8)
                    |  (conn.pending.get(3) & 0xFF);

            if (conn.pending.size() < 4 + len) break;

            byte[] bodyBytes = new byte[len];
            for (int i = 0; i < len; i++) {
                bodyBytes[i] = conn.pending.get(4 + i);
            }
            // Remove consumed bytes
            conn.pending.subList(0, 4 + len).clear();

            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            String[] parts = body.split("\t", 4);
            if (parts.length == 4) {
                messages.add(new NetMessage(
                        Integer.parseInt(parts[0]),
                        parts[1],
                        parts[2],
                        new Datum.Str(parts[3])
                ));
            }
        }

        return messages;
    }

    @Override
    public void destroyInstance(int instanceId) {
        requestDisconnect(instanceId);
    }
}
