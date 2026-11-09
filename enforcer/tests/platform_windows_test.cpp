// Tests the real Windows adapter. It only ever closes processes it started itself: copies of this
// program run in a "child" mode (no window, a window that closes when asked, or a window that ignores
// WM_CLOSE). Windows are placed off-screen, never take focus, and every child quits after 60 s.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <tlhelp32.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <ctime>
#include <iostream>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "platform_adapter.h"

using brainrot::PlatformAdapter;
using brainrot::ProcessInfo;
using Clock = std::chrono::steady_clock;

static int failures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #cond "\n"; \
            ++failures;                                                          \
        }                                                                        \
    } while (0)

namespace {

constexpr UINT kChildLifetimeMs = 60000;
constexpr DWORD kForcedExitCode = 1;  // what the adapter's TerminateProcess leaves behind

// ---- Child side ---------------------------------------------------------------------------------

bool g_stubborn = false;

LRESULT CALLBACK childWindowProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    switch (message) {
        case WM_CLOSE:
            if (g_stubborn) return 0;  // ignore the polite request
            break;                     // default: DestroyWindow
        case WM_TIMER:
            ::DestroyWindow(window);   // lifetime cap, in case the test died
            return 0;
        case WM_DESTROY:
            ::PostQuitMessage(0);
            return 0;
    }
    return ::DefWindowProcW(window, message, wParam, lParam);
}

int runWindowChild(bool stubborn) {
    g_stubborn = stubborn;
    WNDCLASSW cls{};
    cls.lpfnWndProc = childWindowProc;
    cls.hInstance = ::GetModuleHandleW(nullptr);
    cls.lpszClassName = L"BrainrotAdapterTestChild";
    ::RegisterClassW(&cls);

    // Visible (so the adapter treats it as a main window) but off-screen and off the taskbar.
    HWND window = ::CreateWindowExW(WS_EX_TOOLWINDOW, cls.lpszClassName, L"brainrot adapter test",
                                    WS_OVERLAPPEDWINDOW, -20000, -20000, 100, 100, nullptr, nullptr,
                                    cls.hInstance, nullptr);
    if (!window) return 3;
    ::ShowWindow(window, SW_SHOWNOACTIVATE);
    ::SetTimer(window, 1, kChildLifetimeMs, nullptr);

    MSG msg;
    while (::GetMessageW(&msg, nullptr, 0, 0) > 0) {
        ::TranslateMessage(&msg);
        ::DispatchMessageW(&msg);
    }
    return 0;
}

int runChild(const std::string& mode) {
    if (mode == "--child-console") {
        ::Sleep(kChildLifetimeMs);
        return 0;
    }
    if (mode == "--child-window") return runWindowChild(false);
    if (mode == "--child-stubborn") return runWindowChild(true);
    return 2;
}

// ---- Parent side --------------------------------------------------------------------------------

std::wstring ownExePath() {
    wchar_t path[MAX_PATH];
    DWORD n = ::GetModuleFileNameW(nullptr, path, MAX_PATH);
    return std::wstring(path, n);
}

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return s;
}

