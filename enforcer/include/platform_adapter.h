#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace brainrot {

struct ProcessInfo {
    std::uint32_t pid;
    std::uint32_t parentPid = 0;
    std::string name;
    std::string path;
    std::string identifier;  // bundle ID on macOS (own, else the enclosing app's), empty on Windows
    std::string cmdline;
    std::string detectedBy;  // diagnostic: "notification" or "poll", set on start events
};

// The only OS-specific surface the enforcer core talks to.
class PlatformAdapter {
public:
    virtual ~PlatformAdapter() = default;

    virtual std::vector<ProcessInfo> listProcesses() = 0;
    // This process and its ancestors (shell, terminal, ...), nearest first. Never to be killed.
    virtual std::vector<std::uint32_t> ownProcessChain() = 0;
    // Reports each process that starts after this call, exactly once. Existing processes are
    // not reported. The callback may run on any thread, never concurrently with itself, and
    // only while run() is active. Call before run().
    virtual void onProcessStart(std::function<void(const ProcessInfo&)> callback) = 0;
    // Blocks the calling thread (which must be the main thread) delivering events until stop().
    virtual void run() = 0;
    // Safe to call from a signal handler or any thread.
    virtual void stop() = 0;
    // Asks the process to quit; escalates to a hard kill after graceTimeoutMs.
    virtual bool terminate(std::uint32_t pid, int graceTimeoutMs) = 0;
};

// Implemented once per OS in src/platform_<os>.*; CMake compiles exactly one.
std::unique_ptr<PlatformAdapter> createPlatformAdapter();

}  // namespace brainrot
