// libreshockwave_export_ts
//
// Director movie -> self-contained TypeScript / PixiJS project exporter.
//
// This is a read-only probe over the parsed Director model: it loads a movie
// (reusing the RenderProbe loading path), renders every frame through the C++
// pipeline, and serializes the result into a TS project skeleton copied from
// ts/export-template/ + ts/runtime-template/. It adds no C++ public API and
// changes no renderer behavior; it only reads the parsed model and emits data.
//
// Stage 2 scope: bitmap sprites with COPY / TRANSPARENT / BLEND inks, baked by
// the C++ SpriteBaker and composited by SoftwareFrameRenderer. The exported TS
// runtime re-implements compositing in TS and is checked for parity against the
// C++ reference frames dumped here under assets/reference/.
//
// Usage:
//   libreshockwave_export_ts <movie> [--out <dir>] [--frames <N>] [--no-preload-casts]

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <exception>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <unordered_set>
#include <vector>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/bitmap/Bitmap.hpp"
#include "libreshockwave/chunks/CastChunk.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/chunks/FrameLabelsChunk.hpp"
#include "libreshockwave/cast/MemberType.hpp"
#include "libreshockwave/chunks/ScriptChunk.hpp"
#include "libreshockwave/chunks/ScriptNamesChunk.hpp"
#include "libreshockwave/chunks/SoundChunk.hpp"
#include "libreshockwave/chunks/TextChunk.hpp"
#include "libreshockwave/lingo/decompiler/LingoDecompiler.hpp"
#include "libreshockwave/player/audio/SoundManager.hpp"
#include "libreshockwave/player/cast/CastLib.hpp"
#include "libreshockwave/player/Player.hpp"
#include "libreshockwave/player/behavior/BehaviorInstance.hpp"
#include "libreshockwave/player/behavior/BehaviorManager.hpp"
#include "libreshockwave/player/render/pipeline/FrameSnapshot.hpp"
#include "libreshockwave/player/render/pipeline/RenderSprite.hpp"
#include "libreshockwave/player/score/ScoreBehaviorRef.hpp"
#include "libreshockwave/player/score/ScoreNavigator.hpp"
#include "libreshockwave/player/score/SpriteSpan.hpp"

namespace fs = std::filesystem;
namespace ls = libreshockwave;
namespace rsp = libreshockwave::player::render::pipeline;

namespace {

// --- file IO helpers (mirrors RenderProbe's anonymous-namespace ones) --------

std::vector<std::uint8_t> readFile(const fs::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        throw std::runtime_error("Unable to open file: " + path.string());
    }
    input.seekg(0, std::ios::end);
    const auto end = input.tellg();
    if (end == std::ifstream::pos_type(-1)) {
        throw std::runtime_error("Unable to determine file size: " + path.string());
    }
    input.seekg(0, std::ios::beg);
    std::vector<std::uint8_t> data(static_cast<std::size_t>(end));
    if (!data.empty()) {
        input.read(reinterpret_cast<char*>(data.data()), static_cast<std::streamsize>(data.size()));
        if (!input) {
            throw std::runtime_error("Unable to read complete file: " + path.string());
        }
    }
    return data;
}

bool hasDirectorContainerHeader(const std::vector<std::uint8_t>& data) {
    if (data.size() < 4) {
        return false;
    }
    const std::string_view header(reinterpret_cast<const char*>(data.data()), 4);
    return header == "RIFX" || header == "XFIR" || header == "RIFF" || header == "FFIR";
}

// Write an ARGB Uint32 pixel buffer out as raw RGBA bytes (R,G,B,A per pixel).
// The TS runtime decodes this back into a Uint32Array by reading bytes 0..3 of
// each pixel as R,G,B,A and repacking to ARGB. Raw RGBA is dependency-free and
// lossless, which keeps the differential harness exact.
void writeRgba(const fs::path& path, const std::vector<std::uint32_t>& pixels) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) {
        throw std::runtime_error("Unable to write: " + path.string());
    }
    std::string bytes;
    bytes.reserve(pixels.size() * 4);
    for (const std::uint32_t p : pixels) {
        const std::uint8_t r = static_cast<std::uint8_t>((p >> 16) & 0xff);
        const std::uint8_t g = static_cast<std::uint8_t>((p >> 8) & 0xff);
        const std::uint8_t b = static_cast<std::uint8_t>(p & 0xff);
        const std::uint8_t a = static_cast<std::uint8_t>((p >> 24) & 0xff);
        bytes.push_back(static_cast<char>(r));
        bytes.push_back(static_cast<char>(g));
        bytes.push_back(static_cast<char>(b));
        bytes.push_back(static_cast<char>(a));
    }
    out.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    if (!out) {
        throw std::runtime_error("Unable to write complete file: " + path.string());
    }
}

// FNV-1a 64-bit over the raw bytes of an ARGB pixel vector. Used to content-address baked
// bitmaps: the same cast member can bake to different pixels across frames (animated or
// script-modified images), so deduping by (memberId, size) collides distinct content.
// Content-addressing dedups genuinely-identical bitmaps (the common static case) while
// giving different content its own file — the parity oracle requires the exported baked
// bitmap to match what the C++ reference frame actually composited, per frame.
std::uint64_t fnv1a64(const std::vector<std::uint32_t>& pixels) {
    constexpr std::uint64_t offset = 14695981039346656037ULL;
    constexpr std::uint64_t prime = 1099511628211ULL;
    std::uint64_t hash = offset;
    const auto* bytes = reinterpret_cast<const std::uint8_t*>(pixels.data());
    const std::size_t count = pixels.size() * sizeof(std::uint32_t);
    for (std::size_t i = 0; i < count; ++i) {
        hash ^= bytes[i];
        hash *= prime;
    }
    return hash;
}

// Recursively copy a source tree into a destination, skipping build artifacts and deps
// (node_modules / dist / .tsbuildinfo) so the exported project is clean. Mirrors what a
// developer would commit from the template dirs.
void copyTree(const fs::path& src, const fs::path& dst) {
    if (!fs::exists(src)) {
        throw std::runtime_error("Template source not found: " + src.string());
    }
    std::error_code ec;
    for (auto it = fs::recursive_directory_iterator(src, fs::directory_options::skip_permission_denied, ec);
         it != fs::recursive_directory_iterator(); ++it) {
        const auto& entry = *it;
        const auto rel = fs::relative(entry.path(), src, ec);
        // Skip dependency and build-output subtrees.
        const std::string leaf = entry.path().filename().string();
        if (entry.is_directory() && (leaf == "node_modules" || leaf == "dist" || leaf == ".git")) {
            it.disable_recursion_pending();
            continue;
        }
        if (leaf == ".tsbuildinfo" || entry.path().extension() == ".tsbuildinfo") {
            continue;
        }
        const auto target = dst / rel;
        if (entry.is_directory()) {
            fs::create_directories(target);
        } else if (entry.is_regular_file()) {
            fs::create_directories(target.parent_path());
            fs::copy_file(entry.path(), target, fs::copy_options::overwrite_existing, ec);
            if (ec) {
                throw std::runtime_error("Unable to copy " + entry.path().string() + " -> " + target.string()
                                         + ": " + ec.message());
            }
        }
    }
}

// Assemble the runnable TS project skeleton into the export: the export-template project
// files (package.json/tsconfig/vite/index.html/src) plus the hand-written runtime copied
// verbatim under src/runtime/. Paths come from CMake compile-definitions pointing at the
// in-repo source-of-truth template dirs.
void copyProjectSkeleton(const fs::path& outDir) {
#ifndef LIBRESHOCKWAVE_TS_EXPORT_TEMPLATE_DIR
#define LIBRESHOCKWAVE_TS_EXPORT_TEMPLATE_DIR ""
#endif
#ifndef LIBRESHOCKWAVE_TS_RUNTIME_TEMPLATE_DIR
#define LIBRESHOCKWAVE_TS_RUNTIME_TEMPLATE_DIR ""
#endif
    const fs::path exportTemplate = LIBRESHOCKWAVE_TS_EXPORT_TEMPLATE_DIR;
    const fs::path runtimeTemplate = LIBRESHOCKWAVE_TS_RUNTIME_TEMPLATE_DIR;
#undef LIBRESHOCKWAVE_TS_EXPORT_TEMPLATE_DIR
#undef LIBRESHOCKWAVE_TS_RUNTIME_TEMPLATE_DIR
    if (!exportTemplate.empty() && fs::exists(exportTemplate)) {
        copyTree(exportTemplate, outDir);
    } else {
        std::cerr << "export_ts: warning: export-template dir not configured; project skeleton not copied.\n";
    }
    if (!runtimeTemplate.empty() && fs::exists(runtimeTemplate)) {
        fs::create_directories(outDir / "src" / "runtime");
        copyTree(runtimeTemplate, outDir / "src" / "runtime");
    } else {
        std::cerr << "export_ts: warning: runtime-template dir not configured; runtime not copied.\n";
    }
}

// --- pre-bake cast member assets ---------------------------------------------
// Director sprites can be reassigned to arbitrary cast members at runtime via
// Lingo (e.g. `set the member of sprite 5 to "chair_1"`). The static exporter
// only bakes the members visible on score frames, so we pre-bake every member
// that has renderable image data and register it by name/number. Film-loops are
// baked for every internal sub-frame so the TS runtime can animate them.

