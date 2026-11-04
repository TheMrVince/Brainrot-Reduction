#pragma once

#include <atomic>
#include <string>
#include <thread>

#include "message_handler.h"

namespace brainrot {

// Listens on a local socket, reads newline-terminated messages and writes back the
// handler's one-line responses. Implemented per OS (ipc_server_posix.cpp today).
class IpcServer {
public:
    IpcServer(std::string socketPath, const MessageHandler& handler);
    ~IpcServer();

    IpcServer(const IpcServer&) = delete;
    IpcServer& operator=(const IpcServer&) = delete;

    // Returns an empty string on success, otherwise a description of what went wrong.
    // Refuses to start if another enforcer is already listening on the path.
    std::string start();
    void stop();

private:
    void acceptLoop();
    void serveConnection(int fd);

    std::string path_;
    const MessageHandler& handler_;
    int listenFd_ = -1;
    std::thread acceptThread_;
    std::atomic<bool> stopping_{false};
    std::atomic<int> activeConnections_{0};
};

}  // namespace brainrot
