#include "libreshockwave/editor/score/FrameAppearanceFinder.hpp"

#include <algorithm>
#include <map>
#include <utility>

#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/chunks/FrameLabelsChunk.hpp"
#include "libreshockwave/chunks/ScoreChunk.hpp"
#include "libreshockwave/editor/format/ChannelNames.hpp"

namespace libreshockwave::editor::score {

std::vector<model::FrameAppearance> FrameAppearanceFinder::find(DirectorFile& dirFile, int memberId) const {
    std::vector<model::FrameAppearance> appearances;
    if (!dirFile.hasScore()) {
        return appearances;
    }

    const auto scoreChunk = dirFile.scoreChunk();
    if (scoreChunk == nullptr) {
        return appearances;
    }

    std::map<int, std::string> labelMap;
    if (const auto labels = dirFile.frameLabelsChunk()) {
        for (const auto& label : labels->labels()) {
            labelMap[label.frameNum.value()] = label.label;
        }
    }

    for (const auto& entry : scoreChunk->frameData().frameChannelData) {
        const auto& data = entry.data;
        const auto resolvedMember = dirFile.getCastMemberByIndex(data.castLib, data.castMember);
        if (resolvedMember == nullptr || resolvedMember->id().value() != memberId) {
            continue;
        }

        const int frameNumber = entry.frameIndex.value() + 1;
        std::string frameLabel;
        if (const auto label = labelMap.find(frameNumber); label != labelMap.end()) {
            frameLabel = label->second;
        }
        appearances.push_back(model::FrameAppearance{
            frameNumber,
            entry.channelIndex.value(),
            format::ChannelNames::get(entry.channelIndex.value()),
            std::move(frameLabel),
            data.posX,
            data.posY
        });
    }

    std::sort(appearances.begin(), appearances.end(), [](const auto& lhs, const auto& rhs) {
        if (lhs.frame != rhs.frame) {
            return lhs.frame < rhs.frame;
        }
        return lhs.channel < rhs.channel;
    });
    return appearances;
}

std::string FrameAppearanceFinder::format(const std::vector<model::FrameAppearance>& appearances) const {
    if (appearances.empty()) {
        return "Not used in score";
    }

    std::vector<std::string> ranges;
    int rangeStart = -1;
    int rangeEnd = -1;
    int lastChannel = -1;
    std::string lastChannelName;

    for (const auto& appearance : appearances) {
        if (lastChannel == appearance.channel && rangeEnd + 1 == appearance.frame) {
            rangeEnd = appearance.frame;
            continue;
        }

        if (rangeStart > 0) {
            ranges.push_back(formatRange(rangeStart, rangeEnd, lastChannelName));
        }
        rangeStart = appearance.frame;
        rangeEnd = appearance.frame;
        lastChannel = appearance.channel;
        lastChannelName = appearance.channelName;
    }

    if (rangeStart > 0) {
        ranges.push_back(formatRange(rangeStart, rangeEnd, lastChannelName));
    }

    std::string result;
    const auto visibleRanges = ranges.size() <= 5 ? ranges.size() : 3U;
    for (std::size_t index = 0; index < visibleRanges; ++index) {
        if (!result.empty()) {
            result += ", ";
        }
        result += ranges[index];
    }
    if (ranges.size() > 5) {
        result += " ... and " + std::to_string(ranges.size() - 3U) + " more";
    }
    return result;
}

std::string FrameAppearanceFinder::formatRange(int start, int end, std::string_view channelName) {
    if (start == end) {
        return "Frame " + std::to_string(start) + " (" + std::string(channelName) + ")";
    }
    return "Frames " + std::to_string(start) + "-" + std::to_string(end) + " (" + std::string(channelName) + ")";
}

} // namespace libreshockwave::editor::score
