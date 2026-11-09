#include "enforcer.h"

#include <algorithm>
#include <chrono>

namespace brainrot {
namespace {

// A quitting app gets this long to save and exit before it is force-killed.
constexpr int kAppGraceMs = 5000;
// Leftover helpers have no unsaved work worth waiting for.
constexpr int kLeftoverGraceMs = 1500;
// Longest the worker sleeps with nothing scheduled; also bounds the damage of a clock jump.
constexpr std::int64_t kMaxIdleSeconds = 30;
constexpr int kMaxAncestorDepth = 16;

const char* const kNotificationTitle = "Brainrot Reduction";

std::string describe(const ProcessInfo& p) {
    return p.name + " (pid " + std::to_string(p.pid) + ")";
}

std::string labelFor(const Matcher& matcher, const ProcessInfo& p) {
    const AppRule* rule = matcher.matchRule(p);
    return (rule && !rule->label.empty()) ? rule->label : p.name;
}

std::vector<std::string> uniqueSorted(std::vector<std::string> names) {
    std::sort(names.begin(), names.end());
    names.erase(std::unique(names.begin(), names.end()), names.end());
    return names;
}

std::string join(const std::vector<std::string>& names) {
    std::string out;
    for (size_t i = 0; i < names.size(); ++i) out += (i ? ", " : "") + names[i];
    return out;
}

}  // namespace

Enforcer::Enforcer(PlatformAdapter& adapter, Log log, Clock now)
    : adapter_(adapter), log_(std::move(log)), now_(std::move(now)), ownChain_(adapter.ownProcessChain()) {}

Enforcer::~Enforcer() {
    stop();
}

void Enforcer::start() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (worker_.joinable()) return;
    stopping_ = false;
    worker_ = std::thread([this] { workerLoop(); });
}

void Enforcer::stop() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!worker_.joinable()) return;
        stopping_ = true;
    }
    wake_.notify_all();
    worker_.join();
}

void Enforcer::setPolicy(Policy policy) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        // The controller re-sends the same policy every minute; that must not repeat the dry-run log.
        if (!policy_ || !(*policy_ == policy)) dryRunLogged_.clear();
        policy_ = std::move(policy);
        refreshEnforcingLocked(now_());
    }
    requestScan();
}

void Enforcer::onProcessStarted(const ProcessInfo& process) {
    std::optional<Policy> policy;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        policy = policy_;
    }
    if (!policy || !policy->isEnforcing(now_())) return;

    if (Matcher(policy->apps, ownChain_).shouldBlock(process)) requestScan();
}

void Enforcer::requestScan() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        scanRequested_ = true;
    }
    wake_.notify_one();
}

// Tracks when a lock begins (an unlock expiring counts as one) so apps running before that
// moment can be told apart from apps launched after it.
void Enforcer::refreshEnforcingLocked(std::int64_t now) {
    bool enforcing = policy_ && policy_->isEnforcing(now);
    if (enforcing && !wasEnforcing_) {
        bool unlockJustExpired = policy_->unlockedUntil && *policy_->unlockedUntil <= now;
        enforcingSince_ = unlockJustExpired ? *policy_->unlockedUntil : now;
    }
    if (!enforcing) closeAt_.clear();
    wasEnforcing_ = enforcing;
}

std::int64_t Enforcer::secondsUntilNextEvent() {
    const std::int64_t now = now_();
    std::int64_t wait = kMaxIdleSeconds;
    if (policy_ && policy_->locked && policy_->unlockedUntil && *policy_->unlockedUntil > now) {
        wait = std::min(wait, *policy_->unlockedUntil - now);
    }
    for (const auto& entry : closeAt_) {
        if (entry.second > now) wait = std::min(wait, entry.second - now);
    }
    return std::max<std::int64_t>(wait, 1);
}

void Enforcer::workerLoop() {
    std::unique_lock<std::mutex> lock(mutex_);
    while (true) {
        wake_.wait_for(lock, std::chrono::seconds(secondsUntilNextEvent()),
                       [this] { return stopping_ || scanRequested_; });
        if (stopping_) return;
        scanRequested_ = false;

        lock.unlock();
        scanOnce();
        lock.lock();
    }
}

