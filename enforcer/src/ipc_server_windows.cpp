// The Windows twin of ipc_server_posix.cpp: the same protocol over an AF_UNIX socket, which Winsock
// supports since Windows 10 1803. The differences are only in names (closesocket, recv/send, select)
// and in starting Winsock once before the first socket call.

#include "ipc_server.h"

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <windows.h>
#include <afunix.h>

#include <chrono>
#include <cstring>
#include <filesystem>
#include <system_error>

namespace brainrot {
namespace {

constexpr int kPollMs = 200;
constexpr size_t kMaxLineBytes = 1 << 20;

// Winsock must be started once per process before any socket call; it is stopped at exit.
bool winsockReady() {
    struct Winsock {
        bool ok;
        Winsock() {
            WSADATA data;
            ok = ::WSAStartup(MAKEWORD(2, 2), &data) == 0;
        }
        ~Winsock() {
            if (ok) ::WSACleanup();
        }
    };
    static Winsock winsock;
    return winsock.ok;
}

std::string lastSocketError() {
    return std::system_category().message(::WSAGetLastError());
}

SOCKET asSocket(std::intptr_t handle) {
    return static_cast<SOCKET>(handle);
}

// 1 if readable, 0 on timeout, -1 on error.
int waitReadable(SOCKET s, int timeoutMs) {
    fd_set readable;
    FD_ZERO(&readable);
    FD_SET(s, &readable);
    timeval timeout{timeoutMs / 1000, (timeoutMs % 1000) * 1000};
    int ready = ::select(0, &readable, nullptr, nullptr, &timeout);  // first argument is ignored on Windows
    return ready == SOCKET_ERROR ? -1 : ready;
}

bool writeAll(SOCKET s, const std::string& data) {
    size_t sent = 0;
    while (sent < data.size()) {
        int n = ::send(s, data.data() + sent, static_cast<int>(data.size() - sent), 0);
        if (n == SOCKET_ERROR || n == 0) return false;  // no SIGPIPE on Windows: a closed client is just an error
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

// A Windows socket file is a reparse point that std::filesystem cannot always inspect, so ask the OS
// directly. Paths go through the ANSI code page; non-ASCII user names are untested.
bool socketFileExists(const std::string& path) {
    return ::GetFileAttributesA(path.c_str()) != INVALID_FILE_ATTRIBUTES;
}

void removeSocketFile(const std::string& path) {
    ::DeleteFileA(path.c_str());
}

bool someoneIsListening(const sockaddr_un& addr) {
    SOCKET s = ::socket(AF_UNIX, SOCK_STREAM, 0);
    if (s == INVALID_SOCKET) return false;
    bool connected = ::connect(s, reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) == 0;
    ::closesocket(s);
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
    if (!winsockReady()) return "WSAStartup failed: " + lastSocketError();

    // No mode bits on Windows: the folder lives in the user's profile, whose access list already
    // admits only that user (plus SYSTEM and administrators), and the socket file inherits it.
    std::error_code ec;
    std::filesystem::path dir = std::filesystem::path(path_).parent_path();
    std::filesystem::create_directories(dir, ec);
    if (ec) return "cannot create " + dir.string() + ": " + ec.message();

    if (socketFileExists(path_)) {
        if (someoneIsListening(addr)) return "another enforcer is already listening on " + path_;
        removeSocketFile(path_);  // leftover from a crashed run
    }

    SOCKET listener = ::socket(AF_UNIX, SOCK_STREAM, 0);
    if (listener == INVALID_SOCKET) return "socket: " + lastSocketError();

    if (::bind(listener, reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) != 0) {
        std::string message = "bind: " + lastSocketError();
        ::closesocket(listener);
        return message;
    }
    if (::listen(listener, 8) != 0) {
        std::string message = "listen: " + lastSocketError();
        ::closesocket(listener);
        removeSocketFile(path_);
        return message;
    }

    listenFd_ = static_cast<std::intptr_t>(listener);
    stopping_ = false;
    acceptThread_ = std::thread([this] { acceptLoop(); });
    return {};
}

void IpcServer::stop() {
    if (listenFd_ == -1) return;

    stopping_ = true;
    if (acceptThread_.joinable()) acceptThread_.join();
    while (activeConnections_ > 0) std::this_thread::sleep_for(std::chrono::milliseconds(10));

    ::closesocket(asSocket(listenFd_));
    listenFd_ = -1;
    removeSocketFile(path_);
}

void IpcServer::acceptLoop() {
    SOCKET listener = asSocket(listenFd_);
    while (!stopping_) {
        if (waitReadable(listener, kPollMs) <= 0) continue;

        SOCKET client = ::accept(listener, nullptr, nullptr);
        if (client == INVALID_SOCKET) continue;

        ++activeConnections_;
        std::thread([this, client] {
            serveConnection(static_cast<std::intptr_t>(client));
            ::closesocket(client);
            --activeConnections_;
        }).detach();
    }
}

void IpcServer::serveConnection(std::intptr_t handle) {
    SOCKET s = asSocket(handle);
    std::string pending;
    char buffer[4096];

    while (!stopping_) {
        int ready = waitReadable(s, kPollMs);
        if (ready < 0) return;
        if (ready == 0) continue;

        int n = ::recv(s, buffer, sizeof(buffer), 0);
        if (n <= 0) return;  // client closed or errored
        pending.append(buffer, static_cast<size_t>(n));

        size_t newline;
        while ((newline = pending.find('\n')) != std::string::npos) {
            std::string line = pending.substr(0, newline);
            pending.erase(0, newline + 1);
            if (line.empty()) continue;
            if (!writeAll(s, handler_.handle(line) + "\n")) return;
        }
        if (pending.size() > kMaxLineBytes) return;  // not speaking our protocol
    }
}

}  // namespace brainrot
