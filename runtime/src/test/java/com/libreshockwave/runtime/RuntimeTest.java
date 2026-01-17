package com.libreshockwave.runtime;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.execution.DirPlayer;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.vm.LingoVM;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Test runner for the runtime project.
 * Tests ExecutionScope, DirPlayer, and LingoVM integration.
 */
public class RuntimeTest {

    public static void main(String[] args) {
        System.out.println("=== LibreShockwave Runtime Tests ===\n");

        testExecutionScope();
        testScopeStack();
        testScopeLocals();
        testScopeArgs();
        testHandlerExecutionResult();

        String path = "http://localhost:8080/assets/habbo.dcr";

        testDirPlayer(path);
        testLingoVMIntegration(path);

        /*
        if (args.length > 0) {
            testDirPlayer(args[0]);
            testLingoVMIntegration(args[0]);
        } else {
            System.out.println("\n[Skipping DirPlayer test - no file provided]");
            System.out.println("Run with: gradle runTests -Pfile=path/to/movie.dcr");
        }*/

        System.out.println("\n=== All Tests Complete ===");
    }

    private static void testExecutionScope() {
        System.out.println("--- Test: ExecutionScope Stack Operations ---");

        ExecutionScope scope = new ExecutionScope();

        // Test push/pop
        scope.push(Datum.of(42));
        scope.push(Datum.of(100));
        scope.push(Datum.of("hello"));

        assert scope.stackSize() == 3 : "Stack size should be 3";
        assert scope.pop().stringValue().equals("hello") : "Top should be 'hello'";
        assert scope.pop().intValue() == 100 : "Next should be 100";
        assert scope.stackSize() == 1 : "Stack size should be 1";

        // Test peek
        Datum peeked = scope.peek();
        assert peeked.intValue() == 42 : "Peek should return 42";
        assert scope.stackSize() == 1 : "Peek should not remove item";

        // Test swap
        scope.push(Datum.of(99));
        scope.swap();
        assert scope.pop().intValue() == 42 : "After swap, top should be 42";
        assert scope.pop().intValue() == 99 : "After swap, next should be 99";

        System.out.println("  Stack operations: PASSED");
    }

    private static void testScopeStack() {
        System.out.println("--- Test: ExecutionScope Nested Operations ---");

        ExecutionScope scope = new ExecutionScope();

        // Push multiple values
        for (int i = 1; i <= 10; i++) {
            scope.push(Datum.of(i));
        }
        assert scope.stackSize() == 10 : "Should have 10 items";

        // Pop N
        scope.popN(5);
        assert scope.stackSize() == 5 : "Should have 5 items after popN(5)";

        // Clear
        scope.clearStack();
        assert scope.stackSize() == 0 : "Stack should be empty after clear";

        // Pop from empty returns void
        Datum empty = scope.pop();
        assert empty.isVoid() : "Pop from empty should return void";

        System.out.println("  Nested operations: PASSED");
    }

    private static void testScopeLocals() {
        System.out.println("--- Test: ExecutionScope Locals ---");

        ExecutionScope scope = new ExecutionScope();

        // Set and get locals
        scope.setLocal("x", Datum.of(10));
        scope.setLocal("y", Datum.of(20));
        scope.setLocal("name", Datum.of("test"));

        assert scope.hasLocal("x") : "Should have local 'x'";
        assert scope.getLocal("x").intValue() == 10 : "x should be 10";
        assert scope.getLocal("y").intValue() == 20 : "y should be 20";
        assert scope.getLocal("name").stringValue().equals("test") : "name should be 'test'";

        // Non-existent local returns void
        assert scope.getLocal("missing").isVoid() : "Missing local should return void";
        assert !scope.hasLocal("missing") : "Should not have 'missing'";

        System.out.println("  Locals: PASSED");
    }

    private static void testScopeArgs() {
        System.out.println("--- Test: ExecutionScope Arguments ---");

        // Create scope with args
        ExecutionScope scope = new ExecutionScope(Arrays.asList(
            Datum.of(1),
            Datum.of(2),
            Datum.of(3)
        ));

        assert scope.getArgCount() == 3 : "Should have 3 args";
        assert scope.getArg(0).intValue() == 1 : "Arg 0 should be 1";
        assert scope.getArg(1).intValue() == 2 : "Arg 1 should be 2";
        assert scope.getArg(2).intValue() == 3 : "Arg 2 should be 3";

        // Out of range returns void
        assert scope.getArg(10).isVoid() : "Out of range arg should return void";

        // Set arg
        scope.setArg(1, Datum.of(99));
        assert scope.getArg(1).intValue() == 99 : "Arg 1 should now be 99";

        System.out.println("  Arguments: PASSED");
    }

