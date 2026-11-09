#include "message_handler.h"

#include <nlohmann/json.hpp>

#include "policy_json.h"

namespace brainrot {
namespace {

using json = nlohmann::json;

std::string dump(const json& j) {
    return j.dump(-1, ' ', false, json::error_handler_t::replace);
}

std::string error(const std::string& message) {
    return dump({{"event", "error"}, {"message", message}});
}

std::string ack(const std::string& cmd) {
    return dump({{"event", "ack"}, {"cmd", cmd}});
}

}  // namespace

MessageHandler::MessageHandler(PolicyListener onPolicy) : onPolicy_(std::move(onPolicy)) {}

std::string MessageHandler::handle(const std::string& line) const {
    try {
        json message = json::parse(line);
        if (!message.is_object()) return error("message must be a JSON object");

        auto cmd = message.find("cmd");
        if (cmd == message.end() || !cmd->is_string()) return error("cmd (string) is required");
        const std::string name = cmd->get<std::string>();

        if (name == "ping") return ack(name);

        if (name == "set_policy") {
            std::string problem;
            std::optional<Policy> policy = parsePolicyJson(line, problem);
            if (!policy) return error(problem);
            if (onPolicy_) onPolicy_(*policy);
            return ack(name);
        }
        return error("unknown cmd: " + name);
    } catch (const json::parse_error&) {
        return error("invalid JSON");
    } catch (const json::exception& e) {
        return error(e.what());
    }
}

}  // namespace brainrot
