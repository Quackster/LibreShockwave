#pragma once

#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "libreshockwave/lingo/Datum.hpp"
#include "libreshockwave/lingo/xtra/XtraManager.hpp"

namespace libreshockwave::lingo::xtra {

class MultiuserNetBridge {
public:
    struct NetMessage {
        int errorCode = 0;
        std::string senderID;
        std::string subject;
        Datum content = Datum::voidValue();
    };

    virtual ~MultiuserNetBridge() = default;

    virtual void requestConnect(int instanceId, const std::string& host, int port, int mode) = 0;
    virtual void requestSend(int instanceId,
                             const std::string& senderID,
                             const std::string& subject,
                             const Datum& content) = 0;
    virtual void requestDisconnect(int instanceId) = 0;
    [[nodiscard]] virtual bool isConnected(int instanceId) const = 0;
    [[nodiscard]] virtual std::vector<NetMessage> pollMessages(int instanceId) = 0;
    virtual void destroyInstance(int instanceId) = 0;
};

class MultiuserXtra : public Xtra {
public:
    using ScriptCallback = std::function<void(const Datum& target,
                                              const std::string& handlerName,
                                              const std::vector<Datum>& args)>;

    MultiuserXtra(MultiuserNetBridge* bridge, ScriptCallback callback);

    [[nodiscard]] std::string name() const override;
    [[nodiscard]] int createInstance(const std::vector<Datum>& args) override;
    void destroyInstance(int instanceId) override;
    [[nodiscard]] Datum callHandler(int instanceId,
                                    std::string_view handlerName,
                                    const std::vector<Datum>& args) override;
    [[nodiscard]] Datum getProperty(int instanceId, std::string_view propertyName) const override;
    void setProperty(int instanceId, std::string_view propertyName, const Datum& value) override;
    void tick() override;

private:
    struct InstanceState {
        std::string host;
        int port = 0;
        int bufferMin = 0;
        int bufferMax = 0;
        int bufferUrgency = 0;
        std::string callbackHandler;
        std::optional<Datum> callbackTarget;
        std::optional<MultiuserNetBridge::NetMessage> currentMessage;
        std::vector<MultiuserNetBridge::NetMessage> messageQueue;
    };

    [[nodiscard]] Datum setNetBufferLimits(InstanceState& state, const std::vector<Datum>& args);
    [[nodiscard]] Datum setNetMessageHandler(InstanceState& state, const std::vector<Datum>& args);
    [[nodiscard]] Datum connectToNetServer(int instanceId, InstanceState& state, const std::vector<Datum>& args);
    [[nodiscard]] Datum sendNetMessage(int instanceId, InstanceState& state, const std::vector<Datum>& args);
    [[nodiscard]] Datum getNetMessage(const InstanceState& state) const;
    [[nodiscard]] Datum checkNetMessages(int instanceId, InstanceState& state, const std::vector<Datum>& args);
    [[nodiscard]] Datum getNumberWaitingNetMessages(int instanceId, InstanceState& state);
    [[nodiscard]] static Datum getNetErrorString(const std::vector<Datum>& args);
    void pollIntoQueue(int instanceId, InstanceState& state);
    void invokeCallback(const InstanceState& state) const;

    MultiuserNetBridge* bridge_{nullptr};
    ScriptCallback scriptCallback_;
    std::unordered_map<int, InstanceState> instances_;
    int nextInstanceId_ = 1;
};

} // namespace libreshockwave::lingo::xtra
