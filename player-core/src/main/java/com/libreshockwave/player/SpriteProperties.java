package com.libreshockwave.player;

import com.libreshockwave.player.render.SpriteRegistry;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.builtin.SpritePropertyProvider;

/**
 * Provides sprite property access for Lingo scripts.
 * Implements sprite property expressions like:
 * - the locH of sprite 1
 * - the visible of sprite 5
 * - set the locV of sprite 3 to 100
 */
public class SpriteProperties implements SpritePropertyProvider {

    private final SpriteRegistry registry;

    public SpriteProperties(SpriteRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Datum getSpriteProp(int spriteNum, String propName) {
        SpriteState sprite = registry.get(spriteNum);
        if (sprite == null) {
            return Datum.VOID;
        }

        String prop = propName.toLowerCase();
        return switch (prop) {
            case "loch" -> Datum.of(sprite.getLocH());
            case "locv" -> Datum.of(sprite.getLocV());
            case "loc" -> new Datum.Point(sprite.getLocH(), sprite.getLocV());
            case "width" -> Datum.of(sprite.getWidth());
            case "height" -> Datum.of(sprite.getHeight());
            case "visible" -> Datum.of(sprite.isVisible() ? 1 : 0);
            case "left" -> Datum.of(sprite.getLocH());
            case "top" -> Datum.of(sprite.getLocV());
            case "right" -> Datum.of(sprite.getLocH() + sprite.getWidth());
            case "bottom" -> Datum.of(sprite.getLocV() + sprite.getHeight());
            case "rect" -> new Datum.Rect(
                sprite.getLocH(),
                sprite.getLocV(),
                sprite.getLocH() + sprite.getWidth(),
                sprite.getLocV() + sprite.getHeight()
            );
            case "spritenum" -> Datum.of(spriteNum);
            // Return initial data properties
            case "castnum", "membernum" -> {
                var data = sprite.getInitialData();
                yield data != null ? Datum.of(data.castMember()) : Datum.VOID;
            }
            case "ink" -> {
                var data = sprite.getInitialData();
                yield data != null ? Datum.of(data.ink()) : Datum.of(0);
            }
            case "blend" -> Datum.of(100);
            // Placeholder properties
            case "puppet" -> Datum.of(0);
            case "stretch" -> Datum.of(0);
            case "trails" -> Datum.of(0);
            case "moveable", "moveablesprite" -> Datum.of(0);
            case "editabletext" -> Datum.of(0);
            default -> {
                System.err.println("[SpriteProperties] Unknown sprite property: " + propName);
                yield Datum.VOID;
            }
        };
    }

    @Override
    public boolean setSpriteProp(int spriteNum, String propName, Datum value) {
        SpriteState sprite = registry.get(spriteNum);
        if (sprite == null) {
            System.err.println("[SpriteProperties] Sprite " + spriteNum + " not found");
            return false;
        }

        String prop = propName.toLowerCase();
        switch (prop) {
            case "loch" -> {
                sprite.setLocH(value.toInt());
                return true;
            }
            case "locv" -> {
                sprite.setLocV(value.toInt());
                return true;
            }
            case "loc" -> {
                if (value instanceof Datum.Point p) {
                    sprite.setLocH(p.x());
                    sprite.setLocV(p.y());
                    return true;
                }
                return false;
            }
            case "visible" -> {
                sprite.setVisible(value.isTruthy());
                return true;
            }
            // Read-only properties
            case "width", "height", "left", "top", "right", "bottom", "rect",
                 "spritenum", "castnum", "membernum" -> {
                System.err.println("[SpriteProperties] Cannot set read-only property: " + propName);
                return false;
            }
            default -> {
                System.err.println("[SpriteProperties] Unknown sprite property: " + propName);
                return false;
            }
        }
    }
}
