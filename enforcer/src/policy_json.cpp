#include "policy_json.h"

#include <nlohmann/json.hpp>

#include <stdexcept>

namespace brainrot {
namespace {

using json = nlohmann::json;

struct InvalidPolicy : std::runtime_error {
    using std::runtime_error::runtime_error;
};

std::string stringField(const json& j, const char* key) {
    auto it = j.find(key);
    if (it == j.end()) return {};
    if (!it->is_string()) throw InvalidPolicy(std::string(key) + " must be a string");
    return it->get<std::string>();
}

AppRule parseRule(const json& j, size_t index) {
    std::string where = "apps[" + std::to_string(index) + "]";
    if (!j.is_object()) throw InvalidPolicy(where + " must be an object");

    AppRule rule;
    rule.label = stringField(j, "label");
    rule.bundleId = stringField(j, "bundleId");
    rule.processName = stringField(j, "processName");
    rule.pathPrefix = stringField(j, "pathPrefix");

    auto fragments = j.find("cmdlineContains");
    if (fragments != j.end()) {
        if (!fragments->is_array()) throw InvalidPolicy(where + ".cmdlineContains must be an array");
        for (const json& item : *fragments) {
            if (!item.is_string()) throw InvalidPolicy(where + ".cmdlineContains must hold strings");
            rule.cmdlineContains.push_back(item.get<std::string>());
        }
    }

    if (rule.bundleId.empty() && rule.processName.empty() && rule.pathPrefix.empty() &&
        rule.cmdlineContains.empty()) {
        throw InvalidPolicy(where + " has no matching criteria");
    }
    return rule;
}

Policy parsePolicy(const json& j) {
    Policy policy;

    auto locked = j.find("locked");
    if (locked == j.end() || !locked->is_boolean()) throw InvalidPolicy("locked (boolean) is required");
    policy.locked = locked->get<bool>();

    auto apps = j.find("apps");
    if (apps == j.end() || !apps->is_array()) throw InvalidPolicy("apps (array) is required");
    for (size_t i = 0; i < apps->size(); ++i) policy.apps.push_back(parseRule((*apps)[i], i));

    auto unlockedUntil = j.find("unlockedUntil");
    if (unlockedUntil != j.end() && !unlockedUntil->is_null()) {
        if (!unlockedUntil->is_number_integer()) throw InvalidPolicy("unlockedUntil must be Unix seconds or null");
        policy.unlockedUntil = unlockedUntil->get<std::int64_t>();
    }

    auto warning = j.find("closeWarningMinutes");
    if (warning != j.end()) {
        if (!warning->is_number_integer() || warning->get<int>() < 0) {
            throw InvalidPolicy("closeWarningMinutes must be a non-negative integer");
        }
        policy.closeWarningMinutes = warning->get<int>();
    }

    auto dryRun = j.find("dryRun");
    if (dryRun != j.end()) {
        if (!dryRun->is_boolean()) throw InvalidPolicy("dryRun must be a boolean");
        policy.dryRun = dryRun->get<bool>();
    }
    return policy;
}

}  // namespace

std::optional<Policy> parsePolicyJson(const std::string& text, std::string& error) {
    try {
        json j = json::parse(text);
        if (!j.is_object()) {
            error = "policy must be a JSON object";
            return std::nullopt;
        }
        return parsePolicy(j);
    } catch (const json::parse_error&) {
        error = "invalid JSON";
    } catch (const InvalidPolicy& e) {
        error = e.what();
    } catch (const json::exception& e) {
        error = e.what();
    }
    return std::nullopt;
}

std::string policyToJson(const Policy& policy) {
    json apps = json::array();
    for (const AppRule& rule : policy.apps) {
        json r = json::object();
        if (!rule.label.empty()) r["label"] = rule.label;
        if (!rule.bundleId.empty()) r["bundleId"] = rule.bundleId;
        if (!rule.processName.empty()) r["processName"] = rule.processName;
        if (!rule.pathPrefix.empty()) r["pathPrefix"] = rule.pathPrefix;
        if (!rule.cmdlineContains.empty()) r["cmdlineContains"] = rule.cmdlineContains;
        apps.push_back(std::move(r));
    }

    json j = {
        {"locked", policy.locked},
        {"unlockedUntil", policy.unlockedUntil ? json(*policy.unlockedUntil) : json(nullptr)},
        {"closeWarningMinutes", policy.closeWarningMinutes},
        {"dryRun", policy.dryRun},
        {"apps", std::move(apps)},
    };
    return j.dump(2, ' ', false, json::error_handler_t::replace);
}

}  // namespace brainrot
