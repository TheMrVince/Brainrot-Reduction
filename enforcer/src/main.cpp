#include <algorithm>
#include <cctype>
#include <csignal>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "platform_adapter.h"

namespace {

using brainrot::ProcessInfo;

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return std::tolower(c); });
    return s;
}

bool matches(const ProcessInfo& p, const std::string& query) {
    return p.identifier == query || lower(p.name) == lower(query);
}

brainrot::PlatformAdapter* g_adapter = nullptr;

void onSignal(int) {
    if (g_adapter) g_adapter->stop();
}

void usage() {
    std::cerr << "Usage:\n"
                 "  enforcer list [--all]\n"
                 "  enforcer watch\n"
                 "  enforcer close [--dry-run] [--grace-ms N] <name-or-bundle-id>\n";
}

int list(brainrot::PlatformAdapter& adapter, bool all) {
    for (const auto& p : adapter.listProcesses()) {
        if (!all && p.identifier.empty()) continue;
        std::cout << p.pid << "\t" << (p.identifier.empty() ? "-" : p.identifier) << "\t" << p.name << "\n";
    }
    return 0;
}

int close(brainrot::PlatformAdapter& adapter, const std::string& query, bool dryRun, int graceMs) {
    std::vector<ProcessInfo> targets;
    for (const auto& p : adapter.listProcesses()) {
        if (matches(p, query)) targets.push_back(p);
    }
    if (targets.empty()) {
        std::cerr << "No running process matches \"" << query << "\"\n";
        return 1;
    }

    int failures = 0;
    for (const auto& p : targets) {
        if (dryRun) {
            std::cout << "[dry run] would close " << p.name << " (pid " << p.pid << ")\n";
            continue;
        }
        bool ok = adapter.terminate(p.pid, graceMs);
        std::cout << (ok ? "closed " : "FAILED to close ") << p.name << " (pid " << p.pid << ")\n";
        if (!ok) ++failures;
    }
    return failures == 0 ? 0 : 1;
}

}  // namespace

int main(int argc, char** argv) {
    std::vector<std::string> args(argv + 1, argv + argc);
    if (args.empty()) {
        usage();
        return 2;
    }

    auto adapter = brainrot::createPlatformAdapter();

    if (args[0] == "list") {
        return list(*adapter, args.size() > 1 && args[1] == "--all");
    }

    if (args[0] == "close") {
        bool dryRun = false;
        int graceMs = 3000;
        std::string query;
        for (size_t i = 1; i < args.size(); ++i) {
            if (args[i] == "--dry-run") {
                dryRun = true;
            } else if (args[i] == "--grace-ms" && i + 1 < args.size()) {
                graceMs = std::atoi(args[++i].c_str());
            } else {
                query = args[i];
            }
        }
        if (query.empty()) {
            usage();
            return 2;
        }
        return close(*adapter, query, dryRun, graceMs);
    }

    if (args[0] == "watch") {
        g_adapter = adapter.get();
        std::signal(SIGINT, onSignal);
        std::signal(SIGTERM, onSignal);
        adapter->onProcessStart([](const ProcessInfo& p) {
            std::cout << "[" << p.detectedBy << "] started " << p.name << " (pid " << p.pid << ", "
                      << (p.identifier.empty() ? "-" : p.identifier) << ")" << std::endl;
        });
        std::cout << "Watching for new processes. Ctrl+C to stop." << std::endl;
        adapter->run();
        return 0;
    }

    usage();
    return 2;
}