namespace {

rsp::SpriteType spriteTypeForMemberType(::libreshockwave::cast::MemberType type) {
    switch (type) {
        case ::libreshockwave::cast::MemberType::Bitmap:
        case ::libreshockwave::cast::MemberType::Picture:
            return rsp::SpriteType::Bitmap;
        case ::libreshockwave::cast::MemberType::FilmLoop:
            return rsp::SpriteType::FilmLoop;
        case ::libreshockwave::cast::MemberType::Text:
        case ::libreshockwave::cast::MemberType::Button:
            return rsp::SpriteType::Text;
        case ::libreshockwave::cast::MemberType::Shape:
            return rsp::SpriteType::Shape;
        case ::libreshockwave::cast::MemberType::Shockwave3D:
            return rsp::SpriteType::Shockwave3D;
        default:
            return rsp::SpriteType::Unknown;
    }
}

} // namespace

// --- minimal hand-rolled JSON emission ---------------------------------------

std::string jsonEscape(std::string_view s) {
    std::string out;
    out.reserve(s.size() + 2);
    for (const char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<std::uint8_t>(c) < 0x20) {
                    std::ostringstream hex;
                    hex << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                        << static_cast<int>(static_cast<std::uint8_t>(c));
                    out += hex.str();
                } else {
                    out += c;
                }
                break;
        }
    }
    return out;
}

// Make a cast-member name safe to use as an asset filename: keep alphanumerics, dash, dot,
// underscore; collapse everything else to a single underscore; strip leading/trailing dots
// and spaces. Empty results fall back to a numeric id at the call site.
std::string sanitizeAssetName(std::string_view s) {
    std::string out;
    out.reserve(s.size());
    for (const char c : s) {
        if (std::isalnum(static_cast<unsigned char>(c)) || c == '-' || c == '_' || c == '.') {
            out += static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
        } else if (!out.empty() && out.back() != '_') {
            out += '_';
        }
    }
    while (!out.empty() && (out.back() == '.' || out.back() == '_' || out.back() == ' ')) {
        out.pop_back();
    }
    while (!out.empty() && (out.front() == '.' || out.front() == '_')) {
        out.erase(out.begin());
    }
    return out;
}

// Map a ScriptChunkType to the string emitted in script metadata.
std::string scriptTypeToString(ls::chunks::ScriptChunkType t) {
    switch (t) {
        case ls::chunks::ScriptChunkType::Score:       return "Score";
        case ls::chunks::ScriptChunkType::Behavior:    return "Behavior";
        case ls::chunks::ScriptChunkType::MovieScript: return "MovieScript";
        case ls::chunks::ScriptChunkType::Parent:      return "Parent";
        default:                                        return "Unknown";
    }
}

// Director system-event handler names. A handler whose name matches one of these is dispatched
// by the player on the corresponding event; everything else is a plain handler invoked from Lingo.
// Used only to tag emitted handlers with their dispatch event — it changes no behavior.
bool isLingoSystemEvent(std::string_view name) {
    static const std::unordered_set<std::string_view> events = {
        "prepareFrame", "enterFrame", "exitFrame", "beginSprite", "endSprite", "stepFrame",
        "mouseDown", "mouseUp", "mouseEnter", "mouseLeave", "mouseWithin", "mouseUpOutside",
        "rightMouseDown", "rightMouseUp", "keyDown", "keyUp", "idle", "startMovie", "stopMovie",
        "stepMovie", "new", "openWindow", "closeWindow", "moveWindow", "resizeWindow",
        "activateWindow", "deactivateWindow", "resume", "suspend", "cuePassed",
        "beginKeyboardFocus", "endKeyboardFocus", "beginSprite"
    };
    return events.find(name) != events.end();
}

// Sanitize a Lingo handler/script name into a valid TS identifier fragment (used for filenames
// and quoted object keys, so it need only avoid quotes/backslashes/control chars).
std::string sanitizeTsKey(std::string_view s) {
    std::string out;
    out.reserve(s.size());
    for (const char c : s) {
        if (c == '"' || c == '\\' || static_cast<std::uint8_t>(c) < 0x20) {
            out += '_';
        } else {
            out += c;
        }
    }
    if (out.empty()) {
        out = "_";
    }
    return out;
}

// Sanitize a Lingo handler name into a valid unquoted TypeScript identifier for function names.
std::string sanitizeTsIdentifier(std::string_view s) {
    std::string out;
    out.reserve(s.size());
    for (std::size_t i = 0; i < s.size(); ++i) {
        const char c = s[i];
        if (std::isalnum(static_cast<unsigned char>(c)) || c == '_') {
            out += c;
        } else {
            out += '_';
        }
    }
    if (out.empty() || std::isdigit(static_cast<unsigned char>(out[0]))) {
        out.insert(out.begin(), '_');
    }
    // Avoid TS reserved words and runtime helper names.
    static const std::unordered_set<std::string> reserved = {
        "var", "let", "const", "function", "return", "if", "else", "while", "for", "break",
        "continue", "switch", "case", "default", "new", "this", "undefined", "true", "false",
        "null", "void", "typeof", "in", "of", "do", "try", "catch", "finally", "throw", "with",
        "yield", "await", "async", "class", "extends", "super", "export", "import", "from", "as",
        "interface", "type", "namespace", "module", "declare", "abstract", "implements", "private",
        "protected", "public", "readonly", "static", "get", "set", "constructor", "debugger",
        "enum", "never", "unknown", "any", "object", "number", "string", "boolean", "symbol",
        "bigint", "me",
    };
    if (reserved.count(out) > 0) {
        out = "_" + out;
    }
    return out;
}

// --- exporter ----------------------------------------------------------------

struct ExportOptions {
    fs::path moviePath;
    fs::path outDir = "exported-movie";
    int frames = 0;        // 0 = all frames
    bool preloadCasts = true;
    bool selfTestInks = false; // --self-test-inks: synthetic frames covering every InkMode
};

ExportOptions parseOptions(int argc, char** argv) {
    ExportOptions options;
    bool sawMovie = false;
    for (int index = 1; index < argc; ++index) {
        const std::string_view arg(argv[index]);
        if (arg == "--help" || arg == "-h") {
            std::cout << "Usage: " << argv[0]
                      << " <movie> [--out <dir>] [--frames <N>] [--no-preload-casts]\n"
                      << "       " << argv[0] << " --self-test-inks [--out <dir>]\n"
                      << "  --out <dir>          Output project directory (default: exported-movie)\n"
                      << "  --frames <N>         Export only the first N frames (default: all)\n"
                      << "  --no-preload-casts   Skip preloading external cast libraries\n"
                      << "  --self-test-inks     Emit synthetic frames covering every InkMode (no movie)\n";
            std::exit(0);
        }
        if (arg == "--self-test-inks") {
            options.selfTestInks = true;
            continue;
        }
        if (arg == "--out") {
            if (index + 1 >= argc) {
                throw std::runtime_error("--out requires a value");
            }
            options.outDir = argv[++index];
            continue;
        }
        if (arg == "--frames") {
            if (index + 1 >= argc) {
                throw std::runtime_error("--frames requires a value");
            }
            options.frames = std::atoi(argv[++index]);
            if (options.frames < 0) {
                throw std::runtime_error("--frames must be non-negative");
            }
            continue;
        }
        if (arg == "--no-preload-casts") {
            options.preloadCasts = false;
            continue;
        }
        if (arg.starts_with('-')) {
            throw std::runtime_error("Unknown option: " + std::string(arg));
        }
        if (sawMovie) {
            throw std::runtime_error("Unexpected extra positional argument: " + std::string(arg));
        }
        options.moviePath = std::string(arg);
        sawMovie = true;
    }
    if (!sawMovie && !options.selfTestInks) {
        throw std::runtime_error("Missing required <movie> argument (or pass --self-test-inks)");
    }
    return options;
}

// Per-sprite record written to score.json. Geometry + ink + blend + the asset
// reference to the baked bitmap (deduplicated by cast member).
struct SpriteRecord {
    int channel;
    int x, y, width, height;
    int locZ;
    bool visible;
    std::string type;
    int ink;
    int blend;
    bool flipH;       // effective mirror: isFlipH() ^ hasDirectorHorizontalMirror()
    bool flipV;
    double rotation;
    double skew;
    bool hasBakedBitmap;
    std::string bakedBitmapAsset; // relative to out dir, "" if none
    int bakedWidth;
    int bakedHeight;
    int castMemberId = -1;        // Director cast member number, or -1 if none
    std::string castMemberName;   // member name if the cast member has one
    bool hasBehaviors = false;    // true if the score channel carries a behavior script
};

struct BehaviorChannel {
    int channel;
    int castLib;
    int castMember;
    std::string scriptName; // best-effort name from the behavior member, may be empty
};

struct FrameRecord {
    int frame;
    int tempo = 0; // effective fps for this frame (score tempo channel, else base tempo)
    std::optional<BehaviorChannel> frameScript; // frame script attached to this score frame
    std::vector<SpriteRecord> sprites;
};

