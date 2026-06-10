#include "libreshockwave/player/cast/CastLib.hpp"

#include <algorithm>
#include <cctype>
#include <exception>
#include <string_view>
#include <utility>

#include "libreshockwave/DirectorFile.hpp"
#include "libreshockwave/bitmap/Bitmap.hpp"
#include "libreshockwave/cast/CastMember.hpp"
#include "libreshockwave/cast/MemberType.hpp"
#include "libreshockwave/chunks/CastChunk.hpp"
#include "libreshockwave/chunks/CastMemberChunk.hpp"
#include "libreshockwave/chunks/ConfigChunk.hpp"
#include "libreshockwave/chunks/KeyTableChunk.hpp"
#include "libreshockwave/chunks/RawChunk.hpp"
#include "libreshockwave/chunks/ScriptChunk.hpp"
#include "libreshockwave/chunks/ScriptNamesChunk.hpp"
#include "libreshockwave/chunks/TextChunk.hpp"
#include "libreshockwave/format/ChunkType.hpp"
#include "libreshockwave/io/BinaryReader.hpp"
#include "libreshockwave/player/cast/FontRegistry.hpp"
#include "libreshockwave/util/FileUtil.hpp"

namespace libreshockwave::player::cast {
namespace {

std::string lower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return value;
}

std::string trim(const std::string& value) {
    auto begin = value.begin();
    while (begin != value.end() && std::isspace(static_cast<unsigned char>(*begin))) {
        ++begin;
    }
    auto end = value.end();
    while (end != begin && std::isspace(static_cast<unsigned char>(*(end - 1)))) {
        --end;
    }
    return std::string(begin, end);
}

bool equalsIgnoreCase(const std::string& lhs, const std::string& rhs) {
    return lower(lhs) == lower(rhs);
}

bool startsWithIgnoreCase(const std::string& value, const std::string& prefix) {
    if (value.size() < prefix.size()) {
        return false;
    }
    return equalsIgnoreCase(value.substr(0, prefix.size()), prefix);
}

bool endsWith(const std::string& value, const std::string& suffix) {
    return value.size() >= suffix.size() &&
           value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

lingo::Datum stringDatum(const std::string& value) {
    return lingo::Datum::of(value);
}

std::string normalizeTextLineEndings(std::string value) {
    std::string result;
    result.reserve(value.size());
    for (std::size_t index = 0; index < value.size(); ++index) {
        const char ch = value[index];
        if (ch == '\r') {
            result.push_back('\r');
            if (index + 1 < value.size() && value[index + 1] == '\n') {
                ++index;
            }
        } else if (ch == '\n') {
            result.push_back('\r');
        } else {
            result.push_back(ch);
        }
    }
    return result;
}

std::string stripHtmlTags(std::string_view value) {
    std::string result;
    result.reserve(value.size());
    bool inTag = false;
    for (const char ch : value) {
        if (ch == '<') {
            inTag = true;
            continue;
        }
        if (ch == '>' && inTag) {
            inTag = false;
            continue;
        }
        if (!inTag) {
            result.push_back(ch);
        }
    }
    return result;
}

} // namespace

CastLib::CastLib(int number,
                 std::shared_ptr<chunks::CastChunk> castChunk,
                 const chunks::CastListChunk::CastListEntry* listEntry)
    : castLibId_(number),
      selection_(lingo::Datum::list()),
      castChunk_(std::move(castChunk)) {
    if (listEntry != nullptr) {
        name_ = listEntry->name;
        fileName_ = listEntry->path;
        authoredFileName_ = fileName_;
        preloadMode_ = listEntry->preloadSettings;
    }

    if (name_.empty() && number == 1) {
        name_ = "Internal";
    }
}

void CastLib::setSourceFile(std::shared_ptr<DirectorFile> file) {
    sourceFile_ = std::move(file);
}

std::shared_ptr<DirectorFile> CastLib::sourceFile() const {
    return sourceFile_;
}

void CastLib::reloadFromFile(std::shared_ptr<DirectorFile> file) {
    if (!file) {
        return;
    }
    sourceFile_ = std::move(file);
    state_ = State::None;
    memberChunks_.clear();
    members_.clear();
    scripts_.clear();
    cachedScripts_.clear();
    totalSlotCount_ = 0;
    load();
}

id::CastLibId CastLib::castLibId() const { return castLibId_; }
int CastLib::number() const { return castLibId_.value(); }
const std::string& CastLib::name() const { return name_; }

void CastLib::setName(std::string name) {
    name_ = std::move(name);
    nameExplicitlyAssigned_ = true;
    nameLoadedFromExternalFile_ = false;
}

const std::string& CastLib::fileName() const { return fileName_; }

void CastLib::setFileName(std::string fileName) {
    if (!sameFileBinding(fileName_, fileName)) {
        invalidateFileBackedBinding();
    }
    fileName_ = std::move(fileName);
    if (!nameExplicitlyAssigned_ && hasAuthoredExternalBinding() && !usesAuthoredExternalBinding()) {
        name_ = fileName_;
        nameLoadedFromExternalFile_ = false;
    }
}

bool CastLib::isExternal() const {
    return !fileName_.empty();
}

bool CastLib::isFetched() const {
    return sourceFile_ != nullptr || !fetchedExternalData_.empty() || !isExternal();
}

bool CastLib::hasAuthoredExternalBinding() const {
    return !authoredFileName_.empty();
}

bool CastLib::matchesAuthoredExternalFile(const std::string& baseName) const {
    if (!hasAuthoredExternalBinding() || baseName.empty()) {
        return false;
    }
    const auto authoredBaseName = util::getFileNameWithoutExtension(util::getFileName(authoredFileName_));
    return equalsIgnoreCase(authoredBaseName, baseName);
}

bool CastLib::usesAuthoredExternalBinding() const {
    if (!hasAuthoredExternalBinding()) {
        return true;
    }
    if (fileName_.empty()) {
        return false;
    }
    const auto currentBaseName = util::getFileNameWithoutExtension(util::getFileName(fileName_));
    return matchesAuthoredExternalFile(currentBaseName);
}

bool CastLib::usesStableRegistryBinding() const {
    if (!hasAuthoredExternalBinding() || usesAuthoredExternalBinding()) {
        return true;
    }
    if (nameLoadedFromExternalFile_) {
        return false;
    }
    const auto runtimeName = trim(name_);
    if (runtimeName.empty() || usesGeneratedPlaceholderName(runtimeName)) {
        return false;
    }
    return !looksLikeDirectFileBindingName(runtimeName);
}

CastLib::State CastLib::state() const { return state_; }
bool CastLib::isLoaded() const { return state_ == State::Loaded; }
bool CastLib::isFetching() const { return state_ == State::Fetching; }

void CastLib::markFetching() {
    if (state_ == State::None) {
        state_ = State::Fetching;
    }
}

int CastLib::preloadMode() const { return preloadMode_; }

void CastLib::setPreloadMode(int preloadMode) {
    preloadMode_ = preloadMode;
}

void CastLib::load() {
    if (state_ == State::Loaded) {
        return;
    }

    state_ = State::Loading;

    if (!sourceFile_) {
        if (!fetchedExternalData_.empty() && setExternalData(fetchedExternalData_)) {
            return;
        }
        state_ = isExternal() ? State::None : State::Loaded;
        return;
    }

    if (isExternal()) {
        if (!sourceFile_->casts().empty()) {
            loadFromExternalFile();
        }
        scanXmedFonts();
        state_ = State::Loaded;
        return;
    }

    if (!castChunk_ && !sourceFile_->casts().empty()) {
        loadFromExternalFile();
        scanXmedFonts();
        state_ = State::Loaded;
        return;
    }

    if (castChunk_) {
        loadMembersFromCast(castChunk_, minMember());
    }

    scanXmedFonts();
    state_ = State::Loaded;
}

int CastLib::memberCount() {
    if (!isLoaded()) {
        load();
    }
    return totalSlotCount_ > 0 ? totalSlotCount_ : static_cast<int>(memberChunks_.size());
}

const std::map<int, std::shared_ptr<chunks::CastMemberChunk>>& CastLib::memberChunks() {
    if (!isLoaded()) {
        load();
    }
    return memberChunks_;
}

std::shared_ptr<chunks::CastMemberChunk> CastLib::findMemberByNumber(int memberNumber) {
    if (!isLoaded()) {
        load();
    }
    if (const auto found = memberChunks_.find(memberNumber); found != memberChunks_.end()) {
        return found->second;
    }
    return nullptr;
}

std::shared_ptr<chunks::CastMemberChunk> CastLib::findMemberByName(const std::string& memberName) {
    if (!isLoaded()) {
        load();
    }
    if (auto direct = findMemberChunkByNameExact(memberName)) {
        return direct;
    }
    if (const auto prefixed = sourcePrefixedLookupName(memberName); prefixed.has_value()) {
        return findMemberChunkByNameExact(*prefixed);
    }
    return nullptr;
}

std::shared_ptr<libreshockwave::cast::CastMember> CastLib::getMember(int memberNumber) {
    if (!isLoaded()) {
        load();
    }
    if (const auto found = members_.find(memberNumber); found != members_.end()) {
        return found->second;
    }
    auto chunk = findMemberByNumber(memberNumber);
    if (!chunk) {
        return nullptr;
    }
    auto member = std::make_shared<libreshockwave::cast::CastMember>(
        chunk->id().value(), castLibId_.value(), memberNumber, chunk);
    members_[memberNumber] = member;
    return member;
}

std::shared_ptr<libreshockwave::cast::CastMember> CastLib::getMemberByName(const std::string& memberName) {
    if (!isLoaded()) {
        load();
    }
    if (auto direct = findMemberByNameExact(memberName)) {
        return direct;
    }
    if (const auto prefixed = sourcePrefixedLookupName(memberName); prefixed.has_value()) {
        return findMemberByNameExact(*prefixed);
    }
    return nullptr;
}

std::shared_ptr<libreshockwave::cast::CastMember> CastLib::createDynamicMember(const std::string& memberType) {
    if (!isLoaded()) {
        load();
    }
    const auto type = dynamicMemberTypeFor(memberType);
    for (int memberNumber = 10000; memberNumber < nextDynamicMember_; ++memberNumber) {
        if (memberChunks_.contains(memberNumber)) {
            continue;
        }
        if (const auto found = members_.find(memberNumber);
            found != members_.end() && found->second && found->second->isReusableDynamicSlot()) {
            found->second->reuseAs(type);
            return found->second;
        }
    }
    const int memberNumber = nextAvailableDynamicMemberNumber();
    auto member = std::make_shared<libreshockwave::cast::CastMember>(
        castLibId_.value(),
        memberNumber,
        type);
    members_[memberNumber] = member;
    return member;
}

std::shared_ptr<libreshockwave::cast::CastMember> CastLib::createDynamicMember(
    const std::string& memberName,
    const std::string& memberType) {
    auto member = createDynamicMember(memberType);
    if (member) {
        member->setName(memberName);
    }
    return member;
}

bool CastLib::hasMemberNamedExact(const std::string& memberName) {
    return findMemberChunkByNameExact(memberName) != nullptr || findMemberByNameExact(memberName) != nullptr;
}

int CastLib::getMemberNumber(const std::shared_ptr<chunks::CastMemberChunk>& member) {
    if (!isLoaded()) {
        load();
    }
    for (const auto& [number, existing] : memberChunks_) {
        if (sameAuthoredMember(existing, member)) {
            return number;
        }
    }
    return -1;
}

std::shared_ptr<chunks::ScriptChunk> CastLib::getScript(int memberNumber) {
    if (!isLoaded()) {
        load();
    }
    if (const auto found = scripts_.find(memberNumber); found != scripts_.end()) {
        return found->second;
    }
    return nullptr;
}

const std::vector<std::shared_ptr<chunks::ScriptChunk>>& CastLib::allScripts() {
    if (!isLoaded()) {
        load();
    }
    cachedScripts_.clear();
    if (sourceFile_) {
        cachedScripts_ = sourceFile_->scripts();
    } else {
        for (const auto& [_, script] : scripts_) {
            cachedScripts_.push_back(script);
        }
    }
    return cachedScripts_;
}

std::shared_ptr<chunks::ScriptNamesChunk> CastLib::scriptNames() const {
    return sourceFile_ ? sourceFile_->scriptNames() : nullptr;
}

lingo::Datum CastLib::getProp(const std::string& propName) {
    const auto prop = lower(propName);
    if (prop == "number") return lingo::Datum::of(castLibId_.value());
    if (prop == "name") return stringDatum(name_);
    if (prop == "filename") return stringDatum(fileName_);
    if (prop == "preloadmode") return lingo::Datum::of(preloadMode_);
    if (prop == "selection") return selection_;
    if (prop == "loaded") return isLoaded() ? lingo::Datum::TRUE : lingo::Datum::FALSE;
    if (prop.find("member") != std::string::npos) return lingo::Datum::of(memberCount());
    return lingo::Datum::voidValue();
}

bool CastLib::setProp(const std::string& propName, const lingo::Datum& value) {
    const auto prop = lower(propName);
    if (prop == "name") {
        setName(value.stringValue());
        return true;
    }
    if (prop == "filename") {
        setFileName(value.stringValue());
        return true;
    }
    if (prop == "preloadmode") {
        preloadMode_ = value.intValue();
        return true;
    }
    if (prop == "selection") {
        selection_ = value.isList() ? value : lingo::Datum::list();
        return true;
    }
    return false;
}

lingo::Datum CastLib::getMemberProp(int memberNumber, const std::string& propName) {
    auto member = getMember(memberNumber);
    if (!member) {
        return getInvalidMemberProp(propName);
    }

    const auto prop = lower(propName);
    if (prop == "name") return stringDatum(member->name());
    if (prop == "number" || prop == "membernum") return lingo::Datum::of(memberNumber);
    if (prop == "type") {
        if (member->memberType() == libreshockwave::cast::MemberType::Null) {
            return lingo::Datum::symbol("empty");
        }
        return lingo::Datum::symbol(std::string(libreshockwave::cast::name(member->memberType())));
    }
    if (prop == "castlibnum" || prop == "castlib") return lingo::Datum::of(castLibId_.value());
    if (prop == "media") return lingo::Datum::castMemberRef(castLibId_, id::MemberId(memberNumber));
    if (member->isText() && prop == "text") return stringDatum(resolveMemberText(member));
    if (prop == "width") return lingo::Datum::of(member->width());
    if (prop == "height") return lingo::Datum::of(member->height());
    if (prop == "depth") {
        auto bitmap = member->runtimeBitmap();
        if (bitmap) {
            return lingo::Datum::of(bitmap->bitDepth());
        }
        return lingo::Datum::of(member->bitmapInfo().has_value() ? member->bitmapInfo()->bitDepth : 0);
    }
    if (prop == "regpoint") return lingo::Datum::intPoint(member->regX(), member->regY());
    if (prop == "rect") return lingo::Datum::intRect(0, 0, member->width(), member->height());
    if (prop == "image") {
        auto bitmap = member->runtimeBitmap();
        return bitmap ? lingo::Datum::imageRef(std::move(bitmap)) : lingo::Datum::voidValue();
    }
    return lingo::Datum::voidValue();
}

bool CastLib::setMemberProp(int memberNumber, const std::string& propName, const lingo::Datum& value) {
    auto member = getMember(memberNumber);
    if (!member) {
        return false;
    }

    const auto prop = lower(propName);
    if (prop == "name" && member->isRuntimeDynamic()) {
        member->setName(value.stringValue());
        return true;
    }
    if (member->isText() && prop == "text") {
        member->setDynamicText(value.stringValue());
        return true;
    }
    if (member->isText() && prop == "html") {
        member->setDynamicText(stripHtmlTags(value.stringValue()));
        return true;
    }
    if (member->isText() && prop == "media" && (value.isString() || value.isSymbol())) {
        member->setDynamicText(value.stringValue());
        return true;
    }
    if (prop == "image") {
        const auto* image = value.asImageRef();
        if (image == nullptr || image->bitmap == nullptr) {
            return false;
        }
        member->setRuntimeBitmap(*image->bitmap);
        return true;
    }
    return false;
}

bool CastLib::setExternalData(const std::vector<std::uint8_t>& data) {
    if (data.empty()) {
        return false;
    }

    try {
        fetchedExternalData_ = data;
        auto file = DirectorFile::load(data);
        if (!file) {
            return false;
        }

        sourceFile_ = std::move(file);
        const auto nameBeforeLoad = name_;
        if (auto externalCastList = sourceFile_->castList();
            externalCastList && !externalCastList->entries().empty()) {
            const auto& internalName = externalCastList->entries().front().name;
            if (!internalName.empty() && !nameExplicitlyAssigned_) {
                name_ = internalName;
                nameLoadedFromExternalFile_ = !usesAuthoredExternalBinding();
            }
        }
        if (name_.empty()) {
            name_ = nameBeforeLoad;
            nameLoadedFromExternalFile_ = false;
        }

        state_ = State::None;
        memberChunks_.clear();
        members_.clear();
        scripts_.clear();
        cachedScripts_.clear();
        totalSlotCount_ = 0;
        load();
        return true;
    } catch (const std::exception&) {
        return false;
    }
}

void CastLib::cacheFetchedExternalData(const std::vector<std::uint8_t>& data) {
    if (data.empty()) {
        return;
    }
    fetchedExternalData_ = data;
    const auto dataString = std::string(data.begin(), data.end());
    if (dataString.find("font") != std::string::npos || dataString.find("FONT") != std::string::npos) {
        try {
            registerFontAliases(DirectorFile::load(data));
        } catch (const std::exception&) {
        }
    }
}

lingo::Datum CastLib::getInvalidMemberProp(const std::string& propName) {
    const auto prop = lower(propName);
    if (prop == "name") return stringDatum("");
    if (prop == "number" || prop == "membernum") return lingo::Datum::of(0);
    if (prop == "type") return lingo::Datum::symbol("empty");
    return lingo::Datum::voidValue();
}

void CastLib::registerFontAliases(const std::shared_ptr<DirectorFile>& file) {
    if (!file) {
        return;
    }
    for (const auto& member : file->castMembers()) {
        if (!member) {
            continue;
        }
        if (const auto aliasInfo = parseFontAlias(member->specificData(), member->name()); aliasInfo.has_value()) {
            FontRegistry::registerFontAlias(aliasInfo->alias, aliasInfo->fontName, aliasInfo->bold);
        }
    }
}

int CastLib::minMember() const {
    if (!sourceFile_) {
        return 1;
    }
    if (auto castList = sourceFile_->castList();
        castList && castLibId_.value() - 1 < static_cast<int>(castList->entries().size())) {
        const int value = castList->entries()[static_cast<std::size_t>(castLibId_.value() - 1)].minMember;
        return value > 0 ? value : 1;
    }
    if (auto config = sourceFile_->config()) {
        const int value = config->minMember();
        return value > 0 ? value : 1;
    }
    return 1;
}

void CastLib::loadMembersFromCast(const std::shared_ptr<chunks::CastChunk>& cast, int minMemberValue) {
    if (!cast || !sourceFile_) {
        return;
    }

    totalSlotCount_ = static_cast<int>(cast->memberIds().size()) + minMemberValue - 1;
    for (int index = 0; index < static_cast<int>(cast->memberIds().size()); ++index) {
        const int chunkId = cast->memberIds()[static_cast<std::size_t>(index)];
        if (chunkId <= 0) {
            continue;
        }

        const int memberNumber = index + minMemberValue;
        for (const auto& member : sourceFile_->castMembers()) {
            if (!member || member->id().value() != chunkId) {
                continue;
            }
            memberChunks_[memberNumber] = member;
            if (member->isScript() && member->scriptId() > 0) {
                if (auto script = sourceFile_->getScriptForCastMember(member, cast)) {
                    scripts_[memberNumber] = script;
                }
            }
            break;
        }
    }
}

void CastLib::loadFromExternalFile() {
    if (!sourceFile_ || sourceFile_->casts().empty()) {
        return;
    }

    int minMemberValue = 1;
    if (auto externalCastList = sourceFile_->castList();
        externalCastList && !externalCastList->entries().empty()) {
        minMemberValue = externalCastList->entries().front().minMember;
    } else if (auto config = sourceFile_->config()) {
        minMemberValue = config->minMember();
    }
    if (minMemberValue <= 0) {
        minMemberValue = 1;
    }

    loadMembersFromCast(sourceFile_->casts().front(), minMemberValue);
}

void CastLib::scanXmedFonts() {
    if (!sourceFile_) {
        return;
    }

    registerFontAliases(sourceFile_);
    const auto keyTable = sourceFile_->keyTable();
    if (!keyTable) {
        return;
    }

    const auto xmedFourCC = io::BinaryReader::fourCC("XMED");
    for (const auto& member : sourceFile_->castMembers()) {
        if (!member) {
            continue;
        }
        auto entry = keyTable->findEntry(member->id(), xmedFourCC);
        if (!entry.has_value()) {
            continue;
        }
        auto raw = std::dynamic_pointer_cast<chunks::RawChunk>(sourceFile_->getChunk(entry->sectionId));
        if (!raw || raw->data().size() < 4) {
            continue;
        }
        const auto& data = raw->data();
        if (data[0] == static_cast<std::uint8_t>('P') &&
            data[1] == static_cast<std::uint8_t>('F') &&
            data[2] == static_cast<std::uint8_t>('R') &&
            data[3] == static_cast<std::uint8_t>('1') &&
            !member->name().empty()) {
            FontRegistry::registerPfr1Font(member->name(), data);
        }
    }
}

void CastLib::invalidateFileBackedBinding() {
    sourceFile_.reset();
    fetchedExternalData_.clear();
    state_ = State::None;
    totalSlotCount_ = 0;
    nameLoadedFromExternalFile_ = false;
    memberChunks_.clear();
    members_.clear();
    scripts_.clear();
    cachedScripts_.clear();
}

bool CastLib::sameFileBinding(const std::string& currentFileName, const std::string& newFileName) const {
    return currentFileName == newFileName || (currentFileName.empty() && newFileName.empty());
}

bool CastLib::usesGeneratedPlaceholderName(const std::string& candidateName) const {
    if (candidateName.empty()) {
        return true;
    }
    const auto authoredBaseName = util::getFileNameWithoutExtension(util::getFileName(authoredFileName_));
    if (authoredBaseName.empty()) {
        return false;
    }

    const auto normalizedName = lower(trim(candidateName));
    const auto normalizedBase = lower(trim(authoredBaseName));
    if (normalizedName == normalizedBase) {
        return true;
    }
    const auto prefix = normalizedBase + " ";
    if (!startsWithIgnoreCase(normalizedName, prefix)) {
        return false;
    }
    const auto suffix = normalizedName.substr(prefix.size());
    return !suffix.empty() && std::all_of(suffix.begin(), suffix.end(), [](unsigned char ch) {
        return std::isdigit(ch) != 0;
    });
}

bool CastLib::looksLikeDirectFileBindingName(const std::string& candidateName) const {
    const auto normalizedName = lower(trim(candidateName));
    if (normalizedName.empty()) {
        return true;
    }
    if (normalizedName.find("://") != std::string::npos ||
        normalizedName.find('/') != std::string::npos ||
        normalizedName.find('\\') != std::string::npos ||
        normalizedName.find('?') != std::string::npos ||
        normalizedName.find('#') != std::string::npos) {
        return true;
    }
    if (endsWith(normalizedName, ".cct") ||
        endsWith(normalizedName, ".cst") ||
        endsWith(normalizedName, ".dcr") ||
        endsWith(normalizedName, ".dir")) {
        return true;
    }
    return !fileName_.empty() && normalizedName == lower(trim(fileName_));
}

std::string CastLib::resolveMemberText(const std::shared_ptr<libreshockwave::cast::CastMember>& member) {
    if (!member || !member->isText()) {
        return "";
    }
    if (member->hasDynamicText()) {
        return member->textContent();
    }
    if (sourceFile_ && member->rawChunk()) {
        if (auto text = sourceFile_->getTextForMember(member->rawChunk())) {
            return normalizeTextLineEndings(text->text());
        }
    }
    return "";
}

int CastLib::nextAvailableDynamicMemberNumber() {
    while (memberChunks_.contains(nextDynamicMember_) || members_.contains(nextDynamicMember_)) {
        ++nextDynamicMember_;
    }
    return nextDynamicMember_++;
}

std::shared_ptr<chunks::CastMemberChunk> CastLib::findMemberChunkByNameExact(const std::string& memberName) {
    if (memberName.empty()) {
        return nullptr;
    }
    for (const auto& [_, member] : memberChunks_) {
        if (member && equalsIgnoreCase(member->name(), memberName)) {
            return member;
        }
    }
    return nullptr;
}

std::shared_ptr<libreshockwave::cast::CastMember> CastLib::findMemberByNameExact(const std::string& memberName) {
    if (memberName.empty()) {
        return nullptr;
    }
    for (const auto& [number, member] : memberChunks_) {
        if (member && equalsIgnoreCase(member->name(), memberName)) {
            return getMember(number);
        }
    }
    for (const auto& [_, member] : members_) {
        if (member && equalsIgnoreCase(member->name(), memberName)) {
            return member;
        }
    }
    return nullptr;
}

std::optional<std::string> CastLib::sourcePrefixedLookupName(const std::string& requestedName) {
    if (requestedName.empty() || startsWithIgnoreCase(requestedName, "s_")) {
        return std::nullopt;
    }
    return "s_" + requestedName;
}

libreshockwave::cast::MemberType CastLib::dynamicMemberTypeFor(const std::string& typeName) {
    const auto type = lower(typeName);
    if (type == "field" || type == "text") return libreshockwave::cast::MemberType::Text;
    if (type == "bitmap") return libreshockwave::cast::MemberType::Bitmap;
    if (type == "palette") return libreshockwave::cast::MemberType::Palette;
    if (type == "script") return libreshockwave::cast::MemberType::Script;
    if (type == "button") return libreshockwave::cast::MemberType::Button;
    if (type == "shape") return libreshockwave::cast::MemberType::Shape;
    if (type == "sound") return libreshockwave::cast::MemberType::Sound;
    return libreshockwave::cast::MemberType::Text;
}

bool CastLib::sameAuthoredMember(const std::shared_ptr<chunks::CastMemberChunk>& left,
                                 const std::shared_ptr<chunks::CastMemberChunk>& right) {
    if (left == right) {
        return true;
    }
    return left && right && left->file() == right->file() && left->id().value() == right->id().value();
}

std::optional<CastLib::FontAliasInfo> CastLib::parseFontAlias(const std::vector<std::uint8_t>& data,
                                                              const std::string& memberName) {
    if (data.size() < 12) {
        return std::nullopt;
    }

    const auto strings = extractPrintableNullTerminatedStrings(data);
    if (strings.size() < 2 || !equalsIgnoreCase(strings.front(), "font")) {
        return std::nullopt;
    }

    std::string fontName;
    for (auto it = strings.rbegin(); it != strings.rend(); ++it) {
        if (!equalsIgnoreCase(*it, "font")) {
            fontName = *it;
            break;
        }
    }
    if (trim(fontName).empty()) {
        return std::nullopt;
    }

    std::string alias = memberName;
    if (trim(alias).empty() && strings.size() >= 3) {
        alias = strings[strings.size() - 2];
    }
    if (trim(alias).empty() || equalsIgnoreCase(alias, "fontName")) {
        return std::nullopt;
    }

    const bool bold = data.size() > 23 && (data[23] & 0x01U) != 0U;
    return FontAliasInfo{alias, fontName, bold};
}

std::vector<std::string> CastLib::extractPrintableNullTerminatedStrings(const std::vector<std::uint8_t>& data) {
    std::vector<std::string> strings;
    int start = -1;
    for (int index = 0; index <= static_cast<int>(data.size()); ++index) {
        const int value = index < static_cast<int>(data.size()) ? static_cast<int>(data[static_cast<std::size_t>(index)]) : 0;
        const bool printable = value >= 0x20 && value <= 0x7E;
        if (printable) {
            if (start < 0) {
                start = index;
            }
        } else if (start >= 0) {
            if (value == 0 && index > start) {
                strings.emplace_back(data.begin() + start, data.begin() + index);
            }
            start = -1;
        }
    }
    return strings;
}

} // namespace libreshockwave::player::cast
