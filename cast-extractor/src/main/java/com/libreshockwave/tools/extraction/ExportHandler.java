package com.libreshockwave.tools.extraction;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.audio.SoundConverter;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.SoundChunk;
import com.libreshockwave.tools.model.MemberNodeData;
import com.libreshockwave.tools.scanning.MemberResolver;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Handles single-file export operations with file chooser dialog.
 */
public class ExportHandler {

    private final Map<String, DirectorFile> loadedFiles;
    private Consumer<String> statusCallback;

    public ExportHandler(Map<String, DirectorFile> loadedFiles) {
        this.loadedFiles = loadedFiles;
    }

    /**
     * Gets a DirectorFile from cache, loading it on demand if not present.
     */
    private DirectorFile getDirectorFile(String filePath) {
        DirectorFile dirFile = loadedFiles.get(filePath);
        if (dirFile == null) {
            try {
                dirFile = DirectorFile.load(Path.of(filePath));
                loadedFiles.put(filePath, dirFile);
            } catch (Exception e) {
                return null;
            }
        }
        return dirFile;
    }

    public void setStatusCallback(Consumer<String> callback) {
        this.statusCallback = callback;
    }

    /**
     * Exports a single member with a save dialog.
     */
    public void export(JFrame parent, MemberNodeData memberData, String lastOutputDir) {
        MemberType type = memberData.memberInfo().memberType();
        DirectorFile dirFile = getDirectorFile(memberData.filePath());
        if (dirFile == null) {
            setStatus("Error: Could not load file");
            return;
        }

        // Sanitize name for default filename
        String safeName = memberData.memberInfo().name().replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safeName.isEmpty()) {
            safeName = type.getName() + "_" + memberData.memberInfo().memberNum();
        }

        JFileChooser chooser = new JFileChooser();
        if (!lastOutputDir.isEmpty()) {
            chooser.setCurrentDirectory(new File(lastOutputDir));
        }

        if (type == MemberType.BITMAP) {
            exportBitmap(parent, chooser, dirFile, memberData, safeName);
        } else if (type == MemberType.SOUND) {
            exportSound(parent, chooser, dirFile, memberData, safeName);
        } else {
            setStatus("Export not supported for " + type.getName() + " members");
        }
    }

    private void exportBitmap(JFrame parent, JFileChooser chooser, DirectorFile dirFile,
                              MemberNodeData memberData, String safeName) {
        chooser.setSelectedFile(new File(safeName + ".png"));
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PNG Image", "png"));

        if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
            File outputFile = chooser.getSelectedFile();
            if (!outputFile.getName().toLowerCase().endsWith(".png")) {
                outputFile = new File(outputFile.getAbsolutePath() + ".png");
            }

            try {
                var bitmapOpt = dirFile.decodeBitmap(memberData.memberInfo().member());
                if (bitmapOpt.isPresent()) {
                    BufferedImage image = bitmapOpt.get().toBufferedImage();
                    ImageIO.write(image, "PNG", outputFile);
                    setStatus("Exported bitmap to: " + outputFile.getName());
                } else {
                    setStatus("Failed to decode bitmap");
                }
            } catch (Exception ex) {
                setStatus("Export error: " + ex.getMessage());
            }
        }
    }

    private void exportSound(JFrame parent, JFileChooser chooser, DirectorFile dirFile,
                             MemberNodeData memberData, String safeName) {
        SoundChunk soundChunk = MemberResolver.findSoundForMember(dirFile, memberData.memberInfo().member());
        if (soundChunk == null) {
            setStatus("Sound data not found");
            return;
        }

        String extension = soundChunk.isMp3() ? ".mp3" : ".wav";
        chooser.setSelectedFile(new File(safeName + extension));

        if (soundChunk.isMp3()) {
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("MP3 Audio", "mp3"));
        } else {
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("WAV Audio", "wav"));
        }

        if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
            File outputFile = chooser.getSelectedFile();
            if (!outputFile.getName().toLowerCase().endsWith(extension)) {
                outputFile = new File(outputFile.getAbsolutePath() + extension);
            }

            try {
                byte[] audioData;
                if (soundChunk.isMp3()) {
                    audioData = SoundConverter.extractMp3(soundChunk);
                } else {
                    audioData = SoundConverter.toWav(soundChunk);
                }

                if (audioData != null && audioData.length > 0) {
                    Files.write(outputFile.toPath(), audioData);
                    setStatus("Exported sound to: " + outputFile.getName());
                } else {
                    setStatus("Failed to export sound");
                }
            } catch (Exception ex) {
                setStatus("Export error: " + ex.getMessage());
            }
        }
    }

    private void setStatus(String status) {
        if (statusCallback != null) {
            statusCallback.accept(status);
        }
    }
}
