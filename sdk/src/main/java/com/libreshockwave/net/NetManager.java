package com.libreshockwave.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages network tasks for HTTP loading.
 * Matches dirplayer-rs NetManager (vm-rust/src/player/net_manager.rs).
 */
public class NetManager {

    private URI basePath;
    private final Map<Integer, NetTask> tasks = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<NetResult>> taskFutures = new ConcurrentHashMap<>();
    private final Map<Integer, NetResult> taskResults = new ConcurrentHashMap<>();
    private final AtomicInteger taskIdCounter = new AtomicInteger(0);
    private final HttpClient httpClient;

    public NetManager() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    // --- Base path management ---

    public void setBasePath(URI basePath) {
        // Ensure path ends with /
        String path = basePath.toString();
        if (!path.endsWith("/")) {
            this.basePath = URI.create(path + "/");
        } else {
            this.basePath = basePath;
        }
    }

    public void setBasePath(String basePathStr) {
        if (basePathStr != null && !basePathStr.isEmpty()) {
            setBasePath(URI.create(basePathStr));
        }
    }

    public Optional<URI> getBasePath() {
        return Optional.ofNullable(basePath);
    }

    // --- Task management (matches dirplayer-rs) ---

    /**
     * Preload a URL and return task ID.
     * Non-blocking - starts async fetch immediately.
     */
    public int preloadNetThing(String url) {
        // Check if task already exists for this URL
        Optional<NetTask> existing = findTaskWithUrl(url);
        if (existing.isPresent()) {
            return existing.get().id();
        }

        // Create new task
        int taskId = taskIdCounter.incrementAndGet();
        URI resolvedUri = normalizeTaskUrl(url);
        NetTask task = NetTask.get(taskId, url, resolvedUri);

        tasks.put(taskId, task);

        // Execute async HTTP fetch
        CompletableFuture<NetResult> future = executeTask(task);
        taskFutures.put(taskId, future);

        return taskId;
    }

    /**
     * POST data to URL and return task ID.
     */
    public int postNetText(String url, String postData) {
        int taskId = taskIdCounter.incrementAndGet();
        URI resolvedUri = normalizeTaskUrl(url);
        NetTask task = NetTask.post(taskId, url, resolvedUri, postData);

        tasks.put(taskId, task);

        CompletableFuture<NetResult> future = executeTask(task);
        taskFutures.put(taskId, future);

        return taskId;
    }

    // --- State queries ---

    /**
     * Check if task is complete.
     * If taskId is null, checks the most recent task.
     */
    public boolean isTaskDone(Integer taskId) {
        if (taskId == null || taskId == 0) {
            // Default to last task
            taskId = taskIdCounter.get();
        }
        return taskResults.containsKey(taskId);
    }

    /**
     * Get task result if complete.
     */
    public Optional<NetResult> getTaskResult(Integer taskId) {
        if (taskId == null || taskId == 0) {
            taskId = taskIdCounter.get();
        }
        return Optional.ofNullable(taskResults.get(taskId));
    }

    /**
     * Get task by ID.
     */
    public Optional<NetTask> getTask(int taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    // --- Blocking wait (for cast loading) ---

    /**
     * Wait for task to complete.
     */
    public CompletableFuture<NetResult> awaitTask(int taskId) {
        CompletableFuture<NetResult> future = taskFutures.get(taskId);
        if (future != null) {
            return future;
        }
        // Already completed
        NetResult result = taskResults.get(taskId);
        if (result != null) {
            return CompletableFuture.completedFuture(result);
        }
        return CompletableFuture.completedFuture(new NetResult.Error(4));
    }

    // --- Private helpers ---

    private CompletableFuture<NetResult> executeTask(NetTask task) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(task.resolvedUri())
            .timeout(Duration.ofSeconds(60));

        HttpRequest request = switch (task.method()) {
            case GET -> builder.GET().build();
            case POST -> builder
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                    task.postData() != null ? task.postData() : ""))
                .build();
        };

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            .thenApply(response -> {
                NetResult result;
                if (response.statusCode() == 200) {
                    result = new NetResult.Success(response.body());
                } else {
                    result = new NetResult.Error(response.statusCode());
                }
                taskResults.put(task.id(), result);
                return result;
            })
            .exceptionally(ex -> {
                System.err.println("[NetManager] Error fetching " + task.url() + ": " + ex.getMessage());
                NetResult result = new NetResult.Error(4);
                taskResults.put(task.id(), result);
                return result;
            });
    }

    private Optional<NetTask> findTaskWithUrl(String url) {
        return tasks.values().stream()
            .filter(t -> t.url().equals(url))
            .findFirst();
    }

    private URI normalizeTaskUrl(String url) {
        // Normalize slashes
        String normalized = url.replace("\\", "/");

        // Check if already absolute URI with host
        try {
            URI parsed = URI.create(normalized);
            if (parsed.isAbsolute() && parsed.getHost() != null) {
                return parsed;
            }
        } catch (Exception ignored) {}

        // Resolve against base path
        if (basePath != null) {
            try {
                return basePath.resolve(normalized);
            } catch (Exception e) {
                System.err.println("[NetManager] Failed to resolve URL: " + normalized + " against " + basePath);
            }
        }

        return URI.create(normalized);
    }

    /**
     * Clear all tasks and results.
     */
    public void clear() {
        tasks.clear();
        taskFutures.clear();
        taskResults.clear();
        taskIdCounter.set(0);
    }
}
