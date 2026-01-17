package com.libreshockwave.chunks;

import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;
import com.libreshockwave.lingo.Opcode;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Script bytecode chunk (Lscr).
 * Contains the compiled Lingo bytecode for a script.
 */
public record ScriptChunk(
    int id,
    ScriptType scriptType,
    int behaviorFlags,
    List<Handler> handlers,
    List<LiteralEntry> literals,
    List<PropertyEntry> properties,
    List<GlobalEntry> globals,
    byte[] rawBytecode
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.Lscr;
    }

    public enum ScriptType {
        MOVIE_SCRIPT(1),
        BEHAVIOR(3),
        PARENT(7),
        UNKNOWN(-1);

        private final int code;

        ScriptType(int code) {
            this.code = code;
        }

        public static ScriptType fromCode(int code) {
            for (ScriptType type : values()) {
                if (type.code == code) return type;
            }
            return UNKNOWN;
        }
    }

    public record Handler(
        int nameId,
        int handlerVectorPos,
        int bytecodeLength,
        int bytecodeOffset,
        int argCount,
        int localCount,
        int globalsCount,
        int lineCount,
        List<Integer> argNameIds,
        List<Integer> localNameIds,
        List<Instruction> instructions
    ) {
        public record Instruction(
            int offset,
            Opcode opcode,
            int rawOpcode,
            int argument
        ) {
            @Override
            public String toString() {
                if (rawOpcode >= 0x40) {
                    return String.format("[%d] %s %d", offset, opcode.getMnemonic(), argument);
                } else {
                    return String.format("[%d] %s", offset, opcode.getMnemonic());
                }
            }
        }
    }

    public record LiteralEntry(
        int type,
        int offset,
        Object value
    ) {}

    public record PropertyEntry(
        int nameId
    ) {}

    public record GlobalEntry(
        int nameId
    ) {}

    public Handler findHandler(String name, ScriptNamesChunk names) {
        int nameId = names.findName(name);
        if (nameId < 0) return null;

        for (Handler h : handlers) {
            if (h.nameId == nameId) {
                return h;
            }
        }
        return null;
    }

    public static ScriptChunk read(BinaryReader reader, int id, int version, boolean capitalX) {
        // Lingo scripts are ALWAYS big endian regardless of file byte order
        reader.setOrder(ByteOrder.BIG_ENDIAN);

        // Header - skip first 8 bytes
        reader.seek(8);

        int totalLength = reader.readI32();
        int totalLength2 = reader.readI32();
        int headerLength = reader.readU16();
        int scriptNumber = reader.readU16();

        // Seek to scriptBehavior at offset 38
        reader.seek(38);
        int behaviorFlags = reader.readI32();

        // Seek to handler data at offset 50
        reader.seek(50);
        int handlerVectorsCount = reader.readU16();
        int handlerVectorsOffset = reader.readI32();
        int handlerVectorsSize = reader.readI32();
        int propertyCount = reader.readU16();
        int propertiesOffset = reader.readI32();
        int globalCount = reader.readU16();
        int globalsOffset = reader.readI32();
        int handlerInfoCount = reader.readU16();
        int handlersOffset = reader.readI32();
        int literalCount = reader.readU16();
        int literalsOffset = reader.readI32();
        int literalDataLen = reader.readI32();
        int literalDataOffset = reader.readI32();

        int scriptType = scriptNumber; // Actually script number, not type

        // Read properties
        List<PropertyEntry> properties = new ArrayList<>();
        if (propertyCount > 0 && propertiesOffset > 0) {
            reader.setPosition(propertiesOffset);
            for (int i = 0; i < propertyCount; i++) {
                properties.add(new PropertyEntry(reader.readI16()));
            }
        }

        // Read globals
        List<GlobalEntry> globals = new ArrayList<>();
        if (globalCount > 0 && globalsOffset > 0) {
            reader.setPosition(globalsOffset);
            for (int i = 0; i < globalCount; i++) {
                globals.add(new GlobalEntry(reader.readI16()));
            }
        }

        // Read literals
        List<LiteralEntry> literals = new ArrayList<>();
        if (literalCount > 0 && literalsOffset > 0) {
            reader.setPosition(literalsOffset);
            List<int[]> literalInfo = new ArrayList<>();
            for (int i = 0; i < literalCount; i++) {
                int type = reader.readI32();
                int offset = reader.readI32();
                literalInfo.add(new int[]{type, offset});
            }

            // Read literal data
            for (int[] info : literalInfo) {
                int type = info[0];
                int offset = info[1];
                Object value = null;

                reader.setPosition(literalDataOffset + offset);
                int dataLen = reader.readI32();

                switch (type) {
                    case 1 -> { // String (dataLen includes null terminator)
                        String s = reader.readStringMacRoman(dataLen);
                        // Strip null terminator if present
                        if (s.endsWith("\0")) {
                            s = s.substring(0, s.length() - 1);
                        }
                        value = s;
                    }
                    case 4 -> { // Int
                        value = dataLen; // Actually the value
                    }
                    case 9 -> { // Float
                        value = (double) Float.intBitsToFloat(reader.readI32());
                    }
                    default -> {
                        value = reader.readBytes(dataLen);
                    }
                }

                literals.add(new LiteralEntry(type, offset, value));
            }
        }

        // Read handlers
        List<Handler> handlers = new ArrayList<>();
        if (handlerInfoCount > 0 && handlersOffset > 0) {
            reader.setPosition(handlersOffset);

            for (int i = 0; i < handlerInfoCount; i++) {
                // Handler record structure from ProjectorRays handler.cpp:readRecord()
                int nameId = reader.readI16();           // int16
                int handlerVectorPos = reader.readU16(); // uint16
                int bytecodeLen = reader.readI32();      // uint32
                int bytecodeOffset = reader.readI32();   // uint32
                int argCount = reader.readU16();         // uint16
                int argOffset = reader.readI32();        // uint32
                int localCount = reader.readU16();       // uint16
                int localOffset = reader.readI32();      // uint32
                int handlerGlobalsCount = reader.readU16();   // uint16
                int handlerGlobalsOffset = reader.readI32();  // uint32
                int unknown1 = reader.readI32();         // uint32
                int unknown2 = reader.readU16();         // uint16
                int lineCount = reader.readU16();        // uint16
                int lineOffset = reader.readI32();       // uint32
                if (capitalX) {
                    int stackHeight = reader.readI32();  // uint32 (only in LctX)
                }

                int savedPos = reader.getPosition();

                // Read argument names (using unsigned 16-bit per ProjectorRays)
                List<Integer> argNameIds = new ArrayList<>();
                if (argCount > 0 && argOffset > 0) {
                    reader.setPosition(argOffset);
                    for (int j = 0; j < argCount; j++) {
                        argNameIds.add(reader.readU16());
                    }
                }

                // Read local variable names (using unsigned 16-bit per ProjectorRays)
                List<Integer> localNameIds = new ArrayList<>();
                if (localCount > 0 && localOffset > 0) {
                    reader.setPosition(localOffset);
                    for (int j = 0; j < localCount; j++) {
                        localNameIds.add(reader.readU16());
                    }
                }

                // Parse bytecode instructions (matching dirplayer-rs handler.rs)
                List<Handler.Instruction> instructions = new ArrayList<>();
                if (bytecodeLen > 0 && bytecodeOffset > 0) {
                    reader.setPosition(bytecodeOffset);
                    int bytecodeEnd = bytecodeOffset + bytecodeLen;

                    while (reader.getPosition() < bytecodeEnd) {
                        int instrOffset = reader.getPosition() - bytecodeOffset;
                        int op = reader.readU8();

                        // Normalize opcode: multi-byte opcodes are 0x40 + (op % 0x40)
                        Opcode opcode = Opcode.fromCode(op >= 0x40 ? (0x40 + op % 0x40) : op);
                        int argument = 0;

                        // Argument size is determined by the op byte value, not opcode type
                        if (op >= 0xC0) {
                            // 4-byte argument
                            argument = reader.readI32();
                        } else if (op >= 0x80) {
                            // 2-byte argument
                            if (opcode == Opcode.PUSH_INT16 || opcode == Opcode.PUSH_INT8) {
                                // Treat pushint's arg as signed
                                argument = reader.readI16();
                            } else {
                                argument = reader.readU16();
                            }
                        } else if (op >= 0x40) {
                            // 1-byte argument
                            if (opcode == Opcode.PUSH_INT8) {
                                // Treat pushint's arg as signed
                                argument = reader.readI8();
                            } else {
                                argument = reader.readU8();
                            }
                        }
                        // op < 0x40: no argument (single-byte opcode)

                        instructions.add(new Handler.Instruction(instrOffset, opcode, op, argument));
                    }
                }

                reader.setPosition(savedPos);

                handlers.add(new Handler(
                    nameId,
                    handlerVectorPos,
                    bytecodeLen,
                    bytecodeOffset,
                    argCount,
                    localCount,
                    handlerGlobalsCount,
                    lineCount,
                    argNameIds,
                    localNameIds,
                    instructions
                ));
            }
        }

        // Read raw bytecode for reference
        byte[] rawBytecode = new byte[0];

        return new ScriptChunk(
            id,
            ScriptType.fromCode(scriptType),
            behaviorFlags,
            handlers,
            literals,
            properties,
            globals,
            rawBytecode
        );
    }
}
