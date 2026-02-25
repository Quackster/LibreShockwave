package com.libreshockwave.player.wasm.canvas;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.render.FrameSnapshot;
import com.libreshockwave.player.render.RenderSprite;

import org.teavm.jso.canvas.CanvasImageSource;
import org.teavm.jso.canvas.CanvasRenderingContext2D;
import org.teavm.jso.canvas.ImageData;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.browser.Window;
import org.teavm.jso.typedarrays.Uint8ClampedArray;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * HTML5 Canvas 2D renderer for the Director stage.
 * Port of StagePanel's rendering logic to Canvas API via TeaVM JSO.
 */
public class CanvasStageRenderer {

    private final HTMLCanvasElement canvas;
    private final CanvasRenderingContext2D ctx;
    private final Player player;

    // Cached offscreen canvases for bitmap sprites (keyed by cast member ID)
    private final Map<Integer, HTMLCanvasElement> bitmapCache = new HashMap<>();

    public CanvasStageRenderer(HTMLCanvasElement canvas, Player player) {
        this.canvas = canvas;
        this.ctx = (CanvasRenderingContext2D) canvas.getContext("2d");
        this.player = player;
    }

    /**
     * Render the current frame to the canvas.
     */
    public void render() {
        FrameSnapshot snapshot = player.getFrameSnapshot();

        int w = canvas.getWidth();
        int h = canvas.getHeight();

        // Clear with background color
        String bgColor = colorToCSS(snapshot.backgroundColor());
        ctx.setFillStyle(bgColor);
        ctx.fillRect(0, 0, w, h);

        // Draw all sprites
        for (RenderSprite sprite : snapshot.sprites()) {
            drawSprite(sprite);
        }

        // Debug info
        ctx.setFillStyle("rgba(0,0,0,0.5)");
        ctx.setFont("12px monospace");
        ctx.fillText(snapshot.debugInfo(), 5, h - 5);
    }

    private void drawSprite(RenderSprite sprite) {
        if (!sprite.isVisible()) return;

        int x = sprite.getX();
        int y = sprite.getY();
        int width = sprite.getWidth();
        int height = sprite.getHeight();

        switch (sprite.getType()) {
            case BITMAP -> drawBitmap(sprite, x, y, width, height);
            case SHAPE -> drawShape(sprite, x, y, width, height);
            case TEXT, BUTTON -> drawPlaceholder(x, y, width, height, sprite.getChannel(), "txt");
            default -> drawPlaceholder(x, y, width, height, sprite.getChannel(), "?");
        }
    }

    private void drawBitmap(RenderSprite sprite, int x, int y, int width, int height) {
        CastMemberChunk member = sprite.getCastMember();
        if (member == null) {
            drawPlaceholder(x, y, width, height, sprite.getChannel(), "bmp");
            return;
        }

        HTMLCanvasElement cached = getCachedBitmap(member);
        if (cached != null) {
            if (width > 0 && height > 0) {
                ctx.drawImage((CanvasImageSource) cached, x, y, width, height);
            } else {
                ctx.drawImage((CanvasImageSource) cached, x, y);
            }
        } else {
            drawPlaceholder(x, y, width, height, sprite.getChannel(), "bmp");
        }
    }

    private void drawShape(RenderSprite sprite, int x, int y, int width, int height) {
        int fc = sprite.getForeColor();
        ctx.setFillStyle(colorToCSS(fc, fc, fc));
        ctx.fillRect(x, y, width > 0 ? width : 50, height > 0 ? height : 50);
    }

    private void drawPlaceholder(int x, int y, int width, int height, int channel, String label) {
        int w = width > 0 ? width : 50;
        int h = height > 0 ? height : 50;

        ctx.setFillStyle("rgba(200,200,200,0.5)");
        ctx.fillRect(x, y, w, h);
        ctx.setStrokeStyle("gray");
        ctx.strokeRect(x, y, w, h);

        ctx.setFillStyle("gray");
        ctx.setFont("10px sans-serif");
        ctx.fillText(label + channel, x + 2, y + 12);
    }

    /**
     * Get or create a cached offscreen canvas for a bitmap cast member.
     * Converts ARGB int[] pixels to Canvas ImageData (RGBA bytes).
     */
    private HTMLCanvasElement getCachedBitmap(CastMemberChunk member) {
        int id = member.id();
        if (bitmapCache.containsKey(id)) {
            return bitmapCache.get(id);
        }

        if (player.getFile() == null) {
            return null;
        }

        Optional<Bitmap> bitmap = player.getFile().decodeBitmap(member);
        if (bitmap.isPresent()) {
            HTMLCanvasElement offscreen = createBitmapCanvas(bitmap.get());
            bitmapCache.put(id, offscreen);
            return offscreen;
        }

        bitmapCache.put(id, null);
        return null;
    }

    /**
     * Create an offscreen canvas from a Bitmap's ARGB pixel data.
     * Converts ARGB (0xAARRGGBB) to RGBA byte order for Canvas ImageData.
     */
    private HTMLCanvasElement createBitmapCanvas(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] argbPixels = bitmap.getPixels();

        HTMLDocument doc = Window.current().getDocument();
        HTMLCanvasElement offscreen = (HTMLCanvasElement) doc.createElement("canvas");
        offscreen.setWidth(w);
        offscreen.setHeight(h);

        CanvasRenderingContext2D offCtx = (CanvasRenderingContext2D) offscreen.getContext("2d");
        ImageData imageData = offCtx.createImageData(w, h);
        Uint8ClampedArray data = imageData.getData();

        // Convert ARGB int[] to RGBA byte[]
        for (int i = 0; i < argbPixels.length; i++) {
            int argb = argbPixels[i];
            int offset = i * 4;
            data.set(offset,     (argb >> 16) & 0xFF); // R
            data.set(offset + 1, (argb >> 8) & 0xFF);  // G
            data.set(offset + 2, argb & 0xFF);          // B
            data.set(offset + 3, (argb >> 24) & 0xFF);  // A
        }

        offCtx.putImageData(imageData, 0, 0);
        return offscreen;
    }

    private static String colorToCSS(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return "rgb(" + r + "," + g + "," + b + ")";
    }

    private static String colorToCSS(int r, int g, int b) {
        return "rgb(" + (r & 0xFF) + "," + (g & 0xFF) + "," + (b & 0xFF) + ")";
    }
}
