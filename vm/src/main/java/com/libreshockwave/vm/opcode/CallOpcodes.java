package com.libreshockwave.vm.opcode;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.HandlerRef;
import com.libreshockwave.vm.LingoException;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.builtin.CastLibProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Function call opcodes.
 */
public final class CallOpcodes {

    private CallOpcodes() {}

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

    public static void register(Map<Opcode, OpcodeHandler> handlers) {
        handlers.put(Opcode.LOCAL_CALL, CallOpcodes::localCall);
        handlers.put(Opcode.EXT_CALL, CallOpcodes::extCall);
        handlers.put(Opcode.OBJ_CALL, CallOpcodes::objCall);
    }

    private static boolean localCall(ExecutionContext ctx) {
        ScriptChunk.Handler targetHandler = ctx.findLocalHandler(ctx.getArgument());
        if (targetHandler != null) {
            Datum argListDatum = ctx.pop();
            boolean noRet = argListDatum instanceof Datum.ArgListNoRet;
            List<Datum> args = getArgs(argListDatum);
            Datum result = safeExecuteHandler(ctx, ctx.getScript(), targetHandler, args, ctx.getReceiver());
            if (!noRet) {
                ctx.push(result);
            }
        }
        return true;
    }

    private static boolean extCall(ExecutionContext ctx) {
        String handlerName = ctx.resolveName(ctx.getArgument());
        Datum argListDatum = ctx.pop();
        boolean noRet = argListDatum instanceof Datum.ArgListNoRet;
        List<Datum> args = getArgs(argListDatum);

        Datum result;
        if (ctx.isBuiltin(handlerName)) {
            result = ctx.invokeBuiltin(handlerName, args);
        } else {
            HandlerRef ref = ctx.findHandler(handlerName);
            if (ref != null) {
                result = safeExecuteHandler(ctx, ref.script(), ref.handler(), args, null);
            } else {
                result = Datum.VOID;
            }
        }
        if (!noRet) {
            ctx.push(result);
        }
        return true;
    }

    private static boolean objCall(ExecutionContext ctx) {
        String methodName = ctx.resolveName(ctx.getArgument());
        Datum argListDatum = ctx.pop();
        boolean noRet = argListDatum instanceof Datum.ArgListNoRet;
        List<Datum> args = getArgs(argListDatum);
        Datum target = args.isEmpty() ? Datum.VOID : args.remove(0);

        Datum result = dispatchMethod(ctx, target, methodName, args);

        if (!noRet) {
            ctx.push(result);
        }
        return true;
    }

    /**
     * Dispatch a method call to the appropriate handler based on target type.
     */
    private static Datum dispatchMethod(ExecutionContext ctx, Datum target,
                                        String methodName, List<Datum> args) {
        return switch (target) {
            case Datum.List list -> handleListMethod(list, methodName, args);
            case Datum.PropList propList -> handlePropListMethod(propList, methodName, args);
            case Datum.ScriptInstance instance -> handleScriptInstanceMethod(ctx, instance, methodName, args);
            case Datum.ScriptRef scriptRef -> handleScriptRefMethod(ctx, scriptRef, methodName, args);
            case Datum.Point point -> handlePointMethod(point, methodName, args);
            case Datum.Rect rect -> handleRectMethod(rect, methodName, args);
            case Datum.Str str -> handleStringMethod(str, methodName, args);
            default -> {
                // Try to find the method as a global handler (with target as first arg)
                if (ctx.isBuiltin(methodName)) {
                    List<Datum> fullArgs = new ArrayList<>();
                    fullArgs.add(target);
                    fullArgs.addAll(args);
                    yield ctx.invokeBuiltin(methodName, fullArgs);
                }
                yield Datum.VOID;
            }
        };
    }

