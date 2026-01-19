package com.libreshockwave.vm;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptNamesChunk;
import com.libreshockwave.lingo.Datum;
import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;

import java.util.Deque;

/**
 * Script and handler resolution for the Lingo VM.
 * Handles finding scripts by name/reference and resolving handler names.
 */
public class ScriptResolver {

    private final DirectorFile file;
    private final CastManager castManager;
    private final Deque<Scope> callStack;

    public ScriptResolver(DirectorFile file, CastManager castManager, Deque<Scope> callStack) {
        this.file = file;
        this.castManager = castManager;
        this.callStack = callStack;
    }

    /**
     * Result of finding a handler - contains script, handler, names chunk, and source cast.
     */
    public record HandlerLocation(ScriptChunk script, ScriptChunk.Handler handler,
                                   ScriptNamesChunk names, CastLib sourceCast) {}

    /**
     * Get a name by ID from the appropriate names chunk.
     * Uses the current executing script's names chunk first, then falls back to main file.
     */
    public String getName(int nameId) {
        // First try current scope's script names
        if (!callStack.isEmpty()) {
            Scope currentScope = callStack.peek();
            ScriptChunk currentScript = currentScope.getScript();
            if (currentScript != null) {
                CastLib scriptCast = findCastForScript(currentScript);
                if (scriptCast != null && scriptCast.getState() == CastLib.State.LOADED) {
                    ScriptNamesChunk castNames = scriptCast.getScriptNames();
                    if (castNames != null && nameId >= 0 && nameId < castNames.names().size()) {
                        return castNames.getName(nameId);
                    }
                }
            }
        }

        // Fall back to main file's names chunk
        if (file != null) {
            ScriptNamesChunk names = file.getScriptNames();
            if (names != null && nameId >= 0 && nameId < names.names().size()) {
                return names.getName(nameId);
            }
        }

        // Then search in external cast scripts
        if (castManager != null) {
            for (CastLib cast : castManager.getCasts()) {
                if (cast.getState() == CastLib.State.LOADED) {
                    ScriptNamesChunk names = cast.getDirectorFile().getScriptNames();
                    if (names != null && nameId >= 0 && nameId < names.names().size()) {
                        return names.getName(nameId);
                    }
                }
            }
        }

        return "<name:" + nameId + ">";
    }

    /**
     * Find a handler by name across all scripts.
     */
    public HandlerLocation findHandler(String name) {
        // Search in main movie's scripts first
        if (file != null) {
            ScriptNamesChunk names = file.getScriptNames();
            if (names != null) {
                int nameId = names.findName(name);
                if (nameId >= 0) {
                    for (ScriptChunk script : file.getScripts()) {
                        for (ScriptChunk.Handler handler : script.handlers()) {
                            if (handler.nameId() == nameId) {
                                CastLib primaryCast = castManager != null ? castManager.getCast(1) : null;
                                return new HandlerLocation(script, handler, names, primaryCast);
                            }
                        }
                    }
                }
            }
        }

        // Then search in external cast scripts
        if (castManager != null) {
            for (CastLib cast : castManager.getCasts()) {
                if (cast.getState() == CastLib.State.LOADED) {
                    ScriptNamesChunk castNames = cast.getScriptNames();
                    if (castNames != null) {
                        int nameId = castNames.findName(name);
                        if (nameId >= 0) {
                            for (ScriptChunk script : cast.getAllScripts()) {
                                for (ScriptChunk.Handler handler : script.handlers()) {
                                    if (handler.nameId() == nameId) {
                                        return new HandlerLocation(script, handler, castNames, cast);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;

    }

    /**
     * Find a script by its cast member reference.
     */
    public ScriptChunk findScriptByCastRef(Datum.CastMemberRef ref) {
        if (ref == null) return null;

        int castLib = ref.castLib();
        int memberId = ref.memberNum();

        // Search in main file
        if (file != null && castLib <= 1) {
            for (ScriptChunk script : file.getScripts()) {
                if (script.id() == memberId) {
                    return script;
                }
            }
        }

        // Search in cast library
        if (castManager != null) {
            CastLib cast = castManager.getCast(castLib);
            if (cast != null && cast.getState() == CastLib.State.LOADED) {
                return cast.getScript(memberId);
            }
        }

        return null;
    }

    /**
     * Find the script containing a given handler.
     */
    public ScriptChunk findScriptForHandler(ScriptChunk.Handler target) {
        if (file != null) {
            for (ScriptChunk script : file.getScripts()) {
                if (script.handlers().contains(target)) {
                    return script;
                }
            }
        }

        if (castManager != null) {
            for (CastLib cast : castManager.getCasts()) {
                if (cast.getState() == CastLib.State.LOADED) {
                    for (ScriptChunk script : cast.getAllScripts()) {
                        if (script.handlers().contains(target)) {
                            return script;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Find which cast library contains a given script.
     */
    public CastLib findCastForScript(ScriptChunk script) {
        if (script == null || castManager == null) return null;

        // Check if script is in the main file's scripts
        if (file != null) {
            for (ScriptChunk s : file.getScripts()) {
                if (s == script) {
                    return castManager.getCast(1);
                }
            }
        }

        // Check all cast libraries
        for (CastLib cast : castManager.getCasts()) {
            if (cast.getState() == CastLib.State.LOADED) {
                for (ScriptChunk s : cast.getAllScripts()) {
                    if (s == script) {
                        return cast;
                    }
                }
            }
        }

        return null;
    }


    /**
     * Find instruction index at a given bytecode offset.
     */
    public int findInstructionAtOffset(ScriptChunk.Handler handler, int offset) {
        for (int i = 0; i < handler.instructions().size(); i++) {
            if (handler.instructions().get(i).offset() == offset) {
                return i;
            }
        }
        return handler.instructions().size();
    }

    /**
     * Get the variable multiplier for bytecode argument decoding.
     */
    public int getVariableMultiplier() {
        if (callStack.isEmpty()) return 1;

        Scope currentScope = callStack.peek();
        ScriptChunk currentScript = currentScope.getScript();
        if (currentScript == null) return 1;

        CastLib scriptCast = findCastForScript(currentScript);
        if (scriptCast == null) {
            int version = file != null && file.getConfig() != null ? file.getConfig().directorVersion() : 0;
            return version >= 500 ? 8 : 6;
        }

        if (scriptCast.isCapitalX()) {
            return 1;
        }
        int version = scriptCast.getDirVersion();
        return version >= 500 ? 8 : 6;
    }
}
