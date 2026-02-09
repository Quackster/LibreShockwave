package com.libreshockwave.vm.trace;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.Scope;
import com.libreshockwave.vm.TraceListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper for building trace information and annotations.
 * Builds data structures for tracing - does not perform any I/O.
 * For console output, see {@link ConsoleTracePrinter}.
 */
public class TracingHelper {

    public TracingHelper() {
    }

    /**
     * Build instruction info for tracing.
     */
    public TraceListener.InstructionInfo buildInstructionInfo(Scope scope, ScriptChunk.Handler.Instruction instr,
                                                              Map<String, Datum> globals) {
        String annotation = buildAnnotation(scope, instr);
        List<Datum> stackSnapshot = new ArrayList<>();
        // Capture up to 10 stack items with deep copy to prevent mutation issues
        for (int i = 0; i < Math.min(10, scope.stackSize()); i++) {
            stackSnapshot.add(scope.peek(i).deepCopy());
        }

        // Capture locals (named local variables + parameters)
        Map<String, Datum> localsSnapshot = captureLocals(scope);

        // Capture globals (shallow copy of current state)
        Map<String, Datum> globalsSnapshot = globals != null ? new LinkedHashMap<>(globals) : Map.of();

        return new TraceListener.InstructionInfo(
            scope.getBytecodeIndex(),
            instr.offset(),
            instr.opcode().name(),
            instr.argument(),
            annotation,
            scope.stackSize(),
            stackSnapshot,
            localsSnapshot,
            globalsSnapshot
        );
    }

    /**
     * Capture named local variables and parameters from the scope.
     */
    private Map<String, Datum> captureLocals(Scope scope) {
        Map<String, Datum> locals = new LinkedHashMap<>();
        ScriptChunk script = scope.getScript();
        ScriptChunk.Handler handler = scope.getHandler();

        // Capture parameters
        List<Integer> argNameIds = handler.argNameIds();
        for (int i = 0; i < argNameIds.size(); i++) {
            String name = script.resolveName(argNameIds.get(i));
            locals.put(name, scope.getParam(i).deepCopy());
        }

        // Capture local variables
        List<Integer> localNameIds = handler.localNameIds();
        for (int i = 0; i < localNameIds.size(); i++) {
            String name = script.resolveName(localNameIds.get(i));
            locals.put(name, scope.getLocal(i).deepCopy());
        }

        return locals;
    }

    /**
     * Build handler info for tracing.
     */
    public TraceListener.HandlerInfo buildHandlerInfo(
            ScriptChunk script,
            ScriptChunk.Handler handler,
            List<Datum> args,
            Datum receiver,
            Map<String, Datum> globals) {
        return new TraceListener.HandlerInfo(
            script.getHandlerName(handler),
            script.id(),
            script.getDisplayName(),
            args,
            receiver,
            new HashMap<>(globals),
            script.literals(),
            handler.localCount(),
            handler.argCount()
        );
    }

    /**
     * Build annotation string for an instruction.
     * Uses the scope's script for name resolution (without local/param name resolution).
     */
    public String buildAnnotation(Scope scope, ScriptChunk.Handler.Instruction instr) {
        return InstructionAnnotator.annotate(scope.getScript(), instr);
    }
}
