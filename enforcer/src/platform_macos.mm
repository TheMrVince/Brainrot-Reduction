#include "platform_adapter.h"

#import <AppKit/AppKit.h>

#include <libproc.h>
#include <signal.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <mutex>
#include <optional>
#include <thread>
#include <unordered_set>

namespace brainrot {
namespace {

constexpr auto kPollInterval = std::chrono::milliseconds(1000);

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

std::optional<ProcessInfo> describe(pid_t pid) {
    char path[PROC_PIDPATHINFO_MAXSIZE];
    if (proc_pidpath(pid, path, sizeof(path)) <= 0) return std::nullopt;

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
    return info;
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
            if (pids[i] <= 0) continue;
            if (auto info = describe(pids[i])) result.push_back(std::move(*info));
        }
        return result;
    }

    void onProcessStart(std::function<void(const ProcessInfo&)> callback) override {
        callback_ = std::move(callback);
    }

    // Two detectors feed report(), which de-duplicates by pid:
    //  - NSWorkspace launch notifications: instant, but only for GUI apps.
    //  - A process-list diff every second: covers everything else, with up to 1s delay.
    void run() override {
        stopping_ = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            seen_.clear();
            for (const auto& p : listProcesses()) seen_.insert(p.pid);
        }

        id observer = [[NSWorkspace sharedWorkspace].notificationCenter
            addObserverForName:NSWorkspaceDidLaunchApplicationNotification
                        object:nil
                         queue:nil
                    usingBlock:^(NSNotification* note) {
                        NSRunningApplication* app = note.userInfo[NSWorkspaceApplicationKey];
                        if (!app) return;
                        if (auto info = describe(app.processIdentifier)) report(*info, "notification");
                    }];

        std::thread poller([this] { pollLoop(); });

        while (!stopping_) {
            SInt32 result = CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.25, false);
            if (result == kCFRunLoopRunFinished) std::this_thread::sleep_for(std::chrono::milliseconds(250));
        }

        poller.join();
        [[NSWorkspace sharedWorkspace].notificationCenter removeObserver:observer];
    }

    void stop() override { stopping_ = true; }

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

private:
    void report(ProcessInfo info, const char* source) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!seen_.insert(info.pid).second) return;
        info.detectedBy = source;
        if (callback_) callback_(info);
    }

    void pollLoop() {
        while (!stopping_) {
            for (auto waited = std::chrono::milliseconds(0); waited < kPollInterval && !stopping_;
                 waited += std::chrono::milliseconds(100)) {
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
            if (stopping_) break;

            std::vector<ProcessInfo> current = listProcesses();

            std::lock_guard<std::mutex> lock(mutex_);
            std::unordered_set<std::uint32_t> alive;
            for (auto& p : current) {
                alive.insert(p.pid);
                if (seen_.insert(p.pid).second) {
                    p.detectedBy = "poll";
                    if (callback_) callback_(p);
                }
            }
            // Forget exited pids so a recycled pid is reported as a new process.
            for (auto it = seen_.begin(); it != seen_.end();) {
                it = alive.count(*it) ? std::next(it) : seen_.erase(it);
            }
        }
    }

    std::function<void(const ProcessInfo&)> callback_;
    std::mutex mutex_;
    std::unordered_set<std::uint32_t> seen_;
    std::atomic<bool> stopping_{false};
};

}  // namespace

std::unique_ptr<PlatformAdapter> createPlatformAdapter() {
    return std::make_unique<MacAdapter>();
}

}  // namespace brainrot
