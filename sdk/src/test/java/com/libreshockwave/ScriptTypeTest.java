package com.libreshockwave;

import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.cast.MemberType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test for verifying script type detection in habbo.dcr.
 * Confirms that scripts are correctly identified as BEHAVIOR or MOVIE_SCRIPT.
 */
public class ScriptTypeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== Script Type Test ===\n");

        String testFile = "C:/SourceControl/habbo.dcr";
        if (args.length > 0) {
            testFile = args[0];
        }

        testScriptTypes(testFile);

        System.out.println("\n=== Script Type Test Complete ===");
        System.out.println("Passed: " + passed + ", Failed: " + failed);

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void testScriptTypes(String filePath) {
        System.out.println("--- Testing Script Types: " + filePath + " ---\n");

        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                System.out.println("  SKIP: File not found: " + filePath);
                return;
            }

            DirectorFile file = DirectorFile.load(path);

            // Test: "Init" is SCORE (attached to sprites in score)
            assertScriptType(file, "Init", ScriptChunk.ScriptType.SCORE);

            // Test: "Loop" is SCORE (attached to sprites in score)
            assertScriptType(file, "Loop", ScriptChunk.ScriptType.SCORE);

            // Test: "Initialization" is MOVIE_SCRIPT (global movie script)
            assertScriptType(file, "Initialization", ScriptChunk.ScriptType.MOVIE_SCRIPT);

            // Print all scripts for debugging
            System.out.println("\n--- All Scripts in File ---");
            for (CastMemberChunk member : file.getCastMembers()) {
                if (member.memberType() == MemberType.SCRIPT) {
                    ScriptChunk.ScriptType scriptType = member.getScriptType();
                    String typeName = scriptType != null ? scriptType.name() : "UNKNOWN";
                    System.out.println("  " + member.name() + " -> " + typeName);
                }
            }

        } catch (IOException e) {
            System.out.println("  FAILED: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }
    }

    private static void assertScriptType(DirectorFile file, String scriptName, ScriptChunk.ScriptType expectedType) {
        System.out.print("  Testing script '" + scriptName + "' is " + expectedType + "... ");

        // Find cast member with this name and type SCRIPT
        CastMemberChunk scriptMember = null;
        for (CastMemberChunk member : file.getCastMembers()) {
            if (member.memberType() == MemberType.SCRIPT && scriptName.equalsIgnoreCase(member.name())) {
                scriptMember = member;
                break;
            }
        }

        if (scriptMember == null) {
            System.out.println("FAILED - Cast member not found");
            failed++;
            return;
        }

        // Get script type from the cast member's specific data (per dirplayer-rs)
        ScriptChunk.ScriptType actualType = scriptMember.getScriptType();
        if (actualType == null) {
            System.out.println("FAILED - Could not read script type from cast member");
            failed++;
            return;
        }

        if (actualType == expectedType) {
            System.out.println("PASS");
            passed++;
        } else {
            System.out.println("FAILED - Expected " + expectedType + " but got " + actualType);
            failed++;
        }
    }
}
