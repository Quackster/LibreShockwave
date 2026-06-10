#include "libreshockwave/player/xtra/SocketMultiuserBridge.hpp"

#include <array>
#include <cerrno>
#include <cstring>
#include <mutex>
#include <thread>
#include <utility>

#include <netdb.h>
#include <sys/socket.h>
#include <unistd.h>

namespace libreshockwave::player::xtra {
namespace {

constexpr std::size_t READ_BUFFER_SIZE = 8192;

void closeSocket(int& socketFd) {
    if (socketFd >= 0) {
        ::close(socketFd);
        socketFd = -1;
    }
}

int connectSocket(const std::string& host, int port) {
    if (host.empty() || port <= 0) {
        return -1;
    }

    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    addrinfo* rawResult = nullptr;
    const std::string service = std::to_string(port);
    if (::getaddrinfo(host.c_str(), service.c_str(), &hints, &rawResult) != 0) {
        return -1;
    }

    std::unique_ptr<addrinfo, decltype(&::freeaddrinfo)> results(rawResult, ::freeaddrinfo);
    for (addrinfo* entry = results.get(); entry != nullptr; entry = entry->ai_next) {
        int socketFd = ::socket(entry->ai_family, entry->ai_socktype, entry->ai_protocol);
        if (socketFd < 0) {
            continue;
        }

        if (::connect(socketFd, entry->ai_addr, entry->ai_addrlen) == 0) {
            return socketFd;
        }
        ::close(socketFd);
    }

    return -1;
}

std::string serializeWireContent(const std::string& subject, const lingo::Datum& content) {
    const std::string body = content.stringValue();
    if (subject.empty() || subject == "0") {
        return body;
    }
    if (body.empty()) {
        return subject;
    }
    return subject + " " + body;
}

int sendFlags() {
#ifdef MSG_NOSIGNAL
    return MSG_NOSIGNAL;
#else
    return 0;
#endif
}

int recvFlags() {
#ifdef MSG_DONTWAIT
    return MSG_DONTWAIT;
#else
    return 0;
#endif
}

} // namespace

struct SocketMultiuserBridge::Connection {
    mutable std::mutex mutex;
    int socketFd = -1;
    bool connected = false;
    bool connecting = false;
    bool closeRequested = false;
    int mode = 0;
    std::array<char, READ_BUFFER_SIZE> readBuf{};

    ~Connection() {
        closeSocket(socketFd);
    }
};

SocketMultiuserBridge::~SocketMultiuserBridge() {
    closeAll();
}

void SocketMultiuserBridge::requestConnect(int instanceId, const std::string& host, int port, int mode) {
    auto connection = std::make_shared<Connection>();
    {
        std::lock_guard lock(connection->mutex);
        connection->connecting = true;
        connection->mode = mode;
    }

    {
        std::lock_guard lock(connectionsMutex_);
        connections_[instanceId] = connection;
    }

    std::thread([connection, host, port] {
        int socketFd = connectSocket(host, port);
        std::lock_guard lock(connection->mutex);
        connection->connecting = false;
        if (connection->closeRequested || socketFd < 0) {
            closeSocket(socketFd);
            return;
        }
        connection->socketFd = socketFd;
        connection->connected = true;
    }).detach();
}

void SocketMultiuserBridge::requestSend(int instanceId,
                                        const std::string&,
                                        const std::string& subject,
                                        const lingo::Datum& content) {
    auto connection = connectionFor(instanceId);
    if (connection == nullptr) {
        return;
    }

    const std::string wire = serializeWireContent(subject, content);
    std::lock_guard lock(connection->mutex);
    if (!connection->connected || connection->socketFd < 0 || wire.empty()) {
        return;
    }

    const auto* bytes = wire.data();
    std::size_t remaining = wire.size();
    while (remaining > 0) {
        const ssize_t sent = ::send(connection->socketFd, bytes, remaining, sendFlags());
        if (sent <= 0) {
            closeSocket(connection->socketFd);
            connection->connected = false;
            return;
        }
        bytes += sent;
        remaining -= static_cast<std::size_t>(sent);
    }
}

void SocketMultiuserBridge::requestDisconnect(int instanceId) {
    std::shared_ptr<Connection> connection;
    {
        std::lock_guard lock(connectionsMutex_);
        const auto found = connections_.find(instanceId);
        if (found == connections_.end()) {
            return;
        }
        connection = std::move(found->second);
        connections_.erase(found);
    }

    std::lock_guard lock(connection->mutex);
    connection->closeRequested = true;
    connection->connecting = false;
    connection->connected = false;
    closeSocket(connection->socketFd);
}

bool SocketMultiuserBridge::isConnected(int instanceId) const {
    auto connection = connectionFor(instanceId);
    if (connection == nullptr) {
        return false;
    }
    std::lock_guard lock(connection->mutex);
    return connection->connected;
}

std::vector<SocketMultiuserBridge::NetMessage> SocketMultiuserBridge::pollMessages(int instanceId) {
    auto connection = connectionFor(instanceId);
    if (connection == nullptr) {
        return {};
    }

    std::lock_guard lock(connection->mutex);
    if (!connection->connected || connection->socketFd < 0) {
        return {};
    }

    const ssize_t read = ::recv(connection->socketFd,
                               connection->readBuf.data(),
                               connection->readBuf.size(),
                               recvFlags());
    if (read > 0) {
        return {NetMessage{0,
                           "",
                           "",
                           lingo::Datum::of(std::string(connection->readBuf.data(),
                                                        static_cast<std::size_t>(read)))}};
    }
    if (read == 0) {
        closeSocket(connection->socketFd);
        connection->connected = false;
        return {};
    }
    if (errno == EAGAIN || errno == EWOULDBLOCK) {
        return {};
    }

    closeSocket(connection->socketFd);
    connection->connected = false;
    return {};
}

void SocketMultiuserBridge::destroyInstance(int instanceId) {
    requestDisconnect(instanceId);
}

std::shared_ptr<SocketMultiuserBridge::Connection> SocketMultiuserBridge::connectionFor(int instanceId) const {
    std::lock_guard lock(connectionsMutex_);
    const auto found = connections_.find(instanceId);
    return found != connections_.end() ? found->second : nullptr;
}

void SocketMultiuserBridge::closeAll() {
    std::vector<std::shared_ptr<Connection>> connections;
    {
        std::lock_guard lock(connectionsMutex_);
        connections.reserve(connections_.size());
        for (auto& entry : connections_) {
            connections.push_back(std::move(entry.second));
        }
        connections_.clear();
    }

    for (const auto& connection : connections) {
        std::lock_guard lock(connection->mutex);
        connection->closeRequested = true;
        connection->connecting = false;
        connection->connected = false;
        closeSocket(connection->socketFd);
    }
}

} // namespace libreshockwave::player::xtra
