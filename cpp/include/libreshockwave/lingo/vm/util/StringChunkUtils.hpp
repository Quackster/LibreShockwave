#pragma once

#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/lingo/Datum.hpp"

namespace libreshockwave::lingo::vm::util {

[[nodiscard]] std::string pickLineDelimiter(std::string_view value);
[[nodiscard]] std::string chunkDelimiter(StringChunkType chunkType, char itemDelimiter = ',');
[[nodiscard]] std::vector<std::string> splitIntoChunks(std::string_view value,
                                                       StringChunkType chunkType,
                                                       char itemDelimiter = ',');
[[nodiscard]] int countChunks(std::string_view value, StringChunkType chunkType, char itemDelimiter = ',');
[[nodiscard]] std::string getLastChunk(std::string_view value,
                                       StringChunkType chunkType,
                                       char itemDelimiter = ',');
[[nodiscard]] std::string getChunk(std::string_view value,
                                   StringChunkType chunkType,
                                   int index,
                                   char itemDelimiter = ',');
[[nodiscard]] std::string getChunkRange(std::string_view value,
                                        StringChunkType chunkType,
                                        int start,
                                        int end,
                                        char itemDelimiter = ',');
[[nodiscard]] std::string getItemRangeDirect(std::string_view value,
                                             int start,
                                             int end,
                                             char itemDelimiter = ',');
[[nodiscard]] std::string getWordRangeDirect(std::string_view value, int start, int end);
[[nodiscard]] std::string getLineRangeDirect(std::string_view value, int start, int end);

} // namespace libreshockwave::lingo::vm::util
