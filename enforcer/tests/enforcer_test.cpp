#include <algorithm>
#include <chrono>
#include <iostream>
#include <set>

#include "enforcer.h"

using brainrot::AppRule;
using brainrot::Enforcer;
using brainrot::Policy;
using brainrot::ProcessInfo;

static int failures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #cond "\n"; \
            ++failures;                                                          \
        }                                                                        \
    } while (0)

namespace {

constexpr std::int64_t kNow = 1000000;

// An in-memory "operating system": terminating a process removes it, and (optionally) its children.
class FakeAdapter : public brainrot::PlatformAdapter {
public:
    std::vector<ProcessInfo> processes;
    std::vector<std::uint32_t> terminated;
    std::vector<std::uint32_t> chain;
    std::set<std::uint32_t> stubborn;  // terminate() fails for these
    bool parentQuitTakesChildren = true;

    std::vector<ProcessInfo> listProcesses() override {
        std::lock_guard<std::mutex> lock(mutex_);
        return processes;
    }
    std::vector<std::uint32_t> ownProcessChain() override { return chain; }
    void onProcessStart(std::function<void(const ProcessInfo&)>) override {}
    void run() override {}
    void stop() override {}

    bool terminate(std::uint32_t pid, int) override {
        std::lock_guard<std::mutex> lock(mutex_);
        terminated.push_back(pid);
        if (stubborn.count(pid)) return false;
        processes.erase(std::remove_if(processes.begin(), processes.end(),
                                       [&](const ProcessInfo& p) {
                                           return p.pid == pid || (parentQuitTakesChildren && p.parentPid == pid);
                                       }),
                        processes.end());
        return true;
    }

    void add(ProcessInfo p) {
        std::lock_guard<std::mutex> lock(mutex_);
        processes.push_back(std::move(p));
    }
    std::vector<std::uint32_t> terminatedSnapshot() {
        std::lock_guard<std::mutex> lock(mutex_);
        return terminated;
    }

private:
    std::mutex mutex_;
};

ProcessInfo proc(std::uint32_t pid, std::uint32_t parent, std::string name, std::string identifier = "") {
    ProcessInfo p;
    p.pid = pid;
    p.parentPid = parent;
    p.name = std::move(name);
    p.path = "/Applications/" + p.name + ".app/Contents/MacOS/" + p.name;
    p.identifier = std::move(identifier);
    return p;
}

Policy discordPolicy(bool dryRun = false) {
    Policy policy;
    policy.locked = true;
    policy.dryRun = dryRun;
    policy.apps.push_back(AppRule{"Discord", "com.hnc.Discord", "", "", {}});
    return policy;
}

// Discord's real shape: a main app, helpers that are its children, and an orphaned crash handler.
void addDiscord(FakeAdapter& os) {
    os.add(proc(100, 1, "Discord", "com.hnc.Discord"));
    os.add(proc(101, 100, "Discord Helper", "com.hnc.Discord"));
    os.add(proc(102, 100, "Discord Helper (Renderer)", "com.hnc.Discord"));
    os.add(proc(103, 1, "chrome_crashpad_handler", "com.hnc.Discord"));
    os.add(proc(200, 1, "Safari", "com.apple.Safari"));
}

struct Rig {
    FakeAdapter os;
    std::vector<std::string> logs;
    std::int64_t now = kNow;
    Enforcer enforcer{os, [this](const std::string& m) { logs.push_back(m); }, [this] { return now; }};

