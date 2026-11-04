#include "platform_adapter.h"

#import <AppKit/AppKit.h>

#include <libproc.h>
#include <signal.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <thread>

namespace brainrot {
namespace {

std::string toStd(NSString* s) {
    return s ? std::string([s UTF8String]) : std::string();
}

bool isAlive(pid_t pid) {
    return kill(pid, 0) == 0 || errno == EPERM;
}

bool waitForExit(pid_t pid, int timeoutMs) {
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    while (isAlive(pid)) {
        if (std::chrono::steady_clock::now() >= deadline) return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    return true;
}

class MacAdapter : public PlatformAdapter {
public:
    std::vector<ProcessInfo> listProcesses() override {
        std::vector<ProcessInfo> result;

        int bytes = proc_listpids(PROC_ALL_PIDS, 0, nullptr, 0);
        if (bytes <= 0) return result;

        std::vector<pid_t> pids(bytes / sizeof(pid_t) + 32);
        bytes = proc_listpids(PROC_ALL_PIDS, 0, pids.data(),
                              static_cast<int>(pids.size() * sizeof(pid_t)));
        int count = bytes / static_cast<int>(sizeof(pid_t));

        for (int i = 0; i < count; ++i) {
            pid_t pid = pids[i];
            if (pid <= 0) continue;

            char path[PROC_PIDPATHINFO_MAXSIZE];
            if (proc_pidpath(pid, path, sizeof(path)) <= 0) continue;

            char name[2 * MAXCOMLEN + 1] = {0};
            proc_name(pid, name, sizeof(name));

            ProcessInfo info;
            info.pid = static_cast<std::uint32_t>(pid);
            info.name = name;
            info.path = path;

            NSRunningApplication* app = [NSRunningApplication runningApplicationWithProcessIdentifier:pid];
            if (app) {
                info.identifier = toStd(app.bundleIdentifier);
                std::string localized = toStd(app.localizedName);
                if (!localized.empty()) info.name = localized;
            }
            result.push_back(std::move(info));
        }
        return result;
    }

    // Launch notifications arrive in phase C2.
    void onProcessStart(std::function<void(const ProcessInfo&)> callback) override {
        (void)callback;
    }

    bool terminate(std::uint32_t pid, int graceTimeoutMs) override {
        pid_t target = static_cast<pid_t>(pid);
        if (target <= 1 || target == getpid()) return false;

        NSRunningApplication* app = [NSRunningApplication runningApplicationWithProcessIdentifier:target];
        if (app) {
            [app terminate];
        } else {
            kill(target, SIGTERM);
        }
        if (waitForExit(target, graceTimeoutMs)) return true;

        kill(target, SIGKILL);
        return waitForExit(target, 2000);
    }
};

}  // namespace

std::unique_ptr<PlatformAdapter> createPlatformAdapter() {
    return std::make_unique<MacAdapter>();
}

}  // namespace brainrot
