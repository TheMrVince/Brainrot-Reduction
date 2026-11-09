#include <filesystem>
#include <fstream>
#include <iostream>
#include <random>

#include "policy_json.h"
#include "policy_store.h"

using brainrot::AppRule;
using brainrot::Policy;
using brainrot::PolicyStore;
namespace fs = std::filesystem;

static int failures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #cond "\n"; \
            ++failures;                                                          \
        }                                                                        \
    } while (0)

namespace {

Policy sample() {
    Policy p;
    p.locked = true;
    p.unlockedUntil = 1234567;
    p.closeWarningMinutes = 7;
    p.dryRun = false;
    p.apps.push_back(AppRule{"Discord", "com.hnc.Discord", "", "", {}});
    p.apps.push_back(AppRule{"Game", "", "java", "/opt/games/", {"com.example.game", "--username"}});
    return p;
}

bool same(const Policy& a, const Policy& b) {
    if (a.locked != b.locked || a.unlockedUntil != b.unlockedUntil || a.dryRun != b.dryRun ||
        a.closeWarningMinutes != b.closeWarningMinutes || a.apps.size() != b.apps.size()) {
        return false;
    }
    for (size_t i = 0; i < a.apps.size(); ++i) {
        const AppRule &x = a.apps[i], &y = b.apps[i];
        if (x.label != y.label || x.bundleId != y.bundleId || x.processName != y.processName ||
            x.pathPrefix != y.pathPrefix || x.cmdlineContains != y.cmdlineContains) {
            return false;
        }
    }
    return true;
}

void jsonRoundTrips() {
    std::string error;
    auto back = brainrot::parsePolicyJson(brainrot::policyToJson(sample()), error);
    CHECK(back.has_value());
    CHECK(back && same(*back, sample()));

    Policy minimal;
    minimal.locked = false;
    auto minimalBack = brainrot::parsePolicyJson(brainrot::policyToJson(minimal), error);
    CHECK(minimalBack && same(*minimalBack, minimal));
    CHECK(minimalBack && !minimalBack->unlockedUntil.has_value());
}

void policiesCompareByValue() {
    CHECK(sample() == sample());

    Policy different = sample();
    different.apps[1].cmdlineContains.push_back("--extra");
    CHECK(!(sample() == different));

    Policy later = sample();
    later.unlockedUntil = 7654321;
    CHECK(!(sample() == later));

    Policy relocked = sample();
    relocked.unlockedUntil.reset();
    CHECK(!(sample() == relocked));
}

void jsonRejectsBadInput() {
    std::string error;
    CHECK(!brainrot::parsePolicyJson("nope", error) && error == "invalid JSON");
    CHECK(!brainrot::parsePolicyJson("[]", error));
    CHECK(!brainrot::parsePolicyJson(R"({"locked":true,"apps":[{"label":"x"}]})", error));
    CHECK(error.find("apps[0]") != std::string::npos);
}

std::string makeTempDir() {
    std::random_device random;
    for (int attempt = 0; attempt < 100; ++attempt) {
        fs::path dir = fs::temp_directory_path() / ("brainrot-test-" + std::to_string(random()));
        std::error_code ec;
        if (fs::create_directory(dir, ec)) return dir.string();
    }
    return "";
}

void storeSavesAndLoads(const std::string& dir) {
    PolicyStore store(dir + "/nested/deeper/policy.json");  // parent folders are created
    CHECK(store.save(sample()).empty());

    std::string problem;
    auto loaded = store.load(problem);
    CHECK(loaded && same(*loaded, sample()));
    CHECK(problem.empty());

#ifndef _WIN32  // Windows does not report POSIX permission bits; its profile folder is already private
    CHECK((fs::status(dir + "/nested/deeper/policy.json").permissions() & fs::perms::group_all) == fs::perms::none);
    CHECK((fs::status(dir + "/nested/deeper/policy.json").permissions() & fs::perms::others_all) == fs::perms::none);
#endif
    CHECK(!fs::exists(dir + "/nested/deeper/policy.json.tmp"));  // no leftover from the atomic write
}

void storeOverwritesWithTheLatestPolicy(const std::string& dir) {
    PolicyStore store(dir + "/policy.json");
    CHECK(store.save(sample()).empty());

    Policy later;
    later.locked = false;
    CHECK(store.save(later).empty());

    std::string problem;
    auto loaded = store.load(problem);
    CHECK(loaded && same(*loaded, later));
}

void missingFileIsNotAProblem(const std::string& dir) {
    PolicyStore store(dir + "/never-written.json");
    std::string problem = "untouched";
    CHECK(!store.load(problem).has_value());
    CHECK(problem.empty());
}

void corruptFileIsReportedAndIgnored(const std::string& dir) {
    {
        std::ofstream out(dir + "/corrupt.json");
        out << "{ this is not json";
    }
    PolicyStore store(dir + "/corrupt.json");
    std::string problem;
    CHECK(!store.load(problem).has_value());
    CHECK(problem.find("unusable") != std::string::npos);

    {
        std::ofstream out(dir + "/invalid.json");
        out << R"({"locked":true,"apps":[{"label":"no criteria"}]})";
    }
    PolicyStore invalid(dir + "/invalid.json");
    CHECK(!invalid.load(problem).has_value());
    CHECK(problem.find("no matching criteria") != std::string::npos);
}

void unwritableLocationIsReported(const std::string& dir) {
    PolicyStore store(dir + "/policy.json/cannot-be-a-dir/policy.json");  // policy.json exists as a file
    CHECK(!store.save(sample()).empty());
}

}  // namespace

int main() {
    std::string dir = makeTempDir();
    if (dir.empty()) {
        std::cerr << "could not create temp dir\n";
        return 1;
    }

    jsonRoundTrips();
    policiesCompareByValue();
    jsonRejectsBadInput();
    storeSavesAndLoads(dir);
    storeOverwritesWithTheLatestPolicy(dir);
    missingFileIsNotAProblem(dir);
    corruptFileIsReportedAndIgnored(dir);
    unwritableLocationIsReported(dir);

    fs::remove_all(dir);
    if (failures == 0) std::cout << "policy tests passed\n";
    return failures == 0 ? 0 : 1;
}
