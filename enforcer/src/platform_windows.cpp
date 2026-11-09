// The Windows platform adapter: the counterpart of platform_macos.mm.
//
//  - Processes come from a Toolhelp snapshot; path, start time and command line are read per process.
//  - New processes are found by diffing the process list every second (Windows has no cheap launch
//    notification without admin rights), so a blocked app may be visible for up to a second.
//  - Closing asks politely first (WM_CLOSE to the app's visible windows), then uses TerminateProcess.

#include "platform_adapter.h"

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <tlhelp32.h>
#include <winternl.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cwchar>
#include <mutex>
#include <optional>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace brainrot {
namespace {

constexpr auto kPollInterval = std::chrono::milliseconds(1000);
constexpr DWORD kSystemPid = 4;           // the kernel's "System" process; pid 0 is the idle process
constexpr UINT kForcedExitCode = 1;       // what a process killed by TerminateProcess reports
constexpr DWORD kKillWaitMs = 2000;

// Closes a Win32 handle when it goes out of scope.
class Handle {
public:
    explicit Handle(HANDLE h) : h_(h) {}
    ~Handle() {
        if (h_ && h_ != INVALID_HANDLE_VALUE) ::CloseHandle(h_);
    }
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
    HANDLE get() const { return h_; }
    explicit operator bool() const { return h_ && h_ != INVALID_HANDLE_VALUE; }

private:
    HANDLE h_;
};

// Windows speaks UTF-16; the rest of the enforcer speaks UTF-8.
std::string toUtf8(const wchar_t* text, size_t length) {
    if (!text || length == 0) return {};
    int bytes = ::WideCharToMultiByte(CP_UTF8, 0, text, static_cast<int>(length), nullptr, 0, nullptr, nullptr);
    if (bytes <= 0) return {};
    std::string out(static_cast<size_t>(bytes), '\0');
    ::WideCharToMultiByte(CP_UTF8, 0, text, static_cast<int>(length), out.data(), bytes, nullptr, nullptr);
    return out;
}

std::string toUtf8(const wchar_t* text) {
    return text ? toUtf8(text, std::wcslen(text)) : std::string();
}

// FILETIME counts 100 ns steps since 1601-01-01 UTC.
std::uint64_t ticks(const FILETIME& ft) {
    return (static_cast<std::uint64_t>(ft.dwHighDateTime) << 32) | ft.dwLowDateTime;
}

std::int64_t toUnixSeconds(std::uint64_t fileTimeTicks) {
    constexpr std::uint64_t kUnixEpochTicks = 116444736000000000ULL;  // 1970-01-01 in FILETIME ticks
    if (fileTimeTicks < kUnixEpochTicks) return 0;
    return static_cast<std::int64_t>((fileTimeTicks - kUnixEpochTicks) / 10000000ULL);
}

std::optional<std::uint64_t> creationTicks(HANDLE process) {
    FILETIME created, exited, kernel, user;
    if (!::GetProcessTimes(process, &created, &exited, &kernel, &user)) return std::nullopt;
    return ticks(created);
}

std::optional<std::uint64_t> creationTicks(DWORD pid) {
    Handle process(::OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid));
    if (!process) return std::nullopt;
    return creationTicks(process.get());
}

std::string readImagePath(HANDLE process) {
    std::vector<wchar_t> buffer(32768);  // the longest path Windows allows
    DWORD size = static_cast<DWORD>(buffer.size());
    if (!::QueryFullProcessImageNameW(process, 0, buffer.data(), &size)) return {};
    return toUtf8(buffer.data(), size);
}

// There is no documented call for another process's command line; NtQueryInformationProcess with
// ProcessCommandLineInformation (60) is the stable, widely used one (Windows 8.1 and later).
std::string readCmdline(HANDLE process) {
    using QueryFn = NTSTATUS(NTAPI*)(HANDLE, PROCESSINFOCLASS, PVOID, ULONG, PULONG);
    static const QueryFn query = reinterpret_cast<QueryFn>(reinterpret_cast<void*>(
        ::GetProcAddress(::GetModuleHandleW(L"ntdll.dll"), "NtQueryInformationProcess")));
    if (!query) return {};
    constexpr auto kProcessCommandLineInformation = static_cast<PROCESSINFOCLASS>(60);

    ULONG size = 0;
    query(process, kProcessCommandLineInformation, nullptr, 0, &size);  // asks for the size
    if (size < sizeof(UNICODE_STRING)) return {};

    std::vector<std::uint64_t> buffer((size + sizeof(std::uint64_t) - 1) / sizeof(std::uint64_t));  // 8-byte aligned
    if (query(process, kProcessCommandLineInformation, buffer.data(), size, &size) < 0) return {};
    const auto* text = reinterpret_cast<const UNICODE_STRING*>(buffer.data());
    return toUtf8(text->Buffer, text->Length / sizeof(wchar_t));
}

// Like macOS, a process whose executable cannot be read is left out: those are the kernel, protected
// system processes and other users' elevated ones, which this enforcer could not close anyway.
std::optional<ProcessInfo> describe(const PROCESSENTRY32W& entry) {
    Handle process(::OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, entry.th32ProcessID));
    if (!process) return std::nullopt;

    ProcessInfo info;
    info.path = readImagePath(process.get());
    if (info.path.empty()) return std::nullopt;

    info.pid = entry.th32ProcessID;
    info.parentPid = entry.th32ParentProcessID;
    info.name = toUtf8(entry.szExeFile);  // includes ".exe"
    info.cmdline = readCmdline(process.get());
    if (auto created = creationTicks(process.get())) info.startTime = toUnixSeconds(*created);
    // info.identifier stays empty: Windows has no bundle IDs.
    return info;
}

