#pragma once

#include <cstdint>
#include <optional>
#include <vector>

#include "matcher.h"

namespace brainrot {

// What the controller tells the enforcer to do. See shared/protocol.md.
struct Policy {
    bool locked = true;
    std::optional<std::int64_t> unlockedUntil;  // Unix seconds; an emergency unlock expires by itself
    int closeWarningMinutes = 10;
    bool dryRun = true;  // the safe default when a client forgets to say
    std::vector<AppRule> apps;

    bool operator==(const Policy&) const = default;

    // Locked, unless an emergency unlock is still running.
    bool isEnforcing(std::int64_t nowSeconds) const {
        return locked && !(unlockedUntil && nowSeconds < *unlockedUntil);
    }
};

}  // namespace brainrot
