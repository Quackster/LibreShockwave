package com.libreshockwave.player.wasm;

import com.libreshockwave.util.FileUtil;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.builtin.NetBuiltins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Polling-based network provider for WASM.
 * No @Import annotations — all communication is JS → WASM via @Export methods.
 *
 * When Lingo calls preloadNetThing(url), the request is queued. After each tick(),
 * JS polls for pending requests via WasmEntry, does fetch(), and delivers results
 * back via deliverFetchResult/deliverFetchError exports.
 */
public class QueuedNetProvider implements NetBuiltins.NetProvider {

    private final String basePath;
    private final Map<Integer, NetTask> tasks = new HashMap<>();
    private final List<PendingRequest> pendingRequests = new ArrayList<>();
    private int nextTaskId = 1;
    private int lastTaskId = 0;

    public QueuedNetProvider(String basePath) {
        this.basePath = basePath;
    }

    @Override
    public int preloadNetThing(String url) {
        int taskId = nextTaskId++;
        lastTaskId = taskId;

        String resolvedUrl = resolveUrl(url);
        String[] fallbacks = FileUtil.getUrlsWithFallbacks(resolvedUrl);

        NetTask task = new NetTask(taskId, resolvedUrl);
        task.fallbackUrls = fallbacks;
        tasks.put(taskId, task);

        pendingRequests.add(new PendingRequest(taskId, fallbacks[0], "GET", null, fallbacks));
        return taskId;
    }

    @Override
    public int postNetText(String url, String postData) {
        int taskId = nextTaskId++;
        lastTaskId = taskId;

        String resolvedUrl = resolveUrl(url);
        NetTask task = new NetTask(taskId, resolvedUrl);
        tasks.put(taskId, task);

        pendingRequests.add(new PendingRequest(taskId, resolvedUrl, "POST", postData, null));
        return taskId;
    }

    @Override
    public boolean netDone(Integer taskId) {
        NetTask task = getTask(taskId);
        return task != null && task.done;
    }

    @Override
    public String netTextResult(Integer taskId) {
        NetTask task = getTask(taskId);
        if (task != null && task.done && task.data != null) {
            return new String(task.data);
        }
        return "";
    }

    @Override
    public int netError(Integer taskId) {
        NetTask task = getTask(taskId);
        return task != null ? task.errorCode : 0;
    }

    @Override
    public String getStreamStatus(Integer taskId) {
        NetTask task = getTask(taskId);
        if (task == null) return "Error";
        if (task.done) return task.errorCode == 0 ? "Complete" : "Error";
        return "Loading";
    }

    /**
     * Returns stream status as a PropList with real bytesSoFar so that the
     * Download Instance's check (tStreamStatus[#bytesSoFar] > 0) passes.
     */
    @Override
    public Datum getStreamStatusDatum(Integer taskId) {
        NetTask task = getTask(taskId);
        java.util.LinkedHashMap<String, Datum> props = new java.util.LinkedHashMap<>();
        if (task == null) {
            props.put("URL",        Datum.EMPTY_STRING);
            props.put("state",      Datum.of("Error"));
            props.put("bytesSoFar", Datum.ZERO);
            props.put("bytesTotal", Datum.ZERO);
            props.put("error",      Datum.of("OK"));
            return Datum.propList(props);
        }
        int byteCount = task.done && task.data != null ? task.data.length : 0;
        String state  = task.done ? (task.errorCode == 0 ? "Complete" : "Error") : "Loading";
        props.put("URL",        Datum.EMPTY_STRING);
        props.put("state",      Datum.of(state));
        props.put("bytesSoFar", Datum.of(byteCount));
        props.put("bytesTotal", Datum.of(byteCount));
        props.put("error",      task.errorCode == 0 ? Datum.of("OK") : Datum.of(String.valueOf(task.errorCode)));
        return Datum.propList(props);
    }

    /**
     * Get the URL for a task (used to identify external cast loads).
     */
    public String getTaskUrl(int taskId) {
        NetTask task = tasks.get(taskId);
        return task != null ? task.url : null;
    }

    /**
     * Get all pending requests for JS to read.
     */
    public List<PendingRequest> getPendingRequests() {
        return pendingRequests;
    }

    /**
     * Clear pending requests after JS has read them.
     */
    public void drainPendingRequests() {
        pendingRequests.clear();
    }

    /**
     * Called when JS delivers a successful fetch result.
     */
    public void onFetchComplete(int taskId, byte[] data) {
        NetTask task = tasks.get(taskId);
        if (task != null) {
            task.data = data;
            task.done = true;
        }
    }

    /**
     * Called when JS delivers a fetch error.
     */
    public void onFetchError(int taskId, int status) {
        NetTask task = tasks.get(taskId);
        if (task != null) {
            task.errorCode = status != 0 ? status : -1;
            task.done = true;
        }
    }

    private NetTask getTask(Integer taskId) {
        if (taskId == null || taskId == 0) {
            return lastTaskId > 0 ? tasks.get(lastTaskId) : null;
        }
        return tasks.get(taskId);
    }

    private String resolveUrl(String url) {
        if (url == null || url.isEmpty()) return url;

        // If already absolute, use as-is
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }

        // Extract just the filename (strip any path from the author's machine)
        String fileName = url;
        int lastSlash = Math.max(url.lastIndexOf('/'), url.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            fileName = url.substring(lastSlash + 1);
        }

        // Resolve against basePath
        if (basePath != null && !basePath.isEmpty()) {
            String base = basePath;
            // Remove trailing filename from basePath if it looks like a file
            int baseSlash = base.lastIndexOf('/');
            if (baseSlash >= 0 && base.lastIndexOf('.') > baseSlash) {
                base = base.substring(0, baseSlash + 1);
            } else if (!base.endsWith("/")) {
                base = base + "/";
            }
            return base + fileName;
        }

        return fileName;
    }

    /**
     * Serialize pending requests as JSON for JS to read from WASM memory.
     */
    public String serializePendingRequests() {
        if (pendingRequests.isEmpty()) return "[]";

        StringBuilder sb = new StringBuilder(256);
        sb.append('[');
        boolean first = true;
        for (PendingRequest req : pendingRequests) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"taskId\":").append(req.taskId);
            sb.append(",\"url\":\"").append(escapeJson(req.url)).append('"');
            sb.append(",\"method\":\"").append(req.method).append('"');
            if (req.postData != null) {
                sb.append(",\"postData\":\"").append(escapeJson(req.postData)).append('"');
            }
            if (req.fallbacks != null && req.fallbacks.length > 1) {
                sb.append(",\"fallbacks\":[");
                for (int i = 1; i < req.fallbacks.length; i++) {
                    if (i > 1) sb.append(',');
                    sb.append('"').append(escapeJson(req.fallbacks[i])).append('"');
                }
                sb.append(']');
            }
            sb.append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * A pending network request for JS to execute.
     * Uses a plain class instead of a record for TeaVM compatibility
     * (TeaVM may not correctly handle String[] fields in records).
     */
    public static class PendingRequest {
        public final int taskId;
        public final String url;
        public final String method;
        public final String postData;
        public final String[] fallbacks;

        public PendingRequest(int taskId, String url, String method, String postData, String[] fallbacks) {
            this.taskId = taskId;
            this.url = url;
            this.method = method;
            this.postData = postData;
            this.fallbacks = fallbacks;
        }
    }

    // Simple task data holder
    static class NetTask {
        final int id;
        final String url;
        byte[] data;
        int errorCode;
        boolean done;
        String[] fallbackUrls;

        NetTask(int id, String url) {
            this.id = id;
            this.url = url;
        }
    }
}
