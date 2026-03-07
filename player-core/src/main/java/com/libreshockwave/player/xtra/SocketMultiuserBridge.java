package com.libreshockwave.player.xtra;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.xtra.MultiuserNetBridge;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Socket-based MultiuserNetBridge using Java NIO non-blocking channels.
 * <p>
 * Wire protocol (length-prefixed, tab-separated fields):
 * <pre>
 *   [4 bytes big-endian length][UTF-8 body]
 *   body = errorCode \t senderID \t subject \t content
 * </pre>
 */
public class SocketMultiuserBridge implements MultiuserNetBridge {

    private static final int HEADER_SIZE = 4;

    private static class Connection {
        SocketChannel channel;
        ByteBuffer readBuffer = ByteBuffer.allocate(8192);
        boolean connected;
    }

    private final Map<Integer, Connection> connections = new HashMap<>();

    @Override
    public void requestConnect(int instanceId, String host, int port) {
        try {
            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress(host, port));

            Connection conn = new Connection();
            conn.channel = channel;
            connections.put(instanceId, conn);
        } catch (IOException e) {
            System.err.println("[SocketMultiuserBridge] Connect failed: " + e.getMessage());
        }
    }

    @Override
    public void requestSend(int instanceId, String senderID, String subject, Datum content) {
        Connection conn = connections.get(instanceId);
        if (conn == null || !conn.connected) return;

        String body = "0\t" + senderID + "\t" + subject + "\t" + content.toStr();
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + bodyBytes.length);
        buf.putInt(bodyBytes.length);
        buf.put(bodyBytes);
        buf.flip();

        try {
            while (buf.hasRemaining()) {
                conn.channel.write(buf);
            }
        } catch (IOException e) {
            System.err.println("[SocketMultiuserBridge] Send failed: " + e.getMessage());
        }
    }

    @Override
    public void requestDisconnect(int instanceId) {
        Connection conn = connections.remove(instanceId);
        if (conn != null) {
            try { conn.channel.close(); } catch (IOException ignored) {}
        }
    }

    @Override
    public boolean isConnected(int instanceId) {
        Connection conn = connections.get(instanceId);
        if (conn == null) return false;

        if (!conn.connected) {
            try {
                if (conn.channel.finishConnect()) {
                    conn.connected = true;
                }
            } catch (IOException e) {
                return false;
            }
        }

        return conn.connected;
    }

    @Override
    public List<NetMessage> pollMessages(int instanceId) {
        Connection conn = connections.get(instanceId);
        if (conn == null || !conn.connected) return List.of();

        // Read available data (non-blocking)
        try {
            int read = conn.channel.read(conn.readBuffer);
            if (read == -1) {
                conn.connected = false;
                return List.of();
            }
        } catch (IOException e) {
            return List.of();
        }

        // Parse complete length-prefixed messages
        List<NetMessage> messages = new ArrayList<>();
        conn.readBuffer.flip();

        while (conn.readBuffer.remaining() >= HEADER_SIZE) {
            conn.readBuffer.mark();
            int length = conn.readBuffer.getInt();

            if (conn.readBuffer.remaining() < length) {
                conn.readBuffer.reset();
                break;
            }

            byte[] bodyBytes = new byte[length];
            conn.readBuffer.get(bodyBytes);
            String body = new String(bodyBytes, StandardCharsets.UTF_8);

            // Parse: errorCode\tsenderID\tsubject\tcontent
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

        conn.readBuffer.compact();
        return messages;
    }

    @Override
    public void destroyInstance(int instanceId) {
        requestDisconnect(instanceId);
    }
}
