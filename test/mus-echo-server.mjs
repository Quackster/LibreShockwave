#!/usr/bin/env node
/**
 * WebSocket-to-TCP proxy for Multiuser Xtra testing.
 *
 * Bridges browser WebSocket connections to a real TCP server.
 * The WASM worker opens WebSockets; this proxy forwards them to TCP.
 *
 * Usage:
 *   npm install ws
 *   node test/mus-echo-server.mjs [ws-port] [tcp-host] [tcp-port]
 *
 *   Defaults: ws://0.0.0.0:30001 → tcp://localhost:30001
 *
 * Examples:
 *   # Proxy ws://localhost:30001 → tcp://localhost:30001 (same port, different protocol)
 *   node test/mus-echo-server.mjs
 *
 *   # Custom ports
 *   node test/mus-echo-server.mjs 8080 localhost 30001
 *
 * Alternatives (no dependencies):
 *   websocat:    websocat -v ws-l:0.0.0.0:30001 tcp:localhost:30001
 *   websockify:  websockify 30001 localhost:30001
 */

import { WebSocketServer } from 'ws';
import { createConnection } from 'net';

const WS_PORT  = parseInt(process.argv[2]) || 30001;
const TCP_HOST = process.argv[3] || 'localhost';
const TCP_PORT = parseInt(process.argv[4]) || WS_PORT;

const wss = new WebSocketServer({ port: WS_PORT });
let nextId = 1;

function ts() { return new Date().toISOString().substring(11, 23); }

wss.on('listening', () => {
    console.log(`[${ts()}] WS→TCP proxy listening on ws://0.0.0.0:${WS_PORT}`);
    console.log(`[${ts()}] Forwarding to tcp://${TCP_HOST}:${TCP_PORT}`);
    console.log(`[${ts()}] Press Ctrl+C to stop`);
});

wss.on('connection', (ws, req) => {
    const id = nextId++;
    const addr = req.socket.remoteAddress;
    console.log(`[${ts()}] #${id} WebSocket connected from ${addr}`);

    const tcp = createConnection({ host: TCP_HOST, port: TCP_PORT }, () => {
        console.log(`[${ts()}] #${id} TCP connected to ${TCP_HOST}:${TCP_PORT}`);
    });

    // WS → TCP
    ws.on('message', (data) => {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
        console.log(`[${ts()}] #${id} WS→TCP ${buf.length} bytes`);
        tcp.write(buf);
    });

    // TCP → WS
    tcp.on('data', (data) => {
        console.log(`[${ts()}] #${id} TCP→WS ${data.length} bytes`);
        if (ws.readyState === 1) ws.send(data);
    });

    // Cleanup
    ws.on('close', () => {
        console.log(`[${ts()}] #${id} WS closed`);
        tcp.destroy();
    });
    tcp.on('close', () => {
        console.log(`[${ts()}] #${id} TCP closed`);
        ws.close();
    });
    tcp.on('error', (err) => {
        console.log(`[${ts()}] #${id} TCP error: ${err.message}`);
        ws.close();
    });
    ws.on('error', (err) => {
        console.log(`[${ts()}] #${id} WS error: ${err.message}`);
        tcp.destroy();
    });
});
