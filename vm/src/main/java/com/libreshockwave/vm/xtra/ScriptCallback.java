package com.libreshockwave.vm.xtra;

import com.libreshockwave.vm.Datum;

import java.util.List;

/**
 * Callback interface for invoking Lingo handlers on script instances.
 * Used by Xtras (e.g., MultiuserXtra) that need to fire callbacks
 * into Lingo code during handler execution.
 */
@FunctionalInterface
public interface ScriptCallback {
    void invoke(Datum target, String handlerName, List<Datum> args);
}
