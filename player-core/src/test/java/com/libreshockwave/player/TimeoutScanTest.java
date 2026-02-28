package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.lingo.Opcode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans all scripts in habbo.dcr AND fuse_client.cct for any reference to
 * "timeout" — in handler names, ext_call targets, obj_call targets, literals,
 * and the global script names table.
 *
 * Run: ./gradlew :player-core:runTimeoutScanTest
 */
public class TimeoutScanTest {

    private static final String DCR_FILE = "C:/SourceControl/habbo.dcr";
    private static final String CCT_FILE = "C:/SourceControl/fuse_client.cct";

    public static void main(String[] args) throws IOException {
        List<String[]> filesToScan = new ArrayList<>();
        filesToScan.add(new String[]{DCR_FILE, "habbo.dcr"});
        filesToScan.add(new String[]{CCT_FILE, "fuse_client.cct"});

        for (String[] entry : filesToScan) {
            String filePath = entry[0];
            String label = entry[1];

            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                System.err.println("File not found: " + filePath);
                continue;
            }

            DirectorFile file = DirectorFile.load(path);
            scanFile(file, label);
        }
    }

    private static void scanFile(DirectorFile file, String label) {
        ScriptNamesChunk names = file.getScriptNames();
        if (names == null) {
            System.out.println("[" + label + "] No script names chunk — skipping\n");
            return;
        }

        System.out.println("========================================");
        System.out.println("  TIMEOUT SCAN -- " + label);
        System.out.println("  (" + file.getScripts().size() + " scripts, " + names.names().size() + " names)");
        System.out.println("========================================\n");

        // 1. Scan the global names table for anything containing "timeout"
        System.out.println("--- Names table entries containing 'timeout' ---");
        int nameHits = 0;
        for (int i = 0; i < names.names().size(); i++) {
            String name = names.names().get(i);
            if (name.toLowerCase().contains("timeout")) {
                System.out.printf("  [%d] %s%n", i, name);
                nameHits++;
            }
        }
        if (nameHits == 0) System.out.println("  (none)");
        System.out.println();

        // 2. Scan all scripts for bytecode references to timeout-related names
        System.out.println("--- Bytecode references to 'timeout' names ---");
        int codeHits = 0;
        for (ScriptChunk script : file.getScripts()) {
            String scriptName = getScriptLabel(script);

            for (ScriptChunk.Handler handler : script.handlers()) {
                String handlerName = script.getHandlerName(handler);
                if (handlerName == null) handlerName = "handler#" + handler.nameId();

                for (ScriptChunk.Handler.Instruction instr : handler.instructions()) {
                    String resolved = resolveNameForInstruction(instr, names, script);
                    if (resolved != null && resolved.toLowerCase().contains("timeout")) {
                        System.out.printf("  %s::%s [%04d] %-15s %d  ; %s%n",
                                scriptName, handlerName, instr.offset(),
                                instr.opcode(), instr.argument(), resolved);
                        codeHits++;
                    }
                }
            }
        }
        if (codeHits == 0) System.out.println("  (none)");
        System.out.println();

        // 3. Scan all literals for timeout references
        System.out.println("--- Literals containing 'timeout' ---");
        int litHits = 0;
        for (ScriptChunk script : file.getScripts()) {
            String scriptName = getScriptLabel(script);

            for (int i = 0; i < script.literals().size(); i++) {
                ScriptChunk.LiteralEntry lit = script.literals().get(i);
                if (lit.value() != null && lit.value().toString().toLowerCase().contains("timeout")) {
                    System.out.printf("  %s literal[%d]: %s%n", scriptName, i, lit.value());
                    litHits++;
                }
            }
        }
        if (litHits == 0) System.out.println("  (none)");
        System.out.println();

        // 4. Scan all handler names for timeout references
        System.out.println("--- Handlers with 'timeout' in name ---");
        int handlerHits = 0;
        for (ScriptChunk script : file.getScripts()) {
            String scriptName = getScriptLabel(script);

            for (ScriptChunk.Handler handler : script.handlers()) {
                String handlerName = script.getHandlerName(handler);
                if (handlerName != null && handlerName.toLowerCase().contains("timeout")) {
                    System.out.printf("  %s :: %s%n", scriptName, handlerName);
                    handlerHits++;
                }
            }
        }
        if (handlerHits == 0) System.out.println("  (none)");
        System.out.println();

        // 5. Scan for scheduling-related names: timer, idle, tick, run, cycle, etc.
        System.out.println("--- Names table: scheduling-related entries ---");
        int schedHits = 0;
        for (int i = 0; i < names.names().size(); i++) {
            String name = names.names().get(i).toLowerCase();
            if (name.contains("timer") || name.contains("idle") || name.equals("tick")
                    || name.equals("run") || name.contains("schedule") || name.contains("interval")
                    || name.contains("cycle") || name.contains("heartbeat") || name.contains("poll")) {
                System.out.printf("  [%d] %s%n", i, names.names().get(i));
                schedHits++;
            }
        }
        if (schedHits == 0) System.out.println("  (none)");
        System.out.println();

        // 6. Dump Thread Manager Class and Thread Instance Class handlers
        System.out.println("--- Thread-related script handlers ---");
        int threadHits = 0;
        for (ScriptChunk script : file.getScripts()) {
            String scriptName = getScriptLabel(script);
            if (scriptName.toLowerCase().contains("thread")) {
                System.out.println("  " + scriptName + " (" + script.getScriptType() + "):");
                for (ScriptChunk.Handler handler : script.handlers()) {
                    String handlerName = script.getHandlerName(handler);
                    System.out.println("    - " + handlerName);
                    threadHits++;
                }
            }
        }
        if (threadHits == 0) System.out.println("  (none)");
        System.out.println();

        // 7. Summary
        System.out.println("========================================");
        System.out.println("  SUMMARY for " + label);
        System.out.println("========================================");
        System.out.printf("  Names table 'timeout' hits: %d%n", nameHits);
        System.out.printf("  Bytecode 'timeout' refs:    %d%n", codeHits);
        System.out.printf("  Literal 'timeout' refs:     %d%n", litHits);
        System.out.printf("  Handler 'timeout' names:    %d%n", handlerHits);
        System.out.printf("  Scheduling-related names:   %d%n", schedHits);
        System.out.printf("  Thread script handlers:     %d%n", threadHits);

        if (nameHits + codeHits + litHits + handlerHits == 0) {
            System.out.println("\n  *** NO timeout references found ***");
        } else {
            System.out.println("\n  *** TIMEOUT usage CONFIRMED ***");
        }
        System.out.println();
    }

    private static String getScriptLabel(ScriptChunk script) {
        String name = script.getScriptName();
        return name != null ? name : "script#" + script.id();
    }

    /**
     * Try to resolve the name referenced by an instruction.
     * EXT_CALL, LOCAL_CALL, OBJ_CALL use name IDs from ScriptNamesChunk.
     * PUSH_CONS uses literal indices. PUSH_SYMB uses name IDs.
     */
    private static String resolveNameForInstruction(ScriptChunk.Handler.Instruction instr,
                                                     ScriptNamesChunk names,
                                                     ScriptChunk script) {
        Opcode op = instr.opcode();
        int arg = instr.argument();

        if (op == Opcode.EXT_CALL || op == Opcode.LOCAL_CALL || op == Opcode.OBJ_CALL
                || op == Opcode.OBJ_CALL_V4 || op == Opcode.TELL_CALL || op == Opcode.PUSH_SYMB) {
            return names.getName(arg);
        }

        if (op == Opcode.PUSH_CONS) {
            List<ScriptChunk.LiteralEntry> literals = script.literals();
            if (arg >= 0 && arg < literals.size()) {
                Object val = literals.get(arg).value();
                return val != null ? val.toString() : null;
            }
        }

        return null;
    }
}
