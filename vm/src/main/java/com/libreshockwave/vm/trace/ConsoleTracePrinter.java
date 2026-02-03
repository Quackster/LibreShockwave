package com.libreshockwave.vm.trace;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.TraceListener;

import java.util.HashSet;
import java.util.Set;

/**
 * Formats and prints trace output to the console.
 * Implements TraceListener for use with the VM's trace system.
 *
 * Output format matches dirplayer-rs for consistency:
 * - Handler entry: "== Script: <name> Handler: <name>"
 * - Instructions: "--> [offset] OPCODE arg ... annotation"
 * - Handler exit: "== <handler> returned <value>" (non-void only)
 */
public class ConsoleTracePrinter implements TraceListener {

    // Track visited instruction offsets per handler to suppress loop repetitions
    private final Set<Integer> visitedOffsets = new HashSet<>();
    private boolean loopSuppressed = false;
    private int currentHandlerId = -1;

    /**
     * Reset visited tracking for a new handler.
     */
    private void resetForHandler(int handlerId) {
        if (handlerId != currentHandlerId) {
            visitedOffsets.clear();
            loopSuppressed = false;
            currentHandlerId = handlerId;
        }
    }

    /**
     * Check if this instruction has been visited before (for loop suppression).
     * Returns true if we should skip printing this instruction.
     */
    private boolean shouldSuppressInstruction(int offset) {
        if (visitedOffsets.contains(offset)) {
            if (!loopSuppressed) {
                System.out.println("    ... [loop iterations suppressed] ...");
                loopSuppressed = true;
            }
            return true;
        }
        visitedOffsets.add(offset);
        loopSuppressed = false;
        return false;
    }

    @Override
    public void onHandlerEnter(HandlerInfo info) {
        resetForHandler(info.scriptId());
        System.out.println("== Script: " + info.scriptDisplayName() + " Handler: " + info.handlerName());
    }

    @Override
    public void onHandlerExit(HandlerInfo info, Datum returnValue) {
        if (!(returnValue instanceof Datum.Void)) {
            System.out.println("== " + info.handlerName() + " returned " + returnValue);
        }
    }

    @Override
    public void onInstruction(InstructionInfo info) {
        // Suppress repeated instructions (loop iterations)
        if (shouldSuppressInstruction(info.offset())) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("--> [%3d] %-16s", info.offset(), info.opcode()));
        if (info.argument() != 0) {
            sb.append(String.format(" %d", info.argument()));
        }
        while (sb.length() < 38) {
            sb.append('.');
        }
        if (!info.annotation().isEmpty()) {
            sb.append(' ').append(info.annotation());
        }
        System.out.println(sb);
    }

    /**
     * Format an instruction for display (without printing).
     * Useful for building trace strings programmatically.
     */
    public static String formatInstruction(InstructionInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("--> [%3d] %-16s", info.offset(), info.opcode()));
        if (info.argument() != 0) {
            sb.append(String.format(" %d", info.argument()));
        }
        while (sb.length() < 38) {
            sb.append('.');
        }
        if (!info.annotation().isEmpty()) {
            sb.append(' ').append(info.annotation());
        }
        return sb.toString();
    }

    /**
     * Format a handler entry for display (without printing).
     */
    public static String formatHandlerEnter(HandlerInfo info) {
        return "== Script: " + info.scriptDisplayName() + " Handler: " + info.handlerName();
    }

    /**
     * Format a handler exit for display (without printing).
     * Returns null for void return values.
     */
    public static String formatHandlerExit(HandlerInfo info, Datum returnValue) {
        if (returnValue instanceof Datum.Void) {
            return null;
        }
        return "== " + info.handlerName() + " returned " + returnValue;
    }
}
