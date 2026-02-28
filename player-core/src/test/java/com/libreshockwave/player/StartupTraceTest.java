package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.TraceListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Traces the DCR startup flow from the first event dispatch through
 * getThreadManager().create(#core, #core) and beyond, looking for
 * visual output (sprite/window/bitmap operations).
 *
 * Run: ./gradlew :player-core:runStartupTraceTest
 * Requires: C:/SourceControl/habbo.dcr
 */
public class StartupTraceTest {

    private static final String TEST_FILE = "C:/SourceControl/habbo.dcr";
    private static final int MAX_RESULT_LEN = 80;
    private static final int POST_TARGET_FRAMES = 10;

    private static volatile boolean targetReached = false;
    private static int targetEntryIndex = -1;

    /** Keywords that suggest visual rendering activity. */
    private static final Pattern VISUAL_PATTERN = Pattern.compile(
            "(?i)(visualiz|window|sprite|draw|render|image|bitmap|room|view|loading|bar|stage|display|screen|pixel|rect|quad)");

    /** Recorded call tree entry. */
    record CallEntry(int depth, boolean isEnter, String text, CallKind kind) {
        enum CallKind { HANDLER_ENTER, HANDLER_EXIT, ERROR, FRAME_MARKER, TARGET_MARKER, VISUAL_FLAG }

        @Override
        public String toString() {
            return "  ".repeat(depth) + text;
        }
    }

    public static void main(String[] args) throws IOException {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            return;
        }

        System.out.println("=== Loading habbo.dcr ===");
        DirectorFile file = DirectorFile.load(path);

        // --- Diagnostic: dump script handlers ---
        dumpScriptDiagnostics(file);

        Player player = new Player(file);
        LingoVM vm = player.getVM();

        vm.setStepLimit(500_000);

        List<CallEntry> callTree = new ArrayList<>();
        int[] depth = {0};
        int[] createCoreDepth = {-1};
        int[] currentFrame = {0};
        List<String> visualHits = new ArrayList<>();

        // --- Track frame boundaries via event listener ---
        player.setEventListener(info -> {
            if (info.event() == PlayerEvent.EXIT_FRAME) {
                callTree.add(new CallEntry(0, false,
                        "--- Frame " + info.frame() + " EXIT_FRAME ---",
                        CallEntry.CallKind.FRAME_MARKER));
            } else if (info.event() == PlayerEvent.ENTER_FRAME) {
                currentFrame[0] = info.frame();
                callTree.add(new CallEntry(0, false,
                        "--- Frame " + info.frame() + " ENTER_FRAME ---",
                        CallEntry.CallKind.FRAME_MARKER));
            } else if (info.event() == PlayerEvent.PREPARE_FRAME) {
                callTree.add(new CallEntry(0, false,
                        "--- Frame " + info.frame() + " PREPARE_FRAME ---",
                        CallEntry.CallKind.FRAME_MARKER));
            }
        });

        vm.setTraceListener(new TraceListener() {
            @Override
            public void onHandlerEnter(HandlerInfo info) {
                String argsStr = formatArgs(info.arguments());
                String label = "-> " + info.handlerName() + "(" + argsStr + ")  [" + info.scriptDisplayName() + "]";
                callTree.add(new CallEntry(depth[0], true, label, CallEntry.CallKind.HANDLER_ENTER));
                depth[0]++;

                // Flag visual-related handlers
                checkVisual(info.handlerName(), info.scriptDisplayName(), argsStr, label, depth[0] - 1, visualHits, callTree);

                // Detect target: create(#core, ...)
                if (info.handlerName().equals("create")) {
                    for (Datum arg : info.arguments()) {
                        if (arg instanceof Datum.Symbol sym && sym.name().equalsIgnoreCase("core")) {
                            createCoreDepth[0] = depth[0];
                            break;
                        }
                    }
                }
            }

            @Override
            public void onHandlerExit(HandlerInfo info, Datum result) {
                depth[0] = Math.max(0, depth[0] - 1);

                String resultStr = truncate(result == null ? "void" : result.toString());
                callTree.add(new CallEntry(depth[0], false,
                        "<- " + info.handlerName() + " = " + resultStr,
                        CallEntry.CallKind.HANDLER_EXIT));

                // Check if this is the create(#core) exit
                if (info.handlerName().equals("create") && createCoreDepth[0] >= 0
                        && depth[0] + 1 == createCoreDepth[0]) {
                    String marker = "*** TARGET REACHED: create(#core) returned " + resultStr + " ***";
                    callTree.add(new CallEntry(depth[0], false, marker, CallEntry.CallKind.TARGET_MARKER));
                    targetEntryIndex = callTree.size() - 1;
                    targetReached = true;
                    createCoreDepth[0] = -1;
                }
            }

            @Override
            public void onError(String message, Exception error) {
                callTree.add(new CallEntry(depth[0], false,
                        "!! ERROR: " + message, CallEntry.CallKind.ERROR));
            }

            private String formatArgs(List<Datum> arguments) {
                if (arguments.isEmpty()) return "";
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arguments.size(); i++) {
                    if (i > 0) sb.append(", ");
                    Datum arg = arguments.get(i);
                    if (arg instanceof Datum.Str s) {
                        String val = s.value();
                        if (val.length() > 30) val = val.substring(0, 27) + "...";
                        sb.append("\"").append(val).append("\"");
                    } else {
                        sb.append(arg);
                    }
                }
                return sb.toString();
            }
        });

        // --- Run startup ---
        System.out.println("=== Starting playback ===\n");
        player.play();

        // Wait for external cast to load
        for (int i = 0; i < 100 && !targetReached; i++) {
            var castLib2 = player.getCastLibManager().getCastLibs().get(2);
            if (castLib2 != null && castLib2.isLoaded()) break;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        // Step frames — continue past target for POST_TARGET_FRAMES more
        int framesPastTarget = 0;
        for (int frame = 0; frame < 50; frame++) {
            try {
                player.stepFrame();
            } catch (Exception e) {
                System.err.println("Error at frame step " + frame + ": " + e.getMessage());
                break;
            }

            if (targetReached) {
                framesPastTarget++;
                if (framesPastTarget >= POST_TARGET_FRAMES) break;
            }
        }

        // --- Print the SPINE: only handlers on the path to the target ---
        if (targetReached) {
            System.out.println("\n========================================");
            System.out.println("  STARTUP SPINE (path to target only)");
            System.out.println("========================================\n");
            printSpine(callTree);
        }

        // --- Print the full call tree (up to target) ---
        System.out.println("\n========================================");
        System.out.println("  FULL CALL TREE (up to target)");
        System.out.println("========================================\n");
        for (CallEntry entry : callTree) {
            System.out.println(entry);
            if (entry.kind() == CallEntry.CallKind.TARGET_MARKER) break;
        }

        // --- Print POST-TARGET TRACE ---
        if (targetReached && targetEntryIndex >= 0 && targetEntryIndex < callTree.size() - 1) {
            System.out.println("\n========================================");
            System.out.println("  POST-TARGET TRACE (" + framesPastTarget + " frames after target)");
            System.out.println("========================================\n");

            int postEntries = 0;
            for (int i = targetEntryIndex + 1; i < callTree.size(); i++) {
                CallEntry entry = callTree.get(i);
                System.out.println(entry);
                postEntries++;
            }

            if (postEntries == 0) {
                System.out.println("  (no handler activity after target)");
            }
        }

        // --- Print visual hits summary ---
        System.out.println("\n========================================");
        System.out.println("  VISUAL INDICATORS");
        System.out.println("========================================\n");
        if (visualHits.isEmpty()) {
            System.out.println("  (none detected)");
        } else {
            for (String hit : visualHits) {
                System.out.println("  * " + hit);
            }
        }

        // --- Print error summary ---
        System.out.println("\n========================================");
        System.out.println("  ERROR SUMMARY");
        System.out.println("========================================\n");
        List<CallEntry> errors = callTree.stream()
                .filter(e -> e.kind() == CallEntry.CallKind.ERROR)
                .toList();
        if (errors.isEmpty()) {
            System.out.println("  (no errors)");
        } else {
            // Deduplicate errors and count them
            var errorCounts = new java.util.LinkedHashMap<String, Integer>();
            for (CallEntry e : errors) {
                errorCounts.merge(e.text(), 1, Integer::sum);
            }
            for (var entry : errorCounts.entrySet()) {
                if (entry.getValue() > 1) {
                    System.out.println("  [x" + entry.getValue() + "] " + entry.getKey());
                } else {
                    System.out.println("  " + entry.getKey());
                }
            }
        }

        if (!targetReached) {
            System.out.println("\n=== Target not reached after stepping ===");
        }

        player.shutdown();
    }

    /** Check if a handler call matches visual-related keywords. */
    private static void checkVisual(String handlerName, String scriptName, String argsStr,
                                    String fullLabel, int depth, List<String> visualHits,
                                    List<CallEntry> callTree) {
        String combined = handlerName + " " + scriptName + " " + argsStr;
        if (VISUAL_PATTERN.matcher(combined).find()) {
            String hit = handlerName + "(" + argsStr + ") [" + scriptName + "]";
            visualHits.add(hit);
            callTree.add(new CallEntry(depth, false,
                    ">>> VISUAL: " + hit,
                    CallEntry.CallKind.VISUAL_FLAG));
        }
    }

    /**
     * Prints only the "spine" — handlers that are ancestors of the target.
     */
    private static void printSpine(List<CallEntry> entries) {
        // Find the target index
        int targetIdx = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).kind() == CallEntry.CallKind.TARGET_MARKER) {
                targetIdx = i;
                break;
            }
        }
        if (targetIdx < 0) return;

        // Walk forward and track which handlers are "open" at the target
        List<Integer> spineEnterIndices = new ArrayList<>();
        int[] openAtDepth = new int[200];
        java.util.Arrays.fill(openAtDepth, -1);

        for (int i = 0; i <= targetIdx; i++) {
            CallEntry e = entries.get(i);
            if (e.isEnter()) {
                openAtDepth[e.depth()] = i;
            }
        }

        for (int d = 0; d < openAtDepth.length; d++) {
            if (openAtDepth[d] >= 0) {
                spineEnterIndices.add(openAtDepth[d]);
            }
        }

        // Print spine entries with collapsed children
        int lastSpineDepth = -1;
        int skippedCalls = 0;

        for (int i = 0; i <= targetIdx; i++) {
            CallEntry e = entries.get(i);

            if (spineEnterIndices.contains(i)) {
                if (skippedCalls > 0) {
                    System.out.println("  ".repeat(lastSpineDepth + 1) + "   ... (" + skippedCalls + " calls collapsed)");
                    skippedCalls = 0;
                }
                System.out.println(e);
                lastSpineDepth = e.depth();
            } else if (e.kind() == CallEntry.CallKind.TARGET_MARKER) {
                if (skippedCalls > 0) {
                    System.out.println("  ".repeat(lastSpineDepth + 1) + "   ... (" + skippedCalls + " calls collapsed)");
                    skippedCalls = 0;
                }
                System.out.println(e);
            } else if (e.kind() == CallEntry.CallKind.FRAME_MARKER) {
                // Always show frame markers in the spine
                if (skippedCalls > 0) {
                    System.out.println("  ".repeat(lastSpineDepth + 1) + "   ... (" + skippedCalls + " calls collapsed)");
                    skippedCalls = 0;
                }
                System.out.println(e);
            } else if (e.isEnter()) {
                skippedCalls++;
            }
        }
    }

    private static String truncate(String s) {
        if (s.length() <= MAX_RESULT_LEN) return s;
        return s.substring(0, MAX_RESULT_LEN - 3) + "...";
    }

    /**
     * Dumps diagnostic info about script handlers — specifically looking for
     * stepFrame/update handlers and the Loop/Init score scripts.
     */
    private static void dumpScriptDiagnostics(DirectorFile file) {
        ScriptNamesChunk names = file.getScriptNames();
        if (names == null) {
            System.out.println("[DIAG] No script names chunk found");
            return;
        }

        System.out.println("\n========================================");
        System.out.println("  SCRIPT DIAGNOSTICS");
        System.out.println("========================================\n");

        // 1. Find Loop and Init scripts, dump their handlers
        for (ScriptChunk script : file.getScripts()) {
            String scriptName = script.getScriptName();
            if (scriptName != null && (scriptName.equalsIgnoreCase("Loop") || scriptName.equalsIgnoreCase("Init"))) {
                System.out.println("  Script: \"" + scriptName + "\" (" + script.getScriptType() + ")");
                System.out.println("  Handlers:");
                for (ScriptChunk.Handler handler : script.handlers()) {
                    String hName = script.getHandlerName(handler);
                    System.out.println("    - " + hName);
                }

                // Dump bytecode for exitFrame handler
                ScriptChunk.Handler exitFrame = script.findHandler("exitFrame");
                if (exitFrame != null) {
                    System.out.println("  exitFrame bytecode:");
                    for (ScriptChunk.Handler.Instruction instr : exitFrame.instructions()) {
                        String litInfo = "";
                        // Try to resolve push constants
                        if (instr.opcode().name().contains("PUSH") || instr.opcode().name().contains("EXT_CALL")) {
                            litInfo = resolveLiteral(script, names, instr);
                        }
                        System.out.printf("    [%04d] %-20s %d%s%n",
                                instr.offset(), instr.opcode(), instr.argument(), litInfo);
                    }
                }
                System.out.println();
            }
        }

        // 2. Search ALL scripts for stepFrame or update handlers
        System.out.println("  Scripts with stepFrame/update handlers:");
        boolean found = false;
        for (ScriptChunk script : file.getScripts()) {
            for (ScriptChunk.Handler handler : script.handlers()) {
                String hName = script.getHandlerName(handler);
                if (hName != null && (hName.equalsIgnoreCase("stepFrame") || hName.equalsIgnoreCase("update"))) {
                    String scriptName = script.getScriptName();
                    if (scriptName == null) scriptName = "script#" + script.id();
                    System.out.println("    - " + scriptName + " (" + script.getScriptType() + ") :: " + hName);
                    found = true;
                }
            }
        }
        if (!found) {
            System.out.println("    (none found in any script!)");
        }

        System.out.println();
    }

    /** Try to resolve literal/name info for an instruction. */
    private static String resolveLiteral(ScriptChunk script, ScriptNamesChunk names, ScriptChunk.Handler.Instruction instr) {
        try {
            String opName = instr.opcode().name();
            if (opName.contains("EXT_CALL") || opName.contains("CALL")) {
                String name = names.getName(instr.argument());
                if (name != null) return "  ; " + name;
            }
            if (opName.contains("PUSH_CONS")) {
                var literals = script.literals();
                if (instr.argument() >= 0 && instr.argument() < literals.size()) {
                    return "  ; " + literals.get(instr.argument());
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }
}
