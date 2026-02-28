package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoException;
import com.libreshockwave.vm.builtin.CastLibProvider;
import com.libreshockwave.vm.opcode.ExecutionContext;
import com.libreshockwave.vm.util.AncestorChainWalker;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Handles method calls on script instances.
 * Dispatches to handlers defined in the script.
 * Matches dirplayer-rs: built-in methods are handled first, then Lingo handlers.
 */
public final class ScriptInstanceMethodDispatcher {

    private ScriptInstanceMethodDispatcher() {}

    public static Datum dispatch(ExecutionContext ctx, Datum.ScriptInstance instance,
                                  String methodName, List<Datum> args) {
        // FIRST: Handle built-in property access/modification methods
        // This matches dirplayer-rs ScriptInstanceHandlers.call()
        String method = methodName.toLowerCase();
        switch (method) {
            case "setat" -> {
                // setAt on ScriptInstance: only "ancestor" key is allowed (dirplayer-rs)
                if (args.size() >= 2) {
                    String propName = getPropertyName(args.get(0));
                    Datum value = args.get(1);
                    if (propName.equalsIgnoreCase("ancestor") || propName.equals(Datum.PROP_ANCESTOR)) {
                        instance.properties().put(Datum.PROP_ANCESTOR, value);
                    } else {
                        throw new LingoException("Cannot setAt property " + propName + " on script instance");
                    }
                }
                return Datum.VOID;
            }
            case "setaprop" -> {
                // setaProp(instance, #propName, value)
                // Matches dirplayer-rs: walks ancestor chain via script_set_prop
                if (args.size() >= 2) {
                    String propName = getPropertyName(args.get(0));
                    Datum value = args.get(1);
                    AncestorChainWalker.setProperty(instance, propName, value);
                }
                return Datum.VOID;
            }
            case "setprop" -> {
                // setProp(instance, #propName, value) - 2 args: set property directly
                // setProp(instance, #propName, key, value) - 3 args: nested setting
                if (args.size() == 2) {
                    // Simple case: set property directly (walks ancestor chain)
                    String propName = getPropertyName(args.get(0));
                    Datum value = args.get(1);
                    AncestorChainWalker.setProperty(instance, propName, value);
                } else if (args.size() == 3) {
                    // Nested case: me.setProp(#pItemList, key, value)
                    // Get or create the property, then set a sub-property on it
                    String localPropName = getPropertyName(args.get(0));
                    Datum subKey = args.get(1);
                    Datum value = args.get(2);
                    String keyName = getPropertyName(subKey);

                    Datum localProp = instance.properties().get(localPropName);

                    // If the property doesn't exist or is VOID, create an empty PropList
                    if (localProp == null || localProp.isVoid()) {
                        localProp = new Datum.PropList(new LinkedHashMap<>());
                        instance.properties().put(localPropName, localProp);
                    }

                    // Now do the nested set
                    if (localProp instanceof Datum.List list) {
                        // List: use setAt (1-indexed)
                        int index = subKey.toInt() - 1;
                        if (index >= 0) {
                            while (list.items().size() <= index) {
                                list.items().add(Datum.VOID);
                            }
                            list.items().set(index, value);
                        }
                    } else if (localProp instanceof Datum.PropList pl) {
                        // PropList: set by key
                        pl.properties().put(keyName, value);
                    }
                }
                return Datum.VOID;
            }
            case "getat" -> {
                // getAt(instance, #propName) - like dirplayer-rs: check "ancestor" specially
                if (args.isEmpty()) return Datum.VOID;
                String key = getPropertyName(args.get(0));
                if (key.equalsIgnoreCase(Datum.PROP_ANCESTOR)) {
                    Datum ancestor = instance.properties().get(Datum.PROP_ANCESTOR);
                    return ancestor != null ? ancestor : Datum.ZERO;
                }
                // Otherwise same as getaProp
                return AncestorChainWalker.getProperty(instance, key);
            }
            case "getaprop" -> {
                // getaProp(instance, #propName) - simple single-arg property lookup
                if (args.isEmpty()) return Datum.VOID;
                String propName = getPropertyName(args.get(0));
                return AncestorChainWalker.getProperty(instance, propName);
            }
            case "getprop", "getpropref" -> {
                // getProp(instance, #propName) - single arg: get property
                // getProp(instance, #propName, key) - two args: get property, then look up key in it
                if (args.isEmpty()) return Datum.VOID;
                String localPropName = getPropertyName(args.get(0));
                Datum localProp = AncestorChainWalker.getProperty(instance, localPropName);

                // If there's a second argument, do nested lookup
                if (args.size() > 1) {
                    Datum subKey = args.get(1);
                    if (localProp instanceof Datum.List list) {
                        // List: use index (1-based)
                        int index = subKey.toInt() - 1;
                        if (index >= 0 && index < list.items().size()) {
                            return list.items().get(index);
                        }
                        return Datum.VOID;
                    } else if (localProp instanceof Datum.PropList pl) {
                        // PropList: look up by key (string or symbol, case-insensitive for symbols)
                        String key = getPropertyName(subKey);
                        // Try exact match first, then case-insensitive
                        if (pl.properties().containsKey(key)) {
                            return pl.properties().get(key);
                        }
                        // Case-insensitive search
                        for (var entry : pl.properties().entrySet()) {
                            if (entry.getKey().equalsIgnoreCase(key)) {
                                return entry.getValue();
                            }
                        }
                        return Datum.VOID;
                    } else {
                        // Cannot get sub-property from non-list/proplist
                        return Datum.VOID;
                    }
                }
                return localProp;
            }
            case "addprop" -> {
                // addProp(instance, #propName, value)
                if (args.size() >= 2) {
                    String propName = getPropertyName(args.get(0));
                    instance.properties().put(propName, args.get(1));
                }
                return Datum.VOID;
            }
            case "deleteprop" -> {
                // deleteProp(instance, #propName)
                if (args.isEmpty()) return Datum.VOID;
                String propName = getPropertyName(args.get(0));
                instance.properties().remove(propName);
                return Datum.VOID;
            }
            case "count" -> {
                // count(instance) - return number of properties
                return Datum.of(instance.properties().size());
            }
            case "ilk" -> {
                // ilk(instance) - return #instance
                return new Datum.Symbol("instance");
            }
            case "addat" -> {
                // addAt on ScriptInstance is a no-op (matching dirplayer-rs)
                // In dirplayer-rs, addAt checks if datum is a List; for non-List types it returns Void
                return Datum.VOID;
            }
        }

        // SECOND: Check for Lingo handlers in the script (and ancestor chain)
        // This is for non-built-in methods like create(), dump(), etc.
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider != null) {
            Datum.ScriptInstance current = instance;
            for (int i = 0; i < AncestorChainWalker.MAX_ANCESTOR_DEPTH; i++) { // Safety limit to prevent infinite loops
                Datum.ScriptRef scriptRef = getScriptRefFromInstance(current);

                CastLibProvider.HandlerLocation location;
                if (scriptRef != null) {
                    location = provider.findHandlerInScript(scriptRef.castLib(), scriptRef.member(), methodName);
                } else {
                    location = provider.findHandlerInScript(current.scriptId(), methodName);
                }

                if (location != null && location.script() != null && location.handler() != null) {
                    if (location.script() instanceof ScriptChunk script
                            && location.handler() instanceof ScriptChunk.Handler handler) {
                        return safeExecuteHandler(ctx, script, handler, args, instance);
                    }
                }

                Datum ancestor = current.properties().get(Datum.PROP_ANCESTOR);
                if (ancestor instanceof Datum.ScriptInstance ancestorInstance) {
                    current = ancestorInstance;
                } else {
                    break;
                }
            }
            // Handler not found on instance - return VOID
            // Director doesn't fall back to global handlers for OBJ_CALL on instances
        }

