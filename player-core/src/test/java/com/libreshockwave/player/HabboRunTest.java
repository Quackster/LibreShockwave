package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.TraceListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Test that runs habbo.dcr until it reaches getThreadManager().create(#core, #core).
 */
public class HabboRunTest {

    private static final String TEST_FILE = "C:/SourceControl/habbo.dcr";
    private static volatile boolean targetReached = false;
    private static volatile String stopReason = null;
    private static volatile int startClientReturnValue = -1;
    private static volatile int createCoreReturnValue = -1;
    private static volatile int createCoreDepth = -1;

    public static void main(String[] args) throws IOException {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.err.println("Test file not found: " + TEST_FILE);
            return;
        }

        System.out.println("=== Loading habbo.dcr ===");
        DirectorFile file = DirectorFile.load(path);
        System.out.println("Loaded. Scripts: " + file.getScripts().size());

        Player player = new Player(file);
        LingoVM vm = player.getVM();

        // Set high step limit
        vm.setStepLimit(500000);
        vm.setTraceEnabled(true);

        // Add trace listener to watch for target
        vm.setTraceListener(new TraceListener() {
            private final Deque<String> handlerStack = new ArrayDeque<>();
            private int totalInstructions = 0;
            private String currentHandler = "";

            @Override
            public void onInstruction(InstructionInfo info) {
                totalInstructions++;

                // Print every 5000th instruction
                if (totalInstructions % 5000 == 0) {
                    System.out.println("  [" + totalInstructions + "] " + currentHandler +
                        " @" + info.bytecodeIndex() + ": " + info.opcode());
                }

                // Print detailed trace for key handlers
                if (currentHandler.equals("create") || currentHandler.equals("createManager") ||
                    currentHandler.equals("GetValue") || currentHandler.equals("getProp")) {
                    System.out.printf("  [%3d] %-16s %d%n",
                        info.bytecodeIndex(), info.opcode(), info.argument());
                    System.out.println("         stack: " + formatStack(info.stackSnapshot()));
                }
            }

            @Override
            public void onHandlerEnter(HandlerInfo info) {
                handlerStack.push(info.handlerName());
                currentHandler = info.handlerName();
                System.out.println("\n== " + info.handlerName() + " (\"" + info.scriptDisplayName() + "\")");

                // Check for target: getThreadManager().create(#core, #core)
                if (info.handlerName().equals("create")) {
                    // Check if args include #core
                    for (Datum arg : info.arguments()) {
                        if (arg instanceof Datum.Symbol sym && sym.name().equalsIgnoreCase("core")) {
                            System.out.println("*** TARGET REACHED: create(#core, ...) ***");
                            targetReached = true;
                            createCoreDepth = handlerStack.size();
                            stopReason = "Target reached: create(#core)";
                        }
                    }
                }
            }

            @Override
            public void onHandlerExit(HandlerInfo info, Datum result) {
                System.out.println("<< Exit " + info.handlerName() + " -> " + result);
                // Capture create(#core) return value — match by stack depth
                // to avoid capturing nested create calls inside initThread
                if (info.handlerName().equals("create") && createCoreDepth >= 0
                        && handlerStack.size() == createCoreDepth) {
                    createCoreReturnValue = result.toInt();
                    createCoreDepth = -1;
                    System.out.println("*** create(#core) returned: " + createCoreReturnValue + " ***");
                }
                // Capture startClient return value
                if (info.handlerName().equals("startClient")) {
                    startClientReturnValue = result.toInt();
                    System.out.println("*** startClient returned: " + startClientReturnValue + " ***");
                }
                if (!handlerStack.isEmpty()) {
                    handlerStack.pop();
                }
                currentHandler = handlerStack.isEmpty() ? "" : handlerStack.peek();
            }

            @Override
            public void onError(String message, Exception error) {
                System.err.println("ERROR: " + message);
                if (error != null) {
                    error.printStackTrace();
                }
            }

            private String formatStack(List<Datum> stack) {
                if (stack.size() <= 5) {
                    return stack.toString();
                }
                return "[" + stack.size() + " items: " +
                    stack.subList(0, 3) + "..." +
                    stack.subList(stack.size() - 2, stack.size()) + "]";
            }
        });

        System.out.println("\n=== Starting playback ===");
        player.play();

        // Wait for external cast to load
        System.out.println("\n=== Waiting for external cast ===");
        for (int i = 0; i < 100 && !targetReached; i++) {
            var castLib2 = player.getCastLibManager().getCastLibs().get(2);
            if (castLib2 != null && castLib2.isLoaded()) {
                System.out.println("External cast loaded after " + (i * 100) + "ms");
                break;
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }

        // Step through frames
        System.out.println("\n=== Stepping frames ===");
        for (int frame = 0; frame < 20 && !targetReached; frame++) {
            System.out.println("\n--- Frame " + (player.getCurrentFrame() + 1) + " ---");
            try {
                player.stepFrame();
            } catch (Exception e) {
                System.err.println("Error at frame " + frame + ": " + e.getMessage());
                e.printStackTrace();
                break;
            }
        }

        if (targetReached) {
            System.out.println("\n=== SUCCESS: " + stopReason + " ===");
        } else {
            System.out.println("\n=== Target not reached after 20 frames ===");
        }

        // Assert create(#core, #core) returned 1 (TRUE)
        if (createCoreReturnValue != 1) {
            System.err.println("\n=== FAIL: getThreadManager().create(#core, #core) returned " +
                createCoreReturnValue + " (expected 1) ===");
            player.shutdown();
            System.exit(1);
        }
        System.out.println("=== PASS: getThreadManager().create(#core, #core) returned 1 ===");

        // Assert startClient returned 1 (TRUE)
        // The full startClient flow:
        //   if not constructObjectManager() then return 0
        //   if not dumpVariableField("System Props") then return stopClient()
        //   if not resetCastLibs(0, 0) then return stopClient()
        //   if not getResourceManager().preIndexMembers() then return stopClient()
        //   if not dumpTextField("System Texts") then return stopClient()
        //   if not getThreadManager().create(#core, #core) then return stopClient()
        //   return 1
        if (startClientReturnValue != 1) {
            System.err.println("\n=== FAIL: startClient returned " + startClientReturnValue + " (expected 1) ===");
            player.shutdown();
            System.exit(1);
        }
        System.out.println("=== PASS: startClient returned 1 ===");

        player.shutdown();
    }
}
