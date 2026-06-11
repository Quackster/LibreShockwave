#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <exception>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/bitmap/Bitmap.hpp"
#include "libreshockwave/format/ChunkType.hpp"
#include "libreshockwave/player/Player.hpp"
#include "libreshockwave/player/PlayerState.hpp"
#include "libreshockwave/player/render/pipeline/RenderSprite.hpp"

namespace {

namespace fs = std::filesystem;

constexpr std::array<std::string_view, 5> kDirectorExtensions{
    ".cct",
    ".cst",
    ".dcr",
    ".dir",
    ".dxr",
};

struct RenderProbeOptions {
    bool allowEmpty = false;
    bool verbose = false;
    bool showCurrent = false;
    bool play = false;
    bool preloadCasts = true;
    int ticks = 0;
    int scriptTimeoutMs = 1000;
    std::size_t maxFailures = 25;
    std::size_t progressInterval = 250;
    std::vector<fs::path> roots;
};

struct RenderProbeSummary {
    std::size_t bytes = 0;
    std::size_t chunks = 0;
    std::size_t externalCasts = 0;
    std::size_t loadedExternalCasts = 0;
    std::size_t fetchedExternalCasts = 0;
    std::size_t externalCastRequests = 0;
    std::size_t sprites = 0;
    std::size_t bakedSprites = 0;
    std::size_t pixels = 0;
    std::size_t nonBackgroundPixels = 0;
    std::size_t transparentPixels = 0;
    int stageWidth = 0;
    int stageHeight = 0;
    int frame = 0;
    int frameCount = 0;
    int version = 0;
    bool afterburner = false;
    std::string movieType;
    std::string playerState;
    std::vector<std::string> scriptErrors;
};

class SkippedFile : public std::runtime_error {
public:
    explicit SkippedFile(const std::string& message) : std::runtime_error(message) {}
};

std::string toLower(std::string value) {
    std::ranges::transform(value, value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

bool hasDirectorExtension(const fs::path& path) {
    const auto extension = toLower(path.extension().string());
    return std::ranges::find(kDirectorExtensions, extension) != kDirectorExtensions.end();
}

std::string pathString(const fs::path& path) {
    return path.lexically_normal().string();
}

std::string printableFourCC(const std::vector<std::uint8_t>& data) {
    if (data.size() < 4) {
        return "<short>";
    }

    std::string result;
    result.reserve(4);
    for (std::size_t index = 0; index < 4; ++index) {
        const auto ch = data[index];
        result.push_back(std::isprint(ch) ? static_cast<char>(ch) : '.');
    }
    return result;
}

bool hasDirectorContainerHeader(const std::vector<std::uint8_t>& data) {
    if (data.size() < 4) {
        return false;
    }

    const std::string_view header(reinterpret_cast<const char*>(data.data()), 4);
    return header == "RIFX" || header == "XFIR" || header == "RIFF" || header == "FFIR";
}

std::string usage(const char* argv0) {
    std::ostringstream out;
    out << "Usage: " << argv0
        << " [--allow-empty] [--max-failures N] [--progress-interval N] [--show-current]"
        << " [--verbose] [--no-preload-casts] [--play] [--ticks N] [--script-timeout-ms N]"
        << " <file-or-directory>...\n"
        << "Loads Director .cct/.cst/.dcr/.dir/.dxr files through the native C++ player renderer "
        << "and reports frame-buffer statistics. By default local external casts are preloaded from "
        << "the movie directory but scripts are not run; --play prepares the movie foundation, "
        << "and --ticks N advances N playback ticks before rendering. "
        << "Lifecycle script dispatch is capped by --script-timeout-ms when --play is active; use 0 to disable it.\n"
        << "If no paths are supplied, /var/html is used.";
    return out.str();
}

std::size_t parseSize(std::string_view value, std::string_view optionName) {
    std::size_t consumed = 0;
    const auto parsed = std::stoull(std::string(value), &consumed, 10);
    if (consumed != value.size()) {
        throw std::runtime_error("Invalid value for " + std::string(optionName) + ": " + std::string(value));
    }
    return static_cast<std::size_t>(parsed);
}

int parseNonNegativeInt(std::string_view value, std::string_view optionName) {
    const auto parsed = parseSize(value, optionName);
    if (parsed > static_cast<std::size_t>(std::numeric_limits<int>::max())) {
        throw std::runtime_error("Value for " + std::string(optionName) + " is too large: " + std::string(value));
    }
    return static_cast<int>(parsed);
}

RenderProbeOptions parseOptions(int argc, char** argv) {
    RenderProbeOptions options;
    for (int index = 1; index < argc; ++index) {
        const std::string_view arg(argv[index]);
        if (arg == "--help" || arg == "-h") {
            std::cout << usage(argv[0]) << '\n';
            std::exit(0);
        }
        if (arg == "--allow-empty") {
            options.allowEmpty = true;
            continue;
        }
        if (arg == "--verbose" || arg == "-v") {
            options.verbose = true;
            continue;
        }
        if (arg == "--show-current") {
            options.showCurrent = true;
            continue;
        }
        if (arg == "--play") {
            options.play = true;
            continue;
        }
        if (arg == "--no-preload-casts") {
            options.preloadCasts = false;
            continue;
        }
        if (arg == "--max-failures") {
            if (index + 1 >= argc) {
                throw std::runtime_error("--max-failures requires a value");
            }
            options.maxFailures = parseSize(argv[++index], "--max-failures");
            continue;
        }
        constexpr std::string_view maxFailuresPrefix = "--max-failures=";
        if (arg.starts_with(maxFailuresPrefix)) {
            options.maxFailures = parseSize(arg.substr(maxFailuresPrefix.size()), "--max-failures");
            continue;
        }
        if (arg == "--progress-interval") {
            if (index + 1 >= argc) {
                throw std::runtime_error("--progress-interval requires a value");
            }
            options.progressInterval = parseSize(argv[++index], "--progress-interval");
            continue;
        }
        constexpr std::string_view progressIntervalPrefix = "--progress-interval=";
        if (arg.starts_with(progressIntervalPrefix)) {
            options.progressInterval = parseSize(arg.substr(progressIntervalPrefix.size()), "--progress-interval");
            continue;
        }
        if (arg == "--ticks") {
            if (index + 1 >= argc) {
                throw std::runtime_error("--ticks requires a value");
            }
            options.ticks = parseNonNegativeInt(argv[++index], "--ticks");
            options.play = true;
            continue;
        }
        constexpr std::string_view ticksPrefix = "--ticks=";
        if (arg.starts_with(ticksPrefix)) {
            options.ticks = parseNonNegativeInt(arg.substr(ticksPrefix.size()), "--ticks");
            options.play = true;
            continue;
        }
        if (arg == "--script-timeout-ms") {
            if (index + 1 >= argc) {
                throw std::runtime_error("--script-timeout-ms requires a value");
            }
            options.scriptTimeoutMs = parseNonNegativeInt(argv[++index], "--script-timeout-ms");
            continue;
        }
        constexpr std::string_view scriptTimeoutPrefix = "--script-timeout-ms=";
        if (arg.starts_with(scriptTimeoutPrefix)) {
            options.scriptTimeoutMs = parseNonNegativeInt(arg.substr(scriptTimeoutPrefix.size()),
                                                          "--script-timeout-ms");
            continue;
        }
        if (arg.starts_with("-")) {
            throw std::runtime_error("Unknown option: " + std::string(arg));
        }
        options.roots.emplace_back(arg);
    }

    if (options.roots.empty()) {
        options.roots.emplace_back("/var/html");
    }
    return options;
}

std::vector<std::uint8_t> readFile(const fs::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        throw std::runtime_error("Unable to open file");
    }

    input.seekg(0, std::ios::end);
    const auto end = input.tellg();
    if (end == std::ifstream::pos_type(-1)) {
        throw std::runtime_error("Unable to determine file size");
    }
    input.seekg(0, std::ios::beg);

    std::vector<std::uint8_t> data(static_cast<std::size_t>(end));
    if (!data.empty()) {
        input.read(reinterpret_cast<char*>(data.data()), static_cast<std::streamsize>(data.size()));
        if (!input) {
            throw std::runtime_error("Unable to read complete file");
        }
    }
    return data;
}

void appendRootFiles(const fs::path& root, std::vector<fs::path>& files) {
    std::error_code ec;
    if (!fs::exists(root, ec)) {
        throw std::runtime_error("Path does not exist: " + pathString(root));
    }

    if (fs::is_regular_file(root, ec)) {
        if (hasDirectorExtension(root)) {
            files.push_back(root);
        }
        return;
    }

    if (!fs::is_directory(root, ec)) {
        return;
    }

    fs::recursive_directory_iterator iterator(root, fs::directory_options::skip_permission_denied, ec);
    const fs::recursive_directory_iterator end;
    while (!ec && iterator != end) {
        std::error_code entryEc;
        const auto path = iterator->path();
        if (iterator->is_regular_file(entryEc) && !entryEc && hasDirectorExtension(path)) {
            files.push_back(path);
        }
        iterator.increment(ec);
    }
    if (ec) {
        throw std::runtime_error("Unable to scan directory " + pathString(root) + ": " + ec.message());
    }
}

std::vector<fs::path> collectFiles(const std::vector<fs::path>& roots) {
    std::vector<fs::path> files;
    for (const auto& root : roots) {
        appendRootFiles(root, files);
    }
    std::ranges::sort(files);
    files.erase(std::ranges::unique(files).begin(), files.end());
    return files;
}

std::size_t countBakedSprites(const std::vector<libreshockwave::player::render::pipeline::RenderSprite>& sprites) {
    return static_cast<std::size_t>(std::ranges::count_if(sprites, [](const auto& sprite) {
        return sprite.bakedBitmap() != nullptr;
    }));
}

struct ExternalCastStats {
    std::size_t externalCasts = 0;
    std::size_t loadedExternalCasts = 0;
    std::size_t fetchedExternalCasts = 0;
};

ExternalCastStats countExternalCastStats(libreshockwave::player::Player& player) {
    ExternalCastStats stats;
    for (const auto& [_, castLib] : player.castLibManager().castLibs()) {
        if (!castLib || !castLib->isExternal()) {
            continue;
        }
        ++stats.externalCasts;
        if (castLib->isLoaded()) {
            ++stats.loadedExternalCasts;
        }
        if (castLib->isFetched()) {
            ++stats.fetchedExternalCasts;
        }
    }
    return stats;
}

RenderProbeSummary probeFile(const fs::path& path, const RenderProbeOptions& options) {
    const auto data = readFile(path);
    if (!hasDirectorContainerHeader(data)) {
        throw SkippedFile("not a Director container header: " + printableFourCC(data));
    }

    auto directorFile = libreshockwave::DirectorFile::load(data);
    directorFile->setBasePath(path.parent_path().string());

    libreshockwave::player::Player player(directorFile);
    std::vector<std::string> scriptErrors;
    player.setErrorListener([&scriptErrors](std::string_view message, std::string_view errorDetail) {
        std::string error(message);
        if (!errorDetail.empty()) {
            error += ": ";
            error += errorDetail;
        }
        scriptErrors.push_back(std::move(error));
    });

    int externalCastRequests = 0;
    if (options.preloadCasts) {
        externalCastRequests = player.preloadAllCasts();
    }

    if (options.play) {
        player.vm().setTickDeadlineMs(options.scriptTimeoutMs);
        player.play();
        for (int tick = 0; tick < options.ticks; ++tick) {
            if (!player.tick()) {
                break;
            }
        }
    }

    const auto snapshot = player.frameSnapshot();
    const auto bitmap = snapshot.renderFrame();
    if (bitmap.width() != snapshot.stageWidth || bitmap.height() != snapshot.stageHeight) {
        throw std::runtime_error("Rendered bitmap dimensions do not match frame snapshot");
    }

    const std::uint32_t background = 0xFF000000U | static_cast<std::uint32_t>(snapshot.backgroundColor & 0xFFFFFF);
    std::size_t nonBackgroundPixels = 0;
    std::size_t transparentPixels = 0;
    for (const auto pixel : bitmap.pixels()) {
        if ((pixel >> 24) == 0) {
            ++transparentPixels;
        }
        if (pixel != background) {
            ++nonBackgroundPixels;
        }
    }

    const auto externalCastStats = countExternalCastStats(player);
    return RenderProbeSummary{
        data.size(),
        directorFile->chunks().size(),
        externalCastStats.externalCasts,
        externalCastStats.loadedExternalCasts,
        externalCastStats.fetchedExternalCasts,
        static_cast<std::size_t>(externalCastRequests),
        snapshot.sprites.size(),
        countBakedSprites(snapshot.sprites),
        bitmap.pixels().size(),
        nonBackgroundPixels,
        transparentPixels,
        snapshot.stageWidth,
        snapshot.stageHeight,
        player.currentFrame(),
        player.frameCount(),
        directorFile->version(),
        directorFile->isAfterburner(),
        libreshockwave::format::toString(directorFile->movieType()),
        std::string(libreshockwave::player::name(player.state())),
        std::move(scriptErrors),
    };
}

void printVerboseOk(const fs::path& path, const RenderProbeSummary& summary) {
    std::cout << "OK " << pathString(path)
              << " version=" << summary.version
              << " movie=" << summary.movieType
              << " afterburner=" << (summary.afterburner ? "yes" : "no")
              << " stage=" << summary.stageWidth << 'x' << summary.stageHeight
              << " frame=" << summary.frame << '/' << summary.frameCount
              << " state=" << summary.playerState
              << " chunks=" << summary.chunks
              << " externalCasts=" << summary.externalCasts
              << " loadedExternalCasts=" << summary.loadedExternalCasts
              << " fetchedExternalCasts=" << summary.fetchedExternalCasts
              << " externalCastRequests=" << summary.externalCastRequests
              << " sprites=" << summary.sprites
              << " bakedSprites=" << summary.bakedSprites
              << " pixels=" << summary.pixels
              << " nonBackgroundPixels=" << summary.nonBackgroundPixels
              << " transparentPixels=" << summary.transparentPixels
              << " scriptErrors=" << summary.scriptErrors.size()
              << '\n';

    for (const auto& error : summary.scriptErrors) {
        std::cout << "SCRIPT_ERROR " << pathString(path) << ": " << error << '\n';
    }
}

} // namespace

int main(int argc, char** argv) {
    try {
        const auto options = parseOptions(argc, argv);
        const auto files = collectFiles(options.roots);
        if (files.empty() && !options.allowEmpty) {
            std::cerr << "No Director files found. Use --allow-empty to treat this as success.\n";
            return 2;
        }

        std::cout << "Director render probe: files=" << files.size()
                  << " preloadCasts=" << (options.preloadCasts ? "yes" : "no")
                  << " play=" << (options.play ? "yes" : "no")
                  << " ticks=" << options.ticks
                  << " scriptTimeoutMs=" << (options.play ? options.scriptTimeoutMs : 0)
                  << '\n' << std::flush;

        std::size_t okCount = 0;
        std::size_t skippedCount = 0;
        std::size_t failureCount = 0;
        std::size_t totalBytes = 0;
        std::size_t totalChunks = 0;
        std::size_t totalExternalCasts = 0;
        std::size_t totalLoadedExternalCasts = 0;
        std::size_t totalFetchedExternalCasts = 0;
        std::size_t totalExternalCastRequests = 0;
        std::size_t totalSprites = 0;
        std::size_t totalBakedSprites = 0;
        std::size_t totalPixels = 0;
        std::size_t totalNonBackgroundPixels = 0;
        std::size_t totalTransparentPixels = 0;
        std::size_t totalScriptErrors = 0;

        for (std::size_t fileIndex = 0; fileIndex < files.size(); ++fileIndex) {
            const auto& file = files[fileIndex];
            if (options.showCurrent || options.verbose) {
                std::cout << "RENDER " << (fileIndex + 1) << '/' << files.size() << ' '
                          << pathString(file) << '\n' << std::flush;
            }

            try {
                const auto summary = probeFile(file, options);
                ++okCount;
                totalBytes += summary.bytes;
                totalChunks += summary.chunks;
                totalExternalCasts += summary.externalCasts;
                totalLoadedExternalCasts += summary.loadedExternalCasts;
                totalFetchedExternalCasts += summary.fetchedExternalCasts;
                totalExternalCastRequests += summary.externalCastRequests;
                totalSprites += summary.sprites;
                totalBakedSprites += summary.bakedSprites;
                totalPixels += summary.pixels;
                totalNonBackgroundPixels += summary.nonBackgroundPixels;
                totalTransparentPixels += summary.transparentPixels;
                totalScriptErrors += summary.scriptErrors.size();

                if (options.verbose) {
                    printVerboseOk(file, summary);
                } else if (options.progressInterval != 0 && okCount % options.progressInterval == 0) {
                    std::cout << "OK " << okCount << '/' << files.size() << '\n' << std::flush;
                }
            } catch (const SkippedFile& skipped) {
                ++skippedCount;
                if (options.verbose) {
                    std::cout << "SKIP " << pathString(file) << ": " << skipped.what() << '\n' << std::flush;
                }
            } catch (const std::exception& error) {
                ++failureCount;
                std::cerr << "FAIL " << pathString(file) << ": " << error.what() << '\n';
                if (options.maxFailures != 0 && failureCount >= options.maxFailures) {
                    std::cerr << "Stopping after " << failureCount << " failures.\n";
                    break;
                }
            }
        }

        std::cout << "Director render probe summary: ok=" << okCount
                  << " skipped=" << skippedCount
                  << " failed=" << failureCount
                  << " bytes=" << totalBytes
                  << " chunks=" << totalChunks
                  << " externalCasts=" << totalExternalCasts
                  << " loadedExternalCasts=" << totalLoadedExternalCasts
                  << " fetchedExternalCasts=" << totalFetchedExternalCasts
                  << " externalCastRequests=" << totalExternalCastRequests
                  << " sprites=" << totalSprites
                  << " bakedSprites=" << totalBakedSprites
                  << " pixels=" << totalPixels
                  << " nonBackgroundPixels=" << totalNonBackgroundPixels
                  << " transparentPixels=" << totalTransparentPixels
                  << " scriptErrors=" << totalScriptErrors
                  << '\n';

        if (okCount == 0 && !files.empty() && failureCount == 0 && !options.allowEmpty) {
            std::cerr << "No valid Director containers were rendered.\n";
            return 2;
        }
        return failureCount == 0 ? 0 : 1;
    } catch (const std::exception& error) {
        std::cerr << "Director render probe error: " << error.what() << '\n';
        std::cerr << usage(argv[0]) << '\n';
        return 2;
    }
}
