#include "message_handler.h"

#include <nlohmann/json.hpp>

#include <stdexcept>

namespace brainrot {
namespace {

using json = nlohmann::json;

struct InvalidMessage : std::runtime_error {
    using std::runtime_error::runtime_error;
};

std::string dump(const json& j) {
    return j.dump(-1, ' ', false, json::error_handler_t::replace);
}

std::string error(const std::string& message) {
    return dump({{"event", "error"}, {"message", message}});
}

std::string ack(const std::string& cmd) {
    return dump({{"event", "ack"}, {"cmd", cmd}});
}

std::string stringField(const json& j, const char* key) {
    auto it = j.find(key);
    if (it == j.end()) return {};
    if (!it->is_string()) throw InvalidMessage(std::string(key) + " must be a string");
    return it->get<std::string>();
}

AppRule parseRule(const json& j, size_t index) {
    std::string where = "apps[" + std::to_string(index) + "]";
    if (!j.is_object()) throw InvalidMessage(where + " must be an object");

    AppRule rule;
    rule.label = stringField(j, "label");
    rule.bundleId = stringField(j, "bundleId");
    rule.processName = stringField(j, "processName");
    rule.pathPrefix = stringField(j, "pathPrefix");

    auto fragments = j.find("cmdlineContains");
    if (fragments != j.end()) {
        if (!fragments->is_array()) throw InvalidMessage(where + ".cmdlineContains must be an array");
        for (const json& item : *fragments) {
            if (!item.is_string()) throw InvalidMessage(where + ".cmdlineContains must hold strings");
            rule.cmdlineContains.push_back(item.get<std::string>());
        }
    }

    if (rule.bundleId.empty() && rule.processName.empty() && rule.pathPrefix.empty() &&
        rule.cmdlineContains.empty()) {
        throw InvalidMessage(where + " has no matching criteria");
    }
    return rule;
}

Policy parsePolicy(const json& j) {
    Policy policy;

    auto locked = j.find("locked");
    if (locked == j.end() || !locked->is_boolean()) throw InvalidMessage("locked (boolean) is required");
    policy.locked = locked->get<bool>();

    auto apps = j.find("apps");
    if (apps == j.end() || !apps->is_array()) throw InvalidMessage("apps (array) is required");
    for (size_t i = 0; i < apps->size(); ++i) policy.apps.push_back(parseRule((*apps)[i], i));

    auto unlockedUntil = j.find("unlockedUntil");
    if (unlockedUntil != j.end() && !unlockedUntil->is_null()) {
        if (!unlockedUntil->is_number_integer()) throw InvalidMessage("unlockedUntil must be Unix seconds or null");
        policy.unlockedUntil = unlockedUntil->get<std::int64_t>();
    }

    auto warning = j.find("closeWarningMinutes");
    if (warning != j.end()) {
        if (!warning->is_number_integer() || warning->get<int>() < 0) {
            throw InvalidMessage("closeWarningMinutes must be a non-negative integer");
        }
        policy.closeWarningMinutes = warning->get<int>();
    }

    auto dryRun = j.find("dryRun");
    if (dryRun != j.end()) {
        if (!dryRun->is_boolean()) throw InvalidMessage("dryRun must be a boolean");
        policy.dryRun = dryRun->get<bool>();
    }
    return policy;
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
            Policy policy = parsePolicy(message);
            if (onPolicy_) onPolicy_(policy);
            return ack(name);
        }
        return error("unknown cmd: " + name);
    } catch (const json::parse_error&) {
        return error("invalid JSON");
    } catch (const InvalidMessage& e) {
        return error(e.what());
    } catch (const json::exception& e) {
        return error(e.what());
    }
}

}  // namespace brainrot
