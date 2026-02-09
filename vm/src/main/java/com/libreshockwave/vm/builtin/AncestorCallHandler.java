package com.libreshockwave.vm.builtin;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.Scope;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the callAncestor builtin function.
 * Calls a handler on the ancestor of a given script instance.
 */
public final class AncestorCallHandler {

    private AncestorCallHandler() {}

    /**
     * callAncestor(#handler, me, arg1, arg2, ...)
     * Calls a handler on the ancestor of the given script instance.
     *
     * In Director, callAncestor:
     * 1. Finds the ancestor of the 'me' argument (args[1])
     * 2. Looks up the handler in the ancestor's SCRIPT
     * 3. Executes the handler with 'me' still being the ORIGINAL instance
     *
     * When inside an ancestor's handler (due to callAncestor), a nested callAncestor
     * should use the CURRENT SCOPE's script to determine which ancestor to call next.
     */
    public static Datum call(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) {
            return Datum.VOID;
        }

        // Get handler name from first argument (symbol or string)
        Datum handlerArg = args.get(0);
        String handlerName;
        if (handlerArg instanceof Datum.Symbol sym) {
            handlerName = sym.name();
        } else {
            handlerName = handlerArg.toStr();
        }

        // Get the script instance (me)
        Datum meArg = args.get(1);
        if (!(meArg instanceof Datum.ScriptInstance instance)) {
            // Also handle lists of instances
            if (meArg instanceof Datum.List list) {
                Datum result = Datum.VOID;
                for (Datum item : list.items()) {
                    if (item instanceof Datum.ScriptInstance) {
                        List<Datum> newArgs = new ArrayList<>();
                        newArgs.add(handlerArg);
                        newArgs.add(item);
                        newArgs.addAll(args.subList(2, args.size()));
                        result = call(vm, newArgs);
                    }
                }
                return result;
            }
            return Datum.VOID;
        }

        // Find the ancestor
        // If we're already executing in an ancestor's script, we need to find
        // which level of the ancestor chain we're at and get THAT instance's ancestor
        Datum ancestor = findAncestorForCall(vm, instance);
        if (ancestor == null || ancestor.isVoid()) {
            return Datum.VOID;
        }

        if (!(ancestor instanceof Datum.ScriptInstance ancestorInstance)) {
            return Datum.VOID;
        }

        // Look up the handler in the ancestor's script (or its ancestors)
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            return Datum.VOID;
        }

        // Walk the ancestor chain to find a script with the handler
        Datum.ScriptInstance currentAncestor = ancestorInstance;
        CastLibProvider.HandlerLocation location = null;

        for (int i = 0; i < 100; i++) { // Safety limit
            location = provider.findHandlerInScript(currentAncestor.scriptId(), handlerName);
            if (location != null) {
                break;
            }
            // Try next ancestor
            Datum nextAncestor = currentAncestor.properties().get(Datum.PROP_ANCESTOR);
            if (nextAncestor instanceof Datum.ScriptInstance next) {
                currentAncestor = next;
            } else {
                break;
            }
        }

        if (location == null || location.script() == null || location.handler() == null) {
            return Datum.VOID;
        }

        // Build call arguments (me + remaining args)
        List<Datum> callArgs = new ArrayList<>();
        callArgs.addAll(args.subList(2, args.size()));

        // Execute the handler with original 'me' as receiver
        if (location.script() instanceof ScriptChunk script
                && location.handler() instanceof ScriptChunk.Handler handler) {
            return vm.executeHandler(script, handler, callArgs, instance);
        }

        return Datum.VOID;
    }

    /**
     * Find the appropriate ancestor for a callAncestor call.
     * If we're already executing in an ancestor's handler, we need to find
     * which level we're at and return THAT instance's ancestor.
     */
    private static Datum findAncestorForCall(LingoVM vm, Datum.ScriptInstance me) {
        Scope currentScope = vm.getCurrentScope();
        if (currentScope == null) {
            // Not in a handler, just return me's ancestor
            return me.properties().get(Datum.PROP_ANCESTOR);
        }

        // Get the script we're currently executing in
        int currentScriptId = currentScope.getScript().id();

        // Walk me's ancestor chain to find which instance has the script we're in
        Datum.ScriptInstance walkInstance = me;
        for (int i = 0; i < 100; i++) { // Safety limit
            if (walkInstance.scriptId() == currentScriptId) {
                // Found it - return this instance's ancestor
                Datum ancestor = walkInstance.properties().get(Datum.PROP_ANCESTOR);
                return ancestor != null ? ancestor : Datum.VOID;
            }
            // Move to next ancestor
            Datum nextAncestor = walkInstance.properties().get(Datum.PROP_ANCESTOR);
            if (nextAncestor instanceof Datum.ScriptInstance next) {
                walkInstance = next;
            } else {
                break;
            }
        }

        // Fallback: return me's direct ancestor
        Datum ancestor = me.properties().get(Datum.PROP_ANCESTOR);
        return ancestor != null ? ancestor : Datum.VOID;
    }
}
