package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.Scope;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Control flow builtin functions.
 * Includes: return, halt, abort, nothing, param
 */
public final class ControlFlowBuiltins {

    private ControlFlowBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("return", ControlFlowBuiltins::returnValue);
        builtins.put("halt", ControlFlowBuiltins::halt);
        builtins.put("abort", ControlFlowBuiltins::abort);
        builtins.put("nothing", ControlFlowBuiltins::nothing);
        builtins.put("param", ControlFlowBuiltins::param);
        builtins.put("go", ControlFlowBuiltins::go);
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

    /**
     * go(frameOrLabel)
     * Navigate to a frame by number, label, or symbol (#next, #previous, #loop).
     * Matches dirplayer-rs MovieHandlers::go().
     */
    private static Datum go(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.VOID;
        MoviePropertyProvider provider = MoviePropertyProvider.getProvider();
        if (provider == null) return Datum.VOID;

        Datum arg = args.get(0);
        if (arg instanceof Datum.Int i) {
            provider.goToFrame(i.value());
        } else if (arg instanceof Datum.Symbol sym) {
            switch (sym.name().toLowerCase()) {
                case "next" -> provider.goToFrame(provider.getMovieProp("frame").toInt() + 1);
                case "previous" -> provider.goToFrame(Math.max(1, provider.getMovieProp("frame").toInt() - 1));
                case "loop" -> provider.goToFrame(provider.getMovieProp("frame").toInt());
                default -> provider.goToLabel(sym.name());
            }
        } else if (arg instanceof Datum.Str s) {
            provider.goToLabel(s.value());
        } else {
            // Fallback: try as int
            int frame = arg.toInt();
            if (frame > 0) {
                provider.goToFrame(frame);
            }
        }
        return Datum.VOID;
    }

    /**
     * param(n)
     * Returns the nth parameter passed to the current handler.
     * Parameter numbers are 1-indexed in Lingo.
     */
    private static Datum param(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.VOID;
        }

        Scope scope = vm.getCurrentScope();
        if (scope == null) {
            return Datum.VOID;
        }

        int paramNumber = args.get(0).toInt();
        List<Datum> handlerArgs = scope.getArguments();

        // Lingo uses 1-indexed parameters
        int index = paramNumber - 1;
        if (index >= 0 && index < handlerArgs.size()) {
            return handlerArgs.get(index);
        }

        return Datum.VOID;
    }
}
