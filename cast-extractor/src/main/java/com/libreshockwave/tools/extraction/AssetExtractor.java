package com.libreshockwave.tools.extraction;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.audio.SoundConverter;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.SoundChunk;
import com.libreshockwave.tools.model.CastMemberInfo;
import com.libreshockwave.tools.model.ExtractionTask;
import com.libreshockwave.tools.scanning.MemberResolver;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Extracts assets (bitmaps and sounds) from Director files.
 */
public class AssetExtractor {

    private final Map<String, DirectorFile> loadedFiles;

    public AssetExtractor(Map<String, DirectorFile> loadedFiles) {
        this.loadedFiles = loadedFiles;
    }

    /**
     * Result of an extraction operation.
     */
    public record ExtractionResult(int bitmapsExtracted, int soundsExtracted) {}

    /**
     * Extracts a single asset and returns true if successful.
     */
    public boolean extract(ExtractionTask task, Path outputDir) {
        try {
            DirectorFile dirFile = loadedFiles.get(task.filePath());
            if (dirFile == null) {
                return false;
            }

            // Create subdirectory for each source file
            String sourceFileName = Path.of(task.filePath()).getFileName().toString();
            String baseName = sourceFileName.contains(".")
                    ? sourceFileName.substring(0, sourceFileName.lastIndexOf('.'))
                    : sourceFileName;

            Path subDir = outputDir.resolve(baseName);
            Files.createDirectories(subDir);

            // Sanitize filename
            String safeName = task.memberInfo().name()
                    .replaceAll("[^a-zA-Z0-9._-]", "_");

            if (task.memberInfo().memberType() == MemberType.BITMAP) {
                return extractBitmap(dirFile, task.memberInfo(), subDir, safeName);
            } else if (task.memberInfo().memberType() == MemberType.SOUND) {
                return extractSound(dirFile, task.memberInfo(), subDir, safeName);
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean extractBitmap(DirectorFile dirFile, CastMemberInfo memberInfo, Path subDir, String safeName) {
        try {
            if (safeName.isEmpty()) {
                safeName = "bitmap_" + memberInfo.memberNum();
            }

            var bitmapOpt = dirFile.decodeBitmap(memberInfo.member());
            if (bitmapOpt.isPresent()) {
                BufferedImage image = bitmapOpt.get().toBufferedImage();

                Path outputFile = subDir.resolve(safeName + ".png");

                // Handle duplicates
                int counter = 1;
                while (Files.exists(outputFile)) {
                    outputFile = subDir.resolve(safeName + "_" + counter + ".png");
                    counter++;
                }

                ImageIO.write(image, "PNG", outputFile.toFile());
                return true;
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    private boolean extractSound(DirectorFile dirFile, CastMemberInfo memberInfo, Path subDir, String safeName) {
        try {
            if (safeName.isEmpty()) {
                safeName = "sound_" + memberInfo.memberNum();
            }

            SoundChunk soundChunk = MemberResolver.findSoundForMember(dirFile, memberInfo.member());
            if (soundChunk != null) {
                byte[] audioData;
                String extension;

                if (soundChunk.isMp3()) {
                    // Export as MP3
                    audioData = SoundConverter.extractMp3(soundChunk);
                    extension = ".mp3";
                } else {
                    // Export as WAV
                    audioData = SoundConverter.toWav(soundChunk);
                    extension = ".wav";
                }

                if (audioData != null && audioData.length > 0) {
                    Path outputFile = subDir.resolve(safeName + extension);

                    // Handle duplicates
                    int counter = 1;
                    while (Files.exists(outputFile)) {
                        outputFile = subDir.resolve(safeName + "_" + counter + extension);
                        counter++;
                    }

                    Files.write(outputFile, audioData);
                    return true;
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }
}
