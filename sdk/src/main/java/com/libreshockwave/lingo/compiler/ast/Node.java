package com.libreshockwave.lingo.compiler.ast;

/**
 * Base sealed interface for all AST nodes in the Lingo compiler.
 * The AST represents parsed Lingo source code in a structured form
 * that can be traversed for code generation.
 */
public sealed interface Node permits ExprNode, StmtNode, HandlerNode, ScriptNode {

    /**
     * Accept a visitor for AST traversal.
     */
    <T> T accept(NodeVisitor<T> visitor);

    /**
     * Get the source location information if available.
     */
    default SourceLocation location() {
        return null;
    }

    /**
     * Source location in the original text.
     */
    record SourceLocation(int line, int column, int startOffset, int endOffset) {
        public static SourceLocation of(int line, int column) {
            return new SourceLocation(line, column, -1, -1);
        }

        public static SourceLocation ofOffset(int startOffset, int endOffset) {
            return new SourceLocation(-1, -1, startOffset, endOffset);
        }
    }
}
