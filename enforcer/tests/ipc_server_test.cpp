#include <nlohmann/json.hpp>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winsock2.h>
#include <windows.h>
#include <afunix.h>
#else
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#endif

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <iostream>
#include <thread>

#include "ipc_server.h"

using brainrot::IpcServer;
using brainrot::MessageHandler;
using brainrot::Policy;
using json = nlohmann::json;

static int failures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #cond "\n"; \
            ++failures;                                                          \
        }                                                                        \
    } while (0)

namespace {

// The few socket calls whose names differ between POSIX and Winsock.
#ifdef _WIN32
using Socket = SOCKET;
constexpr Socket kNoSocket = INVALID_SOCKET;
void closeSocket(Socket s) { ::closesocket(s); }
bool waitReadable(Socket s, int timeoutMs) {
    fd_set readable;
    FD_ZERO(&readable);
    FD_SET(s, &readable);
    timeval timeout{timeoutMs / 1000, (timeoutMs % 1000) * 1000};
    return ::select(0, &readable, nullptr, nullptr, &timeout) > 0;
}
// A Windows socket file is a reparse point that std::filesystem cannot always inspect.
bool pathExists(const std::string& path) {
    return ::GetFileAttributesA(path.c_str()) != INVALID_FILE_ATTRIBUTES;
}
#else
using Socket = int;
constexpr Socket kNoSocket = -1;
void closeSocket(Socket s) { ::close(s); }
bool waitReadable(Socket s, int timeoutMs) {
    pollfd pfd{s, POLLIN, 0};
    return ::poll(&pfd, 1, timeoutMs) > 0;
}
bool pathExists(const std::string& path) {
    return std::filesystem::exists(path);
}
#endif

sockaddr_un addressOf(const std::string& path) {
    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, path.c_str(), sizeof(addr.sun_path) - 1);
    return addr;
}

class Client {
public:
    explicit Client(const std::string& path) {
        fd_ = ::socket(AF_UNIX, SOCK_STREAM, 0);
        sockaddr_un addr = addressOf(path);
        connected_ = ::connect(fd_, reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) == 0;
    }
    ~Client() { if (fd_ != kNoSocket) closeSocket(fd_); }

    bool connected() const { return connected_; }
    void send(const std::string& data) { (void)::send(fd_, data.data(), static_cast<int>(data.size()), 0); }

    // One response line, or "" if none arrives within the timeout.
    std::string readLine(int timeoutMs = 2000) {
        auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
        while (buffer_.find('\n') == std::string::npos) {
            auto left = std::chrono::duration_cast<std::chrono::milliseconds>(
                deadline - std::chrono::steady_clock::now()).count();
            if (left <= 0) return "";
            if (!waitReadable(fd_, static_cast<int>(left))) return "";
            char chunk[1024];
            auto n = ::recv(fd_, chunk, static_cast<int>(sizeof(chunk)), 0);
            if (n <= 0) return "";
            buffer_.append(chunk, static_cast<size_t>(n));
        }
        size_t newline = buffer_.find('\n');
        std::string line = buffer_.substr(0, newline);
        buffer_.erase(0, newline + 1);
        return line;
    }

private:
    Socket fd_ = kNoSocket;
    bool connected_ = false;
    std::string buffer_;
};

// Short on purpose: socket paths are limited to about 100 bytes.
std::string makeTempDir() {
#ifdef _WIN32
    char base[MAX_PATH + 1];
    if (::GetTempPathA(sizeof(base), base) == 0) return "";
    std::string dir = std::string(base) + "brainrot-test-" + std::to_string(::GetCurrentProcessId());
    std::error_code ec;
    std::filesystem::create_directory(dir, ec);
    return ec ? "" : dir;
#else
    std::string pattern = "/tmp/brainrot-test-XXXXXX";
    char* dir = ::mkdtemp(pattern.data());
    return dir ? dir : "";
#endif
}

