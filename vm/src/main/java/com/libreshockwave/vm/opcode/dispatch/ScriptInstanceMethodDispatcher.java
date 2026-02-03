package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoException;
import com.libreshockwave.vm.builtin.CastLibProvider;
import com.libreshockwave.vm.opcode.ExecutionContext;
import com.libreshockwave.vm.util.AncestorChainWalker;

import java.util.ArrayList;
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
                // setAt(instance, #propName, value) or instance.setAt(#propName, value)
                if (args.size() >= 2) {
                    String propName = getPropertyName(args.get(0));
                    Datum value = args.get(1);
                    instance.properties().put(propName, value);
                }
                return Datum.VOID;
            }
            case "setaprop" -> {
                // setaProp(instance, #propName, value)
                if (args.size() >= 2) {
                    String propName = getPropertyName(args.get(0));
                    Datum value = args.get(1);
                    instance.properties().put(propName, value);

                    // Also update pObjectList if it exists (for Object Manager pattern)
                    // This ensures getManager() can find the correct instance
                    Datum pObjectList = instance.properties().get("pObjectList");
                    if (pObjectList instanceof Datum.PropList objList) {
                        objList.properties().put(propName, value);
                    }
                }
                return Datum.VOID;
            }
            case "setprop" -> {
                // setProp(instance, #propName, value) - 2 args: set property directly
                // setProp(instance, #propName, key, value) - 3 args: nested setting
                if (args.size() == 2) {
                    // Simple case: set property directly
                    String propName = getPropertyName(args.get(0));
                    Datum value = args.get(1);
                    instance.properties().put(propName, value);
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
                if (key.equalsIgnoreCase("ancestor")) {
                    Datum ancestor = instance.properties().get("ancestor");
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
                // addAt(instance, position, classList) - set up ancestor chain from class list
                // This is used by Object Manager to build the class hierarchy
                // Position 1 = immediate ancestor
                if (args.size() >= 2) {
                    int position = args.get(0).toInt();
                    Datum classList = args.get(1);
                    if (position == 1 && classList instanceof Datum.List list && !list.items().isEmpty()) {
                        // Build ancestor chain from the class list
                        Datum.ScriptInstance ancestorChain = buildAncestorChain(ctx, list.items());
                        if (ancestorChain != null) {
                            instance.properties().put("ancestor", ancestorChain);
                        }
                    }
                }
                return Datum.VOID;
            }
        }

        // SECOND: Check for Lingo handlers in the script (and ancestor chain)
        // This is for non-built-in methods like create(), dump(), etc.
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider != null) {
            Datum.ScriptInstance current = instance;
            for (int i = 0; i < 100; i++) { // Safety limit to prevent infinite loops
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

                Datum ancestor = current.properties().get("ancestor");
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
        Datum scriptRef = instance.properties().get("__scriptRef__");
        if (scriptRef instanceof Datum.ScriptRef sr) {
            return sr;
        }
        return null;
    }

    /**
     * Build an ancestor chain from a list of class names.
     * Each class is instantiated and chained to the next.
     * Returns the first instance in the chain (which will be set as the ancestor).
     */
    private static Datum.ScriptInstance buildAncestorChain(ExecutionContext ctx, List<Datum> classNames) {
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null || classNames.isEmpty()) {
            return null;
        }

        Datum.ScriptInstance previousInstance = null;
        Datum.ScriptInstance firstInstance = null;

        // Build chain from first class to last
        for (Datum className : classNames) {
            String name = className.toStr();

            // Find the script member for this class name
            Datum memberDatum = provider.getMemberByName(0, name);
            if (!(memberDatum instanceof Datum.CastMemberRef memberRef)) {
                continue;
            }

            // Get the script property from the member (which gives us the slot number)
            Datum scriptDatum = provider.getMemberProp(memberRef.castLib(), memberRef.member(), "script");

            // If script property is an int (slot number), decode it to ScriptRef
            if (scriptDatum instanceof Datum.Int slotNum) {
                int value = slotNum.value();
                if (value > 65535) {
                    // Decode slot number
                    int castLib = value >> 16;
                    int member = value & 0xFFFF;
                    scriptDatum = new Datum.ScriptRef(castLib, member);
                } else {
                    // Simple member number - assume same cast lib
                    scriptDatum = new Datum.ScriptRef(memberRef.castLib(), value);
                }
            } else if (!(scriptDatum instanceof Datum.ScriptRef)) {
                // Create ScriptRef from member info directly
                scriptDatum = new Datum.ScriptRef(memberRef.castLib(), memberRef.member());
            }

            // Create new instance of the script
            List<Datum> newArgs = new ArrayList<>();
            newArgs.add(scriptDatum);
            Datum newInstance = ctx.invokeBuiltin("new", newArgs);

            if (!(newInstance instanceof Datum.ScriptInstance instance)) {
                continue;
            }

            // Set the ancestor of the previous instance to this one
            if (previousInstance != null) {
                previousInstance.properties().put("ancestor", instance);
            }

            if (firstInstance == null) {
                firstInstance = instance;
            }

            previousInstance = instance;
        }

        return firstInstance;
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
