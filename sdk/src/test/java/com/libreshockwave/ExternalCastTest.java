package com.libreshockwave;

import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;
import com.libreshockwave.player.Score;
import com.libreshockwave.player.Sprite;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test for loading movies with external casts.
 */
public class ExternalCastTest {

    public static void main(String[] args) throws Exception {
        String baseUrl = "http://localhost:8080/assets";
        String movieUrl = baseUrl + "/movie.dcr";
        String[] externalCasts = {"fuse_client.cct", "empty.cct"};

        if (args.length > 0) {
            baseUrl = args[0];
            movieUrl = baseUrl + "/movie.dcr";
        }

        System.out.println("=== External Cast Loading Test ===\n");
        System.out.println("Base URL: " + baseUrl);
        System.out.println("Movie URL: " + movieUrl);

        HttpClient client = HttpClient.newHttpClient();

        // Load the main movie
        System.out.println("\n--- Loading main movie ---");
        byte[] movieData = fetchUrl(client, movieUrl);
        System.out.println("Downloaded movie: " + movieData.length + " bytes");

        DirectorFile movieFile = DirectorFile.load(movieData);
        System.out.println("Parsed DirectorFile successfully");
        System.out.println("  Endian: " + movieFile.getEndian());

        if (movieFile.getConfig() != null) {
            System.out.println("  Director version: " + movieFile.getConfig().directorVersion());
            System.out.println("  Stage: " +
                (movieFile.getConfig().stageRight() - movieFile.getConfig().stageLeft()) + "x" +
                (movieFile.getConfig().stageBottom() - movieFile.getConfig().stageTop()));
        }

        // Create cast manager
        CastManager castManager = movieFile.createCastManager();
        System.out.println("\nCast libraries: " + castManager.getCastCount());

        for (CastLib cast : castManager.getCasts()) {
            System.out.println("  Cast #" + cast.getNumber() + ": '" + cast.getName() + "'");
            System.out.println("    External: " + cast.isExternal());
            System.out.println("    State: " + cast.getState());
            System.out.println("    Members: " + cast.getMemberCount());
            if (cast.isExternal()) {
                System.out.println("    File: " + cast.getFileName());
            }
        }

        // Load external casts
        System.out.println("\n--- Loading external casts ---");
        for (CastLib cast : castManager.getCasts()) {
            if (cast.isExternal() && cast.getState() == CastLib.State.NONE && !cast.getFileName().isEmpty()) {
                String castFileName = cast.getFileName();
                String castUrl = baseUrl + "/" + castFileName;

                // Try different extensions
                String[] extensions = {"", ".cct", ".cst", ".cxt"};
                byte[] castData = null;

                for (String ext : extensions) {
                    String tryUrl = castUrl.replaceAll("\\.(cct|cst|cxt)$", "") + ext;
                    if (ext.isEmpty() && !castUrl.matches(".*\\.(cct|cst|cxt)$")) {
                        continue;
                    }
                    try {
                        System.out.println("  Trying: " + tryUrl);
                        castData = fetchUrl(client, tryUrl);
                        System.out.println("  Downloaded: " + castData.length + " bytes");
                        break;
                    } catch (Exception e) {
                        // Try next extension
                    }
                }

                if (castData != null) {
                    try {
                        System.out.println("  Parsing external cast...");
                        DirectorFile castFile = DirectorFile.load(castData);
                        cast.loadFromDirectorFile(castFile);
                        System.out.println("  Loaded cast #" + cast.getNumber() + ": " + cast.getMemberCount() + " members");

                        // List members
                        int bitmaps = 0, scripts = 0, others = 0;
                        for (CastMemberChunk member : cast.getAllMembers()) {
                            if (member.isBitmap()) bitmaps++;
                            else if (member.memberType() == CastMemberChunk.MemberType.SCRIPT) scripts++;
                            else others++;
                        }
                        System.out.println("    Bitmaps: " + bitmaps + ", Scripts: " + scripts + ", Others: " + others);
                    } catch (Exception e) {
                        System.out.println("  FAILED to parse cast: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    System.out.println("  Failed to download external cast: " + castFileName);
                }
            }
        }

        // Create and analyze score
        System.out.println("\n--- Score Analysis ---");
        if (movieFile.hasScore()) {
            Score score = movieFile.createScore();
            System.out.println("Score frames: " + score.getFrameCount());
            System.out.println("Score channels: " + score.getChannelCount());

            // Analyze first few frames
            for (int f = 1; f <= Math.min(5, score.getFrameCount()); f++) {
                Score.Frame frame = score.getFrame(f);
                if (frame != null) {
                    System.out.println("\nFrame " + f + ":");
                    if (frame.hasFrameScript()) {
                        System.out.println("  Frame script: " + frame.getScriptCastLib() + ":" + frame.getScriptCastMember());
                    }
                    for (Sprite sprite : frame.getSpritesSorted()) {
                        System.out.println("  " + sprite);

                        // Try to resolve the cast member
                        CastMemberChunk member = castManager.getMember(
                            sprite.getCastLib() > 0 ? sprite.getCastLib() : 1,
                            sprite.getCastMember()
                        );
                        if (member != null) {
                            System.out.println("    -> " + member.memberType() + " '" + member.name() + "'");
                        } else {
                            System.out.println("    -> MEMBER NOT FOUND");
                        }
                    }
                }
            }
        } else {
            System.out.println("No score found");
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
