package com.libreshockwave.player;

import com.libreshockwave.player.input.DirectorKeyCodes;
import com.libreshockwave.player.render.output.AwtFrameRenderer;
import com.libreshockwave.player.render.pipeline.FrameSnapshot;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * Swing panel that renders the Director stage using the player-core rendering API.
 * The stage canvas maintains a fixed size and is centered within the panel.
 * Resizing the window does not affect the stage dimensions.
 *
 * Handles mouse and keyboard input, translating AWT events to Director events
 * dispatched through the Player's input system.
 *
 * Ink processing (matte, background transparent, etc.) is handled by the Player's
 * BitmapCache and baked into each RenderSprite before it reaches this panel.
 */
public class StagePanel extends JPanel {

    private static final Color VIEWPORT_COLOR = new Color(48, 48, 48);
    private static final Color CANVAS_BORDER_COLOR = new Color(80, 80, 80);

    private Player player;

    // Fixed stage dimensions (set from movie)
    private int stageWidth = 640;
    private int stageHeight = 480;

    public StagePanel() {
        setDoubleBuffered(true);
        setBackground(VIEWPORT_COLOR);
        setFocusable(true);
        setupInputListeners();
    }

    public void setPlayer(Player player) {
        this.player = player;
        repaint();
    }

    /**
     * Set the fixed stage canvas size.
     * This determines the actual stage area, independent of panel size.
     */
    public void setStageSize(int width, int height) {
        this.stageWidth = width > 0 ? width : 640;
        this.stageHeight = height > 0 ? height : 480;
        repaint();
    }

    public int getStageWidth() {
        return stageWidth;
    }

    public int getStageHeight() {
        return stageHeight;
    }

    // --- Coordinate translation ---

    /**
     * Convert a panel X coordinate to stage X coordinate.
     * The stage canvas is centered within the panel.
     */
    private int toStageX(int panelX) {
        int canvasX = (getWidth() - stageWidth) / 2;
        return panelX - canvasX;
    }

    /**
     * Convert a panel Y coordinate to stage Y coordinate.
     */
    private int toStageY(int panelY) {
        int canvasY = (getHeight() - stageHeight) / 2;
        return panelY - canvasY;
    }

    /**
     * Check if a panel coordinate is within the stage canvas area.
     */
    private boolean isOnStage(int panelX, int panelY) {
        int sx = toStageX(panelX);
        int sy = toStageY(panelY);
        return sx >= 0 && sx < stageWidth && sy >= 0 && sy < stageHeight;
    }

    // --- Input listeners ---

    private void setupInputListeners() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (player == null) return;
                int sx = toStageX(e.getX());
                int sy = toStageY(e.getY());
                boolean right = SwingUtilities.isRightMouseButton(e);
                player.onMouseDown(sx, sy, right);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (player == null) return;
                int sx = toStageX(e.getX());
                int sy = toStageY(e.getY());
                boolean right = SwingUtilities.isRightMouseButton(e);
                player.onMouseUp(sx, sy, right);
            }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (player == null) return;
                player.onMouseMove(toStageX(e.getX()), toStageY(e.getY()));
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (player == null) return;
                // During drag, update mouse position (Director tracks mouse continuously)
                player.onMouseMove(toStageX(e.getX()), toStageY(e.getY()));
            }
        });

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (player == null) return;
                // Don't consume keys that PlayerFrame needs (Ctrl+O, Ctrl+R, etc.)
                if (e.isControlDown() || e.isMetaDown()) return;

                int directorCode = DirectorKeyCodes.fromJavaKeyCode(e.getKeyCode());
                String ch = keyEventToString(e);
                player.onKeyDown(directorCode, ch,
                        e.isShiftDown(), e.isControlDown(), e.isAltDown());
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (player == null) return;
                if (e.isControlDown() || e.isMetaDown()) return;

                int directorCode = DirectorKeyCodes.fromJavaKeyCode(e.getKeyCode());
                String ch = keyEventToString(e);
                player.onKeyUp(directorCode, ch,
                        e.isShiftDown(), e.isControlDown(), e.isAltDown());
            }
        });
    }

    /**
     * Convert a Java KeyEvent to the character string Director uses for "the key".
     */
    private static String keyEventToString(KeyEvent e) {
        char c = e.getKeyChar();
        if (c == KeyEvent.CHAR_UNDEFINED || c == 0xFFFF) {
            // Non-character keys (arrows, function keys, etc.)
            return "";
        }
        if (c == '\n' || c == '\r') return "\r";  // Director uses \r for Return
        if (c == '\t') return "\t";
        if (c == '\b') return "\b";
        return String.valueOf(c);
    }

    // --- Rendering ---

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Graphics2D g2d = (Graphics2D) g;

        // Fill viewport area with dark color
        g2d.setColor(VIEWPORT_COLOR);
        g2d.fillRect(0, 0, getWidth(), getHeight());

        if (player == null) {
            paintNoMovie(g2d);
            return;
        }

        // Calculate centered position for the stage canvas
        int canvasX = (getWidth() - stageWidth) / 2;
        int canvasY = (getHeight() - stageHeight) / 2;

        // Get the frame snapshot from player-core (bitmaps are already ink-processed)
        FrameSnapshot snapshot = player.getFrameSnapshot();

        // Draw canvas border (subtle shadow effect)
        g2d.setColor(CANVAS_BORDER_COLOR);
        g2d.drawRect(canvasX - 1, canvasY - 1, stageWidth + 1, stageHeight + 1);

        // Clip to canvas area
        Shape oldClip = g2d.getClip();
        g2d.clipRect(canvasX, canvasY, stageWidth, stageHeight);

        // Translate to canvas origin
        g2d.translate(canvasX, canvasY);

        // Render frame using shared renderer
        AwtFrameRenderer.renderFrame(g2d, snapshot, stageWidth, stageHeight);

        // Draw debug info
        drawDebugInfo(g2d, snapshot);

        // Restore transform and clip
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

    /**
     * Clear the bitmap cache, forcing bitmaps to be re-decoded on next repaint.
     */
    public void clearBitmapCache() {
        if (player != null) {
            player.getBitmapCache().clear();
        }
    }

    private void drawDebugInfo(Graphics2D g, FrameSnapshot snapshot) {
        g.setColor(new Color(0, 0, 0, 128));
        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g.drawString(snapshot.debugInfo(), 5, stageHeight - 5);
    }
}