// Every running process as (pid, parent pid), including the ones describe() skips.
std::vector<PROCESSENTRY32W> snapshot() {
    std::vector<PROCESSENTRY32W> entries;
    Handle snap(::CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0));
    if (!snap) return entries;

    PROCESSENTRY32W entry{};
    entry.dwSize = sizeof(entry);
    for (BOOL more = ::Process32FirstW(snap.get(), &entry); more; more = ::Process32NextW(snap.get(), &entry)) {
        if (entry.th32ProcessID != 0) entries.push_back(entry);
    }
    return entries;
}

// Posts WM_CLOSE to the process's visible top-level windows, as clicking their close button would.
// Returns how many windows were asked.
int askWindowsToClose(DWORD pid) {
    struct Search {
        DWORD pid;
        int asked;
    } search{pid, 0};

    ::EnumWindows(
        [](HWND window, LPARAM param) -> BOOL {
            auto* s = reinterpret_cast<Search*>(param);
            DWORD owner = 0;
            ::GetWindowThreadProcessId(window, &owner);
            // Skip hidden helper windows and owned ones (dialogs, tool windows): closing the main
            // window is what a user would do.
            if (owner == s->pid && ::IsWindowVisible(window) && !::GetWindow(window, GW_OWNER)) {
                if (::PostMessageW(window, WM_CLOSE, 0, 0)) ++s->asked;
            }
            return TRUE;
        },
        reinterpret_cast<LPARAM>(&search));
    return search.asked;
}

bool hasExited(HANDLE process, DWORD waitMs) {
    return ::WaitForSingleObject(process, waitMs) == WAIT_OBJECT_0;
}

class WindowsAdapter : public PlatformAdapter {
public:
    std::vector<ProcessInfo> listProcesses() override {
        std::vector<ProcessInfo> result;
        for (const auto& entry : snapshot()) {
            if (auto info = describe(entry)) result.push_back(std::move(*info));
        }
        return result;
    }

    // Windows reuses pids, and a process keeps its parent's pid after the parent exits. A "parent"
    // that started after its child is therefore a stranger that got the old pid; the walk stops there.
    std::vector<std::uint32_t> ownProcessChain() override {
        std::unordered_map<DWORD, DWORD> parentOf;
        for (const auto& entry : snapshot()) parentOf[entry.th32ProcessID] = entry.th32ParentProcessID;

        std::vector<std::uint32_t> chain;
        DWORD pid = ::GetCurrentProcessId();
        std::optional<std::uint64_t> childCreated = creationTicks(pid);
        chain.push_back(pid);

        while (chain.size() < 64) {
            auto it = parentOf.find(pid);
            if (it == parentOf.end()) break;
            DWORD parent = it->second;
            if (parent == 0 || parent == kSystemPid) break;
            if (std::find(chain.begin(), chain.end(), parent) != chain.end()) break;

            std::optional<std::uint64_t> parentCreated = creationTicks(parent);
            if (!parentCreated || (childCreated && *parentCreated > *childCreated)) break;

            chain.push_back(parent);
            pid = parent;
            childCreated = parentCreated;
        }
        return chain;
    }

    void onProcessStart(std::function<void(const ProcessInfo&)> callback) override {
        callback_ = std::move(callback);
    }

    void run() override {
        stopping_ = false;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            seen_.clear();
            for (const auto& entry : snapshot()) seen_.insert(entry.th32ProcessID);
        }

        std::thread poller([this] { pollLoop(); });
        while (!stopping_) std::this_thread::sleep_for(std::chrono::milliseconds(100));
        poller.join();
    }

    void stop() override { stopping_ = true; }

    // Arrives in C6c (a tray balloon). Until then warnings are only logged by the enforcer.
    void notify(const std::string&, const std::string&) override {}

    bool terminate(std::uint32_t pid, int graceTimeoutMs) override {
        if (pid == 0 || pid == kSystemPid || pid == ::GetCurrentProcessId()) return false;

        Handle process(::OpenProcess(PROCESS_TERMINATE | SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE,
                                     static_cast<DWORD>(pid)));
        if (!process) return ::GetLastError() == ERROR_INVALID_PARAMETER;  // no such process: already gone
        if (hasExited(process.get(), 0)) return true;

        // A process with no visible window (or one that ignores WM_CLOSE) is killed without waiting.
        if (askWindowsToClose(static_cast<DWORD>(pid)) > 0 &&
            hasExited(process.get(), static_cast<DWORD>(std::max(graceTimeoutMs, 0)))) {
            return true;
        }

        if (!::TerminateProcess(process.get(), kForcedExitCode)) return hasExited(process.get(), 0);
        return hasExited(process.get(), kKillWaitMs);
    }

private:
    void pollLoop() {
        while (!stopping_) {
            for (auto waited = std::chrono::milliseconds(0); waited < kPollInterval && !stopping_;
                 waited += std::chrono::milliseconds(100)) {
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
            if (stopping_) break;

            std::vector<PROCESSENTRY32W> entries = snapshot();

            std::lock_guard<std::mutex> lock(mutex_);
            std::unordered_set<std::uint32_t> alive;
            for (const auto& entry : entries) {
                alive.insert(entry.th32ProcessID);
                if (!seen_.insert(entry.th32ProcessID).second) continue;
                if (auto info = describe(entry)) {
                    info->detectedBy = "poll";
                    if (callback_) callback_(*info);
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
    return std::make_unique<WindowsAdapter>();
}

}  // namespace brainrot
