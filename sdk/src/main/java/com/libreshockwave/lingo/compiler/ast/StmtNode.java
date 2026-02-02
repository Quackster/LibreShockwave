package com.libreshockwave.lingo.compiler.ast;

import java.util.List;

/**
 * Sealed interface for all statement AST nodes.
 * Statements are executed for their side effects and don't return values.
 */
public sealed interface StmtNode extends Node {

    /**
     * Expression statement: a function call used as a statement.
     */
    record ExprStmt(ExprNode expr, Node.SourceLocation loc) implements StmtNode {
        public static ExprStmt of(ExprNode expr) {
            return new ExprStmt(expr, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitExprStmt(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Assignment: x = value or set x to value.
     */
    record Assignment(ExprNode target, ExprNode value, Node.SourceLocation loc) implements StmtNode {
        public static Assignment of(ExprNode target, ExprNode value) {
            return new Assignment(target, value, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitAssignment(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Put statement: put x into y, put x before y, put x after y.
     */
    record Put(PutType type, ExprNode value, ExprNode target, Node.SourceLocation loc) implements StmtNode {
        public enum PutType {
            INTO, BEFORE, AFTER
        }

        public static Put into(ExprNode value, ExprNode target) {
            return new Put(PutType.INTO, value, target, null);
        }

        public static Put before(ExprNode value, ExprNode target) {
            return new Put(PutType.BEFORE, value, target, null);
        }

        public static Put after(ExprNode value, ExprNode target) {
            return new Put(PutType.AFTER, value, target, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitPut(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * If statement: if cond then ... else ... end if.
     */
    record If(ExprNode condition, Block thenBlock, Block elseBlock, Node.SourceLocation loc) implements StmtNode {
        public static If simple(ExprNode condition, Block thenBlock) {
            return new If(condition, thenBlock, null, null);
        }

        public static If withElse(ExprNode condition, Block thenBlock, Block elseBlock) {
            return new If(condition, thenBlock, elseBlock, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitIf(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Repeat while loop: repeat while condition ... end repeat.
     */
    record RepeatWhile(ExprNode condition, Block body, Node.SourceLocation loc) implements StmtNode {
        public static RepeatWhile of(ExprNode condition, Block body) {
            return new RepeatWhile(condition, body, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitRepeatWhile(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Repeat with in loop: repeat with x in list ... end repeat.
     */
    record RepeatWithIn(String varName, ExprNode list, Block body, Node.SourceLocation loc) implements StmtNode {
        public static RepeatWithIn of(String varName, ExprNode list, Block body) {
            return new RepeatWithIn(varName, list, body, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitRepeatWithIn(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Repeat with to/down to loop: repeat with x = start to/down to end ... end repeat.
     */
    record RepeatWithTo(String varName, ExprNode start, ExprNode end, boolean ascending, Block body, Node.SourceLocation loc) implements StmtNode {
        public static RepeatWithTo ascending(String varName, ExprNode start, ExprNode end, Block body) {
            return new RepeatWithTo(varName, start, end, true, body, null);
        }

        public static RepeatWithTo descending(String varName, ExprNode start, ExprNode end, Block body) {
            return new RepeatWithTo(varName, start, end, false, body, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitRepeatWithTo(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Case statement: case value of ... end case.
     */
    record Case(ExprNode value, List<CaseLabel> labels, Block otherwise, Node.SourceLocation loc) implements StmtNode {
        public record CaseLabel(List<ExprNode> values, Block body) {}

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitCase(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Tell statement: tell window ... end tell.
     */
    record Tell(ExprNode target, Block body, Node.SourceLocation loc) implements StmtNode {
        public static Tell of(ExprNode target, Block body) {
            return new Tell(target, body, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitTell(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Return statement: return or return value.
     */
    record Return(ExprNode value, Node.SourceLocation loc) implements StmtNode {
        public static Return empty() {
            return new Return(null, null);
        }

        public static Return withValue(ExprNode value) {
            return new Return(value, null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitReturn(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Exit statement: exit.
     */
    record Exit(Node.SourceLocation loc) implements StmtNode {
        public static Exit instance() {
            return new Exit(null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitExit(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Exit repeat statement: exit repeat.
     */
    record ExitRepeat(Node.SourceLocation loc) implements StmtNode {
        public static ExitRepeat instance() {
            return new ExitRepeat(null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitExitRepeat(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Next repeat statement: next repeat.
     */
    record NextRepeat(Node.SourceLocation loc) implements StmtNode {
        public static NextRepeat instance() {
            return new NextRepeat(null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitNextRepeat(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Global declaration: global var1, var2, var3.
     */
    record Global(List<String> variables, Node.SourceLocation loc) implements StmtNode {
        public static Global of(List<String> variables) {
            return new Global(variables, null);
        }

        public static Global of(String... variables) {
            return new Global(List.of(variables), null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitGlobal(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Property declaration: property prop1, prop2 (script-level only).
     */
    record Property(List<String> properties, Node.SourceLocation loc) implements StmtNode {
        public static Property of(List<String> properties) {
            return new Property(properties, null);
        }

        public static Property of(String... properties) {
            return new Property(List.of(properties), null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitProperty(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }

    /**
     * Block of statements.
     */
    record Block(List<StmtNode> statements, Node.SourceLocation loc) implements StmtNode {
        public static Block of(List<StmtNode> statements) {
            return new Block(statements, null);
        }

        public static Block of(StmtNode... statements) {
            return new Block(List.of(statements), null);
        }

        public static Block empty() {
            return new Block(List.of(), null);
        }

        @Override
        public <T> T accept(NodeVisitor<T> visitor) {
            return visitor.visitBlock(this);
        }

        @Override
        public SourceLocation location() {
            return loc;
        }
    }
}