    /**
     * Handle method calls on linear lists.
     */
    private static Datum handleListMethod(Datum.List list, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        return switch (method) {
            case "count" -> {
                // count(list) or count(list, #item)
                yield Datum.of(list.items().size());
            }
            case "getat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt() - 1; // 1-indexed
                if (index >= 0 && index < list.items().size()) {
                    yield list.items().get(index);
                }
                yield Datum.VOID;
            }
            case "setat" -> {
                // setAt(list, position, value) - set value at position (1-indexed)
                // Like dirplayer-rs: pads with VOID if index > current length
                if (args.size() < 2) yield Datum.VOID;
                int index = args.get(0).toInt() - 1; // Convert to 0-indexed
                Datum value = args.get(1);
                if (index < 0) yield Datum.VOID;
                if (index < list.items().size()) {
                    list.items().set(index, value);
                } else {
                    // Pad with VOID values up to the target index
                    while (list.items().size() < index) {
                        list.items().add(Datum.VOID);
                    }
                    list.items().add(value);
                }
                yield Datum.VOID;
            }
            case "append", "add" -> {
                if (args.isEmpty()) yield Datum.VOID;
                list.items().add(args.get(0));
                yield Datum.VOID;
            }
            case "addat" -> {
                // addAt(list, position, value) - insert value at position (1-indexed)
                // If the element at the target position is VOID, replace it instead of inserting
                // This allows VOID to act as a placeholder that gets filled in
                if (args.size() < 2) yield Datum.VOID;
                int index = args.get(0).toInt() - 1; // Convert to 0-indexed
                Datum value = args.get(1);
                if (index < 0) index = 0;
                if (index < list.items().size() && list.items().get(index).isVoid()) {
                    // Replace VOID placeholder instead of inserting
                    list.items().set(index, value);
                } else if (index >= list.items().size()) {
                    // Pad with VOID if needed, then add
                    while (list.items().size() < index) {
                        list.items().add(Datum.VOID);
                    }
                    list.items().add(value);
                } else {
                    list.items().add(index, value);
                }
                yield Datum.VOID;
            }
            case "deleteat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt() - 1;
                if (index >= 0 && index < list.items().size()) {
                    list.items().remove(index);
                }
                yield Datum.VOID;
            }
            case "getone", "findpos" -> {
                // Find 1-based index of value, returns 0 if not found
                if (args.isEmpty()) yield Datum.ZERO;
                Datum value = args.get(0);
                for (int i = 0; i < list.items().size(); i++) {
                    if (list.items().get(i).equals(value)) {
                        yield Datum.of(i + 1);
                    }
                }
                yield Datum.ZERO;
            }
            case "getlast" -> {
                // getLast(list) - return the last element
                if (list.items().isEmpty()) yield Datum.VOID;
                yield list.items().get(list.items().size() - 1);
            }
            case "deleteone" -> {
                // deleteOne(list, value) - remove first matching element
                if (args.isEmpty()) yield Datum.VOID;
                Datum value = args.get(0);
                for (int i = 0; i < list.items().size(); i++) {
                    if (list.items().get(i).equals(value)) {
                        list.items().remove(i);
                        break;
                    }
                }
                yield Datum.VOID;
            }
            case "join" -> {
                // join(list, separator) - concatenate elements into string
                String separator = args.isEmpty() ? "" : args.get(0).toStr();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < list.items().size(); i++) {
                    if (i > 0) sb.append(separator);
                    sb.append(list.items().get(i).toStr());
                }
                yield Datum.of(sb.toString());
            }
            case "sort" -> {
                // Sort list in place (simple implementation)
                list.items().sort((a, b) -> {
                    if (a instanceof Datum.Int ai && b instanceof Datum.Int bi) {
                        return Integer.compare(ai.value(), bi.value());
                    }
                    return a.toStr().compareToIgnoreCase(b.toStr());
                });
                yield Datum.VOID;
            }
            case "duplicate" -> {
                yield new Datum.List(new ArrayList<>(list.items()));
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Handle method calls on property lists.
     */
    private static Datum handlePropListMethod(Datum.PropList propList, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        return switch (method) {
            case "count" -> Datum.of(propList.properties().size());
            case "getat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                Datum keyOrIndex = args.get(0);
                // Support both string/symbol key lookup and integer index lookup
                if (keyOrIndex instanceof Datum.Str s) {
                    yield propList.properties().getOrDefault(s.value(), Datum.VOID);
                } else if (keyOrIndex instanceof Datum.Symbol sym) {
                    yield propList.properties().getOrDefault(sym.name(), Datum.VOID);
                } else {
                    // Integer index (1-based)
                    int index = keyOrIndex.toInt() - 1;
                    var entries = new ArrayList<>(propList.properties().entrySet());
                    if (index >= 0 && index < entries.size()) {
                        yield entries.get(index).getValue();
                    }
                    yield Datum.VOID;
                }
            }
            case "getprop", "getaprop" -> {
                if (args.isEmpty()) yield Datum.VOID;
                String key = args.get(0) instanceof Datum.Symbol s ? s.name() : args.get(0).toStr();
                yield propList.properties().getOrDefault(key, Datum.VOID);
            }
            case "setprop", "setaprop" -> {
                if (args.size() < 2) yield Datum.VOID;
                String key = args.get(0) instanceof Datum.Symbol s ? s.name() : args.get(0).toStr();
                propList.properties().put(key, args.get(1));
                yield Datum.VOID;
            }
            case "addprop" -> {
                if (args.size() < 2) yield Datum.VOID;
                String key = args.get(0) instanceof Datum.Symbol s ? s.name() : args.get(0).toStr();
                propList.properties().put(key, args.get(1));
                yield Datum.VOID;
            }
            case "deleteprop" -> {
                if (args.isEmpty()) yield Datum.VOID;
                String key = args.get(0) instanceof Datum.Symbol s ? s.name() : args.get(0).toStr();
                propList.properties().remove(key);
                yield Datum.VOID;
            }
            case "getpropat" -> {
                // Get the key at position
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt() - 1;
                var keys = new ArrayList<>(propList.properties().keySet());
                if (index >= 0 && index < keys.size()) {
                    yield Datum.symbol(keys.get(index));
                }
                yield Datum.VOID;
            }
            case "setat" -> {
                // setAt(propList, key, value) - add or update a property
                if (args.size() < 2) yield Datum.VOID;
                String key = args.get(0) instanceof Datum.Symbol s ? s.name() : args.get(0).toStr();
                Datum value = args.get(1);
                propList.properties().put(key, value);
                yield Datum.VOID;
            }
            case "findpos" -> {
                // Find position of key
                if (args.isEmpty()) yield Datum.ZERO;
                String key = args.get(0) instanceof Datum.Symbol s ? s.name() : args.get(0).toStr();
                int pos = 1;
                for (String k : propList.properties().keySet()) {
                    if (k.equalsIgnoreCase(key)) {
                        yield Datum.of(pos);
                    }
                    pos++;
                }
                yield Datum.ZERO;
            }
            case "duplicate" -> {
                yield new Datum.PropList(new LinkedHashMap<>(propList.properties()));
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Handle method calls on script instances.
     * Dispatches to handlers defined in the script.
     * Matches dirplayer-rs: built-in methods are handled first, then Lingo handlers.
     */
    private static Datum handleScriptInstanceMethod(ExecutionContext ctx, Datum.ScriptInstance instance,
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
                return getPropertyFromAncestorChain(instance, key);
            }
            case "getaprop" -> {
                // getaProp(instance, #propName) - simple single-arg property lookup
                if (args.isEmpty()) return Datum.VOID;
                String propName = getPropertyName(args.get(0));
                return getPropertyFromAncestorChain(instance, propName);
            }
            case "getprop", "getpropref" -> {
                // getProp(instance, #propName) - single arg: get property
                // getProp(instance, #propName, key) - two args: get property, then look up key in it
                if (args.isEmpty()) return Datum.VOID;
                String localPropName = getPropertyName(args.get(0));
                Datum localProp = getPropertyFromAncestorChain(instance, localPropName);

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
        Datum propValue = getPropertyFromAncestorChain(instance, prop);
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
     * Get the script ID from a script instance.
     * Uses __scriptRef__ if available (for proper handler dispatch), otherwise falls back to scriptId.
     */
    private static int getScriptIdFromInstance(Datum.ScriptInstance instance) {
        Datum.ScriptRef scriptRef = getScriptRefFromInstance(instance);
        if (scriptRef != null) {
            return scriptRef.member();
        }
        // Fallback to instance scriptId (may not work for handler dispatch)
        return instance.scriptId();
    }

    /**
     * Get a property from an instance, walking the ancestor chain if not found.
     */
    private static Datum getPropertyFromAncestorChain(Datum.ScriptInstance instance, String propName) {
        Datum.ScriptInstance current = instance;
        for (int i = 0; i < 100; i++) { // Safety limit
            if (current.properties().containsKey(propName)) {
                return current.properties().get(propName);
            }

            // Try ancestor
            Datum ancestor = current.properties().get("ancestor");
            if (ancestor instanceof Datum.ScriptInstance ancestorInstance) {
                current = ancestorInstance;
            } else {
                break;
            }
        }
        return Datum.VOID;
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
     * Handle method calls on script references (e.g., calling new() on a script).
     */
    private static Datum handleScriptRefMethod(ExecutionContext ctx, Datum.ScriptRef scriptRef,
                                               String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        if ("new".equals(method)) {
            // Create a new instance of the script
            List<Datum> fullArgs = new ArrayList<>();
            fullArgs.add(scriptRef);
            fullArgs.addAll(args);
            return ctx.invokeBuiltin("new", fullArgs);
        }
        return Datum.VOID;
    }

    /**
     * Handle method calls on point values.
     */
    private static Datum handlePointMethod(Datum.Point point, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        return switch (method) {
            case "getat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt();
                yield switch (index) {
                    case 1 -> Datum.of(point.x());
                    case 2 -> Datum.of(point.y());
                    default -> Datum.VOID;
                };
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Handle method calls on rect values.
     */
    private static Datum handleRectMethod(Datum.Rect rect, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        return switch (method) {
            case "getat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt();
                yield switch (index) {
                    case 1 -> Datum.of(rect.left());
                    case 2 -> Datum.of(rect.top());
                    case 3 -> Datum.of(rect.right());
                    case 4 -> Datum.of(rect.bottom());
                    default -> Datum.VOID;
                };
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Handle method calls on string values.
     * Supports Lingo string chunk operations like count, getProp, getPropRef.
     */
    private static Datum handleStringMethod(Datum.Str str, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        // Get the item delimiter (default comma)
        char itemDelimiter = getItemDelimiter();

        return switch (method) {
            case "length" -> Datum.of(str.value().length());
            case "char" -> {
                if (args.isEmpty()) yield Datum.EMPTY_STRING;
                int index = args.get(0).toInt();
                if (index >= 1 && index <= str.value().length()) {
                    yield Datum.of(String.valueOf(str.value().charAt(index - 1)));
                }
                yield Datum.EMPTY_STRING;
            }
            case "count" -> {
                // count(str, #char) or count(str, #word) etc.
                if (args.isEmpty()) yield Datum.of(str.value().length());
                Datum chunkType = args.get(0);
                if (chunkType instanceof Datum.Symbol s) {
                    String type = s.name().toLowerCase();
                    yield switch (type) {
                        case "char" -> Datum.of(str.value().length());
                        case "word" -> Datum.of(countWords(str.value()));
                        case "line" -> Datum.of(countLines(str.value()));
                        case "item" -> Datum.of(countItems(str.value(), itemDelimiter));
                        default -> Datum.of(str.value().length());
                    };
                }
                yield Datum.of(str.value().length());
            }
            case "getpropref" -> {
                // getPropRef(str, #chunkType, index) - gets a single chunk from string
                // e.g., getPropRef(str, #item, 1) gets the first item
                // e.g., getPropRef(str, #word, 2) gets the second word
                if (args.size() < 2) yield Datum.EMPTY_STRING;

                Datum chunkType = args.get(0);
                int index = args.get(1).toInt();

                if (!(chunkType instanceof Datum.Symbol s)) {
                    yield Datum.EMPTY_STRING;
                }

                String type = s.name().toLowerCase();
                String result = getStringChunk(str.value(), type, index, index, itemDelimiter);
                yield Datum.of(result);
            }
            case "getprop" -> {
                // getProp(str, #chunkType, startIndex, endIndex?)
                // e.g., getProp(str, #char, 1, 5) gets chars 1-5
                // e.g., getProp(str, #word, 1, count(str, #word)) gets word 1 to last
                if (args.size() < 2) yield Datum.EMPTY_STRING;

                Datum chunkType = args.get(0);
                int startIndex = args.get(1).toInt();
                int endIndex = args.size() >= 3 ? args.get(2).toInt() : startIndex;

                if (!(chunkType instanceof Datum.Symbol s)) {
                    yield Datum.EMPTY_STRING;
                }

                String type = s.name().toLowerCase();
                String result = getStringChunk(str.value(), type, startIndex, endIndex, itemDelimiter);
                yield Datum.of(result);
            }
            default -> Datum.VOID;
        };
    }

    /**
     * Get a chunk from a string.
     */
    private static String getStringChunk(String str, String chunkType, int start, int end, char itemDelimiter) {
        if (str.isEmpty() || start < 1) return "";

        return switch (chunkType) {
            case "char" -> {
                int s = Math.max(0, start - 1);
                int e = Math.min(str.length(), end);
                if (s >= str.length() || s >= e) yield "";
                yield str.substring(s, e);
            }
            case "word" -> {
                String[] words = str.trim().split("\\s+");
                if (start > words.length) yield "";
                int s = start - 1;
                int e = Math.min(words.length, end);
                StringBuilder sb = new StringBuilder();
                for (int i = s; i < e; i++) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(words[i]);
                }
                yield sb.toString();
            }
            case "line" -> {
                String[] lines = str.split("\r\n|\r|\n", -1);
                if (start > lines.length) yield "";
                int s = start - 1;
                int e = Math.min(lines.length, end);
                StringBuilder sb = new StringBuilder();
                for (int i = s; i < e; i++) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(lines[i]);
                }
                yield sb.toString();
            }
            case "item" -> {
                // Simple split like dirplayer-rs - no bracket/quote awareness
                String[] items = str.split(String.valueOf(itemDelimiter), -1);
                if (start > items.length) yield "";
                int s = start - 1;
                int e = Math.min(items.length, end);
                StringBuilder sb = new StringBuilder();
                for (int i = s; i < e; i++) {
                    if (sb.length() > 0) sb.append(itemDelimiter);
                    sb.append(items[i]);
                }
                yield sb.toString();
            }
            default -> "";
        };
    }

    /**
     * Get the current item delimiter from MoviePropertyProvider.
     */
    private static char getItemDelimiter() {
        var provider = com.libreshockwave.vm.builtin.MoviePropertyProvider.getProvider();
        if (provider != null) {
            return provider.getItemDelimiter();
        }
        return ',';
    }

    private static int countWords(String str) {
        if (str.isEmpty()) return 0;
        return str.trim().split("\\s+").length;
    }

    private static int countLines(String str) {
        if (str.isEmpty()) return 0;
        return str.split("\r\n|\r|\n", -1).length;
    }

    private static int countItems(String str, char delimiter) {
        if (str.isEmpty()) return 0;
        // Simple split like dirplayer-rs
        return str.split(String.valueOf(delimiter), -1).length;
    }

    /**
     * Extract arguments from an arglist datum.
     * Arguments are stored directly in the ArgList/ArgListNoRet items.
     */
    private static List<Datum> getArgs(Datum argListDatum) {
        if (argListDatum instanceof Datum.ArgList al) {
            return new java.util.ArrayList<>(al.items());
        } else if (argListDatum instanceof Datum.ArgListNoRet al) {
            return new java.util.ArrayList<>(al.items());
        } else {
            // Fallback - shouldn't happen with correct bytecode
            return new java.util.ArrayList<>();
        }
    }
}
