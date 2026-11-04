#include "platform_adapter.h"

#import <AppKit/AppKit.h>

#include <libproc.h>
#include <signal.h>
#include <sys/sysctl.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <mutex>
#include <optional>
#include <thread>
#include <unordered_map>
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

// KERN_PROCARGS2 layout: int argc, executable path, NUL padding, argc NUL-terminated args, env.
// Empty when the process is not readable (other users' processes) or has already exited.
std::string readCmdline(pid_t pid) {
    int mib[3] = {CTL_KERN, KERN_PROCARGS2, pid};
    size_t size = 0;
    if (sysctl(mib, 3, nullptr, &size, nullptr, 0) != 0 || size < sizeof(int)) return {};

    std::vector<char> buf(size);
    if (sysctl(mib, 3, buf.data(), &size, nullptr, 0) != 0 || size < sizeof(int)) return {};

    int argc = 0;
    std::memcpy(&argc, buf.data(), sizeof(int));
    size_t i = sizeof(int);
    while (i < size && buf[i] != '\0') ++i;
    while (i < size && buf[i] == '\0') ++i;

    std::string out;
    for (int arg = 0; arg < argc && i < size; ++arg) {
        size_t start = i;
        while (i < size && buf[i] != '\0') ++i;
        if (arg > 0) out += ' ';
        out.append(&buf[start], i - start);
        ++i;
    }
    return out;
}

// Helpers inside an app (Electron helpers, crash handlers) often have no bundle ID of their
// own; they belong to the outermost .app that contains their executable.
std::string enclosingBundleId(const std::string& executablePath) {
    size_t end = executablePath.find(".app/");
    if (end == std::string::npos) return {};
    std::string bundlePath = executablePath.substr(0, end + 4);

    static std::mutex cacheMutex;
    static std::unordered_map<std::string, std::string> cache;
    std::lock_guard<std::mutex> lock(cacheMutex);
    auto it = cache.find(bundlePath);
    if (it != cache.end()) return it->second;

    NSBundle* bundle = [NSBundle bundleWithPath:[NSString stringWithUTF8String:bundlePath.c_str()]];
    return cache[bundlePath] = bundle ? toStd(bundle.bundleIdentifier) : std::string();
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
    info.cmdline = readCmdline(pid);

    NSRunningApplication* app = [NSRunningApplication runningApplicationWithProcessIdentifier:pid];
    if (app) {
        info.identifier = toStd(app.bundleIdentifier);
        std::string localized = toStd(app.localizedName);
        if (!localized.empty()) info.name = localized;
    }
    if (info.identifier.empty()) info.identifier = enclosingBundleId(info.path);
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

    std::vector<std::uint32_t> ownProcessChain() override {
        std::vector<std::uint32_t> chain;
        pid_t pid = getpid();
        while (pid > 1 && chain.size() < 64) {
            chain.push_back(static_cast<std::uint32_t>(pid));
            struct proc_bsdinfo bsd;
            if (proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &bsd, sizeof(bsd)) != sizeof(bsd)) break;
            pid = static_cast<pid_t>(bsd.pbi_ppid);
        }
        return chain;
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