void Enforcer::scanOnce() {
    const std::int64_t now = now_();
    std::optional<Policy> policy;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        refreshEnforcingLocked(now);
        policy = policy_;
    }
    if (!policy || !policy->isEnforcing(now)) return;

    Matcher matcher(policy->apps, ownChain_);
    std::vector<ProcessInfo> targets;
    for (const ProcessInfo& p : adapter_.listProcesses()) {
        if (matcher.shouldBlock(p)) targets.push_back(p);
    }

    if (policy->dryRun) {
        std::lock_guard<std::mutex> lock(mutex_);
        for (const ProcessInfo& p : targets) {
            if (dryRunLogged_.insert(p.pid).second) log_("[dry run] would close " + describe(p));
        }
        return;
    }

    std::vector<ProcessInfo> closeNow;
    std::unordered_set<std::uint32_t> countingDown;
    std::vector<std::string> newlyWarned;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        std::unordered_map<std::uint32_t, const ProcessInfo*> byPid;
        for (const ProcessInfo& p : targets) byPid[p.pid] = &p;
        for (auto it = closeAt_.begin(); it != closeAt_.end();) {
            it = byPid.count(it->first) ? std::next(it) : closeAt_.erase(it);  // the process is gone
        }

        std::vector<const ProcessInfo*> launchedWhileLocked;
        for (const ProcessInfo& p : targets) {
            auto warned = closeAt_.find(p.pid);
            if (warned != closeAt_.end()) {
                if (now >= warned->second) closeNow.push_back(p);
                else countingDown.insert(p.pid);
                continue;
            }

            bool wasRunningBeforeLock = p.startTime == 0 || p.startTime < enforcingSince_;
            if (!wasRunningBeforeLock) {
                launchedWhileLocked.push_back(&p);
            } else if (policy->closeWarningMinutes <= 0) {
                closeNow.push_back(p);
            } else {
                closeAt_[p.pid] = now + static_cast<std::int64_t>(policy->closeWarningMinutes) * 60;
                countingDown.insert(p.pid);
                newlyWarned.push_back(labelFor(matcher, p));
            }
        }

        // A new helper of an app that is still counting down must wait for its app.
        // Decided after the loop above so the order of the process list does not matter.
        for (const ProcessInfo* p : launchedWhileLocked) {
            bool appStillCountingDown = false;
            std::uint32_t ancestor = p->parentPid;
            for (int depth = 0; depth < kMaxAncestorDepth && !appStillCountingDown; ++depth) {
                auto found = byPid.find(ancestor);
                if (found == byPid.end()) break;
                auto deadline = closeAt_.find(ancestor);
                appStillCountingDown = deadline != closeAt_.end() && deadline->second > now;
                ancestor = found->second->parentPid;
            }
            if (appStillCountingDown) countingDown.insert(p->pid);
            else closeNow.push_back(*p);
        }
    }

    if (!newlyWarned.empty()) {
        std::vector<std::string> apps = uniqueSorted(newlyWarned);  // one app is many processes
        std::string minutes = std::to_string(policy->closeWarningMinutes) +
                              (policy->closeWarningMinutes == 1 ? " minute" : " minutes");
        log_("warning: " + join(apps) + " will close in " + minutes);
        adapter_.notify(kNotificationTitle, join(apps) + (apps.size() == 1 ? " closes" : " close") + " in " +
                                                minutes + ". Solve a LeetCode problem to keep using it.");
    }

    if (closeNow.empty()) return;
    std::vector<std::string> closedApps;
    for (const ProcessInfo& p : closeNow) closedApps.push_back(labelFor(matcher, p));
    closeInOrder(closeNow, matcher, countingDown);
    adapter_.notify(kNotificationTitle,
                    join(uniqueSorted(closedApps)) + " closed. Solve a LeetCode problem to use it again.");
}

// Quit the app itself first so it can shut its own helpers down cleanly; only then sweep up
// whatever is left (orphans, helpers that ignored the app quitting). Processes still counting
// down are never swept.
void Enforcer::closeInOrder(const std::vector<ProcessInfo>& targets, const Matcher& matcher,
                            const std::unordered_set<std::uint32_t>& stillCountingDown) {
    std::unordered_set<std::uint32_t> targetPids;
    for (const ProcessInfo& p : targets) targetPids.insert(p.pid);

    for (const ProcessInfo& p : targets) {
        if (targetPids.count(p.parentPid)) continue;  // a helper: its app is being closed
        bool ok = adapter_.terminate(p.pid, kAppGraceMs);
        log_(std::string(ok ? "closed " : "FAILED to close ") + describe(p));
    }

    for (const ProcessInfo& p : adapter_.listProcesses()) {
        if (!matcher.shouldBlock(p) || stillCountingDown.count(p.pid)) continue;
        bool ok = adapter_.terminate(p.pid, kLeftoverGraceMs);
        log_(std::string(ok ? "closed leftover " : "FAILED to close leftover ") + describe(p));
    }
}

}  // namespace brainrot
