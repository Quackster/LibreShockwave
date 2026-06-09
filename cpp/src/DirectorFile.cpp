#include "libreshockwave/DirectorFile.hpp"

#include <algorithm>
#include <string>
#include <utility>

#include "libreshockwave/chunks/BitmapChunk.hpp"
#include "libreshockwave/chunks/CastChunk.hpp"
#include "libreshockwave/chunks/CastListChunk.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/chunks/Chunk.hpp"
#include "libreshockwave/chunks/ConfigChunk.hpp"
#include "libreshockwave/chunks/FontMapChunk.hpp"
#include "libreshockwave/chunks/FrameLabelsChunk.hpp"
#include "libreshockwave/chunks/KeyTableChunk.hpp"
#include "libreshockwave/chunks/MediaChunk.hpp"
#include "libreshockwave/chunks/PaletteChunk.hpp"
#include "libreshockwave/chunks/RawChunk.hpp"
#include "libreshockwave/chunks/ScoreChunk.hpp"
#include "libreshockwave/chunks/ScriptChunk.hpp"
#include "libreshockwave/chunks/ScriptContextChunk.hpp"
#include "libreshockwave/chunks/ScriptNamesChunk.hpp"
#include "libreshockwave/chunks/SoundChunk.hpp"
#include "libreshockwave/chunks/TextChunk.hpp"
#include "libreshockwave/format/AfterburnerReader.hpp"
#include "libreshockwave/lookup/CastMemberLookup.hpp"
#include "libreshockwave/lookup/PaletteResolver.hpp"
#include "libreshockwave/lookup/ScriptLookup.hpp"

