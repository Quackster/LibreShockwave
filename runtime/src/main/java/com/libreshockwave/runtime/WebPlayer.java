package com.libreshockwave.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Web-based Shockwave player HTTP server.
 * Serves the player UI static files.
 *
 * The actual runtime executes in the browser via WASM (libreshockwave.wasm).
 * This server is for development/testing - in production, files can be
 * served from any static file server.
 */
public class WebPlayer {

    private final int port;
    private final String staticDir;
    private HttpServer server;

    public WebPlayer(int port) {
        this(port, null);
    }

    public WebPlayer(int port, String staticDir) {
        this.port = port;
        this.staticDir = staticDir;
    }

    /**
     * Start the web server.
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        // Static file handler for player UI
        server.createContext("/", new StaticFileHandler());

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        System.out.println("[WebPlayer] Server started on http://localhost:" + port);
    }

    /**
     * Stop the web server.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            System.out.println("[WebPlayer] Server stopped");
        }
    }

    /**
     * Serves static files from resources/player/ or a custom directory.
     */
    class StaticFileHandler implements HttpHandler {
        private final Map<String, String> mimeTypes = Map.of(
            ".html", "text/html",
            ".css", "text/css",
            ".js", "application/javascript",
            ".wasm", "application/wasm",
            ".png", "image/png",
            ".jpg", "image/jpeg",
            ".gif", "image/gif",
            ".ico", "image/x-icon",
            ".json", "application/json"
        );

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();

            // Default to index.html
            if (path.equals("/")) {
                path = "/index.html";
            }

            // Security: prevent directory traversal
            if (path.contains("..")) {
                sendError(exchange, 403, "Forbidden");
                return;
            }

            // Try to serve from custom static directory first
            if (staticDir != null) {
                Path filePath = Path.of(staticDir, path);
                if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                    serveFile(exchange, filePath);
                    return;
                }
            }

            // Fall back to resources
            String resourcePath = "player" + path;
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    sendError(exchange, 404, "Not found: " + path);
                    return;
                }

                byte[] data = is.readAllBytes();
                String contentType = getContentType(path);

                // Add CORS headers for WASM loading
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Cross-Origin-Embedder-Policy", "require-corp");
                exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin");

                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
            }
            exchange.close();
        }

        private void serveFile(HttpExchange exchange, Path filePath) throws IOException {
            byte[] data = Files.readAllBytes(filePath);
            String contentType = getContentType(filePath.toString());

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Cross-Origin-Embedder-Policy", "require-corp");
            exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin");

            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        }

        private String getContentType(String path) {
            int dotIndex = path.lastIndexOf('.');
            if (dotIndex > 0) {
                String ext = path.substring(dotIndex);
                return mimeTypes.getOrDefault(ext, "application/octet-stream");
            }
            return "application/octet-stream";
        }

        private void sendError(HttpExchange exchange, int status, String message) throws IOException {
            byte[] bytes = message.getBytes();
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    // --- Main ---

    public static void main(String[] args) {
        int port = 8080;
        String staticDir = null;

        // Parse arguments
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-p") || args[i].equals("--port")) {
                if (i + 1 < args.length) {
                    try {
                        port = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid port: " + args[i]);
                        System.exit(1);
                    }
                }
            } else if (args[i].equals("-d") || args[i].equals("--dir")) {
                if (i + 1 < args.length) {
                    staticDir = args[++i];
                }
            } else if (args[i].matches("\\d+")) {
                port = Integer.parseInt(args[i]);
            }
        }

        try {
            WebPlayer webPlayer = new WebPlayer(port, staticDir);
            webPlayer.start();

            System.out.println();
            System.out.println("===========================================");
            System.out.println("  LibreShockwave Web Player");
            System.out.println("===========================================");
            System.out.println();
            System.out.println("  Open in browser: http://localhost:" + port);
            System.out.println();
            if (staticDir != null) {
                System.out.println("  Serving files from: " + staticDir);
                System.out.println();
            }
            System.out.println("  The player runs entirely in the browser.");
            System.out.println("  Load a .dcr or .dir file to begin.");
            System.out.println();
            System.out.println("  Press Ctrl+C to stop");
            System.out.println();

            // Keep running
            Thread.currentThread().join();

        } catch (Exception e) {
            System.err.println("Error starting WebPlayer: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
