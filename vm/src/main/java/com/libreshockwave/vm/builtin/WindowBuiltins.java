package com.libreshockwave.vm.builtin;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Window and stage builtin functions for Lingo.
 *
 * Provides:
 * - moveToFront(window) - bring window to front
 * - moveToBack(window) - send window to back
 * - puppetTempo(rate) - set the tempo programmatically
 * - createWindow(id, template) - create a window with a bitmap buffer
 * - getWindow(id) - get a window reference
 * - removeWindow(id) - remove a window
 */
public final class WindowBuiltins {

    private WindowBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("movetofront", WindowBuiltins::moveToFront);
        builtins.put("movetoback", WindowBuiltins::moveToBack);
        builtins.put("puppettempo", WindowBuiltins::puppetTempo);
        builtins.put("puppetsprite", WindowBuiltins::puppetSprite);
        // NOTE: createWindow, getWindow, removeWindow are NOT registered as builtins.
        // These are Lingo handlers (in "Window API" movie script) that perform complex
        // window creation: layout parsing, sprite allocation, bitmap member creation.
        // Registering them as builtins would shadow the Lingo handlers and break
        // the window system (error dialogs, loading bars, etc.)
        builtins.put("cursor", (vm, args) -> Datum.VOID);  // No-op stub
        builtins.put("pauseupdate", (vm, args) -> Datum.VOID);  // No-op stub
        builtins.put("updatestage", (vm, args) -> Datum.VOID);  // No-op stub
    }

    /**
     * Dispatch method calls on WindowRef objects.
     * Supports: resizeTo, center, getElement.
     *
     * The getElement("drag").getProperty(#buffer).image chain is used by the Loading Bar
     * to get a bitmap buffer for drawing.
     */
    public static Datum dispatchMethod(Datum.WindowRef windowRef, String methodName, List<Datum> args) {
        String method = methodName.toLowerCase();
        WindowProvider provider = WindowProvider.getProvider();
        if (provider == null) {
            return Datum.VOID;
        }

        return switch (method) {
            case "resizeto" -> {
                if (args.size() >= 2) {
                    provider.resizeWindow(windowRef.name(), args.get(0).toInt(), args.get(1).toInt());
                }
                yield Datum.VOID;
            }
            case "center" -> {
                provider.centerWindow(windowRef.name());
                yield Datum.VOID;
            }
            case "getelement" -> {
                // Returns a PropList wrapping the window's bitmap buffer.
                // Chain: getElement("drag").getProperty(#buffer).image → ImageRef
                Bitmap bmp = provider.getWindowBitmap(windowRef.name());
                if (bmp != null) {
                    // Build nested structure: [#buffer: ImageRef(bitmap)]
                    // So .getProperty(#buffer) returns ImageRef, and .image returns itself
                    Map<String, Datum> elementProps = new LinkedHashMap<>();
                    elementProps.put("buffer", new Datum.ImageRef(bmp));
                    yield new Datum.PropList(elementProps);
                }
                yield Datum.VOID;
            }
            default -> Datum.VOID;
        };
    }

    /**
     * moveToFront(window)
     * Brings the specified window to the front of the window stack.
     * Commonly used with "the stage".
     */
    private static Datum moveToFront(LingoVM vm, List<Datum> args) {
        // No-op - windowing is handled externally
        return Datum.VOID;
    }

    /**
     * moveToBack(window)
     * Sends the specified window to the back of the window stack.
     */
    private static Datum moveToBack(LingoVM vm, List<Datum> args) {
        // No-op - windowing is handled externally
        return Datum.VOID;
    }

    /**
     * puppetSprite(spriteNum, enabled)
     * Takes programmatic control of a sprite channel.
     * When puppet is TRUE, the sprite is controlled by script instead of the score.
     */
    private static Datum puppetSprite(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) {
            return Datum.VOID;
        }

        int spriteNum = args.get(0).toInt();
        boolean enabled = args.get(1).isTruthy();

        SpritePropertyProvider provider = SpritePropertyProvider.getProvider();
        if (provider != null) {
            provider.setSpriteProp(spriteNum, "puppet", Datum.of(enabled ? 1 : 0));
        }

        return Datum.VOID;
    }

    /**
     * createWindow(id, template)
     * Creates a new window with a bitmap buffer.
     * Used by Lingo scripts (e.g., Loading Bar) to create off-screen render targets.
     */
    private static Datum createWindow(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) {
            return Datum.VOID;
        }

        String windowId = args.get(0).toStr();
        String template = args.get(1).toStr();

        WindowProvider provider = WindowProvider.getProvider();
        if (provider != null) {
            provider.createWindow(windowId, template);
        }

        return Datum.VOID;
    }

    /**
     * getWindow(id)
     * Returns a reference to a window by ID.
     */
    private static Datum getWindow(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.VOID;
        }

        String windowId = args.get(0).toStr();

        WindowProvider provider = WindowProvider.getProvider();
        if (provider != null) {
            return provider.getWindow(windowId);
        }

        return Datum.VOID;
    }

    /**
     * removeWindow(id)
     * Removes a window and its bitmap buffer.
     */
    private static Datum removeWindow(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.VOID;
        }

        String windowId = args.get(0).toStr();

        WindowProvider provider = WindowProvider.getProvider();
        if (provider != null) {
            provider.removeWindow(windowId);
        }

        return Datum.VOID;
    }

    /**
     * puppetTempo(rate)
     * Sets the tempo of the movie programmatically.
     * The tempo remains in effect until another puppetTempo call or until
     * the movie encounters a tempo setting in the score.
     * Set to 0 to return to score tempo.
     */
    private static Datum puppetTempo(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.VOID;
        }

        int tempo = args.get(0).toInt();

        // Set the puppetTempo property which overrides the score tempo
        MoviePropertyProvider provider = MoviePropertyProvider.getProvider();
        if (provider != null) {
            provider.setMovieProp("puppetTempo", Datum.of(tempo));
        }

        return Datum.VOID;
    }
}
