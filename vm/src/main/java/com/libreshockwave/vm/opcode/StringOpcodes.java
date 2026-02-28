package com.libreshockwave.vm.opcode;

import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.lingo.StringChunkType;
import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.builtin.CastLibProvider;
import com.libreshockwave.vm.builtin.MoviePropertyProvider;
import com.libreshockwave.vm.util.StringChunkUtils;

import java.util.List;
import java.util.Map;

/**
 * String operation opcodes.
 */
public final class StringOpcodes {

    private StringOpcodes() {}

    public static void register(Map<Opcode, OpcodeHandler> handlers) {
        handlers.put(Opcode.JOIN_STR, StringOpcodes::joinStr);
        handlers.put(Opcode.JOIN_PAD_STR, StringOpcodes::joinPadStr);
        handlers.put(Opcode.CONTAINS_STR, StringOpcodes::containsStr);
        handlers.put(Opcode.CONTAINS_0_STR, StringOpcodes::contains0Str);
        handlers.put(Opcode.GET_CHUNK, StringOpcodes::getChunk);
        handlers.put(Opcode.PUT, StringOpcodes::put);
        handlers.put(Opcode.PUT_CHUNK, StringOpcodes::putChunk);
        handlers.put(Opcode.DELETE_CHUNK, StringOpcodes::deleteChunk);
    }

    private static boolean joinStr(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();
        ctx.push(Datum.of(a.toStr() + b.toStr()));
        return true;
    }

    private static boolean joinPadStr(ExecutionContext ctx) {
        Datum b = ctx.pop();
        Datum a = ctx.pop();
        ctx.push(Datum.of(a.toStr() + " " + b.toStr()));
        return true;
    }

    private static boolean containsStr(ExecutionContext ctx) {
        Datum needle = ctx.pop();
        Datum haystack = ctx.pop();
        boolean contains = haystack.toStr().contains(needle.toStr());
        ctx.push(contains ? Datum.TRUE : Datum.FALSE);
        return true;
    }

    /**
     * CONTAINS_0_STR (0x16) - "starts with" check (case-insensitive).
     * Stack: [..., haystack, needle] -> [..., result]
     */
    private static boolean contains0Str(ExecutionContext ctx) {
        Datum needle = ctx.pop();
        Datum haystack = ctx.pop();

        boolean result;
        if (haystack.isVoid()) {
            result = false;
        } else {
            result = haystack.toStr().toLowerCase().startsWith(needle.toStr().toLowerCase());
        }

        ctx.push(result ? Datum.TRUE : Datum.FALSE);
        return true;
    }

    /**
     * GET_CHUNK (0x17) - Extract a string chunk.
     * Pops string, then 8 chunk parameters, extracts the chunk.
     * Stack: [..., firstChar, lastChar, firstWord, lastWord, firstItem, lastItem, firstLine, lastLine, string]
     *     -> [..., chunkValue]
     */
    private static boolean getChunk(ExecutionContext ctx) {
        Datum stringDatum = ctx.pop();
        String str = stringDatum.toStr();

        // Pop 8 chunk parameters (popped in reverse order from stack)
        int lastLine = ctx.pop().toInt();
        int firstLine = ctx.pop().toInt();
        int lastItem = ctx.pop().toInt();
        int firstItem = ctx.pop().toInt();
        int lastWord = ctx.pop().toInt();
        int firstWord = ctx.pop().toInt();
        int lastChar = ctx.pop().toInt();
        int firstChar = ctx.pop().toInt();

        char itemDelimiter = getItemDelimiter();

        // Apply chunks sequentially (largest to smallest granularity)
        String result = str;

        if (firstLine != 0 || lastLine != 0) {
            result = resolveChunkRange(result, StringChunkType.LINE, firstLine, lastLine, itemDelimiter);
        }
        if (firstItem != 0 || lastItem != 0) {
            result = resolveChunkRange(result, StringChunkType.ITEM, firstItem, lastItem, itemDelimiter);
        }
        if (firstWord != 0 || lastWord != 0) {
            result = resolveChunkRange(result, StringChunkType.WORD, firstWord, lastWord, itemDelimiter);
        }
        if (firstChar != 0 || lastChar != 0) {
            result = resolveChunkRange(result, StringChunkType.CHAR, firstChar, lastChar, itemDelimiter);
        }

        ctx.push(Datum.of(result));
        return true;
    }

