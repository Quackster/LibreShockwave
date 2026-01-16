package com.libreshockwave;

import com.libreshockwave.lingo.Datum;
import com.libreshockwave.player.DirPlayer;
import com.libreshockwave.vm.LingoVM;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Test for Lingo bytecode execution.
 */
public class BytecodeExecutionTest {

    public static void main(String[] args) {
        System.out.println("=== Bytecode Execution Test ===\n");

        testBuiltinHandlers();
        testArithmetic();
        testListOperations();
        testFileExecution();

        System.out.println("\n=== Bytecode Execution Test Complete ===");
    }

    private static void testBuiltinHandlers() {
        System.out.println("--- Testing Builtin Handlers ---");

        // Create a minimal DirectorFile for testing
        // We'll test the handlers directly via LingoVM
        try {
            Path testPath = Path.of("C:/SourceControl/habbo.dcr");
            if (!Files.exists(testPath)) {
                System.out.println("  SKIP: Test file not found");
                return;
            }

            DirectorFile file = DirectorFile.load(testPath);
            LingoVM vm = new LingoVM(file);

            // Test math functions
            Datum result = vm.call("abs", Datum.of(-5));
            assert result.intValue() == 5 : "abs(-5) should be 5";
            System.out.println("  abs(-5) = " + result.intValue() + " PASS");

            result = vm.call("sqrt", Datum.of(16.0f));
            assert Math.abs(result.floatValue() - 4.0f) < 0.001 : "sqrt(16) should be 4";
            System.out.println("  sqrt(16) = " + result.floatValue() + " PASS");

            result = vm.call("random", Datum.of(10));
            assert result.intValue() >= 1 && result.intValue() <= 10 : "random(10) should be 1-10";
            System.out.println("  random(10) = " + result.intValue() + " (1-10) PASS");

            // Test type functions
            result = vm.call("integer", Datum.of(3.7f));
            assert result.intValue() == 3 : "integer(3.7) should be 3";
            System.out.println("  integer(3.7) = " + result.intValue() + " PASS");

            result = vm.call("string", Datum.of(42));
            assert result.stringValue().equals("42") : "string(42) should be '42'";
            System.out.println("  string(42) = '" + result.stringValue() + "' PASS");

            // Test type checking
            result = vm.call("ilk", Datum.of(42));
            assert result.isSymbol() && result.stringValue().equals("integer") : "ilk(42) should be #integer";
            System.out.println("  ilk(42) = " + result.stringValue() + " PASS");

            result = vm.call("ilk", Datum.of("hello"));
            assert result.stringValue().equals("string") : "ilk('hello') should be #string";
            System.out.println("  ilk('hello') = " + result.stringValue() + " PASS");

            System.out.println("  Builtin Handlers: ALL PASS\n");

        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testArithmetic() {
        System.out.println("--- Testing Arithmetic ---");

        try {
            Path testPath = Path.of("C:/SourceControl/habbo.dcr");
            if (!Files.exists(testPath)) {
                System.out.println("  SKIP: Test file not found");
                return;
            }

            DirectorFile file = DirectorFile.load(testPath);
            LingoVM vm = new LingoVM(file);

            // Test min/max
            Datum result = vm.call("min", Datum.of(5), Datum.of(3), Datum.of(8));
            assert result.intValue() == 3 : "min(5,3,8) should be 3";
            System.out.println("  min(5,3,8) = " + result.intValue() + " PASS");

            result = vm.call("max", Datum.of(5), Datum.of(3), Datum.of(8));
            assert result.intValue() == 8 : "max(5,3,8) should be 8";
            System.out.println("  max(5,3,8) = " + result.intValue() + " PASS");

            // Test power
            result = vm.call("power", Datum.of(2.0f), Datum.of(10.0f));
            assert Math.abs(result.floatValue() - 1024.0f) < 0.001 : "power(2,10) should be 1024";
            System.out.println("  power(2,10) = " + result.floatValue() + " PASS");

            // Test bitwise
            result = vm.call("bitAnd", Datum.of(0xFF), Datum.of(0x0F));
            assert result.intValue() == 0x0F : "bitAnd(0xFF, 0x0F) should be 0x0F";
            System.out.println("  bitAnd(0xFF, 0x0F) = 0x" + Integer.toHexString(result.intValue()) + " PASS");

            result = vm.call("bitOr", Datum.of(0xF0), Datum.of(0x0F));
            assert result.intValue() == 0xFF : "bitOr(0xF0, 0x0F) should be 0xFF";
            System.out.println("  bitOr(0xF0, 0x0F) = 0x" + Integer.toHexString(result.intValue()) + " PASS");

            System.out.println("  Arithmetic: ALL PASS\n");

        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testListOperations() {
        System.out.println("--- Testing List Operations ---");

        try {
            Path testPath = Path.of("C:/SourceControl/habbo.dcr");
            if (!Files.exists(testPath)) {
                System.out.println("  SKIP: Test file not found");
                return;
            }

            DirectorFile file = DirectorFile.load(testPath);
            LingoVM vm = new LingoVM(file);

            // Create a list
            Datum.DList list = Datum.list();
            list.add(Datum.of(10));
            list.add(Datum.of(5));
            list.add(Datum.of(20));

            // Test count
            Datum result = vm.call("count", list);
            assert result.intValue() == 3 : "count([10,5,20]) should be 3";
            System.out.println("  count([10,5,20]) = " + result.intValue() + " PASS");

            // Test getAt
            result = vm.call("getAt", list, Datum.of(2));
            assert result.intValue() == 5 : "getAt([10,5,20], 2) should be 5";
            System.out.println("  getAt([10,5,20], 2) = " + result.intValue() + " PASS");

            // Test sort
            vm.call("sort", list);
            result = vm.call("getAt", list, Datum.of(1));
            assert result.intValue() == 5 : "After sort, getAt(1) should be 5";
            System.out.println("  sort([10,5,20])[1] = " + result.intValue() + " PASS");

            // Test getPos
            result = vm.call("getPos", list, Datum.of(10));
            assert result.intValue() == 2 : "getPos([5,10,20], 10) should be 2";
            System.out.println("  getPos([5,10,20], 10) = " + result.intValue() + " PASS");

            System.out.println("  List Operations: ALL PASS\n");

        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testFileExecution() {
        System.out.println("--- Testing File Execution ---");

        try {
            Path testPath = Path.of("C:/SourceControl/habbo.dcr");
            if (!Files.exists(testPath)) {
                System.out.println("  SKIP: Test file not found");
                return;
            }

            // Track events
            List<String> events = new ArrayList<>();

            DirPlayer player = new DirPlayer();
            player.loadMovie(testPath);

            player.addEventListener((event, frame) -> {
                events.add(event.handlerName() + "@" + frame);
            });

            System.out.println("  Loaded: " + testPath);
            System.out.println("  Stage: " + player.getStageWidth() + "x" + player.getStageHeight());
            System.out.println("  Scripts: " + player.getFile().getScripts().size());

            // Execute prepareMovie
            System.out.print("  Executing prepareMovie... ");
            player.dispatchEvent(DirPlayer.MovieEvent.PREPARE_MOVIE);
            System.out.println("DONE");

            // Execute exitFrame
            System.out.print("  Executing exitFrame... ");
            player.dispatchEvent(DirPlayer.MovieEvent.EXIT_FRAME);
            System.out.println("DONE");

            // Verify events were triggered
            assert events.contains("prepareMovie@1") : "prepareMovie should be dispatched";
            assert events.contains("exitFrame@1") : "exitFrame should be dispatched";

            System.out.println("  Events: " + events);
            System.out.println("  File Execution: ALL PASS\n");

        } catch (Exception e) {
            System.out.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
