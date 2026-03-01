package com.libreshockwave.vm.builtin;

import com.libreshockwave.vm.Datum;
import com.libreshockwave.vm.LingoVM;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Cast library builtin functions for Lingo.
 * Similar to dirplayer-rs player/handlers/cast.rs.
 *
 * Provides:
 * - castLib(number) or castLib("name") - get cast library reference
 * - member(number) or member("name") - get cast member reference
 * - member(number, castLib) - get member from specific cast library
 */
public final class CastLibBuiltins {

    private CastLibBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("castlib", CastLibBuiltins::castLib);
        builtins.put("member", CastLibBuiltins::member);
        builtins.put("field", CastLibBuiltins::field);
    }

    /**
     * castLib(number) or castLib("name")
     * Returns a cast library reference.
     */
    private static Datum castLib(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.VOID;
        }

        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            return Datum.VOID;
        }

        Datum arg = args.get(0);
        int castLibNumber;

        if (arg.isInt() || arg.isFloat()) {
            castLibNumber = provider.getCastLibByNumber(arg.toInt());
        } else if (arg.isString()) {
            castLibNumber = provider.getCastLibByName(arg.toStr());
        } else {
            return Datum.VOID;
        }

        if (castLibNumber < 0) {
            return Datum.VOID;
        }

        return new Datum.CastLibRef(castLibNumber);
    }

    /**
     * member(number) - get member from default cast
     * member("name") - find member by name in all casts
     * member(number, castLib) - get member from specific cast
     * member("name", castLib) - find member by name in specific cast
     */
    private static Datum member(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.VOID;
        }

        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            // Return a placeholder CastMemberRef
            return new Datum.CastMemberRef(1, args.get(0).toInt());
        }

        Datum memberArg = args.get(0);
        int castLibNumber = 0; // 0 = search all or use default

        // Check for castLib argument
        if (args.size() > 1) {
            Datum castArg = args.get(1);
            if (castArg instanceof Datum.CastLibRef clr) {
                castLibNumber = clr.castLibNumber();
            } else if (castArg.isInt()) {
                castLibNumber = castArg.toInt();
            }
        }

        // Get member by number or name
        if (memberArg.isInt() || memberArg.isFloat()) {
            int memberNumber = memberArg.toInt();
            if (castLibNumber > 0) {
                return provider.getMember(castLibNumber, memberNumber);
            }

            // Check if this is a slot number (castLib << 16 | memberNum) from member.number
            int encodedCast = (memberNumber >> 16) & 0xFFFF;
            int encodedMember = memberNumber & 0xFFFF;
            if (encodedCast > 0 && encodedMember > 0) {
                // Decode slot number: direct lookup in the encoded cast lib
                if (provider.memberExists(encodedCast, encodedMember)) {
                    return provider.getMember(encodedCast, encodedMember);
                }
            }

            // No cast lib specified — search all casts for the member
            int totalCasts = provider.getCastLibCount();
            for (int i = 1; i <= totalCasts; i++) {
                if (provider.memberExists(i, memberNumber)) {
                    return provider.getMember(i, memberNumber);
                }
            }
            // Not found in any cast — return ref in cast 1 (Director fallback)
            return provider.getMember(1, memberNumber);
        } else if (memberArg.isString()) {
            return provider.getMemberByName(castLibNumber, memberArg.toStr());
        }

        return Datum.VOID;
    }

    /**
     * field(nameOrNum) or field(nameOrNum, castLib)
     * Returns the text content of a field/text cast member.
     * In Director, field("name") is equivalent to member("name").text
     */
    private static Datum field(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.EMPTY_STRING;
        }

        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            return Datum.EMPTY_STRING;
        }

        Datum fieldArg = args.get(0);
        int castId = 0; // 0 = search all casts

        if (args.size() > 1) {
            Datum castArg = args.get(1);
            if (castArg instanceof Datum.CastLibRef clr) {
                castId = clr.castLibNumber();
            } else if (castArg.isInt()) {
                castId = castArg.toInt();
            }
        }

        Object identifier = fieldArg instanceof Datum.Str s ? s.value()
                : fieldArg instanceof Datum.Int i ? i.value()
                : fieldArg.toStr();

        String fieldValue = provider.getFieldValue(identifier, castId);
        return Datum.of(fieldValue);
    }

}