    // Context variable types (matching dirplayer-rs ContextVars)
    private static final int VAR_TYPE_GLOBAL1 = 0x1;
    private static final int VAR_TYPE_GLOBAL2 = 0x2;
    private static final int VAR_TYPE_PROPERTY = 0x3;
    private static final int VAR_TYPE_ARG = 0x4;
    private static final int VAR_TYPE_LOCAL = 0x5;
    private static final int VAR_TYPE_FIELD = 0x6;

    /**
     * PUT (0x59) - Put a value into/before/after a variable.
     * Argument encoding: upper nibble = put type, lower nibble = var type.
     * Stack depends on var type.
     */
    private static boolean put(ExecutionContext ctx) {
        int arg = ctx.getArgument();
        int putType = (arg >> 4) & 0xF;  // 0=INTO, 1=BEFORE, 2=AFTER
        int varType = arg & 0xF;

        // Read context var args (pop ID from stack, and castId for fields)
        Datum castIdDatum = null;
        if (varType == VAR_TYPE_FIELD) {
            castIdDatum = ctx.pop();
        }
        Datum idDatum = ctx.pop();

        // Pop the value to put
        Datum value = ctx.pop();

        switch (putType) {
            case 0: // INTO
                setContextVar(ctx, varType, idDatum, castIdDatum, value);
                break;
            case 1: { // BEFORE
                Datum current = getContextVar(ctx, varType, idDatum, castIdDatum);
                String newStr = value.toStr() + current.toStr();
                setContextVar(ctx, varType, idDatum, castIdDatum, Datum.of(newStr));
                break;
            }
            case 2: { // AFTER
                Datum current = getContextVar(ctx, varType, idDatum, castIdDatum);
                String newStr = current.toStr() + value.toStr();
                setContextVar(ctx, varType, idDatum, castIdDatum, Datum.of(newStr));
                break;
            }
        }

        return true;
    }

    /**
     * PUT_CHUNK (0x5A) - Put a value into/before/after a string chunk of a variable.
     * Argument encoding: upper nibble = put type, lower nibble = var type.
     */
    private static boolean putChunk(ExecutionContext ctx) {
        int arg = ctx.getArgument();
        int putType = (arg >> 4) & 0xF;
        int varType = arg & 0xF;

        // Read context var args
        Datum castIdDatum = null;
        if (varType == VAR_TYPE_FIELD) {
            castIdDatum = ctx.pop();
        }
        Datum idDatum = ctx.pop();

        // Pop the value
        Datum value = ctx.pop();

        // Read chunk expression from stack
        ChunkExpr chunkExpr = readSingleChunkRef(ctx);

        // Get current string value
        Datum currentDatum = getContextVar(ctx, varType, idDatum, castIdDatum);
        String currentString = currentDatum.toStr();
        String valueString = value.toStr();
        char itemDelimiter = getItemDelimiter();

        // Apply put operation on the chunk
        String newString;
        switch (putType) {
            case 0: // INTO
                newString = stringByPuttingIntoChunk(currentString, chunkExpr, valueString, itemDelimiter);
                break;
            case 1: // BEFORE
                newString = stringByPuttingBeforeChunk(currentString, chunkExpr, valueString, itemDelimiter);
                break;
            case 2: // AFTER
                newString = stringByPuttingAfterChunk(currentString, chunkExpr, valueString, itemDelimiter);
                break;
            default:
                newString = currentString;
        }

        setContextVar(ctx, varType, idDatum, castIdDatum, Datum.of(newString));
        return true;
    }

    /**
     * DELETE_CHUNK (0x5B) - Delete a string chunk from a variable.
     * Argument is just the var type (no put type).
     */
    private static boolean deleteChunk(ExecutionContext ctx) {
        int varType = ctx.getArgument();

        // Read context var args
        Datum castIdDatum = null;
        if (varType == VAR_TYPE_FIELD) {
            castIdDatum = ctx.pop();
        }
        Datum idDatum = ctx.pop();

        // Read chunk expression
        ChunkExpr chunkExpr = readSingleChunkRef(ctx);

        // Get current string
        Datum currentDatum = getContextVar(ctx, varType, idDatum, castIdDatum);
        String currentString = currentDatum.toStr();
        char itemDelimiter = getItemDelimiter();

        // Delete the chunk
        String newString = stringByDeletingChunk(currentString, chunkExpr, itemDelimiter);
        setContextVar(ctx, varType, idDatum, castIdDatum, Datum.of(newString));

        return true;
    }

    // ==================== Helper methods ====================

