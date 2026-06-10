#pragma once

#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/lingo/builtin/BuiltinRegistry.hpp"
#include "libreshockwave/player/cast/CastLib.hpp"

namespace libreshockwave {
class DirectorFile;
}

namespace libreshockwave::cast {
class CastMember;
}

namespace libreshockwave::bitmap {
class Palette;
}

namespace libreshockwave::chunks {
class CastMemberChunk;
}

namespace libreshockwave::player::cast {

class CastLibManager {
public:
    using CastDataRequestCallback = std::function<void(int castLibNumber, const std::string& fileName)>;
    using MemberSlotRetiredCallback = std::function<void(int castLibNumber, int memberNumber)>;

    explicit CastLibManager(std::shared_ptr<DirectorFile> file,
                            CastDataRequestCallback castDataRequestCallback = {});

    [[nodiscard]] std::shared_ptr<DirectorFile> file() const;

    [[nodiscard]] std::shared_ptr<CastLib> getCastLib(int castLibNumber);
    [[nodiscard]] std::shared_ptr<CastLib> getCastLibByNameInternal(const std::string& name);
    [[nodiscard]] int getCastLibByNumber(int castLibNumber);
    [[nodiscard]] int getCastLibByName(const std::string& name);
    [[nodiscard]] int getCastLibCount();
    [[nodiscard]] std::map<int, std::shared_ptr<CastLib>>& castLibs();

    [[nodiscard]] lingo::Datum getCastLibProp(int castLibNumber, const std::string& propName);
    bool setCastLibProp(int castLibNumber, const std::string& propName, const lingo::Datum& value);
    [[nodiscard]] std::string getCastLibName(int castLibNumber);
    [[nodiscard]] std::string getCastLibFileName(int castLibNumber);
    [[nodiscard]] bool fetchCastLib(int castLibNumber);
    [[nodiscard]] bool isCastLibExternal(int castLibNumber);
    void preloadCasts(int mode);

    [[nodiscard]] lingo::Datum getMember(int castLibNumber, int memberNumber);
    [[nodiscard]] lingo::Datum getMemberByName(int castLibNumber, const std::string& memberName);
    [[nodiscard]] lingo::Datum getRegistryMemberByName(int castLibNumber, const std::string& memberName);
    [[nodiscard]] bool memberExists(int castLibNumber, int memberNumber);
    [[nodiscard]] bool isRegistryVisibleMember(int castLibNumber, int memberNumber);
    [[nodiscard]] int getMemberCount(int castLibNumber);
    [[nodiscard]] lingo::Datum getMemberProp(int castLibNumber, int memberNumber, const std::string& propName);
    bool setMemberProp(int castLibNumber, int memberNumber, const std::string& propName, const lingo::Datum& value);
    [[nodiscard]] lingo::Datum getFieldValue(const lingo::Datum& identifier, int castLibNumber);
    void setFieldValue(const lingo::Datum& identifier, int castLibNumber, const std::string& value);
    [[nodiscard]] std::shared_ptr<chunks::CastMemberChunk> getCastMember(int castLibNumber, int memberNumber);
    [[nodiscard]] std::shared_ptr<chunks::CastMemberChunk> getCastMemberByName(const std::string& memberName);
    [[nodiscard]] std::shared_ptr<libreshockwave::cast::CastMember> resolveMember(int castLibNumber, int memberNumber);
    [[nodiscard]] std::shared_ptr<libreshockwave::cast::CastMember> findCastMemberByName(const std::string& memberName);
    [[nodiscard]] std::shared_ptr<libreshockwave::cast::CastMember> findRuntimeMember(
        const std::shared_ptr<chunks::CastMemberChunk>& target);
    [[nodiscard]] std::shared_ptr<const bitmap::Palette> resolvePaletteByMember(int castLibNumber, int memberNumber);
    [[nodiscard]] lingo::Datum createMember(int castLibNumber, const std::string& memberType);
    [[nodiscard]] lingo::Datum createMember(const std::string& memberName, const std::string& memberType);
    bool eraseMember(int castLibNumber, int memberNumber);
    [[nodiscard]] lingo::Datum callMemberMethod(int castLibNumber,
                                                int memberNumber,
                                                const std::string& methodName,
                                                const std::vector<lingo::Datum>& args);
    bool importFileIntoMember(int castLibNumber,
                              int memberNumber,
                              const std::string& url,
                              const lingo::Datum& options);

    void cacheExternalData(const std::string& url, const std::vector<std::uint8_t>& data);
    [[nodiscard]] std::optional<std::vector<std::uint8_t>> getCachedExternalData(const std::string& baseName) const;
    [[nodiscard]] std::optional<std::vector<std::uint8_t>> getCachedDownloadedData(const std::string& url) const;
    bool setExternalCastData(int castLibNumber, const std::vector<std::uint8_t>& data);
    bool setExternalCastDataByUrl(const std::string& url, const std::vector<std::uint8_t>& data);
    [[nodiscard]] std::vector<int> getMatchingCastLibNumbersByUrl(const std::string& url);
    [[nodiscard]] std::vector<int> getRequestedExternalCastSlots(const std::string& url);
    void clearPendingExternalLoad(int castLibNumber);
    void setMemberSlotRetiredCallback(MemberSlotRetiredCallback callback);

    void installBuiltinCallbacks(lingo::builtin::BuiltinContext& context);

private:
    void ensureInitialized();
    void tryLoadCastFromCache(int castLibNumber, const std::string& newFileName);
    void markPendingExternalLoad(int castLibNumber, const std::string& fileName);
    [[nodiscard]] std::vector<std::shared_ptr<CastLib>> findCastLibsByUrl(const std::string& url);
    [[nodiscard]] static std::vector<std::string> downloadCacheKeys(const std::string& url);
    [[nodiscard]] static lingo::Datum getMemberByNameInCast(const std::shared_ptr<CastLib>& castLib,
                                                            const std::string& memberName);
    [[nodiscard]] static bool isRegistryFallbackEligibleCast(const std::shared_ptr<CastLib>& castLib);
    [[nodiscard]] std::shared_ptr<libreshockwave::cast::CastMember> resolveFieldMember(
        const lingo::Datum& identifier,
        int castLibNumber);

    std::shared_ptr<DirectorFile> file_;
    std::map<int, std::shared_ptr<CastLib>> castLibs_;
    std::unordered_map<std::string, std::vector<std::uint8_t>> castDataCache_;
    std::map<int, std::string> pendingExternalLoads_;
    CastDataRequestCallback castDataRequestCallback_;
    MemberSlotRetiredCallback memberSlotRetiredCallback_;
    bool initialized_ = false;
};

} // namespace libreshockwave::player::cast
