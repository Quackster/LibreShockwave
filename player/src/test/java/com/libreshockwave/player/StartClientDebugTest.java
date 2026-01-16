package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.net.NetManager;
import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.xtras.XtraManager;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Debug test to investigate why startClient isn't being called.
 * Run with: gradlew :player:runStartClientDebug -Purl=http://localhost:8080/asset/movie.dcr
 * Or for local files: gradlew :player:runStartClientDebug -Purl=file:///path/to/movie.dcr
 */
public class StartClientDebugTest {

    public static void main(String[] args) throws Exception {
        String location = args.length > 0 ? args[0] : "runtime/src/main/resources/player/assets/movie.dcr";
        System.out.println("=== StartClient Debug Test ===");
        System.out.println("Loading: " + location + "\n");

        byte[] movieData;
        String basePath;

        if (location.startsWith("http://") || location.startsWith("https://")) {
            // Fetch from URL
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(location))
                .build();
            movieData = client.send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
            basePath = location.substring(0, location.lastIndexOf('/') + 1);
        } else {
            // Load from local file
            Path filePath = Path.of(location);
            if (!filePath.isAbsolute()) {
                // Resolve relative to project root
                filePath = Path.of(System.getProperty("user.dir")).resolve(location);
            }
            System.out.println("Resolved path: " + filePath);
            movieData = Files.readAllBytes(filePath);
            basePath = filePath.getParent().toUri().toString();
        }

        System.out.println("Movie data: " + movieData.length + " bytes");
        System.out.println("Base path: " + basePath + "\n");

        // Parse movie
        DirectorFile movieFile = DirectorFile.load(movieData);
        System.out.println("DirectorFile loaded");
        System.out.println("  Endian: " + movieFile.getEndian());

        ConfigChunk config = movieFile.getConfig();
        if (config != null) {
            System.out.println("  Director version: " + config.directorVersion());
        }

        // Create cast manager
        CastManager castManager = movieFile.createCastManager();
        System.out.println("\n=== Casts ===");
        System.out.println("Total casts: " + castManager.getCastCount());

        for (CastLib cast : castManager.getCasts()) {
            System.out.println("\nCast #" + cast.getNumber() + ": '" + cast.getName() + "'");
            System.out.println("  External: " + cast.isExternal());
            System.out.println("  State: " + cast.getState());
            System.out.println("  Members: " + cast.getMemberCount());
            if (cast.isExternal()) {
                System.out.println("  FileName: '" + cast.getFileName() + "'");
            }
        }

        // Load external casts
        System.out.println("\n=== Loading External Casts ===");
        HttpClient client = HttpClient.newHttpClient();

        for (CastLib cast : castManager.getCasts()) {
            if (cast.isExternal() && cast.getState() == CastLib.State.NONE) {
                String fileName = cast.getFileName();
                if (fileName.isEmpty()) continue;

                // Try to load the cast file
                String[] extensions = {"", ".cct", ".cst", ".cxt"};
                for (String ext : extensions) {
                    String baseName = fileName.replaceAll("\\.(cct|cst|cxt)$", "");
                    String castLocation = basePath + baseName + ext;
                    System.out.println("  Trying: " + castLocation);

                    try {
                        byte[] castData;
                        if (castLocation.startsWith("file:")) {
                            // Load from local file
                            Path castPath = Path.of(URI.create(castLocation));
                            if (!Files.exists(castPath)) {
                                System.out.println("    File not found");
                                continue;
                            }
                            castData = Files.readAllBytes(castPath);
                            System.out.println("    Read: " + castData.length + " bytes");
                        } else {
                            // Load from URL
                            HttpRequest castRequest = HttpRequest.newBuilder()
                                .uri(URI.create(castLocation))
                                .build();
                            HttpResponse<byte[]> response = client.send(castRequest, HttpResponse.BodyHandlers.ofByteArray());

                            if (response.statusCode() != 200) {
                                System.out.println("    HTTP " + response.statusCode());
                                continue;
                            }
                            castData = response.body();
                            System.out.println("    Downloaded: " + castData.length + " bytes");
                        }

                        DirectorFile castFile = DirectorFile.load(castData);
                        cast.loadFromDirectorFile(castFile);
                        System.out.println("    Loaded " + cast.getMemberCount() + " members");

                        // List script members
                        for (CastMemberChunk member : cast.getAllMembers()) {
                            if (member.memberType() == CastMemberChunk.MemberType.SCRIPT) {
                                System.out.println("      Script member: #" + member.id() + " '" + member.name() + "'");
                            }
                        }
                        break;
                    } catch (Exception e) {
                        System.out.println("    Error: " + e.getMessage());
                    }
                }
            }
        }

        // List all scripts
        System.out.println("\n=== Scripts in Movie ===");
        ScriptNamesChunk names = movieFile.getScriptNames();
        System.out.println("ScriptNames: " + (names != null ? names.names().size() + " names" : "NULL"));
        if (names != null) {
            System.out.println("Names: " + names.names());
        }

