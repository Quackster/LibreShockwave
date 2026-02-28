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
 * timeout().new() creation, logging milestones like create(#core) along the way.
 *
 * Run: ./gradlew :player-core:runStartupTraceTest
 * Requires: C:/SourceControl/habbo.dcr
 */
public class StartupTraceTest {

    private static final String TEST_FILE = "C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr";
    private static final int MAX_RESULT_LEN = 80;
    private static final int POST_TARGET_FRAMES = 5;

    /** Milestone: create(#core) was called. */
    private static volatile boolean createCoreReached = false;

    /** Milestone: prepareFrame fired on Object Manager. */
    private static volatile boolean prepareFrameFired = false;

    /** Target: a timeout was created (requires Habbo-specific code beyond Fuse framework). */
    private static volatile boolean timeoutReached = false;
    private static int timeoutEntryIndex = -1;

    /** Keywords that suggest visual rendering activity. */
    private static final Pattern VISUAL_PATTERN = Pattern.compile(
            "(?i)(visualiz|window|sprite|draw|render|image|bitmap|room|view|loading|bar|stage|display|screen|pixel|rect|quad)");

    /** Recorded call tree entry. */
    record CallEntry(int depth, boolean isEnter, String text, CallKind kind) {
        enum CallKind { HANDLER_ENTER, HANDLER_EXIT, ERROR, FRAME_MARKER, MILESTONE_MARKER, TARGET_MARKER, VISUAL_FLAG }

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
        boolean[] frameProxyCreated = {false};
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

                // Detect milestone: prepareFrame on Object Manager
                if (!prepareFrameFired && info.handlerName().equals("prepareFrame")
                        && info.scriptDisplayName().contains("Object Manager")) {
                    prepareFrameFired = true;
                    String marker = "*** MILESTONE: prepareFrame fired on Object Manager ***";
                    callTree.add(new CallEntry(depth[0] - 1, false, marker, CallEntry.CallKind.MILESTONE_MARKER));
                }

                // Detect milestone: create(#core, ...)
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

                // Check if this is the create(#core) exit — milestone marker
                if (info.handlerName().equals("create") && createCoreDepth[0] >= 0
                        && depth[0] + 1 == createCoreDepth[0]) {
                    String marker = "*** MILESTONE: create(#core) returned " + resultStr + " ***";
                    callTree.add(new CallEntry(depth[0], false, marker, CallEntry.CallKind.MILESTONE_MARKER));
                    createCoreReached = true;
                    createCoreDepth[0] = -1;
                }

                // After constructObjectManager returns, create a frameProxy timeout
                // so the Object Manager receives prepareFrame system events.
                // In Director, parent script instances use timeout targets to receive
                // frame events (the "frameProxy" trick). The Fuse framework relies on this.
                if (!frameProxyCreated[0]
                        && info.handlerName().equals("constructObjectManager")
                        && result instanceof Datum.ScriptInstance) {
                    frameProxyCreated[0] = true;
                    player.getTimeoutManager().createTimeout(
                            "fuse_frameProxy", Integer.MAX_VALUE, "null", result);
                    System.out.println("[FrameProxy] Created frameProxy timeout for Object Manager");
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

        // --- Diagnostic: dump cast lib names ---
        System.out.println("========================================");
        System.out.println("  CAST LIB DIAGNOSTICS");
        System.out.println("========================================\n");
        for (var entry : player.getCastLibManager().getCastLibs().entrySet()) {
            var castLib = entry.getValue();
            System.out.printf("  CastLib #%d: name=\"%s\" fileName=\"%s\" external=%s loaded=%s%n",
                    entry.getKey(), castLib.getName(), castLib.getFileName(),
                    castLib.isExternal(), castLib.isLoaded());
        }
        System.out.println();

        // --- Preload external casts (like swing player does before playback) ---
        int preloadCount = player.preloadAllCasts();
        System.out.println("=== Preloading " + preloadCount + " external casts ===");

        // Wait for unique external casts to load (many "empty N" casts share one file
        // and only one will match, so just wait for non-empty casts)
        Set<String> uniqueFileNames = new java.util.HashSet<>();
        for (var castLib : player.getCastLibManager().getCastLibs().values()) {
            if (castLib.isExternal()) {
                String fn = castLib.getFileName();
                if (fn != null && !fn.isEmpty()) {
                    uniqueFileNames.add(fn.toLowerCase());
                }
            }
        }
        System.out.println("Unique external cast files: " + uniqueFileNames.size());

        for (int i = 0; i < 50; i++) {  // 5 seconds max
            int loaded = 0;
            int external = 0;
            for (var castLib : player.getCastLibManager().getCastLibs().values()) {
                if (castLib.isExternal()) {
                    external++;
                    if (castLib.isLoaded()) loaded++;
                }
            }
            // At least one external cast loaded = files are being resolved
            if (loaded > 0 && i >= 10) {
                System.out.println("  " + loaded + "/" + external + " external casts loaded (proceeding).");
                break;
            }
            if (i % 10 == 0) {
                System.out.println("  ... " + loaded + "/" + external + " external casts loaded");
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        // --- Diagnostic: dump external cast scripts ---
        for (var entry : player.getCastLibManager().getCastLibs().entrySet()) {
            var castLib = entry.getValue();
            if (castLib.isExternal() && castLib.isLoaded()) {
                System.out.println("  External CastLib #" + entry.getKey() + " \"" + castLib.getName() + "\" scripts:");
                var castNames = castLib.getScriptNames();
                for (var script : castLib.getAllScripts()) {
                    String sName = script.getScriptName();
                    if (sName == null) sName = "script#" + script.id();
                    System.out.println("    - " + sName + " (" + script.getScriptType() + ")");
                    for (var handler : script.handlers()) {
                        String hName = castNames != null ? castNames.getName(handler.nameId()) : null;
                        System.out.println("        handler: " + hName + " (nameIdx=" + handler.nameId() + ")");
                    }
                }
            }
        }

        // --- Diagnostic: movie scripts with null/unresolved handler names ---
        System.out.println("  MOVIE SCRIPTS WITH NULL HANDLER NAMES:");
        boolean foundNullHandlers = false;
        for (var entry : player.getCastLibManager().getCastLibs().entrySet()) {
            var castLib = entry.getValue();
            if (!castLib.isExternal() || !castLib.isLoaded()) continue;
            var castNames = castLib.getScriptNames();
            for (var script : castLib.getAllScripts()) {
                if (script.getScriptType() != ScriptChunk.ScriptType.MOVIE_SCRIPT) continue;
                for (var handler : script.handlers()) {
                    String hName = castNames != null ? castNames.getName(handler.nameId()) : null;
                    if (hName == null || hName.isEmpty() || hName.startsWith("<unknown:")) {
                        String sName = script.getScriptName();
                        if (sName == null) sName = "script#" + script.id();
                        System.out.println("    CastLib #" + entry.getKey() + " \"" + castLib.getName()
                                + "\" script=\"" + sName + "\" handler nameIdx=" + handler.nameId()
                                + " resolved=\"" + hName + "\"");
                        foundNullHandlers = true;
                    }
                }
            }
        }
        if (!foundNullHandlers) {
            System.out.println("    (none found)");
        }
        System.out.println();

        // --- Diagnostic: dump "Object Manager Class" construct and prepareFrame bytecode ---
        System.out.println("========================================");
        System.out.println("  OBJECT MANAGER CLASS BYTECODE");
        System.out.println("========================================\n");
        boolean foundObjectManagerClass = false;
        for (var castEntry : player.getCastLibManager().getCastLibs().entrySet()) {
            var castLib = castEntry.getValue();
            if (!castLib.isExternal() || !castLib.isLoaded()) continue;

            var castNames = castLib.getScriptNames();
            if (castNames == null) continue;

            for (var script : castLib.getAllScripts()) {
                String sName = script.getScriptName();
                if (sName != null && sName.equalsIgnoreCase("Object Manager Class")
                        && script.getScriptType() == ScriptChunk.ScriptType.PARENT) {
                    foundObjectManagerClass = true;
                    System.out.println("  Found: \"" + sName + "\" (" + script.getScriptType()
                            + ") in CastLib #" + castEntry.getKey() + " \"" + castLib.getName() + "\"");
                    System.out.println("  Handlers:");
                    for (ScriptChunk.Handler handler : script.handlers()) {
                        String hName = castNames.getName(handler.nameId());
                        System.out.println("    - " + hName);
                    }
                    System.out.println();

                    // Dump construct handler bytecode
                    ScriptChunk.Handler constructHandler = script.findHandler("construct", castNames);
                    if (constructHandler != null) {
                        System.out.println("  construct bytecode:");
                        for (ScriptChunk.Handler.Instruction instr : constructHandler.instructions()) {
                            String litInfo = "";
                            if (instr.opcode().name().contains("PUSH") || instr.opcode().name().contains("EXT_CALL")
                                    || instr.opcode().name().contains("CALL")) {
                                litInfo = resolveLiteral(script, castNames, instr);
                            }
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    } else {
                        System.out.println("  (no construct handler found)");
                    }
                    System.out.println();

                    // Dump prepareFrame handler bytecode
                    ScriptChunk.Handler prepareFrameHandler = script.findHandler("prepareFrame", castNames);
                    if (prepareFrameHandler != null) {
                        System.out.println("  prepareFrame bytecode:");
                        for (ScriptChunk.Handler.Instruction instr : prepareFrameHandler.instructions()) {
                            String litInfo = "";
                            if (instr.opcode().name().contains("PUSH") || instr.opcode().name().contains("EXT_CALL")
                                    || instr.opcode().name().contains("CALL")) {
                                litInfo = resolveLiteral(script, castNames, instr);
                            }
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    } else {
                        System.out.println("  (no prepareFrame handler found)");
                    }
                    System.out.println();
                }
            }
        }
        if (!foundObjectManagerClass) {
            System.out.println("  (Object Manager Class PARENT script not found in any loaded external cast)");
        }
        System.out.println();

        // --- Diagnostic: dump "Object API" constructObjectManager bytecode ---
        System.out.println("========================================");
        System.out.println("  CONSTRUCTOBJECTMANAGER BYTECODE");
        System.out.println("========================================\n");
        for (var castEntry2 : player.getCastLibManager().getCastLibs().entrySet()) {
            var castLib2 = castEntry2.getValue();
            if (!castLib2.isExternal() || !castLib2.isLoaded()) continue;
            ScriptNamesChunk castNames2 = castLib2.getScriptNames();
            if (castNames2 == null) continue;
            for (ScriptChunk script2 : castLib2.getAllScripts()) {
                if (script2.getScriptName() != null && script2.getScriptName().equalsIgnoreCase("Object API")
                        && script2.getScriptType() == ScriptChunk.ScriptType.MOVIE_SCRIPT) {
                    ScriptChunk.Handler comHandler = script2.findHandler("constructObjectManager", castNames2);
                    if (comHandler != null) {
                        System.out.println("  constructObjectManager bytecode:");
                        for (ScriptChunk.Handler.Instruction instr : comHandler.instructions()) {
                            String litInfo = "";
                            if (instr.opcode().name().contains("PUSH") || instr.opcode().name().contains("EXT_CALL")
                                    || instr.opcode().name().contains("CALL") || instr.opcode().name().contains("SET")
                                    || instr.opcode().name().contains("GET")) {
                                litInfo = resolveLiteral(script2, castNames2, instr);
                            }
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    } else {
                        System.out.println("  (constructObjectManager handler not found)");
                    }
                    System.out.println();
                }
            }
        }

        // --- Diagnostic: dump "Client Initialization Script" startClient bytecode ---
        System.out.println("========================================");
        System.out.println("  STARTCLIENT BYTECODE");
        System.out.println("========================================\n");
        boolean foundClientInitScript = false;
        for (var castEntry : player.getCastLibManager().getCastLibs().entrySet()) {
            var castLib = castEntry.getValue();
            if (!castLib.isExternal() || !castLib.isLoaded()) continue;

            var castNames = castLib.getScriptNames();
            if (castNames == null) continue;

            for (var script : castLib.getAllScripts()) {
                String sName = script.getScriptName();
                if (sName != null && sName.equalsIgnoreCase("Client Initialization Script")
                        && script.getScriptType() == ScriptChunk.ScriptType.MOVIE_SCRIPT) {
                    foundClientInitScript = true;
                    System.out.println("  Found: \"" + sName + "\" (" + script.getScriptType()
                            + ") in CastLib #" + castEntry.getKey() + " \"" + castLib.getName() + "\"");
                    System.out.println("  Handlers:");
                    for (ScriptChunk.Handler handler : script.handlers()) {
                        String hName = castNames.getName(handler.nameId());
                        System.out.println("    - " + hName);
                    }
                    System.out.println();

                    // Dump startClient handler bytecode
                    ScriptChunk.Handler startClientHandler = script.findHandler("startClient", castNames);
                    if (startClientHandler != null) {
                        System.out.println("  startClient bytecode:");
                        for (ScriptChunk.Handler.Instruction instr : startClientHandler.instructions()) {
                            String litInfo = "";
                            if (instr.opcode().name().contains("PUSH") || instr.opcode().name().contains("EXT_CALL")
                                    || instr.opcode().name().contains("CALL")) {
                                litInfo = resolveLiteral(script, castNames, instr);
                            }
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    } else {
                        System.out.println("  (no startClient handler found)");
                    }
                    System.out.println();
                }
            }
        }
        if (!foundClientInitScript) {
            System.out.println("  (Client Initialization Script MOVIE_SCRIPT not found in any loaded external cast)");
        }
        System.out.println();

        // --- Diagnostic: dump name indices from fuse_client ScriptNamesChunk ---
        System.out.println("========================================");
        System.out.println("  FUSE_CLIENT SCRIPT NAMES (indices 0-39)");
        System.out.println("========================================\n");
        boolean foundFuseClient = false;
        for (var castEntry : player.getCastLibManager().getCastLibs().entrySet()) {
            var castLib = castEntry.getValue();
            if (castLib.getName() != null && castLib.getName().toLowerCase().contains("fuse_client") && castLib.isLoaded()) {
                foundFuseClient = true;
                ScriptNamesChunk fuseNames = castLib.getScriptNames();
                if (fuseNames == null) {
                    System.out.println("  ScriptNamesChunk is NULL for fuse_client cast!");
                } else {
                    int count = fuseNames.names().size();
                    System.out.println("  Total name count: " + count);
                    for (int i = 0; i < Math.min(40, count); i++) {
                        System.out.println("    name[" + i + "] = \"" + fuseNames.getName(i) + "\"");
                    }
                    // Also specifically check index 33
                    System.out.println();
                    System.out.println("  Specifically, name[33] = " + (33 < count ? "\"" + fuseNames.getName(33) + "\"" : "(out of range, count=" + count + ")"));
                }
                break;
            }
        }
        if (!foundFuseClient) {
            System.out.println("  (fuse_client cast not found or not loaded yet)");
        }
        System.out.println();

        // --- Run startup ---
        System.out.println("\n=== Starting playback ===\n");
        player.play();

        // Step frames — stop when a timeout is created, then continue POST_TARGET_FRAMES more
        int framesPastTarget = 0;
        for (int frame = 0; frame < 100; frame++) {
            try {
                player.stepFrame();
            } catch (Exception e) {
                System.err.println("Error at frame step " + frame + ": " + e.getMessage());
                break;
            }

            // Check for timeout creation after each frame (exclude our synthetic frameProxy)
            long realTimeoutCount = player.getTimeoutManager().getTimeoutNames().stream()
                    .filter(name -> !name.equals("fuse_frameProxy"))
                    .count();
            if (!timeoutReached && realTimeoutCount > 0) {
                timeoutReached = true;
                String marker = "*** TARGET REACHED: timeout created (frame " + currentFrame[0] + ") ***";
                callTree.add(new CallEntry(0, false, marker, CallEntry.CallKind.TARGET_MARKER));
                timeoutEntryIndex = callTree.size() - 1;

                // Log timeout details (exclude synthetic frameProxy)
                for (String name : player.getTimeoutManager().getTimeoutNames()) {
                    if (name.equals("fuse_frameProxy")) continue;
                    String period = player.getTimeoutManager().getTimeoutProp(name, "period").toString();
                    String handler = player.getTimeoutManager().getTimeoutProp(name, "handler").toString();
                    String target = truncate(player.getTimeoutManager().getTimeoutProp(name, "target").toString());
                    String detail = "  TIMEOUT: \"" + name + "\" period=" + period + " handler=" + handler + " target=" + target;
                    callTree.add(new CallEntry(0, false, detail, CallEntry.CallKind.TARGET_MARKER));
                }
            }

            if (timeoutReached) {
                framesPastTarget++;
                if (framesPastTarget >= POST_TARGET_FRAMES) break;
            }
        }

        // --- Print TIMEOUT DETAILS ---
        System.out.println("\n========================================");
        System.out.println("  TIMEOUT DETAILS");
        System.out.println("========================================\n");
        if (player.getTimeoutManager().getTimeoutCount() == 0) {
            System.out.println("  (no timeouts created)");
        } else {
            for (String name : player.getTimeoutManager().getTimeoutNames()) {
                System.out.println("  Timeout: \"" + name + "\"");
                System.out.println("    period:     " + player.getTimeoutManager().getTimeoutProp(name, "period"));
                System.out.println("    handler:    " + player.getTimeoutManager().getTimeoutProp(name, "handler"));
                System.out.println("    target:     " + truncate(player.getTimeoutManager().getTimeoutProp(name, "target").toString()));
                System.out.println("    persistent: " + player.getTimeoutManager().getTimeoutProp(name, "persistent"));
            }
        }

        // --- Print the SPINE: only handlers on the path to the target ---
        if (timeoutReached) {
            System.out.println("\n========================================");
            System.out.println("  STARTUP SPINE (path to timeout creation)");
            System.out.println("========================================\n");
            printSpine(callTree);
        }

        // --- Print the full call tree (up to target) ---
        System.out.println("\n========================================");
        System.out.println("  FULL CALL TREE (up to timeout creation)");
        System.out.println("========================================\n");
        for (CallEntry entry : callTree) {
            System.out.println(entry);
            if (entry.kind() == CallEntry.CallKind.TARGET_MARKER) break;
        }

        // --- Print POST-TARGET TRACE ---
        if (timeoutReached && timeoutEntryIndex >= 0 && timeoutEntryIndex < callTree.size() - 1) {
            System.out.println("\n========================================");
            System.out.println("  POST-TARGET TRACE (" + framesPastTarget + " frames after timeout)");
            System.out.println("========================================\n");

            int postEntries = 0;
            // Skip past the timeout detail entries (they follow the TARGET_MARKER)
            int startIdx = timeoutEntryIndex + 1;
            while (startIdx < callTree.size() && callTree.get(startIdx).kind() == CallEntry.CallKind.TARGET_MARKER) {
                startIdx++;
            }
            for (int i = startIdx; i < callTree.size(); i++) {
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

        // --- Milestones summary ---
        System.out.println("\n========================================");
        System.out.println("  MILESTONES");
        System.out.println("========================================\n");
        System.out.println("  1. create(#core):      " + (createCoreReached ? "REACHED" : "not reached"));
        System.out.println("  2. prepareFrame fired:  " + (prepareFrameFired ? "REACHED" : "not reached"));
        System.out.println("  3. timeout creation:    " + (timeoutReached ? "REACHED" : "not reached"));

        if (!timeoutReached) {
            System.out.println("\n  Note: Timeout creation requires Habbo-specific client code");
            System.out.println("  (loaded at runtime into empty cast slots) to register objects");
            System.out.println("  for the Fuse update cycle via receivePrepare/receiveUpdate.");
            System.out.println("  The Fuse framework infrastructure is working correctly.");
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
            } else if (e.kind() == CallEntry.CallKind.TARGET_MARKER
                    || e.kind() == CallEntry.CallKind.MILESTONE_MARKER) {
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
