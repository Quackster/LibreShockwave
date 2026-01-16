package com.libreshockwave;

import com.libreshockwave.chunks.*;
import com.libreshockwave.format.AfterburnerReader;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;
import com.libreshockwave.lingo.Opcode;

import com.libreshockwave.player.CastLib;
import com.libreshockwave.player.CastManager;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Main entry point for reading Director/Shockwave files.
 * Supports .dir, .dxr, .dcr, and .cst files.
 */
public class DirectorFile {

    private final ByteOrder endian;
    private final boolean afterburner;
    private final int version;
    private final ChunkType movieType;
    private String basePath;  // Base path for resolving external casts

    private ConfigChunk config;
    private KeyTableChunk keyTable;
    private CastListChunk castList;
    private ScriptContextChunk scriptContext;
    private ScriptNamesChunk scriptNames;  // Default/primary names chunk
    private final Map<Integer, ScriptNamesChunk> scriptNamesById = new HashMap<>();

    private final Map<Integer, Chunk> chunks = new HashMap<>();
    private final Map<Integer, ChunkInfo> chunkInfo = new HashMap<>();
    private final List<CastChunk> casts = new ArrayList<>();
    private final List<CastMemberChunk> castMembers = new ArrayList<>();
    private final List<ScriptChunk> scripts = new ArrayList<>();

    public record ChunkInfo(
        int id,
        int fourcc,
        int offset,
        int length,
        int uncompressedLength
    ) {
        public ChunkType type() {
            return ChunkType.fromFourCC(fourcc);
        }
    }

    private DirectorFile(ByteOrder endian, boolean afterburner, int version, ChunkType movieType) {
        this.endian = endian;
        this.afterburner = afterburner;
        this.version = version;
        this.movieType = movieType;
        this.basePath = "";
    }

    // Getters