bool endsWith(const std::string& s, const std::string& suffix) {
    return s.size() >= suffix.size() && s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

class Child {
public:
    Child(const std::string& mode, const std::string& marker) {
        std::wstring exe = ownExePath();
        std::wstring cmd = L"\"" + exe + L"\" " + std::wstring(mode.begin(), mode.end()) + L" " +
                           std::wstring(marker.begin(), marker.end());
        STARTUPINFOW si{};
        si.cb = sizeof(si);
        started_ = ::CreateProcessW(exe.c_str(), cmd.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr,
                                    nullptr, &si, &pi_) != 0;
    }
    ~Child() {
        if (!started_) return;
        if (::WaitForSingleObject(pi_.hProcess, 0) != WAIT_OBJECT_0) ::TerminateProcess(pi_.hProcess, 99);
        ::CloseHandle(pi_.hThread);
        ::CloseHandle(pi_.hProcess);
    }
    Child(const Child&) = delete;
    Child& operator=(const Child&) = delete;

    bool started() const { return started_; }
    std::uint32_t pid() const { return pi_.dwProcessId; }
    bool exited(DWORD waitMs = 0) const { return ::WaitForSingleObject(pi_.hProcess, waitMs) == WAIT_OBJECT_0; }
    DWORD exitCode() const {
        DWORD code = 0;
        ::GetExitCodeProcess(pi_.hProcess, &code);
        return code;
    }

    // The window children need a moment to create their window before there is anything to close.
    bool waitForVisibleWindow(int timeoutMs = 5000) const {
        auto deadline = Clock::now() + std::chrono::milliseconds(timeoutMs);
        while (Clock::now() < deadline) {
            struct Search {
                DWORD pid;
                bool found;
            } search{pi_.dwProcessId, false};
            ::EnumWindows(
                [](HWND w, LPARAM p) -> BOOL {
                    auto* s = reinterpret_cast<Search*>(p);
                    DWORD owner = 0;
                    ::GetWindowThreadProcessId(w, &owner);
                    if (owner == s->pid && ::IsWindowVisible(w)) s->found = true;
                    return !s->found;
                },
                reinterpret_cast<LPARAM>(&search));
            if (search.found) return true;
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        }
        return false;
    }

private:
    PROCESS_INFORMATION pi_{};
    bool started_ = false;
};

std::string uniqueMarker(const char* what) {
    return std::string("brainrot-adapter-test-") + what + "-" + std::to_string(::GetCurrentProcessId());
}

const ProcessInfo* findPid(const std::vector<ProcessInfo>& list, std::uint32_t pid) {
    for (const auto& p : list) {
        if (p.pid == pid) return &p;
    }
    return nullptr;
}

long long millisSince(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}

void listsAChildWithItsDetails(PlatformAdapter& adapter) {
    std::string marker = uniqueMarker("list");
    Child child("--child-console", marker);
    CHECK(child.started());

    auto processes = adapter.listProcesses();
    const ProcessInfo* p = findPid(processes, child.pid());
    CHECK(p != nullptr);
    if (!p) return;

    CHECK(lower(p->name) == "platform_windows_test.exe");  // the file name, with ".exe"
    CHECK(endsWith(lower(p->path), "\\platform_windows_test.exe"));  // full path, backslashes
    CHECK(p->path.size() > 3 && p->path[1] == ':');
    CHECK(p->cmdline.find(marker) != std::string::npos);
    CHECK(p->cmdline.find("--child-console") != std::string::npos);
    CHECK(p->parentPid == ::GetCurrentProcessId());
    CHECK(p->identifier.empty());

    std::int64_t now = static_cast<std::int64_t>(std::time(nullptr));
    CHECK(p->startTime > now - 60 && p->startTime <= now + 1);  // Unix seconds, just now
}

void ownProcessChainStartsHere(PlatformAdapter& adapter) {
    auto chain = adapter.ownProcessChain();
    CHECK(!chain.empty());
    if (chain.empty()) return;
    CHECK(chain.front() == ::GetCurrentProcessId());

    auto sorted = chain;
    std::sort(sorted.begin(), sorted.end());
    CHECK(std::adjacent_find(sorted.begin(), sorted.end()) == sorted.end());  // no loops
    CHECK(std::find(chain.begin(), chain.end(), 4u) == chain.end());          // never the System process
}

void reportsANewProcessExactlyOnce(PlatformAdapter& adapter) {
    Child before("--child-console", uniqueMarker("before"));
    CHECK(before.started());

    std::mutex mutex;
    std::vector<ProcessInfo> reported;
    adapter.onProcessStart([&](const ProcessInfo& p) {
        std::lock_guard<std::mutex> lock(mutex);
        reported.push_back(p);
    });

    std::uint32_t newPid = 0;
    std::thread driver([&] {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));  // let run() take its first snapshot
        Child after("--child-console", uniqueMarker("after"));
        newPid = after.pid();

        auto deadline = Clock::now() + std::chrono::seconds(5);
        bool seen = false;
        while (!seen && Clock::now() < deadline) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            std::lock_guard<std::mutex> lock(mutex);
            seen = findPid(reported, newPid) != nullptr;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(2500));  // two more polls: no duplicate
        adapter.stop();
    });

    adapter.run();  // on the main thread, as in the enforcer
    driver.join();
    adapter.onProcessStart(nullptr);

    int timesNew = 0;
    int timesBefore = 0;
    for (const auto& p : reported) {
        if (p.pid == newPid) {
            ++timesNew;
            CHECK(p.detectedBy == "poll");
            CHECK(p.cmdline.find(uniqueMarker("after")) != std::string::npos);
        }
        if (p.pid == before.pid()) ++timesBefore;
    }
    CHECK(timesNew == 1);
    CHECK(timesBefore == 0);  // already running when run() began
}

