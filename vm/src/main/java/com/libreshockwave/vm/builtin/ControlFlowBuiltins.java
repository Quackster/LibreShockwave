package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.Scope;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Control flow builtin functions.
 * Includes: return, halt, abort, nothing
 */
public final class ControlFlowBuiltins {

    private ControlFlowBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("return", ControlFlowBuiltins::returnValue);
        builtins.put("halt", ControlFlowBuiltins::halt);
        builtins.put("abort", ControlFlowBuiltins::abort);
        builtins.put("nothing", ControlFlowBuiltins::nothing);
    }

    /**
     * return(value)
     * Returns early from the current handler with the specified value.
     */
    private static Datum returnValue(LingoVM vm, List<Datum> args) {
        Scope scope = vm.getCurrentScope();
        if (scope != null) {
            Datum returnVal = args.isEmpty() ? Datum.VOID : args.get(0);
            scope.setReturnValue(returnVal);
            scope.setReturned(true);
        }
        return Datum.VOID;
    }

    /**
     * halt()
     * Stops movie playback (does nothing in current implementation).
     */
    private static Datum halt(LingoVM vm, List<Datum> args) {
        // In a full implementation, this would stop movie playback
        return Datum.VOID;
    }

    /**
     * abort()
     * Aborts the current script execution.
     */
    private static Datum abort(LingoVM vm, List<Datum> args) {
        Scope scope = vm.getCurrentScope();
        if (scope != null) {
            scope.setReturned(true);
        }
        return Datum.VOID;
    }

    /**
     * nothing
     * Does nothing - used as a placeholder.
     */
    private static Datum nothing(LingoVM vm, List<Datum> args) {
        return Datum.VOID;
    }
}