namespace libreshockwave {

format::ChunkType DirectorChunkInfo::type() const {
    return format::chunkTypeFromFourCC(fourcc);
}

DirectorFile::DirectorFile(io::ByteOrder endian, bool afterburner, int version, format::ChunkType movieType)
    : endian_(endian), afterburner_(afterburner), version_(version), movieType_(movieType) {}

DirectorFile::~DirectorFile() = default;

io::ByteOrder DirectorFile::endian() const { return endian_; }
bool DirectorFile::isAfterburner() const { return afterburner_; }
int DirectorFile::version() const { return version_; }
format::ChunkType DirectorFile::movieType() const { return movieType_; }
bool DirectorFile::isCapitalX() const { return capitalX_; }
std::shared_ptr<chunks::ConfigChunk> DirectorFile::config() const { return config_; }
std::shared_ptr<chunks::KeyTableChunk> DirectorFile::keyTable() const { return keyTable_; }
std::shared_ptr<chunks::CastListChunk> DirectorFile::castList() const { return castList_; }
std::shared_ptr<chunks::ScriptContextChunk> DirectorFile::scriptContext() const { return scriptContext_; }
std::shared_ptr<chunks::ScriptNamesChunk> DirectorFile::scriptNames() const { return scriptNames_; }
std::shared_ptr<chunks::ScoreChunk> DirectorFile::scoreChunk() const { return scoreChunk_; }
std::shared_ptr<chunks::FrameLabelsChunk> DirectorFile::frameLabelsChunk() const { return frameLabelsChunk_; }
const std::map<int, DirectorChunkInfo>& DirectorFile::chunkInfo() const { return chunkInfo_; }
const std::map<int, std::shared_ptr<chunks::Chunk>>& DirectorFile::chunks() const { return chunks_; }
const std::vector<std::shared_ptr<chunks::CastChunk>>& DirectorFile::casts() const { return casts_; }
const std::vector<std::shared_ptr<chunks::CastMemberChunk>>& DirectorFile::castMembers() const { return castMembers_; }
const std::vector<std::shared_ptr<chunks::ScriptChunk>>& DirectorFile::scripts() const { return scripts_; }
const std::vector<std::shared_ptr<chunks::PaletteChunk>>& DirectorFile::palettes() const { return palettes_; }
const std::vector<std::shared_ptr<chunks::FontMapChunk>>& DirectorFile::fontMaps() const { return fontMaps_; }

std::shared_ptr<chunks::CastChunk> DirectorFile::getMappedCastChunk(int castLib) {
    return castMemberLookup().getMappedCast(castLib);
}

std::shared_ptr<chunks::CastMemberChunk> DirectorFile::getCastMemberByIndex(int castLib, int castMemberIndex) {
    return castMemberLookup().getByIndex(castLib, castMemberIndex);
}

std::shared_ptr<chunks::CastMemberChunk> DirectorFile::getCastMemberByNumber(int castLib, int memberNumber) {
    return castMemberLookup().getByNumber(castLib, memberNumber);
}

std::shared_ptr<chunks::ScriptChunk> DirectorFile::getScriptByContextId(int scriptId) {
    return scriptLookup().getByContextId(scriptId);
}

std::vector<std::shared_ptr<chunks::ScriptChunk>> DirectorFile::getScriptsByContextId(int scriptId) {
    return scriptLookup().getAllByContextId(scriptId);
}

std::optional<chunks::CastMemberScriptType> DirectorFile::getScriptType(const std::shared_ptr<chunks::ScriptChunk>& script) {
    return scriptLookup().getScriptType(script);
}

std::shared_ptr<chunks::ScoreChunk> DirectorFile::getScoreForMember(
    const std::shared_ptr<chunks::CastMemberChunk>& member) {
    if (!member || !keyTable_) {
        return nullptr;
    }

    for (const auto& entry : keyTable_->getEntriesForOwner(member->id())) {
        if (entry.fourcc != format::fourCC(format::ChunkType::VWSC) &&
            entry.fourcc != format::fourCC(format::ChunkType::SCVW)) {
            continue;
        }
        if (auto score = std::dynamic_pointer_cast<chunks::ScoreChunk>(getChunk(entry.sectionId))) {
            return score;
        }
    }
    return nullptr;
}

std::vector<std::shared_ptr<chunks::TextChunk>> DirectorFile::getTextChunksForMember(
    const std::shared_ptr<chunks::CastMemberChunk>& member) {
    if (!member || !keyTable_) {
        return {};
    }

    std::vector<std::shared_ptr<chunks::TextChunk>> result;
    for (const auto& entry : keyTable_->getEntriesForOwner(member->id())) {
        if (entry.fourcc != format::fourCC(format::ChunkType::STXT)) {
            continue;
        }
        if (auto text = std::dynamic_pointer_cast<chunks::TextChunk>(getChunk(entry.sectionId))) {
            result.push_back(text);
        }
    }
    return result;
}

std::shared_ptr<chunks::TextChunk> DirectorFile::getTextForMember(
    const std::shared_ptr<chunks::CastMemberChunk>& member) {
    std::shared_ptr<chunks::TextChunk> firstMatch;
    for (const auto& text : getTextChunksForMember(member)) {
        if (!firstMatch) {
            firstMatch = text;
        }
        if (text && !text->text().empty()) {
            return text;
        }
    }
    if (firstMatch) {
        return firstMatch;
    }
    if (!member) {
        return nullptr;
    }
    return std::dynamic_pointer_cast<chunks::TextChunk>(getChunk(member->id()));
}

int DirectorFile::stageWidth() const {
    return config_ ? config_->stageWidth() : 0;
}

int DirectorFile::stageHeight() const {
    return config_ ? config_->stageHeight() : 0;
}

int DirectorFile::tempo() const {
    return config_ ? config_->tempo() : 15;
}

int DirectorFile::getScoreTempo(int frame) const {
    return scoreChunk_ ? scoreChunk_->getFrameTempo(frame) : -1;
}

std::optional<chunks::ScoreChunk::PaletteChannelData> DirectorFile::getScorePalette(int frame) const {
    return scoreChunk_ ? scoreChunk_->getFramePalette(frame) : std::nullopt;
}

std::shared_ptr<const bitmap::Palette> DirectorFile::resolvePalette(int paletteId) {
    return paletteResolver().resolve(paletteId);
}

std::shared_ptr<const bitmap::Palette> DirectorFile::resolvePaletteExact(int paletteId) {
    return paletteResolver().resolveExact(paletteId);
}

std::shared_ptr<const bitmap::Palette> DirectorFile::resolvePaletteByMemberNumber(int memberNumber) {
    return paletteResolver().resolve(memberNumber - 1);
}

std::shared_ptr<chunks::Chunk> DirectorFile::getChunk(id::ChunkId id) {
    if (const auto found = chunks_.find(id.value()); found != chunks_.end()) {
        return found->second;
    }
    return reparseChunk(id);
}

const DirectorChunkInfo* DirectorFile::getChunkInfo(id::ChunkId id) const {
    if (const auto found = chunkInfo_.find(id.value()); found != chunkInfo_.end()) {
        return &found->second;
    }
    return nullptr;
}

void DirectorFile::releaseNonEssentialChunks() {
    for (auto it = chunks_.begin(); it != chunks_.end();) {
        const auto& chunk = it->second;
        if (std::dynamic_pointer_cast<chunks::SoundChunk>(chunk) ||
            std::dynamic_pointer_cast<chunks::MediaChunk>(chunk) ||
            std::dynamic_pointer_cast<chunks::RawChunk>(chunk)) {
            it = chunks_.erase(it);
        } else {
            ++it;
        }
    }
}

std::shared_ptr<DirectorFile> DirectorFile::load(const std::vector<std::uint8_t>& data) {
    io::BinaryReader reader(data, io::ByteOrder::BigEndian);
    const auto containerFourCC = reader.readFourCC();
    const auto container = format::chunkTypeFromFourCC(containerFourCC);

    io::ByteOrder endian = io::ByteOrder::BigEndian;
    bool isRiff = false;
    if (container == format::ChunkType::RIFX) {
        endian = io::ByteOrder::BigEndian;
    } else if (container == format::ChunkType::XFIR) {
        endian = io::ByteOrder::LittleEndian;
    } else if (container == format::ChunkType::RIFF || container == format::ChunkType::FFIR) {
        endian = io::ByteOrder::LittleEndian;
        isRiff = true;
    } else {
        throw DirectorFileLoadError("Not a supported Director RIFX/XFIR/RIFF file: " +
                                    io::BinaryReader::fourCCToString(containerFourCC));
    }

    reader.setOrder(endian);
    (void)reader.readI32();
    if (isRiff) {
        const auto rmmpFourCC = reader.readFourCC();
        if (format::chunkTypeFromFourCC(rmmpFourCC) != format::ChunkType::RMMP) {
            throw DirectorFileLoadError("Expected RMMP marker in RIFF file, got " +
                                        io::BinaryReader::fourCCToString(rmmpFourCC));
        }
        auto file = loadRIFF(reader, endian);
        file->dataStore_ = data;
        return file;
    }

    const auto movieType = format::chunkTypeFromFourCC(reader.readI32());
    if (format::isAfterburner(movieType)) {
        auto file = loadAfterburner(reader, endian, movieType);
        file->dataStore_ = data;
        return file;
    }

    auto file = loadRIFX(reader, endian, movieType);
    file->dataStore_ = data;
    return file;
}

std::shared_ptr<DirectorFile> DirectorFile::loadRIFX(io::BinaryReader& reader,
                                                     io::ByteOrder endian,
                                                     format::ChunkType movieType) {
    auto file = std::make_shared<DirectorFile>(endian, false, 0, movieType);

    const auto imapFourCC = reader.readFourCC();
    if (format::chunkTypeFromFourCC(imapFourCC) != format::ChunkType::IMAP) {
        throw DirectorFileLoadError("Expected imap chunk, got " + io::BinaryReader::fourCCToString(imapFourCC));
    }

    const int imapLen = reader.readI32();
    (void)reader.readI32();
    const int mmapOffset = reader.readI32();
    int imapDirectorVersion = 0;
    if (imapLen >= 12 && reader.bytesLeft() >= 4) {
        imapDirectorVersion = reader.readI32();
    }
    file->setVersion(imapDirectorVersion);

    reader.seek(static_cast<std::size_t>(mmapOffset));
    const auto mmapFourCC = reader.readFourCC();
    if (format::chunkTypeFromFourCC(mmapFourCC) != format::ChunkType::MMAP) {
        throw DirectorFileLoadError("Expected mmap chunk, got " + io::BinaryReader::fourCCToString(mmapFourCC));
    }

    (void)reader.readI32();
    (void)reader.readI16();
    (void)reader.readI16();
    (void)reader.readI32();
    const int chunkCountUsed = reader.readI32();
    (void)reader.readI32();
    reader.skip(4);
    (void)reader.readI32();

    for (int index = 0; index < chunkCountUsed && reader.bytesLeft() >= 20; ++index) {
        const auto fourcc = reader.readU32();
        const int length = reader.readI32();
        const int offset = reader.readI32();
        (void)reader.readI16();
        reader.skip(2);
        (void)reader.readI32();

        if (fourcc != 0 && offset > 0 && length >= 0) {
            auto chunkId = id::ChunkId(index);
            file->chunkInfo_.insert_or_assign(chunkId.value(), DirectorChunkInfo{chunkId, fourcc, offset + 8, length, length});
        }
    }

    for (const auto& [chunkIdValue, info] : file->chunkInfo_) {
        if (info.type() == format::ChunkType::DRCF || info.type() == format::ChunkType::VWCF) {
            auto chunkReader = reader.sliceReaderAt(static_cast<std::size_t>(info.offset), static_cast<std::size_t>(info.length));
            auto config = chunks::ConfigChunk::read(file.get(), chunkReader, info.id, 0);
            file->config_ = std::make_shared<chunks::ConfigChunk>(std::move(config));
            file->setVersion(file->config_->directorVersion());
            break;
        }
    }

    const int version = file->config_ ? file->config_->directorVersion() : imapDirectorVersion;
    const bool capitalX = std::any_of(file->chunkInfo_.begin(), file->chunkInfo_.end(), [](const auto& entry) {
        return entry.second.fourcc == io::BinaryReader::fourCC("LctX");
    });
    file->setCapitalX(capitalX);

    for (const auto& [chunkIdValue, info] : file->chunkInfo_) {
        try {
            auto chunkReader = reader.sliceReaderAt(static_cast<std::size_t>(info.offset), static_cast<std::size_t>(info.length));
            auto chunk = file->parseChunkFromReader(chunkReader, info, version, file->capitalX_);
            if (chunk) {
                file->chunks_[info.id.value()] = chunk;
                file->categorizeChunk(chunk);
                if (std::dynamic_pointer_cast<chunks::ScriptContextChunk>(chunk)) {
                    file->setCapitalX(info.fourcc == io::BinaryReader::fourCC("LctX"));
                }
            }
        } catch (const std::exception&) {
        }
    }

    return file;
}

std::shared_ptr<DirectorFile> DirectorFile::loadRIFF(io::BinaryReader& reader, io::ByteOrder endian) {
    auto file = std::make_shared<DirectorFile>(endian, false, 0, format::ChunkType::MV93);

    const auto cftcFourCC = reader.readFourCC();
    if (io::BinaryReader::fourCCToString(cftcFourCC) != "CFTC") {
        throw DirectorFileLoadError("Expected CFTC in RIFF file, got " + io::BinaryReader::fourCCToString(cftcFourCC));
    }

    const int cftcSize = reader.readI32();
    const auto cftcStart = reader.position();
    if (reader.bytesLeft() < 4) {
        return file;
    }
    reader.skip(4);

    int chunkIndex = 0;
    const auto cftcEnd = std::min(reader.length(), cftcStart + static_cast<std::size_t>(std::max(0, cftcSize)));
    while (reader.position() < cftcEnd && reader.bytesLeft() >= 16) {
        const auto tag = reader.readFourCC();
        if (tag == 0) {
            break;
        }

        const int size = reader.readI32();
        (void)reader.readI32();
        const int offset = reader.readI32();
        const auto savedPosition = reader.position();
        const int resourceHeaderOffset = offset + 12;

        if (resourceHeaderOffset >= 0 && static_cast<std::size_t>(resourceHeaderOffset) < reader.length()) {
            reader.seek(static_cast<std::size_t>(resourceHeaderOffset));
            if (reader.bytesLeft() >= 1) {
                const int nameLength = reader.readU8();
                int dataOffset = resourceHeaderOffset + 1 + nameLength;
                if ((dataOffset % 2) != 0) {
                    ++dataOffset;
                }
                int dataSize = size - 4 - 1 - nameLength;
                if (dataSize < 0) {
                    dataSize = size;
                }

                if (dataOffset >= 0 && dataSize >= 0 &&
                    static_cast<std::size_t>(dataOffset) <= reader.length()) {
                    auto chunkId = id::ChunkId(chunkIndex);
                    file->chunkInfo_.insert_or_assign(chunkId.value(),
                                                      DirectorChunkInfo{chunkId, tag, dataOffset, dataSize, dataSize});
                    ++chunkIndex;
                }
            }
        }

        reader.seek(savedPosition);
    }

    for (const auto& [chunkIdValue, info] : file->chunkInfo_) {
        if (info.type() == format::ChunkType::DRCF || info.type() == format::ChunkType::VWCF) {
            try {
                auto chunkReader = reader.sliceReaderAt(static_cast<std::size_t>(info.offset), static_cast<std::size_t>(info.length));
                auto config = chunks::ConfigChunk::read(file.get(), chunkReader, info.id, 0);
                file->config_ = std::make_shared<chunks::ConfigChunk>(std::move(config));
                file->setVersion(file->config_->directorVersion());
                break;
            } catch (const std::exception&) {
            }
        }
    }

    const int version = file->config_ ? file->config_->directorVersion() : 0;
    const bool capitalX = std::any_of(file->chunkInfo_.begin(), file->chunkInfo_.end(), [](const auto& entry) {
        return entry.second.fourcc == io::BinaryReader::fourCC("LctX");
    });
    file->setCapitalX(capitalX);

    for (const auto& [chunkIdValue, info] : file->chunkInfo_) {
        try {
            auto chunkReader = reader.sliceReaderAt(static_cast<std::size_t>(info.offset), static_cast<std::size_t>(info.length));
            auto chunk = file->parseChunkFromReader(chunkReader, info, version, file->capitalX_);
            if (chunk) {
                file->chunks_[info.id.value()] = chunk;
                file->categorizeChunk(chunk);
                if (std::dynamic_pointer_cast<chunks::ScriptContextChunk>(chunk)) {
                    file->setCapitalX(info.fourcc == io::BinaryReader::fourCC("LctX"));
                }
            }
        } catch (const std::exception&) {
        }
    }

    return file;
}

std::shared_ptr<DirectorFile> DirectorFile::loadAfterburner(io::BinaryReader& reader,
                                                            io::ByteOrder endian,
                                                            format::ChunkType movieType) {
    auto file = std::make_shared<DirectorFile>(endian, true, 0, movieType);
    format::AfterburnerReader afterburnerReader(reader, endian);
    afterburnerReader.parse();

    int version = afterburnerReader.directorVersion();
    file->setVersion(version);
    const auto abInfos = afterburnerReader.chunkInfos();
    const bool capitalX = std::any_of(abInfos.begin(), abInfos.end(), [](const auto& info) {
        return info.fourCC == "LctX";
    });
    file->setCapitalX(capitalX);

    for (const auto& abInfo : abInfos) {
        const auto fourcc = io::BinaryReader::fourCC(abInfo.fourCC);
        auto chunkId = id::ChunkId(abInfo.resourceId);
        file->chunkInfo_.insert_or_assign(chunkId.value(),
                                          DirectorChunkInfo{chunkId, fourcc, abInfo.offset, abInfo.compressedSize, abInfo.uncompressedSize});
    }

    for (const auto& abInfo : abInfos) {
        const auto type = format::chunkTypeFromString(abInfo.fourCC);
        if (type == format::ChunkType::DRCF || type == format::ChunkType::VWCF) {
            const auto chunkData = afterburnerReader.getChunkData(abInfo.resourceId);
            if (!chunkData.has_value()) {
                continue;
            }

            auto chunkReader = io::BinaryReader(chunkData.value(), endian);
            auto chunkId = id::ChunkId(abInfo.resourceId);
            auto config = chunks::ConfigChunk::read(file.get(), chunkReader, chunkId, 0);
            file->config_ = std::make_shared<chunks::ConfigChunk>(std::move(config));
            version = file->config_->directorVersion();
            file->setVersion(version);
            break;
        }
    }

    for (const auto& abInfo : abInfos) {
        if (abInfo.resourceId == 2 && abInfo.fourCC == "ILS ") {
            continue;
        }

        const auto chunkData = afterburnerReader.getChunkData(abInfo.resourceId);
        if (!chunkData.has_value()) {
            continue;
        }

        auto infoIt = file->chunkInfo_.find(abInfo.resourceId);
        if (infoIt == file->chunkInfo_.end()) {
            continue;
        }

        try {
            auto chunkReader = io::BinaryReader(chunkData.value(), endian);
            auto chunk = file->parseChunkFromReader(chunkReader, infoIt->second, version, file->capitalX_);
            if (chunk) {
                file->chunks_[infoIt->second.id.value()] = chunk;
                file->categorizeChunk(chunk);
                if (std::dynamic_pointer_cast<chunks::ScriptContextChunk>(chunk)) {
                    file->setCapitalX(abInfo.fourCC == "LctX");
                }
            }
        } catch (const std::exception&) {
        }
    }

    file->afterburnerReader_ = std::make_unique<format::AfterburnerReader>(std::move(afterburnerReader));
    return file;
}

std::shared_ptr<chunks::Chunk> DirectorFile::parseChunkFromReader(io::BinaryReader& reader,
                                                                  const DirectorChunkInfo& info,
                                                                  int version,
                                                                  bool capitalX) {
    reader.setOrder(endian_);
    switch (info.type()) {
        case format::ChunkType::DRCF:
        case format::ChunkType::VWCF:
            return std::make_shared<chunks::ConfigChunk>(chunks::ConfigChunk::read(this, reader, info.id, version));
        case format::ChunkType::KEYp:
            return std::make_shared<chunks::KeyTableChunk>(chunks::KeyTableChunk::read(this, reader, info.id, version));
        case format::ChunkType::MCsL:
            return std::make_shared<chunks::CastListChunk>(chunks::CastListChunk::read(this, reader, info.id, version));
        case format::ChunkType::CASp:
            return std::make_shared<chunks::CastChunk>(chunks::CastChunk::read(this, reader, info.id, version));
        case format::ChunkType::CASt:
            return std::make_shared<chunks::CastMemberChunk>(chunks::CastMemberChunk::read(this, reader, info.id, version));
        case format::ChunkType::Lctx:
        case format::ChunkType::LctX:
            return std::make_shared<chunks::ScriptContextChunk>(chunks::ScriptContextChunk::read(this, reader, info.id, version));
        case format::ChunkType::Lnam:
            return std::make_shared<chunks::ScriptNamesChunk>(chunks::ScriptNamesChunk::read(this, reader, info.id, version));
        case format::ChunkType::Lscr:
            return std::make_shared<chunks::ScriptChunk>(chunks::ScriptChunk::read(this, reader, info.id, version, capitalX));
        case format::ChunkType::VWSC:
        case format::ChunkType::SCVW:
            return std::make_shared<chunks::ScoreChunk>(chunks::ScoreChunk::read(this, reader, info.id, version));
        case format::ChunkType::VWLB:
            return std::make_shared<chunks::FrameLabelsChunk>(chunks::FrameLabelsChunk::read(this, reader, info.id, version));
        case format::ChunkType::BITD:
            return std::make_shared<chunks::BitmapChunk>(chunks::BitmapChunk::read(this, reader, info.id, version));
        case format::ChunkType::CLUT:
            return std::make_shared<chunks::PaletteChunk>(chunks::PaletteChunk::read(this, reader, info.id, version));
        case format::ChunkType::STXT:
            return std::make_shared<chunks::TextChunk>(chunks::TextChunk::read(this, reader, info.id, version));
        case format::ChunkType::Fmap:
            return std::make_shared<chunks::FontMapChunk>(chunks::FontMapChunk::read(this, reader, info.id));
        case format::ChunkType::snd_:
            return std::make_shared<chunks::SoundChunk>(chunks::SoundChunk::read(this, reader, info.id));
        case format::ChunkType::ediM:
            return std::make_shared<chunks::MediaChunk>(chunks::MediaChunk::read(this, reader, info.id));
        default:
            return std::make_shared<chunks::RawChunk>(this, info.id, info.type(), reader.readBytes(reader.bytesLeft()));
    }
}

std::shared_ptr<chunks::Chunk> DirectorFile::reparseChunk(id::ChunkId id) {
    if (const auto found = chunks_.find(id.value()); found != chunks_.end()) {
        return found->second;
    }

    const auto* info = getChunkInfo(id);
    if (!info) {
        return nullptr;
    }

    try {
        io::BinaryReader chunkReader({}, endian_);
        if (afterburner_) {
            if (!afterburnerReader_) {
                return nullptr;
            }
            const auto chunkData = afterburnerReader_->getChunkData(id.value());
            if (!chunkData.has_value()) {
                return nullptr;
            }
            chunkReader = io::BinaryReader(chunkData.value(), endian_);
        } else {
            if (dataStore_.empty()) {
                return nullptr;
            }
            chunkReader = io::BinaryReader(dataStore_, endian_).sliceReaderAt(static_cast<std::size_t>(info->offset),
                                                                              static_cast<std::size_t>(info->length));
        }

        const int version = config_ ? config_->directorVersion() : version_;
        auto chunk = parseChunkFromReader(chunkReader, *info, version, capitalX_);
        if (chunk) {
            chunks_[id.value()] = chunk;
            categorizeChunk(chunk);
        }
        return chunk;
    } catch (const std::exception&) {
        return nullptr;
    }
}

void DirectorFile::categorizeChunk(const std::shared_ptr<chunks::Chunk>& chunk) {
    castMemberLookup_.reset();
    paletteResolver_.reset();
    scriptLookup_.reset();

    if (auto config = std::dynamic_pointer_cast<chunks::ConfigChunk>(chunk)) {
        config_ = config;
        version_ = config->directorVersion();
    } else if (auto keyTable = std::dynamic_pointer_cast<chunks::KeyTableChunk>(chunk)) {
        keyTable_ = keyTable;
    } else if (auto castList = std::dynamic_pointer_cast<chunks::CastListChunk>(chunk)) {
        castList_ = castList;
    } else if (auto scriptContext = std::dynamic_pointer_cast<chunks::ScriptContextChunk>(chunk)) {
        allScriptContexts_.push_back(scriptContext);
        if (!scriptContext_ || !scriptContext->entries().empty()) {
            scriptContext_ = scriptContext;
        }
    } else if (auto scriptNames = std::dynamic_pointer_cast<chunks::ScriptNamesChunk>(chunk)) {
        scriptNamesById_[scriptNames->id().value()] = scriptNames;
        if (!scriptNames->names().empty()) {
            scriptNames_ = scriptNames;
        }
    } else if (auto cast = std::dynamic_pointer_cast<chunks::CastChunk>(chunk)) {
        casts_.push_back(cast);
    } else if (auto castMember = std::dynamic_pointer_cast<chunks::CastMemberChunk>(chunk)) {
        castMembers_.push_back(castMember);
    } else if (auto script = std::dynamic_pointer_cast<chunks::ScriptChunk>(chunk)) {
        scripts_.push_back(script);
    } else if (auto score = std::dynamic_pointer_cast<chunks::ScoreChunk>(chunk)) {
        scoreChunk_ = score;
    } else if (auto frameLabels = std::dynamic_pointer_cast<chunks::FrameLabelsChunk>(chunk)) {
        frameLabelsChunk_ = frameLabels;
    } else if (auto palette = std::dynamic_pointer_cast<chunks::PaletteChunk>(chunk)) {
        palettes_.push_back(palette);
    } else if (auto fontMap = std::dynamic_pointer_cast<chunks::FontMapChunk>(chunk)) {
        fontMaps_.push_back(fontMap);
    }
}

lookup::CastMemberLookup& DirectorFile::castMemberLookup() {
    if (!castMemberLookup_) {
        castMemberLookup_ = std::make_unique<lookup::CastMemberLookup>(casts_, castMembers_, castList_, config_);
    }
    return *castMemberLookup_;
}

lookup::PaletteResolver& DirectorFile::paletteResolver() {
    if (!paletteResolver_) {
        paletteResolver_ = std::make_unique<lookup::PaletteResolver>(
            casts_, castMembers_, palettes_, castList_, config_, keyTable_, [this](id::ChunkId chunkId) {
                return getChunk(chunkId);
            });
    }
    return *paletteResolver_;
}

lookup::ScriptLookup& DirectorFile::scriptLookup() {
    if (!scriptLookup_) {
        scriptLookup_ = std::make_unique<lookup::ScriptLookup>(scripts_, allScriptContexts_, castMembers_);
    }
    return *scriptLookup_;
}

void DirectorFile::setVersion(int version) {
    version_ = version;
}

void DirectorFile::setCapitalX(bool capitalX) {
    capitalX_ = capitalX;
}

} // namespace libreshockwave
