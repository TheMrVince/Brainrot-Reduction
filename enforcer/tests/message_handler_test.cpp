#include <nlohmann/json.hpp>

#include <iostream>

#include "message_handler.h"

using brainrot::MessageHandler;
using brainrot::Policy;
using json = nlohmann::json;

static int failures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #cond "\n"; \
            ++failures;                                                          \
        }                                                                        \
    } while (0)

namespace {

struct Harness {
    int policiesReceived = 0;
    Policy last;
    MessageHandler handler{[this](const Policy& p) {
        ++policiesReceived;
        last = p;
    }};

    json send(const std::string& line) { return json::parse(handler.handle(line)); }
};

void pingIsAcknowledged() {
    Harness h;
    json r = h.send(R"({"cmd":"ping"})");
    CHECK(r["event"] == "ack");
    CHECK(r["cmd"] == "ping");
    CHECK(h.policiesReceived == 0);
}

void validPolicyIsAppliedAndAcknowledged() {
    Harness h;
    json r = h.send(R"({
        "cmd": "set_policy", "locked": true, "unlockedUntil": null,
        "closeWarningMinutes": 10, "dryRun": false,
        "apps": [
            {"label": "Discord", "bundleId": "com.hnc.Discord"},
            {"label": "Game", "cmdlineContains": ["com.example.game", "--username"]}
        ]})");

    CHECK(r["event"] == "ack");
    CHECK(r["cmd"] == "set_policy");
    CHECK(h.policiesReceived == 1);
    CHECK(h.last.locked);
    CHECK(!h.last.unlockedUntil.has_value());
    CHECK(!h.last.dryRun);
    CHECK(h.last.closeWarningMinutes == 10);
    CHECK(h.last.apps.size() == 2);
    CHECK(h.last.apps[0].bundleId == "com.hnc.Discord");
    CHECK(h.last.apps[1].cmdlineContains.size() == 2);
}

void optionalFieldsHaveSafeDefaults() {
    Harness h;
    json r = h.send(R"({"cmd":"set_policy","locked":true,"apps":[]})");

    CHECK(r["event"] == "ack");
    CHECK(h.last.dryRun);  // forgetting the field must not start killing things
    CHECK(h.last.closeWarningMinutes == 10);
}

void unlockedUntilControlsEnforcement() {
    Harness h;
    h.send(R"({"cmd":"set_policy","locked":true,"unlockedUntil":2000,"apps":[]})");

    CHECK(h.last.unlockedUntil == 2000);
    CHECK(!h.last.isEnforcing(1999));
    CHECK(h.last.isEnforcing(2000));
    CHECK(h.last.isEnforcing(2001));

    h.send(R"({"cmd":"set_policy","locked":false,"apps":[]})");
    CHECK(!h.last.isEnforcing(0));
}

void invalidMessagesAreRejectedWithoutSideEffects() {
    Harness h;
    const char* bad[] = {
        "not json at all",
        "[1,2,3]",
        R"({"no":"cmd"})",
        R"({"cmd":42})",
        R"({"cmd":"explode"})",
        R"({"cmd":"set_policy","apps":[]})",
        R"({"cmd":"set_policy","locked":"yes","apps":[]})",
        R"({"cmd":"set_policy","locked":true})",
        R"({"cmd":"set_policy","locked":true,"apps":{}})",
        R"({"cmd":"set_policy","locked":true,"apps":["Discord"]})",
        R"({"cmd":"set_policy","locked":true,"apps":[{"label":"Empty"}]})",
        R"({"cmd":"set_policy","locked":true,"apps":[{"bundleId":5}]})",
        R"({"cmd":"set_policy","locked":true,"apps":[{"cmdlineContains":"x"}]})",
        R"({"cmd":"set_policy","locked":true,"apps":[{"cmdlineContains":[1]}]})",
        R"({"cmd":"set_policy","locked":true,"unlockedUntil":"soon","apps":[]})",
        R"({"cmd":"set_policy","locked":true,"closeWarningMinutes":-1,"apps":[]})",
        R"({"cmd":"set_policy","locked":true,"dryRun":"no","apps":[]})",
    };
    for (const char* line : bad) {
        json r = h.send(line);
        CHECK(r["event"] == "error");
        CHECK(r.contains("message"));
    }
    CHECK(h.policiesReceived == 0);
}

void errorsNameTheProblem() {
    Harness h;
    json r = h.send(R"({"cmd":"set_policy","locked":true,"apps":[{"label":"Oops"}]})");
    CHECK(r["message"].get<std::string>().find("apps[0]") != std::string::npos);
}

void invalidUtf8DoesNotCrashTheHandler() {
    Harness h;
    json r = h.send(std::string(R"({"cmd":"bad)") + "\xff\xfe" + R"("})");
    CHECK(r["event"] == "error");
}

}  // namespace

int main() {
    pingIsAcknowledged();
    validPolicyIsAppliedAndAcknowledged();
    optionalFieldsHaveSafeDefaults();
    unlockedUntilControlsEnforcement();
    invalidMessagesAreRejectedWithoutSideEffects();
    errorsNameTheProblem();
    invalidUtf8DoesNotCrashTheHandler();

    if (failures == 0) std::cout << "message handler tests passed\n";
    return failures == 0 ? 0 : 1;
}
