#pragma once

#include <functional>
#include <string>

#include "policy.h"

namespace brainrot {

// Turns one protocol line (a JSON object) into one response line. Knows nothing about sockets.
class MessageHandler {
public:
    using PolicyListener = std::function<void(const Policy&)>;

    // The listener runs only for valid set_policy messages, possibly from several threads.
    explicit MessageHandler(PolicyListener onPolicy);

    // Never throws. Invalid input yields an {"event":"error"} response and changes nothing.
    std::string handle(const std::string& line) const;

private:
    PolicyListener onPolicy_;
};

}  // namespace brainrot