// A frame label / marker (in Director, the label set and the marker set are the same —
// ScoreNavigator populates both from the VWLB frame-labels chunk). Emitted so the TS
// ScorePlayer can navigate by name and report markers.
struct LabelRecord {
    int frame;
    std::string name;
};

// A decoded sound cast member exported as a playable audio asset. Director sound playback is
// Lingo-driven (there is no score sound channel), so the exporter ships the sound ASSETS +
// metadata; per-frame cues are not statically capturable and arrive with Lingo (Stage 7).
struct SoundRecord {
    std::string name;       // sanitized asset stem
    std::string assetRef;   // assets/sounds/<name>.<ext>
    std::string format;     // "wav" | "mp3"
    int sampleRate = 0;
    int channels = 0;
    int bitsPerSample = 0;
    double durationSeconds = 0.0;
    std::string codec;      // raw_pcm | mp3 | ima_adpcm
};

struct CastMemberRecord {
    int id;
    int castLib;
    std::string name;
    std::string type;
    std::string bakedBitmapAsset; // for bitmap/film-loop/etc, if already exported
    int bakedWidth = 0;
    int bakedHeight = 0;
    std::string text; // static text content for text/field cast members
    std::vector<std::string> filmLoopFrames; // one asset per internal sub-frame
};

namespace {

struct BakedAsset {
    std::string assetRel;
    int width = 0;
    int height = 0;
};

BakedAsset bakeAndWriteAsset(const fs::path& outDir,
                             std::unordered_set<std::string>& writtenBitmaps,
                             const std::shared_ptr<const ls::bitmap::Bitmap>& baked,
                             const std::string& stem) {
    BakedAsset result;
    if (!baked || baked->width() <= 0 || baked->height() <= 0) {
        return result;
    }
    const std::uint64_t hash = fnv1a64(baked->pixels());
    std::ostringstream keyStream;
    keyStream << "b" << std::hex << hash << std::dec
              << "_w" << baked->width() << "_h" << baked->height();
    const std::string key = keyStream.str();
    result.assetRel = "assets/bitmaps/" + key + ".rgba";
    result.width = baked->width();
    result.height = baked->height();
    if (writtenBitmaps.insert(key).second) {
        writeRgba(outDir / result.assetRel, baked->pixels());
    }
    return result;
}

} // namespace

// Pre-bake all renderable cast members so Lingo member swaps have assets and
// filmloops can be animated in the browser. Mutates `memberNameToBitmapAsset`
// and writes new assets into `writtenBitmaps`.
void preBakeCastMemberAssets(
    ls::player::Player& player,
    ls::DirectorFile& directorFile,
    const fs::path& outDir,
    std::unordered_set<std::string>& writtenBitmaps,
    std::unordered_map<std::string, std::string>& memberNameToBitmapAsset,
    std::vector<CastMemberRecord>& castMembers) {

    auto& baker = player.spriteBaker();
    const int initialTick = baker.tickCounter();

    for (const auto& [castLibNum, castLib] : player.castLibManager().castLibs()) {
        if (!castLib) {
            continue;
        }
        for (const auto& [memberNumber, memberChunk] : castLib->memberChunks()) {
            if (!memberChunk) {
                continue;
            }
            const auto memberType = memberChunk->memberType();
            const auto spriteType = spriteTypeForMemberType(memberType);
            if (spriteType == rsp::SpriteType::Unknown) {
                continue;
            }

            CastMemberRecord cm;
            cm.id = memberNumber;
            cm.castLib = castLibNum;
            cm.name = memberChunk->name();
            cm.type = std::string(::libreshockwave::cast::name(memberType));

            if (memberType == ::libreshockwave::cast::MemberType::FilmLoop) {
                // Film-loops: bake each internal sub-frame so the runtime can
                // cycle through them based on the current bake tick.
                int frameCount = 1;
                if (const auto score = directorFile.getScoreForMember(
                        std::const_pointer_cast<ls::chunks::CastMemberChunk>(memberChunk))) {
                    frameCount = score->frameData().header.frameCount;
                }
                if (frameCount <= 0) {
                    frameCount = 1;
                }

                std::vector<BakedAsset> loopAssets;
                loopAssets.reserve(static_cast<std::size_t>(frameCount));
                for (int tick = 0; tick < frameCount; ++tick) {
                    baker.setTickCounter(tick);
                    rsp::RenderSprite sprite(0, 0, 0, 0, 0, false, rsp::SpriteType::FilmLoop,
                                             memberChunk, 0, 0, 0, 100);
                    auto baked = baker.bake(sprite).bakedBitmap();
                    auto asset = bakeAndWriteAsset(outDir, writtenBitmaps, baked,
                                                   "fl_" + std::to_string(castLibNum) + "_" + std::to_string(memberNumber));
                    loopAssets.push_back(asset);
                }

                cm.filmLoopFrames.reserve(loopAssets.size());
                for (const auto& asset : loopAssets) {
                    cm.filmLoopFrames.push_back(asset.assetRel);
                }
                if (!loopAssets.empty() && !loopAssets[0].assetRel.empty()) {
                    cm.bakedBitmapAsset = loopAssets[0].assetRel;
                    cm.bakedWidth = loopAssets[0].width;
                    cm.bakedHeight = loopAssets[0].height;
                }
                const std::string memberName = cm.name;
                const std::string firstAsset = cm.bakedBitmapAsset;
                castMembers.push_back(std::move(cm));
                if (!memberName.empty() && !firstAsset.empty()) {
                    memberNameToBitmapAsset.emplace(memberName, firstAsset);
                }
                continue;
            }

            // Bitmap / text / shape: a single baked asset is enough for the
            // static member lookup (Lingo-driven ink/scale is applied at render).
            rsp::RenderSprite sprite(0, 0, 0, 0, 0, false, spriteType,
                                     memberChunk, 0, 0, 0, 100);
            auto baked = baker.bake(sprite).bakedBitmap();
            auto asset = bakeAndWriteAsset(outDir, writtenBitmaps, baked,
                                           "cm_" + std::to_string(castLibNum) + "_" + std::to_string(memberNumber));
            cm.bakedBitmapAsset = asset.assetRel;
            cm.bakedWidth = asset.width;
            cm.bakedHeight = asset.height;
            const std::string memberName = cm.name;
            const std::string bakedAsset = cm.bakedBitmapAsset;
            castMembers.push_back(std::move(cm));
            if (!memberName.empty() && !bakedAsset.empty()) {
                memberNameToBitmapAsset.emplace(memberName, bakedAsset);
            }
        }
    }

    baker.setTickCounter(initialTick);
}

// One Lingo handler within an emitted script module. `event` is the Director system-event
// name when the handler is one of the recognized event handlers (enterFrame, mouseDown, ...),
// else null (a handler called only from other Lingo). Args are the resolved argument names.
struct ScriptHandlerRecord {
    std::string name;
    std::vector<std::string> args;
    std::string event;      // empty => not a system event handler
};

// A decompiled Lingo script emitted as a TS module under src/scripts/. The Lingo source is
// preserved verbatim (LingoDecompiler output) alongside a structured handler table; a TS Lingo
// execution model is the remaining Stage 7 tail, so the emitted stubs throw rather than execute.
struct ScriptRecord {
    std::string name;       // Director script name (cast member name)
    std::string type;       // Score | Behavior | MovieScript | Parent
    std::string file;       // src/scripts/<stem>.ts
    int castLib = 0;        // cast library number the script member lives in
    int castMember = 0;     // cast member number within that library
    std::vector<ScriptHandlerRecord> handlers;
};

// Every Director InkMode value (Ids.hpp). The synthetic self-test renders a known baked
// bitmap through each one so the differential harness covers the full ink set deterministically,
// not just whichever inks happen to appear in a real fixture.
constexpr int kAllInkCodes[] = {
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41,
};

// Synthetic baked bitmap (4x4) exercising opaque / translucent / transparent source pixels so
// every compositing branch (opaque overwrite, alphaComposite, special-ink blend-back when
// srcA<255, transparent skip) is reached per ink.
std::shared_ptr<const ls::bitmap::Bitmap> selfTestBakedBitmap() {
    std::vector<std::uint32_t> pixels = {
        0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff,
        0x80ff0000, 0x8000ff00, 0x800000ff, 0x80808080,
        0xff000000, 0xff808080, 0xffffff00, 0xff00ffff,
        0x00000000, 0x00ffffff, 0xffff00ff, 0xff404040,
    };
    return std::make_shared<const ls::bitmap::Bitmap>(4, 4, 32, std::move(pixels));
}

struct SelfTestVariant {
    const char* label;
    int x, y, width, height; // sprite rect (baked is 4x4; width/height != 4 => scaled)
    int blend;
};