        // THIRD: Check if the method is getting a property (walk ancestor chain)
        String prop = methodName.toLowerCase();
        Datum propValue = AncestorChainWalker.getProperty(instance, prop);
        if (propValue != null && !propValue.isVoid()) {
            return propValue;
        }

        return Datum.VOID;
    }

    /**
     * Get a property name from a Datum (symbol or string).
     */
    private static String getPropertyName(Datum datum) {
        if (datum instanceof Datum.Symbol sym) {
            return sym.name();
        }
        return datum.toStr();
    }

    /**
     * Get the ScriptRef from a script instance.
     * @return The ScriptRef if available, or null
     */
    private static Datum.ScriptRef getScriptRefFromInstance(Datum.ScriptInstance instance) {
        Datum scriptRef = instance.properties().get(Datum.PROP_SCRIPT_REF);
        if (scriptRef instanceof Datum.ScriptRef sr) {
            return sr;
        }
        return null;
    }

    /**
     * Safely execute a handler, catching exceptions and returning VOID on error.
     * This matches dirplayer-rs behavior where errors stop execution but don't propagate
     * as exceptions that could trigger recursive error handling.
     */
    private static Datum safeExecuteHandler(ExecutionContext ctx, ScriptChunk script,
                                             ScriptChunk.Handler handler, List<Datum> args, Datum receiver) {
        try {
            return ctx.executeHandler(script, handler, args, receiver);
        } catch (LingoException e) {
            // Log the error and set error state to prevent further handler execution
            // This matches dirplayer-rs stop() behavior
            System.err.println("[Lingo] Error in " + script.getHandlerName(handler) + ": " + e.getMessage());
            ctx.setErrorState(true);
            return Datum.VOID;
        }
    }
}
