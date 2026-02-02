package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.player.cast.CastLibManager;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.builtin.CastLibProvider;
import com.libreshockwave.vm.builtin.MoviePropertyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for convertToPropList using fuse_client.cct
 */
class ConvertToPropListTest {

    private static final String TEST_FILE = "C:/SourceControl/fuse_client.cct";

    private DirectorFile file;
    private LingoVM vm;
    private CastLibManager castLibManager;

    @BeforeEach
    void setUp() throws Exception {
        Path path = Path.of(TEST_FILE);
        if (!Files.exists(path)) {
            System.out.println("Test file not found: " + TEST_FILE);
            return;
        }

        file = DirectorFile.load(path);
        assertNotNull(file, "Failed to load test file");

        vm = new LingoVM(file);
        vm.setTraceEnabled(true);

        // Set up cast lib manager as provider
        castLibManager = new CastLibManager(file);
        CastLibProvider.setProvider(castLibManager);

        // Set up basic movie property provider
        MoviePropertyProvider.setProvider(new MoviePropertyProvider() {
            private char itemDelimiter = ',';

            @Override
            public Datum getMovieProp(String propName) {
                if ("itemdelimiter".equalsIgnoreCase(propName)) {
                    return Datum.of(String.valueOf(itemDelimiter));
                }
                return Datum.VOID;
            }

            @Override
            public boolean setMovieProp(String propName, Datum value) {
                if ("itemdelimiter".equalsIgnoreCase(propName)) {
                    String s = value.toStr();
                    if (!s.isEmpty()) {
                        itemDelimiter = s.charAt(0);
                    }
                    return true;
                }
                return false;
            }

            @Override
            public char getItemDelimiter() {
                return itemDelimiter;
            }
        });
    }

    @Test
    void testConvertToPropListHandlerExists() {
        if (file == null) return;

        // Find the convertToPropList handler
        var ref = vm.findHandler("convertToPropList");
        assertNotNull(ref, "convertToPropList handler not found");

        System.out.println("Found handler: " + ref.script().getHandlerName(ref.handler()));
        System.out.println("In script: " + ref.script().getScriptName());
    }

    @Test
    void testConvertToPropListSimple() {
        if (file == null) return;

        // Simple test: key=value pairs separated by newlines
        String testInput = "key1=value1\nkey2=value2\nkey3=value3";
        String delimiter = "\n";

        Datum result = vm.callHandler("convertToPropList",
                List.of(Datum.of(testInput), Datum.of(delimiter)));

        System.out.println("Result: " + result);
        System.out.println("Result type: " + result.typeName());

        // The result should be a PropList or List
        assertTrue(result instanceof Datum.PropList || result instanceof Datum.List,
                "Expected PropList or List, got: " + result.typeName());

        if (result instanceof Datum.PropList propList) {
            System.out.println("PropList entries: " + propList.properties().size());
            propList.properties().forEach((k, v) ->
                    System.out.println("  " + k + " = " + v));
        }
    }

    @Test
    void testConvertToPropListFromFieldStyle() {
        if (file == null) return;

        // Test with format similar to what's in the "System Props" field
        // Based on the trace, it seems to be formatted with key=value pairs
        String testInput = "#system\n#props\n#index\n\nsystem.version=1.0\nsystem.name=test";
        String delimiter = "\n";

        Datum result = vm.callHandler("convertToPropList",
                List.of(Datum.of(testInput), Datum.of(delimiter)));

        System.out.println("Result: " + result);
    }

    @Test
    void testListScripts() {
        if (file == null) return;

        System.out.println("\n=== Scripts in file ===");
        for (ScriptChunk script : file.getScripts()) {
            System.out.println("\nScript: " + script.getScriptName()
                    + " (type: " + script.getScriptType() + ")");

            for (ScriptChunk.Handler handler : script.handlers()) {
                String handlerName = script.getHandlerName(handler);
                System.out.println("  - " + handlerName);
            }
        }
    }

    @Test
    void testStringCountWithItem() {
        if (file == null) return;

        // Test the string count method that convertToPropList uses
        // count(str, #item) should count items separated by the itemDelimiter
        String testStr = "one,two,three,four";

        // This would be called as: str.count(#item)
        // In Lingo: count("one,two,three,four", #item) -> 4
    }

    public static void main(String[] args) throws Exception {
        ConvertToPropListTest test = new ConvertToPropListTest();
        test.setUp();
        test.testListScripts();
        test.testConvertToPropListHandlerExists();
        test.testConvertToPropListSimple();
    }
}
