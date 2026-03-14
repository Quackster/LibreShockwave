package com.libreshockwave.editor.panel;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.editor.EditorContext;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.input.DirectorKeyCodes;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.player.render.pipeline.FrameSnapshot;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Stage window - displays the live player rendering.
 * Renders the Player's FrameSnapshot and forwards mouse/keyboard events for interaction.
 * The stage canvas maintains a fixed size and is centered within the panel.
 */
public class StageWindow extends EditorPanel {

    private final StageCanvas canvas;

    public StageWindow(EditorContext context) {
        super("Stage", context, true, true, true, true);
        canvas = new StageCanvas();
        setContentPane(canvas);
        setSize(660, 500);
    }

    @Override
    protected void onFileOpened(DirectorFile file) {
        int w = file.getStageWidth();
        int h = file.getStageHeight();
        canvas.setStageSize(w, h);
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

        private static final Color VIEWPORT_COLOR = new Color(48, 48, 48);
        private static final Color CANVAS_BORDER_COLOR = new Color(80, 80, 80);

        private int stageWidth = 640;
        private int stageHeight = 480;

        StageCanvas() {
            setDoubleBuffered(true);
            setBackground(VIEWPORT_COLOR);
            setFocusable(true);
            setupInputListeners();
        }

        void setStageSize(int width, int height) {
            this.stageWidth = width > 0 ? width : 640;
            this.stageHeight = height > 0 ? height : 480;
            repaint();
        }

        // --- Coordinate translation ---

        private int toStageX(int panelX) {
            int canvasX = (getWidth() - stageWidth) / 2;
            return panelX - canvasX;
        }

        private int toStageY(int panelY) {
            int canvasY = (getHeight() - stageHeight) / 2;
            return panelY - canvasY;
        }

        // --- Input listeners ---

        private void setupInputListeners() {
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    Player player = context.getPlayer();
                    if (player == null) return;
                    int sx = toStageX(e.getX());
                    int sy = toStageY(e.getY());
                    boolean right = SwingUtilities.isRightMouseButton(e);
                    player.getInputHandler().onMouseDown(sx, sy, right);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    Player player = context.getPlayer();
                    if (player == null) return;
                    int sx = toStageX(e.getX());
                    int sy = toStageY(e.getY());
                    boolean right = SwingUtilities.isRightMouseButton(e);
                    player.getInputHandler().onMouseUp(sx, sy, right);
                }
            });

            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    Player player = context.getPlayer();
                    if (player == null) return;
                    player.getInputHandler().onMouseMove(toStageX(e.getX()), toStageY(e.getY()));
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    Player player = context.getPlayer();
                    if (player == null) return;
                    player.getInputHandler().onMouseMove(toStageX(e.getX()), toStageY(e.getY()));
                }
            });

            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    Player player = context.getPlayer();
                    if (player == null) return;
                    if (e.isControlDown() || e.isMetaDown()) return;

                    int directorCode = DirectorKeyCodes.fromJavaKeyCode(e.getKeyCode());
                    String ch = keyEventToString(e);
                    player.getInputHandler().onKeyDown(directorCode, ch,
                            e.isShiftDown(), e.isControlDown(), e.isAltDown());
                }

                @Override
                public void keyReleased(KeyEvent e) {
                    Player player = context.getPlayer();
                    if (player == null) return;
                    if (e.isControlDown() || e.isMetaDown()) return;

                    int directorCode = DirectorKeyCodes.fromJavaKeyCode(e.getKeyCode());
                    String ch = keyEventToString(e);
                    player.getInputHandler().onKeyUp(directorCode, ch,
                            e.isShiftDown(), e.isControlDown(), e.isAltDown());
                }
            });
        }

        private static String keyEventToString(KeyEvent e) {
            char c = e.getKeyChar();
            if (c == KeyEvent.CHAR_UNDEFINED || c == 0xFFFF) return "";
            if (c == '\n' || c == '\r') return "\r";
            if (c == '\t') return "\t";
            if (c == '\b') return "\b";
            return String.valueOf(c);
        }

        // --- Rendering ---

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;

            g2d.setColor(VIEWPORT_COLOR);
            g2d.fillRect(0, 0, getWidth(), getHeight());

            Player player = context.getPlayer();
            if (player == null) {
                paintNoMovie(g2d);
                return;
            }

            int canvasX = (getWidth() - stageWidth) / 2;
            int canvasY = (getHeight() - stageHeight) / 2;

            FrameSnapshot snapshot = player.getFrameSnapshot();

            // Draw canvas border
            g2d.setColor(CANVAS_BORDER_COLOR);
            g2d.drawRect(canvasX - 1, canvasY - 1, stageWidth + 1, stageHeight + 1);

            // Clip to canvas area
            Shape oldClip = g2d.getClip();
            g2d.clipRect(canvasX, canvasY, stageWidth, stageHeight);
            g2d.translate(canvasX, canvasY);

            // Render frame
            if (snapshot != null) {
                Bitmap frameBitmap = snapshot.renderFrame();
                if (frameBitmap != null) {
                    int[] pixels = frameBitmap.getPixels();
                    BufferedImage frameImage = new BufferedImage(stageWidth, stageHeight, BufferedImage.TYPE_INT_ARGB);
                    int[] destPixels = ((DataBufferInt) frameImage.getRaster().getDataBuffer()).getData();
                    System.arraycopy(pixels, 0, destPixels, 0, Math.min(pixels.length, destPixels.length));
                    g2d.drawImage(frameImage, 0, 0, null);
                }

                // Debug info
                g2d.setColor(new Color(0, 0, 0, 128));
                g2d.setFont(new Font("Monospaced", Font.PLAIN, 12));
                g2d.drawString(snapshot.debugInfo(), 5, stageHeight - 5);
            }

            g2d.translate(-canvasX, -canvasY);
            g2d.setClip(oldClip);
        }

        private void paintNoMovie(Graphics2D g) {
            int canvasX = (getWidth() - stageWidth) / 2;
            int canvasY = (getHeight() - stageHeight) / 2;

            g.setColor(CANVAS_BORDER_COLOR);
            g.drawRect(canvasX - 1, canvasY - 1, stageWidth + 1, stageHeight + 1);

            g.setColor(Color.LIGHT_GRAY);
            g.fillRect(canvasX, canvasY, stageWidth, stageHeight);

            g.setColor(Color.DARK_GRAY);
            g.setFont(new Font("SansSerif", Font.BOLD, 16));
            String msg = "No movie loaded";
            FontMetrics fm = g.getFontMetrics();
            int textX = canvasX + (stageWidth - fm.stringWidth(msg)) / 2;
            int textY = canvasY + (stageHeight + fm.getAscent()) / 2;
            g.drawString(msg, textX, textY);
        }
    }
}
