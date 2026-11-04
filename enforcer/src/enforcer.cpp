#include "enforcer.h"

namespace brainrot {
namespace {

// A quitting app gets this long to save and exit before it is force-killed.
constexpr int kAppGraceMs = 5000;
// Leftover helpers have no unsaved work worth waiting for.
constexpr int kLeftoverGraceMs = 1500;

std::string describe(const ProcessInfo& p) {
    return p.name + " (pid " + std::to_string(p.pid) + ")";
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
        policy_ = std::move(policy);
        dryRunLogged_.clear();
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

void Enforcer::workerLoop() {
    std::unique_lock<std::mutex> lock(mutex_);
    while (true) {
        wake_.wait(lock, [this] { return stopping_ || scanRequested_; });
        if (stopping_) return;
        scanRequested_ = false;

        lock.unlock();
        scanOnce();
        lock.lock();
    }
}

void Enforcer::scanOnce() {
    std::optional<Policy> policy;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        policy = policy_;
    }
    if (!policy || !policy->isEnforcing(now_())) return;

    Matcher matcher(policy->apps, ownChain_);
    std::vector<ProcessInfo> targets;
    for (const ProcessInfo& p : adapter_.listProcesses()) {
        if (matcher.shouldBlock(p)) targets.push_back(p);
    }
    if (targets.empty()) return;

    if (policy->dryRun) {
        std::lock_guard<std::mutex> lock(mutex_);
        for (const ProcessInfo& p : targets) {
            if (dryRunLogged_.insert(p.pid).second) log_("[dry run] would close " + describe(p));
        }
        return;
    }
    closeInOrder(targets, matcher);
}

// Quit the app itself first so it can shut its own helpers down cleanly; only then sweep up
// whatever is left (orphans, helpers that ignored the app quitting).
void Enforcer::closeInOrder(const std::vector<ProcessInfo>& targets, const Matcher& matcher) {
    std::unordered_set<std::uint32_t> targetPids;
    for (const ProcessInfo& p : targets) targetPids.insert(p.pid);

    for (const ProcessInfo& p : targets) {
        if (targetPids.count(p.parentPid)) continue;  // a helper: its app is being closed
        bool ok = adapter_.terminate(p.pid, kAppGraceMs);
        log_(std::string(ok ? "closed " : "FAILED to close ") + describe(p));
    }

    for (const ProcessInfo& p : adapter_.listProcesses()) {
        if (!matcher.shouldBlock(p)) continue;
        bool ok = adapter_.terminate(p.pid, kLeftoverGraceMs);
        log_(std::string(ok ? "closed leftover " : "FAILED to close leftover ") + describe(p));
    }
}

}  // namespace brainrot
