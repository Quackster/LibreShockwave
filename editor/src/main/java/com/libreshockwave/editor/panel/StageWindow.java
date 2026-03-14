package com.libreshockwave.editor.panel;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.editor.EditorContext;
import com.libreshockwave.player.Player;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.render.pipeline.FrameSnapshot;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Stage window - displays the live player rendering.
 * Renders the Player's FrameSnapshot and forwards mouse events for interaction.
 */
public class StageWindow extends EditorPanel {

    private final StageCanvas canvas;

    public StageWindow(EditorContext context) {
        super("Stage", context, true, true, true, true);
        canvas = new StageCanvas();
        canvas.setBackground(Color.WHITE);
        setContentPane(canvas);
        setSize(660, 500);
    }

    @Override
    protected void onFileOpened(DirectorFile file) {
        int w = file.getStageWidth();
        int h = file.getStageHeight();
        if (w > 0 && h > 0) {
            canvas.setPreferredSize(new Dimension(w, h));
        }
        setTitle("Stage - " + (context.getCurrentPath() != null
            ? context.getCurrentPath().getFileName() : "Untitled"));
        canvas.repaint();
    }

    @Override
    protected void onFileClosed() {
        setTitle("Stage");
        canvas.repaint();
    }

    @Override
    protected void onFrameChanged(int frame) {
        canvas.repaint();
    }

    private class StageCanvas extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Player player = context.getPlayer();
            if (player == null) {
                g.setColor(Color.GRAY);
                g.drawString("No movie loaded", 20, 30);
                return;
            }

            FrameSnapshot snapshot = player.getFrameSnapshot();
            if (snapshot == null) return;

            Bitmap frameBitmap = snapshot.renderFrame();
            if (frameBitmap == null) return;

            int w = snapshot.stageWidth();
            int h = snapshot.stageHeight();
            int[] pixels = frameBitmap.getPixels();
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            int[] destPixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            System.arraycopy(pixels, 0, destPixels, 0, Math.min(pixels.length, destPixels.length));
            g.drawImage(image, 0, 0, null);
        }
    }
}
