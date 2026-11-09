#pragma once

#include <optional>
#include <string>

#include "policy.h"

namespace brainrot {

// Parses a JSON object holding policy fields. Unknown keys (such as "cmd") are ignored.
// On failure returns nullopt and describes the problem in `error`.
std::optional<Policy> parsePolicyJson(const std::string& text, std::string& error);

std::string policyToJson(const Policy& policy);

}  // namespace brainrot
