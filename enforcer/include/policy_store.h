#pragma once

#include <optional>
#include <string>

#include "policy.h"

namespace brainrot {

// The last accepted policy on disk, so a restarted enforcer keeps enforcing before the
// controller reconnects.
class PolicyStore {
public:
    explicit PolicyStore(std::string file);

    // Returns an empty string on success. Writes atomically: a crash never leaves a half file.
    std::string save(const Policy& policy) const;

    // nullopt if there is no file or it is unusable; `problem` then says which (empty for "no file").
    std::optional<Policy> load(std::string& problem) const;

private:
    std::string file_;
};

}  // namespace brainrot
