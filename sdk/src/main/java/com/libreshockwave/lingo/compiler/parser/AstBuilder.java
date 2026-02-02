package com.libreshockwave.lingo.compiler.parser;

import com.libreshockwave.lingo.compiler.ast.*;

import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds AST nodes from the ANTLR parse tree.
 * Extends the generated LingoBaseVisitor to transform parse tree nodes into AST nodes.
 */
public class AstBuilder extends LingoBaseVisitor<Node> {

    // --- Script Structure ---

    @Override
    public Node visitScript(LingoParser.ScriptContext ctx) {
        ScriptNode.Builder builder = ScriptNode.builder();

        for (LingoParser.ScriptElementContext elem : ctx.scriptElement()) {
            if (elem.propertyDecl() != null) {
                for (TerminalNode id : elem.propertyDecl().identifierList().IDENTIFIER()) {
                    builder.addProperty(id.getText());
                }
            } else if (elem.globalDecl() != null) {
                for (TerminalNode id : elem.globalDecl().identifierList().IDENTIFIER()) {
                    builder.addGlobal(id.getText());
                }
            } else if (elem.handler() != null) {
                HandlerNode handler = (HandlerNode) visit(elem.handler());
                if (handler != null) {
                    builder.addHandler(handler);
                }
            }
        }

        return builder.build();
    }

    @Override
    public Node visitHandler(LingoParser.HandlerContext ctx) {
        String name = ctx.IDENTIFIER(0).getText();

        List<String> params = new ArrayList<>();
        if (ctx.parameterList() != null) {
            for (TerminalNode id : ctx.parameterList().IDENTIFIER()) {
                params.add(id.getText());
            }
        }

        List<StmtNode> statements = new ArrayList<>();
        if (ctx.statementList() != null) {
            for (LingoParser.StatementContext stmtCtx : ctx.statementList().statement()) {
                StmtNode stmt = visitStatement(stmtCtx);
                if (stmt != null) {
                    statements.add(stmt);
                }
            }
        }

        StmtNode.Block body = StmtNode.Block.of(statements);

        return HandlerNode.of(name, params, body);
    }

    // --- Statements ---

    private StmtNode visitStatement(LingoParser.StatementContext ctx) {
        if (ctx.assignmentStmt() != null) {
            return visitAssignmentStmt(ctx.assignmentStmt());
        } else if (ctx.putStmt() != null) {
            return visitPutStmt(ctx.putStmt());
        } else if (ctx.ifStmt() != null) {
            return visitIfStmt(ctx.ifStmt());
        } else if (ctx.repeatStmt() != null) {
            return visitRepeatStmt(ctx.repeatStmt());
        } else if (ctx.caseStmt() != null) {
            return visitCaseStmt(ctx.caseStmt());
        } else if (ctx.tellStmt() != null) {
            return visitTellStmt(ctx.tellStmt());
        } else if (ctx.returnStmt() != null) {
            return visitReturnStmt(ctx.returnStmt());
        } else if (ctx.exitStmt() != null) {
            return StmtNode.Exit.instance();
        } else if (ctx.exitRepeatStmt() != null) {
            return StmtNode.ExitRepeat.instance();
        } else if (ctx.nextRepeatStmt() != null) {
            return StmtNode.NextRepeat.instance();
        } else if (ctx.globalStmt() != null) {
            List<String> vars = new ArrayList<>();
            for (TerminalNode id : ctx.globalStmt().identifierList().IDENTIFIER()) {
                vars.add(id.getText());
            }
            return StmtNode.Global.of(vars);
        } else if (ctx.callStmt() != null) {
            return visitCallStmt(ctx.callStmt());
        }
        return null;
    }

    private StmtNode visitAssignmentStmt(LingoParser.AssignmentStmtContext ctx) {
        ExprNode target = visitExpr(ctx.expression(0));
        ExprNode value = visitExpr(ctx.expression(1));
        return StmtNode.Assignment.of(target, value);
    }

