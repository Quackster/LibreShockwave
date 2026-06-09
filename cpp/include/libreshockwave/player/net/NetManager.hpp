#pragma once

#include <cstdint>
#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/player/net/NetTask.hpp"

namespace libreshockwave::player::net {

class NetManager {
public:
    struct LoadResult {
        std::optional<std::vector<std::uint8_t>> data;
        int errorCode{0};
        std::string errorMessage;

        [[nodiscard]] static LoadResult success(std::vector<std::uint8_t> data);
        [[nodiscard]] static LoadResult failure(int errorCode, std::string errorMessage);
    };

    using FetchHandler = std::function<LoadResult(const NetTask&)>;
    using CompletionCallback = std::function<void(const std::string&, const std::vector<std::uint8_t>&)>;

    void setBasePath(std::string basePath);
    [[nodiscard]] const std::string& basePath() const;
    [[nodiscard]] const std::string& getBasePath() const;

    void setLocalHttpRoot(std::string root);
    [[nodiscard]] const std::string& localHttpRoot() const;

    void setFetchHandler(FetchHandler handler);
    void setCompletionCallback(CompletionCallback callback);

    [[nodiscard]] int preloadNetThing(std::string url);
    [[nodiscard]] int postNetText(std::string url, std::string postData);

    [[nodiscard]] bool netDone(std::optional<int> taskId = std::nullopt) const;
    [[nodiscard]] std::string netTextResult(std::optional<int> taskId = std::nullopt) const;
    [[nodiscard]] int netError(std::optional<int> taskId = std::nullopt) const;
    [[nodiscard]] std::string_view getStreamStatus(std::optional<int> taskId = std::nullopt) const;
    [[nodiscard]] lingo::Datum getStreamStatusDatum(std::optional<int> taskId = std::nullopt) const;
    [[nodiscard]] lingo::Datum getStreamStatusDatum(std::string_view url) const;
    [[nodiscard]] std::optional<std::vector<std::uint8_t>> getNetBytes(std::optional<int> taskId = std::nullopt) const;

    [[nodiscard]] NetTask* getTask(std::optional<int> taskId = std::nullopt);
    [[nodiscard]] const NetTask* getTask(std::optional<int> taskId = std::nullopt) const;
    [[nodiscard]] const std::unordered_map<int, NetTask>& tasks() const;

    void cacheData(std::string url, std::vector<std::uint8_t> data);
    [[nodiscard]] std::optional<std::vector<std::uint8_t>> getCachedData(std::string_view url) const;
    [[nodiscard]] const std::unordered_map<std::string, std::vector<std::uint8_t>>& urlCache() const;
    void clear();
    void shutdown();

    [[nodiscard]] static std::string resolveUrl(std::string_view url);
    [[nodiscard]] static std::string cacheKeyForUrl(std::string_view url);
    [[nodiscard]] static std::string extractUrlPath(std::string_view url);
    [[nodiscard]] static std::string extractOrigin(std::string_view url);

private:
    [[nodiscard]] NetTask& createGetTask(std::string url);
    [[nodiscard]] NetTask& createPostTask(std::string url, std::string postData);
    void executeTask(NetTask& task, bool useCache);
    void completeTask(NetTask& task, std::vector<std::uint8_t> data, bool cacheResult);
    void notifyCompletion(const std::string& url, const std::vector<std::uint8_t>& data) const;

    std::unordered_map<int, NetTask> tasks_;
    std::unordered_map<std::string, std::vector<std::uint8_t>> urlCache_;
    int nextTaskId_{1};
    int lastTaskId_{0};
    std::string basePath_;
    std::string localHttpRoot_;
    FetchHandler fetchHandler_;
    CompletionCallback completionCallback_;
};

} // namespace libreshockwave::player::net
