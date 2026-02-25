package com.libreshockwave.player.wasm.net;

import com.libreshockwave.player.wasm.WasmPlayerApp;
import com.libreshockwave.vm.builtin.NetBuiltins;
import org.teavm.interop.Import;

import java.util.HashMap;
import java.util.Map;

/**
 * Network provider for standard WASM target.
 * Uses @Import to call JavaScript fetch operations through the WASM import mechanism.
 * URLs are passed via a shared string buffer; results arrive via exported callback methods.
 */
public class WasmNetManager implements NetBuiltins.NetProvider {

    private static WasmNetManager instance;

    private final String basePath;
    private final Map<Integer, NetTask> tasks = new HashMap<>();
    private int nextTaskId = 1;
    private int lastTaskId = 0;

    public WasmNetManager(String basePath) {
        this.basePath = basePath;
        instance = this;
    }

    public static WasmNetManager getInstance() {
        return instance;
    }

    @Override
    public int preloadNetThing(String url) {
        int taskId = nextTaskId++;
        lastTaskId = taskId;

        String resolvedUrl = resolveUrl(url);
        NetTask task = new NetTask(taskId, resolvedUrl);
        tasks.put(taskId, task);

        System.out.println("[WasmNetManager] Fetching: " + resolvedUrl + " (task " + taskId + ")");

        // Write URL to shared string buffer for JS to read
        WasmPlayerApp.writeStringToBuffer(resolvedUrl);
        byte[] urlBytes = resolvedUrl.getBytes();
        jsFetchGet(taskId, urlBytes.length);

        return taskId;
    }

    @Override
    public int postNetText(String url, String postData) {
        int taskId = nextTaskId++;
        lastTaskId = taskId;

        String resolvedUrl = resolveUrl(url);
        NetTask task = new NetTask(taskId, resolvedUrl);
        tasks.put(taskId, task);

        String post = postData != null ? postData : "";
        byte[] urlBytes = resolvedUrl.getBytes();
        byte[] postBytes = post.getBytes();

        // Write URL then post data to shared string buffer
        byte[] stringBuf = WasmPlayerApp.stringBuffer;
        int urlLen = Math.min(urlBytes.length, stringBuf.length);
        System.arraycopy(urlBytes, 0, stringBuf, 0, urlLen);
        int postOffset = urlLen;
        int postLen = Math.min(postBytes.length, stringBuf.length - postOffset);
        if (postLen > 0) {
            System.arraycopy(postBytes, 0, stringBuf, postOffset, postLen);
        }

        jsFetchPost(taskId, urlBytes.length, postBytes.length);

        return taskId;
    }

    /**
     * Called from WasmPlayerApp.onFetchComplete export when JS delivers fetch results.
     */
    public void onFetchComplete(int taskId, byte[] data) {
        NetTask task = tasks.get(taskId);
        if (task != null) {
            task.data = data;
            task.done = true;
            System.out.println("[WasmNetManager] Complete: task " + taskId + " (" + data.length + " bytes)");
        }
    }

    /**
     * Called from WasmPlayerApp.onFetchError export when JS reports a fetch error.
     */
    public void onFetchError(int taskId, int status) {
        NetTask task = tasks.get(taskId);
        if (task != null) {
            task.errorCode = status != 0 ? status : -1;
            task.done = true;
            System.err.println("[WasmNetManager] Error: task " + taskId + " (HTTP " + status + ")");
        }
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

    // === WASM imports - provided by JavaScript via installImports ===

    @Import(name = "fetchGet", module = "libreshockwave")
    private static native void jsFetchGet(int taskId, int urlLength);

    @Import(name = "fetchPost", module = "libreshockwave")
    private static native void jsFetchPost(int taskId, int urlLength, int postDataLength);

    // Simple task data holder
    static class NetTask {
        final int id;
        final String url;
        byte[] data;
        int errorCode;
        boolean done;

        NetTask(int id, String url) {
            this.id = id;
            this.url = url;
        }
    }
}
