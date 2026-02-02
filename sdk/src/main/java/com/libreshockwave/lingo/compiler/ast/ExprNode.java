package com.libreshockwave.lingo.compiler.ast;

import java.util.List;

/**
 * Sealed interface for all expression AST nodes.
 * Expressions evaluate to a value and can be used in assignments,
 * as arguments to functions, or as operands to operators.
 */
public sealed interface ExprNode extends Node {

    /**
     * Literal value: integer, float, string, symbol, void.
     */
    record Literal(LiteralType type, Object value, Node.SourceLocation loc) implements ExprNode {
        public enum LiteralType {
            INTEGER, FLOAT, STRING, SYMBOL, VOID, TRUE, FALSE
        }

        public static Literal integer(int value) {
            return new Literal(LiteralType.INTEGER, value, null);
        }

        public static Literal floatVal(double value) {
            return new Literal(LiteralType.FLOAT, value, null);
        }

        public static Literal string(String value) {
            return new Literal(LiteralType.STRING, value, null);
        }

        public static Literal symbol(String name) {
            return new Literal(LiteralType.SYMBOL, name, null);
        }

        public static Literal voidVal() {
            return new Literal(LiteralType.VOID, null, null);
        }

        public static Literal trueVal() {
            return new Literal(LiteralType.TRUE, true, null);
        }

        public static Literal falseVal() {
            return new Literal(LiteralType.FALSE, false, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitLiteral(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Variable reference: local, global, param, or property.
     */
    record Variable(String name, VariableScope scope, Node.SourceLocation loc) implements ExprNode {
        public enum VariableScope {
            LOCAL, GLOBAL, PARAM, PROPERTY, AUTO
        }

        public static Variable local(String name) {
            return new Variable(name, VariableScope.LOCAL, null);
        }

        public static Variable global(String name) {
            return new Variable(name, VariableScope.GLOBAL, null);
        }

        public static Variable auto(String name) {
            return new Variable(name, VariableScope.AUTO, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitVariable(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Binary operator expression: a + b, a and b, etc.
     */
    record BinaryOp(BinaryOpType op, ExprNode left, ExprNode right, Node.SourceLocation loc) implements ExprNode {
        public enum BinaryOpType {
            // Arithmetic
            ADD, SUB, MUL, DIV, MOD,
            // Comparison
            EQ, NE, LT, LE, GT, GE,
            // Logical
            AND, OR,
            // String
            CONCAT, CONCAT_SPACE, CONTAINS, STARTS
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitBinaryOp(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Unary operator expression: not x, -x.
     */
    record UnaryOp(UnaryOpType op, ExprNode operand, Node.SourceLocation loc) implements ExprNode {
        public enum UnaryOpType {
            NOT, NEGATE
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitUnaryOp(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Function call: foo(a, b, c) or foo a, b, c.
     */
    record Call(String name, List<ExprNode> args, boolean isStatement, Node.SourceLocation loc) implements ExprNode {
        public static Call function(String name, List<ExprNode> args) {
            return new Call(name, args, false, null);
        }

        public static Call command(String name, List<ExprNode> args) {
            return new Call(name, args, true, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitCall(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Method call: obj.method(args) or call(#method, obj, args).
     */
    record MethodCall(ExprNode target, String methodName, List<ExprNode> args, Node.SourceLocation loc) implements ExprNode {
        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitMethodCall(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Member reference: member(x) or member x of castLib y.
     */
    record MemberRef(ExprNode memberExpr, ExprNode castLib, Node.SourceLocation loc) implements ExprNode {
        public static MemberRef of(ExprNode memberExpr) {
            return new MemberRef(memberExpr, null, null);
        }

        public static MemberRef of(ExprNode memberExpr, ExprNode castLib) {
            return new MemberRef(memberExpr, castLib, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitMemberRef(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Sprite reference: sprite(x) or sprite x.
     */
    record SpriteRef(ExprNode spriteNum, Node.SourceLocation loc) implements ExprNode {
        public static SpriteRef of(ExprNode spriteNum) {
            return new SpriteRef(spriteNum, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitSpriteRef(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * The-property: the mouseH, the time, the frame.
     */
    record TheProperty(String property, Node.SourceLocation loc) implements ExprNode {
        public static TheProperty of(String property) {
            return new TheProperty(property, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitTheProperty(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Property-of expression: the loc of sprite 1, the width of member "foo".
     */
    record PropertyOf(String property, ExprNode target, Node.SourceLocation loc) implements ExprNode {
        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitPropertyOf(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Chunk expression: char 1 of x, word 2 to 5 of y, line 1 of field "text".
     */
    record ChunkExpr(ChunkType chunkType, ExprNode first, ExprNode last, ExprNode target, Node.SourceLocation loc) implements ExprNode {
        public enum ChunkType {
            CHAR, WORD, ITEM, LINE
        }

        public static ChunkExpr single(ChunkType type, ExprNode index, ExprNode target) {
            return new ChunkExpr(type, index, null, target, null);
        }

        public static ChunkExpr range(ChunkType type, ExprNode first, ExprNode last, ExprNode target) {
            return new ChunkExpr(type, first, last, target, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitChunkExpr(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * List literal: [1, 2, 3].
     */
    record ListLiteral(List<ExprNode> elements, Node.SourceLocation loc) implements ExprNode {
        public static ListLiteral of(List<ExprNode> elements) {
            return new ListLiteral(elements, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitListLiteral(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Property list literal: [#a: 1, #b: 2] or [a: 1, b: 2].
     */
    record PropListLiteral(List<PropEntry> entries, Node.SourceLocation loc) implements ExprNode {
        public record PropEntry(ExprNode key, ExprNode value) {}

        public static PropListLiteral of(List<PropEntry> entries) {
            return new PropListLiteral(entries, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitPropListLiteral(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * New object: new(script "Foo", args) or script("Foo").new(args).
     */
    record NewObject(ExprNode scriptRef, List<ExprNode> args, Node.SourceLocation loc) implements ExprNode {
        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitNewObject(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Menu reference: menu(x).
     */
    record Menu(ExprNode menuRef, Node.SourceLocation loc) implements ExprNode {
        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitMenu(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Menu item reference: menuItem x of menu y.
     */
    record MenuItem(ExprNode itemRef, ExprNode menuRef, Node.SourceLocation loc) implements ExprNode {
        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitMenuItem(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Sound reference: sound(x).
     */
    record Sound(ExprNode soundRef, Node.SourceLocation loc) implements ExprNode {
        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitSound(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Cast reference: castLib(x).
     */
    record Cast(ExprNode castRef, Node.SourceLocation loc) implements ExprNode {
        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitCast(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Field reference (older Director versions): field(x).
     */
    record Field(ExprNode fieldRef, Node.SourceLocation loc) implements ExprNode {
        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitField(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }
}