    private StmtNode visitPutStmt(LingoParser.PutStmtContext ctx) {
        ExprNode value = visitExpr(ctx.expression(0));
        ExprNode target = visitExpr(ctx.expression(1));

        if (ctx.INTO() != null) {
            return StmtNode.Put.into(value, target);
        } else if (ctx.BEFORE() != null) {
            return StmtNode.Put.before(value, target);
        } else {
            return StmtNode.Put.after(value, target);
        }
    }

    private StmtNode visitIfStmt(LingoParser.IfStmtContext ctx) {
        ExprNode condition = visitExpr(ctx.expression());

        if (ctx.singleLineIf() != null) {
            // Single line if
            LingoParser.SingleLineIfContext single = ctx.singleLineIf();
            StmtNode thenStmt = visitStatement(single.statement(0));
            StmtNode.Block thenBlock = StmtNode.Block.of(thenStmt);

            if (single.ELSE() != null && single.statement().size() > 1) {
                StmtNode elseStmt = visitStatement(single.statement(1));
                StmtNode.Block elseBlock = StmtNode.Block.of(elseStmt);
                return StmtNode.If.withElse(condition, thenBlock, elseBlock);
            }
            return StmtNode.If.simple(condition, thenBlock);
        } else {
            // Multi-line if
            List<StmtNode> thenStmts = new ArrayList<>();
            if (ctx.statementList() != null) {
                for (LingoParser.StatementContext stmtCtx : ctx.statementList().statement()) {
                    StmtNode stmt = visitStatement(stmtCtx);
                    if (stmt != null) {
                        thenStmts.add(stmt);
                    }
                }
            }
            StmtNode.Block thenBlock = StmtNode.Block.of(thenStmts);

            // Handle else if and else clauses
            StmtNode.Block elseBlock = null;
            if (ctx.elseClause() != null) {
                List<StmtNode> elseStmts = new ArrayList<>();
                for (LingoParser.StatementContext stmtCtx : ctx.elseClause().statementList().statement()) {
                    StmtNode stmt = visitStatement(stmtCtx);
                    if (stmt != null) {
                        elseStmts.add(stmt);
                    }
                }
                elseBlock = StmtNode.Block.of(elseStmts);
            }

            // Handle else if as nested if
            if (!ctx.elseIfClause().isEmpty()) {
                // Build nested if statements from the else-if clauses
                for (int i = ctx.elseIfClause().size() - 1; i >= 0; i--) {
                    LingoParser.ElseIfClauseContext elseIfCtx = ctx.elseIfClause(i);
                    ExprNode elseIfCond = visitExpr(elseIfCtx.expression());
                    List<StmtNode> elseIfStmts = new ArrayList<>();
                    for (LingoParser.StatementContext stmtCtx : elseIfCtx.statementList().statement()) {
                        StmtNode stmt = visitStatement(stmtCtx);
                        if (stmt != null) {
                            elseIfStmts.add(stmt);
                        }
                    }
                    StmtNode.Block elseIfBlock = StmtNode.Block.of(elseIfStmts);
                    StmtNode.If nestedIf = elseBlock != null
                        ? StmtNode.If.withElse(elseIfCond, elseIfBlock, elseBlock)
                        : StmtNode.If.simple(elseIfCond, elseIfBlock);
                    elseBlock = StmtNode.Block.of(nestedIf);
                }
            }

            return elseBlock != null
                ? StmtNode.If.withElse(condition, thenBlock, elseBlock)
                : StmtNode.If.simple(condition, thenBlock);
        }
    }

