package com.libreshockwave.player.debug;

import com.libreshockwave.vm.Datum;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Simple expression evaluator for debugger conditions and watch expressions.
 * Supports a subset of Lingo syntax:
 * - Variables: i, name, count (looks up in locals, params, globals)
 * - Literals: 5, 3.14, "test", #symbol
 * - Comparisons: <, <=, >, >=, =, ==, <>, !=
 * - Logical: and, or, not
 * - Arithmetic: +, -, *, /, mod
 * - Property access: me.foo, obj.property
 * - Parentheses for grouping
 */
public class ExpressionEvaluator {

    /**
     * Context for expression evaluation containing variable bindings.
     */
    public record EvaluationContext(
        Map<String, Datum> locals,
        Map<String, Datum> params,
        Map<String, Datum> globals,
        Datum receiver  // "me" object
    ) {
        public static EvaluationContext empty() {
            return new EvaluationContext(Map.of(), Map.of(), Map.of(), null);
        }

        /**
         * Look up a variable by name. Search order: locals, params, globals.
         */
        public Datum lookupVariable(String name) {
            // Special case: "me" returns the receiver
            if ("me".equalsIgnoreCase(name)) {
                return receiver != null ? receiver : Datum.VOID;
            }

            // Check locals first
            if (locals != null && locals.containsKey(name)) {
                return locals.get(name);
            }

            // Then params
            if (params != null && params.containsKey(name)) {
                return params.get(name);
            }

            // Then globals
            if (globals != null && globals.containsKey(name)) {
                return globals.get(name);
            }

            // Not found - return VOID
            return Datum.VOID;
        }
    }

    /**
     * Result of expression evaluation.
     */
    public sealed interface EvalResult {
        record Success(Datum value) implements EvalResult {}
        record Error(String message) implements EvalResult {}
    }

    /**
     * Evaluate an expression and return the result.
     */
    public EvalResult evaluate(String expression, EvaluationContext context) {
        if (expression == null || expression.isBlank()) {
            return new EvalResult.Error("Empty expression");
        }

        try {
            Tokenizer tokenizer = new Tokenizer(expression);
            List<Token> tokens = tokenizer.tokenize();
            Parser parser = new Parser(tokens);
            Expr ast = parser.parseExpression();

            if (!parser.isAtEnd()) {
                return new EvalResult.Error("Unexpected input after expression");
            }

            Datum result = evaluateExpr(ast, context);
            return new EvalResult.Success(result);
        } catch (EvalException e) {
            return new EvalResult.Error(e.getMessage());
        } catch (Exception e) {
            return new EvalResult.Error("Parse error: " + e.getMessage());
        }
    }

    /**
     * Evaluate an expression and return a boolean (for conditions).
     * Returns true if evaluation succeeds and result is truthy.
     * Returns false if there's an error (fails open - doesn't break).
     */
    public boolean evaluateCondition(String expression, EvaluationContext context) {
        EvalResult result = evaluate(expression, context);
        return switch (result) {
            case EvalResult.Success s -> s.value().isTruthy();
            case EvalResult.Error e -> false;
        };
    }

