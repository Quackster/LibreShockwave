package com.libreshockwave.vm;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;


/**
 * Debug output formatting for the Lingo VM.
 * Provides human-readable representations of VM state, datums, and instructions.
 */
public class DebugFormatter {

    private final LingoVM vm;

    public DebugFormatter(LingoVM vm) {
        this.vm = vm;
    }

    /**
     * Format a scope's stack state for debug output.
     */
    public String formatScopeStack(Scope scope) {
        if (scope == null || scope.stackSize() == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        // Iterate through the scope's stack by popping and repushing
        java.util.List<Datum> items = new java.util.ArrayList<>();
        int size = scope.stackSize();
        for (int i = 0; i < size; i++) {
            items.add(0, scope.pop());
        }
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatDatum(items.get(i)));
            scope.push(items.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Format the current stack state for debug output.
     * @deprecated Use formatScopeStack(Scope) instead
     */
    @Deprecated
    public String formatStack(java.util.Deque<Datum> stack) {
        if (stack.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        int count = 0;
        for (Datum d : stack) {
            if (count > 0) sb.append(", ");
            sb.append(formatDatum(d));
            count++;
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Format a Datum for debug output.
     */
    public String formatDatum(Datum d) {
        return formatDatum(d, true);
    }

    /**
     * Format a Datum for debug output with optional detail level.
     */
    public String formatDatum(Datum d, boolean showDetails) {
        if (d == null) return "null";
        if (d.isVoid()) return "VOID";
        if (d.isInt()) return String.valueOf(d.intValue());
        if (d.isFloat()) return String.format("%.2f", d.floatValue());
        if (d.isString()) {
            String s = d.stringValue();
            if (s.length() > 30) s = s.substring(0, 30) + "...";
            return "\"" + s + "\"";
        }
        if (d.isSymbol()) return "#" + d.stringValue();

        // Member reference
        if (d instanceof Datum.CastMemberRef ref) {
            String name = getMemberName(ref.castLib(), ref.castMember());
            if (name != null && !name.isEmpty()) {
                return "member(\"" + name + "\", " + ref.castLib() + ")";
            }
            return "member(" + ref.castLib() + ":" + ref.castMember() + ")";
        }

        // Cast lib reference
        if (d instanceof Datum.CastLibRef ref) {
            String name = getCastName(ref.castLib());
            if (name != null && !name.isEmpty()) {
                return "castLib(\"" + name + "\")";
            }
            return "castLib(" + ref.castLib() + ")";
        }

        // Sprite reference
        if (d instanceof Datum.SpriteRef ref) {
            return "sprite(" + ref.channel() + ")";
        }

        // Script reference
        if (d instanceof Datum.ScriptRef ref) {
            String name = getMemberName(ref.memberRef().castLib(), ref.memberRef().castMember());
            if (name != null && !name.isEmpty()) {
                return "script(\"" + name + "\")";
            }
            return "script(" + ref.memberRef().castLib() + ":" + ref.memberRef().castMember() + ")";
        }

        // Lists
        if (d instanceof Datum.DList l) {
            return formatList(l, showDetails);
        }

        // PropLists
        if (d instanceof Datum.PropList p) {
            return formatPropList(p, showDetails);
        }

        // ArgLists
        if (d instanceof Datum.ArgList a) {
            return formatArgList(a.args(), "args", showDetails);
        }
        if (d instanceof Datum.ArgListNoRet a) {
            return formatArgList(a.args(), "argsNoRet", showDetails);
        }

        // Geometric types
        if (d instanceof Datum.IntPoint pt) {
            return "point(" + pt.x() + ", " + pt.y() + ")";
        }
        if (d instanceof Datum.IntRect r) {
            return "rect(" + r.left() + ", " + r.top() + ", " + r.right() + ", " + r.bottom() + ")";
        }
        if (d instanceof Datum.Vector3 v) {
            return "vector(" + v.x() + ", " + v.y() + ", " + v.z() + ")";
        }
        if (d instanceof Datum.ColorRef c) {
            return "color(" + c.r() + ", " + c.g() + ", " + c.b() + ")";
        }

        return d.getClass().getSimpleName();
    }

    private String formatList(Datum.DList l, boolean showDetails) {
        if (!showDetails || l.count() == 0) return "[]";
        if (l.count() <= 3) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.count(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatDatum(l.getAt(i + 1), false));
            }
            sb.append("]");
            return sb.toString();
        }
        return "[" + formatDatum(l.getAt(1), false) + ", " + formatDatum(l.getAt(2), false) + ", ...(" + l.count() + ")]";
    }

    private String formatPropList(Datum.PropList p, boolean showDetails) {
        if (!showDetails || p.count() == 0) return "[:]";
        StringBuilder sb = new StringBuilder("[");
        int shown = 0;
        for (var entry : p.properties().entrySet()) {
            if (shown > 0) sb.append(", ");
            if (shown >= 2) {
                sb.append("...(").append(p.count()).append(")");
                break;
            }
            sb.append(formatDatum(entry.getKey(), false)).append(": ").append(formatDatum(entry.getValue(), false));
            shown++;
        }
        sb.append("]");
        return sb.toString();
    }

    private String formatArgList(java.util.List<Datum> args, String prefix, boolean showDetails) {
        if (args.isEmpty()) return prefix + "()";
        if (args.size() <= 3) {
            StringBuilder sb = new StringBuilder(prefix + "(");
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatDatum(args.get(i), false));
            }
            sb.append(")");
            return sb.toString();
        }
        return prefix + "(" + formatDatum(args.get(0), false) + ", ...(" + args.size() + "))";
    }

    /**
     * Format an instruction for debug output.
     */
    public String formatInstruction(ScriptChunk.Handler.Instruction instr, ScriptResolver resolver) {
        Opcode op = instr.opcode();
        int arg = instr.argument();
        String argStr = "";

        // Format argument based on opcode type
        if (op == Opcode.PUSH_SYMB || op == Opcode.EXT_CALL || op == Opcode.LOCAL_CALL ||
            op == Opcode.GET_PROP || op == Opcode.SET_PROP || op == Opcode.GET_GLOBAL ||
            op == Opcode.SET_GLOBAL || op == Opcode.GET_MOVIE_PROP || op == Opcode.SET_MOVIE_PROP ||
            op == Opcode.GET_OBJ_PROP || op == Opcode.SET_OBJ_PROP || op == Opcode.GET_CHAINED_PROP ||
            op == Opcode.THE_BUILTIN || op == Opcode.OBJ_CALL || op == Opcode.NEW_OBJ) {
            argStr = " '" + resolver.getName(arg) + "'";
        } else if (arg != 0) {
            argStr = " " + arg;
        }

        return op.name() + argStr;
    }

    /**
     * Format handler arguments for debug output.
     */
    public String formatArgs(Datum[] args) {
        if (args.length == 0) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatDatum(args[i]));
        }
        return sb.toString();
    }

    private String getMemberName(int castLib, int memberNum) {
        CastManager castManager = vm.getCastManager();
        if (castManager == null) return null;
        CastLib cast = castManager.getCast(castLib);
        if (cast == null) return null;
        var member = cast.getMember(memberNum);
        if (member == null) return null;
        return member.name();
    }

    private String getCastName(int castLib) {
        CastManager castManager = vm.getCastManager();
        if (castManager == null) return null;
        CastLib cast = castManager.getCast(castLib);
        if (cast == null) return null;
        return cast.getName();
    }
}
