#pragma once

#include <cstdint>
#include <string>
#include <unordered_set>
#include <vector>

#include "platform_adapter.h"

namespace brainrot {

// A process matches a rule if ANY criterion that is set matches. A rule with no criteria
// matches nothing. All comparisons are case-insensitive.
struct AppRule {
    std::string label;         // display only
    std::string bundleId;      // matches the id itself and its helpers ("<id>.*")
    std::string processName;   // exact
    std::string pathPrefix;    // executable path starts with this
    // Every entry must appear in the command line. Distinguishes e.g. one Java-based
    // game from every other java process.
    std::vector<std::string> cmdlineContains;

    bool operator==(const AppRule&) const = default;
};

class Matcher {
public:
    // protectedPids: processes that must never be killed (the enforcer and its ancestors).
    Matcher(std::vector<AppRule> rules, std::vector<std::uint32_t> protectedPids);

    // The first rule the process matches, or nullptr. Ignores protection.
    const AppRule* matchRule(const ProcessInfo& process) const;

    // True for system essentials, dev tools, the controller and our own process chain.
    // Wins over any rule.
    bool isProtected(const ProcessInfo& process) const;

    // The rule to block this process under, or nullptr if no rule matches or it is protected.
    const AppRule* shouldBlock(const ProcessInfo& process) const;

private:
    std::vector<AppRule> rules_;
    std::unordered_set<std::uint32_t> protectedPids_;
};

}  // namespace brainrot
