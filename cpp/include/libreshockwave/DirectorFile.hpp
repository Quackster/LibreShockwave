#pragma once

#include <cstdint>
#include <map>
#include <memory>
#include <stdexcept>
#include <vector>

#include "libreshockwave/format/ChunkType.hpp"
#include "libreshockwave/id/Ids.hpp"
#include "libreshockwave/io/BinaryReader.hpp"

namespace libreshockwave::chunks {
class CastChunk;
class CastListChunk;
class CastMemberChunk;
class Chunk;
class ConfigChunk;
class FontMapChunk;
class FrameLabelsChunk;
class KeyTableChunk;
class PaletteChunk;
class ScoreChunk;
class ScriptChunk;
class ScriptContextChunk;
class ScriptNamesChunk;
}

namespace libreshockwave::format {
class AfterburnerReader;
}

namespace libreshockwave {

class DirectorFileLoadError : public std::runtime_error {
public:
    explicit DirectorFileLoadError(const std::string& message) : std::runtime_error(message) {}
};

struct DirectorChunkInfo {
    id::ChunkId id;
    std::uint32_t fourcc;
    int offset;
    int length;
    int uncompressedLength;

    [[nodiscard]] format::ChunkType type() const;
};

class DirectorFile {
public:
    DirectorFile(io::ByteOrder endian, bool afterburner, int version, format::ChunkType movieType);
    ~DirectorFile();

    [[nodiscard]] io::ByteOrder endian() const;
    [[nodiscard]] bool isAfterburner() const;
    [[nodiscard]] int version() const;
    [[nodiscard]] format::ChunkType movieType() const;
    [[nodiscard]] bool isCapitalX() const;

    [[nodiscard]] std::shared_ptr<chunks::ConfigChunk> config() const;
    [[nodiscard]] std::shared_ptr<chunks::KeyTableChunk> keyTable() const;
    [[nodiscard]] std::shared_ptr<chunks::CastListChunk> castList() const;
    [[nodiscard]] std::shared_ptr<chunks::ScriptContextChunk> scriptContext() const;
    [[nodiscard]] std::shared_ptr<chunks::ScriptNamesChunk> scriptNames() const;
    [[nodiscard]] std::shared_ptr<chunks::ScoreChunk> scoreChunk() const;
    [[nodiscard]] std::shared_ptr<chunks::FrameLabelsChunk> frameLabelsChunk() const;

    [[nodiscard]] const std::map<int, DirectorChunkInfo>& chunkInfo() const;
    [[nodiscard]] const std::map<int, std::shared_ptr<chunks::Chunk>>& chunks() const;
    [[nodiscard]] std::shared_ptr<chunks::Chunk> getChunk(id::ChunkId id);
    [[nodiscard]] const DirectorChunkInfo* getChunkInfo(id::ChunkId id) const;
    void releaseNonEssentialChunks();

    [[nodiscard]] const std::vector<std::shared_ptr<chunks::CastChunk>>& casts() const;
    [[nodiscard]] const std::vector<std::shared_ptr<chunks::CastMemberChunk>>& castMembers() const;
    [[nodiscard]] const std::vector<std::shared_ptr<chunks::ScriptChunk>>& scripts() const;
    [[nodiscard]] const std::vector<std::shared_ptr<chunks::PaletteChunk>>& palettes() const;
    [[nodiscard]] const std::vector<std::shared_ptr<chunks::FontMapChunk>>& fontMaps() const;

    [[nodiscard]] static std::shared_ptr<DirectorFile> load(const std::vector<std::uint8_t>& data);

private:
    [[nodiscard]] static std::shared_ptr<DirectorFile> loadRIFX(io::BinaryReader& reader,
                                                                io::ByteOrder endian,
                                                                format::ChunkType movieType);
    [[nodiscard]] static std::shared_ptr<DirectorFile> loadRIFF(io::BinaryReader& reader, io::ByteOrder endian);
    [[nodiscard]] static std::shared_ptr<DirectorFile> loadAfterburner(io::BinaryReader& reader,
                                                                       io::ByteOrder endian,
                                                                       format::ChunkType movieType);
    [[nodiscard]] std::shared_ptr<chunks::Chunk> parseChunkFromReader(io::BinaryReader& reader,
                                                                      const DirectorChunkInfo& info,
                                                                      int version,
                                                                      bool capitalX);
    [[nodiscard]] std::shared_ptr<chunks::Chunk> reparseChunk(id::ChunkId id);
    void categorizeChunk(const std::shared_ptr<chunks::Chunk>& chunk);
    void setVersion(int version);
    void setCapitalX(bool capitalX);

    io::ByteOrder endian_;
    bool afterburner_;
    int version_;
    format::ChunkType movieType_;
    bool capitalX_ = false;
    std::vector<std::uint8_t> dataStore_;
    std::unique_ptr<format::AfterburnerReader> afterburnerReader_;

    std::shared_ptr<chunks::ConfigChunk> config_;
    std::shared_ptr<chunks::KeyTableChunk> keyTable_;
    std::shared_ptr<chunks::CastListChunk> castList_;
    std::shared_ptr<chunks::ScriptContextChunk> scriptContext_;
    std::vector<std::shared_ptr<chunks::ScriptContextChunk>> allScriptContexts_;
    std::shared_ptr<chunks::ScriptNamesChunk> scriptNames_;
    std::map<int, std::shared_ptr<chunks::ScriptNamesChunk>> scriptNamesById_;
    std::shared_ptr<chunks::ScoreChunk> scoreChunk_;
    std::shared_ptr<chunks::FrameLabelsChunk> frameLabelsChunk_;

    std::map<int, std::shared_ptr<chunks::Chunk>> chunks_;
    std::map<int, DirectorChunkInfo> chunkInfo_;
    std::vector<std::shared_ptr<chunks::CastChunk>> casts_;
    std::vector<std::shared_ptr<chunks::CastMemberChunk>> castMembers_;
    std::vector<std::shared_ptr<chunks::ScriptChunk>> scripts_;
    std::vector<std::shared_ptr<chunks::PaletteChunk>> palettes_;
    std::vector<std::shared_ptr<chunks::FontMapChunk>> fontMaps_;
};

} // namespace libreshockwave
