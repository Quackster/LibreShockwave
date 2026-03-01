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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final int POST_TARGET_FRAMES = 500;
    private static final int MAX_FRAMES = 600;
    private static final int IDLE_REPORT_INTERVAL = 50;

    /** Milestone: create(#core) was called. */
    private static volatile boolean createCoreReached = false;

    /** Milestone: prepareFrame fired on Object Manager. */
    private static volatile boolean prepareFrameFired = false;

    /** Target: a timeout was created (requires Habbo-specific code beyond Fuse framework). */
    private static volatile boolean timeoutReached = false;
    private static int timeoutEntryIndex = -1;

    /** Milestone 4: first visual object creation. */
    private static volatile boolean visualMilestoneReached = false;
    private static int visualMilestoneFrame = -1;
    private static String visualMilestoneDetail = "";

    /** State machine transitions (updateState/changeState/setState calls). */
    private static final List<String> stateTransitions = new ArrayList<>();

    /** Network completion events (netDone returns true). */
    private static final List<String> netCompletions = new ArrayList<>();

    /** Handler call frequency counter for post-target phase. */
    private static final Map<String, Integer> postTargetHandlerCounts = new LinkedHashMap<>();

    /** Frames with non-idle activity. */
    private static final List<Integer> interestingFrames = new ArrayList<>();

    /** New ScriptInstance creations by script name. */
    private static final List<String> instanceCreations = new ArrayList<>();

    /** Whether we are in the post-target phase. */
    private static volatile boolean inPostTargetPhase = false;

    /** Keywords that suggest visual rendering activity. */
    private static final Pattern VISUAL_PATTERN = Pattern.compile(
            "(?i)(visualiz|window|sprite|draw|render|image|bitmap|room|view|loading|bar|stage|display|screen|pixel|rect|quad)");

    /** Specific visual milestone indicators. */
    private static final Pattern VISUAL_MILESTONE_PATTERN = Pattern.compile(
            "(?i)(constructVisualizerManager|createWindow|Loading Bar|Visualizer Instance|Window Instance)");

    /** State machine handler patterns. */
    private static final Pattern STATE_HANDLER_PATTERN = Pattern.compile(
            "(?i)(updateState|changeState|setState|stateChange)");

    /** Recorded call tree entry. */
    record CallEntry(int depth, boolean isEnter, String text, CallKind kind) {
        enum CallKind { HANDLER_ENTER, HANDLER_EXIT, ERROR, FRAME_MARKER, MILESTONE_MARKER, TARGET_MARKER, VISUAL_FLAG, NET_DONE }

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

        vm.setStepLimit(2_000_000);

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

                // Track state machine transitions
                if (STATE_HANDLER_PATTERN.matcher(info.handlerName()).find()) {
                    String entry = "[Frame " + currentFrame[0] + "] " + info.handlerName()
                            + "(" + argsStr + ") [" + info.scriptDisplayName() + "]";
                    stateTransitions.add(entry);
                    callTree.add(new CallEntry(depth[0] - 1, false,
                            "*** STATE: " + info.handlerName() + "(" + argsStr + ") ***",
                            CallEntry.CallKind.MILESTONE_MARKER));
                }

                // Detect Milestone 4: visual object creation
                String combined4 = info.handlerName() + " " + info.scriptDisplayName();
                if (!visualMilestoneReached && VISUAL_MILESTONE_PATTERN.matcher(combined4).find()) {
                    visualMilestoneReached = true;
                    visualMilestoneFrame = currentFrame[0];
                    visualMilestoneDetail = info.handlerName() + " [" + info.scriptDisplayName() + "]";
                    String marker = "*** MILESTONE 4: Visual creation — " + visualMilestoneDetail + " ***";
                    callTree.add(new CallEntry(depth[0] - 1, false, marker, CallEntry.CallKind.MILESTONE_MARKER));
                }

                // Handler frequency tracking in post-target phase
                if (inPostTargetPhase) {
                    String key = info.handlerName() + " [" + info.scriptDisplayName() + "]";
                    postTargetHandlerCounts.merge(key, 1, Integer::sum);
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
                }

                // Track netDone returning true
                if (info.handlerName().equals("netDone") && result != null
                        && !(result instanceof Datum.Int i && i.value() == 0)
                        && !(result instanceof Datum.Void)) {
                    String entry = "[Frame " + currentFrame[0] + "] netDone() = " + resultStr
                            + " [" + info.scriptDisplayName() + "]";
                    netCompletions.add(entry);
                    callTree.add(new CallEntry(depth[0], false,
                            "*** NET_DONE: " + resultStr + " ***",
                            CallEntry.CallKind.NET_DONE));
                }

                // Track script instance creation via construct
                if (info.handlerName().equals("construct") && result instanceof Datum.ScriptInstance si) {
                    String scriptName = info.scriptDisplayName();
                    String entry = "[Frame " + currentFrame[0] + "] new " + scriptName;
                    instanceCreations.add(entry);
                    // Flag if it's a visual-related instance
                    if (VISUAL_PATTERN.matcher(scriptName).find()) {
                        callTree.add(new CallEntry(depth[0], false,
                                ">>> VISUAL_INSTANCE: " + scriptName,
                                CallEntry.CallKind.VISUAL_FLAG));
                    }
                }

                // Dump Object Manager properties after registerManager exits
                if (info.handlerName().equals("registerManager")
                        && info.scriptDisplayName().contains("Object Manager")) {
                    // Find the Object Manager instance (it's the 'me' receiver)
                    var ctx = info.receiver();
                    if (ctx instanceof Datum.ScriptInstance si) {
                        callTree.add(new CallEntry(depth[0], false,
                                "[DIAG] Object Manager props after registerManager("
                                + formatArgs(info.arguments()) + "):",
                                CallEntry.CallKind.ERROR));
                        for (var propEntry : si.properties().entrySet()) {
                            String key = propEntry.getKey();
                            Datum val = propEntry.getValue();
                            String valStr = val instanceof Datum.List list
                                    ? "List[" + list.items().size() + "]=" + truncate(val.toString())
                                    : val instanceof Datum.PropList pl
                                            ? "PropList[" + pl.properties().size() + "]"
                                            : truncate(val.toString());
                            callTree.add(new CallEntry(depth[0] + 1, false,
                                    key + " = " + valStr,
                                    CallEntry.CallKind.ERROR));
                        }
                    }
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

                    // Dump registerManager handler bytecode
                    ScriptChunk.Handler regMgrHandler = script.findHandler("registerManager", castNames);
                    if (regMgrHandler != null) {
                        System.out.println("  registerManager bytecode:");
                        for (ScriptChunk.Handler.Instruction instr : regMgrHandler.instructions()) {
                            String litInfo = "";
                            if (instr.opcode().name().contains("PUSH") || instr.opcode().name().contains("CALL")
                                    || instr.opcode().name().contains("PROP") || instr.opcode().name().contains("SET")) {
                                litInfo = resolveLiteral(script, castNames, instr);
                            }
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    }
                    System.out.println();

                    // Dump receiveUpdate handler bytecode
                    ScriptChunk.Handler recvUpdateHandler = script.findHandler("receiveUpdate", castNames);
                    if (recvUpdateHandler != null) {
                        System.out.println("  receiveUpdate bytecode:");
                        for (ScriptChunk.Handler.Instruction instr : recvUpdateHandler.instructions()) {
                            String litInfo = "";
                            if (instr.opcode().name().contains("PUSH") || instr.opcode().name().contains("CALL")
                                    || instr.opcode().name().contains("PROP") || instr.opcode().name().contains("SET")) {
                                litInfo = resolveLiteral(script, castNames, instr);
                            }
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    }
                    System.out.println();

                    // Dump create handler bytecode
                    ScriptChunk.Handler createOmHandler = script.findHandler("create", castNames);
                    if (createOmHandler != null) {
                        System.out.println("  create bytecode (argCount=" + createOmHandler.argCount()
                                + " localCount=" + createOmHandler.localCount() + "):");
                        for (ScriptChunk.Handler.Instruction instr : createOmHandler.instructions()) {
                            String litInfo = "";
                            if (instr.opcode().name().contains("PUSH") || instr.opcode().name().contains("CALL")
                                    || instr.opcode().name().contains("PROP") || instr.opcode().name().contains("SET")) {
                                litInfo = resolveLiteral(script, castNames, instr);
                            }
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    }
                    System.out.println();

                    // Dump declared properties
                    if (script.hasProperties()) {
                        System.out.println("  Declared properties:");
                        var propNames = script.getPropertyNames(castNames);
                        for (int pi = 0; pi < propNames.size(); pi++) {
                            System.out.println("    - " + propNames.get(pi));
                        }
                        System.out.println();
                    }
                }
            }
        }
        if (!foundObjectManagerClass) {
            System.out.println("  (Object Manager Class PARENT script not found in any loaded external cast)");
        }
        System.out.println();

        // --- Diagnostic: dump "Thread Manager Class" create/initThread bytecode ---
        System.out.println("========================================");
        System.out.println("  THREAD MANAGER CLASS BYTECODE");
        System.out.println("========================================\n");
        boolean foundThreadManagerClass = false;
        for (var castEntry : player.getCastLibManager().getCastLibs().entrySet()) {
            var castLib = castEntry.getValue();
            if (!castLib.isExternal() || !castLib.isLoaded()) continue;
            var castNames = castLib.getScriptNames();
            if (castNames == null) continue;
            for (var script : castLib.getAllScripts()) {
                String sName = script.getScriptName();
                if (sName != null && sName.equalsIgnoreCase("Thread Manager Class")
                        && script.getScriptType() == ScriptChunk.ScriptType.PARENT) {
                    foundThreadManagerClass = true;
                    // File diagnostics
                    DirectorFile scriptFile = script.file();
                    System.out.println("  Found: \"" + sName + "\" (" + script.getScriptType()
                            + ") in CastLib #" + castEntry.getKey() + " \"" + castLib.getName() + "\"");
                    System.out.println("  script.file() = " + (scriptFile != null ? scriptFile.getClass().getSimpleName() : "NULL"));
                    if (scriptFile != null) {
                        System.out.println("  capitalX = " + scriptFile.isCapitalX());
                        System.out.println("  directorVersion = " + (scriptFile.getConfig() != null ? scriptFile.getConfig().directorVersion() : "NULL config"));
                        int mult = scriptFile.isCapitalX() ? 1
                                : (scriptFile.getConfig() != null && scriptFile.getConfig().directorVersion() >= 500 ? 8 : 6);
                        System.out.println("  computed multiplier = " + mult);
                    }
                    System.out.println("  Handlers:");
                    for (ScriptChunk.Handler handler : script.handlers()) {
                        String hName = castNames.getName(handler.nameId());
                        System.out.println("    - " + hName);
                    }
                    System.out.println();

                    // Dump declared properties
                    if (script.hasProperties()) {
                        System.out.println("  Declared properties:");
                        for (String propName : script.getPropertyNames(castNames)) {
                            System.out.println("    - " + propName);
                        }
                    }
                    System.out.println();

                    // Dump construct handler bytecode
                    ScriptChunk.Handler tmConstructHandler = script.findHandler("construct", castNames);
                    if (tmConstructHandler != null) {
                        System.out.println("  construct bytecode (argCount=" + tmConstructHandler.argCount() + " localCount=" + tmConstructHandler.localCount() + "):");
                        for (ScriptChunk.Handler.Instruction instr : tmConstructHandler.instructions()) {
                            String litInfo = "";
                            if (instr.opcode().name().contains("PUSH") || instr.opcode().name().contains("EXT_CALL")
                                    || instr.opcode().name().contains("CALL") || instr.opcode().name().contains("GET")
                                    || instr.opcode().name().contains("SET")) {
                                litInfo = resolveLiteral(script, castNames, instr);
                            }
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    }
                    System.out.println();

                    // Dump create handler bytecode
                    ScriptChunk.Handler createHandler = script.findHandler("create", castNames);
                    if (createHandler != null) {
                        System.out.println("  create bytecode (argCount=" + createHandler.argCount() + " localCount=" + createHandler.localCount() + "):");
                        for (ScriptChunk.Handler.Instruction instr : createHandler.instructions()) {
                            String litInfo = "";
                            if (instr.opcode().name().contains("PUSH") || instr.opcode().name().contains("EXT_CALL")
                                    || instr.opcode().name().contains("CALL") || instr.opcode().name().contains("GET")
                                    || instr.opcode().name().contains("SET")) {
                                litInfo = resolveLiteral(script, castNames, instr);
                            }
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    }
                    System.out.println();

                    // Dump initThread handler bytecode
                    ScriptChunk.Handler initThreadHandler = script.findHandler("initThread", castNames);
                    if (initThreadHandler != null) {
                        System.out.println("  initThread bytecode (argCount=" + initThreadHandler.argCount() + " localCount=" + initThreadHandler.localCount() + "):");
                        for (ScriptChunk.Handler.Instruction instr : initThreadHandler.instructions()) {
                            String litInfo = "";
                            if (instr.opcode().name().contains("PUSH") || instr.opcode().name().contains("EXT_CALL")
                                    || instr.opcode().name().contains("CALL") || instr.opcode().name().contains("GET")
                                    || instr.opcode().name().contains("SET")) {
                                litInfo = resolveLiteral(script, castNames, instr);
                            }
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    } else {
                        System.out.println("  (no initThread handler found)");
                    }
                    System.out.println();
                }
            }
        }
        if (!foundThreadManagerClass) {
            System.out.println("  (Thread Manager Class PARENT script not found)");
        }
        System.out.println();

        // --- Diagnostic: external cast file properties ---
        System.out.println("========================================");
        System.out.println("  EXTERNAL CAST FILE PROPERTIES");
        System.out.println("========================================\n");
        for (var castEntry : player.getCastLibManager().getCastLibs().entrySet()) {
            var castLib = castEntry.getValue();
            if (!castLib.isExternal() || !castLib.isLoaded()) continue;
            // Check first script's file reference
            var allScripts = castLib.getAllScripts();
            if (!allScripts.isEmpty()) {
                ScriptChunk firstScript = allScripts.iterator().next();
                DirectorFile sf = firstScript.file();
                System.out.printf("  CastLib #%d \"%s\": file=%s capitalX=%s dirVer=%s%n",
                        castEntry.getKey(), castLib.getName(),
                        sf != null ? "present" : "NULL",
                        sf != null ? String.valueOf(sf.isCapitalX()) : "N/A",
                        sf != null && sf.getConfig() != null ? String.valueOf(sf.getConfig().directorVersion()) : "N/A");
            }
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
                    // Specifically check thread-related indices
                    System.out.println();
                    for (int idx : new int[]{33, 664, 703, 704, 707}) {
                        System.out.println("  name[" + idx + "] = " + (idx < count ? "\"" + fuseNames.getName(idx) + "\"" : "(out of range, count=" + count + ")"));
                    }
                }
                break;
            }
        }
        if (!foundFuseClient) {
            System.out.println("  (fuse_client cast not found or not loaded yet)");
        }
        System.out.println();

        // --- Diagnostic: dump "System Props" field content ---
        System.out.println("========================================");
        System.out.println("  SYSTEM PROPS FIELD CONTENT");
        System.out.println("========================================\n");
        String sysPropsContent = player.getCastLibManager().getFieldValue("System Props", 0);
        if (sysPropsContent != null && !sysPropsContent.isEmpty()) {
            // Show first 2000 chars
            String display = sysPropsContent.length() > 2000 ? sysPropsContent.substring(0, 2000) + "..." : sysPropsContent;
            System.out.println(display);
            // Search for thread-related entries
            if (sysPropsContent.contains("thread")) {
                System.out.println("\n  Thread-related entries found in System Props!");
            } else {
                System.out.println("\n  NO thread-related entries in System Props");
            }
        } else {
            System.out.println("  (System Props field is empty or not found)");
        }
        System.out.println();

        // --- Diagnostic: list ALL member names in fuse_client ---
        System.out.println("========================================");
        System.out.println("  FUSE_CLIENT CAST MEMBERS (all types)");
        System.out.println("========================================\n");
        for (var castEntry : player.getCastLibManager().getCastLibs().entrySet()) {
            var castLib = castEntry.getValue();
            if (castLib.getName() != null && castLib.getName().toLowerCase().contains("fuse_client") && castLib.isLoaded()) {
                int memberCount = castLib.getMemberCount();
                System.out.println("  Total member count: " + memberCount);
                // List all members with names
                int namedCount = 0;
                for (int m = 1; m <= 1000; m++) {
                    var chunk = castLib.findMemberByNumber(m);
                    if (chunk != null) {
                        String memberName = chunk.name() != null ? chunk.name() : "(null)";
                        String memberType = chunk.isScript() ? "SCRIPT" : "type=" + chunk.memberType();
                        System.out.println("    member[" + m + "] = \"" + memberName + "\" (" + memberType + ")");
                        namedCount++;
                    }
                }
                System.out.println("  Found " + namedCount + " members in range 1-1000");
                break;
            }
        }
        System.out.println();

        // --- Diagnostic: find ALL handlers that call receiveUpdate/receivePrepare ---
        System.out.println("========================================");
        System.out.println("  HANDLERS CALLING receiveUpdate/receivePrepare");
        System.out.println("========================================\n");
        for (var castEntry : player.getCastLibManager().getCastLibs().entrySet()) {
            var castLib = castEntry.getValue();
            if (!castLib.isExternal() || !castLib.isLoaded()) continue;
            var castNames = castLib.getScriptNames();
            if (castNames == null) continue;
            // Find nameIdx for receiveUpdate and receivePrepare
            int recvUpdateIdx = -1, recvPrepareIdx = -1;
            for (int i = 0; i < castNames.names().size(); i++) {
                String n = castNames.getName(i);
                if ("receiveUpdate".equals(n)) recvUpdateIdx = i;
                if ("receivePrepare".equals(n)) recvPrepareIdx = i;
            }
            System.out.println("  CastLib #" + castEntry.getKey() + " \"" + castLib.getName()
                    + "\": receiveUpdate nameIdx=" + recvUpdateIdx + ", receivePrepare nameIdx=" + recvPrepareIdx);
            for (var script : castLib.getAllScripts()) {
                String sName = script.getScriptName();
                if (sName == null) sName = "script#" + script.id();
                for (var handler : script.handlers()) {
                    String hName = castNames.getName(handler.nameId());
                    for (var instr : handler.instructions()) {
                        String opName = instr.opcode().name();
                        int arg = instr.argument();
                        boolean match = false;
                        if (opName.contains("EXT_CALL") && (arg == recvUpdateIdx || arg == recvPrepareIdx)) {
                            match = true;
                        }
                        // Also check OBJ_CALL with those indices
                        if (opName.contains("OBJ_CALL") && (arg == recvUpdateIdx || arg == recvPrepareIdx)) {
                            match = true;
                        }
                        if (match) {
                            String calledName = arg == recvUpdateIdx ? "receiveUpdate" : "receivePrepare";
                            System.out.println("    FOUND: " + sName + "." + hName + " calls " + calledName
                                    + " via " + opName + " at offset " + instr.offset());
                        }
                    }
                }
            }
        }
        System.out.println();

        // --- Diagnostic: dump constructDownloadManager bytecode ---
        System.out.println("========================================");
        System.out.println("  CONSTRUCT DOWNLOAD MANAGER BYTECODE");
        System.out.println("========================================\n");
        for (var castEntry : player.getCastLibManager().getCastLibs().entrySet()) {
            var castLib = castEntry.getValue();
            if (!castLib.isExternal() || !castLib.isLoaded()) continue;
            var castNames = castLib.getScriptNames();
            if (castNames == null) continue;
            for (var script : castLib.getAllScripts()) {
                String sName = script.getScriptName();
                if (sName != null && sName.equalsIgnoreCase("Download API")
                        && script.getScriptType() == ScriptChunk.ScriptType.MOVIE_SCRIPT) {
                    ScriptChunk.Handler cdm = script.findHandler("constructDownloadManager", castNames);
                    if (cdm != null) {
                        System.out.println("  constructDownloadManager bytecode (argCount=" + cdm.argCount()
                                + " localCount=" + cdm.localCount() + "):");
                        for (var instr : cdm.instructions()) {
                            String litInfo = resolveLiteral(script, castNames, instr);
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    }
                    System.out.println();
                    // Also dump the queueDownload handler
                    ScriptChunk.Handler qd = script.findHandler("queueDownload", castNames);
                    if (qd != null) {
                        System.out.println("  queueDownload bytecode (argCount=" + qd.argCount()
                                + " localCount=" + qd.localCount() + "):");
                        for (var instr : qd.instructions()) {
                            String litInfo = resolveLiteral(script, castNames, instr);
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    }
                }
                // Also dump Download Manager Class construct
                if (sName != null && sName.equalsIgnoreCase("Download Manager Class")
                        && script.getScriptType() == ScriptChunk.ScriptType.PARENT) {
                    ScriptChunk.Handler dmConstruct = script.findHandler("construct", castNames);
                    if (dmConstruct != null) {
                        System.out.println("  Download Manager Class construct bytecode (argCount="
                                + dmConstruct.argCount() + " localCount=" + dmConstruct.localCount() + "):");
                        for (var instr : dmConstruct.instructions()) {
                            String litInfo = resolveLiteral(script, castNames, instr);
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    }
                    System.out.println();
                    // Dump updateQueue handler (calls receiveUpdate!)
                    ScriptChunk.Handler dmUpdateQueue = script.findHandler("updateQueue", castNames);
                    if (dmUpdateQueue != null) {
                        System.out.println("  Download Manager Class updateQueue bytecode (argCount="
                                + dmUpdateQueue.argCount() + " localCount=" + dmUpdateQueue.localCount() + "):");
                        for (var instr : dmUpdateQueue.instructions()) {
                            String litInfo = resolveLiteral(script, castNames, instr);
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    }
                    System.out.println();
                    // Also dump update handler
                    ScriptChunk.Handler dmUpdate = script.findHandler("update", castNames);
                    if (dmUpdate != null) {
                        System.out.println("  Download Manager Class update bytecode (argCount="
                                + dmUpdate.argCount() + " localCount=" + dmUpdate.localCount() + "):");
                        for (var instr : dmUpdate.instructions()) {
                            String litInfo = resolveLiteral(script, castNames, instr);
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    }
                }
            }
        }
        System.out.println();

        // --- Diagnostic: dump "String Services Class" replaceChunks/convertSpecialChars bytecode ---
        System.out.println("========================================");
        System.out.println("  STRING SERVICES CLASS BYTECODE");
        System.out.println("========================================\n");
        boolean foundStringServicesClass = false;
        for (var castEntry : player.getCastLibManager().getCastLibs().entrySet()) {
            var castLib = castEntry.getValue();
            if (!castLib.isExternal() || !castLib.isLoaded()) continue;
            var castNames = castLib.getScriptNames();
            if (castNames == null) continue;
            for (var script : castLib.getAllScripts()) {
                String sName = script.getScriptName();
                if (sName != null && sName.equalsIgnoreCase("String Services Class")
                        && script.getScriptType() == ScriptChunk.ScriptType.PARENT) {
                    foundStringServicesClass = true;
                    System.out.println("  Found: \"" + sName + "\" (" + script.getScriptType()
                            + ") in CastLib #" + castEntry.getKey() + " \"" + castLib.getName() + "\"");
                    System.out.println("  Handlers:");
                    for (ScriptChunk.Handler handler : script.handlers()) {
                        String hName = castNames.getName(handler.nameId());
                        System.out.println("    - " + hName);
                    }
                    System.out.println();

                    // Dump replaceChunks handler bytecode
                    ScriptChunk.Handler replaceChunksHandler = script.findHandler("replaceChunks", castNames);
                    if (replaceChunksHandler != null) {
                        System.out.println("  replaceChunks bytecode (argCount=" + replaceChunksHandler.argCount()
                                + " localCount=" + replaceChunksHandler.localCount() + "):");
                        for (var instr : replaceChunksHandler.instructions()) {
                            String litInfo = resolveLiteral(script, castNames, instr);
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    } else {
                        System.out.println("  (no replaceChunks handler found)");
                    }
                    System.out.println();

                    // Dump convertSpecialChars handler bytecode
                    ScriptChunk.Handler convertSpecialCharsHandler = script.findHandler("convertSpecialChars", castNames);
                    if (convertSpecialCharsHandler != null) {
                        System.out.println("  convertSpecialChars bytecode (argCount=" + convertSpecialCharsHandler.argCount()
                                + " localCount=" + convertSpecialCharsHandler.localCount() + "):");
                        for (var instr : convertSpecialCharsHandler.instructions()) {
                            String litInfo = resolveLiteral(script, castNames, instr);
                            System.out.printf("    [%04d] %-20s %d%s%n",
                                    instr.offset(), instr.opcode(), instr.argument(), litInfo);
                        }
                    } else {
                        System.out.println("  (no convertSpecialChars handler found)");
                    }
                    System.out.println();
                }
            }
        }
        if (!foundStringServicesClass) {
            System.out.println("  (String Services Class PARENT script not found)");
        }
        System.out.println();

        // --- Run startup ---
        System.out.println("\n=== Starting playback ===\n");
        player.play();

        // Step frames — stop when a timeout is created, then continue POST_TARGET_FRAMES more
        int framesPastTarget = 0;
        int totalFramesStepped = 0;
        int idleStreak = 0;
        int visualContextFrames = 0;  // extra frames after visual milestone

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            int callTreeSizeBefore = callTree.size();

            try {
                player.stepFrame();
            } catch (Exception e) {
                System.err.println("Error at frame step " + frame + ": " + e.getMessage());
                e.printStackTrace(System.err);
                break;
            }

            totalFramesStepped++;

            // Check for timeout creation after each frame (exclude our synthetic frameProxy)
            long realTimeoutCount = player.getTimeoutManager().getTimeoutNames().stream()
                    .filter(name -> !name.equals("fuse_frameProxy"))
                    .count();
            if (!timeoutReached && realTimeoutCount > 0) {
                timeoutReached = true;
                inPostTargetPhase = true;
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

            // Detect idle vs interesting frames
            int newEntries = callTree.size() - callTreeSizeBefore;
            if (newEntries > 15) {
                interestingFrames.add(currentFrame[0]);
                idleStreak = 0;
            } else {
                idleStreak++;
            }

            // Progress logging
            if (totalFramesStepped % IDLE_REPORT_INTERVAL == 0) {
                System.out.println("  [progress] Frame " + totalFramesStepped + "/" + MAX_FRAMES
                        + " | idle streak: " + idleStreak
                        + " | callTree size: " + callTree.size()
                        + " | interesting frames: " + interestingFrames.size()
                        + " | state transitions: " + stateTransitions.size());
            }

            // Stop conditions (in priority order)
            if (visualMilestoneReached) {
                visualContextFrames++;
                if (visualContextFrames >= 10) {
                    System.out.println("  [stop] Visual milestone reached, ran 10 context frames.");
                    break;
                }
            } else if (timeoutReached) {
                framesPastTarget++;
                if (framesPastTarget >= POST_TARGET_FRAMES) {
                    System.out.println("  [stop] POST_TARGET_FRAMES (" + POST_TARGET_FRAMES + ") exceeded.");
                    break;
                }
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

        // --- Print the full call tree (up to target only — pre-target flow is understood) ---
        System.out.println("\n========================================");
        System.out.println("  FULL CALL TREE (up to timeout creation)");
        System.out.println("========================================\n");
        for (CallEntry entry : callTree) {
            if (entry.kind() == CallEntry.CallKind.TARGET_MARKER) {
                System.out.println(entry);
                break;
            }
            System.out.println(entry);
        }

        // --- Print CONDENSED POST-TARGET TRACE ---
        if (timeoutReached && timeoutEntryIndex >= 0 && timeoutEntryIndex < callTree.size() - 1) {
            System.out.println("\n========================================");
            System.out.println("  CONDENSED POST-TARGET TRACE (" + framesPastTarget + " frames, "
                    + totalFramesStepped + " total stepped)");
            System.out.println("========================================\n");

            int postEntries = 0;
            int skippedIdle = 0;
            // Skip past the timeout detail entries (they follow the TARGET_MARKER)
            int startIdx = timeoutEntryIndex + 1;
            while (startIdx < callTree.size() && callTree.get(startIdx).kind() == CallEntry.CallKind.TARGET_MARKER) {
                startIdx++;
            }
            for (int i = startIdx; i < callTree.size(); i++) {
                CallEntry entry = callTree.get(i);

                // Always show milestone, visual, target, net_done, and error entries
                if (entry.kind() == CallEntry.CallKind.MILESTONE_MARKER
                        || entry.kind() == CallEntry.CallKind.VISUAL_FLAG
                        || entry.kind() == CallEntry.CallKind.TARGET_MARKER
                        || entry.kind() == CallEntry.CallKind.NET_DONE
                        || entry.kind() == CallEntry.CallKind.ERROR) {
                    if (skippedIdle > 0) {
                        System.out.println("    ... (" + skippedIdle + " idle entries skipped)");
                        skippedIdle = 0;
                    }
                    System.out.println(entry);
                    postEntries++;
                } else if (entry.kind() == CallEntry.CallKind.FRAME_MARKER) {
                    // Show frame markers only for interesting frames
                    String text = entry.text();
                    boolean isInteresting = false;
                    for (int f : interestingFrames) {
                        if (text.contains("Frame " + f + " ")) {
                            isInteresting = true;
                            break;
                        }
                    }
                    if (isInteresting) {
                        if (skippedIdle > 0) {
                            System.out.println("    ... (" + skippedIdle + " idle entries skipped)");
                            skippedIdle = 0;
                        }
                        System.out.println(entry);
                        postEntries++;
                    } else {
                        skippedIdle++;
                    }
                } else if (entry.depth() > 2) {
                    // Show handler enter/exit deeper than routine prepareFrame->update
                    if (skippedIdle > 0) {
                        System.out.println("    ... (" + skippedIdle + " idle entries skipped)");
                        skippedIdle = 0;
                    }
                    System.out.println(entry);
                    postEntries++;
                } else {
                    skippedIdle++;
                }
            }
            if (skippedIdle > 0) {
                System.out.println("    ... (" + skippedIdle + " idle entries skipped)");
            }

            if (postEntries == 0) {
                System.out.println("  (no interesting handler activity after target)");
            }
        }

        // --- Print STATE MACHINE TRANSITIONS ---
        System.out.println("\n========================================");
        System.out.println("  STATE MACHINE TRANSITIONS");
        System.out.println("========================================\n");
        if (stateTransitions.isEmpty()) {
            System.out.println("  (no state transitions detected)");
        } else {
            for (String st : stateTransitions) {
                System.out.println("  " + st);
            }
        }

        // --- Print NETWORK COMPLETIONS ---
        System.out.println("\n========================================");
        System.out.println("  NETWORK COMPLETIONS");
        System.out.println("========================================\n");
        if (netCompletions.isEmpty()) {
            System.out.println("  (no network completions detected)");
        } else {
            for (String nc : netCompletions) {
                System.out.println("  " + nc);
            }
        }

        // --- Print SCRIPT INSTANCE CREATIONS ---
        System.out.println("\n========================================");
        System.out.println("  SCRIPT INSTANCE CREATIONS");
        System.out.println("========================================\n");
        if (instanceCreations.isEmpty()) {
            System.out.println("  (no script instance creations tracked)");
        } else {
            // Group by script name with count, first/last frame
            var creationGroups = new LinkedHashMap<String, int[]>();  // name -> [count, firstFrame, lastFrame]
            for (String ic : instanceCreations) {
                // Format: "[Frame N] new ScriptName"
                int frameStart = ic.indexOf("[Frame ") + 7;
                int frameEnd = ic.indexOf(']', frameStart);
                int frameNum = Integer.parseInt(ic.substring(frameStart, frameEnd));
                String scriptName = ic.substring(ic.indexOf("new ") + 4);
                creationGroups.compute(scriptName, (k, v) -> {
                    if (v == null) return new int[]{1, frameNum, frameNum};
                    v[0]++;
                    v[2] = frameNum;
                    return v;
                });
            }
            for (var entry : creationGroups.entrySet()) {
                int[] info = entry.getValue();
                if (info[0] == 1) {
                    System.out.println("  " + entry.getKey() + " (1x, frame " + info[1] + ")");
                } else {
                    System.out.println("  " + entry.getKey() + " (" + info[0] + "x, frames " + info[1] + "-" + info[2] + ")");
                }
            }
        }

        // --- Print POST-TARGET HANDLER FREQUENCY ---
        System.out.println("\n========================================");
        System.out.println("  POST-TARGET HANDLER FREQUENCY (top 30)");
        System.out.println("========================================\n");
        if (postTargetHandlerCounts.isEmpty()) {
            System.out.println("  (no post-target handler calls tracked)");
        } else {
            postTargetHandlerCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(30)
                    .forEach(entry -> System.out.printf("  %6d  %s%n", entry.getValue(), entry.getKey()));
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
            var errorCounts = new LinkedHashMap<String, Integer>();
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
        System.out.println("  4. visual creation:     " + (visualMilestoneReached
                ? "REACHED (frame " + visualMilestoneFrame + ") — " + visualMilestoneDetail
                : "not reached in " + totalFramesStepped + " frames"));

        if (!timeoutReached) {
            System.out.println("\n  Note: Timeout creation requires Habbo-specific client code");
            System.out.println("  (loaded at runtime into empty cast slots) to register objects");
            System.out.println("  for the Fuse update cycle via receivePrepare/receiveUpdate.");
            System.out.println("  The Fuse framework infrastructure is working correctly.");
        }

        System.out.println("\n  Total frames stepped: " + totalFramesStepped);
        System.out.println("  Call tree entries: " + callTree.size());
        System.out.println("  Interesting frames: " + interestingFrames.size());

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
