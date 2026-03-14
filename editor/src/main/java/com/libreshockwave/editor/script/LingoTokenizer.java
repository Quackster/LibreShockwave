package com.libreshockwave.editor.script;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple tokenizer for Lingo source code.
 * Scans source text into tokens for syntax highlighting.
 */
public class LingoTokenizer {

    public enum TokenType {
        KEYWORD, COMMAND, FUNCTION, EVENT,
        STRING, NUMBER, COMMENT, SYMBOL,
        IDENTIFIER, OPERATOR, WHITESPACE, NEWLINE
    }

    public record Token(TokenType type, int start, int end, String text) {}

    public static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int len = source.length();

        while (i < len) {
            char c = source.charAt(i);

            // Newline
            if (c == '\n') {
                tokens.add(new Token(TokenType.NEWLINE, i, i + 1, "\n"));
                i++;
                continue;
            }

            // Whitespace
            if (Character.isWhitespace(c)) {
                int start = i;
                while (i < len && source.charAt(i) != '\n' && Character.isWhitespace(source.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(TokenType.WHITESPACE, start, i, source.substring(start, i)));
                continue;
            }

            // Comment (-- to end of line)
            if (c == '-' && i + 1 < len && source.charAt(i + 1) == '-') {
                int start = i;
                while (i < len && source.charAt(i) != '\n') {
                    i++;
                }
                tokens.add(new Token(TokenType.COMMENT, start, i, source.substring(start, i)));
                continue;
            }

            // String literal
            if (c == '"') {
                int start = i;
                i++;
                while (i < len && source.charAt(i) != '"' && source.charAt(i) != '\n') {
                    i++;
                }
                if (i < len && source.charAt(i) == '"') i++;
                tokens.add(new Token(TokenType.STRING, start, i, source.substring(start, i)));
                continue;
            }

            // Symbol (#word)
            if (c == '#') {
                int start = i;
                i++;
                while (i < len && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(TokenType.SYMBOL, start, i, source.substring(start, i)));
                continue;
            }

            // Number
            if (Character.isDigit(c) || (c == '.' && i + 1 < len && Character.isDigit(source.charAt(i + 1)))) {
                int start = i;
                while (i < len && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(TokenType.NUMBER, start, i, source.substring(start, i)));
                continue;
            }

            // Identifier or keyword
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < len && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) {
                    i++;
                }
                String word = source.substring(start, i);
                String lower = word.toLowerCase();

                TokenType type;
                if (LingoKeywords.KEYWORDS.contains(word) || LingoKeywords.KEYWORDS.contains(lower)) {
                    type = TokenType.KEYWORD;
                } else if (LingoKeywords.COMMANDS.contains(lower)) {
                    type = TokenType.COMMAND;
                } else if (LingoKeywords.FUNCTIONS.contains(lower)) {
                    type = TokenType.FUNCTION;
                } else if (LingoKeywords.EVENTS.contains(lower)) {
                    type = TokenType.EVENT;
                } else {
                    type = TokenType.IDENTIFIER;
                }

                tokens.add(new Token(type, start, i, word));
                continue;
            }

            // Operator or other single character
            tokens.add(new Token(TokenType.OPERATOR, i, i + 1, String.valueOf(c)));
            i++;
        }

        return tokens;
    }
}
