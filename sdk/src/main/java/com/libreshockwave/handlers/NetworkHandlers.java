package com.libreshockwave.handlers;

import com.libreshockwave.lingo.Datum;
import com.libreshockwave.net.NetManager;
import com.libreshockwave.net.NetResult;
import com.libreshockwave.net.NetTask;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Optional;

import static com.libreshockwave.handlers.HandlerArgs.*;

/**
 * Built-in network handlers for Lingo.
 * Provides netDone, preloadNetThing, getNetText, and related network operations.
 * Refactored: Uses HandlerArgs for argument extraction, reducing boilerplate.
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

    /**
     * Get optional task ID from args (null if empty).
     * Refactored: Extracts common "optional task ID" pattern used in multiple handlers.
     */
    private static Integer getOptionalTaskId(List<Datum> args) {
        return isEmpty(args) ? null : getInt0(args);
    }

    private static Datum netDone(LingoVM vm, List<Datum> args) {
        NetManager netManager = vm.getNetManager();
        if (netManager == null) return Datum.TRUE;
        return netManager.isTaskDone(getOptionalTaskId(args)) ? Datum.TRUE : Datum.FALSE;
    }

    private static Datum preloadNetThing(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.of(0);
        String url = getString0(args);
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
        if (isEmpty(args)) return Datum.of(0);
        String url = getString0(args);
        NetManager netManager = vm.getNetManager();
        if (netManager == null) return Datum.of(1);
        return Datum.of(netManager.preloadNetThing(url));
    }

    private static Datum netTextResult(LingoVM vm, List<Datum> args) {
        NetManager netManager = vm.getNetManager();
        if (netManager == null) return Datum.of("");
        return netManager.getTaskResult(getOptionalTaskId(args))
            .filter(NetResult::isSuccess)
            .map(r -> Datum.of(new String(r.getData())))
            .orElse(Datum.of(""));
    }

    private static Datum netStatus(LingoVM vm, List<Datum> args) {
        NetManager netManager = vm.getNetManager();
        if (netManager == null) return Datum.of("Complete");
        return Datum.of(netManager.isTaskDone(getOptionalTaskId(args)) ? "Complete" : "InProgress");
    }

    private static Datum netError(LingoVM vm, List<Datum> args) {
        NetManager netManager = vm.getNetManager();
        if (netManager == null) return Datum.of("OK");
        return netManager.getTaskResult(getOptionalTaskId(args))
            .map(r -> r.isSuccess() ? Datum.of("OK") : Datum.of(r.getErrorCode()))
            .orElse(Datum.of(0));
    }

    private static Datum postNetText(LingoVM vm, List<Datum> args) {
        if (isEmpty(args)) return Datum.of(0);
        String url = getString0(args);
        String postData = getString(args, 1, "");
        NetManager netManager = vm.getNetManager();
        if (netManager == null) return Datum.of(1);
        return Datum.of(netManager.postNetText(url, postData));
    }

    private static Datum getStreamStatus(LingoVM vm, List<Datum> args) {
        NetManager netManager = vm.getNetManager();
        if (netManager == null || isEmpty(args)) return Datum.propList();
        int taskId = getInt0(args);
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
        if (!isEmpty(args)) {
            System.out.println("[gotoNetPage] " + getString0(args));
        }
        return Datum.voidValue();
    }

    private static Datum gotoNetMovie(LingoVM vm, List<Datum> args) {
        if (!isEmpty(args)) {
            System.out.println("[gotoNetMovie] " + getString0(args));
        }
        return Datum.voidValue();
    }
}
