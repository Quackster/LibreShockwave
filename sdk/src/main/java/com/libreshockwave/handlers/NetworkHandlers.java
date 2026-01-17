package com.libreshockwave.handlers;

import com.libreshockwave.lingo.Datum;
import com.libreshockwave.net.NetManager;
import com.libreshockwave.net.NetResult;
import com.libreshockwave.net.NetTask;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Optional;

/**
 * Built-in network handlers for Lingo.
 * Provides netDone, preloadNetThing, getNetText, and related network operations.
 */
public class NetworkHandlers {

    public static void register(LingoVM vm) {
        vm.registerBuiltin("netDone", NetworkHandlers::netDone);
        vm.registerBuiltin("preloadNetThing", NetworkHandlers::preloadNetThing);
        vm.registerBuiltin("getNetText", NetworkHandlers::getNetText);
        vm.registerBuiltin("netTextResult", NetworkHandlers::netTextResult);
        vm.registerBuiltin("netStatus", NetworkHandlers::netStatus);
        vm.registerBuiltin("netError", NetworkHandlers::netError);
        vm.registerBuiltin("postNetText", NetworkHandlers::postNetText);
        vm.registerBuiltin("getStreamStatus", NetworkHandlers::getStreamStatus);
        vm.registerBuiltin("gotoNetPage", NetworkHandlers::gotoNetPage);
        vm.registerBuiltin("gotoNetMovie", NetworkHandlers::gotoNetMovie);
    }

    private static Datum netDone(LingoVM vm, List<Datum> args) {
        NetManager netManager = vm.getNetManager();
        if (netManager == null) return Datum.TRUE;
        Integer taskId = args.isEmpty() ? null : args.get(0).intValue();
        return netManager.isTaskDone(taskId) ? Datum.TRUE : Datum.FALSE;
    }

    private static Datum preloadNetThing(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0);
        String url = args.get(0).stringValue();
        NetManager netManager = vm.getNetManager();
        if (netManager == null) {
            System.out.println("[preloadNetThing] " + url + " (no NetManager)");
            return Datum.of(1);
        }
        int taskId = netManager.preloadNetThing(url);
        System.out.println("[preloadNetThing] " + url + " -> task " + taskId);
        return Datum.of(taskId);
    }

    private static Datum getNetText(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0);
        String url = args.get(0).stringValue();
        NetManager netManager = vm.getNetManager();
        if (netManager == null) return Datum.of(1);
        return Datum.of(netManager.preloadNetThing(url));
    }

    private static Datum netTextResult(LingoVM vm, List<Datum> args) {
        NetManager netManager = vm.getNetManager();
        if (netManager == null) return Datum.of("");
        Integer taskId = args.isEmpty() ? null : args.get(0).intValue();
        return netManager.getTaskResult(taskId)
            .filter(NetResult::isSuccess)
            .map(r -> Datum.of(new String(r.getData())))
            .orElse(Datum.of(""));
    }

    private static Datum netStatus(LingoVM vm, List<Datum> args) {
        NetManager netManager = vm.getNetManager();
        if (netManager == null) return Datum.of("Complete");
        Integer taskId = args.isEmpty() ? null : args.get(0).intValue();
        return Datum.of(netManager.isTaskDone(taskId) ? "Complete" : "InProgress");
    }

    private static Datum netError(LingoVM vm, List<Datum> args) {
        NetManager netManager = vm.getNetManager();
        if (netManager == null) return Datum.of("OK");
        Integer taskId = args.isEmpty() ? null : args.get(0).intValue();
        return netManager.getTaskResult(taskId)
            .map(r -> r.isSuccess() ? Datum.of("OK") : Datum.of(r.getErrorCode()))
            .orElse(Datum.of(0));
    }

    private static Datum postNetText(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.of(0);
        String url = args.get(0).stringValue();
        String postData = args.size() > 1 ? args.get(1).stringValue() : "";
        NetManager netManager = vm.getNetManager();
        if (netManager == null) return Datum.of(1);
        return Datum.of(netManager.postNetText(url, postData));
    }

    private static Datum getStreamStatus(LingoVM vm, List<Datum> args) {
        NetManager netManager = vm.getNetManager();
        if (netManager == null || args.isEmpty()) return Datum.propList();
        int taskId = args.get(0).intValue();
        Optional<NetTask> taskOpt = netManager.getTask(taskId);
        if (taskOpt.isEmpty()) return Datum.propList();

        NetTask task = taskOpt.get();
        boolean isDone = netManager.isTaskDone(taskId);
        boolean isOk = isDone && netManager.getTaskResult(taskId).map(NetResult::isSuccess).orElse(false);

        Datum.PropList result = Datum.propList();
        result.put(Datum.symbol("URL"), Datum.of(task.url()));
        result.put(Datum.symbol("state"), Datum.of(isDone ? "Complete" : "InProgress"));
        result.put(Datum.symbol("bytesSoFar"), Datum.of(isOk ? 100 : 0));
        result.put(Datum.symbol("bytesTotal"), Datum.of(100));
        result.put(Datum.symbol("error"), Datum.of(isOk ? "OK" : "Error"));
        return result;
    }

    private static Datum gotoNetPage(LingoVM vm, List<Datum> args) {
        if (!args.isEmpty()) {
            System.out.println("[gotoNetPage] " + args.get(0).stringValue());
        }
        return Datum.voidValue();
    }

    private static Datum gotoNetMovie(LingoVM vm, List<Datum> args) {
        if (!args.isEmpty()) {
            System.out.println("[gotoNetMovie] " + args.get(0).stringValue());
        }
        return Datum.voidValue();
    }
}
