#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace brainrot {

struct ProcessInfo {
    std::uint32_t pid;
    std::string name;
    std::string path;
    std::string identifier;  // bundle ID on macOS, empty on Windows
    std::string cmdline;
};

// The only OS-specific surface the enforcer core talks to.
class PlatformAdapter {
public:
    virtual ~PlatformAdapter() = default;

    virtual std::vector<ProcessInfo> listProcesses() = 0;
    virtual void onProcessStart(std::function<void(const ProcessInfo&)> callback) = 0;
    // Asks the process to quit; escalates to a hard kill after graceTimeoutMs.
    virtual bool terminate(std::uint32_t pid, int graceTimeoutMs) = 0;
};

}  // namespace brainrot