// Run the synthetic ink self-test: for every InkMode, render the baked pattern at 1:1
// (blend 100 and 50) and scaled (blend 100) over a gray stage, dumping each C++ reference
// frame + a score.json the differential harness composites in TS and diffs bit-exact.
int runSelfTestInks(const ExportOptions& options) {
    constexpr int stageWidth = 8;
    constexpr int stageHeight = 8;
    constexpr int backgroundColor = 0x808080;

    fs::create_directories(options.outDir / "src");
    fs::create_directories(options.outDir / "assets" / "bitmaps");
    fs::create_directories(options.outDir / "assets" / "reference");
    fs::create_directories(options.outDir / "test");
    copyProjectSkeleton(options.outDir);

    const auto baked = selfTestBakedBitmap();
    const std::string bakedAsset = "assets/bitmaps/b"
        + ([&] {
            std::ostringstream s;
            s << std::hex << fnv1a64(baked->pixels());
            return s.str();
        }()) + "_w4_h4.rgba";
    writeRgba(options.outDir / bakedAsset, baked->pixels());

    const SelfTestVariant variants[] = {
        {"a", 2, 2, 4, 4, 100}, // 1:1, full blend
        {"b", 2, 2, 4, 4, 50},  // 1:1, blend 50 (exercises blend<100 paths)
        {"c", 1, 1, 6, 6, 100}, // scaled 4x4 -> 6x6 (exercises blitBitmapScaled per ink)
    };

    std::ostringstream score;
    score << "{\n";
    score << "  \"stageWidth\": " << stageWidth << ",\n";
    score << "  \"stageHeight\": " << stageHeight << ",\n";
    score << "  \"backgroundColor\": " << backgroundColor << ",\n";
    const int selfTestFrameCount = static_cast<int>(sizeof(kAllInkCodes) / sizeof(kAllInkCodes[0])) * 3;
    score << "  \"totalFrames\": " << selfTestFrameCount << ",\n";
    score << "  \"tempo\": " << 15 << ",\n";
    score << "  \"labels\": [],\n";

    int frameNo = 0;
    std::vector<std::string> frameBlocks;
    for (const int ink : kAllInkCodes) {
        for (const auto& v : variants) {
            ++frameNo;
            rsp::RenderSprite sprite(1, v.x, v.y, v.width, v.height, 0, true,
                                     rsp::SpriteType::Bitmap, nullptr, nullptr,
                                     0, 0, false, false, ink, v.blend,
                                     false, false, 0.0, 0.0, baked, false);
            rsp::FrameSnapshot snapshot;
            snapshot.frameNumber = frameNo;
            snapshot.stageWidth = stageWidth;
            snapshot.stageHeight = stageHeight;
            snapshot.backgroundColor = backgroundColor;
            snapshot.sprites.push_back(std::move(sprite));
            snapshot.stageImage = nullptr;
            snapshot.bakeTick = 0;

            const auto ref = snapshot.renderFrame();
            if (ref.width() != stageWidth || ref.height() != stageHeight) {
                throw std::runtime_error("Self-test reference dimensions mismatch at frame "
                                         + std::to_string(frameNo));
            }
            std::ostringstream refPath;
            refPath << "assets/reference/f" << frameNo << ".rgba";
            writeRgba(options.outDir / refPath.str(), ref.pixels());

            std::ostringstream fb;
            fb << "    { \"frame\": " << frameNo << ", \"tempo\": 15, \"sprites\": [";
            fb << "{ \"channel\": 1, \"x\": " << v.x << ", \"y\": " << v.y
               << ", \"width\": " << v.width << ", \"height\": " << v.height
               << ", \"locZ\": 0, \"visible\": true, \"type\": \"bitmap\""
               << ", \"ink\": " << ink << ", \"blend\": " << v.blend
               << ", \"flipH\": false, \"flipV\": false, \"rotation\": 0, \"skew\": 0"
               << ", \"bakedBitmapAsset\": \"" << jsonEscape(bakedAsset) << "\""
               << ", \"bakedWidth\": 4, \"bakedHeight\": 4"
               << ", \"castMemberId\": -1, \"castMemberName\": \"\", \"hasBehaviors\": false }";
            fb << "] }";
            frameBlocks.push_back(fb.str());
        }
    }
    score << "  \"frameCount\": " << frameNo << ",\n";
    score << "  \"frames\": [\n";
    for (std::size_t i = 0; i < frameBlocks.size(); ++i) {
        score << frameBlocks[i];
        if (i + 1 < frameBlocks.size()) {
            score << ",";
        }
        score << "\n";
    }
    score << "  ]\n}\n";
    {
        std::ofstream sf(options.outDir / "score.json", std::ios::trunc);
        sf << score.str();
    }
    {
        std::ofstream m(options.outDir / "manifest.json", std::ios::trunc);
        m << "{\n";
        m << "  \"runtimeVersion\": \"0.1.0-stage8\",\n";
        m << "  \"fixture\": \"self-test-inks\",\n";
        m << "  \"stage\": { \"width\": " << stageWidth << ", \"height\": " << stageHeight
          << ", \"backgroundColor\": " << backgroundColor << " },\n";
        m << "  \"frameCount\": " << frameNo << ",\n";
        m << "  \"totalFrames\": " << frameNo << ",\n";
        m << "  \"assets\": { \"bitmaps\": \"assets/bitmaps\", \"reference\": \"assets/reference\" }\n";
        m << "}\n";
    }
    {
        std::ofstream c(options.outDir / "cast.json", std::ios::trunc);
        c << "{ \"note\": \"Synthetic ink self-test; no cast members.\", \"members\": [] }\n";
    }

    std::cout << "Self-test: exported " << frameNo << " synthetic frame(s) covering "
              << (sizeof(kAllInkCodes) / sizeof(kAllInkCodes[0])) << " inks x 3 variants to "
              << options.outDir.string() << "\n";
    return 0;
}

} // namespace

