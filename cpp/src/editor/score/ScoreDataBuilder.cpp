#include "libreshockwave/editor/score/ScoreDataBuilder.hpp"

#include <memory>
#include <utility>

#include "libreshockwave/cast/MemberType.hpp"
#include "libreshockwave/chunks/CastChunk.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/chunks/FrameLabelsChunk.hpp"
#include "libreshockwave/editor/format/PaletteDescriptions.hpp"
#include "libreshockwave/id/Ids.hpp"

namespace libreshockwave::editor::score {
namespace {

std::string displayNameForMember(const chunks::CastMemberChunk& member) {
    if (!member.name().empty()) {
        return "#" + std::to_string(member.id().value()) + " " + member.name();
    }
    return "#" + std::to_string(member.id().value()) + " " + std::string(cast::name(member.memberType()));
}

} // namespace

ScoreDataBuilder::ScoreGrid ScoreDataBuilder::buildScoreData(DirectorFile& dirFile) const {
    const auto scoreChunk = dirFile.scoreChunk();
    if (scoreChunk == nullptr) {
        return {};
    }

    const int frameCount = scoreChunk->getFrameCount();
    const int channelCount = scoreChunk->getChannelCount();
    if (frameCount <= 0 || channelCount <= 0) {
        return {};
    }

    ScoreGrid data(static_cast<std::size_t>(channelCount));
    for (auto& row : data) {
        row.resize(static_cast<std::size_t>(frameCount));
    }

    const auto frameScriptMap = buildFrameScriptMap(dirFile, *scoreChunk, frameCount);
    for (const auto& entry : scoreChunk->frameData().frameChannelData) {
        const int frame = entry.frameIndex.value();
        const int channel = entry.channelIndex.value();
        const auto& channelData = entry.data;
        if (frame < 0 || frame >= frameCount || channel < 0 || channel >= channelCount || channelData.isEmpty()) {
            continue;
        }

        std::string displayName;
        if (channel == 0) {
            if (const auto script = frameScriptMap.find(frame); script != frameScriptMap.end()) {
                displayName = script->second;
            } else {
                displayName = resolveChannelCellName(dirFile, channel, channelData);
            }
        } else {
            displayName = resolveChannelCellName(dirFile, channel, channelData);
        }

        data[static_cast<std::size_t>(channel)][static_cast<std::size_t>(frame)] = model::ScoreCellData{
            channelData.castLib,
            channelData.castMember,
            channelData.spriteType,
            channelData.ink,
            channelData.posX,
            channelData.posY,
            channelData.width,
            channelData.height,
            std::move(displayName)
        };
    }

    return data;
}

std::vector<std::string> ScoreDataBuilder::buildColumnNames(const DirectorFile& dirFile) const {
    const auto scoreChunk = dirFile.scoreChunk();
    if (scoreChunk == nullptr) {
        return {};
    }

    const int frameCount = scoreChunk->getFrameCount();
    if (frameCount <= 0) {
        return {};
    }

    std::map<int, std::string> labelMap;
    if (const auto frameLabels = dirFile.frameLabelsChunk()) {
        for (const auto& label : frameLabels->labels()) {
            labelMap[label.frameNum.value()] = label.label;
        }
    }

    std::vector<std::string> columnNames;
    columnNames.reserve(static_cast<std::size_t>(frameCount));
    for (int frame = 0; frame < frameCount; ++frame) {
        const int frameNumber = frame + 1;
        if (const auto label = labelMap.find(frameNumber); label != labelMap.end()) {
            columnNames.push_back(std::to_string(frameNumber) + " [" + label->second + "]");
        } else {
            columnNames.push_back(std::to_string(frameNumber));
        }
    }
    return columnNames;
}

std::map<int, std::string> ScoreDataBuilder::buildFrameScriptMap(DirectorFile& dirFile,
                                                                 const chunks::ScoreChunk& scoreChunk,
                                                                 int frameCount) const {
    std::map<int, std::string> frameScriptMap;
    for (const auto& interval : scoreChunk.frameIntervals()) {
        if (!interval.secondary.has_value() || interval.primary.channelIndex != 0) {
            continue;
        }

        const auto scriptName = resolveCastMemberByNumber(
            dirFile,
            interval.secondary->castLib,
            interval.secondary->castMember);
        const int startFrame = interval.primary.startFrame - 1;
        const int endFrame = interval.primary.endFrame - 1;
        for (int frame = startFrame; frame <= endFrame; ++frame) {
            if (frame >= 0 && frame < frameCount) {
                frameScriptMap[frame] = scriptName;
            }
        }
    }
    return frameScriptMap;
}

std::string ScoreDataBuilder::resolveChannelCellName(DirectorFile& dirFile,
                                                     int channelIndex,
                                                     const chunks::ScoreChunk::ChannelData& data) const {
    switch (channelIndex) {
        case 0:
            return "";
        case 1:
            return format::PaletteDescriptions::get(data.castMember);
        case 2:
            if (data.castMember > 0) {
                return "Trans #" + std::to_string(data.castMember);
            }
            return "";
        case 3:
        case 4:
        case 5:
        default:
            return resolveCastMemberName(dirFile, data.castLib, data.castMember);
    }
}

std::string ScoreDataBuilder::resolveCastMemberName(DirectorFile& dirFile, int castLib, int castMember) const {
    const auto member = dirFile.getCastMemberByIndex(castLib, castMember);
    if (member != nullptr) {
        return displayNameForMember(*member);
    }
    return "Member #" + std::to_string(castMember);
}

std::string ScoreDataBuilder::resolveCastMemberByNumber(DirectorFile& dirFile, int castLib, int memberNumber) const {
    (void)castLib;
    const int index = memberNumber - 1;
    for (const auto& cast : dirFile.casts()) {
        if (cast == nullptr || index < 0 || index >= static_cast<int>(cast->memberIds().size())) {
            continue;
        }
        const int chunkId = cast->memberIds()[static_cast<std::size_t>(index)];
        if (chunkId == 0) {
            continue;
        }
        const auto member = std::dynamic_pointer_cast<chunks::CastMemberChunk>(dirFile.getChunk(id::ChunkId(chunkId)));
        if (member != nullptr) {
            return displayNameForMember(*member);
        }
    }

    const auto fallback = std::dynamic_pointer_cast<chunks::CastMemberChunk>(dirFile.getChunk(id::ChunkId(memberNumber)));
    if (fallback != nullptr) {
        return displayNameForMember(*fallback);
    }
    return "Member #" + std::to_string(memberNumber);
}

} // namespace libreshockwave::editor::score
