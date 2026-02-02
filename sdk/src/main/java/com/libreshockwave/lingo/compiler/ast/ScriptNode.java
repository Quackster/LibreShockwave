package com.libreshockwave.lingo.compiler.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Root node representing a complete Lingo script.
 * A script contains property declarations, global declarations, and handlers.
 */
public record ScriptNode(
    List<String> properties,
    List<String> globals,
    List<HandlerNode> handlers,
    ScriptType scriptType,
    Node.SourceLocation loc
) implements Node {

    /**
     * Script type (matches ScriptChunk.ScriptType).
     */
    public enum ScriptType {
        MOVIE,      // Movie script
        BEHAVIOR,   // Sprite behavior
        PARENT,     // Parent script (class)
        SCORE       // Score/frame script
    }

    /**
     * Create a movie script.
     */
    public static ScriptNode movieScript(List<String> globals, List<HandlerNode> handlers) {
        return new ScriptNode(List.of(), globals, handlers, ScriptType.MOVIE, null);
    }

    /**
     * Create a behavior script.
     */
    public static ScriptNode behavior(List<String> properties, List<String> globals, List<HandlerNode> handlers) {
        return new ScriptNode(properties, globals, handlers, ScriptType.BEHAVIOR, null);
    }

    /**
     * Create a parent script (class).
     */
    public static ScriptNode parentScript(List<String> properties, List<String> globals, List<HandlerNode> handlers) {
        return new ScriptNode(properties, globals, handlers, ScriptType.PARENT, null);
    }

    /**
     * Create a score/frame script.
     */
    public static ScriptNode scoreScript(List<String> globals, List<HandlerNode> handlers) {
        return new ScriptNode(List.of(), globals, handlers, ScriptType.SCORE, null);
    }

    @Override
    public <T> T accept(NodeVisitor<T> visitor) {
        return visitor.visitScript(this);
    }

    @Override
    public SourceLocation location() {
        return loc;
    }

    /**
     * Find a handler by name (case-insensitive).
     */
    public HandlerNode findHandler(String name) {
        for (HandlerNode handler : handlers) {
            if (handler.name().equalsIgnoreCase(name)) {
                return handler;
            }
        }
        return null;
    }

    /**
     * Check if this script declares a specific property.
     */
    public boolean hasProperty(String name) {
        for (String prop : properties) {
            if (prop.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if this script declares a specific global.
     */
    public boolean hasGlobal(String name) {
        for (String global : globals) {
            if (global.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builder for constructing ScriptNode incrementally.
     */
    public static class Builder {
        private final List<String> properties = new ArrayList<>();
        private final List<String> globals = new ArrayList<>();
        private final List<HandlerNode> handlers = new ArrayList<>();
        private ScriptType scriptType = ScriptType.MOVIE;

        public Builder addProperty(String name) {
            properties.add(name);
            return this;
        }

        public Builder addProperties(List<String> names) {
            properties.addAll(names);
            return this;
        }

        public Builder addGlobal(String name) {
            globals.add(name);
            return this;
        }

        public Builder addGlobals(List<String> names) {
            globals.addAll(names);
            return this;
        }

        public Builder addHandler(HandlerNode handler) {
            handlers.add(handler);
            return this;
        }

        public Builder setScriptType(ScriptType type) {
            this.scriptType = type;
            return this;
        }

        public ScriptNode build() {
            return new ScriptNode(
                List.copyOf(properties),
                List.copyOf(globals),
                List.copyOf(handlers),
                scriptType,
                null
            );
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
