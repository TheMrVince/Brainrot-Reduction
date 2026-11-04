#include "policy_store.h"

#include <filesystem>
#include <fstream>
#include <sstream>

#include "policy_json.h"

namespace brainrot {
namespace fs = std::filesystem;

PolicyStore::PolicyStore(std::string file) : file_(std::move(file)) {}

std::string PolicyStore::save(const Policy& policy) const {
    std::error_code ec;
    fs::path target(file_);
    if (target.has_parent_path()) {
        fs::create_directories(target.parent_path(), ec);
        if (ec) return "cannot create " + target.parent_path().string() + ": " + ec.message();
    }

    fs::path temp = target;
    temp += ".tmp";
    {
        std::ofstream out(temp, std::ios::binary | std::ios::trunc);
        if (!out) return "cannot write " + temp.string();
        out << policyToJson(policy) << "\n";
        out.flush();
        if (!out) return "cannot write " + temp.string();
    }
    fs::permissions(temp, fs::perms::owner_read | fs::perms::owner_write, fs::perm_options::replace, ec);

    fs::rename(temp, target, ec);
    if (ec) {
        fs::remove(temp, ec);
        return "cannot replace " + target.string();
    }
    return {};
}

std::optional<Policy> PolicyStore::load(std::string& problem) const {
    problem.clear();
    std::error_code ec;
    if (!fs::exists(file_, ec)) return std::nullopt;

    std::ifstream in(file_, std::ios::binary);
    if (!in) {
        problem = "cannot read " + file_;
        return std::nullopt;
    }
    std::ostringstream text;
    text << in.rdbuf();

    std::string error;
    std::optional<Policy> policy = parsePolicyJson(text.str(), error);
    if (!policy) problem = file_ + " is unusable: " + error;
    return policy;
}

}  // namespace brainrot
