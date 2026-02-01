package com.libreshockwave.player.net;

import com.libreshockwave.vm.builtin.NetBuiltins;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Manages asynchronous network requests for Lingo scripts.
 * Similar to dirplayer-rs net_manager.rs.
 *
 * Provides implementations for:
 * - preloadNetThing(url) - Start async GET request
 * - postNetText(url, postData) - Start async POST request
 * - netDone(taskId) - Check if request completed
 * - netTextResult(taskId) - Get result text
 * - netError(taskId) - Get error code
 * - getStreamStatus(taskId) - Get request status
 */
public class NetManager implements NetBuiltins.NetProvider {

    private final Map<Integer, NetTask> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final HttpClient httpClient;

    private int nextTaskId = 1;
    private String basePath;

    // Callback for when a fetch completes (used to integrate with CastLibManager)
    private NetCompletionCallback completionCallback;

    public NetManager() {
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "NetManager-worker");
            t.setDaemon(true);
            return t;
        });
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * Set the base path for resolving relative URLs.
     */
    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public String getBasePath() {
        return basePath;
    }

    /**
     * Callback interface for when a network fetch completes.
     */
    @FunctionalInterface
    public interface NetCompletionCallback {
        void onComplete(String url, byte[] data);
    }

    /**
     * Set a callback to be notified when network requests complete.
     * Used by Player to integrate with CastLibManager for external casts.
     */
    public void setCompletionCallback(NetCompletionCallback callback) {
        this.completionCallback = callback;
    }

    /**
     * Start an async GET request (preloadNetThing).
     * @param url The URL to fetch
     * @return The task ID for tracking the request
     */
    public int preloadNetThing(String url) {
        int taskId = nextTaskId++;
        NetTask task = NetTask.get(taskId, resolveUrl(url));
        tasks.put(taskId, task);
        executeTask(task);
        return taskId;
    }

    /**
     * Start an async POST request (postNetText).
     * @param url The URL to post to
     * @param postData The form data to send
     * @return The task ID for tracking the request
     */
    public int postNetText(String url, String postData) {
        int taskId = nextTaskId++;
        NetTask task = NetTask.post(taskId, resolveUrl(url), postData);
        tasks.put(taskId, task);
        executeTask(task);
        return taskId;
    }

    /**
     * Check if a task is done.
     * @param taskId The task ID (or null to check latest)
     * @return true if the task completed or failed
     */
    @Override
    public boolean netDone(Integer taskId) {
        NetTask task = getTask(taskId);
        return task != null && task.isDone();
    }

    /**
     * Get the text result of a completed task.
     * @param taskId The task ID
     * @return The result text, or empty string if not done/failed
     */
    @Override
    public String netTextResult(Integer taskId) {
        NetTask task = getTask(taskId);
        if (task != null && task.getState() == NetTask.State.COMPLETED) {
            return task.getResultAsString();
        }
        return "";
    }

    /**
     * Get the error code of a task.
     * @param taskId The task ID
     * @return 0 for success/pending, non-zero for errors
     */
    @Override
    public int netError(Integer taskId) {
        NetTask task = getTask(taskId);
        return task != null ? task.getErrorCode() : 0;
    }

    /**
     * Get the stream status of a task.
     * @param taskId The task ID
     * @return Status string like "Connecting", "Loading", "Complete", "Error"
     */
    @Override
    public String getStreamStatus(Integer taskId) {
        NetTask task = getTask(taskId);
        return task != null ? task.getStreamStatus() : "Error";
    }

    /**
     * Get the raw bytes of a completed task.
     */
    public byte[] getNetBytes(Integer taskId) {
        NetTask task = getTask(taskId);
        if (task != null && task.getState() == NetTask.State.COMPLETED) {
            return task.getResult();
        }
        return null;
    }

    /**
     * Get a task by ID.
     */
    public NetTask getTask(Integer taskId) {
        if (taskId == null) {
            // Return latest task
            return tasks.values().stream()
                .reduce((a, b) -> b)
                .orElse(null);
        }
        return tasks.get(taskId);
    }

    /**
     * Shutdown the network manager.
     */
    public void shutdown() {
        executor.shutdownNow();
    }

    private String resolveUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }

        return Paths.get(url).getFileName().toString();
    }

    private void executeTask(NetTask task) {
        executor.submit(() -> {
            task.markInProgress();

            String url = task.getUrl();

            try {
                if (url.startsWith("http")) {
                    // HTTP request
                    loadFromHttp(url, task);
                } else {
                    // Handle local file URL
                    loadFromFileUrl(url, task);
                }
            } catch (Exception e) {
                task.fail(-1, e.getMessage());
            }
        });
    }

    private void loadFromFileUrl(String url, NetTask task) throws Exception {
        URI uri = new URI(url);
        Path path = Path.of(uri);

        // Try with extension fallbacks
        Path resolvedPath = resolvePathWithFallbacks(path);
        if (resolvedPath == null) {
            task.fail(404, "File not found: " + path);
            return;
        }

        byte[] data = Files.readAllBytes(resolvedPath);
        System.out.println("[NetManager] Loaded file: " + resolvedPath + " (" + data.length + " bytes)");
        task.complete(data);
        notifyCompletion(task.getUrl(), data);
    }

    private void loadFromFilePath(String filePath, NetTask task) throws Exception {
        Path path = Path.of(filePath);

        // Try with extension fallbacks
        Path resolvedPath = resolvePathWithFallbacks(path);
        if (resolvedPath == null) {
            task.fail(404, "File not found: " + path);
            return;
        }

        byte[] data = Files.readAllBytes(resolvedPath);
        System.out.println("[NetManager] Loaded file: " + resolvedPath + " (" + data.length + " bytes)");
        task.complete(data);
        notifyCompletion(task.getUrl(), data);
    }

    /**
     * Resolve a file path with extension fallbacks.
     * For cast files (.cst, .cct): try requested, then .cst, then .cct
     * For movie files (.dcr, .dxr, .dir): try requested, then .dir, then .dcr
     */
    private Path resolvePathWithFallbacks(Path path) {
        // If the file exists, return it directly
        if (Files.exists(path)) {
            return path;
        }

        String fileName = path.getFileName().toString().toLowerCase();

        // Cast file extensions: try .cst first, then .cct
        if (fileName.endsWith(".cst") || fileName.endsWith(".cct")) {
            String baseName = getFileBaseName(path);
            Path parent = path.getParent();

            // Try .cst first
            Path cstPath = parent != null ? parent.resolve(baseName + ".cst") : Path.of(baseName + ".cst");
            if (Files.exists(cstPath)) {
                return cstPath;
            }

            // Try .cct as fallback
            Path cctPath = parent != null ? parent.resolve(baseName + ".cct") : Path.of(baseName + ".cct");
            if (Files.exists(cctPath)) {
                return cctPath;
            }
        }

        // Movie file extensions: try .dir first, then .dcr, then .dxr
        if (fileName.endsWith(".dcr") || fileName.endsWith(".dxr") || fileName.endsWith(".dir")) {
            String baseName = getFileBaseName(path);
            Path parent = path.getParent();

            // Try .dir first
            Path dirPath = parent != null ? parent.resolve(baseName + ".dir") : Path.of(baseName + ".dir");
            if (Files.exists(dirPath)) {
                return dirPath;
            }

            // Try .dcr as fallback
            Path dcrPath = parent != null ? parent.resolve(baseName + ".dcr") : Path.of(baseName + ".dcr");
            if (Files.exists(dcrPath)) {
                return dcrPath;
            }

            // Try .dxr as last fallback
            Path dxrPath = parent != null ? parent.resolve(baseName + ".dxr") : Path.of(baseName + ".dxr");
            if (Files.exists(dxrPath)) {
                return dxrPath;
            }
        }

        // File not found with any extension
        return null;
    }

    /**
     * Get the base name of a file (without extension).
     */
    private String getFileBaseName(Path path) {
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private void loadFromHttp(String url, NetTask task) throws Exception {
        // Try the URL with extension fallbacks
        String[] urlsToTry = getUrlsWithFallbacks(url);

        Exception lastException = null;
        int lastStatusCode = 0;

        for (String tryUrl : urlsToTry) {
            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(tryUrl))
                    .timeout(Duration.ofSeconds(60));

                if (task.getMethod() == NetTask.Method.POST) {
                    requestBuilder.header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                            task.getPostData() != null ? task.getPostData() : ""));
                } else {
                    requestBuilder.GET();
                }

                HttpResponse<byte[]> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofByteArray()
                );

                int statusCode = response.statusCode();
                if (statusCode >= 200 && statusCode < 300) {
                    System.out.println("[NetManager] Loaded URL: " + tryUrl + " (" + response.body().length + " bytes)");
                    task.complete(response.body());
                    notifyCompletion(task.getUrl(), response.body());
                    return;
                }
                lastStatusCode = statusCode;
            } catch (Exception e) {
                lastException = e;
            }
        }

        // All URLs failed
        if (lastStatusCode > 0) {
            task.fail(lastStatusCode, "HTTP " + lastStatusCode);
        } else if (lastException != null) {
            task.fail(-1, lastException.getMessage());
        } else {
            task.fail(404, "Not found");
        }
    }

    /**
     * Get URLs to try with extension fallbacks.
     * For cast files (.cst, .cct): try requested, then .cst, then .cct
     * For movie files (.dcr, .dxr, .dir): try requested, then .dir, then .dcr
     */
    private String[] getUrlsWithFallbacks(String url) {
        String lowerUrl = url.toLowerCase();

        // Cast file extensions
        if (lowerUrl.endsWith(".cst") || lowerUrl.endsWith(".cct")) {
            String baseName = url.substring(0, url.length() - 4);
            return new String[] { url, baseName + ".cst", baseName + ".cct" };
        }

        // Movie file extensions
        if (lowerUrl.endsWith(".dcr") || lowerUrl.endsWith(".dxr") || lowerUrl.endsWith(".dir")) {
            String baseName = url.substring(0, url.length() - 4);
            return new String[] { url, baseName + ".dir", baseName + ".dcr", baseName + ".dxr" };
        }

        // No fallbacks
        return new String[] { url };
    }

    private void notifyCompletion(String url, byte[] data) {
        if (completionCallback != null && url != null && data != null) {
            try {
                completionCallback.onComplete(url, data);
            } catch (Exception e) {
                System.err.println("[NetManager] Completion callback error: " + e.getMessage());
            }
        }
    }
}