        for (ScriptChunk script : movieFile.getScripts()) {
            System.out.println("\nScript #" + script.id() + " (" + script.scriptType() + ")");
            for (ScriptChunk.Handler handler : script.handlers()) {
                String handlerName = names != null ? names.getName(handler.nameId()) : "?" + handler.nameId();
                System.out.println("  Handler: " + handlerName + " (nameId=" + handler.nameId() +
                    ", " + handler.instructions().size() + " instructions)");
            }
        }

        // Search for startClient in all casts' scripts
        System.out.println("\n=== Searching for 'startClient' handler ===");

        // Check movie scripts
        if (names != null) {
            int startClientId = names.findName("startClient");
            System.out.println("startClient nameId in movie: " + startClientId);

            if (startClientId >= 0) {
                for (ScriptChunk script : movieFile.getScripts()) {
                    for (ScriptChunk.Handler handler : script.handlers()) {
                        if (handler.nameId() == startClientId) {
                            System.out.println("  FOUND in movie script #" + script.id());
                        }
                    }
                }
            }
        }

        // Check external cast scripts
        for (CastLib cast : castManager.getCasts()) {
            if (cast.isExternal() && cast.getState() == CastLib.State.LOADED) {
                ScriptNamesChunk castNames = cast.getScriptNames();
                System.out.println("\nSearching in cast #" + cast.getNumber() + " '" + cast.getName() + "':");
                System.out.println("  ScriptNames: " + (castNames != null ? castNames.names().size() + " names" : "NULL"));
                if (castNames != null) {
                    System.out.println("  Names: " + castNames.names());
                    int startClientId = castNames.findName("startClient");
                    System.out.println("  startClient nameId: " + startClientId);

                    for (ScriptChunk script : cast.getAllScripts()) {
                        System.out.println("  Script #" + script.id() + " (" + script.scriptType() + ")");
                        for (ScriptChunk.Handler handler : script.handlers()) {
                            String handlerName = castNames.getName(handler.nameId());
                            System.out.println("    Handler: " + handlerName);
                            if (handlerName.equalsIgnoreCase("startClient")) {
                                System.out.println("    *** FOUND startClient! ***");
                            }
                        }
                    }
                }
            }
        }

        // Create VM and try to execute startMovie/prepareMovie
        System.out.println("\n=== Testing VM Execution ===");
        LingoVM vm = new LingoVM(movieFile);
        vm.setDebugMode(true);
        vm.setDebugOutputCallback(msg -> System.out.println("[VM] " + msg));

        // Set cast manager so VM can find handlers in external casts
        vm.setCastManager(castManager);

        // Set up NetManager
        NetManager netManager = new NetManager();
        netManager.setBasePath(basePath);
        vm.setNetManager(netManager);

        // Register Xtras
        XtraManager xtraManager = XtraManager.createWithStandardXtras();
        xtraManager.registerAll(vm);

        // Try executing startup handlers
        System.out.println("\n--- Trying prepareMovie ---");
        tryExecuteHandler(vm, movieFile, "prepareMovie");

        System.out.println("\n--- Trying startMovie ---");
        tryExecuteHandler(vm, movieFile, "startMovie");

        System.out.println("\n--- Trying startClient (movie only - old method) ---");
        tryExecuteHandler(vm, movieFile, "startClient");

        System.out.println("\n--- Trying startClient via vm.call() (searches all casts) ---");
        try {
            vm.call("startClient");
            System.out.println("startClient executed successfully!");
        } catch (Exception e) {
            System.out.println("Error calling startClient: " + e.getMessage());
        }

        System.out.println("\n=== Done ===");
    }

    private static void tryExecuteHandler(LingoVM vm, DirectorFile movieFile, String handlerName) {
        ScriptNamesChunk names = movieFile.getScriptNames();
        if (names == null) {
            System.out.println("No ScriptNamesChunk");
            return;
        }

        int nameId = names.findName(handlerName);
        if (nameId < 0) {
            System.out.println(handlerName + " not found in Lnam (searched: " + names.names() + ")");
            return;
        }
        System.out.println(handlerName + " nameId=" + nameId);

        for (ScriptChunk script : movieFile.getScripts()) {
            for (ScriptChunk.Handler handler : script.handlers()) {
                if (handler.nameId() == nameId) {
                    System.out.println("Found in script #" + script.id() + ", executing...");
                    try {
                        vm.execute(script, handler, new Datum[0]);
                        System.out.println("Execution completed");
                    } catch (Exception e) {
                        System.out.println("Error: " + e.getMessage());
                        e.printStackTrace();
                    }
                    return;
                }
            }
        }
        System.out.println("Handler found in Lnam but no matching handler in scripts");
    }
}
