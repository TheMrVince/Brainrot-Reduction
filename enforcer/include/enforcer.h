#pragma once

#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <unordered_set>
#include <vector>

#include "matcher.h"
#include "platform_adapter.h"
#include "policy.h"

namespace brainrot {

// Applies the current policy: closes blocked apps that are running when a lock starts and
// any that launch while it lasts. Closing happens on a worker thread so a slow-quitting app
// never stalls launch detection.
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

    // Replaces the policy and rescans. Without any policy nothing is enforced.
    void setPolicy(Policy policy);

    // Cheap; call for every process start the adapter reports.
    void onProcessStarted(const ProcessInfo& process);

    // One synchronous pass over the running processes. The worker thread calls this;
    // it is public so tests can drive it directly.
    void scanOnce();

private:
    void workerLoop();
    void requestScan();
    void closeInOrder(const std::vector<ProcessInfo>& targets, const Matcher& matcher);

    PlatformAdapter& adapter_;
    Log log_;
    Clock now_;
    std::vector<std::uint32_t> ownChain_;

    std::mutex mutex_;
    std::condition_variable wake_;
    std::optional<Policy> policy_;
    std::unordered_set<std::uint32_t> dryRunLogged_;
    bool scanRequested_ = false;
    bool stopping_ = false;
    std::thread worker_;
};

}  // namespace brainrot
