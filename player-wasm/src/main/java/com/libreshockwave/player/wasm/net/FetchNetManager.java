package com.libreshockwave.player.wasm.net;

import com.libreshockwave.vm.builtin.NetBuiltins;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

import java.util.HashMap;
import java.util.Map;

/**
 * Browser fetch()-based implementation of NetProvider.
 * Uses @JSBody + @JSFunctor callbacks to call the Fetch API from Java via TeaVM.
 * Results arrive asynchronously via functor callbacks passed to JavaScript.
 */
public class FetchNetManager implements NetBuiltins.NetProvider {

    private final String basePath;
    private final Map<Integer, NetTask> tasks = new HashMap<>();
    private int nextTaskId = 1;
    private int lastTaskId = 0;

    public FetchNetManager(String basePath) {
        this.basePath = basePath;
    }

    @Override
    public int preloadNetThing(String url) {
        int taskId = nextTaskId++;
        lastTaskId = taskId;

        String resolvedUrl = resolveUrl(url);
        NetTask task = new NetTask(taskId, resolvedUrl);
        tasks.put(taskId, task);

        System.out.println("[FetchNetManager] Fetching: " + resolvedUrl + " (task " + taskId + ")");

        FetchSuccessCallback onSuccess = (Int8Array data) -> {
            byte[] bytes = new byte[data.getLength()];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = data.get(i);
            }
            task.data = bytes;
            task.done = true;
            System.out.println("[FetchNetManager] Complete: task " + taskId + " (" + bytes.length + " bytes)");
        };

        FetchErrorCallback onError = (int status) -> {
            task.errorCode = status != 0 ? status : -1;
            task.done = true;
            System.err.println("[FetchNetManager] Error: task " + taskId + " (HTTP " + status + ")");
        };

        jsFetchGet(resolvedUrl, onSuccess, onError);
        return taskId;
    }

    @Override
    public int postNetText(String url, String postData) {
        int taskId = nextTaskId++;
        lastTaskId = taskId;

        String resolvedUrl = resolveUrl(url);
        NetTask task = new NetTask(taskId, resolvedUrl);
        tasks.put(taskId, task);

        FetchSuccessCallback onSuccess = (Int8Array data) -> {
            byte[] bytes = new byte[data.getLength()];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = data.get(i);
            }
            task.data = bytes;
            task.done = true;
        };

        FetchErrorCallback onError = (int status) -> {
            task.errorCode = status != 0 ? status : -1;
            task.done = true;
        };

        jsFetchPost(resolvedUrl, postData != null ? postData : "", onSuccess, onError);
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

    // === JS interop via @JSFunctor callbacks ===

    @JSFunctor
    public interface FetchSuccessCallback extends JSObject {
        void onSuccess(Int8Array data);
    }

    @JSFunctor
    public interface FetchErrorCallback extends JSObject {
        void onError(int status);
    }

    @JSBody(params = {"url", "onSuccess", "onError"}, script =
        "fetch(url)" +
        "  .then(function(r) {" +
        "    if (!r.ok) throw r.status;" +
        "    return r.arrayBuffer();" +
        "  })" +
        "  .then(function(buf) {" +
        "    onSuccess(new Int8Array(buf));" +
        "  })" +
        "  .catch(function(e) {" +
        "    onError(typeof e === 'number' ? e : 0);" +
        "  });")
    private static native void jsFetchGet(String url, FetchSuccessCallback onSuccess, FetchErrorCallback onError);

    @JSBody(params = {"url", "postData", "onSuccess", "onError"}, script =
        "fetch(url, {method:'POST', body:postData," +
        "  headers:{'Content-Type':'application/x-www-form-urlencoded'}})" +
        "  .then(function(r) {" +
        "    if (!r.ok) throw r.status;" +
        "    return r.arrayBuffer();" +
        "  })" +
        "  .then(function(buf) {" +
        "    onSuccess(new Int8Array(buf));" +
        "  })" +
        "  .catch(function(e) {" +
        "    onError(typeof e === 'number' ? e : 0);" +
        "  });")
    private static native void jsFetchPost(String url, String postData, FetchSuccessCallback onSuccess, FetchErrorCallback onError);

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
