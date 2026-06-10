#include "libreshockwave/lingo/xtra/MultiuserXtra.hpp"

#include <algorithm>
#include <cctype>
#include <string>
#include <utility>

namespace libreshockwave::lingo::xtra {
namespace {

std::string lowerAscii(std::string_view value) {
    std::string result(value);
    std::transform(result.begin(), result.end(), result.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return result;
}

Datum messagePropList(const MultiuserNetBridge::NetMessage& message) {
    auto result = Datum::propList();
    auto& props = result.propListValue();
    props.put(Datum::symbol("errorCode"), Datum::of(message.errorCode));
    props.put(Datum::symbol("senderID"), Datum::of(message.senderID));
    props.put(Datum::symbol("subject"), Datum::of(message.subject));
    props.put(Datum::symbol("content"), message.content);
    return result;
}

} // namespace

MultiuserXtra::MultiuserXtra(MultiuserNetBridge* bridge, ScriptCallback callback)
    : bridge_(bridge), scriptCallback_(std::move(callback)) {}

std::string MultiuserXtra::name() const {
    return "Multiuser";
}

int MultiuserXtra::createInstance(const std::vector<Datum>&) {
    const int id = nextInstanceId_++;
    instances_.emplace(id, InstanceState{});
    return id;
}

void MultiuserXtra::destroyInstance(int instanceId) {
    const auto erased = instances_.erase(instanceId);
    if (erased > 0 && bridge_ != nullptr) {
        bridge_->requestDisconnect(instanceId);
        bridge_->destroyInstance(instanceId);
    }
}

Datum MultiuserXtra::callHandler(int instanceId,
                                 std::string_view handlerName,
                                 const std::vector<Datum>& args) {
    const auto found = instances_.find(instanceId);
    if (found == instances_.end()) {
        return Datum::voidValue();
    }

    auto& state = found->second;
    const std::string method = lowerAscii(handlerName);
    if (method == "setnetbufferlimits") {
        return setNetBufferLimits(state, args);
    }
    if (method == "setnetmessagehandler") {
        return setNetMessageHandler(state, args);
    }
    if (method == "connecttonetserver") {
        return connectToNetServer(instanceId, state, args);
    }
    if (method == "sendnetmessage") {
        return sendNetMessage(instanceId, state, args);
    }
    if (method == "getnetmessage") {
        return getNetMessage(state);
    }
    if (method == "checknetmessages") {
        return checkNetMessages(instanceId, state, args);
    }
    if (method == "getnumberwaitingnetmessages") {
        return getNumberWaitingNetMessages(instanceId, state);
    }
    if (method == "getneterrorstring") {
        return getNetErrorString(args);
    }
    return Datum::voidValue();
}

Datum MultiuserXtra::getProperty(int, std::string_view) const {
    return Datum::voidValue();
}

void MultiuserXtra::setProperty(int, std::string_view, const Datum&) {}

void MultiuserXtra::tick() {
    for (auto& entry : instances_) {
        auto& state = entry.second;
        if (state.callbackHandler.empty() || !state.callbackTarget.has_value()) {
            continue;
        }

        pollIntoQueue(entry.first, state);
        while (!state.messageQueue.empty()) {
            state.currentMessage = state.messageQueue.front();
            state.messageQueue.erase(state.messageQueue.begin());
            invokeCallback(state);
        }
        state.currentMessage.reset();
    }
}

Datum MultiuserXtra::setNetBufferLimits(InstanceState& state, const std::vector<Datum>& args) {
    if (args.size() >= 3) {
        state.bufferMin = args[0].intValue();
        state.bufferMax = args[1].intValue();
        state.bufferUrgency = args[2].intValue();
    }
    return Datum::of(0);
}

Datum MultiuserXtra::setNetMessageHandler(InstanceState& state, const std::vector<Datum>& args) {
    if (args.size() >= 2) {
        if (args[0].isVoid() || args[1].isVoid()) {
            state.callbackHandler.clear();
            state.callbackTarget.reset();
        } else {
            if (const auto* symbol = args[0].asSymbol()) {
                state.callbackHandler = symbol->name;
            } else {
                state.callbackHandler = args[0].stringValue();
            }
            state.callbackTarget = args[1];
        }
    }
    return Datum::of(0);
}

Datum MultiuserXtra::connectToNetServer(int instanceId, InstanceState& state, const std::vector<Datum>& args) {
    if (args.size() >= 4) {
        state.host = args[2].stringValue();
        state.port = args[3].intValue();
        const int mode = args.size() >= 6 ? args[5].intValue() : 1;
        if (bridge_ != nullptr) {
            bridge_->requestConnect(instanceId, state.host, state.port, mode);
        }
    }
    return Datum::of(0);
}

Datum MultiuserXtra::sendNetMessage(int instanceId, InstanceState&, const std::vector<Datum>& args) {
    if (args.size() >= 3 && bridge_ != nullptr) {
        bridge_->requestSend(instanceId, args[0].stringValue(), args[1].stringValue(), args[2]);
    }
    return Datum::of(0);
}

Datum MultiuserXtra::getNetMessage(const InstanceState& state) const {
    if (!state.currentMessage.has_value()) {
        return Datum::voidValue();
    }
    return messagePropList(*state.currentMessage);
}

Datum MultiuserXtra::checkNetMessages(int instanceId, InstanceState& state, const std::vector<Datum>& args) {
    const int count = args.empty() ? 1 : args[0].intValue();
    pollIntoQueue(instanceId, state);

    int processed = 0;
    for (int i = 0; i < count && !state.messageQueue.empty(); ++i) {
        state.currentMessage = state.messageQueue.front();
        state.messageQueue.erase(state.messageQueue.begin());
        ++processed;
        if (!state.callbackHandler.empty() && state.callbackTarget.has_value()) {
            invokeCallback(state);
        }
    }

    state.currentMessage.reset();
    return Datum::of(processed);
}

Datum MultiuserXtra::getNumberWaitingNetMessages(int instanceId, InstanceState& state) {
    pollIntoQueue(instanceId, state);
    return Datum::of(static_cast<int>(state.messageQueue.size()));
}

Datum MultiuserXtra::getNetErrorString(const std::vector<Datum>& args) {
    if (args.empty()) {
        return Datum::of(std::string());
    }
    switch (args[0].intValue()) {
        case 0: return Datum::of(std::string("No error"));
        case -1: return Datum::of(std::string("Memory allocation error"));
        case -2: return Datum::of(std::string("Network error"));
        case -3: return Datum::of(std::string("Connection refused"));
        case -4: return Datum::of(std::string("Connection timed out"));
        case -5: return Datum::of(std::string("Invalid message"));
        case -6: return Datum::of(std::string("Invalid server address"));
        default: return Datum::of("Unknown error (" + std::to_string(args[0].intValue()) + ")");
    }
}

void MultiuserXtra::pollIntoQueue(int instanceId, InstanceState& state) {
    if (bridge_ == nullptr) {
        return;
    }
    auto messages = bridge_->pollMessages(instanceId);
    state.messageQueue.insert(state.messageQueue.end(), messages.begin(), messages.end());
}

void MultiuserXtra::invokeCallback(const InstanceState& state) const {
    if (!scriptCallback_ || !state.callbackTarget.has_value() || state.callbackHandler.empty()) {
        return;
    }
    try {
        scriptCallback_(*state.callbackTarget, state.callbackHandler, {});
    } catch (...) {
        // Match the Java Xtra path: callback failures are isolated from Xtra polling.
    }
}

} // namespace libreshockwave::lingo::xtra