    private StmtNode visitRepeatStmt(LingoParser.RepeatStmtContext ctx) {
        List<StmtNode> bodyStmts = new ArrayList<>();
        if (ctx.statementList() != null) {
            for (LingoParser.StatementContext stmtCtx : ctx.statementList().statement()) {
                StmtNode stmt = visitStatement(stmtCtx);
                if (stmt != null) {
                    bodyStmts.add(stmt);
                }
            }
        }
        StmtNode.Block body = StmtNode.Block.of(bodyStmts);

        if (ctx.WHILE() != null) {
            // repeat while
            ExprNode condition = visitExpr(ctx.expression(0));
            return StmtNode.RepeatWhile.of(condition, body);
        } else if (ctx.IN() != null) {
            // repeat with x in list
            String varName = ctx.IDENTIFIER().getText();
            ExprNode list = visitExpr(ctx.expression(0));
            return StmtNode.RepeatWithIn.of(varName, list, body);
        } else {
            // repeat with x = start to/down to end
            String varName = ctx.IDENTIFIER().getText();
            ExprNode start = visitExpr(ctx.expression(0));
            ExprNode end = visitExpr(ctx.expression(1));
            boolean ascending = ctx.DOWN() == null;
            return ascending
                ? StmtNode.RepeatWithTo.ascending(varName, start, end, body)
                : StmtNode.RepeatWithTo.descending(varName, start, end, body);
        }
    }

    private StmtNode visitCaseStmt(LingoParser.CaseStmtContext ctx) {
        ExprNode value = visitExpr(ctx.expression());

        List<StmtNode.Case.CaseLabel> labels = new ArrayList<>();
        for (LingoParser.CaseLabelContext labelCtx : ctx.caseLabel()) {
            List<ExprNode> values = new ArrayList<>();
            for (LingoParser.ExpressionContext exprCtx : labelCtx.expression()) {
                values.add(visitExpr(exprCtx));
            }
            List<StmtNode> stmts = new ArrayList<>();
            for (LingoParser.StatementContext stmtCtx : labelCtx.statementList().statement()) {
                StmtNode stmt = visitStatement(stmtCtx);
                if (stmt != null) {
                    stmts.add(stmt);
                }
            }
            labels.add(new StmtNode.Case.CaseLabel(values, StmtNode.Block.of(stmts)));
        }

        StmtNode.Block otherwise = null;
        if (ctx.otherwiseClause() != null) {
            List<StmtNode> stmts = new ArrayList<>();
            for (LingoParser.StatementContext stmtCtx : ctx.otherwiseClause().statementList().statement()) {
                StmtNode stmt = visitStatement(stmtCtx);
                if (stmt != null) {
                    stmts.add(stmt);
                }
            }
            otherwise = StmtNode.Block.of(stmts);
        }

        return new StmtNode.Case(value, labels, otherwise, null);
    }

    private StmtNode visitTellStmt(LingoParser.TellStmtContext ctx) {
        ExprNode target = visitExpr(ctx.expression());

        if (ctx.statementList() != null) {
            List<StmtNode> stmts = new ArrayList<>();
            for (LingoParser.StatementContext stmtCtx : ctx.statementList().statement()) {
                StmtNode stmt = visitStatement(stmtCtx);
                if (stmt != null) {
                    stmts.add(stmt);
                }
            }
            return StmtNode.Tell.of(target, StmtNode.Block.of(stmts));
        } else {
            // tell target to statement
            StmtNode stmt = visitStatement(ctx.statement());
            return StmtNode.Tell.of(target, StmtNode.Block.of(stmt));
        }
    }

    private StmtNode visitReturnStmt(LingoParser.ReturnStmtContext ctx) {
        if (ctx.expression() != null) {
            ExprNode value = visitExpr(ctx.expression());
            return StmtNode.Return.withValue(value);
        }
        return StmtNode.Return.empty();
    }

    private StmtNode visitCallStmt(LingoParser.CallStmtContext ctx) {
        if (ctx.DOT() != null) {
            // Method call: expr.method(args)
            ExprNode target = visitExpr(ctx.expression());
            String methodName = ctx.IDENTIFIER().getText();
            List<ExprNode> args = new ArrayList<>();
            if (ctx.argumentList() != null) {
                for (LingoParser.ExpressionContext exprCtx : ctx.argumentList().expression()) {
                    args.add(visitExpr(exprCtx));
                }
            }
            return StmtNode.ExprStmt.of(new ExprNode.MethodCall(target, methodName, args, null));
        } else {
            // Function call
            String name = ctx.IDENTIFIER().getText();
            List<ExprNode> args = new ArrayList<>();
            if (ctx.argumentList() != null) {
                for (LingoParser.ExpressionContext exprCtx : ctx.argumentList().expression()) {
                    args.add(visitExpr(exprCtx));
                }
            }
            return StmtNode.ExprStmt.of(ExprNode.Call.command(name, args));
        }
    }