    private static void testDirPlayer(String filePath) {
        System.out.println("\n--- Test: DirPlayer with " + filePath + " ---");

        try {
            DirPlayer player = new DirPlayer();
            player.loadMovie(Path.of(filePath));

            System.out.println("  Loaded: " + filePath);
            System.out.println("  Stage: " + player.getStageWidth() + "x" + player.getStageHeight());
            System.out.println("  Tempo: " + player.getTempo() + " fps");
            System.out.println("  Scripts: " + player.getFile().getScripts().size());

            // Add event listener to track events
            player.addEventListener((event, frame) -> {
                System.out.println("  Event: " + event.handlerName() + " (frame " + frame + ")");
            });

            // Dispatch events
            System.out.println("\n  Dispatching events:");
            player.dispatchEvent(DirPlayer.MovieEvent.PREPARE_MOVIE);
            player.dispatchEvent(DirPlayer.MovieEvent.ENTER_FRAME);
            player.dispatchEvent(DirPlayer.MovieEvent.EXIT_FRAME);

            System.out.println("\n  DirPlayer: PASSED");

        } catch (Exception e) {
            System.err.println("  DirPlayer test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testHandlerExecutionResult() {
        System.out.println("--- Test: HandlerExecutionResult ---");

        // Test all enum values exist
        assert HandlerExecutionResult.ADVANCE != null : "ADVANCE should exist";
        assert HandlerExecutionResult.JUMP != null : "JUMP should exist";
        assert HandlerExecutionResult.STOP != null : "STOP should exist";
        assert HandlerExecutionResult.ERROR != null : "ERROR should exist";

        // Test enum values are distinct
        assert HandlerExecutionResult.ADVANCE != HandlerExecutionResult.JUMP : "ADVANCE != JUMP";
        assert HandlerExecutionResult.ADVANCE != HandlerExecutionResult.STOP : "ADVANCE != STOP";
        assert HandlerExecutionResult.JUMP != HandlerExecutionResult.STOP : "JUMP != STOP";

        System.out.println("  HandlerExecutionResult: PASSED");
    }

    private static void testLingoVMIntegration(String filePath) {
        System.out.println("\n--- Test: LingoVM Integration with " + filePath + " ---");

        try {
            DirectorFile file = DirectorFile.load(Path.of(filePath));
            LingoVM vm = new LingoVM(file);

            System.out.println("  Created LingoVM for: " + filePath);

            // Test builtin handlers (these are in SDK's LingoVM)
            System.out.println("  Testing SDK builtin handlers via DirPlayer:");

            // Test abs
            Datum absResult = vm.call("abs", Datum.of(-42));
            assert absResult.intValue() == 42 : "abs(-42) should be 42";
            System.out.println("    abs(-42) = " + absResult.intValue());

            // Test sqrt
            Datum sqrtResult = vm.call("sqrt", Datum.of(16.0f));
            assert Math.abs(sqrtResult.floatValue() - 4.0f) < 0.001 : "sqrt(16) should be 4";
            System.out.println("    sqrt(16) = " + sqrtResult.floatValue());

            // Test string length
            Datum lenResult = vm.call("length", Datum.of("hello"));
            assert lenResult.intValue() == 5 : "length('hello') should be 5";
            System.out.println("    length('hello') = " + lenResult.intValue());

            // Test list operations
            Datum.DList list = Datum.list();
            list.add(Datum.of(1));
            list.add(Datum.of(2));
            list.add(Datum.of(3));
            Datum countResult = vm.call("count", list);
            assert countResult.intValue() == 3 : "count([1,2,3]) should be 3";
            System.out.println("    count([1,2,3]) = " + countResult.intValue());

            // Test getAt
            Datum getAtResult = vm.call("getAt", list, Datum.of(2));
            assert getAtResult.intValue() == 2 : "getAt([1,2,3], 2) should be 2";
            System.out.println("    getAt([1,2,3], 2) = " + getAtResult.intValue());

            // Test min/max
            Datum minResult = vm.call("min", Datum.of(5), Datum.of(3), Datum.of(8));
            assert minResult.intValue() == 3 : "min(5,3,8) should be 3";
            System.out.println("    min(5,3,8) = " + minResult.intValue());

            Datum maxResult = vm.call("max", Datum.of(5), Datum.of(3), Datum.of(8));
            assert maxResult.intValue() == 8 : "max(5,3,8) should be 8";
            System.out.println("    max(5,3,8) = " + maxResult.intValue());

            // Test bitwise
            Datum andResult = vm.call("bitAnd", Datum.of(0b1100), Datum.of(0b1010));
            assert andResult.intValue() == 0b1000 : "bitAnd(12, 10) should be 8";
            System.out.println("    bitAnd(12, 10) = " + andResult.intValue());

            // Test global variable access
            vm.setGlobal("testVar", Datum.of(123));
            assert vm.getGlobal("testVar").intValue() == 123 : "Global testVar should be 123";
            System.out.println("    Global testVar = " + vm.getGlobal("testVar").intValue());

            // Test ilk type checking
            Datum ilkInt = vm.call("ilk", Datum.of(42));
            assert ilkInt.stringValue().equals("integer") : "ilk(42) should be 'integer'";
            System.out.println("    ilk(42) = " + ilkInt.stringValue());

            Datum ilkStr = vm.call("ilk", Datum.of("hello"));
            assert ilkStr.stringValue().equals("string") : "ilk('hello') should be 'string'";
            System.out.println("    ilk('hello') = " + ilkStr.stringValue());

            // Test point creation
            Datum point = vm.call("point", Datum.of(100), Datum.of(200));
            assert point instanceof Datum.IntPoint : "point(100, 200) should be IntPoint";
            System.out.println("    point(100, 200) = " + point);

            System.out.println("\n  LingoVM Integration: PASSED");

        } catch (Exception e) {
            System.err.println("  LingoVM Integration test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
