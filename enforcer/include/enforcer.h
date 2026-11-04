#pragma once

#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "matcher.h"
#include "platform_adapter.h"
#include "policy.h"

namespace brainrot {

// Applies the current policy.
//
//  - A blocked app that was already running when the lock began is warned about, then closed
//    when closeWarningMinutes have passed (immediately if that is 0).
//  - A blocked app that starts while the lock is on is closed straight away.
//  - Nothing is enforced without a policy, while unlocked, or during an emergency unlock; when
//    the unlock runs out the lock begins and the same rules apply.
//
// All work happens on a worker thread so a slow-quitting app never stalls launch detection.
class Enforcer {
public:
    using Log = std::function<void(const std::string&)>;
    using Clock = std::function<std::int64_t()>;  // Unix seconds

    Enforcer(PlatformAdapter& adapter, Log log, Clock now);
    ~Enforcer();

    Enforcer(const Enforcer&) = delete;
    Enforcer& operator=(const Enforcer&) = delete;

    void start();
    void stop();

    // Replaces the policy and rescans. Re-sending an unchanged policy does not restart a countdown.
    void setPolicy(Policy policy);

    // Cheap; call for every process start the adapter reports.
    void onProcessStarted(const ProcessInfo& process);

    // One synchronous pass over the running processes. The worker thread calls this; it is
    // public so tests can drive it with a fake clock.
    void scanOnce();

private:
    void workerLoop();
    void requestScan();
    std::int64_t secondsUntilNextEvent();  // requires mutex_
    void refreshEnforcingLocked(std::int64_t now);
    void closeInOrder(const std::vector<ProcessInfo>& targets, const Matcher& matcher,
                      const std::unordered_set<std::uint32_t>& stillCountingDown);

    PlatformAdapter& adapter_;
    Log log_;
    Clock now_;
    std::vector<std::uint32_t> ownChain_;

    std::mutex mutex_;
    std::condition_variable wake_;
    std::optional<Policy> policy_;
    bool wasEnforcing_ = false;
    std::int64_t enforcingSince_ = 0;                       // when the current lock began
    std::unordered_map<std::uint32_t, std::int64_t> closeAt_;  // pid -> deadline, for warned apps
    std::unordered_set<std::uint32_t> dryRunLogged_;
    bool scanRequested_ = false;
    bool stopping_ = false;
    std::thread worker_;
};

}  // namespace brainrot