    // --- Expressions ---

    private ExprNode visitExpr(LingoParser.ExpressionContext ctx) {
        return visitOrExpr(ctx.orExpr());
    }

    private ExprNode visitOrExpr(LingoParser.OrExprContext ctx) {
        ExprNode left = visitAndExpr(ctx.andExpr(0));
        for (int i = 1; i < ctx.andExpr().size(); i++) {
            ExprNode right = visitAndExpr(ctx.andExpr(i));
            left = new ExprNode.BinaryOp(ExprNode.BinaryOp.BinaryOpType.OR, left, right, null);
        }
        return left;
    }

    private ExprNode visitAndExpr(LingoParser.AndExprContext ctx) {
        ExprNode left = visitNotExpr(ctx.notExpr(0));
        for (int i = 1; i < ctx.notExpr().size(); i++) {
            ExprNode right = visitNotExpr(ctx.notExpr(i));
            left = new ExprNode.BinaryOp(ExprNode.BinaryOp.BinaryOpType.AND, left, right, null);
        }
        return left;
    }

    private ExprNode visitNotExpr(LingoParser.NotExprContext ctx) {
        if (ctx.NOT() != null) {
            ExprNode operand = visitNotExpr(ctx.notExpr());
            return new ExprNode.UnaryOp(ExprNode.UnaryOp.UnaryOpType.NOT, operand, null);
        }
        return visitComparisonExpr(ctx.comparisonExpr());
    }

    private ExprNode visitComparisonExpr(LingoParser.ComparisonExprContext ctx) {
        ExprNode left = visitConcatExpr(ctx.concatExpr(0));
        if (ctx.concatExpr().size() > 1) {
            ExprNode right = visitConcatExpr(ctx.concatExpr(1));
            ExprNode.BinaryOp.BinaryOpType op;
            if (ctx.LT() != null) op = ExprNode.BinaryOp.BinaryOpType.LT;
            else if (ctx.LE() != null) op = ExprNode.BinaryOp.BinaryOpType.LE;
            else if (ctx.GT() != null) op = ExprNode.BinaryOp.BinaryOpType.GT;
            else if (ctx.GE() != null) op = ExprNode.BinaryOp.BinaryOpType.GE;
            else if (ctx.EQ_OP() != null) op = ExprNode.BinaryOp.BinaryOpType.EQ;
            else if (ctx.NE() != null) op = ExprNode.BinaryOp.BinaryOpType.NE;
            else if (ctx.CONTAINS() != null) op = ExprNode.BinaryOp.BinaryOpType.CONTAINS;
            else if (ctx.STARTS() != null) op = ExprNode.BinaryOp.BinaryOpType.STARTS;
            else op = ExprNode.BinaryOp.BinaryOpType.EQ;
            left = new ExprNode.BinaryOp(op, left, right, null);
        }
        return left;
    }

    private ExprNode visitConcatExpr(LingoParser.ConcatExprContext ctx) {
        ExprNode left = visitAddExpr(ctx.addExpr(0));
        for (int i = 1; i < ctx.addExpr().size(); i++) {
            ExprNode right = visitAddExpr(ctx.addExpr(i));
            // Check which operator was used
            ParseTree opToken = ctx.getChild(2 * i - 1);
            ExprNode.BinaryOp.BinaryOpType op = opToken.getText().equals("&&")
                ? ExprNode.BinaryOp.BinaryOpType.CONCAT_SPACE
                : ExprNode.BinaryOp.BinaryOpType.CONCAT;
            left = new ExprNode.BinaryOp(op, left, right, null);
        }
        return left;
    }