    public ByteOrder getEndian() { return endian; }
    public boolean isAfterburner() { return afterburner; }
    public int getVersion() { return version; }
    public ChunkType getMovieType() { return movieType; }
    public ConfigChunk getConfig() { return config; }
    public KeyTableChunk getKeyTable() { return keyTable; }
    public CastListChunk getCastList() { return castList; }
    public ScriptContextChunk getScriptContext() { return scriptContext; }
    public ScriptNamesChunk getScriptNames() { return scriptNames; }
    public List<CastChunk> getCasts() { return Collections.unmodifiableList(casts); }
    public List<CastMemberChunk> getCastMembers() { return Collections.unmodifiableList(castMembers); }
    public List<ScriptChunk> getScripts() { return Collections.unmodifiableList(scripts); }
    public Collection<ChunkInfo> getAllChunkInfo() { return Collections.unmodifiableCollection(chunkInfo.values()); }
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath != null ? basePath : ""; }

    public Chunk getChunk(int id) {
        return chunks.get(id);
    }

    public <T extends Chunk> Optional<T> getChunk(int id, Class<T> type) {
        Chunk chunk = chunks.get(id);
        if (type.isInstance(chunk)) {
            return Optional.of(type.cast(chunk));
        }
        return Optional.empty();
    }

    // Stage properties

    public int getStageWidth() {
        return config != null ? config.stageWidth() : 0;
    }

    public int getStageHeight() {
        return config != null ? config.stageHeight() : 0;
    }

    public int getTempo() {
        return config != null ? config.tempo() : 15;
    }

    /**
     * Get the number of sprite channels available.
     * Director 7+ has 1000 channels, earlier versions had fewer.
     */
    public int getChannelCount() {
        if (config == null) return 120; // default

        int dirVer = config.directorVersion();
        // Convert internal version to human-readable:
        // 1000-1099 = D4, 1100-1199 = D5, 1200-1299 = D6, 1300-1399 = D7,
        // 1400-1499 = D8, 1500-1599 = D9, 1600-1699 = D10, 1700-1799 = D11,
        // 1800-1899 = D12
        int humanVer = (dirVer / 100) * 100;
        if (humanVer >= 1300) { // Director 7+
            return 1000;
        } else if (humanVer >= 1200) { // Director 6
            return 120;
        } else if (humanVer >= 1100) { // Director 5
            return 48;
        } else {
            return 48;
        }
    }

    // Script access

    public ScriptChunk getScriptForMember(int castLib, int memberNum) {
        for (ScriptChunk script : scripts) {
            // Match by cast member reference
            // This requires linking through the script context
        }
        return null;
    }

    public List<ScriptChunk.Handler> getAllHandlers() {
        List<ScriptChunk.Handler> allHandlers = new ArrayList<>();
        for (ScriptChunk script : scripts) {
            allHandlers.addAll(script.handlers());
        }
        return allHandlers;
    }

    /**
     * Get handler name from the default script names chunk.
     * For more accurate lookup, use getHandlerName(int, ScriptContextChunk).
     */
    public String getHandlerName(int nameId) {
        if (scriptNames != null) {
            return scriptNames.getName(nameId);
        }
        return "<unknown:" + nameId + ">";
    }

    /**
     * Get the ScriptNamesChunk for a given ScriptContextChunk.
     */
    public ScriptNamesChunk getScriptNamesForContext(ScriptContextChunk context) {
        if (context != null) {
            ScriptNamesChunk names = scriptNamesById.get(context.lnamSectionId());
            if (names != null) {
                return names;
            }
        }
        return scriptNames;  // Fallback to default
    }

    /**
     * Get a ScriptNamesChunk by its resource ID.
     */
    public ScriptNamesChunk getScriptNamesById(int id) {
        return scriptNamesById.get(id);
    }

    // Cast Management

    /**
     * Create a CastManager for this file.
     * The CastManager handles both internal and external casts.
     * For external casts, call preloadCasts() after creating the manager.
     */
    public CastManager createCastManager() {
        CastManager manager = new CastManager();
        manager.setBasePath(basePath);
        manager.loadFromDirectorFile(this);
        return manager;
    }

    /**
     * Get list of external cast file paths referenced by this movie.
     * These paths have been normalized to .cct extension.
     */
    public List<String> getExternalCastPaths() {
        List<String> paths = new ArrayList<>();
        if (castList != null) {
            for (CastListChunk.CastListEntry entry : castList.entries()) {
                if (entry.path() != null && !entry.path().isEmpty()) {
                    String normalized = CastLib.normalizeCastPath(basePath, entry.path());
                    paths.add(normalized);
                }
            }
        }
        return paths;
    }

    /**
     * Check if this file references external casts.
     */
    public boolean hasExternalCasts() {
        if (castList == null) return false;
        for (CastListChunk.CastListEntry entry : castList.entries()) {
            if (entry.path() != null && !entry.path().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // Loading

    public static DirectorFile load(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        DirectorFile file = load(data);
        // Set base path from file location for external cast resolution
        if (path.getParent() != null) {
            file.setBasePath(path.getParent().toString());
        }
        return file;
    }

    public static DirectorFile load(byte[] data) throws IOException {
        BinaryReader reader = new BinaryReader(data);

        // Read container header
        int containerFourCC = reader.readFourCC();
        ChunkType container = ChunkType.fromFourCC(containerFourCC);

        ByteOrder endian;
        if (container == ChunkType.RIFX) {
            endian = ByteOrder.BIG_ENDIAN;
        } else if (container == ChunkType.XFIR) {
            endian = ByteOrder.LITTLE_ENDIAN;
        } else {
            throw new IOException("Not a valid Director file: expected RIFX or XFIR, got " +
                BinaryReader.fourCCToString(containerFourCC));
        }

        reader.setOrder(endian);
        int fileSize = reader.readI32();

        // Read movie type FourCC using the file's byte order (not always big-endian)
        // because the movie type is stored in the container's native byte order
        int movieFourCC = reader.readI32();
        ChunkType movieType = ChunkType.fromFourCC(movieFourCC);

        boolean afterburner = movieType.isAfterburner();
        int version = 0;

        DirectorFile file;

        if (afterburner) {
            file = loadAfterburner(reader, endian, movieType);
        } else {
            file = loadRIFX(reader, endian, movieType);
        }

        return file;
    }

    private static DirectorFile loadRIFX(BinaryReader reader, ByteOrder endian, ChunkType movieType) throws IOException {
        DirectorFile file = new DirectorFile(endian, false, 0, movieType);

        // Read imap chunk to find mmap (FourCCs use the file's byte order)
        int imapFourCC = reader.readI32();
        int imapLen = reader.readI32();
        int mmapOffset = reader.readI32();

        // Read mmap (memory map)
        reader.setPosition(mmapOffset);
        int mmapFourCC = reader.readI32();
        int mmapLen = reader.readI32();

        // Parse memory map
        int headerLen = reader.readI16();
        int entryLen = reader.readI16();
        int chunkCountMax = reader.readI32();
        int chunkCountUsed = reader.readI32();
        int junkPtr = reader.readI32();
        reader.skip(4);
        int freePtr = reader.readI32();

        // Read chunk entries (FourCCs use the file's byte order)
        for (int i = 0; i < chunkCountUsed; i++) {
            int fourcc = reader.readI32();  // FourCC in file's byte order
            int length = reader.readI32();
            int offset = reader.readI32();
            int flags = reader.readI16();
            reader.skip(2);
            int link = reader.readI32();

            if (fourcc != 0 && offset > 0) {
                file.chunkInfo.put(i, new ChunkInfo(i, fourcc, offset + 8, length, length));
            }
        }

        // Detect version from config chunk
        for (ChunkInfo info : file.chunkInfo.values()) {
            ChunkType type = info.type();
            if (type == ChunkType.DRCF || type == ChunkType.VWCF) {
                BinaryReader chunkReader = reader.sliceReaderAt(info.offset, info.length);
                file.config = ConfigChunk.read(chunkReader, info.id, 0, endian);
                break;
            }
        }

        // Parse all chunks
        int version = file.config != null ? file.config.directorVersion() : 0;
        boolean capitalX = false;

        for (ChunkInfo info : file.chunkInfo.values()) {
            try {
                Chunk chunk = file.parseChunk(reader, info, version, capitalX);
                if (chunk != null) {
                    file.chunks.put(info.id, chunk);
                    file.categorizeChunk(chunk);

                    if (chunk instanceof ScriptContextChunk) {
                        capitalX = info.fourcc == BinaryReader.fourCC("LctX");
                    }
                }
            } catch (Exception e) {
                // Log and continue
                System.err.println("Failed to parse chunk " + info.type() + ": " + e.getMessage());
            }
        }

        return file;
    }

    private static DirectorFile loadAfterburner(BinaryReader reader, ByteOrder endian, ChunkType movieType) throws IOException {
        DirectorFile file = new DirectorFile(endian, true, 0, movieType);

        // Use AfterburnerReader to parse the compressed file
        AfterburnerReader abReader = new AfterburnerReader(reader, endian);
        abReader.parse();

        int version = abReader.getDirectorVersion();
        boolean capitalX = false;

        // First pass: find and parse the config chunk to get correct version
        for (com.libreshockwave.format.ChunkInfo abInfo : abReader.getChunkInfos()) {
            String fourCCStr = abInfo.fourCC().trim();
            if (fourCCStr.equals("DRCF") || fourCCStr.equals("VWCF")) {
                try {
                    byte[] chunkData = abReader.getChunkData(abInfo.resourceId());
                    BinaryReader chunkReader = new BinaryReader(chunkData, endian);
                    file.config = ConfigChunk.read(chunkReader, abInfo.resourceId(), 0, endian);
                    version = file.config.directorVersion();
                    break;
                } catch (Exception e) {
                    // Continue without config
                }
            }
        }

        // Second pass: parse all chunks with correct version
        for (com.libreshockwave.format.ChunkInfo abInfo : abReader.getChunkInfos()) {
            int fourcc = BinaryReader.fourCC(abInfo.fourCC());
            ChunkInfo info = new ChunkInfo(
                abInfo.resourceId(),
                fourcc,
                abInfo.offset(),
                abInfo.compressedSize(),
                abInfo.uncompressedSize()
            );
            file.chunkInfo.put(info.id, info);

            // Try to get and parse the chunk data
            try {
                byte[] chunkData = abReader.getChunkData(abInfo.resourceId());
                BinaryReader chunkReader = new BinaryReader(chunkData, endian);

                Chunk chunk = file.parseChunkFromReader(chunkReader, info, version, capitalX);
                if (chunk != null) {
                    file.chunks.put(info.id, chunk);
                    file.categorizeChunk(chunk);

                    // Update capitalX flag if we found a script context
                    if (chunk instanceof ScriptContextChunk) {
                        capitalX = abInfo.fourCC().equals("LctX");
                    }

                    // Update version from config
                    if (chunk instanceof ConfigChunk cfg) {
                        version = cfg.directorVersion();
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to parse Afterburner chunk " + abInfo.fourCC() +
                    " (id=" + abInfo.resourceId() + "): " + e.getMessage());
            }
        }

        return file;
    }

    private Chunk parseChunkFromReader(BinaryReader reader, ChunkInfo info, int version, boolean capitalX) {
        reader.setOrder(endian);
        ChunkType type = info.type();

        return switch (type) {
            case DRCF, VWCF -> ConfigChunk.read(reader, info.id, version, endian);
            case KEYp -> KeyTableChunk.read(reader, info.id, version);
            case MCsL -> CastListChunk.read(reader, info.id, version, endian);
            case CASp -> CastChunk.read(reader, info.id, version);
            case CASt -> CastMemberChunk.read(reader, info.id, version);
            case Lctx, LctX -> ScriptContextChunk.read(reader, info.id, version);
            case Lnam -> ScriptNamesChunk.read(reader, info.id, version);
            case Lscr -> ScriptChunk.read(reader, info.id, version, capitalX);
            case VWSC, SCVW -> ScoreChunk.read(reader, info.id, version);
            case BITD -> BitmapChunk.read(reader, info.id, version);
            case CLUT -> PaletteChunk.read(reader, info.id, version);
            case STXT -> TextChunk.read(reader, info.id);
            case snd_ -> SoundChunk.read(reader, info.id);
            default -> new RawChunk(info.id, type, reader.readBytes(reader.bytesLeft()));
        };
    }

    private Chunk parseChunk(BinaryReader mainReader, ChunkInfo info, int version, boolean capitalX) {
        BinaryReader reader = mainReader.sliceReaderAt(info.offset, info.length);
        reader.setOrder(endian);

        ChunkType type = info.type();

        return switch (type) {
            case DRCF, VWCF -> ConfigChunk.read(reader, info.id, version, endian);
            case KEYp -> KeyTableChunk.read(reader, info.id, version);
            case MCsL -> CastListChunk.read(reader, info.id, version, endian);
            case CASp -> CastChunk.read(reader, info.id, version);
            case CASt -> CastMemberChunk.read(reader, info.id, version);
            case Lctx, LctX -> ScriptContextChunk.read(reader, info.id, version);
            case Lnam -> ScriptNamesChunk.read(reader, info.id, version);
            case Lscr -> ScriptChunk.read(reader, info.id, version, capitalX);
            case VWSC, SCVW -> ScoreChunk.read(reader, info.id, version);
            case BITD -> BitmapChunk.read(reader, info.id, version);
            case CLUT -> PaletteChunk.read(reader, info.id, version);
            case STXT -> TextChunk.read(reader, info.id);
            case snd_ -> SoundChunk.read(reader, info.id);
            default -> new RawChunk(info.id, type, reader.readBytes(reader.bytesLeft()));
        };
    }

    private void categorizeChunk(Chunk chunk) {
        switch (chunk) {
            case ConfigChunk c -> this.config = c;
            case KeyTableChunk k -> this.keyTable = k;
            case CastListChunk cl -> this.castList = cl;
            case ScriptContextChunk sc -> {
                // Keep the context with entries (some files have multiple Lctx, one empty)
                if (this.scriptContext == null || sc.entries().size() > 0) {
                    this.scriptContext = sc;
                }
            }
            case ScriptNamesChunk sn -> {
                this.scriptNamesById.put(sn.id(), sn);
                // Also set as default if it has names (for backward compatibility)
                if (sn.names().size() > 0) {
                    this.scriptNames = sn;
                }
            }
            case CastChunk c -> this.casts.add(c);
            case CastMemberChunk cm -> this.castMembers.add(cm);
            case ScriptChunk s -> this.scripts.add(s);
            default -> {}
        }
    }

    // Utility methods

    public void printSummary() {
        System.out.println("=== Director File Summary ===");
        System.out.println("Endian: " + (endian == ByteOrder.BIG_ENDIAN ? "Big (Mac)" : "Little (Win)"));
        System.out.println("Afterburner: " + afterburner);
        System.out.println("Movie Type: " + movieType);

        if (config != null) {
            System.out.println("\n--- Config ---");
            System.out.println("Director Version: " + config.directorVersion());
            System.out.println("Stage: " + config.stageWidth() + "x" + config.stageHeight());
            System.out.println("Tempo: " + config.tempo() + " fps");
        }

        System.out.println("\n--- Chunks ---");
        System.out.println("Total chunks: " + chunkInfo.size());
        System.out.println("Cast libraries: " + (castList != null ? castList.entries().size() : 0));
        System.out.println("Cast members: " + castMembers.size());
        System.out.println("Scripts: " + scripts.size());

        if (!scripts.isEmpty() && scriptNames != null) {
            System.out.println("\n--- Handlers ---");
            for (ScriptChunk script : scripts) {
                for (ScriptChunk.Handler handler : script.handlers()) {
                    String name = scriptNames.getName(handler.nameId());
                    System.out.println("  " + name + "(" + handler.argCount() + " args, " +
                        handler.localCount() + " locals, " + handler.instructions().size() + " instructions)");
                }
            }
        }
    }

    public void disassembleScript(ScriptChunk script) {
        if (scriptNames == null) {
            System.out.println("No script names available");
            return;
        }

        for (ScriptChunk.Handler handler : script.handlers()) {
            String name = scriptNames.getName(handler.nameId());

            // Print handler signature
            StringBuilder sig = new StringBuilder();
            sig.append("on ").append(name);
            if (!handler.argNameIds().isEmpty()) {
                sig.append("(");
                for (int i = 0; i < handler.argNameIds().size(); i++) {
                    if (i > 0) sig.append(", ");
                    sig.append(scriptNames.getName(handler.argNameIds().get(i)));
                }
                sig.append(")");
            }
            System.out.println("        " + sig);

            // Print locals if any
            if (!handler.localNameIds().isEmpty()) {
                StringBuilder locals = new StringBuilder("          -- locals: ");
                for (int i = 0; i < handler.localNameIds().size(); i++) {
                    if (i > 0) locals.append(", ");
                    locals.append(scriptNames.getName(handler.localNameIds().get(i)));
                }
                System.out.println(locals);
            }

            // Print bytecode with resolved names
            for (ScriptChunk.Handler.Instruction instr : handler.instructions()) {
                String line = formatInstruction(instr, handler, scriptNames);
                System.out.println("          " + line);
            }
            System.out.println("        end");
        }
    }

    private String formatInstruction(ScriptChunk.Handler.Instruction instr,
                                     ScriptChunk.Handler handler,
                                     ScriptNamesChunk names) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%d] %s", instr.offset(), instr.opcode().getMnemonic()));

        Opcode op = instr.opcode();
        long arg = instr.argument();

        switch (op) {
            // Jump instructions - show target position
            case JMP, JMP_IF_Z -> {
                int target = instr.offset() + (int) arg;
                sb.append(" [").append(target).append("]");
            }
            case END_REPEAT -> {
                int target = instr.offset() - (int) arg;
                sb.append(" [").append(target).append("]");
            }

            // Name-based opcodes - resolve name from script names
            case OBJ_CALL, EXT_CALL, GET_OBJ_PROP, SET_OBJ_PROP,
                 PUSH_SYMB, GET_PROP, SET_PROP, GET_CHAINED_PROP,
                 GET_GLOBAL, SET_GLOBAL, GET_GLOBAL2, SET_GLOBAL2,
                 GET_TOP_LEVEL_PROP, NEW_OBJ -> {
                String symName = names.getName((int) arg);
                sb.append(" ").append(symName);
            }

            // Local variable access - resolve from handler's local names
            case GET_LOCAL, SET_LOCAL -> {
                if (arg >= 0 && arg < handler.localNameIds().size()) {
                    int nameId = handler.localNameIds().get((int) arg);
                    sb.append(" ").append(names.getName(nameId));
                } else {
                    sb.append(" local_").append(arg);
                }
            }

            // Parameter access - resolve from handler's arg names
            case GET_PARAM, SET_PARAM -> {
                if (arg >= 0 && arg < handler.argNameIds().size()) {
                    int nameId = handler.argNameIds().get((int) arg);
                    sb.append(" ").append(names.getName(nameId));
                } else {
                    sb.append(" param_").append(arg);
                }
            }

            // Float literal
            case PUSH_FLOAT32 -> {
                float f = Float.intBitsToFloat((int) arg);
                sb.append(" ").append(f);
            }

            // Integer literals
            case PUSH_INT8, PUSH_INT16, PUSH_INT32 -> {
                sb.append(" ").append(arg);
            }

            // Other multi-byte opcodes - just show the argument
            default -> {
                if (instr.rawOpcode() >= 0x40) {
                    sb.append(" ").append(arg);
                }
            }
        }

        return sb.toString();
    }
}