void answersPingAndAppliesPolicy(const std::string& socketPath) {
    std::atomic<int> received{0};
    MessageHandler handler([&](const Policy&) { ++received; });
    IpcServer server(socketPath, handler);
    CHECK(server.start().empty());

#ifndef _WIN32
    struct stat st{};
    CHECK(::stat(socketPath.c_str(), &st) == 0);
    CHECK((st.st_mode & 0777) == 0600);  // only the current user may connect
#else
    CHECK(pathExists(socketPath));  // no mode bits on Windows; the profile folder's access list applies
#endif

    Client client(socketPath);
    CHECK(client.connected());

    client.send("{\"cmd\":\"ping\"}\n");
    CHECK(json::parse(client.readLine())["event"] == "ack");

    // Two messages in one write, then one message split across two writes.
    client.send("{\"cmd\":\"ping\"}\n{\"cmd\":\"set_policy\",\"locked\":true,\"apps\":[]}\n");
    CHECK(json::parse(client.readLine())["cmd"] == "ping");
    CHECK(json::parse(client.readLine())["cmd"] == "set_policy");

    client.send("{\"cmd\":\"set_pol");
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    client.send("icy\",\"locked\":false,\"apps\":[]}\n");
    CHECK(json::parse(client.readLine())["cmd"] == "set_policy");
    CHECK(received == 2);

    client.send("garbage\n");
    CHECK(json::parse(client.readLine())["event"] == "error");
    client.send("{\"cmd\":\"ping\"}\n");
    CHECK(json::parse(client.readLine())["event"] == "ack");  // connection survives bad input

    server.stop();
    CHECK(!pathExists(socketPath));  // cleaned up
}

void servesSeveralClientsAtOnce(const std::string& socketPath) {
    MessageHandler handler(nullptr);
    IpcServer server(socketPath, handler);
    CHECK(server.start().empty());

    Client a(socketPath);
    Client b(socketPath);
    a.send("{\"cmd\":\"ping\"}\n");
    b.send("{\"cmd\":\"ping\"}\n");
    CHECK(json::parse(a.readLine())["event"] == "ack");
    CHECK(json::parse(b.readLine())["event"] == "ack");
    server.stop();
}

void stopsPromptlyWithClientsStillConnected(const std::string& socketPath) {
    MessageHandler handler(nullptr);
    IpcServer server(socketPath, handler);
    CHECK(server.start().empty());

    Client idle(socketPath);
    CHECK(idle.connected());

    auto begin = std::chrono::steady_clock::now();
    server.stop();
    auto took = std::chrono::steady_clock::now() - begin;
    CHECK(took < std::chrono::seconds(2));
}

void refusesToStartWhenAnotherServerIsListening(const std::string& socketPath) {
    MessageHandler handler(nullptr);
    IpcServer first(socketPath, handler);
    CHECK(first.start().empty());

    IpcServer second(socketPath, handler);
    std::string error = second.start();
    CHECK(error.find("already listening") != std::string::npos);

    // The failed second start must not have removed the first server's socket.
    Client client(socketPath);
    CHECK(client.connected());
    first.stop();
}

void replacesLeftoverSocketFromACrashedRun(const std::string& socketPath) {
    // Bind without listening, then close: leaves a socket file that nobody serves.
    Socket fd = ::socket(AF_UNIX, SOCK_STREAM, 0);
    sockaddr_un addr = addressOf(socketPath);
    CHECK(::bind(fd, reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) == 0);
    closeSocket(fd);
    CHECK(pathExists(socketPath));

    MessageHandler handler(nullptr);
    IpcServer server(socketPath, handler);
    CHECK(server.start().empty());
    Client client(socketPath);
    CHECK(client.connected());
    server.stop();
}

void rejectsOverlongPaths() {
    MessageHandler handler(nullptr);
    IpcServer server("/tmp/" + std::string(200, 'x') + "/s.sock", handler);
    CHECK(server.start().find("too long") != std::string::npos);
}

}  // namespace

int main() {
#ifdef _WIN32
    WSADATA wsa;  // the test's own client sockets need Winsock started too
    if (::WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        std::cerr << "WSAStartup failed\n";
        return 1;
    }
#endif
    std::string dir = makeTempDir();
    if (dir.empty()) {
        std::cerr << "could not create temp dir\n";
        return 1;
    }

    answersPingAndAppliesPolicy(dir + "/a.sock");
    servesSeveralClientsAtOnce(dir + "/b.sock");
    stopsPromptlyWithClientsStillConnected(dir + "/c.sock");
    refusesToStartWhenAnotherServerIsListening(dir + "/d.sock");
    replacesLeftoverSocketFromACrashedRun(dir + "/e.sock");
    rejectsOverlongPaths();

    std::error_code ec;
    std::filesystem::remove_all(dir, ec);
    if (failures == 0) std::cout << "ipc server tests passed\n";
    return failures == 0 ? 0 : 1;
}
