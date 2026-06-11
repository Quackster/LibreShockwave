#pragma once

#include <map>
#include <optional>
#include <string>
#include <vector>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/chunks/ScoreChunk.hpp"
#include "libreshockwave/editor/model/ScoreCellData.hpp"

namespace libreshockwave::editor::score {

class ScoreDataBuilder {
public:
    using ScoreGrid = std::vector<std::vector<std::optional<model::ScoreCellData>>>;

    [[nodiscard]] ScoreGrid buildScoreData(DirectorFile& dirFile) const;
    [[nodiscard]] std::vector<std::string> buildColumnNames(const DirectorFile& dirFile) const;

private:
    [[nodiscard]] std::map<int, std::string> buildFrameScriptMap(DirectorFile& dirFile,
                                                                 const chunks::ScoreChunk& scoreChunk,
                                                                 int frameCount) const;
    [[nodiscard]] std::string resolveChannelCellName(DirectorFile& dirFile,
                                                     int channelIndex,
                                                     const chunks::ScoreChunk::ChannelData& data) const;
    [[nodiscard]] std::string resolveCastMemberName(DirectorFile& dirFile, int castLib, int castMember) const;
    [[nodiscard]] std::string resolveCastMemberByNumber(DirectorFile& dirFile, int castLib, int memberNumber) const;
};

} // namespace libreshockwave::editor::score
