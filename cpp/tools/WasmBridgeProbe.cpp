#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

#include "libreshockwave/player/web/WasmExports.hpp"

namespace {

namespace fs = std::filesystem;

constexpr std::array<std::string_view, 5> kDirectorExtensions{
    ".cct",
    ".cst",
    ".dcr",
    ".dir",
    ".dxr",
};

struct BridgeProbeOptions {
    bool allowEmpty = false;
    bool verbose = false;
    bool showCurrent = false;
    bool play = false;
    bool preloadCasts = true;
    int ticks = 0;
    int scanFrames = 0;
    int scriptTimeoutMs = 1000;
    std::size_t maxFailures = 25;
    std::size_t progressInterval = 250;
    std::vector<std::string> traceHandlers;
    std::vector<fs::path> roots;
};

struct RgbaStats {
    std::size_t pixels = 0;
    std::size_t nonTransparentPixels = 0;
    std::size_t nonBlackPixels = 0;
};

struct FetchDeliveryStats {
    std::size_t pendingFetches = 0;
    std::size_t deliveredFetches = 0;
    std::size_t failedFetches = 0;
    std::size_t deliveredBytes = 0;
};

struct NavigationStats {
    std::size_t gotoNetPages = 0;
    std::size_t gotoNetMovies = 0;
    std::vector<std::string> pageUrls;
    std::vector<std::string> movieUrls;
};

struct BridgeProbeSummary {
    std::size_t bytes = 0;
    std::size_t preloadRequests = 0;
    FetchDeliveryStats fetches;
    NavigationStats navigation;
    std::size_t renderBytes = 0;
    std::size_t sprites = 0;
    std::size_t pixels = 0;
    std::size_t nonTransparentPixels = 0;
    std::size_t nonBlackPixels = 0;
    std::size_t scannedFrames = 0;
    std::size_t framesWithSprites = 0;
    std::size_t framesWithNonBlackPixels = 0;
    int firstNonBlackFrame = 0;
    int stageWidth = 0;
    int stageHeight = 0;
    int frame = 0;
    int frameCount = 0;
    bool renderable = false;
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
        << " [--scan-frames N] [--trace-handler NAME]"
        << " <file-or-directory>...\n"
        << "Loads Director files through the native C++ WASM C ABI exports, optionally satisfies pending "
        << "host fetches from local sidecar files, renders through libreshockwave_wasm_render(), and reports "
        << "bridge-facing frame-buffer statistics.\n"
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

BridgeProbeOptions parseOptions(int argc, char** argv) {
    BridgeProbeOptions options;
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
        if (arg == "--trace-handler") {
            if (index + 1 >= argc) {
                throw std::runtime_error("--trace-handler requires a value");
            }
            options.traceHandlers.emplace_back(argv[++index]);
            continue;
        }
        constexpr std::string_view traceHandlerPrefix = "--trace-handler=";
        if (arg.starts_with(traceHandlerPrefix)) {
            options.traceHandlers.emplace_back(arg.substr(traceHandlerPrefix.size()));
            continue;
        }
        if (arg == "--scan-frames") {
            if (index + 1 >= argc) {
                throw std::runtime_error("--scan-frames requires a value");
            }
            options.scanFrames = parseNonNegativeInt(argv[++index], "--scan-frames");
            continue;
        }
        constexpr std::string_view scanFramesPrefix = "--scan-frames=";
        if (arg.starts_with(scanFramesPrefix)) {
            options.scanFrames = parseNonNegativeInt(arg.substr(scanFramesPrefix.size()), "--scan-frames");
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

std::uint8_t* stringBuffer() {
    return reinterpret_cast<std::uint8_t*>(libreshockwave_wasm_get_string_buffer_address());
}

std::string readStringBuffer(int length) {
    if (length <= 0) {
        return "";
    }
    const auto* buffer = reinterpret_cast<const char*>(stringBuffer());
    return buffer == nullptr ? "" : std::string(buffer, static_cast<std::size_t>(length));
}

std::string takeLastError() {
    const int length = libreshockwave_wasm_get_last_error();
    return readStringBuffer(length);
}

void writeStringBuffer(std::string_view value) {
    auto* buffer = stringBuffer();
    if (buffer == nullptr) {
        throw std::runtime_error("WASM string buffer is unavailable");
    }
    if (value.size() > static_cast<std::size_t>(libreshockwave_wasm_get_string_buffer_capacity())) {
        throw std::runtime_error("Value does not fit in WASM string buffer");
    }
    if (!value.empty()) {
        std::memcpy(buffer, value.data(), value.size());
    }
}

void installTraceHandlers(const std::vector<std::string>& handlers) {
    libreshockwave_wasm_clear_trace_handlers();
    for (const auto& handler : handlers) {
        writeStringBuffer(handler);
        libreshockwave_wasm_add_trace_handler(static_cast<int>(handler.size()));
    }
}

std::string cleanUrl(std::string value) {
    if (const auto query = value.find('?'); query != std::string::npos) {
        value = value.substr(0, query);
    }
    if (const auto hash = value.find('#'); hash != std::string::npos) {
        value = value.substr(0, hash);
    }
    constexpr std::string_view filePrefix = "file://";
    if (value.starts_with(filePrefix)) {
        value = value.substr(filePrefix.size());
    }
    return value;
}

int hexValue(char ch) {
    if (ch >= '0' && ch <= '9') {
        return ch - '0';
    }
    if (ch >= 'A' && ch <= 'F') {
        return ch - 'A' + 10;
    }
    if (ch >= 'a' && ch <= 'f') {
        return ch - 'a' + 10;
    }
    return -1;
}

std::string decodeUrlPath(std::string_view value) {
    std::string decoded;
    decoded.reserve(value.size());
    for (std::size_t index = 0; index < value.size(); ++index) {
        const char ch = value[index];
        if (ch == '%' && index + 2 < value.size()) {
            const int high = hexValue(value[index + 1]);
            const int low = hexValue(value[index + 2]);
            if (high >= 0 && low >= 0) {
                decoded.push_back(static_cast<char>((high << 4) | low));
                index += 2;
                continue;
            }
        }
        decoded.push_back(ch == '+' ? ' ' : ch);
    }
    return decoded;
}

void addFetchPathCandidates(std::vector<fs::path>& candidates,
                            std::string_view url,
                            const fs::path& movieDir) {
    const std::string cleaned = cleanUrl(std::string(url));
    const std::string decoded = decodeUrlPath(cleaned);
    for (const auto& value : {cleaned, decoded}) {
        if (value.empty()) {
            continue;
        }
        fs::path path(value);
        if (path.is_absolute()) {
            candidates.push_back(path);
        } else {
            candidates.push_back(movieDir / path);
        }
        const auto fileName = path.filename();
        if (!fileName.empty()) {
            candidates.push_back(movieDir / fileName);
        }
    }
}

std::optional<fs::path> resolveFetchPath(const std::vector<std::string>& urls, const fs::path& movieDir) {
    std::vector<fs::path> candidates;
    for (const auto& url : urls) {
        addFetchPathCandidates(candidates, url, movieDir);
    }

    std::error_code ec;
    for (const auto& candidate : candidates) {
        if (candidate.empty()) {
            continue;
        }
        if (fs::is_regular_file(candidate, ec) && !ec) {
            return candidate;
        }
        ec.clear();
    }
    return std::nullopt;
}

struct PendingFetch {
    int taskId = 0;
    std::vector<std::string> urls;
};

std::vector<PendingFetch> collectPendingFetches() {
    std::vector<PendingFetch> result;
    const int count = libreshockwave_wasm_get_pending_fetch_count();
    result.reserve(static_cast<std::size_t>(std::max(0, count)));
    for (int index = 0; index < count; ++index) {
        PendingFetch fetch;
        fetch.taskId = libreshockwave_wasm_get_pending_fetch_task_id(index);
        const int urlLen = libreshockwave_wasm_get_pending_fetch_url(index);
        if (urlLen > 0) {
            fetch.urls.push_back(readStringBuffer(urlLen));
        }
        const int fallbackCount = libreshockwave_wasm_get_pending_fetch_fallback_count(index);
        for (int fallbackIndex = 0; fallbackIndex < fallbackCount; ++fallbackIndex) {
            const int fallbackLen = libreshockwave_wasm_get_pending_fetch_fallback_url(index, fallbackIndex);
            if (fallbackLen > 0) {
                fetch.urls.push_back(readStringBuffer(fallbackLen));
            }
        }
        result.push_back(std::move(fetch));
    }
    return result;
}

FetchDeliveryStats deliverPendingFetches(const fs::path& movieDir) {
    FetchDeliveryStats stats;
    const auto pending = collectPendingFetches();
    stats.pendingFetches = pending.size();
    for (const auto& fetch : pending) {
        const auto path = resolveFetchPath(fetch.urls, movieDir);
        if (!path.has_value()) {
            libreshockwave_wasm_deliver_fetch_error(fetch.taskId, 404);
            ++stats.failedFetches;
            continue;
        }

        const auto data = readFile(*path);
        auto* buffer = reinterpret_cast<std::uint8_t*>(
            libreshockwave_wasm_allocate_net_buffer(static_cast<int>(data.size())));
        if (buffer == nullptr && !data.empty()) {
            throw std::runtime_error("WASM net buffer is unavailable");
        }
        if (!data.empty()) {
            std::memcpy(buffer, data.data(), data.size());
        }
        libreshockwave_wasm_deliver_fetch_result(fetch.taskId, static_cast<int>(data.size()));
        ++stats.deliveredFetches;
        stats.deliveredBytes += data.size();
    }
    libreshockwave_wasm_drain_pending_fetches();
    return stats;
}

void addFetchStats(FetchDeliveryStats& target, const FetchDeliveryStats& source) {
    target.pendingFetches += source.pendingFetches;
    target.deliveredFetches += source.deliveredFetches;
    target.failedFetches += source.failedFetches;
    target.deliveredBytes += source.deliveredBytes;
}

NavigationStats drainNavigationRequests() {
    NavigationStats stats;
    while (true) {
        const int packedPage = libreshockwave_wasm_read_next_goto_net_page();
        if (packedPage == 0) {
            break;
        }
        const int urlLen = (packedPage >> 16) & 0xFFFF;
        const int targetLen = packedPage & 0xFFFF;
        const auto* buffer = reinterpret_cast<const char*>(stringBuffer());
        std::string url;
        if (buffer != nullptr && urlLen > 0) {
            url.assign(buffer, static_cast<std::size_t>(urlLen));
        }
        std::string target;
        if (buffer != nullptr && targetLen > 0) {
            target.assign(buffer + urlLen, static_cast<std::size_t>(targetLen));
        }
        ++stats.gotoNetPages;
        stats.pageUrls.push_back(target.empty() ? url : url + " target=" + target);
    }

    while (true) {
        const int movieLen = libreshockwave_wasm_read_next_goto_net_movie();
        if (movieLen <= 0) {
            break;
        }
        ++stats.gotoNetMovies;
        stats.movieUrls.push_back(readStringBuffer(movieLen));
    }
    return stats;
}

void addNavigationStats(NavigationStats& target, NavigationStats source) {
    target.gotoNetPages += source.gotoNetPages;
    target.gotoNetMovies += source.gotoNetMovies;
    target.pageUrls.insert(target.pageUrls.end(),
                           std::make_move_iterator(source.pageUrls.begin()),
                           std::make_move_iterator(source.pageUrls.end()));
    target.movieUrls.insert(target.movieUrls.end(),
                            std::make_move_iterator(source.movieUrls.begin()),
                            std::make_move_iterator(source.movieUrls.end()));
}

RgbaStats measureRgba(std::uintptr_t address, int byteLength) {
    RgbaStats stats;
    if (address == 0 || byteLength <= 0) {
        return stats;
    }

    const auto* data = reinterpret_cast<const std::uint8_t*>(address);
    stats.pixels = static_cast<std::size_t>(byteLength / 4);
    for (std::size_t pixel = 0; pixel < stats.pixels; ++pixel) {
        const auto offset = pixel * 4U;
        const auto r = data[offset];
        const auto g = data[offset + 1U];
        const auto b = data[offset + 2U];
        const auto a = data[offset + 3U];
        if (a != 0) {
            ++stats.nonTransparentPixels;
        }
        if (r != 0 || g != 0 || b != 0) {
            ++stats.nonBlackPixels;
        }
    }
    return stats;
}

BridgeProbeSummary probeFile(const fs::path& rawPath, const BridgeProbeOptions& options) {
    const auto path = fs::absolute(rawPath).lexically_normal();
    const auto data = readFile(path);
    if (!hasDirectorContainerHeader(data)) {
        throw SkippedFile("not a Director container header: " + printableFourCC(data));
    }

    auto* movieBuffer = reinterpret_cast<std::uint8_t*>(
        libreshockwave_wasm_allocate_buffer(static_cast<int>(data.size())));
    if (movieBuffer == nullptr && !data.empty()) {
        throw std::runtime_error("WASM movie buffer is unavailable");
    }
    if (!data.empty()) {
        std::memcpy(movieBuffer, data.data(), data.size());
    }

    const auto basePath = pathString(path);
    writeStringBuffer(basePath);
    const int packedStage = libreshockwave_wasm_load_movie(static_cast<int>(data.size()),
                                                           static_cast<int>(basePath.size()));
    const int loadedStageWidth = libreshockwave_wasm_stage_width();
    const int loadedStageHeight = libreshockwave_wasm_stage_height();
    const bool loadedRenderable = loadedStageWidth > 0 && loadedStageHeight > 0;
    if (packedStage == 0 && loadedRenderable) {
        const int errorLen = libreshockwave_wasm_get_last_error();
        const auto error = readStringBuffer(errorLen);
        throw std::runtime_error(error.empty() ? "WASM bridge failed to load movie" : error);
    }

    BridgeProbeSummary summary;
    summary.bytes = data.size();
    summary.stageWidth = loadedStageWidth;
    summary.stageHeight = loadedStageHeight;
    summary.renderable = loadedRenderable;
    summary.frame = libreshockwave_wasm_current_frame();
    summary.frameCount = libreshockwave_wasm_frame_count();
    libreshockwave_wasm_set_script_timeout_ms(options.scriptTimeoutMs);
    installTraceHandlers(options.traceHandlers);

    const auto pumpHostQueues = [&] {
        addFetchStats(summary.fetches, deliverPendingFetches(path.parent_path()));
        addNavigationStats(summary.navigation, drainNavigationRequests());
    };

    pumpHostQueues();
    if (options.preloadCasts) {
        summary.preloadRequests = static_cast<std::size_t>(std::max(0, libreshockwave_wasm_preload_casts()));
        pumpHostQueues();
    }

    if (options.play) {
        libreshockwave_wasm_play();
        pumpHostQueues();
        if (auto error = takeLastError(); !error.empty()) {
            summary.scriptErrors.push_back(std::move(error));
        }
        for (int tick = 0; tick < options.ticks; ++tick) {
            if (libreshockwave_wasm_tick() == 0) {
                break;
            }
            pumpHostQueues();
            if (auto error = takeLastError(); !error.empty()) {
                summary.scriptErrors.push_back(std::move(error));
            }
        }
    }

    if (summary.renderable) {
        const int renderBytes = libreshockwave_wasm_render();
        if (renderBytes != summary.stageWidth * summary.stageHeight * 4) {
            throw std::runtime_error("WASM bridge render size does not match stage dimensions");
        }
        const auto bitmapStats = measureRgba(libreshockwave_wasm_get_render_buffer_address(), renderBytes);
        summary.renderBytes = static_cast<std::size_t>(renderBytes);
        summary.sprites = static_cast<std::size_t>(std::max(0, libreshockwave_wasm_get_sprite_count()));
        summary.pixels = bitmapStats.pixels;
        summary.nonTransparentPixels = bitmapStats.nonTransparentPixels;
        summary.nonBlackPixels = bitmapStats.nonBlackPixels;
    }
    summary.frame = libreshockwave_wasm_current_frame();
    summary.frameCount = libreshockwave_wasm_frame_count();

    if (summary.renderable && options.scanFrames > 0 && summary.frameCount > 0) {
        const int framesToScan = std::min(options.scanFrames, summary.frameCount);
        for (int frameIndex = 1; frameIndex <= framesToScan; ++frameIndex) {
            libreshockwave_wasm_step_forward();
            pumpHostQueues();
            if (auto error = takeLastError(); !error.empty()) {
                summary.scriptErrors.push_back(std::move(error));
            }
            const int scanBytes = libreshockwave_wasm_render();
            if (scanBytes != summary.stageWidth * summary.stageHeight * 4) {
                throw std::runtime_error("WASM bridge scanned render size does not match stage dimensions");
            }
            const auto scanStats = measureRgba(libreshockwave_wasm_get_render_buffer_address(), scanBytes);
            ++summary.scannedFrames;
            if (libreshockwave_wasm_get_sprite_count() > 0) {
                ++summary.framesWithSprites;
            }
            if (scanStats.nonBlackPixels > 0) {
                ++summary.framesWithNonBlackPixels;
                if (summary.firstNonBlackFrame == 0) {
                    summary.firstNonBlackFrame = libreshockwave_wasm_current_frame();
                }
            }
        }
    }

    return summary;
}

void printVerboseOk(const fs::path& path, const BridgeProbeSummary& summary) {
    std::cout << "OK " << pathString(path)
              << " stage=" << summary.stageWidth << 'x' << summary.stageHeight
              << " renderable=" << (summary.renderable ? "yes" : "no")
              << " frame=" << summary.frame << '/' << summary.frameCount
              << " preloadRequests=" << summary.preloadRequests
              << " pendingFetches=" << summary.fetches.pendingFetches
              << " deliveredFetches=" << summary.fetches.deliveredFetches
              << " failedFetches=" << summary.fetches.failedFetches
              << " deliveredBytes=" << summary.fetches.deliveredBytes
              << " gotoNetPages=" << summary.navigation.gotoNetPages
              << " gotoNetMovies=" << summary.navigation.gotoNetMovies
              << " renderBytes=" << summary.renderBytes
              << " sprites=" << summary.sprites
              << " pixels=" << summary.pixels
              << " nonTransparentPixels=" << summary.nonTransparentPixels
              << " nonBlackPixels=" << summary.nonBlackPixels
              << " scannedFrames=" << summary.scannedFrames
              << " framesWithSprites=" << summary.framesWithSprites
              << " framesWithNonBlackPixels=" << summary.framesWithNonBlackPixels
              << " firstNonBlackFrame=" << summary.firstNonBlackFrame
              << " scriptErrors=" << summary.scriptErrors.size()
              << '\n';

    for (const auto& error : summary.scriptErrors) {
        std::cout << "SCRIPT_ERROR " << pathString(path) << ": " << error << '\n';
    }
    for (const auto& url : summary.navigation.pageUrls) {
        std::cout << "GOTO_NET_PAGE " << pathString(path) << ": " << url << '\n';
    }
    for (const auto& url : summary.navigation.movieUrls) {
        std::cout << "GOTO_NET_MOVIE " << pathString(path) << ": " << url << '\n';
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

        std::cout << "WASM bridge probe: files=" << files.size()
                  << " preloadCasts=" << (options.preloadCasts ? "yes" : "no")
                  << " play=" << (options.play ? "yes" : "no")
                  << " ticks=" << options.ticks
                  << " scanFrames=" << options.scanFrames
                  << " scriptTimeoutMs=" << (options.play || options.scanFrames > 0 ? options.scriptTimeoutMs : 0)
                  << '\n' << std::flush;

        std::size_t okCount = 0;
        std::size_t skippedCount = 0;
        std::size_t failureCount = 0;
        std::size_t totalBytes = 0;
        std::size_t totalPreloadRequests = 0;
        std::size_t totalPendingFetches = 0;
        std::size_t totalDeliveredFetches = 0;
        std::size_t totalFailedFetches = 0;
        std::size_t totalDeliveredBytes = 0;
        std::size_t totalGotoNetPages = 0;
        std::size_t totalGotoNetMovies = 0;
        std::size_t totalRenderBytes = 0;
        std::size_t totalSprites = 0;
        std::size_t totalPixels = 0;
        std::size_t totalNonTransparentPixels = 0;
        std::size_t totalNonBlackPixels = 0;
        std::size_t totalScannedFrames = 0;
        std::size_t totalFramesWithSprites = 0;
        std::size_t totalFramesWithNonBlackPixels = 0;
        std::size_t filesWithNonBlackScannedFrames = 0;
        std::size_t totalScriptErrors = 0;

        const auto printProgress = [&](std::size_t processedCount) {
            if (options.verbose || options.progressInterval == 0) {
                return;
            }
            if (processedCount % options.progressInterval != 0 && processedCount != files.size()) {
                return;
            }
            std::cout << "PROGRESS " << processedCount << '/' << files.size()
                      << " ok=" << okCount
                      << " skipped=" << skippedCount
                      << " failed=" << failureCount
                      << '\n' << std::flush;
        };

        for (std::size_t fileIndex = 0; fileIndex < files.size(); ++fileIndex) {
            const auto& file = files[fileIndex];
            bool stopAfterFile = false;
            if (options.showCurrent || options.verbose) {
                std::cout << "BRIDGE " << (fileIndex + 1) << '/' << files.size() << ' '
                          << pathString(file) << '\n' << std::flush;
            }

            try {
                const auto summary = probeFile(file, options);
                ++okCount;
                totalBytes += summary.bytes;
                totalPreloadRequests += summary.preloadRequests;
                totalPendingFetches += summary.fetches.pendingFetches;
                totalDeliveredFetches += summary.fetches.deliveredFetches;
                totalFailedFetches += summary.fetches.failedFetches;
                totalDeliveredBytes += summary.fetches.deliveredBytes;
                totalGotoNetPages += summary.navigation.gotoNetPages;
                totalGotoNetMovies += summary.navigation.gotoNetMovies;
                totalRenderBytes += summary.renderBytes;
                totalSprites += summary.sprites;
                totalPixels += summary.pixels;
                totalNonTransparentPixels += summary.nonTransparentPixels;
                totalNonBlackPixels += summary.nonBlackPixels;
                totalScannedFrames += summary.scannedFrames;
                totalFramesWithSprites += summary.framesWithSprites;
                totalFramesWithNonBlackPixels += summary.framesWithNonBlackPixels;
                if (summary.firstNonBlackFrame > 0) {
                    ++filesWithNonBlackScannedFrames;
                }
                totalScriptErrors += summary.scriptErrors.size();

                if (options.verbose) {
                    printVerboseOk(file, summary);
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
                    stopAfterFile = true;
                }
            }

            printProgress(fileIndex + 1);
            if (stopAfterFile) {
                break;
            }
        }

        std::cout << "WASM bridge probe summary: ok=" << okCount
                  << " skipped=" << skippedCount
                  << " failed=" << failureCount
                  << " bytes=" << totalBytes
                  << " preloadRequests=" << totalPreloadRequests
                  << " pendingFetches=" << totalPendingFetches
                  << " deliveredFetches=" << totalDeliveredFetches
                  << " failedFetches=" << totalFailedFetches
                  << " deliveredBytes=" << totalDeliveredBytes
                  << " gotoNetPages=" << totalGotoNetPages
                  << " gotoNetMovies=" << totalGotoNetMovies
                  << " renderBytes=" << totalRenderBytes
                  << " sprites=" << totalSprites
                  << " pixels=" << totalPixels
                  << " nonTransparentPixels=" << totalNonTransparentPixels
                  << " nonBlackPixels=" << totalNonBlackPixels
                  << " scannedFrames=" << totalScannedFrames
                  << " framesWithSprites=" << totalFramesWithSprites
                  << " framesWithNonBlackPixels=" << totalFramesWithNonBlackPixels
                  << " filesWithNonBlackScannedFrames=" << filesWithNonBlackScannedFrames
                  << " scriptErrors=" << totalScriptErrors
                  << '\n';

        if (okCount == 0 && !files.empty() && failureCount == 0 && !options.allowEmpty) {
            std::cerr << "No valid Director containers were loaded through the WASM bridge.\n";
            return 2;
        }
        return failureCount == 0 ? 0 : 1;
    } catch (const std::exception& error) {
        std::cerr << "WASM bridge probe error: " << error.what() << '\n';
        std::cerr << usage(argv[0]) << '\n';
        return 2;
    }
}
