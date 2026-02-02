package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Constructor builtin functions (point, rect, color).
 */
public final class ConstructorBuiltins {

    private ConstructorBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("point", ConstructorBuiltins::point);
        builtins.put("rect", ConstructorBuiltins::rect);
        builtins.put("color", ConstructorBuiltins::color);
        builtins.put("new", ConstructorBuiltins::newInstance);
    }

    /**
     * new(obj, args...)
     * Creates a new instance of an Xtra or parent script.
     * Example: set instance = new(xtra("Multiuser"))
     * Example: set obj = new(script("MyScript"), arg1, arg2)
     */
    private static Datum newInstance(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.VOID;
        }

        Datum target = args.get(0);
        java.util.List<Datum> constructorArgs = args.size() > 1
            ? new java.util.ArrayList<>(args.subList(1, args.size()))
            : new java.util.ArrayList<>();

        // Handle Xtra instances
        if (target instanceof Datum.XtraRef xtraRef) {
            return XtraBuiltins.createInstance(xtraRef, constructorArgs);
        }

        // Handle script references - create a new script instance
        if (target instanceof Datum.ScriptRef scriptRef) {
            return createScriptInstance(vm, scriptRef, constructorArgs);
        }

        // Handle parent scripts (ScriptInstance) - clone behavior
        if (target instanceof Datum.ScriptInstance) {
            // TODO: Clone/create instance of parent script
            return Datum.VOID;
        }

        return Datum.VOID;
    }

    /**
     * Create a new script instance from a ScriptRef.
     * Looks up the script by cast member reference and creates an instance.
     */
    private static Datum createScriptInstance(LingoVM vm, Datum.ScriptRef scriptRef,
                                               java.util.List<Datum> args) {
        // Create a new ScriptInstance with unique ID
        int instanceId = nextInstanceId++;
        java.util.Map<String, Datum> properties = new java.util.LinkedHashMap<>();

        // Store the script reference for method dispatch
        properties.put("__scriptRef__", scriptRef);

        Datum.ScriptInstance instance = new Datum.ScriptInstance(instanceId, properties);

        // Call the "new" handler on the script if it exists
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider != null) {
            var location = provider.findHandler("new");
            if (location != null && location.script() != null && location.handler() != null) {
                // Call new() with the instance as receiver
                if (location.script() instanceof com.libreshockwave.chunks.ScriptChunk script
                        && location.handler() instanceof com.libreshockwave.chunks.ScriptChunk.Handler handler) {
                    // Add "me" as first argument (the new instance)
                    java.util.List<Datum> newArgs = new java.util.ArrayList<>();
                    newArgs.add(instance);
                    newArgs.addAll(args);
                    vm.executeHandler(script, handler, newArgs, instance);
                }
            }
        }

        return instance;
    }

    private static int nextInstanceId = 1;

    private static Datum point(LingoVM vm, List<Datum> args) {
        int x = args.size() > 0 ? args.get(0).toInt() : 0;
        int y = args.size() > 1 ? args.get(1).toInt() : 0;
        return new Datum.Point(x, y);
    }

    private static Datum rect(LingoVM vm, List<Datum> args) {
        int left = args.size() > 0 ? args.get(0).toInt() : 0;
        int top = args.size() > 1 ? args.get(1).toInt() : 0;
        int right = args.size() > 2 ? args.get(2).toInt() : 0;
        int bottom = args.size() > 3 ? args.get(3).toInt() : 0;
        return new Datum.Rect(left, top, right, bottom);
    }

    private static Datum color(LingoVM vm, List<Datum> args) {
        int r = args.size() > 0 ? args.get(0).toInt() : 0;
        int g = args.size() > 1 ? args.get(1).toInt() : 0;
        int b = args.size() > 2 ? args.get(2).toInt() : 0;
        return new Datum.Color(r, g, b);
    }
}
