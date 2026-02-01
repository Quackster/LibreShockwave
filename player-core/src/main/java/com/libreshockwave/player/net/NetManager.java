package com.libreshockwave.player.net;

import com.libreshockwave.vm.builtin.NetBuiltins;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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

        // Already absolute URL
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
            return url;
        }

        // Resolve relative to base path
        if (basePath != null) {
            try {
                URI baseUri = new URI(basePath);
                return baseUri.resolve(url).toString();
            } catch (Exception e) {
                // Fall back to treating as file path
                Path base = Path.of(basePath);
                if (Files.isRegularFile(base)) {
                    base = base.getParent();
                }
                return base.resolve(url).toUri().toString();
            }
        }

        return url;
    }

    private void executeTask(NetTask task) {
        executor.submit(() -> {
            task.markInProgress();

            String url = task.getUrl();

            try {
                if (url.startsWith("file://")) {
                    // Handle local file
                    URI uri = new URI(url);
                    Path path = Path.of(uri);
                    byte[] data = Files.readAllBytes(path);
                    task.complete(data);
                } else {
                    // HTTP request
                    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
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
                        task.complete(response.body());
                    } else {
                        task.fail(statusCode, "HTTP " + statusCode);
                    }
                }
            } catch (Exception e) {
                task.fail(-1, e.getMessage());
            }
        });
    }
}
