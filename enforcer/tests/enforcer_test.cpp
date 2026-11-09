#include <algorithm>
#include <atomic>
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
constexpr std::int64_t kLongAgo = 100;  // a start time well before any lock

// An in-memory "operating system": terminating a process removes it, and (optionally) its children.
class FakeAdapter : public brainrot::PlatformAdapter {
public:
    std::vector<ProcessInfo> processes;
    std::vector<std::uint32_t> terminated;
    std::vector<std::string> notifications;
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

    void notify(const std::string& title, const std::string& message) override {
        std::lock_guard<std::mutex> lock(mutex_);
        notifications.push_back(title + ": " + message);
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

ProcessInfo proc(std::uint32_t pid, std::uint32_t parent, std::string name, std::string identifier = "",
                 std::int64_t startTime = kLongAgo) {
    ProcessInfo p;
    p.pid = pid;
    p.parentPid = parent;
    p.startTime = startTime;
    p.name = std::move(name);
    p.path = "/Applications/" + p.name + ".app/Contents/MacOS/" + p.name;
    p.identifier = std::move(identifier);
    return p;
}

Policy discordPolicy(int warningMinutes = 0, bool dryRun = false) {
    Policy policy;
    policy.locked = true;
    policy.dryRun = dryRun;
    policy.closeWarningMinutes = warningMinutes;
    policy.apps.push_back(AppRule{"Discord", "com.hnc.Discord", "", "", {}});
    return policy;
}

// Discord's real shape: a main app, helpers that are its children, and an orphaned crash handler.
void addDiscord(FakeAdapter& os, std::int64_t startTime = kLongAgo) {
    os.add(proc(100, 1, "Discord", "com.hnc.Discord", startTime));
    os.add(proc(101, 100, "Discord Helper", "com.hnc.Discord", startTime));
    os.add(proc(102, 100, "Discord Helper (Renderer)", "com.hnc.Discord", startTime));
    os.add(proc(103, 1, "chrome_crashpad_handler", "com.hnc.Discord", startTime));
    os.add(proc(200, 1, "Safari", "com.apple.Safari", startTime));
}

struct Rig {
    FakeAdapter os;
    std::vector<std::string> logs;
    std::atomic<std::int64_t> now{kNow};
    Enforcer enforcer{os, [this](const std::string& m) { logs.push_back(m); }, [this] { return now.load(); }};

    bool logged(const std::string& part) const {
        return std::any_of(logs.begin(), logs.end(),
                           [&](const std::string& l) { return l.find(part) != std::string::npos; });
    }
    void scanAt(std::int64_t time) {
        now = time;
        enforcer.scanOnce();
    }
};

bool contains(const std::vector<std::uint32_t>& v, std::uint32_t pid) {
    return std::find(v.begin(), v.end(), pid) != v.end();
}

bool mentions(const std::string& text, const std::string& part) {
    return text.find(part) != std::string::npos;
}

void waitUntil(const std::function<bool()>& condition, int timeoutMs = 4000) {
    for (int waited = 0; waited < timeoutMs && !condition(); waited += 20) {
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
}

// --- closing --------------------------------------------------------------------------

void closesTheAppFirstAndLeavesItsHelpersToIt() {
    Rig r;
    addDiscord(r.os);
    r.enforcer.setPolicy(discordPolicy());
    r.enforcer.scanOnce();

    // Roots only, in order; the helpers disappeared with their parent and were never touched.
    CHECK((r.os.terminated == std::vector<std::uint32_t>{100, 103}));
    CHECK(r.os.processes.size() == 1 && r.os.processes[0].name == "Safari");
    CHECK(r.os.notifications.size() == 1 && mentions(r.os.notifications[0], "Discord closed"));
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

// --- when to enforce ------------------------------------------------------------------

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
    CHECK(r.os.notifications.empty());
}

void emergencyUnlockSuspendsEnforcementUntilItExpires() {
    Rig r;
    addDiscord(r.os);
    Policy policy = discordPolicy();
    policy.unlockedUntil = kNow + 3600;
    r.enforcer.setPolicy(policy);

    r.scanAt(kNow);
    CHECK(r.os.terminated.empty());

    r.scanAt(kNow + 3600);  // the unlock has run out
    CHECK(!r.os.terminated.empty());
}

void dryRunOnlyLogsAndLogsEachProcessOnce() {
    Rig r;
    addDiscord(r.os);
    r.enforcer.setPolicy(discordPolicy(10, true));
    r.enforcer.scanOnce();
    size_t afterFirst = r.logs.size();
    r.enforcer.scanOnce();

    CHECK(r.os.terminated.empty());
    CHECK(r.os.notifications.empty());
    CHECK(afterFirst == 4);  // main app, two helpers, crash handler
    CHECK(r.logs.size() == afterFirst);
    CHECK(r.logged("[dry run] would close Discord (pid 100)"));
}

void resendingTheSamePolicyDoesNotRepeatTheDryRunLog() {
    Rig r;
    addDiscord(r.os);
    r.enforcer.setPolicy(discordPolicy(10, true));
    r.enforcer.scanOnce();
    size_t afterFirst = r.logs.size();

    for (int i = 0; i < 3; ++i) {  // the controller's heartbeat
        r.enforcer.setPolicy(discordPolicy(10, true));
        r.enforcer.scanOnce();
    }
    CHECK(r.logs.size() == afterFirst);

    r.enforcer.setPolicy(discordPolicy(5, true));  // a real change reports again
    r.enforcer.scanOnce();
    CHECK(r.logs.size() == afterFirst * 2);
}

// --- the warning ----------------------------------------------------------------------

void appsRunningWhenTheLockStartsAreWarnedThenClosed() {
    Rig r;
    addDiscord(r.os);
    r.enforcer.setPolicy(discordPolicy(10));

    r.scanAt(kNow);
    CHECK(r.os.terminated.empty());
    CHECK(r.os.notifications.size() == 1);
    CHECK(mentions(r.os.notifications[0], "Discord closes in 10 minutes"));
    CHECK(r.logged("warning: Discord will close in 10 minutes"));

    r.scanAt(kNow + 599);
    CHECK(r.os.terminated.empty());
    CHECK(r.os.notifications.size() == 1);  // warned once, not on every scan

    r.scanAt(kNow + 600);
    CHECK((r.os.terminated == std::vector<std::uint32_t>{100, 103}));
    CHECK(r.os.notifications.size() == 2 && mentions(r.os.notifications[1], "Discord closed"));
}

void resendingThePolicyDoesNotRestartTheCountdown() {
    Rig r;
    addDiscord(r.os);
    r.enforcer.setPolicy(discordPolicy(10));
    r.scanAt(kNow);

    r.now = kNow + 300;
    r.enforcer.setPolicy(discordPolicy(10));  // the controller resends on every poll
    r.enforcer.scanOnce();
    CHECK(r.os.notifications.size() == 1);

    r.scanAt(kNow + 599);
    CHECK(r.os.terminated.empty());
    r.scanAt(kNow + 600);
    CHECK(!r.os.terminated.empty());
}

void appsLaunchedWhileLockedCloseImmediately() {
    Rig r;
    r.enforcer.setPolicy(discordPolicy(10));
    r.scanAt(kNow);

    addDiscord(r.os, kNow + 5);  // started after the lock began
    r.scanAt(kNow + 5);

    CHECK(contains(r.os.terminated, 100));
    CHECK(r.os.notifications.size() == 1 && mentions(r.os.notifications[0], "Discord closed"));
    CHECK(!r.logged("warning:"));
}

void aNewHelperOfAnAppCountingDownWaitsForItsApp() {
    Rig r;
    // The helper is listed before its app on purpose: the outcome must not depend on list order.
    r.os.processes = {proc(110, 100, "Discord Helper", "com.hnc.Discord", kNow + 60),
                      proc(100, 1, "Discord", "com.hnc.Discord", kLongAgo)};
    r.enforcer.setPolicy(discordPolicy(10));
    r.scanAt(kNow);
    CHECK(r.os.notifications.size() == 1);

    r.os.parentQuitTakesChildren = false;
    r.scanAt(kNow + 61);
    CHECK(r.os.terminated.empty());  // the helper did not get closed early

    r.scanAt(kNow + 600);
    CHECK(contains(r.os.terminated, 100));
    CHECK(contains(r.os.terminated, 110));
}

void unlockingDuringTheCountdownCancelsItAndRelockingStartsAFreshOne() {
    Rig r;
    addDiscord(r.os);
    r.enforcer.setPolicy(discordPolicy(10));
    r.scanAt(kNow);

    Policy solved = discordPolicy(10);
    solved.locked = false;
    r.now = kNow + 100;
    r.enforcer.setPolicy(solved);
    r.scanAt(kNow + 700);
    CHECK(r.os.terminated.empty());  // the old deadline passed while unlocked

    r.now = kNow + 800;
    r.enforcer.setPolicy(discordPolicy(10));
    r.scanAt(kNow + 800);
    CHECK(r.os.terminated.empty());
    CHECK(r.os.notifications.size() == 2);  // a fresh warning

    r.scanAt(kNow + 1399);
    CHECK(r.os.terminated.empty());
    r.scanAt(kNow + 1400);
    CHECK(!r.os.terminated.empty());
}

void anExpiringUnlockStartsALockWithTheUsualWarning() {
    Rig r;
    addDiscord(r.os);
    r.os.add(proc(150, 1, "Discord Late", "com.hnc.Discord", kNow + 50));  // started during the unlock
    Policy policy = discordPolicy(10);
    policy.unlockedUntil = kNow + 100;
    r.enforcer.setPolicy(policy);

    r.scanAt(kNow + 50);
    CHECK(r.os.terminated.empty() && r.os.notifications.empty());

    r.scanAt(kNow + 100);  // the unlock ends: running apps get the warning, not the axe
    CHECK(r.os.terminated.empty());
    CHECK(r.os.notifications.size() == 1);

    r.scanAt(kNow + 699);
    CHECK(r.os.terminated.empty());
    r.scanAt(kNow + 700);
    CHECK(contains(r.os.terminated, 100));
}

// --- the worker thread ----------------------------------------------------------------

void workerThreadClosesAppsThatLaunchWhileLocked() {
    Rig r;
    r.enforcer.start();
    r.enforcer.setPolicy(discordPolicy(10));  // nothing running yet

    addDiscord(r.os, kNow + 5);
    r.enforcer.onProcessStarted(r.os.processes[0]);
    r.enforcer.onProcessStarted(proc(201, 1, "Safari", "com.apple.Safari"));  // irrelevant launch

    waitUntil([&] { return !r.os.terminatedSnapshot().empty(); });
    r.enforcer.stop();
    CHECK(contains(r.os.terminatedSnapshot(), 100));
    CHECK(!contains(r.os.terminatedSnapshot(), 200));
}

void workerThreadClosesAppsAlreadyRunningWhenALockStarts() {
    Rig r;
    addDiscord(r.os);
    r.enforcer.start();
    r.enforcer.setPolicy(discordPolicy(0));

    waitUntil([&] { return !r.os.terminatedSnapshot().empty(); });
    r.enforcer.stop();
    CHECK(contains(r.os.terminatedSnapshot(), 100));
}

void workerThreadWakesByItselfWhenAnUnlockExpires() {
    Rig r;
    addDiscord(r.os);
    Policy policy = discordPolicy(0);
    policy.unlockedUntil = kNow + 1;
    r.enforcer.start();
    r.enforcer.setPolicy(policy);

    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    CHECK(r.os.terminatedSnapshot().empty());

    r.now = kNow + 5;  // time passes; nothing else happens, no launch and no new policy
    waitUntil([&] { return !r.os.terminatedSnapshot().empty(); });
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
    protectedProcessesAreSkippedEvenWhenTheyMatch();
    reportsProcessesThatWillNotQuit();
    doesNothingWhileUnlockedOrWithoutAPolicy();
    emergencyUnlockSuspendsEnforcementUntilItExpires();
    dryRunOnlyLogsAndLogsEachProcessOnce();
    resendingTheSamePolicyDoesNotRepeatTheDryRunLog();
    appsRunningWhenTheLockStartsAreWarnedThenClosed();
    resendingThePolicyDoesNotRestartTheCountdown();
    appsLaunchedWhileLockedCloseImmediately();
    aNewHelperOfAnAppCountingDownWaitsForItsApp();
    unlockingDuringTheCountdownCancelsItAndRelockingStartsAFreshOne();
    anExpiringUnlockStartsALockWithTheUsualWarning();
    workerThreadClosesAppsThatLaunchWhileLocked();
    workerThreadClosesAppsAlreadyRunningWhenALockStarts();
    workerThreadWakesByItselfWhenAnUnlockExpires();
    stopsCleanlyEvenIfNeverStarted();

    if (failures == 0) std::cout << "enforcer tests passed\n";
    return failures == 0 ? 0 : 1;
}
