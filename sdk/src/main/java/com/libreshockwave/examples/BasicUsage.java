package com.libreshockwave.examples;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.*;
import com.libreshockwave.handlers.HandlerRegistry;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.player.bitmap.Bitmap;
import com.libreshockwave.player.bitmap.BitmapDecoder;
import com.libreshockwave.vm.LingoVM;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Example usage of the LibreShockwave SDK.
 */
public class BasicUsage {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("LibreShockwave SDK - Example Usage");
            System.out.println("Usage: java BasicUsage <director-file.dir>");
            System.out.println();
            System.out.println("Supported file formats:");
            System.out.println("  .dir - Director movie (uncompressed)");
            System.out.println("  .dxr - Protected Director movie");
            System.out.println("  .dcr - Shockwave movie (Afterburner compressed)");
            System.out.println("  .cst - External cast");
            return;
        }

        String filePath = args[0];

        try {
            // Load the Director file
            System.out.println("Loading: " + filePath);
            DirectorFile file = DirectorFile.load(Path.of(filePath));

            // Print basic info
            printFileInfo(file);

            // List all handlers
            listHandlers(file);

            // Extract bitmaps (example)
            extractBitmaps(file, "output");

            // Execute a handler (if exists)
            executeHandler(file, "startMovie");

        } catch (IOException e) {
            System.err.println("Error loading file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printFileInfo(DirectorFile file) {
        System.out.println();
        System.out.println("=== File Information ===");
        System.out.println("Format: " + (file.isAfterburner() ? "Afterburner (compressed)" : "RIFX (uncompressed)"));
        System.out.println("Byte Order: " + file.getEndian());

        ConfigChunk config = file.getConfig();
        if (config != null) {
            System.out.println("Director Version: " + config.directorVersion());
            System.out.println("Stage Size: " + config.stageWidth() + " x " + config.stageHeight());
            System.out.println("Frame Rate: " + config.tempo() + " fps");
        }

        System.out.println("Cast Members: " + file.getCastMembers().size());
        System.out.println("Scripts: " + file.getScripts().size());
    }

    private static void listHandlers(DirectorFile file) {
        System.out.println();
        System.out.println("=== Script Handlers ===");

        ScriptNamesChunk names = file.getScriptNames();
        if (names == null) {
            System.out.println("No script names found.");
            return;
        }

        int totalHandlers = 0;
        for (ScriptChunk script : file.getScripts()) {
            System.out.println("Script (type " + script.scriptType() + "):");

            for (ScriptChunk.Handler handler : script.handlers()) {
                String name = names.getName(handler.nameId());
                totalHandlers++;

                // Build argument list
                StringBuilder argList = new StringBuilder();
                for (int i = 0; i < handler.argNameIds().size(); i++) {
                    if (i > 0) argList.append(", ");
                    argList.append(names.getName(handler.argNameIds().get(i)));
                }

                System.out.println("  on " + name + "(" + argList + ")");
                System.out.println("    Locals: " + handler.localCount());
                System.out.println("    Instructions: " + handler.instructions().size());

                for (int i = 0; i < handler.instructions().size(); i++) {
                    System.out.println("      " + handler.instructions().get(i));
                }

                /*
                // Optionally show first few instructions
                int showCount = Math.min(5, handler.instructions().size());
                for (int i = 0; i < showCount; i++) {
                    System.out.println("      " + handler.instructions().get(i));
                }
                if (handler.instructions().size() > showCount) {
                    System.out.println("      ... (" + (handler.instructions().size() - showCount) + " more)");
                }*/
            }
        }

        System.out.println("Total handlers: " + totalHandlers);
    }

    private static void extractBitmaps(DirectorFile file, String outputDir) {
        System.out.println();
        System.out.println("=== Extracting Bitmaps ===");

        File dir = new File(outputDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        int extracted = 0;
        for (int i = 0; i < file.getCastMembers().size(); i++) {
            CastMemberChunk member = file.getCastMembers().get(i);
            if (member.memberType() == CastMemberChunk.MemberType.BITMAP) {
                // This is a bitmap member
                // In a real implementation, we would:
                // 1. Find the associated BITD chunk
                // 2. Decode using BitmapDecoder
                // 3. Save to file

                // For now, just log it
                System.out.println("  Found bitmap member #" + member.id());
                extracted++;
            }
        }

        System.out.println("Found " + extracted + " bitmap members");
        System.out.println("(Full extraction requires linking to BITD chunks via KEY* table)");
    }

    private static void executeHandler(DirectorFile file, String handlerName) {
        System.out.println();
        System.out.println("=== Executing Handler: " + handlerName + " ===");

        // Check if handler exists
        ScriptNamesChunk names = file.getScriptNames();
        if (names == null) {
            System.out.println("No scripts available.");
            return;
        }

        int nameId = names.findName(handlerName);
        if (nameId < 0) {
            System.out.println("Handler '" + handlerName + "' not found.");
            return;
        }

        // Create VM and execute
        LingoVM vm = new LingoVM(file);

        // Register all built-in handlers
        HandlerRegistry.registerAll(vm);

        try {
            System.out.println("Calling " + handlerName + "()...");
            Datum result = vm.call(handlerName);
            System.out.println("Result: " + result.stringValue());
        } catch (Exception e) {
            System.out.println("Execution error: " + e.getMessage());
        }
    }

    /**
     * Example: Decode and save a bitmap to PNG.
     */
    private static void saveBitmapToPng(Bitmap bitmap, String filename) throws IOException {
        BufferedImage image = bitmap.toBufferedImage();
        ImageIO.write(image, "PNG", new File(filename));
        System.out.println("Saved: " + filename);
    }
}
