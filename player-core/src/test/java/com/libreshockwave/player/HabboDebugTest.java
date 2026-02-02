package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.TraceListener;
import com.libreshockwave.vm.builtin.CastLibProvider.HandlerLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Debug test for habbo.dcr to identify the infinite loop in exitFrame handler.
 */
public class HabboDebugTest {

    private static final String TEST_FILE = "C:/SourceControl/habbo.dcr";
    private static final String FUSE_CLIENT_FILE = "C:/SourceControl/fuse_client.cct";

    public static void main(String[] args) throws IOException {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            return;
        }

        System.out.println("=== Loading habbo.dcr ===");
        DirectorFile file = DirectorFile.load(path);
        System.out.println("Loaded. Scripts: " + file.getScripts().size());

        // Dump EXT_CALL from habbo.dcr
        dumpExtCalls(file, "habbo.dcr");

        // Load and dump EXT_CALL from fuse_client.cct
        Path fusePath = Path.of(FUSE_CLIENT_FILE);
        if (Files.exists(fusePath)) {
            System.out.println("\n=== Loading fuse_client.cct ===");
            DirectorFile fuseFile = DirectorFile.load(fusePath);
            System.out.println("Loaded. Scripts: " + fuseFile.getScripts().size());
            dumpExtCalls(fuseFile, "fuse_client.cct");
        } else {
            System.out.println("\nfuse_client.cct not found at: " + FUSE_CLIENT_FILE);
        }

        // Find and analyze script 133
        analyzeScript(file, 133);

        // Find startClient handler
        findHandler(file, "startClient");