    private ExprNode visitAddExpr(LingoParser.AddExprContext ctx) {
        ExprNode left = visitMulExpr(ctx.mulExpr(0));
        for (int i = 1; i < ctx.mulExpr().size(); i++) {
            ExprNode right = visitMulExpr(ctx.mulExpr(i));
            ParseTree opToken = ctx.getChild(2 * i - 1);
            ExprNode.BinaryOp.BinaryOpType op = opToken.getText().equals("+")
                ? ExprNode.BinaryOp.BinaryOpType.ADD
                : ExprNode.BinaryOp.BinaryOpType.SUB;
            left = new ExprNode.BinaryOp(op, left, right, null);
        }
        return left;
    }

    private ExprNode visitMulExpr(LingoParser.MulExprContext ctx) {
        ExprNode left = visitUnaryExpr(ctx.unaryExpr(0));
        for (int i = 1; i < ctx.unaryExpr().size(); i++) {
            ExprNode right = visitUnaryExpr(ctx.unaryExpr(i));
            ParseTree opToken = ctx.getChild(2 * i - 1);
            String opText = opToken.getText();
            ExprNode.BinaryOp.BinaryOpType op;
            if (opText.equals("*")) op = ExprNode.BinaryOp.BinaryOpType.MUL;
            else if (opText.equals("/")) op = ExprNode.BinaryOp.BinaryOpType.DIV;
            else op = ExprNode.BinaryOp.BinaryOpType.MOD;
            left = new ExprNode.BinaryOp(op, left, right, null);
        }
        return left;
    }

    private ExprNode visitUnaryExpr(LingoParser.UnaryExprContext ctx) {
        if (ctx.MINUS() != null) {
            ExprNode operand = visitUnaryExpr(ctx.unaryExpr());
            return new ExprNode.UnaryOp(ExprNode.UnaryOp.UnaryOpType.NEGATE, operand, null);
        }
        return visitPrimaryExpr(ctx.primaryExpr());
    }

    private ExprNode visitPrimaryExpr(LingoParser.PrimaryExprContext ctx) {
        if (ctx.literal() != null) {
            return visitLiteral(ctx.literal());
        } else if (ctx.theExpr() != null) {
            return visitTheExpr(ctx.theExpr());
        } else if (ctx.memberExpr() != null) {
            return visitMemberExpr(ctx.memberExpr());
        } else if (ctx.spriteExpr() != null) {
            return visitSpriteExpr(ctx.spriteExpr());
        } else if (ctx.menuExpr() != null) {
            return visitMenuExpr(ctx.menuExpr());
        } else if (ctx.soundExpr() != null) {
            return visitSoundExpr(ctx.soundExpr());
        } else if (ctx.castExpr() != null) {
            return visitCastExpr(ctx.castExpr());
        } else if (ctx.fieldExpr() != null) {
            return visitFieldExpr(ctx.fieldExpr());
        } else if (ctx.chunkExpr() != null) {
            return visitChunkExpr(ctx.chunkExpr());
        } else if (ctx.newExpr() != null) {
            return visitNewExpr(ctx.newExpr());
        } else if (ctx.callExpr() != null) {
            return visitCallExpr(ctx.callExpr());
        } else if (ctx.variableExpr() != null) {
            return ExprNode.Variable.auto(ctx.variableExpr().IDENTIFIER().getText());
        } else if (ctx.expression() != null) {
            // Parenthesized expression
            return visitExpr(ctx.expression());
        } else if (ctx.DOT() != null) {
            // Method call or property access: primaryExpr.IDENTIFIER(args)?
            ExprNode target = visitPrimaryExpr(ctx.primaryExpr());
            String name = ctx.IDENTIFIER().getText();
            if (ctx.LPAREN() != null) {
                // Method call
                List<ExprNode> args = new ArrayList<>();
                if (ctx.argumentList() != null) {
                    for (LingoParser.ExpressionContext exprCtx : ctx.argumentList().expression()) {
                        args.add(visitExpr(exprCtx));
                    }
                }
                return new ExprNode.MethodCall(target, name, args, null);
            } else {
                // Property access
                return new ExprNode.PropertyOf(name, target, null);
            }
        } else if (ctx.LBRACKET() != null) {
            // Index access: primaryExpr[expr]
            ExprNode target = visitPrimaryExpr(ctx.primaryExpr());
            ExprNode index = visitExpr(ctx.expression());
            // Treat as method call to getAt
            return new ExprNode.MethodCall(target, "getAt", List.of(index), null);
        }
        return null;
    }