    bool logged(const std::string& part) const {
        return std::any_of(logs.begin(), logs.end(),
                           [&](const std::string& l) { return l.find(part) != std::string::npos; });
    }
};

bool contains(const std::vector<std::uint32_t>& v, std::uint32_t pid) {
    return std::find(v.begin(), v.end(), pid) != v.end();
}

void closesTheAppFirstAndLeavesItsHelpersToIt() {
    Rig r;
    addDiscord(r.os);
    r.enforcer.setPolicy(discordPolicy());
    r.enforcer.scanOnce();

    // Roots only, in order; the helpers disappeared with their parent and were never touched.
    CHECK((r.os.terminated == std::vector<std::uint32_t>{100, 103}));
    CHECK(r.os.processes.size() == 1 && r.os.processes[0].name == "Safari");
}

void sweepsUpHelpersThatOutliveTheirApp() {
    Rig r;
    r.os.parentQuitTakesChildren = false;
    addDiscord(r.os);
    r.enforcer.setPolicy(discordPolicy());
    r.enforcer.scanOnce();

    CHECK(r.os.terminated.size() == 4);
    CHECK(r.os.terminated[0] == 100);  // the app before any helper
    CHECK(contains(r.os.terminated, 101) && contains(r.os.terminated, 102));
    CHECK(!contains(r.os.terminated, 200));
    CHECK(r.logged("closed leftover Discord Helper"));
}

void doesNothingWhileUnlockedOrWithoutAPolicy() {
    Rig r;
    addDiscord(r.os);

    r.enforcer.scanOnce();  // no policy yet
    CHECK(r.os.terminated.empty());

    Policy unlocked = discordPolicy();
    unlocked.locked = false;
    r.enforcer.setPolicy(unlocked);
    r.enforcer.scanOnce();
    CHECK(r.os.terminated.empty());
}

void emergencyUnlockSuspendsEnforcementUntilItExpires() {
    Rig r;
    addDiscord(r.os);
    Policy policy = discordPolicy();
    policy.unlockedUntil = kNow + 3600;
    r.enforcer.setPolicy(policy);

    r.enforcer.scanOnce();
    CHECK(r.os.terminated.empty());

    r.now = kNow + 3600;  // the unlock has run out
    r.enforcer.scanOnce();
    CHECK(!r.os.terminated.empty());
}

void dryRunOnlyLogsAndLogsEachProcessOnce() {
    Rig r;
    addDiscord(r.os);
    r.enforcer.setPolicy(discordPolicy(true));
    r.enforcer.scanOnce();
    size_t afterFirst = r.logs.size();
    r.enforcer.scanOnce();

    CHECK(r.os.terminated.empty());
    CHECK(afterFirst == 4);  // main app, two helpers, crash handler
    CHECK(r.logs.size() == afterFirst);
    CHECK(r.logged("[dry run] would close Discord (pid 100)"));
}

void protectedProcessesAreSkippedEvenWhenTheyMatch() {
    FakeAdapter os;
    os.chain = {101};  // pretend the enforcer was launched from a Discord helper
    os.parentQuitTakesChildren = false;  // so the leftover sweep would reach pid 101 if unprotected
    addDiscord(os);
    os.add(proc(300, 1, "Terminal", "com.apple.Terminal"));

    std::vector<std::string> logs;
    Enforcer enforcer(os, [&](const std::string& m) { logs.push_back(m); }, [] { return kNow; });
    Policy policy = discordPolicy();
    policy.apps.push_back(AppRule{"Terminal", "com.apple.Terminal", "", "", {}});
    enforcer.setPolicy(policy);
    enforcer.scanOnce();

    CHECK(contains(os.terminated, 100));   // the rule still works on everything else
    CHECK(contains(os.terminated, 102));
    CHECK(!contains(os.terminated, 300));  // Terminal is on the protection list
    CHECK(!contains(os.terminated, 101));  // in our own process chain
}

void reportsProcessesThatWillNotQuit() {
    Rig r;
    r.os.stubborn = {100};
    addDiscord(r.os);
    r.enforcer.setPolicy(discordPolicy());
    r.enforcer.scanOnce();

    CHECK(r.logged("FAILED to close Discord (pid 100)"));
}

void workerThreadClosesAppsThatLaunchWhileLocked() {
    Rig r;
    r.enforcer.start();
    r.enforcer.setPolicy(discordPolicy());  // nothing running yet

    addDiscord(r.os);
    r.enforcer.onProcessStarted(r.os.processes[0]);
    r.enforcer.onProcessStarted(proc(200, 1, "Safari", "com.apple.Safari"));  // irrelevant launch

    for (int i = 0; i < 100 && r.os.terminatedSnapshot().empty(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    r.enforcer.stop();
    CHECK(contains(r.os.terminatedSnapshot(), 100));
    CHECK(!contains(r.os.terminatedSnapshot(), 200));
}

void workerThreadClosesAppsAlreadyRunningWhenALockStarts() {
    Rig r;
    addDiscord(r.os);
    r.enforcer.start();
    r.enforcer.setPolicy(discordPolicy());

    for (int i = 0; i < 100 && r.os.terminatedSnapshot().empty(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    r.enforcer.stop();
    CHECK(contains(r.os.terminatedSnapshot(), 100));
}

void stopsCleanlyEvenIfNeverStarted() {
    Rig r;
    r.enforcer.stop();
    r.enforcer.start();
    r.enforcer.stop();
    r.enforcer.stop();
}

}  // namespace

int main() {
    closesTheAppFirstAndLeavesItsHelpersToIt();
    sweepsUpHelpersThatOutliveTheirApp();
    doesNothingWhileUnlockedOrWithoutAPolicy();
    emergencyUnlockSuspendsEnforcementUntilItExpires();
    dryRunOnlyLogsAndLogsEachProcessOnce();
    protectedProcessesAreSkippedEvenWhenTheyMatch();
    reportsProcessesThatWillNotQuit();
    workerThreadClosesAppsThatLaunchWhileLocked();
    workerThreadClosesAppsAlreadyRunningWhenALockStarts();
    stopsCleanlyEvenIfNeverStarted();

    if (failures == 0) std::cout << "enforcer tests passed\n";
    return failures == 0 ? 0 : 1;
}
