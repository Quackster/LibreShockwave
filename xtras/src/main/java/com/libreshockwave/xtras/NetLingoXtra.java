package com.libreshockwave.xtras;

import com.libreshockwave.lingo.Datum;
import com.libreshockwave.net.NetManager;
import com.libreshockwave.net.NetResult;
import com.libreshockwave.vm.LingoVM;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * NetLingo Xtra - provides network functionality for Lingo scripts.
 * Implements preloadNetThing, netDone, netError, getNetText, postNetText, etc.
 */
public class NetLingoXtra implements Xtra {

    @Override
    public String getName() {
        return "NetLingo";
    }

    @Override
    public void register(LingoVM vm) {
        // preloadNetThing(url) - Start async fetch of URL
        vm.registerBuiltin("preloadNetThing", (v, args) -> {
            if (args.isEmpty()) return Datum.of(0);
            String url = args.get(0).stringValue();
            NetManager netManager = v.getNetManager();
            if (netManager == null) {
                System.err.println("[NetLingoXtra] No NetManager configured");
                return Datum.of(0);
            }
            int taskId = netManager.preloadNetThing(url);
            return Datum.of(taskId);
        });

        // netDone(taskId) - Check if task is complete (returns TRUE/FALSE)
        vm.registerBuiltin("netDone", (v, args) -> {
            Integer taskId = args.isEmpty() ? null : args.get(0).intValue();
            NetManager netManager = v.getNetManager();
            if (netManager == null) return Datum.of(1); // Consider done if no manager
            boolean done = netManager.isTaskDone(taskId);
            return Datum.of(done ? 1 : 0);
        });

        // netError(taskId) - Get error state (0 = no error, "OK" = complete, or error string)
        vm.registerBuiltin("netError", (v, args) -> {
            Integer taskId = args.isEmpty() ? null : args.get(0).intValue();
            NetManager netManager = v.getNetManager();
            if (netManager == null) return Datum.of("OK");

            Optional<NetResult> result = netManager.getTaskResult(taskId);
            if (result.isEmpty()) {
                // Still in progress
                return Datum.of(0);
            }
            NetResult r = result.get();
            if (r instanceof NetResult.Success) {
                return Datum.of("OK");
            } else if (r instanceof NetResult.Error err) {
                return Datum.of("Error " + err.errorCode());
            }
            return Datum.of(0);
        });

        // getNetText(taskId) - Get text result of completed task
        vm.registerBuiltin("getNetText", (v, args) -> {
            Integer taskId = args.isEmpty() ? null : args.get(0).intValue();
            NetManager netManager = v.getNetManager();
            if (netManager == null) return Datum.of("");

            Optional<NetResult> result = netManager.getTaskResult(taskId);
            if (result.isEmpty()) {
                return Datum.of("");
            }
            NetResult r = result.get();
            if (r instanceof NetResult.Success success) {
                return Datum.of(new String(success.data(), StandardCharsets.UTF_8));
            }
            return Datum.of("");
        });

        // postNetText(url, postData) - POST data to URL
        vm.registerBuiltin("postNetText", (v, args) -> {
            if (args.size() < 2) return Datum.of(0);
            String url = args.get(0).stringValue();
            String postData = args.get(1).stringValue();
            NetManager netManager = v.getNetManager();
            if (netManager == null) {
                System.err.println("[NetLingoXtra] No NetManager configured");
                return Datum.of(0);
            }
            int taskId = netManager.postNetText(url, postData);
            return Datum.of(taskId);
        });

        // gotoNetPage(url) - Open URL in browser (stub for now)
        vm.registerBuiltin("gotoNetPage", (v, args) -> {
            if (args.isEmpty()) return Datum.voidValue();
            String url = args.get(0).stringValue();
            System.out.println("[NetLingoXtra] gotoNetPage: " + url + " (not implemented)");
            return Datum.voidValue();
        });

        // gotoNetMovie(url) - Load a movie from URL
        vm.registerBuiltin("gotoNetMovie", (v, args) -> {
            if (args.isEmpty()) return Datum.voidValue();
            String url = args.get(0).stringValue();
            System.out.println("[NetLingoXtra] gotoNetMovie: " + url + " (not implemented)");
            return Datum.voidValue();
        });

        // downloadNetThing(url, localPath) - Download to local path (stub)
        vm.registerBuiltin("downloadNetThing", (v, args) -> {
            if (args.size() < 2) return Datum.of(0);
            String url = args.get(0).stringValue();
            String localPath = args.get(1).stringValue();
            System.out.println("[NetLingoXtra] downloadNetThing: " + url + " -> " + localPath + " (not implemented)");
            // For now, just preload
            NetManager netManager = v.getNetManager();
            if (netManager != null) {
                return Datum.of(netManager.preloadNetThing(url));
            }
            return Datum.of(0);
        });

        // netTextResult(taskId) - Alias for getNetText
        vm.registerBuiltin("netTextResult", (v, args) -> {
            Integer taskId = args.isEmpty() ? null : args.get(0).intValue();
            NetManager netManager = v.getNetManager();
            if (netManager == null) return Datum.of("");

            Optional<NetResult> result = netManager.getTaskResult(taskId);
            if (result.isEmpty()) {
                return Datum.of("");
            }
            NetResult r = result.get();
            if (r instanceof NetResult.Success success) {
                return Datum.of(new String(success.data(), StandardCharsets.UTF_8));
            }
            return Datum.of("");
        });

        // netMIME(taskId) - Get MIME type (stub, returns empty)
        vm.registerBuiltin("netMIME", (v, args) -> {
            return Datum.of("");
        });

        // netLastModDate(taskId) - Get last modified date (stub)
        vm.registerBuiltin("netLastModDate", (v, args) -> {
            return Datum.of("");
        });

        // netStatus() - Get status string (stub)
        vm.registerBuiltin("netStatus", (v, args) -> {
            return Datum.of("OK");
        });

        // browserName() - Get browser name (stub)
        vm.registerBuiltin("browserName", (v, args) -> {
            return Datum.of("LibreShockwave");
        });

        // cacheDocVerify() - Cache verification (stub)
        vm.registerBuiltin("cacheDocVerify", (v, args) -> {
            return Datum.voidValue();
        });

        // cacheSize() - Get cache size (stub)
        vm.registerBuiltin("cacheSize", (v, args) -> {
            return Datum.of(0);
        });

        // clearCache() - Clear the cache (stub)
        vm.registerBuiltin("clearCache", (v, args) -> {
            return Datum.voidValue();
        });
    }
}