    private ExprNode visitLiteral(LingoParser.LiteralContext ctx) {
        if (ctx.INTEGER() != null) {
            return ExprNode.Literal.integer(Integer.parseInt(ctx.INTEGER().getText()));
        } else if (ctx.FLOAT() != null) {
            return ExprNode.Literal.floatVal(Double.parseDouble(ctx.FLOAT().getText()));
        } else if (ctx.STRING() != null) {
            String text = ctx.STRING().getText();
            // Remove quotes and handle escaped quotes
            text = text.substring(1, text.length() - 1).replace("\"\"", "\"");
            return ExprNode.Literal.string(text);
        } else if (ctx.SYMBOL() != null) {
            String text = ctx.SYMBOL().getText();
            return ExprNode.Literal.symbol(text.substring(1)); // Remove #
        } else if (ctx.TRUE() != null) {
            return ExprNode.Literal.trueVal();
        } else if (ctx.FALSE() != null) {
            return ExprNode.Literal.falseVal();
        } else if (ctx.VOID() != null) {
            return ExprNode.Literal.voidVal();
        } else if (ctx.listLiteral() != null) {
            List<ExprNode> elements = new ArrayList<>();
            for (LingoParser.ExpressionContext exprCtx : ctx.listLiteral().expression()) {
                elements.add(visitExpr(exprCtx));
            }
            return ExprNode.ListLiteral.of(elements);
        } else if (ctx.propListLiteral() != null) {
            List<ExprNode.PropListLiteral.PropEntry> entries = new ArrayList<>();
            for (LingoParser.PropListEntryContext entryCtx : ctx.propListLiteral().propListEntry()) {
                ExprNode key;
                if (entryCtx.SYMBOL() != null) {
                    key = ExprNode.Literal.symbol(entryCtx.SYMBOL().getText().substring(1));
                } else if (entryCtx.STRING() != null) {
                    String text = entryCtx.STRING().getText();
                    key = ExprNode.Literal.string(text.substring(1, text.length() - 1));
                } else {
                    key = ExprNode.Literal.symbol(entryCtx.IDENTIFIER().getText());
                }
                ExprNode value = visitExpr(entryCtx.expression());
                entries.add(new ExprNode.PropListLiteral.PropEntry(key, value));
            }
            return ExprNode.PropListLiteral.of(entries);
        }
        return null;
    }

    private ExprNode visitTheExpr(LingoParser.TheExprContext ctx) {
        String property = ctx.IDENTIFIER(0).getText();
        if (ctx.expression() != null) {
            // the X of Y
            ExprNode target = visitExpr(ctx.expression());
            return new ExprNode.PropertyOf(property, target, null);
        } else if (ctx.IDENTIFIER().size() > 1) {
            // the X of Y expr (e.g., the loc of sprite 1)
            String targetType = ctx.IDENTIFIER(1).getText();
            // This needs special handling based on targetType
            return ExprNode.TheProperty.of(property);
        } else {
            // the X (global property)
            return ExprNode.TheProperty.of(property);
        }
    }

    private ExprNode visitMemberExpr(LingoParser.MemberExprContext ctx) {
        ExprNode memberRef = visitExpr(ctx.expression(0));
        ExprNode castLib = null;
        if (ctx.expression().size() > 1) {
            castLib = visitExpr(ctx.expression(1));
        }
        return new ExprNode.MemberRef(memberRef, castLib, null);
    }

