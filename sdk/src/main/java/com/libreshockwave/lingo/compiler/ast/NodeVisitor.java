package com.libreshockwave.lingo.compiler.ast;

/**
 * Visitor interface for traversing Lingo AST nodes.
 * Implementations can process each node type differently.
 *
 * @param <T> The return type of the visit methods
 */
public interface NodeVisitor<T> {

    // Script structure
    T visitScript(ScriptNode node);
    T visitHandler(HandlerNode node);

    // Expressions
    T visitLiteral(ExprNode.Literal node);
    T visitVariable(ExprNode.Variable node);
    T visitBinaryOp(ExprNode.BinaryOp node);
    T visitUnaryOp(ExprNode.UnaryOp node);
    T visitCall(ExprNode.Call node);
    T visitMethodCall(ExprNode.MethodCall node);
    T visitMemberRef(ExprNode.MemberRef node);
    T visitSpriteRef(ExprNode.SpriteRef node);
    T visitTheProperty(ExprNode.TheProperty node);
    T visitPropertyOf(ExprNode.PropertyOf node);
    T visitChunkExpr(ExprNode.ChunkExpr node);
    T visitListLiteral(ExprNode.ListLiteral node);
    T visitPropListLiteral(ExprNode.PropListLiteral node);
    T visitNewObject(ExprNode.NewObject node);
    T visitMenu(ExprNode.Menu node);
    T visitMenuItem(ExprNode.MenuItem node);
    T visitSound(ExprNode.Sound node);
    T visitCast(ExprNode.Cast node);
    T visitField(ExprNode.Field node);

    // Statements
    T visitExprStmt(StmtNode.ExprStmt node);
    T visitAssignment(StmtNode.Assignment node);
    T visitPut(StmtNode.Put node);
    T visitIf(StmtNode.If node);
    T visitRepeatWhile(StmtNode.RepeatWhile node);
    T visitRepeatWithIn(StmtNode.RepeatWithIn node);
    T visitRepeatWithTo(StmtNode.RepeatWithTo node);
    T visitCase(StmtNode.Case node);
    T visitTell(StmtNode.Tell node);
    T visitReturn(StmtNode.Return node);
    T visitExit(StmtNode.Exit node);
    T visitExitRepeat(StmtNode.ExitRepeat node);
    T visitNextRepeat(StmtNode.NextRepeat node);
    T visitGlobal(StmtNode.Global node);
    T visitProperty(StmtNode.Property node);
    T visitBlock(StmtNode.Block node);
}
