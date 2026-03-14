package com.libreshockwave.editor.panel;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.FrameLabelsChunk;
import com.libreshockwave.editor.EditorContext;
import com.libreshockwave.editor.model.ScoreCellData;
import com.libreshockwave.editor.score.*;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Score window - Director MX 2004 timeline with channels, frames, and markers.
 * Uses custom-painted ScorePanel with ChannelHeader and MarkersBar to recreate
 * the pixel-level look of Director MX 2004's Score window.
 */
public class ScoreWindow extends EditorPanel {

    private final ScorePanel scorePanel;
    private final ScoreModel scoreModel;
    private final ChannelHeader channelHeader;
    private final MarkersBar markersBar;
    private final PlaybackHead playbackHead;
    private final JLabel statusLabel;
    private final JPanel contentPanel;

    public ScoreWindow(EditorContext context) {
        super("Score", context, true, true, true, true);

        contentPanel = new JPanel(new BorderLayout());

        scoreModel = new ScoreModel();
        playbackHead = new PlaybackHead();

        // Score grid
        scorePanel = new ScorePanel();
        scorePanel.setModel(scoreModel);

        // Channel header on the left
        channelHeader = new ChannelHeader();

        // Markers bar at the top
        markersBar = new MarkersBar();

        // Assemble the score layout: markers on top, channel header on left, score grid in center
        JPanel scoreArea = new JPanel(new BorderLayout());
        scoreArea.add(markersBar, BorderLayout.NORTH);

        // Wrap score grid + channel header in a scroll pane
        JPanel gridWithHeader = new JPanel(new BorderLayout());
        gridWithHeader.add(channelHeader, BorderLayout.WEST);
        gridWithHeader.add(scorePanel, BorderLayout.CENTER);

        JScrollPane scrollPane = new JScrollPane(gridWithHeader);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(12);
        scrollPane.getVerticalScrollBar().setUnitIncrement(14);
        scoreArea.add(scrollPane, BorderLayout.CENTER);

        // Status bar
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusLabel = new JLabel("Frame: 1 | Channel: -");
        statusBar.add(statusLabel);

        contentPanel.add(scoreArea, BorderLayout.CENTER);
        contentPanel.add(statusBar, BorderLayout.SOUTH);

        setContentPane(contentPanel);
        setSize(700, 300);
    }

    @Override
    protected void onFileOpened(DirectorFile file) {
        ScoreDataBuilder builder = new ScoreDataBuilder();
        ScoreCellData[][] data = builder.buildScoreData(file);

        if (data.length == 0 || data[0].length == 0) {
            statusLabel.setText("Score: No score data");
            return;
        }

        int channelCount = data.length;
        int frameCount = data[0].length;

        // Populate ScoreModel with cell colors from ScoreDataBuilder data
        ScoreModel model = new ScoreModel(frameCount, channelCount);
        for (int ch = 0; ch < channelCount; ch++) {
            for (int fr = 0; fr < frameCount; fr++) {
                if (data[ch][fr] != null) {
                    // Map member to color based on channel/member type
                    Color cellColor = getCellColor(ch, data[ch][fr]);
                    model.setCellColor(ch, fr, cellColor);
                }
            }
        }

        scorePanel.setModel(model);
        scorePanel.setCurrentFrame(1);

        // Update channel header
        int spriteChannels = Math.max(0, channelCount - 6);
        channelHeader.setSpriteChannelCount(spriteChannels);

        // Load frame label markers
        FrameLabelsChunk labels = file.getFrameLabelsChunk();
        if (labels != null) {
            Map<Integer, String> markerMap = new LinkedHashMap<>();
            for (FrameLabelsChunk.FrameLabel label : labels.labels()) {
                markerMap.put(label.frameNum().value(), label.label());
            }
            markersBar.setMarkers(markerMap);
        }

        statusLabel.setText(frameCount + " frames, " + channelCount + " channels");
        contentPanel.revalidate();
        contentPanel.repaint();
    }

    @Override
    protected void onFileClosed() {
        scorePanel.setModel(new ScoreModel());
        channelHeader.setSpriteChannelCount(48);
        markersBar.setMarkers(new LinkedHashMap<>());
        statusLabel.setText("Frame: 1 | Channel: -");
        contentPanel.revalidate();
        contentPanel.repaint();
    }

    @Override
    protected void onFrameChanged(int frame) {
        playbackHead.setFrame(frame);
        scorePanel.setCurrentFrame(frame);
        statusLabel.setText("Frame: " + frame);
    }

    /**
     * Determine cell color based on channel type and score data.
     * Special channels (0-5) get specific colors, sprite channels use member type.
     */
    private Color getCellColor(int channel, ScoreCellData data) {
        return switch (channel) {
            case 0 -> ScoreColors.SCRIPT;        // Tempo/Script channel
            case 1 -> ScoreColors.PALETTE;        // Palette channel
            case 2 -> ScoreColors.TRANSITION;     // Transition channel
            case 3, 4 -> ScoreColors.SOUND;       // Sound channels
            case 5 -> ScoreColors.SCRIPT;         // Script channel
            default -> {
                // Sprite channels - color by sprite type
                int spriteType = data.spriteType();
                yield switch (spriteType) {
                    case 1 -> ScoreColors.BITMAP;     // Bitmap
                    case 2 -> ScoreColors.SHAPE;      // Shape
                    case 3 -> ScoreColors.TEXT;        // Text
                    case 4 -> ScoreColors.BUTTON;      // Button
                    case 6 -> ScoreColors.FILM_LOOP;   // Film Loop
                    case 7 -> ScoreColors.FIELD;       // Field
                    default -> ScoreColors.UNKNOWN;
                };
            }
        };
    }
}