    private ExprNode visitSpriteExpr(LingoParser.SpriteExprContext ctx) {
        ExprNode spriteNum = visitExpr(ctx.expression());
        return ExprNode.SpriteRef.of(spriteNum);
    }

    private ExprNode visitMenuExpr(LingoParser.MenuExprContext ctx) {
        if (ctx.MENUITEM() != null) {
            ExprNode itemRef = visitExpr(ctx.expression(0));
            ExprNode menuRef = visitExpr(ctx.expression(1));
            return new ExprNode.MenuItem(itemRef, menuRef, null);
        } else {
            ExprNode menuRef = visitExpr(ctx.expression(0));
            return new ExprNode.Menu(menuRef, null);
        }
    }

    private ExprNode visitSoundExpr(LingoParser.SoundExprContext ctx) {
        ExprNode soundRef = visitExpr(ctx.expression());
        return new ExprNode.Sound(soundRef, null);
    }

    private ExprNode visitCastExpr(LingoParser.CastExprContext ctx) {
        ExprNode castRef = visitExpr(ctx.expression());
        return new ExprNode.Cast(castRef, null);
    }

    private ExprNode visitFieldExpr(LingoParser.FieldExprContext ctx) {
        ExprNode fieldRef = visitExpr(ctx.expression());
        return new ExprNode.Field(fieldRef, null);
    }

    private ExprNode visitChunkExpr(LingoParser.ChunkExprContext ctx) {
        ExprNode.ChunkExpr.ChunkType chunkType;
        if (ctx.chunkType().CHAR() != null) chunkType = ExprNode.ChunkExpr.ChunkType.CHAR;
        else if (ctx.chunkType().WORD() != null) chunkType = ExprNode.ChunkExpr.ChunkType.WORD;
        else if (ctx.chunkType().ITEM() != null) chunkType = ExprNode.ChunkExpr.ChunkType.ITEM;
        else chunkType = ExprNode.ChunkExpr.ChunkType.LINE;

        ExprNode first = visitExpr(ctx.expression(0));
        ExprNode last = null;
        ExprNode target;

        if (ctx.TO() != null) {
            // Range: char 1 to 5 of x
            last = visitExpr(ctx.expression(1));
            target = visitExpr(ctx.expression(2));
            return ExprNode.ChunkExpr.range(chunkType, first, last, target);
        } else {
            // Single: char 1 of x
            target = visitExpr(ctx.expression(1));
            return ExprNode.ChunkExpr.single(chunkType, first, target);
        }
    }

    private ExprNode visitNewExpr(LingoParser.NewExprContext ctx) {
        // new(script "Foo", args) or script("Foo").new(args)
        ExprNode scriptRef;
        List<ExprNode> args = new ArrayList<>();

        if (ctx.SCRIPT() != null && ctx.DOT() == null) {
            // new(script expr, args)
            scriptRef = visitExpr(ctx.expression());
            if (ctx.argumentList() != null) {
                for (LingoParser.ExpressionContext exprCtx : ctx.argumentList().expression()) {
                    args.add(visitExpr(exprCtx));
                }
            }
        } else {
            // script(expr).new(args)
            scriptRef = visitExpr(ctx.expression());
            if (ctx.argumentList() != null) {
                for (LingoParser.ExpressionContext exprCtx : ctx.argumentList().expression()) {
                    args.add(visitExpr(exprCtx));
                }
            }
        }

        return new ExprNode.NewObject(scriptRef, args, null);
    }

    private ExprNode visitCallExpr(LingoParser.CallExprContext ctx) {
        String name = ctx.IDENTIFIER().getText();
        List<ExprNode> args = new ArrayList<>();
        if (ctx.argumentList() != null) {
            for (LingoParser.ExpressionContext exprCtx : ctx.argumentList().expression()) {
                args.add(visitExpr(exprCtx));
            }
        }
        return ExprNode.Call.function(name, args);
    }
}
