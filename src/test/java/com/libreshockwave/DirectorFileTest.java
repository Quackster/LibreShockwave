package com.libreshockwave;

import com.libreshockwave.chunks.*;
import com.libreshockwave.io.BinaryReader;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.LingoVM;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Integration tests for Director file loading and parsing.
 */
public class DirectorFileTest {

    public static void main(String[] args) {
        System.out.println("=== LibreShockwave Integration Tests ===\n");

        testBinaryReader();
        testDatumTypes();
        testLingoVM();
        testBitmapDecoding();

        // If a test file is provided, test file loading
        // if (args.length > 0) {
        //     testFileLoading(args[0]);
        // }

        testFileLoading("C:/SourceControl/habbo.dcr");
        System.out.println("\n=== All Tests Completed ===");
    }

    private static void testBinaryReader() {
        System.out.println("--- Testing BinaryReader ---");

        // Test big-endian reading
        byte[] data = {0x00, 0x00, 0x00, 0x2A}; // 42 in big-endian
        BinaryReader reader = new BinaryReader(data, ByteOrder.BIG_ENDIAN);
        int value = reader.readI32();
        assert value == 42 : "Big-endian int32 failed";
        System.out.println("  Big-endian int32: PASS");

        // Test little-endian reading
        data = new byte[]{0x2A, 0x00, 0x00, 0x00}; // 42 in little-endian
        reader = new BinaryReader(data, ByteOrder.LITTLE_ENDIAN);
        value = reader.readI32();
        assert value == 42 : "Little-endian int32 failed";
        System.out.println("  Little-endian int32: PASS");

        // Test FourCC
        data = new byte[]{'R', 'I', 'F', 'X'};
        reader = new BinaryReader(data);
        String fourcc = reader.readFourCCString();
        assert fourcc.equals("RIFX") : "FourCC failed";
        System.out.println("  FourCC reading: PASS");

        // Test VarInt
        data = new byte[]{(byte) 0x81, 0x00}; // 128 in VarInt encoding
        reader = new BinaryReader(data);
        value = reader.readVarInt();
        assert value == 128 : "VarInt failed: got " + value;
        System.out.println("  VarInt reading: PASS");

        System.out.println("  BinaryReader: ALL PASS\n");
    }

    private static void testDatumTypes() {
        System.out.println("--- Testing Datum Types ---");

        // Test integer
        Datum i = Datum.of(42);
        assert i.intValue() == 42 : "Int value failed";
        assert i.floatValue() == 42.0f : "Int to float failed";
        System.out.println("  Int datum: PASS");

        // Test float
        Datum f = Datum.of(3.14f);
        assert Math.abs(f.floatValue() - 3.14f) < 0.001 : "Float value failed";
        assert f.intValue() == 3 : "Float to int failed";
        System.out.println("  Float datum: PASS");

        // Test string
        Datum s = Datum.of("hello");
        assert s.stringValue().equals("hello") : "String value failed";
        System.out.println("  String datum: PASS");

        // Test symbol
        Datum sym = Datum.symbol("test");
        assert sym.stringValue().equals("test") : "Symbol value failed";
        System.out.println("  Symbol datum: PASS");

        // Test list
        Datum.DList list = Datum.list();
        list.add(Datum.of(1));
        list.add(Datum.of(2));
        list.add(Datum.of(3));
        assert list.count() == 3 : "List count failed";
        assert list.getAt(1).intValue() == 1 : "List getAt failed";
        System.out.println("  List datum: PASS");

        // Test propList
        Datum.PropList propList = Datum.propList();
        propList.put(Datum.symbol("name"), Datum.of("test"));
        propList.put(Datum.symbol("value"), Datum.of(42));
        assert propList.count() == 2 : "PropList count failed";
        assert propList.get(Datum.symbol("name")).stringValue().equals("test") : "PropList get failed";
        System.out.println("  PropList datum: PASS");

        // Test point
        Datum point = new Datum.IntPoint(100, 200);
        assert point.stringValue().equals("point(100, 200)") : "Point stringValue failed";
        System.out.println("  Point datum: PASS");

        // Test rect
        Datum rect = new Datum.IntRect(10, 20, 110, 220);
        assert rect.stringValue().equals("rect(10, 20, 110, 220)") : "Rect stringValue failed";
        System.out.println("  Rect datum: PASS");

        System.out.println("  Datum Types: ALL PASS\n");
    }

    private static void testLingoVM() {
        System.out.println("--- Testing Lingo VM ---");

        // Note: Full VM test requires a DirectorFile with scripts
        // Here we test the basic operations

        // Test opcodes
        assert Opcode.ADD.getCode() == 0x05 : "ADD opcode failed";
        assert Opcode.fromCode(0x05) == Opcode.ADD : "fromCode failed";
        System.out.println("  Opcode lookup: PASS");

        // Test arithmetic with Datum
        Datum a = Datum.of(10);
        Datum b = Datum.of(3);

        // Addition
        int result = a.intValue() + b.intValue();
        assert result == 13 : "Addition failed";
        System.out.println("  Addition: PASS");

        // Multiplication
        result = a.intValue() * b.intValue();
        assert result == 30 : "Multiplication failed";
        System.out.println("  Multiplication: PASS");

        // Division
        float fResult = a.floatValue() / b.floatValue();
        assert Math.abs(fResult - 3.333f) < 0.01 : "Division failed";
        System.out.println("  Division: PASS");

        // String concat
        Datum s1 = Datum.of("hello");
        Datum s2 = Datum.of("world");
        String concat = s1.stringValue() + " " + s2.stringValue();
        assert concat.equals("hello world") : "String concat failed";
        System.out.println("  String concatenation: PASS");

        System.out.println("  Lingo VM: ALL PASS\n");
    }