    /**
     * Read a single chunk reference from the stack.
     * Pops 8 values: lastLine, firstLine, lastItem, firstItem, lastWord, firstWord, lastChar, firstChar.
     * Returns the first non-zero chunk specification.
     */
    private static ChunkExpr readSingleChunkRef(ExecutionContext ctx) {
        int lastLine = ctx.pop().toInt();
        int firstLine = ctx.pop().toInt();
        int lastItem = ctx.pop().toInt();
        int firstItem = ctx.pop().toInt();
        int lastWord = ctx.pop().toInt();
        int firstWord = ctx.pop().toInt();
        int lastChar = ctx.pop().toInt();
        int firstChar = ctx.pop().toInt();

        if (firstLine != 0 || lastLine != 0) {
            return new ChunkExpr(StringChunkType.LINE, firstLine, lastLine);
        } else if (firstItem != 0 || lastItem != 0) {
            return new ChunkExpr(StringChunkType.ITEM, firstItem, lastItem);
        } else if (firstWord != 0 || lastWord != 0) {
            return new ChunkExpr(StringChunkType.WORD, firstWord, lastWord);
        } else if (firstChar != 0 || lastChar != 0) {
            return new ChunkExpr(StringChunkType.CHAR, firstChar, lastChar);
        }

        // Default to char 1 to 1
        return new ChunkExpr(StringChunkType.CHAR, 1, 1);
    }

    private record ChunkExpr(StringChunkType type, int first, int last) {}

    /**
     * Resolve a chunk range from a string.
     */
    private static String resolveChunkRange(String str, StringChunkType chunkType,
                                            int first, int last, char itemDelimiter) {
        if (first == 0 && last == 0) return str;
        int effectiveLast = last == 0 ? first : last;
        if (first == effectiveLast) {
            return StringChunkUtils.getChunk(str, chunkType, first, itemDelimiter);
        }
        return StringChunkUtils.getChunkRange(str, chunkType, first, effectiveLast, itemDelimiter);
    }

    /**
     * Get the current item delimiter.
     */
    private static char getItemDelimiter() {
        MoviePropertyProvider provider = MoviePropertyProvider.getProvider();
        return provider != null ? provider.getItemDelimiter() : ',';
    }

    /**
     * Get a context variable value.
     */
    private static Datum getContextVar(ExecutionContext ctx, int varType,
                                       Datum idDatum, Datum castIdDatum) {
        int variableMultiplier = ctx.getVariableMultiplier();

        switch (varType) {
            case VAR_TYPE_LOCAL: {
                int localIndex = idDatum.toInt() / variableMultiplier;
                return ctx.getLocal(localIndex);
            }
            case VAR_TYPE_ARG: {
                int argIndex = idDatum.toInt() / variableMultiplier;
                return ctx.getParam(argIndex);
            }
            case VAR_TYPE_FIELD: {
                CastLibProvider provider = CastLibProvider.getProvider();
                if (provider != null) {
                    int castId = castIdDatum != null ? castIdDatum.toInt() : 0;
                    Object identifier = idDatum instanceof Datum.Str s ? s.value()
                            : idDatum instanceof Datum.Int i ? i.value()
                            : idDatum.toStr();
                    return Datum.of(provider.getFieldValue(identifier, castId));
                }
                return Datum.EMPTY_STRING;
            }
            case VAR_TYPE_GLOBAL1:
            case VAR_TYPE_GLOBAL2: {
                String name = ctx.resolveName(idDatum.toInt());
                return ctx.getGlobal(name);
            }
            case VAR_TYPE_PROPERTY: {
                // Property on current receiver
                Datum receiver = ctx.getReceiver();
                if (receiver instanceof Datum.ScriptInstance si) {
                    String propName = ctx.resolveName(idDatum.toInt());
                    Datum value = si.properties().get(propName);
                    return value != null ? value : Datum.VOID;
                }
                return Datum.VOID;
            }
            default: {
                System.err.println("[WARN] getContextVar: unsupported var type " + varType);
                return Datum.VOID;
            }
        }
    }

