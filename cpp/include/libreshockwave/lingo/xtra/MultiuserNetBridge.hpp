#pragma once

#include <string>
#include <vector>

#include "libreshockwave/lingo/Datum.hpp"

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

} // namespace libreshockwave::lingo::xtra
