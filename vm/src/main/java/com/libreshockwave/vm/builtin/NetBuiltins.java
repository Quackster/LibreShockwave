package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Network builtin functions for Lingo.
 * Similar to dirplayer-rs player/handlers/net.rs.
 *
 * These require a NetManager to be registered via the VM context.
 * The actual implementation is delegated to the registered NetManager.
 */
public final class NetBuiltins {

    private NetBuiltins() {}

    /**
     * Interface for network operations.
     * Implemented by NetManager in player-core.
     */
    public interface NetProvider {
        int preloadNetThing(String url);
        int postNetText(String url, String postData);
        boolean netDone(Integer taskId);
        String netTextResult(Integer taskId);
        int netError(Integer taskId);
        String getStreamStatus(Integer taskId);
    }

    // Thread-local provider for VM access
    private static final ThreadLocal<NetProvider> currentProvider = new ThreadLocal<>();

    /**
     * Set the network provider for the current thread.
     * Call this before executing scripts that use network functions.
     */
    public static void setProvider(NetProvider provider) {
        currentProvider.set(provider);
    }

    /**
     * Clear the network provider for the current thread.
     */
    public static void clearProvider() {
        currentProvider.remove();
    }

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("preloadnetthing", NetBuiltins::preloadNetThing);
        builtins.put("getnetthing", NetBuiltins::preloadNetThing);  // Alias
        builtins.put("postnetthing", NetBuiltins::postNetText);
        builtins.put("postnettext", NetBuiltins::postNetText);
        builtins.put("netdone", NetBuiltins::netDone);
        builtins.put("nettextresult", NetBuiltins::netTextResult);
        builtins.put("neterror", NetBuiltins::netError);
        builtins.put("getstreamstatus", NetBuiltins::getStreamStatus);
    }

    /**
     * preloadNetThing(util)
     * Starts an async network request and returns a task ID.
     */
    private static Datum preloadNetThing(LingoVM vm, List<Datum> args) {
        NetProvider provider = currentProvider.get();
        if (provider == null) {
            System.err.println("[NetBuiltins] No NetProvider registered");
            return Datum.of(-1);
        }

        if (args.isEmpty()) {
            return Datum.of(-1);
        }

        String url = args.get(0).toStr();
        int taskId = provider.preloadNetThing(url);
        return Datum.of(taskId);
    }

    /**
     * postNetText(util, postData)
     * Starts an async POST request and returns a task ID.
     */
    private static Datum postNetText(LingoVM vm, List<Datum> args) {
        NetProvider provider = currentProvider.get();
        if (provider == null) {
            System.err.println("[NetBuiltins] No NetProvider registered");
            return Datum.of(-1);
        }

        if (args.isEmpty()) {
            return Datum.of(-1);
        }

        String url = args.get(0).toStr();
        String postData = args.size() > 1 ? args.get(1).toStr() : "";
        int taskId = provider.postNetText(url, postData);
        return Datum.of(taskId);
    }

    /**
     * netDone(taskId)
     * Returns 1 if the network request is complete, 0 otherwise.
     */
    private static Datum netDone(LingoVM vm, List<Datum> args) {
        NetProvider provider = currentProvider.get();
        if (provider == null) {
            return Datum.TRUE;  // No provider = pretend done
        }

        Integer taskId = args.isEmpty() ? null : args.get(0).toInt();
        boolean done = provider.netDone(taskId);
        return done ? Datum.TRUE : Datum.FALSE;
    }

    /**
     * netTextResult(taskId)
     * Returns the text result of a completed network request.
     */
    private static Datum netTextResult(LingoVM vm, List<Datum> args) {
        NetProvider provider = currentProvider.get();
        if (provider == null) {
            return Datum.EMPTY_STRING;
        }

        Integer taskId = args.isEmpty() ? null : args.get(0).toInt();
        String result = provider.netTextResult(taskId);
        return Datum.of(result);
    }

    /**
     * netError(taskId)
     * Returns the error code (0 = no error).
     */
    private static Datum netError(LingoVM vm, List<Datum> args) {
        NetProvider provider = currentProvider.get();
        if (provider == null) {
            return Datum.ZERO;
        }

        Integer taskId = args.isEmpty() ? null : args.get(0).toInt();
        int error = provider.netError(taskId);
        return Datum.of(error);
    }

    /**
     * getStreamStatus(taskId)
     * Returns the status string: "Connecting", "Loading", "Complete", "Error"
     */
    private static Datum getStreamStatus(LingoVM vm, List<Datum> args) {
        NetProvider provider = currentProvider.get();
        if (provider == null) {
            return Datum.of("Error");
        }

        Integer taskId = args.isEmpty() ? null : args.get(0).toInt();
        String status = provider.getStreamStatus(taskId);
        return Datum.of(status);
    }
}
