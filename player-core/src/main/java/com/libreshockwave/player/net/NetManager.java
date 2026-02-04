package com.libreshockwave.player.net;

import com.libreshockwave.util.FileUtil;
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
 * - preloadNetThing(util) - Start async GET request
 * - postNetText(util, postData) - Start async POST request
 * - netDone(taskId) - Check if request completed
 * - netTextResult(taskId) - Get result text
 * - netError(taskId) - Get error code
 * - getStreamStatus(taskId) - Get request status
 */
public class NetManager implements NetBuiltins.NetProvider {

    private final Map<Integer, NetTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, byte[]> urlCache = new ConcurrentHashMap<>();  // Cache for loaded URLs
    private final ExecutorService executor;
    private final HttpClient httpClient;

    private int nextTaskId = 1;
    private volatile int lastTaskId = 0;  // Track the most recent task for netDone() with no args
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
        lastTaskId = taskId;
        NetTask task = NetTask.get(taskId, url, resolveUrl(url));
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
        lastTaskId = taskId;
        NetTask task = NetTask.post(taskId, url, resolveUrl(url), postData);
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
     * @param taskId The task ID, or null to get the most recent task
     */
    public NetTask getTask(Integer taskId) {
        if (taskId == null || taskId == 0) {
            // Return the most recent task
            return lastTaskId > 0 ? tasks.get(lastTaskId) : null;
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

            String url = task.getOriginalUrl();
            String cacheKey = FileUtil.getFileName(url);

            // Check cache first
            byte[] cached = urlCache.get(cacheKey);
            if (cached != null) {
                System.out.println("[NetManager] Using cached: " + cacheKey + " (" + cached.length + " bytes)");
                task.complete(cached);
                notifyCompletion(task.getOriginalUrl(), cached);
                return;
            }

            try {
                // Check if it's already a full HTTP URL
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    loadFromHttp(url, task, cacheKey);
                }
                // Check if basePath is an HTTP URL - need to construct full URL
                else if (basePath != null && (basePath.startsWith("http://") || basePath.startsWith("https://"))) {
                    // Extract just the filename - the path may be an absolute Windows path from the DCR
                    String fileName = FileUtil.getFileName(url);
                    String fullUrl = basePath + fileName;
                    loadFromHttp(fullUrl, task, cacheKey);
                }
                // Handle local file URL
                else {
                    loadFromFileUrl(url, task, cacheKey);
                }
            } catch (Exception e) {
                task.fail(-1, e.getMessage());
            }
        });
    }

    private void loadFromFileUrl(String url, NetTask task, String cacheKey) throws Exception {
        Path path;

        // Check if it's a file:// URI or a plain path
        if (url.startsWith("file://")) {
            URI uri = new URI(url);
            path = Path.of(uri);
        } else {
            // Plain filename - resolve against basePath
            if (basePath != null && !basePath.isEmpty()) {
                Path base = Path.of(basePath);
                if (Files.isRegularFile(base)) {
                    base = base.getParent();
                }
                path = base.resolve(url);
            } else {
                path = Path.of(url);
            }
        }

        // Try with extension fallbacks
        Path resolvedPath = resolvePathWithFallbacks(path);
        if (resolvedPath == null) {
            task.fail(404, "File not found: " + path);
            return;
        }

        byte[] data = Files.readAllBytes(resolvedPath);
        urlCache.put(cacheKey, data);  // Cache the result
        System.out.println("[NetManager] Loaded file: " + resolvedPath + " (" + data.length + " bytes)");
        task.complete(data);
        notifyCompletion(task.getOriginalUrl(), data);
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

    private void loadFromHttp(String url, NetTask task, String cacheKey) throws Exception {
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
                    byte[] data = response.body();
                    urlCache.put(cacheKey, data);  // Cache the result
                    System.out.println("[NetManager] Loaded URL: " + tryUrl + " (" + data.length + " bytes)");
                    task.complete(data);
                    notifyCompletion(task.getUrl(), data);
                    return;
                }
                lastStatusCode = statusCode;
            } catch (Exception e) {
                lastException = e;
            }
        }

        // All URLs failed
        if (lastStatusCode > 0) {
            System.err.println("[NetManager] HTTP request failed: " + url + " - HTTP " + lastStatusCode);
            task.fail(lastStatusCode, "HTTP " + lastStatusCode);
        } else if (lastException != null) {
            System.err.println("[NetManager] HTTP request failed: " + url + " - " + lastException.getMessage());
            task.fail(-1, lastException.getMessage());
        } else {
            System.err.println("[NetManager] HTTP request failed: " + url + " - Not found");
            task.fail(404, "Not found");
        }
    }

    /**
     * Get URLs to try with extension fallbacks for HTTP loading.
     * Prioritizes Shockwave formats (.dcr, .cct) which are typically deployed on the web.
     * For cast files: try requested, then .cct, then .cst
     * For movie files: try requested, then .dcr, then .dxr, then .dir
     */
    private String[] getUrlsWithFallbacks(String url) {
        String lowerUrl = url.toLowerCase();

        // Cast file extensions - try .cct first (Shockwave protected cast)
        if (lowerUrl.endsWith(".cst") || lowerUrl.endsWith(".cct")) {
            String baseName = url.substring(0, url.length() - 4);
            return new String[] { url, baseName + ".cct", baseName + ".cst" };
        }

        // Movie file extensions - try .dcr first (Shockwave format)
        if (lowerUrl.endsWith(".dcr") || lowerUrl.endsWith(".dxr") || lowerUrl.endsWith(".dir")) {
            String baseName = url.substring(0, url.length() - 4);
            return new String[] { url, baseName + ".dcr", baseName + ".dxr", baseName + ".dir" };
        }

        // No fallbacks
        return new String[] { url };
    }

    private void notifyCompletion(String fileName, byte[] data) {
        if (completionCallback != null && fileName != null && data != null) {
            try {
                completionCallback.onComplete(fileName, data);
            } catch (Exception e) {
                System.err.println("[NetManager] Completion callback error: " + e.getMessage());
            }
        }
    }
}