    /**
     * Interpolate a log message by replacing {expr} with evaluated values.
     */
    public String interpolateLogMessage(String message, EvaluationContext context) {
        if (message == null || !message.contains("{")) {
            return message;
        }

        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < message.length()) {
            char c = message.charAt(i);
            if (c == '{') {
                int end = message.indexOf('}', i + 1);
                if (end > i) {
                    String expr = message.substring(i + 1, end);
                    EvalResult evalResult = evaluate(expr, context);
                    String replacement = switch (evalResult) {
                        case EvalResult.Success s -> formatValue(s.value());
                        case EvalResult.Error e -> "<" + e.message() + ">";
                    };
                    result.append(replacement);
                    i = end + 1;
                    continue;
                }
            }
            result.append(c);
            i++;
        }
        return result.toString();
    }

    private String formatValue(Datum value) {
        return switch (value) {
            case Datum.Str s -> s.value();  // Don't add quotes for strings in log output
            case Datum d -> d.toString();
        };
    }

    // ==================== AST Nodes ====================

    private sealed interface Expr {}

    private record LiteralExpr(Datum value) implements Expr {}
    private record VariableExpr(String name) implements Expr {}
    private record PropertyExpr(Expr object, String property) implements Expr {}
    private record UnaryExpr(String operator, Expr operand) implements Expr {}
    private record BinaryExpr(Expr left, String operator, Expr right) implements Expr {}

    // ==================== Tokenizer ====================

    private enum TokenType {
        NUMBER, STRING, SYMBOL, IDENTIFIER,
        LPAREN, RPAREN,
        PLUS, MINUS, STAR, SLASH, MOD,
        LT, LE, GT, GE, EQ, NE,
        AND, OR, NOT,
        DOT, COMMA,
        EOF
    }

    private record Token(TokenType type, String value, int position) {}

    private static class Tokenizer {
        private final String input;
        private int pos = 0;

        Tokenizer(String input) {
            this.input = input;
        }

        List<Token> tokenize() {
            List<Token> tokens = new ArrayList<>();
            while (!isAtEnd()) {
                skipWhitespace();
                if (isAtEnd()) break;

                int start = pos;
                Token token = scanToken();
                if (token != null) {
                    tokens.add(token);
                }
            }
            tokens.add(new Token(TokenType.EOF, "", pos));
            return tokens;
        }

        private void skipWhitespace() {
            while (!isAtEnd() && Character.isWhitespace(peek())) {
                pos++;
            }
        }

        private Token scanToken() {
            int start = pos;
            char c = advance();

            // Single character tokens
            switch (c) {
                case '(' -> { return new Token(TokenType.LPAREN, "(", start); }
                case ')' -> { return new Token(TokenType.RPAREN, ")", start); }
                case '+' -> { return new Token(TokenType.PLUS, "+", start); }
                case '-' -> {
                    // Check if this is a negative number
                    if (Character.isDigit(peek())) {
                        pos--; // Back up to scan the number including the minus
                        return scanNumber();
                    }
                    return new Token(TokenType.MINUS, "-", start);
                }
                case '*' -> { return new Token(TokenType.STAR, "*", start); }
                case '/' -> { return new Token(TokenType.SLASH, "/", start); }
                case '.' -> { return new Token(TokenType.DOT, ".", start); }
                case ',' -> { return new Token(TokenType.COMMA, ",", start); }
                case '<' -> {
                    if (match('=')) return new Token(TokenType.LE, "<=", start);
                    if (match('>')) return new Token(TokenType.NE, "<>", start);
                    return new Token(TokenType.LT, "<", start);
                }
                case '>' -> {
                    if (match('=')) return new Token(TokenType.GE, ">=", start);
                    return new Token(TokenType.GT, ">", start);
                }
                case '=' -> {
                    match('='); // Allow both = and == for equality
                    return new Token(TokenType.EQ, "=", start);
                }
                case '!' -> {
                    if (match('=')) return new Token(TokenType.NE, "!=", start);
                    throw new EvalException("Unexpected character: !");
                }
                case '"' -> { return scanString(); }
                case '#' -> { return scanSymbol(); }
            }

            // Numbers
            if (Character.isDigit(c)) {
                pos--;
                return scanNumber();
            }

            // Identifiers and keywords
            if (Character.isLetter(c) || c == '_') {
                pos--;
                return scanIdentifier();
            }

            throw new EvalException("Unexpected character: " + c);
        }

        private Token scanNumber() {
            int start = pos;
            boolean isNegative = false;

            if (peek() == '-') {
                isNegative = true;
                advance();
            }

            while (Character.isDigit(peek())) {
                advance();
            }

            // Look for decimal part
            if (peek() == '.' && Character.isDigit(peekNext())) {
                advance(); // consume '.'
                while (Character.isDigit(peek())) {
                    advance();
                }
            }

            String value = input.substring(start, pos);
            return new Token(TokenType.NUMBER, value, start);
        }

        private Token scanString() {
            int start = pos - 1; // include opening quote
            StringBuilder sb = new StringBuilder();

            while (!isAtEnd() && peek() != '"') {
                if (peek() == '\\' && peekNext() == '"') {
                    advance(); // skip backslash
                    sb.append(advance()); // add quote
                } else {
                    sb.append(advance());
                }
            }

            if (isAtEnd()) {
                throw new EvalException("Unterminated string");
            }

            advance(); // consume closing quote
            return new Token(TokenType.STRING, sb.toString(), start);
        }

        private Token scanSymbol() {
            int start = pos - 1; // include #
            while (Character.isLetterOrDigit(peek()) || peek() == '_') {
                advance();
            }
            String name = input.substring(start + 1, pos); // exclude #
            return new Token(TokenType.SYMBOL, name, start);
        }

        private Token scanIdentifier() {
            int start = pos;
            while (Character.isLetterOrDigit(peek()) || peek() == '_') {
                advance();
            }
            String name = input.substring(start, pos);

            // Check for keywords
            return switch (name.toLowerCase()) {
                case "and" -> new Token(TokenType.AND, name, start);
                case "or" -> new Token(TokenType.OR, name, start);
                case "not" -> new Token(TokenType.NOT, name, start);
                case "mod" -> new Token(TokenType.MOD, name, start);
                case "true" -> new Token(TokenType.NUMBER, "1", start);
                case "false" -> new Token(TokenType.NUMBER, "0", start);
                default -> new Token(TokenType.IDENTIFIER, name, start);
            };
        }

        private boolean isAtEnd() {
            return pos >= input.length();
        }

        private char peek() {
            if (isAtEnd()) return '\0';
            return input.charAt(pos);
        }

        private char peekNext() {
            if (pos + 1 >= input.length()) return '\0';
            return input.charAt(pos + 1);
        }

        private char advance() {
            return input.charAt(pos++);
        }

        private boolean match(char expected) {
            if (isAtEnd() || peek() != expected) return false;
            pos++;
            return true;
        }
    }

    // ==================== Parser ====================

    private static class Parser {
        private final List<Token> tokens;
        private int current = 0;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Expr parseExpression() {
            return parseOr();
        }

        private Expr parseOr() {
            Expr left = parseAnd();
            while (match(TokenType.OR)) {
                Expr right = parseAnd();
                left = new BinaryExpr(left, "or", right);
            }
            return left;
        }

        private Expr parseAnd() {
            Expr left = parseNot();
            while (match(TokenType.AND)) {
                Expr right = parseNot();
                left = new BinaryExpr(left, "and", right);
            }
            return left;
        }

        private Expr parseNot() {
            if (match(TokenType.NOT)) {
                Expr operand = parseNot();
                return new UnaryExpr("not", operand);
            }
            return parseComparison();
        }

        private Expr parseComparison() {
            Expr left = parseAddition();
            if (match(TokenType.LT)) {
                Expr right = parseAddition();
                return new BinaryExpr(left, "<", right);
            }
            if (match(TokenType.LE)) {
                Expr right = parseAddition();
                return new BinaryExpr(left, "<=", right);
            }
            if (match(TokenType.GT)) {
                Expr right = parseAddition();
                return new BinaryExpr(left, ">", right);
            }
            if (match(TokenType.GE)) {
                Expr right = parseAddition();
                return new BinaryExpr(left, ">=", right);
            }
            if (match(TokenType.EQ)) {
                Expr right = parseAddition();
                return new BinaryExpr(left, "=", right);
            }
            if (match(TokenType.NE)) {
                Expr right = parseAddition();
                return new BinaryExpr(left, "<>", right);
            }
            return left;
        }

        private Expr parseAddition() {
            Expr left = parseMultiplication();
            while (true) {
                if (match(TokenType.PLUS)) {
                    Expr right = parseMultiplication();
                    left = new BinaryExpr(left, "+", right);
                } else if (match(TokenType.MINUS)) {
                    Expr right = parseMultiplication();
                    left = new BinaryExpr(left, "-", right);
                } else {
                    break;
                }
            }
            return left;
        }

        private Expr parseMultiplication() {
            Expr left = parseUnary();
            while (true) {
                if (match(TokenType.STAR)) {
                    Expr right = parseUnary();
                    left = new BinaryExpr(left, "*", right);
                } else if (match(TokenType.SLASH)) {
                    Expr right = parseUnary();
                    left = new BinaryExpr(left, "/", right);
                } else if (match(TokenType.MOD)) {
                    Expr right = parseUnary();
                    left = new BinaryExpr(left, "mod", right);
                } else {
                    break;
                }
            }
            return left;
        }

        private Expr parseUnary() {
            if (match(TokenType.MINUS)) {
                Expr operand = parseUnary();
                return new UnaryExpr("-", operand);
            }
            return parsePostfix();
        }

        private Expr parsePostfix() {
            Expr expr = parsePrimary();

            // Handle property access: obj.property
            while (match(TokenType.DOT)) {
                Token name = consume(TokenType.IDENTIFIER, "Expected property name after '.'");
                expr = new PropertyExpr(expr, name.value());
            }

            return expr;
        }

        private Expr parsePrimary() {
            // Number literal
            if (match(TokenType.NUMBER)) {
                Token token = previous();
                String value = token.value();
                if (value.contains(".")) {
                    return new LiteralExpr(Datum.of(Double.parseDouble(value)));
                } else {
                    return new LiteralExpr(Datum.of(Integer.parseInt(value)));
                }
            }

            // String literal
            if (match(TokenType.STRING)) {
                return new LiteralExpr(Datum.of(previous().value()));
            }

            // Symbol literal
            if (match(TokenType.SYMBOL)) {
                return new LiteralExpr(Datum.symbol(previous().value()));
            }

            // Identifier (variable)
            if (match(TokenType.IDENTIFIER)) {
                return new VariableExpr(previous().value());
            }

            // Parenthesized expression
            if (match(TokenType.LPAREN)) {
                Expr expr = parseExpression();
                consume(TokenType.RPAREN, "Expected ')' after expression");
                return expr;
            }

            throw new EvalException("Expected expression at position " + peek().position());
        }

        private boolean match(TokenType type) {
            if (check(type)) {
                advance();
                return true;
            }
            return false;
        }

        private boolean check(TokenType type) {
            if (isAtEnd()) return false;
            return peek().type() == type;
        }

        private Token advance() {
            if (!isAtEnd()) current++;
            return previous();
        }

        private boolean isAtEnd() {
            return peek().type() == TokenType.EOF;
        }

        private Token peek() {
            return tokens.get(current);
        }

        private Token previous() {
            return tokens.get(current - 1);
        }

        private Token consume(TokenType type, String message) {
            if (check(type)) return advance();
            throw new EvalException(message + " at position " + peek().position());
        }
    }

    // ==================== Evaluator ====================

    private Datum evaluateExpr(Expr expr, EvaluationContext context) {
        return switch (expr) {
            case LiteralExpr e -> e.value();
            case VariableExpr e -> context.lookupVariable(e.name());
            case PropertyExpr e -> evaluateProperty(e, context);
            case UnaryExpr e -> evaluateUnary(e, context);
            case BinaryExpr e -> evaluateBinary(e, context);
        };
    }

    private Datum evaluateProperty(PropertyExpr expr, EvaluationContext context) {
        Datum object = evaluateExpr(expr.object(), context);

        // Handle PropList property access
        if (object instanceof Datum.PropList propList) {
            Datum value = propList.properties().get(expr.property());
            return value != null ? value : Datum.VOID;
        }

        // Handle ScriptInstance property access
        if (object instanceof Datum.ScriptInstance instance) {
            Datum value = instance.properties().get(expr.property());
            return value != null ? value : Datum.VOID;
        }

        // For other types, we can't access properties in this simple evaluator
        throw new EvalException("Cannot access property '" + expr.property() + "' on " + object.typeName());
    }

    private Datum evaluateUnary(UnaryExpr expr, EvaluationContext context) {
        Datum operand = evaluateExpr(expr.operand(), context);

        return switch (expr.operator()) {
            case "-" -> {
                if (operand instanceof Datum.Int i) yield Datum.of(-i.value());
                if (operand instanceof Datum.Float f) yield Datum.of(-f.value());
                throw new EvalException("Cannot negate " + operand.typeName());
            }
            case "not" -> operand.isTruthy() ? Datum.FALSE : Datum.TRUE;
            default -> throw new EvalException("Unknown operator: " + expr.operator());
        };
    }

    private Datum evaluateBinary(BinaryExpr expr, EvaluationContext context) {
        // Short-circuit evaluation for logical operators
        if (expr.operator().equals("and")) {
            Datum left = evaluateExpr(expr.left(), context);
            if (!left.isTruthy()) return Datum.FALSE;
            Datum right = evaluateExpr(expr.right(), context);
            return right.isTruthy() ? Datum.TRUE : Datum.FALSE;
        }

        if (expr.operator().equals("or")) {
            Datum left = evaluateExpr(expr.left(), context);
            if (left.isTruthy()) return Datum.TRUE;
            Datum right = evaluateExpr(expr.right(), context);
            return right.isTruthy() ? Datum.TRUE : Datum.FALSE;
        }

        Datum left = evaluateExpr(expr.left(), context);
        Datum right = evaluateExpr(expr.right(), context);

        return switch (expr.operator()) {
            case "+" -> evaluateAdd(left, right);
            case "-" -> evaluateSubtract(left, right);
            case "*" -> evaluateMultiply(left, right);
            case "/" -> evaluateDivide(left, right);
            case "mod" -> evaluateMod(left, right);
            case "<" -> compareLessThan(left, right) ? Datum.TRUE : Datum.FALSE;
            case "<=" -> compareLessThanOrEqual(left, right) ? Datum.TRUE : Datum.FALSE;
            case ">" -> !compareLessThanOrEqual(left, right) ? Datum.TRUE : Datum.FALSE;
            case ">=" -> !compareLessThan(left, right) ? Datum.TRUE : Datum.FALSE;
            case "=" -> compareEqual(left, right) ? Datum.TRUE : Datum.FALSE;
            case "<>" -> !compareEqual(left, right) ? Datum.TRUE : Datum.FALSE;
            default -> throw new EvalException("Unknown operator: " + expr.operator());
        };
    }

    private Datum evaluateAdd(Datum left, Datum right) {
        // String concatenation
        if (left instanceof Datum.Str || right instanceof Datum.Str) {
            return Datum.of(left.toStr() + right.toStr());
        }

        // Numeric addition
        if (left instanceof Datum.Float || right instanceof Datum.Float) {
            return Datum.of(left.toDouble() + right.toDouble());
        }
        return Datum.of(left.toInt() + right.toInt());
    }

    private Datum evaluateSubtract(Datum left, Datum right) {
        if (left instanceof Datum.Float || right instanceof Datum.Float) {
            return Datum.of(left.toDouble() - right.toDouble());
        }
        return Datum.of(left.toInt() - right.toInt());
    }

    private Datum evaluateMultiply(Datum left, Datum right) {
        if (left instanceof Datum.Float || right instanceof Datum.Float) {
            return Datum.of(left.toDouble() * right.toDouble());
        }
        return Datum.of(left.toInt() * right.toInt());
    }

    private Datum evaluateDivide(Datum left, Datum right) {
        double r = right.toDouble();
        if (r == 0) {
            throw new EvalException("Division by zero");
        }
        return Datum.of(left.toDouble() / r);
    }

    private Datum evaluateMod(Datum left, Datum right) {
        int r = right.toInt();
        if (r == 0) {
            throw new EvalException("Modulo by zero");
        }
        return Datum.of(left.toInt() % r);
    }

    private boolean compareLessThan(Datum left, Datum right) {
        if (left instanceof Datum.Str l && right instanceof Datum.Str r) {
            return l.value().compareTo(r.value()) < 0;
        }
        return left.toDouble() < right.toDouble();
    }

    private boolean compareLessThanOrEqual(Datum left, Datum right) {
        if (left instanceof Datum.Str l && right instanceof Datum.Str r) {
            return l.value().compareTo(r.value()) <= 0;
        }
        return left.toDouble() <= right.toDouble();
    }

    private boolean compareEqual(Datum left, Datum right) {
        // Same type comparison
        if (left.getClass() == right.getClass()) {
            return left.equals(right);
        }

        // Numeric comparison (int/float interop)
        if (left.isNumber() && right.isNumber()) {
            return left.toDouble() == right.toDouble();
        }

        // String comparison
        if (left instanceof Datum.Str || right instanceof Datum.Str) {
            return left.toStr().equals(right.toStr());
        }

        return false;
    }

    // ==================== Exceptions ====================

    private static class EvalException extends RuntimeException {
        EvalException(String message) {
            super(message);
        }
    }
}
