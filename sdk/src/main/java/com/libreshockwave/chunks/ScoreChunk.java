package com.libreshockwave.chunks;

import com.libreshockwave.format.ChunkType;
import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Score chunk (VWSC).
 * Contains the timeline/score data with frames and sprite channels.
 *
 * Structure matches dirplayer-rs vm-rust/src/director/chunks/score.rs
 */
public record ScoreChunk(
    int id,
    Header header,
    List<byte[]> entries,
    ScoreFrameData frameData,
    List<FrameInterval> frameIntervals
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.VWSC;
    }

    /**
     * Score chunk header (24 bytes).
     */
    public record Header(
        int totalLength,
        int unk1,
        int unk2,
        int entryCount,
        int unk3,
        int entrySizeSum
    ) {
        public static Header read(BinaryReader reader) {
            return new Header(
                reader.readI32(),
                reader.readI32(),
                reader.readI32(),
                reader.readI32(),
                reader.readI32(),
                reader.readI32()
            );
        }
    }

    /**
     * Frame data header.
     */
    public record FrameDataHeader(
        int frameCount,
        int spriteRecordSize,
        int numChannels
    ) {}

    /**
     * Per-channel sprite data (24+ bytes per channel).
     */
    public record ChannelData(
        int spriteType,
        int ink,
        int foreColor,
        int backColor,
        int castLib,
        int castMember,
        int unk1,
        int unk2,
        int posY,
        int posX,
        int height,
        int width,
        int colorFlag,
        int foreColorG,
        int backColorG,
        int foreColorB,
        int backColorB
    ) {
        public static final ChannelData EMPTY = new ChannelData(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        public static ChannelData read(BinaryReader reader) {
            int spriteType = reader.readU8();
            int ink = reader.readU8();
            int foreColor = reader.readU8();
            int backColor = reader.readU8();
            int castLib = reader.readU16();
            int castMember = reader.readU16();
            int unk1 = reader.readU16();
            int unk2 = reader.readU16();
            int posY = reader.readU16();
            int posX = reader.readU16();
            int height = reader.readU16();
            int width = reader.readU16();

            // Extended color data
            int unk3 = reader.readU8();
            int colorFlag = (unk3 & 0xF0) >> 4;
            reader.readU8(); // unk4
            reader.readU8(); // unk5
            reader.readU8(); // unk6
            int foreColorG = reader.readU8();
            int backColorG = reader.readU8();
            int foreColorB = reader.readU8();
            int backColorB = reader.readU8();

            return new ChannelData(
                spriteType, ink, foreColor, backColor,
                castLib, castMember, unk1, unk2,
                posY, posX, height, width,
                colorFlag, foreColorG, backColorG, foreColorB, backColorB
            );
        }

        public boolean isEmpty() {
            return spriteType == 0 && ink == 0 && foreColor == 0 && backColor == 0
                && castLib == 0 && castMember == 0 && posY == 0 && posX == 0
                && height == 0 && width == 0;
        }
    }

    /**
     * Parsed frame data from Entry[0].
     */
    public record ScoreFrameData(
        FrameDataHeader header,
        byte[] decompressedData,
        List<FrameChannelEntry> frameChannelData
    ) {
        public static final ScoreFrameData EMPTY = new ScoreFrameData(
            new FrameDataHeader(0, 24, 0),
            new byte[0],
            new ArrayList<>()
        );
    }

    /**
     * Individual frame/channel data entry.
     */
    public record FrameChannelEntry(
        int frameIndex,
        int channelIndex,
        ChannelData data
    ) {}

    /**
     * Primary interval (44 bytes) - defines frame range and channel.
     */
    public record FrameIntervalPrimary(
        int startFrame,
        int endFrame,
        int unk0,
        int unk1,
        int channelIndex,
        int unk2,
        int unk3,
        int unk4,
        int unk5,
        int unk6,
        int unk7,
        int unk8
    ) {
        public static FrameIntervalPrimary read(BinaryReader reader) {
            return new FrameIntervalPrimary(
                reader.readI32(),
                reader.readI32(),
                reader.readI32(),
                reader.readI32(),
                reader.readI32(),
                reader.readU16(),
                reader.readI32(),
                reader.readU16(),
                reader.readI32(),
                reader.readI32(),
                reader.readI32(),
                reader.readI32()
            );
        }
    }

    /**
     * Secondary interval (8 bytes) - references behavior script.
     */
    public record FrameIntervalSecondary(
        int castLib,
        int castMember,
        int unk0
    ) {
        public static FrameIntervalSecondary read(BinaryReader reader) {
            return new FrameIntervalSecondary(
                reader.readU16(),
                reader.readU16(),
                reader.readI32()
            );
        }
    }

    /**
     * Combined frame interval with primary and optional secondary.
     */
    public record FrameInterval(
        FrameIntervalPrimary primary,
        FrameIntervalSecondary secondary
    ) {}

    public int getFrameCount() {
        return frameData != null ? frameData.header().frameCount() : 0;
    }

    public int getChannelCount() {
        return frameData != null ? frameData.header().numChannels() : 0;
    }

    public static ScoreChunk read(BinaryReader reader, int id, int version) {
        reader.setOrder(ByteOrder.BIG_ENDIAN);

        if (reader.bytesLeft() < 24) {
            return createEmpty(id);
        }

        // Read header
        Header header = Header.read(reader);

        // Read offsets table
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i <= header.entryCount(); i++) {
            if (reader.bytesLeft() < 4) break;
            offsets.add(reader.readI32());
        }

        // Validate offsets
        if (offsets.size() != header.entryCount() + 1) {
            return createEmpty(id);
        }

        for (int j = 0; j < offsets.size() - 1; j++) {
            if (offsets.get(j) > offsets.get(j + 1)) {
                return createEmpty(id);
            }
        }

        // Read all entries
        List<byte[]> entries = new ArrayList<>();
        for (int i = 0; i < header.entryCount(); i++) {
            int currentOffset = offsets.get(i);
            int nextOffset = offsets.get(i + 1);
            int length = nextOffset - currentOffset;

            if (length > 0 && reader.bytesLeft() >= length) {
                entries.add(reader.readBytes(length));
            } else {
                entries.add(new byte[0]);
            }
        }

        // Process frame data from first entry (Entry[0])
        ScoreFrameData frameData = ScoreFrameData.EMPTY;
        if (!entries.isEmpty() && entries.get(0).length > 0) {
            frameData = parseFrameData(entries.get(0));
        }

        // Process behavior attachment entries (Entry[2+])
        List<FrameInterval> frameIntervals = parseFrameIntervals(entries);

        return new ScoreChunk(id, header, entries, frameData, frameIntervals);
    }

    private static ScoreChunk createEmpty(int id) {
        return new ScoreChunk(
            id,
            new Header(0, 0, 0, 0, 0, 0),
            new ArrayList<>(),
            ScoreFrameData.EMPTY,
            new ArrayList<>()
        );
    }

    private static ScoreFrameData parseFrameData(byte[] data) {
        BinaryReader reader = new BinaryReader(data, ByteOrder.BIG_ENDIAN);

        if (reader.bytesLeft() < 20) {
            return ScoreFrameData.EMPTY;
        }

        // Read header
        int actualLength = reader.readI32();
        int unk1 = reader.readI32();
        int frameCount = reader.readI32();
        int framesVersion = reader.readU16();
        int spriteRecordSize = reader.readU16();
        int numChannels = reader.readU16();

        // Skip numChannelsDisplayed based on version
        if (framesVersion > 13) {
            reader.skip(2);
        } else {
            reader.skip(2);
        }

        FrameDataHeader header = new FrameDataHeader(frameCount, spriteRecordSize, numChannels);

        // Allocate channel data buffer with overflow protection
        long totalSizeLong = (long) frameCount * numChannels * spriteRecordSize;
        if (totalSizeLong <= 0 || totalSizeLong > 50_000_000) {
            return ScoreFrameData.EMPTY; // Sanity limit or overflow
        }
        int totalSize = (int) totalSizeLong;
        byte[] channelData = new byte[totalSize];

        // Read delta-compressed frame data
        int frameIndex = 0;
        while (!reader.eof() && frameIndex < frameCount) {
            if (reader.bytesLeft() < 2) break;

            int length = reader.readU16();
            if (length == 0) break;

            int frameLength = length - 2;
            if (frameLength > 0 && reader.bytesLeft() >= frameLength) {
                byte[] frameBytes = reader.readBytes(frameLength);
                BinaryReader frameReader = new BinaryReader(frameBytes, ByteOrder.BIG_ENDIAN);

                // Parse delta entries for this frame
                while (!frameReader.eof() && frameReader.bytesLeft() >= 4) {
                    int channelSize = frameReader.readU16();
                    int channelOffset = frameReader.readU16();

                    if (channelSize > 0 && frameReader.bytesLeft() >= channelSize) {
                        byte[] channelDelta = frameReader.readBytes(channelSize);

                        int frameOffset = frameIndex * numChannels * spriteRecordSize;
                        int destOffset = frameOffset + channelOffset;
                        int endOffset = destOffset + channelSize;

                        if (endOffset <= channelData.length) {
                            System.arraycopy(channelDelta, 0, channelData, destOffset, channelSize);
                        }
                    } else {
                        break;
                    }
                }
            }
            frameIndex++;
        }

        // Parse channel data into structured entries
        List<FrameChannelEntry> frameChannelEntries = new ArrayList<>();
        BinaryReader channelReader = new BinaryReader(channelData, ByteOrder.BIG_ENDIAN);

        for (int f = 0; f < frameCount; f++) {
            for (int c = 0; c < numChannels; c++) {
                int pos = channelReader.getPosition();

                if (channelReader.bytesLeft() >= 24) {
                    ChannelData cd = ChannelData.read(channelReader);
                    channelReader.setPosition(pos + spriteRecordSize);

                    if (!cd.isEmpty()) {
                        frameChannelEntries.add(new FrameChannelEntry(f, c, cd));
                    }
                } else {
                    break;
                }
            }
        }

        return new ScoreFrameData(header, channelData, frameChannelEntries);
    }

    private static List<FrameInterval> parseFrameIntervals(List<byte[]> entries) {
        List<FrameInterval> results = new ArrayList<>();
        int i = 2; // Start at 2, skip entries 0 and 1

        while (i < entries.size()) {
            byte[] entryBytes = entries.get(i);

            if (entryBytes.length == 0) {
                i++;
                continue;
            }

            if (entryBytes.length == 44) {
                // Primary entry (44 bytes)
                BinaryReader reader = new BinaryReader(entryBytes, ByteOrder.BIG_ENDIAN);
                FrameIntervalPrimary primary = FrameIntervalPrimary.read(reader);

                // Look ahead for secondary entries
                List<FrameIntervalSecondary> secondaries = new ArrayList<>();
                int j = i + 1;

                while (j < entries.size()) {
                    int nextSize = entries.get(j).length;

                    // Check if this could be a behavior entry (8 bytes per behavior)
                    if (nextSize >= 8 && nextSize % 8 == 0) {
                        int behaviorCount = nextSize / 8;
                        BinaryReader secReader = new BinaryReader(entries.get(j), ByteOrder.BIG_ENDIAN);

                        boolean foundValidBehavior = false;
                        for (int b = 0; b < behaviorCount; b++) {
                            int castLib = secReader.readU16();
                            int castMember = secReader.readU16();
                            int unk0 = secReader.readI32();

                            if (castLib > 0 && castMember > 0) {
                                secondaries.add(new FrameIntervalSecondary(castLib, castMember, unk0));
                                foundValidBehavior = true;
                            }
                        }

                        if (foundValidBehavior) {
                            j++;
                        } else {
                            break;
                        }
                    } else {
                        break;
                    }
                }

                // Create result entries
                if (secondaries.isEmpty()) {
                    results.add(new FrameInterval(primary, null));
                } else {
                    for (FrameIntervalSecondary secondary : secondaries) {
                        results.add(new FrameInterval(primary, secondary));
                    }
                }

                i = j;
                continue;
            }

            i++;
        }

        return results;
    }
}
