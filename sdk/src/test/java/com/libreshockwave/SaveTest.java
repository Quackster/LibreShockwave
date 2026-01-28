package com.libreshockwave;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Test for saving Director files.
 */
public class SaveTest {

    public static void main(String[] args) throws Exception {
        // Test with fuse_client.cct
        Path inputPath = Path.of("C:/Users/alexm/Desktop/lasm/test/fuse_client.cct");

        if (!Files.exists(inputPath)) {
            System.out.println("File not found: " + inputPath);
            return;
        }

        System.out.println("=== LibreShockwave Save Test ===\n");

        // Read original file
        byte[] originalBytes = Files.readAllBytes(inputPath);
        String originalMd5 = md5(originalBytes);
        System.out.println("Original file: " + inputPath);
        System.out.println("Original size: " + originalBytes.length + " bytes");
        System.out.println("Original MD5:  " + originalMd5);

        // Load the file
        System.out.println("\nLoading file...");
        DirectorFile file = DirectorFile.load(inputPath);
        System.out.println("  Afterburner: " + file.isAfterburner());
        System.out.println("  Endian: " + file.getEndian());
        System.out.println("  Movie type: " + file.getMovieType());
        if (file.getConfig() != null) {
            System.out.println("  Director version: " + file.getConfig().directorVersion());
            System.out.println("  Stage: " + file.getConfig().stageWidth() + "x" + file.getConfig().stageHeight());
        }
        System.out.println("  Chunks: " + file.getAllChunkInfo().size());
        System.out.println("  Cast members: " + file.getCastMembers().size());
        System.out.println("  Scripts: " + file.getScripts().size());
        System.out.println("  Raw chunk data entries: " + file.getRawChunkData().size());

        // Save to a new file
        Path outputPath = Path.of("test_output/fuse_client_saved.cct");
        Files.createDirectories(outputPath.getParent());

        System.out.println("\nSaving file...");
        file.save(outputPath);

        // Read saved file
        byte[] savedBytes = Files.readAllBytes(outputPath);
        String savedMd5 = md5(savedBytes);
        System.out.println("\nSaved file: " + outputPath);
        System.out.println("Saved size: " + savedBytes.length + " bytes");
        System.out.println("Saved MD5:  " + savedMd5);

        // Load saved file to verify
        System.out.println("\nLoading saved file to verify...");
        DirectorFile savedFile = DirectorFile.load(outputPath);
        System.out.println("  Afterburner: " + savedFile.isAfterburner());
        System.out.println("  Endian: " + savedFile.getEndian());
        System.out.println("  Movie type: " + savedFile.getMovieType());
        if (savedFile.getConfig() != null) {
            System.out.println("  Director version: " + savedFile.getConfig().directorVersion());
            System.out.println("  Stage: " + savedFile.getConfig().stageWidth() + "x" + savedFile.getConfig().stageHeight());
        }
        System.out.println("  Chunks: " + savedFile.getAllChunkInfo().size());
        System.out.println("  Cast members: " + savedFile.getCastMembers().size());
        System.out.println("  Scripts: " + savedFile.getScripts().size());

        // Compare
        System.out.println("\n=== Comparison ===");
        System.out.println("Size: " + originalBytes.length + " -> " + savedBytes.length +
            " (" + (savedBytes.length - originalBytes.length) + " bytes difference)");

        // For Afterburner->RIFX conversion, chunk counts will differ slightly
        // because RIFX includes imap/mmap entries in the chunk list
        System.out.println("Original chunks: " + file.getAllChunkInfo().size());
        System.out.println("Saved chunks: " + savedFile.getAllChunkInfo().size());

        boolean membersMatch = file.getCastMembers().size() == savedFile.getCastMembers().size();
        boolean scriptsMatch = file.getScripts().size() == savedFile.getScripts().size();

        System.out.println("Cast members match: " + membersMatch);
        System.out.println("Scripts match: " + scriptsMatch);

        if (membersMatch && scriptsMatch) {
            System.out.println("\n=== SUCCESS: File saved and verified correctly! ===");
        } else {
            System.out.println("\n=== FAILURE: Data mismatch ===");
            System.exit(1);
        }
    }

    private static String md5(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] hash = md.digest(data);
        return HexFormat.of().formatHex(hash);
    }
}