    /**
     * Set a context variable value.
     */
    private static void setContextVar(ExecutionContext ctx, int varType,
                                      Datum idDatum, Datum castIdDatum, Datum value) {
        int variableMultiplier = ctx.getVariableMultiplier();

        switch (varType) {
            case VAR_TYPE_LOCAL: {
                int localIndex = idDatum.toInt() / variableMultiplier;
                ctx.setLocal(localIndex, value);
                break;
            }
            case VAR_TYPE_ARG: {
                int argIndex = idDatum.toInt() / variableMultiplier;
                ctx.setParam(argIndex, value);
                break;
            }
            case VAR_TYPE_FIELD: {
                // Field text setting - limited support
                System.err.println("[WARN] setContextVar: field set not fully implemented");
                break;
            }
            case VAR_TYPE_GLOBAL1:
            case VAR_TYPE_GLOBAL2: {
                String name = ctx.resolveName(idDatum.toInt());
                ctx.setGlobal(name, value);
                break;
            }
            case VAR_TYPE_PROPERTY: {
                Datum receiver = ctx.getReceiver();
                if (receiver instanceof Datum.ScriptInstance si) {
                    String propName = ctx.resolveName(idDatum.toInt());
                    si.properties().put(propName, value);
                }
                break;
            }
            default:
                System.err.println("[WARN] setContextVar: unsupported var type " + varType);
        }
    }

    // ==================== String chunk manipulation ====================

    /**
     * Replace a chunk in a string with a new value (put X into chunk Y of Z).
     */
    private static String stringByPuttingIntoChunk(String str, ChunkExpr chunk,
                                                    String value, char itemDelimiter) {
        int[] range = getChunkByteRange(str, chunk, itemDelimiter);
        if (range == null) return str;
        return str.substring(0, range[0]) + value + str.substring(range[1]);
    }

    /**
     * Insert a value before a chunk (put X before chunk Y of Z).
     */
    private static String stringByPuttingBeforeChunk(String str, ChunkExpr chunk,
                                                      String value, char itemDelimiter) {
        int[] range = getChunkByteRange(str, chunk, itemDelimiter);
        if (range == null) return str;
        return str.substring(0, range[0]) + value + str.substring(range[0]);
    }

    /**
     * Insert a value after a chunk (put X after chunk Y of Z).
     */
    private static String stringByPuttingAfterChunk(String str, ChunkExpr chunk,
                                                     String value, char itemDelimiter) {
        int[] range = getChunkByteRange(str, chunk, itemDelimiter);
        if (range == null) return str;
        return str.substring(0, range[1]) + value + str.substring(range[1]);
    }

    /**
     * Delete a chunk from a string.
     */
    private static String stringByDeletingChunk(String str, ChunkExpr chunk, char itemDelimiter) {
        int[] range = getChunkByteRange(str, chunk, itemDelimiter);
        if (range == null) return str;

        // When deleting, also remove the delimiter if present
        int deleteStart = range[0];
        int deleteEnd = range[1];

        // Try to consume trailing delimiter
        if (deleteEnd < str.length()) {
            String delim = getChunkDelimiter(chunk.type(), itemDelimiter);
            if (delim.length() > 0 && str.startsWith(delim, deleteEnd)) {
                deleteEnd += delim.length();
            }
        } else if (deleteStart > 0) {
            // If at end, try to consume leading delimiter
            String delim = getChunkDelimiter(chunk.type(), itemDelimiter);
            if (delim.length() > 0 && str.substring(0, deleteStart).endsWith(delim)) {
                deleteStart -= delim.length();
            }
        }

        return str.substring(0, deleteStart) + str.substring(deleteEnd);
    }

    /**
     * Get the byte range [start, end) of a chunk in a string.
     * Returns null if the chunk is out of range.
     */
    private static int[] getChunkByteRange(String str, ChunkExpr chunk, char itemDelimiter) {
        List<String> chunks = StringChunkUtils.splitIntoChunks(str, chunk.type(), itemDelimiter);
        if (chunks.isEmpty()) return null;

        int first = chunk.first();
        int last = chunk.last() == 0 ? first : chunk.last();

        // Clamp to valid range
        if (first < 1) first = 1;
        if (first > chunks.size()) return null;
        if (last > chunks.size()) last = chunks.size();

        // Calculate byte offset
        String delimiter = getChunkDelimiter(chunk.type(), itemDelimiter);
        int start = 0;
        for (int i = 0; i < first - 1; i++) {
            start += chunks.get(i).length();
            if (i < chunks.size() - 1) {
                start += delimiter.length();
            }
        }

        int end = start;
        for (int i = first - 1; i < last; i++) {
            end += chunks.get(i).length();
            if (i < last - 1 && i < chunks.size() - 1) {
                end += delimiter.length();
            }
        }

        return new int[] { start, end };
    }

    /**
     * Get the delimiter string for a chunk type.
     */
    private static String getChunkDelimiter(StringChunkType type, char itemDelimiter) {
        return switch (type) {
            case CHAR -> "";
            case WORD -> " ";
            case LINE -> "\r\n";
            case ITEM -> String.valueOf(itemDelimiter);
        };
    }
}