int main(int argc, char** argv) {
    try {
        const auto options = parseOptions(argc, argv);

        if (options.selfTestInks) {
            return runSelfTestInks(options);
        }

        const auto data = readFile(options.moviePath);
        if (!hasDirectorContainerHeader(data)) {
            throw std::runtime_error("Not a Director container: " + options.moviePath.string());
        }

        auto directorFile = ls::DirectorFile::load(data);
        directorFile->setBasePath(options.moviePath.parent_path().string());

        ls::player::Player player(directorFile);
        std::vector<std::string> scriptErrors;
        player.setErrorListener([&scriptErrors](std::string_view message, std::string_view errorDetail) {
            std::string error(message);
            if (!errorDetail.empty()) {
                error += ": ";
                error += errorDetail;
            }
            scriptErrors.push_back(std::move(error));
        });

        if (options.preloadCasts) {
            (void)player.preloadAllCasts();
            player.castLibManager().preloadCasts(2);
            player.castLibManager().preloadCasts(1);
        }

        // For Lingo export (Stage 7) the behavior manager needs the movie foundation prepared
        // so external-cast behavior references resolve. play() loads casts and registers scripts;
        // it also dispatches prepareMovie/startMovie/enterFrame, which we allow because the static
        // reference frames are produced by frameRenderPipeline().renderFrame(frame) below.
        player.play();

        std::cerr << "export_ts: cast libraries:\n";
        for (const auto& [num, castLib] : player.castLibManager().castLibs()) {
            if (castLib) {
                std::cerr << "  castLib " << num << " = " << castLib->name()
                          << " (external=" << player.castLibManager().isCastLibExternal(num)
                          << ", file=" << player.castLibManager().getCastLibFileName(num)
                          << ", loaded=" << castLib->isLoaded() << ")\n";
            } else {
                std::cerr << "  castLib " << num << " = null\n";
            }
        }

        const int frameCount = player.frameCount();
        if (frameCount <= 0) {
            throw std::runtime_error("Movie reports no frames to export");
        }
        const int framesToExport = (options.frames > 0) ? std::min(options.frames, frameCount) : frameCount;

        // Output tree.
        fs::create_directories(options.outDir / "src");
        fs::create_directories(options.outDir / "assets" / "bitmaps");
        fs::create_directories(options.outDir / "assets" / "reference");
        fs::create_directories(options.outDir / "assets" / "sounds");
        fs::create_directories(options.outDir / "test");

        // Copy the runnable TS project skeleton (export-template) + hand-written runtime
        // into the export before emitting data, so data files land beside the project files.
        copyProjectSkeleton(options.outDir);

        // Behavior channel map: for each score frame, which channels carry behaviors and the
        // cast-lib/member id of the behavior. Used by the TS runtime to create per-channel `me`
        // objects and dispatch beginSprite / endSprite.
        std::vector<std::vector<BehaviorChannel>> frameBehaviorChannels;
        frameBehaviorChannels.reserve(static_cast<std::size_t>(framesToExport));

        // Reverse map from an emitted ScriptChunk to the (castLib, castMember) score behavior
        // channels use to reference it. Built via the player's BehaviorManager so external casts
        // are resolved the same way the runtime will resolve them.
        std::map<std::shared_ptr<ls::chunks::ScriptChunk>, std::pair<int, int>, std::owner_less<std::shared_ptr<ls::chunks::ScriptChunk>>> scriptToCastMember;

        // Ownership map for every script member across all cast libraries (internal + external).
        // This lets the exporter attach the correct castLib/castMember and name to scripts that
        // live in external casts, where BehaviorManager resolution currently fails.
        std::map<std::shared_ptr<ls::chunks::ScriptChunk>,
                 std::tuple<int, int, std::string, std::shared_ptr<ls::player::cast::CastLib>>,
                 std::owner_less<std::shared_ptr<ls::chunks::ScriptChunk>>>
        scriptOwnership;
        for (const auto& [castLibNum, castLib] : player.castLibManager().castLibs()) {
            if (!castLib) {
                continue;
            }
            for (const auto& [memberNumber, memberChunk] : castLib->memberChunks()) {
                if (!memberChunk) {
                    continue;
                }
                auto script = castLib->getScript(memberNumber);
                if (!script) {
                    continue;
                }
                scriptOwnership[script] = std::make_tuple(castLibNum, memberNumber,
                                                          std::string(memberChunk->name()), castLib);
            }
        }

        // Baked-bitmap asset dedup: same cast member + size yields identical RGBA,
        // so write it once as assets/bitmaps/m<id>_w<W>_h<H>.rgba and reference it.
        std::unordered_set<std::string> writtenBitmaps;
        // Member-name -> first baked bitmap seen (used by Lingo member("name") swaps at runtime).
        std::unordered_map<std::string, std::string> memberNameToBitmapAsset;

        // Pre-bake every renderable cast member. This gives the runtime assets for
        // Lingo-driven member swaps and sub-frame filmloop animation.
        std::vector<CastMemberRecord> castMembers;
        preBakeCastMemberAssets(player, *directorFile, options.outDir, writtenBitmaps,
                                memberNameToBitmapAsset, castMembers);

        std::vector<FrameRecord> frameRecords;
        frameRecords.reserve(static_cast<std::size_t>(framesToExport));

        int stageWidth = 0;
        int stageHeight = 0;
        int backgroundColor = 0;

        // Base tempo (Config chunk) for the movie. Per-frame effective tempo mirrors C++
        // Player::tempo() without Lingo: the score tempo channel (sticky from the most recent
        // entry at or before the frame) if > 0, else the base tempo. puppetTempo is 0 because
        // the static export runs no Lingo.
        const int baseTempo = player.baseTempo();

        // Frame labels / markers within the exported slice, sorted by frame (the VWLB chunk
        // is parsed in frame order, but sort defensively).
        std::vector<LabelRecord> labels;
        if (const auto flc = directorFile->frameLabelsChunk()) {
            for (const auto& lbl : flc->labels()) {
                const int frameNum = lbl.frameNum.value();
                if (frameNum >= 1 && frameNum <= framesToExport) {
                    LabelRecord lr;
                    lr.frame = frameNum;
                    lr.name = lbl.label;
                    labels.push_back(lr);
                }
            }
            std::sort(labels.begin(), labels.end(),
                      [](const LabelRecord& a, const LabelRecord& b) { return a.frame < b.frame; });
        }

        const auto& navigator = player.navigator();
        for (int frame = 1; frame <= framesToExport; ++frame) {
            const auto snapshot = player.frameRenderPipeline().renderFrame(frame);
            stageWidth = snapshot.stageWidth;
            stageHeight = snapshot.stageHeight;
            backgroundColor = snapshot.backgroundColor;

            FrameRecord frameRec;
            frameRec.frame = frame;

            std::vector<BehaviorChannel> behaviorChannels;
            for (const auto& span : navigator.getActiveSprites(frame)) {
                const int channel = span.channel();
                for (const auto& behaviorRef : span.behaviors()) {
                    BehaviorChannel bc;
                    bc.channel = channel;
                    bc.castLib = behaviorRef.castLib();
                    bc.castMember = behaviorRef.castMember();

                    // Try BehaviorManager first (exact runtime resolution), then fall back to the
                    // cross-cast ownership map so external-cast behaviors get a script name.
                    const auto instance = player.behaviorManager().createInstance(behaviorRef, channel);
                    std::shared_ptr<ls::chunks::ScriptChunk> script;
                    if (instance) {
                        script = instance->script();
                    }
                    if (!script) {
                        if (const auto castLib = player.castLibManager().getCastLib(bc.castLib)) {
                            script = castLib->getScript(bc.castMember);
                        }
                    }
                    if (script) {
                        bc.scriptName = script->scriptName();
                        if (bc.scriptName.empty()) {
                            bc.scriptName = directorFile->getScriptName(script);
                        }
                        if (bc.scriptName.empty()) {
                            if (const auto ownIt = scriptOwnership.find(script); ownIt != scriptOwnership.end()) {
                                bc.scriptName = std::get<2>(ownIt->second);
                            }
                        }
                        scriptToCastMember[script] = { bc.castLib, bc.castMember };
                    }
                    behaviorChannels.push_back(std::move(bc));
                }
            }
            frameBehaviorChannels.push_back(std::move(behaviorChannels));

            // Frame script for this frame (if any). The frame script is a behavior-like
            // instance created by the Director player per frame; it receives beginSprite,
            // prepareFrame, enterFrame, and exitFrame events just like score behaviors.
            if (const auto* frameScriptRef = navigator.getFrameScript(frame); frameScriptRef != nullptr) {
                BehaviorChannel fsc;
                fsc.channel = 0;
                fsc.castLib = frameScriptRef->castLib();
                fsc.castMember = frameScriptRef->castMember();

                const auto script = player.castLibManager().getCastLib(fsc.castLib)
                                        ? player.castLibManager().getCastLib(fsc.castLib)->getScript(fsc.castMember)
                                        : nullptr;
                if (script) {
                    fsc.scriptName = script->scriptName();
                    if (fsc.scriptName.empty()) {
                        fsc.scriptName = directorFile->getScriptName(script);
                    }
                    if (fsc.scriptName.empty()) {
                        if (const auto ownIt = scriptOwnership.find(script); ownIt != scriptOwnership.end()) {
                            fsc.scriptName = std::get<2>(ownIt->second);
                        }
                    }
                    scriptToCastMember[script] = { fsc.castLib, fsc.castMember };
                }
                frameRec.frameScript = std::move(fsc);
            }

            int effectiveTempo = directorFile->getScoreTempo(frame - 1);
            if (effectiveTempo <= 0) {
                effectiveTempo = baseTempo;
            }
            frameRec.tempo = effectiveTempo;

            for (const auto& sprite : snapshot.sprites) {
                SpriteRecord rec;
                rec.channel = sprite.channel();
                rec.x = sprite.x();
                rec.y = sprite.y();
                rec.width = sprite.width();
                rec.height = sprite.height();
                rec.locZ = sprite.locZ();
                rec.visible = sprite.isVisible();
                rec.type = std::string(rsp::name(sprite.type()));
                rec.ink = sprite.ink();
                rec.blend = sprite.blend();
                rec.flipH = sprite.isFlipH() ^ sprite.hasDirectorHorizontalMirror();
                rec.flipV = sprite.isFlipV();
                rec.rotation = sprite.rotation();
                rec.skew = sprite.skew();
                rec.castMemberId = sprite.castMemberId();
                if (auto nameOpt = sprite.memberName()) {
                    rec.castMemberName = *nameOpt;
                }
                rec.hasBehaviors = sprite.hasBehaviors();
                rec.hasBakedBitmap = false;
                rec.bakedWidth = 0;
                rec.bakedHeight = 0;

                const auto baked = sprite.bakedBitmap();
                if (baked && baked->width() > 0 && baked->height() > 0) {
                    rec.hasBakedBitmap = true;
                    rec.bakedWidth = baked->width();
                    rec.bakedHeight = baked->height();
                    // Content-address the baked bitmap: a cast member can bake to different
                    // pixels across frames, so the key is the pixel hash + size, not the
                    // member id. Identical content dedups to one file; different content
                    // gets its own, matching what each C++ reference frame composited.
                    const std::uint64_t hash = fnv1a64(baked->pixels());
                    std::ostringstream keyStream;
                    keyStream << "b" << std::hex << hash << std::dec
                              << "_w" << baked->width() << "_h" << baked->height();
                    const std::string key = keyStream.str();
                    const std::string assetRel = "assets/bitmaps/" + key + ".rgba";
                    if (writtenBitmaps.insert(key).second) {
                        writeRgba(options.outDir / assetRel, baked->pixels());
                    }
                    rec.bakedBitmapAsset = assetRel;
                    if (auto nameOpt = sprite.memberName(); nameOpt && !nameOpt->empty()) {
                        memberNameToBitmapAsset.emplace(*nameOpt, assetRel);
                    }
                }
                frameRec.sprites.push_back(std::move(rec));
            }

            // Reference frame: the C++ composited output, the parity oracle.
            const auto refBitmap = snapshot.renderFrame();
            if (refBitmap.width() != stageWidth || refBitmap.height() != stageHeight) {
                throw std::runtime_error("Reference frame dimensions mismatch at frame "
                                         + std::to_string(frame));
            }
            std::ostringstream refStream;
            refStream << "assets/reference/f" << frame << ".rgba";
            writeRgba(options.outDir / refStream.str(), refBitmap.pixels());

            frameRecords.push_back(std::move(frameRec));
        }

        // --- cast member registry enrichment --------------------------------
        // Pre-bake already created a record for every renderable member. Now add
        // text content for text members and ensure named members without baked
        // assets still appear (they may be swapped in by name at runtime).
        {
            // Map (castLib << 32 | id) -> vector index for deduplication.
            std::map<std::uint64_t, std::size_t> indexByMember;
            for (std::size_t i = 0; i < castMembers.size(); ++i) {
                const auto key = (static_cast<std::uint64_t>(castMembers[i].castLib) << 32)
                               | static_cast<std::uint64_t>(static_cast<std::uint32_t>(castMembers[i].id));
                indexByMember[key] = i;
            }

            for (const auto& [castLibNum, castLib] : player.castLibManager().castLibs()) {
                if (!castLib) {
                    continue;
                }
                for (const auto& [memberNumber, memberChunk] : castLib->memberChunks()) {
                    if (!memberChunk) {
                        continue;
                    }
                    const auto key = (static_cast<std::uint64_t>(castLibNum) << 32)
                                   | static_cast<std::uint64_t>(static_cast<std::uint32_t>(memberNumber));
                    const std::string& name = memberChunk->name();
                    auto indexIt = indexByMember.find(key);
                    if (indexIt != indexByMember.end()) {
                        if (memberChunk->isText()) {
                            auto& cm = castMembers[indexIt->second];
                            bool found = false;
                            if (auto styled = directorFile->getXmedStyledTextForMember(memberChunk)) {
                                cm.text = styled->text;
                                found = true;
                            }
                            if (!found) {
                                if (auto textChunk = directorFile->getTextForMember(memberChunk)) {
                                    cm.text = textChunk->text();
                                }
                            }
                        }
                        continue;
                    }
                    if (name.empty()) {
                        continue;
                    }
                    CastMemberRecord cm;
                    cm.id = memberNumber;
                    cm.castLib = castLibNum;
                    cm.name = name;
                    cm.type = std::string(libreshockwave::cast::name(memberChunk->memberType()));
                    auto it = memberNameToBitmapAsset.find(name);
                    if (it != memberNameToBitmapAsset.end()) {
                        cm.bakedBitmapAsset = it->second;
                        const auto wPos = it->second.find("_w");
                        const auto hPos = it->second.find("_h");
                        if (wPos != std::string::npos && hPos != std::string::npos) {
                            cm.bakedWidth = std::atoi(it->second.c_str() + wPos + 2);
                            cm.bakedHeight = std::atoi(it->second.c_str() + hPos + 2);
                        }
                    }
                    if (memberChunk->isText()) {
                        bool found = false;
                        if (auto styled = directorFile->getXmedStyledTextForMember(memberChunk)) {
                            cm.text = styled->text;
                            found = true;
                        }
                        if (!found) {
                            if (auto textChunk = directorFile->getTextForMember(memberChunk)) {
                                cm.text = textChunk->text();
                            }
                        }
                    }
                    castMembers.push_back(std::move(cm));
                    indexByMember[key] = castMembers.size() - 1;
                }
            }
        }

        // --- sound cast members ----------------------------------------------
        // Director sound playback is Lingo-driven (no score sound channel, no parsed cue
        // points), so the static export ships the decoded sound ASSETS + metadata; per-frame
        // cues are not statically capturable and arrive with Lingo (Stage 7). Each sound
        // member is decoded to a playable blob (WAV for PCM, raw MP3 for SWA) via the same
        // SoundManager path the C++ player uses, so the bytes match what Director would play.
        std::vector<SoundRecord> sounds;
        {
            std::unordered_set<std::string> writtenSoundNames;
            const auto& allCastMembers = directorFile->castMembers();
            for (std::size_t i = 0; i < allCastMembers.size(); ++i) {
                const auto& member = allCastMembers[i];
                if (!member || !member->isSound()) {
                    continue;
                }
                auto soundChunk = ls::player::audio::SoundManager::findSoundForMember(*directorFile, member);
                if (!soundChunk) {
                    continue;
                }
                auto playable = ls::player::audio::SoundManager::convertSoundToPlayable(*soundChunk);
                if (!playable || playable->empty()) {
                    continue;
                }
                const bool isMp3 = soundChunk->isMp3();
                const std::string ext = isMp3 ? "mp3" : "wav";
                std::string stem = sanitizeAssetName(member->name());
                if (stem.empty()) {
                    stem = "sound_" + std::to_string(i);
                }
                // De-dup identical stems with a numeric suffix.
                std::string base = stem;
                int suffix = 1;
                while (!writtenSoundNames.insert(stem + "." + ext).second) {
                    stem = base + "_" + std::to_string(++suffix);
                }

                SoundRecord rec;
                rec.name = stem;
                rec.assetRef = "assets/sounds/" + stem + "." + ext;
                rec.format = ext;
                rec.sampleRate = soundChunk->sampleRate();
                rec.channels = soundChunk->channelCount();
                rec.bitsPerSample = soundChunk->bitsPerSample();
                rec.durationSeconds = soundChunk->durationSeconds();
                rec.codec = soundChunk->codec();

                std::ofstream sf(options.outDir / rec.assetRef,
                                 std::ios::binary | std::ios::trunc);
                if (!sf) {
                    throw std::runtime_error("Unable to write sound asset: " + rec.assetRef);
                }
                sf.write(reinterpret_cast<const char*>(playable->data()),
                         static_cast<std::streamsize>(playable->size()));
                if (!sf) {
                    throw std::runtime_error("Unable to write complete sound asset: " + rec.assetRef);
                }
                sounds.push_back(std::move(rec));
            }
        }

        // --- Lingo scripts -> src/scripts/*.ts --------------------------------
        // Stage 7 emission. Each script cast member is decompiled via the same LingoDecompiler
        // the C++ player uses to disassemble scripts, and emitted as a TS module that preserves
        // the Lingo source verbatim plus a structured handler table (names, argument lists, and
        // the Director system-event each handler dispatches, if any). The handler stubs throw
        // LingoNotImplemented rather than execute: a TS Lingo execution model is the remaining
        // Stage 7 tail (see GOAL.md), and per docs/rendering-rules.md the runtime must not fake
        // C++ behavior, so handlers are wired structurally but not run. The exporter's static
        // renderFrame() runs no Lingo, so this emission adds no parity gate of its own — the
        // execution-match gate (run a handler in TS, compare the FrameSnapshot to C++ executing
        // the same bytecode) requires a Lingo-ticking C++ oracle + the TS execution model, which
        // is the genuinely hard tail and is not faked here.
        std::vector<ScriptRecord> scripts;
        fs::create_directories(options.outDir / "src" / "scripts");
        {
            std::unordered_set<std::string> writtenFiles;
            // Collect every script chunk from the ownership map (internal + every external cast)
            // plus any the main DirectorFile lists that did not appear in a cast library.
            std::vector<std::shared_ptr<ls::chunks::ScriptChunk>> allScripts;
            allScripts.reserve(scriptOwnership.size() + directorFile->scripts().size());
            std::unordered_set<ls::chunks::ScriptChunk*> seenScripts;
            for (const auto& [script, _] : scriptOwnership) {
                if (!script || !seenScripts.insert(script.get()).second) {
                    continue;
                }
                allScripts.push_back(script);
            }
            for (const auto& script : directorFile->scripts()) {
                if (!script || !seenScripts.insert(script.get()).second) {
                    continue;
                }
                allScripts.push_back(script);
            }

            for (std::size_t si = 0; si < allScripts.size(); ++si) {
                const auto& script = allScripts[si];
                std::string rawName;
                std::shared_ptr<ls::player::cast::CastLib> scriptCastLib;
                if (const auto ownIt = scriptOwnership.find(script); ownIt != scriptOwnership.end()) {
                    rawName = std::get<2>(ownIt->second);
                    scriptCastLib = std::get<3>(ownIt->second);
                }
                if (rawName.empty()) {
                    rawName = script->scriptName();
                }
                if (rawName.empty()) {
                    rawName = directorFile->getScriptName(script);
                }
                std::string stem = sanitizeAssetName(rawName);
                if (stem.empty()) {
                    stem = "script_" + std::to_string(si);
                }
                std::string base = stem;
                int suffix = 1;
                while (!writtenFiles.insert(stem + ".ts").second) {
                    stem = base + "_" + std::to_string(++suffix);
                }

                // Resolve the script names chunk from the script's owning DirectorFile context first.
                // The per-script context (via getScriptNamesForScript) carries the correct LNAM
                // section; the cast library's global names chunk can be a different, shorter table
                // that leaves built-in event symbols unresolved.
                std::shared_ptr<ls::chunks::ScriptNamesChunk> names = directorFile->getScriptNamesForScript(script);
                if (!names && scriptCastLib) {
                    names = scriptCastLib->scriptNames();
                }
                if (!names) {
                    names = directorFile->scriptNames();
                }
                const ls::chunks::ScriptNamesChunk* namesPtr = names.get();

                // Decompile the whole script (all handlers) to a single readable Lingo listing.
                std::string lingoSource;
                try {
                    ls::lingo::decompiler::LingoDecompiler decompiler;
                    lingoSource = decompiler.decompile(*script, namesPtr);
                } catch (const std::exception& dex) {
                    lingoSource = "-- decompile failed: " + std::string(dex.what()) + "\n";
                }

                // Build the handler table from the parsed handler metadata (independent of the
                // decompiler text, so the table is complete even if decompilation is partial).
                ScriptRecord rec;
                rec.name = rawName.empty() ? stem : rawName;
                rec.type = scriptTypeToString(script->resolvedScriptType());
                rec.file = "src/scripts/" + stem + ".ts";
                if (const auto it = scriptToCastMember.find(script); it != scriptToCastMember.end()) {
                    rec.castLib = it->second.first;
                    rec.castMember = it->second.second;
                }
                // External-cast scripts that are not attached to the score still need cast
                // library / member metadata; fall back to the ownership map built above.
                if (const auto ownIt = scriptOwnership.find(script); ownIt != scriptOwnership.end()) {
                    if (rec.castLib == 0 || rec.castMember == 0) {
                        rec.castLib = std::get<0>(ownIt->second);
                        rec.castMember = std::get<1>(ownIt->second);
                    }
                    if (rec.name == stem && !std::get<2>(ownIt->second).empty()) {
                        rec.name = std::get<2>(ownIt->second);
                    }
                }
                for (const auto& handler : script->handlers()) {
                    ScriptHandlerRecord hr;
                    hr.name = script->getHandlerName(handler, namesPtr);
                    if (hr.name.empty()) {
                        hr.name = "handler_" + std::to_string(handler.nameId);
                    }
                    hr.event = isLingoSystemEvent(hr.name) ? hr.name : "";
                    for (std::size_t ai = 0; ai < handler.argNameIds.size(); ++ai) {
                        std::string argName = script->resolveName(handler.argNameIds[ai], namesPtr);
                        if (argName.empty()) {
                            argName = "arg" + std::to_string(ai);
                        }
                        hr.args.push_back(std::move(argName));
                    }
                    rec.handlers.push_back(std::move(hr));
                }
                scripts.push_back(rec);

                // Emit the TS module.
                std::ostringstream ts;
                ts << "// @ts-nocheck\n";
                ts << "// Auto-generated from the decompiled Lingo script \""
                   << jsonEscape(rec.name) << "\" (type: " << rec.type << ").\n";
                ts << "//\n";
                ts << "// Stage 7 emission. The decompiled Lingo source is preserved verbatim in "
                   << "`lingoSource` (produced by the LibreShockwave LingoDecompiler — the same code\n";
                ts << "// the C++ player uses to disassemble scripts). The `handlerStubs` table delegates "
                   << "to transpiled TypeScript\n";
                ts << "// functions below, which execute live in the browser via the LingoRuntimeHost. "
                   << "Unhandled AST nodes fall back to\n";
                ts << "// throwing `LingoNotImplemented`. Re-export to regenerate; do not hand-edit.\n\n";
                ts << "import {\n";
                ts << "  LingoNotImplemented,\n";
                ts << "  type LingoMe, type LingoValue,\n";
                ts << "  integer, float, sprite, member, theProperty, setTheProperty, thePropOf, setThePropOf,\n";
                ts << "  callBuiltin, symbol, _symbol, LingoList, LingoPropList,\n";
                ts << "  globalVar, setGlobal, varRef, callMethod, newObj, _new, _return,\n";
                ts << "  spriteIntersects, spriteWithin, menuProp, menuItemProp, soundProp, chunkOf,\n";
                ts << "  deleteChunk, contains, starts, createMe, meProp, setMeProp, sendSprite, put,\n";
                ts << "  voidp, listp, stringp, count, getAt, setAt, getProp, getaProp, addProp, deleteProp,\n";
                ts << "  add, getPropAt, chunkCount, lastChunk, charToNum, numToChar,\n";
                ts << "  duplicate, sort, paletteIndex, abs, sqrt, atan, setProp,\n";
                ts << "} from \"../runtime/lingo-runtime.js\";\n\n";
                ts << "export const scriptName = \"" << jsonEscape(rec.name) << "\";\n";
                ts << "export const scriptType = \"" << jsonEscape(rec.type) << "\";\n";
                ts << "export const castLib = " << rec.castLib << ";\n";
                ts << "export const castMember = " << rec.castMember << ";\n";
                ts << "export const lingoSource = \"" << jsonEscape(lingoSource) << "\";\n\n";
                ts << "export interface ScriptHandler {\n";
                ts << "  name: string;\n";
                ts << "  args: string[];\n";
                ts << "  event: string | null;\n";
                ts << "}\n\n";
                ts << "export const handlers: ScriptHandler[] = [\n";
                for (std::size_t hi = 0; hi < rec.handlers.size(); ++hi) {
                    const auto& hr = rec.handlers[hi];
                    ts << "  { name: \"" << jsonEscape(hr.name) << "\", args: [";
                    for (std::size_t ai = 0; ai < hr.args.size(); ++ai) {
                        if (ai) ts << ", ";
                        ts << "\"" << jsonEscape(hr.args[ai]) << "\"";
                    }
                    ts << "], event: " << (hr.event.empty() ? "null" : ("\"" + jsonEscape(hr.event) + "\"")) << " }";
                    if (hi + 1 < rec.handlers.size()) ts << ",";
                    ts << "\n";
                }
                ts << "];\n\n";

                // Emit transpiled handler bodies via LingoNode::toTypeScript(). Fall back to a
                // throwing stub if translation fails for a particular handler.
                for (const auto& handler : script->handlers()) {
                    const std::string handlerName = script->getHandlerName(handler, namesPtr);
                    if (handlerName.empty()) {
                        continue;
                    }
                    try {
                        ls::lingo::decompiler::LingoDecompiler tsDecompiler;
                        const std::string tsBody = tsDecompiler.emitTypeScriptHandler(handler, *script, namesPtr);
                        ts << tsBody << "\n";
                    } catch (const std::exception& tex) {
                        ts << "/* TypeScript transpilation failed for handler \"" << jsonEscape(handlerName)
                           << "\": " << jsonEscape(tex.what()) << " */\n";
                    }
                }

                ts << "export const handlerStubs: Record<string, (...args: unknown[]) => LingoValue | void> = {\n";
                for (const auto& hr : rec.handlers) {
                    const std::string key = sanitizeTsKey(hr.name);
                    const std::string safeName = sanitizeTsIdentifier(hr.name);
                    const bool explicitMe = !hr.args.empty() &&
                        (hr.args[0] == "me" || hr.args[0] == "ME" || hr.args[0] == "Me");
                    const std::size_t tsParamCount = hr.args.size() + (explicitMe ? 0 : 1);
                    ts << "  \"" << jsonEscape(key) << "\": (...args: unknown[]) => {\n";
                    ts << "    try {\n";
                    ts << "      return " << safeName << "(";
                    for (std::size_t pi = 0; pi < tsParamCount; ++pi) {
                        if (pi > 0) ts << ", ";
                        if (pi == 0 && !explicitMe) {
                            ts << "args[0] as LingoMe";
                        } else {
                            ts << "args[" << pi << "]";
                        }
                    }
                    ts << ");\n";
                    ts << "    } catch (e) {\n";
                    ts << "      if (e instanceof LingoNotImplemented) throw e;\n";
                    ts << "      throw new LingoNotImplemented(\"Lingo handler '" << jsonEscape(hr.name)
                       << "' threw during TS execution: \" + String(e));\n";
                    ts << "    }\n";
                    ts << "  },\n";
                }
                ts << "};\n";

                const fs::path scriptPath = options.outDir / rec.file;
                fs::create_directories(scriptPath.parent_path());
                std::ofstream sf(scriptPath, std::ios::trunc);
                if (!sf) {
                    throw std::runtime_error("Unable to write script module: " + rec.file);
                }
                sf << ts.str();
                if (!sf) {
                    throw std::runtime_error("Unable to write complete script module: " + rec.file);
                }
            }
        }

        // --- manifest.json ---------------------------------------------------
        {
            std::ostringstream m;
            m << "{\n";
            m << "  \"runtimeVersion\": \"0.1.0-stage8\",\n";
            m << "  \"exportedAt\": \"\",\n";
            m << "  \"fixture\": " << "\"" << jsonEscape(options.moviePath.filename().string()) << "\",\n";
            m << "  \"stage\": { \"width\": " << stageWidth
              << ", \"height\": " << stageHeight
              << ", \"backgroundColor\": " << backgroundColor << " },\n";
            m << "  \"frameCount\": " << framesToExport << ",\n";
            m << "  \"totalFrames\": " << frameCount << ",\n";
            m << "  \"soundCount\": " << sounds.size() << ",\n";
            m << "  \"scriptCount\": " << scripts.size() << ",\n";
            m << "  \"assets\": {\n";
            m << "    \"bitmaps\": \"assets/bitmaps\",\n";
            m << "    \"reference\": \"assets/reference\",\n";
            m << "    \"sounds\": \"assets/sounds\",\n";
            m << "    \"scripts\": \"src/scripts\"\n";
            m << "  },\n";
            m << "  \"scripts\": [";
            if (scripts.empty()) {
                m << "]\n";
            } else {
                m << "\n";
                for (std::size_t i = 0; i < scripts.size(); ++i) {
                    const auto& sc = scripts[i];
                    std::vector<std::string> events;
                    for (const auto& hr : sc.handlers) {
                        if (!hr.event.empty()) {
                            events.push_back(hr.event);
                        }
                    }
                    m << "    { \"name\": \"" << jsonEscape(sc.name)
                      << "\", \"type\": \"" << jsonEscape(sc.type)
                      << "\", \"file\": \"" << jsonEscape(sc.file)
                      << "\", \"castLib\": " << sc.castLib
                      << ", \"castMember\": " << sc.castMember
                      << ", \"handlerCount\": " << sc.handlers.size()
                      << ", \"events\": [";
                    for (std::size_t e = 0; e < events.size(); ++e) {
                        if (e) m << ", ";
                        m << "\"" << jsonEscape(events[e]) << "\"";
                    }
                    m << "] }";
                    if (i + 1 < scripts.size()) m << ",";
                    m << "\n";
                }
                m << "  ]\n";
            }
            m << "}\n";
            std::ofstream mf(options.outDir / "manifest.json", std::ios::trunc);
            mf << m.str();
        }

        // --- score.json ------------------------------------------------------
        {
            std::ostringstream s;
            s << "{\n";
            s << "  \"stageWidth\": " << stageWidth << ",\n";
            s << "  \"stageHeight\": " << stageHeight << ",\n";
            s << "  \"backgroundColor\": " << backgroundColor << ",\n";
            s << "  \"frameCount\": " << frameRecords.size() << ",\n";
            s << "  \"totalFrames\": " << frameCount << ",\n";
            s << "  \"tempo\": " << baseTempo << ",\n";
            s << "  \"labels\": [";
            if (labels.empty()) {
                s << "],\n";
            } else {
                s << "\n";
                for (std::size_t li = 0; li < labels.size(); ++li) {
                    s << "    { \"frame\": " << labels[li].frame
                      << ", \"name\": \"" << jsonEscape(labels[li].name) << "\" }";
                    if (li + 1 < labels.size()) {
                        s << ",";
                    }
                    s << "\n";
                }
                s << "  ],\n";
            }
            s << "  \"frames\": [\n";
            for (std::size_t fi = 0; fi < frameRecords.size(); ++fi) {
                const auto& fr = frameRecords[fi];
                const auto& behaviors = frameBehaviorChannels[fi];
                s << "    {\n";
                s << "      \"frame\": " << fr.frame << ",\n";
                s << "      \"tempo\": " << fr.tempo << ",\n";
                s << "      \"behaviors\": [";
                if (behaviors.empty()) {
                    s << "],\n";
                } else {
                    s << "\n";
                    for (std::size_t bi = 0; bi < behaviors.size(); ++bi) {
                        const auto& bc = behaviors[bi];
                        s << "        { \"channel\": " << bc.channel
                          << ", \"castLib\": " << bc.castLib
                          << ", \"castMember\": " << bc.castMember
                          << ", \"scriptName\": \"" << jsonEscape(bc.scriptName) << "\" }";
                        if (bi + 1 < behaviors.size()) {
                            s << ",";
                        }
                        s << "\n";
                    }
                    s << "      ],\n";
                }
                if (fr.frameScript.has_value()) {
                    const auto& fs = *fr.frameScript;
                    s << "      \"frameScript\": { \"channel\": " << fs.channel
                      << ", \"castLib\": " << fs.castLib
                      << ", \"castMember\": " << fs.castMember
                      << ", \"scriptName\": \"" << jsonEscape(fs.scriptName) << "\" },\n";
                } else {
                    s << "      \"frameScript\": null,\n";
                }
                s << "      \"sprites\": [";
                if (fr.sprites.empty()) {
                    s << "]\n";
                } else {
                    s << "\n";
                    for (std::size_t si = 0; si < fr.sprites.size(); ++si) {
                        const auto& sp = fr.sprites[si];
                        s << "        {";
                        s << " \"channel\": " << sp.channel;
                        s << ", \"x\": " << sp.x;
                        s << ", \"y\": " << sp.y;
                        s << ", \"width\": " << sp.width;
                        s << ", \"height\": " << sp.height;
                        s << ", \"locZ\": " << sp.locZ;
                        s << ", \"visible\": " << (sp.visible ? "true" : "false");
                        s << ", \"type\": \"" << jsonEscape(sp.type) << "\"";
                        s << ", \"ink\": " << sp.ink;
                        s << ", \"blend\": " << sp.blend;
                        s << ", \"flipH\": " << (sp.flipH ? "true" : "false");
                        s << ", \"flipV\": " << (sp.flipV ? "true" : "false");
                        s << ", \"rotation\": " << sp.rotation;
                        s << ", \"skew\": " << sp.skew;
                        if (sp.hasBakedBitmap) {
                            s << ", \"bakedBitmapAsset\": \"" << jsonEscape(sp.bakedBitmapAsset) << "\"";
                            s << ", \"bakedWidth\": " << sp.bakedWidth;
                            s << ", \"bakedHeight\": " << sp.bakedHeight;
                        } else {
                            s << ", \"bakedBitmapAsset\": null";
                        }
                        s << ", \"castMemberId\": " << sp.castMemberId;
                        s << ", \"castMemberName\": \"" << jsonEscape(sp.castMemberName) << "\"";
                        s << ", \"hasBehaviors\": " << (sp.hasBehaviors ? "true" : "false");
                        s << " }";
                        if (si + 1 < fr.sprites.size()) {
                            s << ",";
                        }
                        s << "\n";
                    }
                    s << "      ]\n";
                }
                s << "    }";
                if (fi + 1 < frameRecords.size()) {
                    s << ",";
                }
                s << "\n";
            }
            s << "  ]\n";
            s << "}\n";
            std::ofstream sf(options.outDir / "score.json", std::ios::trunc);
            sf << s.str();
        }

        // --- cast.json ------------------------------------------------------
        {
            std::ofstream cf(options.outDir / "cast.json", std::ios::trunc);
            cf << "{\n";
            cf << "  \"note\": \"Stage 7: cast member registry for name-based Lingo resolution, plus decoded sound assets. "
                  "Director sound playback is Lingo-driven, so per-frame cues are not statically captured; "
                  "the AudioPlayer plays sounds by name, driven by emitted Lingo.\",\n";
            cf << "  \"members\": [\n";
            for (std::size_t i = 0; i < castMembers.size(); ++i) {
                const auto& cm = castMembers[i];
                cf << "    {";
                cf << " \"id\": " << cm.id;
                cf << ", \"castLib\": " << cm.castLib;
                cf << ", \"name\": \"" << jsonEscape(cm.name) << "\"";
                cf << ", \"type\": \"" << jsonEscape(cm.type) << "\"";
                cf << ", \"bakedBitmapAsset\": " << (cm.bakedBitmapAsset.empty() ? "null" : "\"" + jsonEscape(cm.bakedBitmapAsset) + "\"");
                cf << ", \"bakedWidth\": " << cm.bakedWidth;
                cf << ", \"bakedHeight\": " << cm.bakedHeight;
                cf << ", \"text\": " << (cm.text.empty() ? "null" : "\"" + jsonEscape(cm.text) + "\"");
                cf << ", \"filmLoopFrames\": [";
                for (std::size_t f = 0; f < cm.filmLoopFrames.size(); ++f) {
                    cf << "\"" << jsonEscape(cm.filmLoopFrames[f]) << "\"";
                    if (f + 1 < cm.filmLoopFrames.size()) {
                        cf << ", ";
                    }
                }
                cf << "]";
                cf << " }";
                if (i + 1 < castMembers.size()) {
                    cf << ",";
                }
                cf << "\n";
            }
            cf << "  ],\n";
            cf << "  \"sounds\": [\n";
            for (std::size_t i = 0; i < sounds.size(); ++i) {
                const auto& s = sounds[i];
                cf << "    {";
                cf << " \"name\": \"" << jsonEscape(s.name) << "\"";
                cf << ", \"asset\": \"" << jsonEscape(s.assetRef) << "\"";
                cf << ", \"format\": \"" << jsonEscape(s.format) << "\"";
                cf << ", \"sampleRate\": " << s.sampleRate;
                cf << ", \"channels\": " << s.channels;
                cf << ", \"bitsPerSample\": " << s.bitsPerSample;
                cf << ", \"durationSeconds\": " << s.durationSeconds;
                cf << ", \"codec\": \"" << jsonEscape(s.codec) << "\"";
                cf << " }";
                if (i + 1 < sounds.size()) {
                    cf << ",";
                }
                cf << "\n";
            }
            cf << "  ]\n";
            cf << "}\n";
        }

        std::cout << "Exported " << frameRecords.size() << " frame(s) of "
                  << options.moviePath.filename().string() << " to " << options.outDir.string() << "\n";
        std::cout << "Stage size: " << stageWidth << "x" << stageHeight
                  << ", background: 0x" << std::hex << backgroundColor << std::dec << "\n";
        if (!scriptErrors.empty()) {
            std::cerr << "Script errors during load (" << scriptErrors.size() << "):\n";
            for (const auto& e : scriptErrors) {
                std::cerr << "  " << e << "\n";
            }
        }
        return 0;
    } catch (const std::exception& ex) {
        std::cerr << "export_ts: " << ex.what() << "\n";
        return 1;
    }
}