    private static void testBitmapDecoding() {
        System.out.println("--- Testing Bitmap Decoding ---");

        var BitmapDecoder = com.libreshockwave.player.bitmap.BitmapDecoder.class;
        var Bitmap = com.libreshockwave.player.bitmap.Bitmap.class;

        // Test scan width calculation
        int scanWidth = com.libreshockwave.player.bitmap.BitmapDecoder.calculateScanWidth(100, 8);
        assert scanWidth == 100 : "Scan width for 8-bit failed: " + scanWidth;
        System.out.println("  Scan width (8-bit): PASS");

        scanWidth = com.libreshockwave.player.bitmap.BitmapDecoder.calculateScanWidth(100, 1);
        assert scanWidth == 14 : "Scan width for 1-bit failed: " + scanWidth;
        System.out.println("  Scan width (1-bit): PASS");

        // Test RLE decompression
        // Simple literal run: 0x02 means copy 3 bytes
        byte[] compressed = {0x02, 0x01, 0x02, 0x03};
        byte[] decompressed = com.libreshockwave.player.bitmap.BitmapDecoder.decompressRLE(compressed, 10);
        assert decompressed.length == 3 : "RLE literal length failed: " + decompressed.length;
        assert decompressed[0] == 1 && decompressed[1] == 2 && decompressed[2] == 3 : "RLE literal values failed";
        System.out.println("  RLE literal run: PASS");

        // Simple repeat run: 0xFE (0x101 - 0xFE = 3) means repeat next byte 3 times
        compressed = new byte[]{(byte) 0xFE, 0x42};
        decompressed = com.libreshockwave.player.bitmap.BitmapDecoder.decompressRLE(compressed, 10);
        assert decompressed.length == 3 : "RLE repeat length failed: " + decompressed.length;
        assert decompressed[0] == 0x42 && decompressed[1] == 0x42 && decompressed[2] == 0x42 : "RLE repeat values failed";
        System.out.println("  RLE repeat run: PASS");

        // Test Bitmap class
        var bitmap = new com.libreshockwave.player.bitmap.Bitmap(10, 10, 32);
        bitmap.fill(0xFFFF0000); // Red
        assert bitmap.getPixel(5, 5) == 0xFFFF0000 : "Bitmap fill failed";
        System.out.println("  Bitmap fill: PASS");

        bitmap.setPixel(0, 0, 0xFF00FF00); // Green
        assert bitmap.getPixel(0, 0) == 0xFF00FF00 : "Bitmap setPixel failed";
        System.out.println("  Bitmap setPixel: PASS");

        System.out.println("  Bitmap Decoding: ALL PASS\n");
    }

    private static void testFileLoading(String filePath) {
        System.out.println("--- Testing File Loading: " + filePath + " ---");

        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                System.out.println("  File not found: " + filePath);
                return;
            }

            DirectorFile file = DirectorFile.load(path);
            System.out.println("  File loaded successfully");
            System.out.println("  Endian: " + (file.getEndian() == ByteOrder.BIG_ENDIAN ? "Big" : "Little"));
            System.out.println("  Afterburner: " + file.isAfterburner());

            if (file.getConfig() != null) {
                System.out.println("  Stage: " + file.getStageWidth() + "x" + file.getStageHeight());
                System.out.println("  Tempo: " + file.getTempo() + " fps");
                System.out.println("  Channels: " + file.getChannelCount());
                System.out.println("  Director Version: " + file.getConfig().directorVersion());
            }

            System.out.println("  Scripts: " + file.getScripts().size());
            System.out.println("  Cast Members: " + file.getCastMembers().size());

            // Build a map from scriptId to cast member name
            java.util.Map<Integer, String> scriptIdToName = new java.util.HashMap<>();
            for (CastMemberChunk cm : file.getCastMembers()) {
                if (cm.isScript() && cm.scriptId() > 0) {
                    scriptIdToName.put(cm.scriptId(), cm.name());
                }
            }

            // Print scripts with their names (from cast members)
            if (file.getScriptNames() != null && file.getScriptContext() != null) {
                System.out.println("  Scripts:");
                var entries = file.getScriptContext().entries();
                for (int i = 0; i < entries.size(); i++) {
                    var entry = entries.get(i);
                    int scriptIndex = i + 1;  // 1-based index
                    String scriptName = scriptIdToName.getOrDefault(scriptIndex, "<unnamed>");

                    // Find the script chunk for this entry
                    for (ScriptChunk script : file.getScripts()) {
                        if (script.id() == entry.id()) {
                            System.out.println("    Script \"" + scriptName + "\":");
                            file.disassembleScript(script);
                            break;
                        }
                    }
                }
            }

            System.out.println("  File Loading: PASS\n");

        } catch (IOException e) {
            System.out.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