void killsAWindowlessProcessWithoutWaiting(PlatformAdapter& adapter) {
    Child child("--child-console", uniqueMarker("kill"));
    CHECK(child.started());

    auto start = Clock::now();
    CHECK(adapter.terminate(child.pid(), 5000));
    CHECK(millisSince(start) < 2500);  // nothing to ask politely, so no grace period
    CHECK(child.exited(1000));
    CHECK(child.exitCode() == kForcedExitCode);
}

void closesAWindowPolitely(PlatformAdapter& adapter) {
    Child child("--child-window", uniqueMarker("window"));
    CHECK(child.started());
    CHECK(child.waitForVisibleWindow());

    auto start = Clock::now();
    CHECK(adapter.terminate(child.pid(), 5000));
    CHECK(millisSince(start) < 5000);
    CHECK(child.exited(1000));
    CHECK(child.exitCode() == 0);  // it quit by itself, not by TerminateProcess
}

void escalatesWhenAWindowIgnoresClose(PlatformAdapter& adapter) {
    Child child("--child-stubborn", uniqueMarker("stubborn"));
    CHECK(child.started());
    CHECK(child.waitForVisibleWindow());

    auto start = Clock::now();
    CHECK(adapter.terminate(child.pid(), 1000));
    CHECK(millisSince(start) >= 900);  // it did wait the grace period first
    CHECK(child.exited(1000));
    CHECK(child.exitCode() == kForcedExitCode);
}

void refusesToKillItselfOrTheSystem(PlatformAdapter& adapter) {
    CHECK(!adapter.terminate(::GetCurrentProcessId(), 100));
    CHECK(!adapter.terminate(0, 100));
    CHECK(!adapter.terminate(4, 100));
}

void treatsAnExitedProcessAsClosed(PlatformAdapter& adapter) {
    Child child("--child-console", uniqueMarker("gone"));
    CHECK(child.started());
    HANDLE h = ::OpenProcess(PROCESS_TERMINATE | SYNCHRONIZE, FALSE, child.pid());
    CHECK(h != nullptr);
    if (h) {
        ::TerminateProcess(h, 7);
        ::WaitForSingleObject(h, 2000);
        ::CloseHandle(h);
    }
    CHECK(adapter.terminate(child.pid(), 100));
}

}  // namespace

int main(int argc, char** argv) {
    if (argc >= 2 && std::string(argv[1]).rfind("--child", 0) == 0) return runChild(argv[1]);

    auto adapter = brainrot::createPlatformAdapter();
    listsAChildWithItsDetails(*adapter);
    ownProcessChainStartsHere(*adapter);
    reportsANewProcessExactlyOnce(*adapter);
    killsAWindowlessProcessWithoutWaiting(*adapter);
    closesAWindowPolitely(*adapter);
    escalatesWhenAWindowIgnoresClose(*adapter);
    refusesToKillItselfOrTheSystem(*adapter);
    treatsAnExitedProcessAsClosed(*adapter);

    if (failures == 0) std::cout << "platform adapter tests passed\n";
    return failures == 0 ? 0 : 1;
}
