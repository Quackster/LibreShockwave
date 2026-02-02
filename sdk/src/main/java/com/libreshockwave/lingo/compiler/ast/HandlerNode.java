package com.libreshockwave.lingo.compiler.ast;

import java.util.List;

/**
 * Handler (function/method) definition node.
 * A handler is the Lingo equivalent of a function.
 */
public record HandlerNode(
    String name,
    List<String> parameters,
    StmtNode.Block body,
    boolean isFactory,
    Node.SourceLocation loc
) implements Node {

    /**
     * Create a regular handler.
     */
    public static HandlerNode of(String name, List<String> parameters, StmtNode.Block body) {
        return new HandlerNode(name, parameters, body, false, null);
    }

    /**
     * Create a factory handler (parent script constructor).
     */
    public static HandlerNode factory(String name, List<String> parameters, StmtNode.Block body) {
        return new HandlerNode(name, parameters, body, true, null);
    }

    @Override
    public <T> T accept(NodeVisitor<T> visitor) {
        return visitor.visitHandler(this);
    }

    @Override
    public SourceLocation location() {
        return loc;
    }

    /**
     * Check if this is a built-in event handler.
     */
    public boolean isEventHandler() {
        String lower = name.toLowerCase();
        return lower.equals("prepareframe") ||
               lower.equals("enterframe") ||
               lower.equals("exitframe") ||
               lower.equals("preparemovie") ||
               lower.equals("startmovie") ||
               lower.equals("stopmovie") ||
               lower.equals("idle") ||
               lower.equals("mousedown") ||
               lower.equals("mouseup") ||
               lower.equals("keydown") ||
               lower.equals("keyup") ||
               lower.equals("beginsprite") ||
               lower.equals("endsprite") ||
               lower.equals("mouseenter") ||
               lower.equals("mouseleave") ||
               lower.equals("mousewithin") ||
               lower.equals("mouseupoutside") ||
               lower.equals("rightmousedown") ||
               lower.equals("rightmouseup");
    }

    /**
     * Check if this is a "new" handler (factory/constructor).
     */
    public boolean isNewHandler() {
        return name.equalsIgnoreCase("new");
    }
}