        // Try stepping through frames with tracing
        testFrameStepping(file);
    }

    /**
     * Dump all EXT_CALL, OBJ_CALL, and LOCAL_CALL opcodes from a DirectorFile.
     * Groups by function name and shows where each is called from.
     */
    private static void dumpExtCalls(DirectorFile file, String fileName) {
        System.out.println("\n=== EXT_CALL Dump for " + fileName + " ===");

        ScriptNamesChunk names = file.getScriptNames();
        if (names == null) {
            System.out.println("No script names chunk found!");
            return;
        }

        // Collect all external calls: functionName -> list of (script, handler) pairs
        Map<String, TreeSet<String>> extCalls = new TreeMap<>();
        Map<String, TreeSet<String>> objCalls = new TreeMap<>();
        Map<String, TreeSet<String>> localCalls = new TreeMap<>();

        for (ScriptChunk script : file.getScripts()) {
            String scriptName = script.getScriptName();

            for (ScriptChunk.Handler handler : script.handlers()) {
                String handlerName = names.getName(handler.nameId());
                if (handlerName == null) handlerName = "handler#" + handler.nameId();

                String location = scriptName + "::" + handlerName;

                for (ScriptChunk.Handler.Instruction instr : handler.instructions()) {
                    Opcode op = instr.opcode();
                    int arg = instr.argument();

                    if (op == Opcode.EXT_CALL) {
                        String funcName = names.getName(arg);
                        if (funcName == null) funcName = "func#" + arg;
                        extCalls.computeIfAbsent(funcName, k -> new TreeSet<>()).add(location);
                    } else if (op == Opcode.OBJ_CALL) {
                        String funcName = names.getName(arg);
                        if (funcName == null) funcName = "method#" + arg;
                        objCalls.computeIfAbsent(funcName, k -> new TreeSet<>()).add(location);
                    } else if (op == Opcode.LOCAL_CALL) {
                        // LOCAL_CALL uses handler index, not name id
                        String funcName = "local#" + arg;
                        if (arg >= 0 && arg < script.handlers().size()) {
                            int localNameId = script.handlers().get(arg).nameId();
                            String localName = names.getName(localNameId);
                            if (localName != null) funcName = localName + " (local)";
                        }
                        localCalls.computeIfAbsent(funcName, k -> new TreeSet<>()).add(location);
                    }
                }
            }
        }

        // Print EXT_CALL summary
        System.out.println("\n--- EXT_CALL (external/builtin functions) ---");
        System.out.println("Total unique functions: " + extCalls.size());
        for (var entry : extCalls.entrySet()) {
            System.out.println("  " + entry.getKey() + " (" + entry.getValue().size() + " calls)");
            if (entry.getValue().size() <= 5) {
                for (String loc : entry.getValue()) {
                    System.out.println("    - " + loc);
                }
            }
        }

        // Print OBJ_CALL summary
        System.out.println("\n--- OBJ_CALL (method calls) ---");
        System.out.println("Total unique methods: " + objCalls.size());
        for (var entry : objCalls.entrySet()) {
            System.out.println("  " + entry.getKey() + " (" + entry.getValue().size() + " calls)");
            if (entry.getValue().size() <= 3) {
                for (String loc : entry.getValue()) {
                    System.out.println("    - " + loc);
                }
            }
        }

        // Print LOCAL_CALL summary
        System.out.println("\n--- LOCAL_CALL (same-script handler calls) ---");
        System.out.println("Total unique local handlers: " + localCalls.size());
        for (var entry : localCalls.entrySet()) {
            System.out.println("  " + entry.getKey() + " (" + entry.getValue().size() + " calls)");
        }

        // Print all unique function names for reference
        System.out.println("\n--- All EXT_CALL function names (alphabetical) ---");
        for (String funcName : extCalls.keySet()) {
            System.out.println("  " + funcName);
        }
    }

    private static void analyzeScript(DirectorFile file, int scriptId) {
        System.out.println("\n=== Analyzing Script " + scriptId + " ===");

        ScriptNamesChunk names = file.getScriptNames();

        for (ScriptChunk script : file.getScripts()) {
            if (script.id() == scriptId) {
                System.out.println("Found script " + scriptId);
                System.out.println("  Type: " + script.getScriptType());
                System.out.println("  Name: " + script.getScriptName());
                System.out.println("  Handlers:");

                for (ScriptChunk.Handler handler : script.handlers()) {
                    String handlerName = names != null ? names.getName(handler.nameId()) : "name#" + handler.nameId();
                    System.out.println("    - " + handlerName + " (" + handler.instructions().size() + " instructions)");

                    // If this is exitFrame, disassemble it
                    if (handlerName.equalsIgnoreCase("exitFrame")) {
                        disassembleHandler(script, handler, names);
                    }
                }
                return;
            }
        }
        System.out.println("Script " + scriptId + " not found");
    }

    private static void findHandler(DirectorFile file, String handlerName) {
        System.out.println("\n=== Finding handler: " + handlerName + " ===");

        ScriptNamesChunk names = file.getScriptNames();

        for (ScriptChunk script : file.getScripts()) {
            for (ScriptChunk.Handler handler : script.handlers()) {
                String name = names != null ? names.getName(handler.nameId()) : "name#" + handler.nameId();
                if (name.equalsIgnoreCase(handlerName)) {
                    System.out.println("Found " + handlerName + " in script " + script.id() + " (" + script.getScriptName() + ")");
                    System.out.println("  Type: " + script.getScriptType());
                    System.out.println("  Instructions: " + handler.instructions().size());
                    disassembleHandler(script, handler, names);
                }
            }
        }
    }

    private static void findObjectManagerCreate(com.libreshockwave.player.cast.CastLibManager castLibMgr) {
        System.out.println("\nSearching for 'Object Manager Class' script...");

        for (var entry : castLibMgr.getCastLibs().entrySet()) {
            var castLib = entry.getValue();
            if (!castLib.isLoaded()) continue;

            var scriptNames = castLib.getScriptNames();
            for (var script : castLib.getAllScripts()) {
                if ("Object Manager Class".equals(script.getScriptName())) {
                    System.out.println("\nFound Object Manager Class!");
                    System.out.println("  CastLib: " + entry.getKey() + " (" + castLib.getName() + ")");
                    System.out.println("  Script ID: " + script.id());
                    System.out.println("  Handlers: " + script.handlers().size());

                    for (var handler : script.handlers()) {
                        String handlerName = scriptNames != null ? scriptNames.getName(handler.nameId()) : "name#" + handler.nameId();
                        System.out.println("\n  Handler: " + handlerName + " (" + handler.instructions().size() + " instructions)");
                        System.out.println("    Args: " + handler.argCount() + ", Locals: " + handler.localCount());

                        // Print arg names
                        if (!handler.argNameIds().isEmpty() && scriptNames != null) {
                            System.out.print("    ArgNames: ");
                            for (int i = 0; i < handler.argNameIds().size(); i++) {
                                if (i > 0) System.out.print(", ");
                                System.out.print(scriptNames.getName(handler.argNameIds().get(i)));
                            }
                            System.out.println();
                        }

                        // Print local names
                        if (!handler.localNameIds().isEmpty() && scriptNames != null) {
                            System.out.print("    LocalNames: ");
                            for (int i = 0; i < handler.localNameIds().size(); i++) {
                                if (i > 0) System.out.print(", ");
                                System.out.print(scriptNames.getName(handler.localNameIds().get(i)));
                            }
                            System.out.println();
                        }

                        // Disassemble create handler in detail
                        if ("create".equals(handlerName)) {
                            System.out.println("\n    === FULL DISASSEMBLY of create ===");
                            disassembleHandlerFull(script, handler, scriptNames);
                        }
                    }
                    return;
                }
            }
        }
        System.out.println("Object Manager Class not found!");
    }

    private static void disassembleHandlerFull(ScriptChunk script, ScriptChunk.Handler handler, ScriptNamesChunk names) {
        List<ScriptChunk.Handler.Instruction> instructions = handler.instructions();

        for (int i = 0; i < instructions.size(); i++) {
            ScriptChunk.Handler.Instruction instr = instructions.get(i);
            StringBuilder line = new StringBuilder();
            line.append(String.format("    %3d: %-22s %5d", i, instr.opcode(), instr.argument()));

            // Try to resolve name for opcodes that reference names
            String opName = instr.opcode().name();
            int arg = instr.argument();

            if (opName.contains("PUSH_NAME") || opName.contains("CALL") || opName.contains("GET") || opName.contains("SET")) {
                if (names != null && arg > 0 && arg < 1000) {
                    String resolvedName = names.getName(arg);
                    if (resolvedName != null) {
                        line.append(" ; ").append(resolvedName);
                    }
                }
            }

            // For JMP opcodes, show the target
            if (opName.contains("JMP")) {
                int target = i + 1 + arg;
                line.append(" -> ").append(target);
            }

            // For GET_LOCAL/SET_LOCAL, show the local name
            if (opName.contains("LOCAL") && !opName.contains("CALL")) {
                if (names != null && arg >= 0 && arg < handler.localNameIds().size()) {
                    String localName = names.getName(handler.localNameIds().get(arg));
                    if (localName != null) {
                        line.append(" ; ").append(localName);
                    }
                }
            }

            // For PUSH_INT, PUSH_FLOAT, etc, show the value
            if (opName.equals("PUSH_INT8") || opName.equals("PUSH_INT16") || opName.equals("PUSH_INT32")) {
                line.append(" ; int ").append(arg);
            }

            // For constants - ScriptChunk doesn't expose constants, so skip detailed lookup

            System.out.println(line);
        }
    }

    private static void disassembleHandler(ScriptChunk script, ScriptChunk.Handler handler, ScriptNamesChunk names) {
        System.out.println("\n      Disassembly of exitFrame:");
        List<ScriptChunk.Handler.Instruction> instructions = handler.instructions();

        for (int i = 0; i < instructions.size(); i++) {
            ScriptChunk.Handler.Instruction instr = instructions.get(i);
            String arg = "";

            // Try to resolve name for opcodes that reference names
            String opName = instr.opcode().name();
            if (opName.contains("PUSH_NAME") || opName.contains("CALL") || opName.contains("GET") || opName.contains("SET")) {
                if (names != null && instr.argument() > 0 && instr.argument() < 1000) {
                    String resolvedName = names.getName(instr.argument());
                    if (resolvedName != null) {
                        arg = " ; " + resolvedName;
                    }
                }
            }

            // For JMP opcodes, show the target
            if (opName.contains("JMP")) {
                int target = i + 1 + instr.argument();
                arg = " -> " + target;
            }

            System.out.printf("        %3d: %-20s %5d%s%n",
                i, instr.opcode(), instr.argument(), arg);
        }
    }

    private static void testFrameStepping(DirectorFile file) {
        System.out.println("\n=== Testing Frame Stepping ===");

        Player player = new Player(file);
        LingoVM vm = player.getVM();

        // Set a higher step limit to allow complex handlers to complete
        vm.setStepLimit(100000);

        // Enable tracing
        vm.setTraceEnabled(true);

        // Run prepareMovie first to load external casts
        player.play();

        // Wait for network tasks to complete AND cast to be loaded
        System.out.println("\n=== Waiting for external cast to load ===");
        for (int i = 0; i < 100; i++) {
            var castLib2 = player.getCastLibManager().getCastLibs().get(2);
            if (castLib2 != null && castLib2.isLoaded()) {
                System.out.println("External cast loaded after " + (i * 100) + "ms");
                break;
            }
            if (i == 99) {
                System.out.println("Timeout waiting for external cast to load");
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        System.out.println("\n=== Searching external casts for startClient ===");
        var castLibManager = player.getCastLibManager();

        // Debug: Check what URL was passed to the completion callback
        var netTask = player.getNetManager().getTask(null);
        if (netTask != null) {
            System.out.println("NetTask URL: " + netTask.getUrl());
            System.out.println("NetTask OriginalURL: " + netTask.getOriginalUrl());
        }

        // Debug: Try to manually match the URL
        String testUrl = netTask != null ? netTask.getOriginalUrl() : "";
        java.nio.file.Path testPath = java.nio.file.Paths.get(testUrl);
        String testFileName = testPath.getFileName().toString();
        String testBaseName = testFileName.contains(".") ? testFileName.substring(0, testFileName.lastIndexOf('.')) : testFileName;
        System.out.println("Extracted filename: " + testFileName);
        System.out.println("Extracted baseName: " + testBaseName);

        // Check what cast libs have matching names
        for (var entry : castLibManager.getCastLibs().entrySet()) {
            var castLib = entry.getValue();
            if (castLib.getName().equalsIgnoreCase(testBaseName)) {
                System.out.println("MATCH FOUND: CastLib " + entry.getKey() + " name '" + castLib.getName() + "' matches '" + testBaseName + "'");
            }
        }

        // Dump all cast libs and their scripts
        for (var entry : castLibManager.getCastLibs().entrySet()) {
            var castLib = entry.getValue();
            System.out.println("CastLib " + entry.getKey() + " (" + castLib.getName() + "):");
            System.out.println("  isLoaded: " + castLib.isLoaded());
            System.out.println("  isExternal: " + castLib.isExternal());
            System.out.println("  isFetched: " + castLib.isFetched());
            if (castLib.isLoaded()) {
                System.out.println("  Scripts: " + castLib.getAllScripts().size());
                var scriptNames = castLib.getScriptNames();
                for (var script : castLib.getAllScripts()) {
                    System.out.println("    Script #" + script.id() + " " + script.getScriptName() + " (" + script.getScriptType() + ")");
                    for (var handler : script.handlers()) {
                        String handlerName = scriptNames != null ? scriptNames.getName(handler.nameId()) : "name#" + handler.nameId();
                        System.out.println("      - " + handlerName + " (" + handler.instructions().size() + " instructions)");
                    }
                }
            }
        }

        var location = castLibManager.findHandler("startClient");
        if (location != null) {
            System.out.println("\nFound startClient in external cast!");
            if (location.script() instanceof com.libreshockwave.chunks.ScriptChunk script
                    && location.handler() instanceof com.libreshockwave.chunks.ScriptChunk.Handler handler) {
                System.out.println("  Script ID: " + script.id());
                System.out.println("  Script Name: " + script.getScriptName());
                System.out.println("  Script Type: " + script.getScriptType());
                System.out.println("  Instructions: " + handler.instructions().size());
                disassembleHandler(script, handler, (ScriptNamesChunk)location.scriptNames());
            }
        } else {
            System.out.println("\nstartClient not found in external casts");
        }

        // Also find and dump the handlers that failed
        for (String handlerName : List.of("createManager", "convertToPropList", "create", "dump", "dumpVariableField", "getClassVariable", "getVariable")) {
            var loc = castLibManager.findHandler(handlerName);
            if (loc != null) {
                System.out.println("\n=== Found " + handlerName + " ===");
                if (loc.script() instanceof com.libreshockwave.chunks.ScriptChunk script
                        && loc.handler() instanceof com.libreshockwave.chunks.ScriptChunk.Handler handler) {
                    System.out.println("  Script ID: " + script.id());
                    System.out.println("  Script Name: " + script.getScriptName());
                    System.out.println("  Instructions: " + handler.instructions().size());
                    disassembleHandler(script, handler, (ScriptNamesChunk)loc.scriptNames());
                }
            }
        }

        // Find and dump Object Manager Class create handler specifically
        System.out.println("\n\n========================================");
        System.out.println("=== Analyzing Object Manager Class ===");
        System.out.println("========================================");
        findObjectManagerCreate(castLibManager);

        player.stop();

        // Add a trace listener to catch the loop
        vm.setTraceListener(new TraceListener() {
            private int instructionCount = 0;
            private int totalInstructions = 0;
            private String currentHandler = "";
            private boolean inCreateManager = false;
            private boolean inDumpVariableField = false;
            private boolean inDump = false;
            private java.util.Deque<String> handlerStack = new java.util.ArrayDeque<>();

            @Override
            public void onInstruction(InstructionInfo info) {
                instructionCount++;
                totalInstructions++;

                // Detailed trace for createManager - show byte offset and instruction index
                if (inCreateManager && instructionCount <= 100) {
                    System.out.printf("  [%d] idx=%d off=%d: %-15s arg=%d stack=%s%n",
                        instructionCount, info.bytecodeIndex(), info.offset(), info.opcode(), info.argument(),
                        info.stackSnapshot().size() <= 3 ? info.stackSnapshot() : "[" + info.stackSnapshot().size() + " items]");
                }

                // Detailed trace for dumpVariableField and dump - ALWAYS show when in these handlers
                if (handlerStack.contains("dumpVariableField") || handlerStack.contains("dump")) {
                    System.out.printf("  [DVF:%s:%d] idx=%d off=%d: %-15s arg=%d stack=%s%n",
                        currentHandler, instructionCount, info.bytecodeIndex(), info.offset(), info.opcode(), info.argument(),
                        info.stackSnapshot());
                }

                // Print every 1000th instruction to see progress
                if (totalInstructions % 1000 == 0) {
                    System.out.println("  [" + totalInstructions + " total] " + currentHandler + " off=" + info.offset() + ": " + info.opcode());
                }
            }

            @Override
            public void onHandlerEnter(HandlerInfo info) {
                instructionCount = 0;
                handlerStack.push(info.handlerName());
                currentHandler = info.handlerName();
                inCreateManager = info.handlerName().equals("createManager");
                inDumpVariableField = info.handlerName().equals("dumpVariableField");
                inDump = info.handlerName().equals("dump");
                System.out.println(">>> Enter " + info.handlerName() + " (script " + info.scriptId() + ")");
                if (inDump || inDumpVariableField) {
                    System.out.println("    Arguments: " + info.arguments());
                    System.out.println("    Receiver: " + info.receiver());
                    System.out.println("    Locals: " + info.localCount() + ", Args: " + info.argCount());
                }
            }

            @Override
            public void onHandlerExit(HandlerInfo info, Datum result) {
                System.out.println("<<< Exit " + info.handlerName() + " (" + instructionCount + " instructions) -> " + result);
                handlerStack.pop();
                currentHandler = handlerStack.isEmpty() ? "" : handlerStack.peek();
                if (info.handlerName().equals("createManager")) {
                    inCreateManager = false;
                }
                if (info.handlerName().equals("dumpVariableField")) {
                    inDumpVariableField = false;
                }
                if (info.handlerName().equals("dump")) {
                    inDump = false;
                }
            }

            @Override
            public void onError(String message, Exception error) {
                System.err.println("ERROR: " + message);
            }
        });

        player.setDebugEnabled(true);

        System.out.println("Playing movie...");
        try {
            player.play();

            // Step through a few frames
            for (int i = 0; i < 10; i++) {
                System.out.println("\n=== Stepping to frame " + (player.getCurrentFrame() + 1) + " ===");
                try {
                    player.stepFrame();
                } catch (Exception e) {
                    System.err.println("Error stepping frame: " + e.getMessage());
                    e.printStackTrace();
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("Error during playback: " + e.getMessage());
            e.printStackTrace();
        }

        player.shutdown();
    }
}
