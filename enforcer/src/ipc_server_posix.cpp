#include "ipc_server.h"

#include <poll.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cstring>
#include <filesystem>

namespace brainrot {
namespace {

constexpr int kPollMs = 200;
constexpr size_t kMaxLineBytes = 1 << 20;

bool writeAll(int fd, const std::string& data) {
    size_t sent = 0;
    while (sent < data.size()) {
        ssize_t n = ::write(fd, data.data() + sent, data.size() - sent);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        sent += static_cast<size_t>(n);
    }
    return true;
}

bool fillAddress(const std::string& path, sockaddr_un& addr) {
    std::memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    if (path.size() >= sizeof(addr.sun_path)) return false;
    std::memcpy(addr.sun_path, path.c_str(), path.size() + 1);
    return true;
}

bool someoneIsListening(const sockaddr_un& addr) {
    int fd = ::socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return false;
    bool connected = ::connect(fd, reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) == 0;
    ::close(fd);
    return connected;
}

}  // namespace

IpcServer::IpcServer(std::string socketPath, const MessageHandler& handler)
    : path_(std::move(socketPath)), handler_(handler) {}

IpcServer::~IpcServer() {
    stop();
}

std::string IpcServer::start() {
    sockaddr_un addr;
    if (!fillAddress(path_, addr)) return "socket path too long: " + path_;

    // A write to a closed client must fail with EPIPE, not kill the daemon.
    ::signal(SIGPIPE, SIG_IGN);

    std::error_code ec;
    std::filesystem::path dir = std::filesystem::path(path_).parent_path();
    std::filesystem::create_directories(dir, ec);
    if (ec) return "cannot create " + dir.string() + ": " + ec.message();
    ::chmod(dir.c_str(), 0700);

    if (std::filesystem::exists(path_, ec)) {
        if (someoneIsListening(addr)) return "another enforcer is already listening on " + path_;
        ::unlink(path_.c_str());  // leftover from a crashed run
    }

    int listener = ::socket(AF_UNIX, SOCK_STREAM, 0);
    if (listener < 0) return std::string("socket: ") + std::strerror(errno);

    // Only the current user may connect.
    mode_t oldMask = ::umask(0177);
    int rc = ::bind(listener, reinterpret_cast<const sockaddr*>(&addr), sizeof(addr));
    int bindError = errno;
    ::umask(oldMask);
    if (rc != 0) {
        ::close(listener);
        return std::string("bind: ") + std::strerror(bindError);
    }
    if (::listen(listener, 8) != 0) {
        std::string message = std::string("listen: ") + std::strerror(errno);
        ::close(listener);
        ::unlink(path_.c_str());
        return message;
    }

    listenFd_ = listener;
    stopping_ = false;
    acceptThread_ = std::thread([this] { acceptLoop(); });
    return {};
}

void IpcServer::stop() {
    if (listenFd_ < 0) return;

    stopping_ = true;
    if (acceptThread_.joinable()) acceptThread_.join();
    while (activeConnections_ > 0) std::this_thread::sleep_for(std::chrono::milliseconds(10));

    ::close(static_cast<int>(listenFd_));
    listenFd_ = -1;
    ::unlink(path_.c_str());
}

void IpcServer::acceptLoop() {
    int listener = static_cast<int>(listenFd_);
    while (!stopping_) {
        pollfd pfd{listener, POLLIN, 0};
        if (::poll(&pfd, 1, kPollMs) <= 0) continue;

        int client = ::accept(listener, nullptr, nullptr);
        if (client < 0) continue;

        ++activeConnections_;
        std::thread([this, client] {
            serveConnection(client);
            ::close(client);
            --activeConnections_;
        }).detach();
    }
}

void IpcServer::serveConnection(std::intptr_t handle) {
    int fd = static_cast<int>(handle);
    std::string pending;
    char buffer[4096];

    while (!stopping_) {
        pollfd pfd{fd, POLLIN, 0};
        int ready = ::poll(&pfd, 1, kPollMs);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) return;
        if (ready == 0) continue;

        ssize_t n = ::read(fd, buffer, sizeof(buffer));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return;  // client closed or errored
        pending.append(buffer, static_cast<size_t>(n));

        size_t newline;
        while ((newline = pending.find('\n')) != std::string::npos) {
            std::string line = pending.substr(0, newline);
            pending.erase(0, newline + 1);
            if (line.empty()) continue;
            if (!writeAll(fd, handler_.handle(line) + "\n")) return;
        }
        if (pending.size() > kMaxLineBytes) return;  // not speaking our protocol
    }
}

}  // namespace brainrot
