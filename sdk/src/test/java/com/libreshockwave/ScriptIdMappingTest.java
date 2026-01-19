package com.libreshockwave;

import com.libreshockwave.chunks.*;
import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Test for verifying script ID to ScriptChunk mapping.
 *
 * The scriptId in CastMemberChunk is a 1-based index into ScriptContextChunk.entries,
 * NOT the resource ID of the Lscr chunk. This test verifies that the mapping works correctly.
 */
public class ScriptIdMappingTest {

    public static void main(String[] args) throws Exception {
        String baseUrl = "http://localhost/assets";
        String movieUrl = baseUrl + "/movie.dcr";

        if (args.length > 0) {
            movieUrl = args[0];
            baseUrl = movieUrl.substring(0, movieUrl.lastIndexOf('/'));
        }

        System.out.println("=== Script ID Mapping Test ===\n");
        System.out.println("Movie URL: " + movieUrl);

        HttpClient client = HttpClient.newHttpClient();

        // Load the main movie
        System.out.println("\n--- Loading movie ---");
        byte[] movieData = fetchUrl(client, movieUrl);
        System.out.println("Downloaded: " + movieData.length + " bytes");

        DirectorFile movieFile = DirectorFile.load(movieData);
        System.out.println("Parsed DirectorFile successfully");

        if (movieFile.getConfig() != null) {
            System.out.println("Director version: " + movieFile.getConfig().directorVersion());
        }

        // Create cast manager
        CastManager castManager = movieFile.createCastManager();
        System.out.println("\nCast libraries: " + castManager.getCastCount());

        // Load external casts if any
        for (CastLib cast : castManager.getCasts()) {
            if (cast.isExternal() && cast.getState() == CastLib.State.NONE && !cast.getFileName().isEmpty()) {
                String castUrl = baseUrl + "/" + cast.getFileName();
                try {
                    byte[] castData = fetchUrl(client, castUrl);
                    DirectorFile castFile = DirectorFile.load(castData);
                    cast.loadFromDirectorFile(castFile);
                    System.out.println("Loaded external cast: " + cast.getName() + " (" + cast.getMemberCount() + " members)");
                } catch (Exception e) {
                    System.out.println("Failed to load external cast: " + cast.getFileName() + " - " + e.getMessage());
                }
            }
        }

        // Test script ID mapping
        System.out.println("\n--- Testing Script ID Mapping ---");

        int testCount = 0;
        int passCount = 0;
        int failCount = 0;

        for (CastLib cast : castManager.getCasts()) {
            if (cast.getState() != CastLib.State.LOADED) continue;

            System.out.println("\nCast #" + cast.getNumber() + " '" + cast.getName() + "':");

            for (var entry : cast.getMemberEntries()) {
                int slot = entry.getKey();
                CastMemberChunk member = entry.getValue();

                if (member.isScript() && member.scriptId() > 0) {
                    testCount++;
                    String memberName = member.name();
                    int scriptId = member.scriptId();

                    // Try to get the script using the scriptId
                    ScriptChunk script = cast.getScriptByScriptId(scriptId);

                    if (script != null) {
                        passCount++;
                        System.out.println("  [PASS] Member '" + memberName + "' (slot " + slot +
                            ", scriptId=" + scriptId + ") -> Script (resourceId=" + script.id() +
                            ", handlers=" + script.handlers().size() + ")");

                        // Print handler names if we have script names
                        if (cast.getScriptNames() != null && !script.handlers().isEmpty()) {
                            for (ScriptChunk.Handler handler : script.handlers()) {
                                String handlerName = cast.getScriptNames().getName(handler.nameId());
                                System.out.println("       - " + handlerName + "(" + handler.argCount() + " args)");
                            }
                        }
                    } else {
                        failCount++;
                        System.out.println("  [FAIL] Member '" + memberName + "' (slot " + slot +
                            ", scriptId=" + scriptId + ") -> Script NOT FOUND!");
                    }
                }
            }
        }

        // Summary
        System.out.println("\n--- Test Summary ---");
        System.out.println("Total script members tested: " + testCount);
        System.out.println("Passed: " + passCount);
        System.out.println("Failed: " + failCount);

        if (failCount > 0) {
            System.out.println("\nWARNING: Some script lookups failed!");
            System.exit(1);
        } else if (testCount > 0) {
            System.out.println("\nAll script ID mappings resolved correctly!");
        } else {
            System.out.println("\nNo script members found in the movie.");
        }

        System.out.println("\n=== Test Complete ===");
    }

    private static byte[] fetchUrl(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }
}
