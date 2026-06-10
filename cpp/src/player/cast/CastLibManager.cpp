#include "libreshockwave/player/cast/CastLibManager.hpp"

#include <algorithm>
#include <cctype>
#include <utility>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/cast/CastMember.hpp"
#include "libreshockwave/chunks/CastChunk.hpp"
#include "libreshockwave/chunks/CastListChunk.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/id/Ids.hpp"
#include "libreshockwave/util/FileUtil.hpp"

namespace libreshockwave::player::cast {
namespace {

std::string lower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

bool equalsIgnoreCase(const std::string& lhs, const std::string& rhs) {
    return lower(lhs) == lower(rhs);
}

} // namespace

CastLibManager::CastLibManager(std::shared_ptr<DirectorFile> file,
                               CastDataRequestCallback castDataRequestCallback)
    : file_(std::move(file)),
      castDataRequestCallback_(std::move(castDataRequestCallback)) {}

std::shared_ptr<DirectorFile> CastLibManager::file() const {
    return file_;
}

std::shared_ptr<CastLib> CastLibManager::getCastLib(int castLibNumber) {
    ensureInitialized();
    if (const auto found = castLibs_.find(castLibNumber); found != castLibs_.end()) {
        if (found->second && !found->second->isLoaded()) {
            found->second->load();
        }
        return found->second;
    }
    return nullptr;
}

std::shared_ptr<CastLib> CastLibManager::getCastLibByNameInternal(const std::string& name) {
    ensureInitialized();
    for (const auto& [_, castLib] : castLibs_) {
        if (castLib && equalsIgnoreCase(castLib->name(), name)) {
            if (!castLib->isLoaded()) {
                castLib->load();
            }
            return castLib;
        }
    }
    if (equalsIgnoreCase(name, "internal")) {
        return getCastLib(1);
    }
    return nullptr;
}

int CastLibManager::getCastLibByNumber(int castLibNumber) {
    return getCastLib(castLibNumber) ? castLibNumber : -1;
}

int CastLibManager::getCastLibByName(const std::string& name) {
    auto castLib = getCastLibByNameInternal(name);
    return castLib ? castLib->number() : -1;
}

int CastLibManager::getCastLibCount() {
    ensureInitialized();
    return static_cast<int>(castLibs_.size());
}

std::map<int, std::shared_ptr<CastLib>>& CastLibManager::castLibs() {
    ensureInitialized();
    return castLibs_;
}

lingo::Datum CastLibManager::getCastLibProp(int castLibNumber, const std::string& propName) {
    auto castLib = getCastLib(castLibNumber);
    return castLib ? castLib->getProp(propName) : lingo::Datum::voidValue();
}

bool CastLibManager::setCastLibProp(int castLibNumber, const std::string& propName, const lingo::Datum& value) {
    auto castLib = getCastLib(castLibNumber);
    if (!castLib) {
        return false;
    }
    const bool result = castLib->setProp(propName, value);
    if (result && equalsIgnoreCase(propName, "filename")) {
        tryLoadCastFromCache(castLibNumber, value.stringValue());
    }
    return result;
}

std::string CastLibManager::getCastLibName(int castLibNumber) {
    ensureInitialized();
    if (const auto found = castLibs_.find(castLibNumber); found != castLibs_.end() && found->second) {
        return found->second->name();
    }
    return "";
}

std::string CastLibManager::getCastLibFileName(int castLibNumber) {
    ensureInitialized();
    if (const auto found = castLibs_.find(castLibNumber); found != castLibs_.end() && found->second) {
        return found->second->fileName();
    }
    return "";
}

bool CastLibManager::fetchCastLib(int castLibNumber) {
    ensureInitialized();
    if (const auto found = castLibs_.find(castLibNumber); found != castLibs_.end() && found->second) {
        return found->second->isFetched();
    }
    return false;
}

bool CastLibManager::isCastLibExternal(int castLibNumber) {
    ensureInitialized();
    if (const auto found = castLibs_.find(castLibNumber); found != castLibs_.end() && found->second) {
        return found->second->isExternal();
    }
    return false;
}

void CastLibManager::preloadCasts(int mode) {
    ensureInitialized();
    for (const auto& [_, castLib] : castLibs_) {
        if (castLib && castLib->preloadMode() == mode && castLib->isFetched() && !castLib->isLoaded()) {
            castLib->load();
        }
    }
}

lingo::Datum CastLibManager::getMember(int castLibNumber, int memberNumber) {
    if (castLibNumber < 1 || memberNumber < 0) {
        return lingo::Datum::voidValue();
    }
    (void)getCastLib(castLibNumber);
    return lingo::Datum::castMemberRef(id::CastLibId(castLibNumber), id::MemberId(memberNumber));
}

lingo::Datum CastLibManager::getMemberByName(int castLibNumber, const std::string& memberName) {
    ensureInitialized();
    if (castLibNumber > 0) {
        return getMemberByNameInCast(getCastLib(castLibNumber), memberName);
    }

    for (const auto& [_, castLib] : castLibs_) {
        auto loadedCast = getCastLib(castLib->number());
        if (!isRegistryFallbackEligibleCast(loadedCast)) {
            continue;
        }
        auto found = getMemberByNameInCast(loadedCast, memberName);
        if (!found.isVoid()) {
            return found;
        }
    }

    for (const auto& [_, castLib] : castLibs_) {
        auto found = getMemberByNameInCast(getCastLib(castLib->number()), memberName);
        if (!found.isVoid()) {
            return found;
        }
    }
    return lingo::Datum::voidValue();
}

lingo::Datum CastLibManager::getRegistryMemberByName(int castLibNumber, const std::string& memberName) {
    ensureInitialized();
    if (castLibNumber > 0) {
        auto castLib = getCastLib(castLibNumber);
        if (!isRegistryFallbackEligibleCast(castLib)) {
            return lingo::Datum::voidValue();
        }
        return getMemberByNameInCast(castLib, memberName);
    }

    for (const auto& [_, castLib] : castLibs_) {
        auto loadedCast = getCastLib(castLib->number());
        if (!isRegistryFallbackEligibleCast(loadedCast)) {
            continue;
        }
        auto found = getMemberByNameInCast(loadedCast, memberName);
        if (!found.isVoid()) {
            return found;
        }
    }
    return lingo::Datum::voidValue();
}

bool CastLibManager::memberExists(int castLibNumber, int memberNumber) {
    auto castLib = getCastLib(castLibNumber);
    return castLib && castLib->getMember(memberNumber) != nullptr;
}

bool CastLibManager::isRegistryVisibleMember(int castLibNumber, int memberNumber) {
    if (memberNumber <= 0) {
        return false;
    }
    auto castLib = getCastLib(castLibNumber);
    return isRegistryFallbackEligibleCast(castLib) && castLib->getMember(memberNumber) != nullptr;
}

int CastLibManager::getMemberCount(int castLibNumber) {
    auto castLib = getCastLib(castLibNumber);
    return castLib ? castLib->memberCount() : 0;
}

lingo::Datum CastLibManager::getMemberProp(int castLibNumber, int memberNumber, const std::string& propName) {
    auto castLib = getCastLib(castLibNumber);
    return castLib ? castLib->getMemberProp(memberNumber, propName) : CastLib::getInvalidMemberProp(propName);
}

bool CastLibManager::setMemberProp(int castLibNumber,
                                   int memberNumber,
                                   const std::string& propName,
                                   const lingo::Datum& value) {
    auto castLib = getCastLib(castLibNumber);
    return castLib && castLib->setMemberProp(memberNumber, propName, value);
}

std::shared_ptr<chunks::CastMemberChunk> CastLibManager::getCastMember(int castLibNumber, int memberNumber) {
    auto castLib = getCastLib(castLibNumber);
    return castLib ? castLib->findMemberByNumber(memberNumber) : nullptr;
}

std::shared_ptr<chunks::CastMemberChunk> CastLibManager::getCastMemberByName(const std::string& memberName) {
    ensureInitialized();
    for (const auto& [_, castLib] : castLibs_) {
        auto member = getCastLib(castLib->number())->findMemberByName(memberName);
        if (member) {
            return member;
        }
    }
    return nullptr;
}

std::shared_ptr<libreshockwave::cast::CastMember> CastLibManager::resolveMember(int castLibNumber, int memberNumber) {
    auto castLib = getCastLib(castLibNumber);
    return castLib ? castLib->getMember(memberNumber) : nullptr;
}

std::shared_ptr<libreshockwave::cast::CastMember> CastLibManager::findCastMemberByName(const std::string& memberName) {
    ensureInitialized();
    for (const auto& [_, castLib] : castLibs_) {
        if (castLib->isLoaded()) {
            if (auto dynamic = castLib->getMemberByName(memberName)) {
                return dynamic;
            }
        }
    }

    for (const auto& [_, castLib] : castLibs_) {
        auto loadedCast = getCastLib(castLib->number());
        if (auto chunk = loadedCast->findMemberByName(memberName)) {
            const int memberNumber = loadedCast->getMemberNumber(chunk);
            if (auto member = loadedCast->getMember(memberNumber)) {
                return member;
            }
        }
    }
    return nullptr;
}

std::shared_ptr<libreshockwave::cast::CastMember> CastLibManager::findRuntimeMember(
    const std::shared_ptr<chunks::CastMemberChunk>& target) {
    if (!target) {
        return nullptr;
    }
    ensureInitialized();
    for (const auto& [_, castLib] : castLibs_) {
        if (!castLib->isLoaded()) {
            continue;
        }
        const int memberNumber = castLib->getMemberNumber(target);
        if (memberNumber >= 0) {
            return castLib->getMember(memberNumber);
        }
    }
    return nullptr;
}

void CastLibManager::cacheExternalData(const std::string& url, const std::vector<std::uint8_t>& data) {
    for (const auto& key : downloadCacheKeys(url)) {
        castDataCache_[key] = data;
    }
    for (const auto& castLib : findCastLibsByUrl(url)) {
        castLib->cacheFetchedExternalData(data);
    }
}

std::optional<std::vector<std::uint8_t>> CastLibManager::getCachedExternalData(const std::string& baseName) const {
    const auto found = castDataCache_.find(lower(baseName));
    if (found == castDataCache_.end()) {
        return std::nullopt;
    }
    return found->second;
}

std::optional<std::vector<std::uint8_t>> CastLibManager::getCachedDownloadedData(const std::string& url) const {
    for (const auto& key : downloadCacheKeys(url)) {
        if (const auto found = castDataCache_.find(key); found != castDataCache_.end()) {
            return found->second;
        }
    }
    return std::nullopt;
}

bool CastLibManager::setExternalCastData(int castLibNumber, const std::vector<std::uint8_t>& data) {
    ensureInitialized();
    const auto found = castLibs_.find(castLibNumber);
    if (found == castLibs_.end() || !found->second) {
        return false;
    }
    const bool loaded = found->second->setExternalData(data);
    if (loaded) {
        clearPendingExternalLoad(castLibNumber);
    }
    return loaded;
}

bool CastLibManager::setExternalCastDataByUrl(const std::string& url, const std::vector<std::uint8_t>& data) {
    ensureInitialized();
    bool anyLoaded = false;
    for (const auto& castLib : findCastLibsByUrl(url)) {
        if (castLib->isLoaded() && castLib->memberCount() > 0) {
            anyLoaded = true;
            continue;
        }
        if (setExternalCastData(castLib->number(), data)) {
            anyLoaded = true;
        }
    }
    return anyLoaded;
}

std::vector<int> CastLibManager::getMatchingCastLibNumbersByUrl(const std::string& url) {
    ensureInitialized();
    const auto baseName = util::getFileNameWithoutExtension(util::getFileName(url));
    std::vector<int> result;
    if (baseName.empty()) {
        return result;
    }
    for (const auto& [_, castLib] : castLibs_) {
        if (castLib->matchesAuthoredExternalFile(baseName)) {
            result.push_back(castLib->number());
        }
    }
    return result;
}

std::vector<int> CastLibManager::getRequestedExternalCastSlots(const std::string& url) {
    ensureInitialized();
    const auto baseName = util::getFileNameWithoutExtension(util::getFileName(url));
    std::vector<int> result;
    if (baseName.empty()) {
        return result;
    }

    for (const auto& castLib : findCastLibsByUrl(url)) {
        const int number = castLib->number();
        const auto pending = pendingExternalLoads_.find(number);
        const bool requested = castLib->isFetching() ||
                               (pending != pendingExternalLoads_.end() && equalsIgnoreCase(baseName, pending->second)) ||
                               (!castLib->isLoaded() && castLib->matchesAuthoredExternalFile(baseName));
        if (requested) {
            result.push_back(number);
        }
    }
    return result;
}

void CastLibManager::clearPendingExternalLoad(int castLibNumber) {
    pendingExternalLoads_.erase(castLibNumber);
}

void CastLibManager::installBuiltinCallbacks(lingo::builtin::BuiltinContext& context) {
    context.castLibNumberResolver = [this](int castLib) {
        return getCastLibByNumber(castLib);
    };
    context.castLibNameResolver = [this](const std::string& name) {
        return getCastLibByName(name);
    };
    context.castLibCountSupplier = [this]() {
        return getCastLibCount();
    };
    context.castMemberResolver = [this](int castLib, int memberNum) {
        return getMember(castLib, memberNum);
    };
    context.castMemberNameResolver = [this](int castLib, const std::string& memberName) {
        return getMemberByName(castLib, memberName);
    };
    context.castMemberExistsResolver = [this](int castLib, int memberNum) {
        return memberExists(castLib, memberNum);
    };
    context.castMemberPropertyGetter = [this](int castLib, int memberNum, const std::string& propertyName) {
        return getMemberProp(castLib, memberNum, propertyName);
    };
    context.castMemberPropertySetter = [this](int castLib, int memberNum, const std::string& propertyName, const lingo::Datum& value) {
        return setMemberProp(castLib, memberNum, propertyName, value);
    };
}

void CastLibManager::ensureInitialized() {
    if (initialized_ || !file_) {
        return;
    }
    initialized_ = true;

    auto castList = file_->castList();
    const auto& casts = file_->casts();
    const auto basePath = file_->basePath();
    (void)basePath;

    if (castList && !castList->entries().empty()) {
        for (int index = 0; index < static_cast<int>(castList->entries().size()); ++index) {
            const int castLibNumber = index + 1;
            const auto& listEntry = castList->entries()[static_cast<std::size_t>(index)];
            const bool isExternal = !listEntry.path.empty();
            std::shared_ptr<chunks::CastChunk> castChunk;
            if (!isExternal) {
                castChunk = file_->getMappedCastChunk(castLibNumber);
            } else if (index < static_cast<int>(casts.size())) {
                castChunk = casts[static_cast<std::size_t>(index)];
            }

            auto castLib = std::make_shared<CastLib>(castLibNumber, castChunk, &listEntry);
            castLib->setPreloadMode(listEntry.preloadSettings);
            if (!isExternal) {
                castLib->setSourceFile(file_);
            }
            castLibs_[castLibNumber] = castLib;
        }
    } else if (!casts.empty()) {
        for (int index = 0; index < static_cast<int>(casts.size()); ++index) {
            const int castLibNumber = index + 1;
            auto castLib = std::make_shared<CastLib>(castLibNumber, casts[static_cast<std::size_t>(index)], nullptr);
            castLib->setSourceFile(file_);
            castLibs_[castLibNumber] = castLib;
        }
    }
}

void CastLibManager::tryLoadCastFromCache(int castLibNumber, const std::string& newFileName) {
    if (newFileName.empty()) {
        return;
    }
    markPendingExternalLoad(castLibNumber, newFileName);
    if (castDataRequestCallback_) {
        castDataRequestCallback_(castLibNumber, newFileName);
    }
}

void CastLibManager::markPendingExternalLoad(int castLibNumber, const std::string& fileName) {
    pendingExternalLoads_[castLibNumber] = util::getFileNameWithoutExtension(util::getFileName(fileName));
}

std::vector<std::shared_ptr<CastLib>> CastLibManager::findCastLibsByUrl(const std::string& url) {
    ensureInitialized();
    const auto extractedFileName = util::getFileName(url);
    const auto fileNameNoExt = util::getFileNameWithoutExtension(extractedFileName);
    std::vector<std::shared_ptr<CastLib>> result;
    if (fileNameNoExt.empty()) {
        return result;
    }

    for (const auto& [_, castLib] : castLibs_) {
        const auto castPath = castLib->fileName();
        if (castPath.empty()) {
            continue;
        }
        const auto castFileNoExt = util::getFileNameWithoutExtension(util::getFileName(castPath));
        if (equalsIgnoreCase(castFileNoExt, fileNameNoExt)) {
            result.push_back(castLib);
        }
    }
    return result;
}

std::vector<std::string> CastLibManager::downloadCacheKeys(const std::string& url) {
    std::vector<std::string> keys;
    if (url.empty()) {
        return keys;
    }
    const auto fileName = util::getFileName(url);
    if (fileName.empty()) {
        return keys;
    }
    keys.push_back(lower(fileName));
    const auto baseName = util::getFileNameWithoutExtension(fileName);
    if (!baseName.empty()) {
        const auto loweredBase = lower(baseName);
        if (std::find(keys.begin(), keys.end(), loweredBase) == keys.end()) {
            keys.push_back(loweredBase);
        }
    }
    return keys;
}

lingo::Datum CastLibManager::getMemberByNameInCast(const std::shared_ptr<CastLib>& castLib,
                                                   const std::string& memberName) {
    if (!castLib || memberName.empty()) {
        return lingo::Datum::voidValue();
    }
    if (auto member = castLib->findMemberByName(memberName)) {
        const int memberNumber = castLib->getMemberNumber(member);
        if (memberNumber >= 0) {
            return lingo::Datum::castMemberRef(id::CastLibId(castLib->number()), id::MemberId(memberNumber));
        }
    }
    if (auto member = castLib->getMemberByName(memberName)) {
        return lingo::Datum::castMemberRef(id::CastLibId(castLib->number()), id::MemberId(member->memberNum()));
    }
    return lingo::Datum::voidValue();
}

bool CastLibManager::isRegistryFallbackEligibleCast(const std::shared_ptr<CastLib>& castLib) {
    return castLib != nullptr && castLib->usesStableRegistryBinding();
}

} // namespace libreshockwave::player::cast
