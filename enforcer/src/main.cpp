#include <chrono>
#include <csignal>
#include <cstdlib>
#include <iostream>
#include <mutex>
#include <optional>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "enforcer.h"
#include "ipc_server.h"
#include "matcher.h"
#include "message_handler.h"
#include "platform_adapter.h"
#include "policy_store.h"

namespace {

using brainrot::AppRule;
using brainrot::Matcher;
using brainrot::ProcessInfo;

brainrot::PlatformAdapter* g_adapter = nullptr;

void onSignal(int) {
    if (g_adapter) g_adapter->stop();
}

void usage() {
    std::cerr << "Usage:\n"
                 "  enforcer list [--all]\n"
                 "  enforcer watch\n"
                 "  enforcer serve [--socket PATH] [--policy-file PATH]\n"
                 "  enforcer scan  <rule>\n"
                 "  enforcer close <rule> [--dry-run] [--grace-ms N]\n"
                 "\n"
                 "A rule needs at least one of (a process matches if any one matches):\n"
                 "  --bundle-id ID    the app and its helpers\n"
                 "  --name NAME       exact process/app name\n"
                 "  --path-prefix P   executable path starts with P\n"
                 "  --cmdline TEXT    command line contains TEXT (repeatable; all must appear)\n";
}

struct Options {
    AppRule rule;
    bool dryRun = false;
    int graceMs = 3000;
    bool valid = true;
};

Options parseOptions(const std::vector<std::string>& args) {
    Options opts;
    for (size_t i = 1; i < args.size(); ++i) {
        const std::string& a = args[i];
        bool hasValue = i + 1 < args.size();
        if (a == "--dry-run") {
            opts.dryRun = true;
        } else if (a == "--bundle-id" && hasValue) {
            opts.rule.bundleId = args[++i];
        } else if (a == "--name" && hasValue) {
            opts.rule.processName = args[++i];
        } else if (a == "--path-prefix" && hasValue) {
            opts.rule.pathPrefix = args[++i];
        } else if (a == "--cmdline" && hasValue) {
            opts.rule.cmdlineContains.push_back(args[++i]);
        } else if (a == "--grace-ms" && hasValue) {
            opts.graceMs = std::atoi(args[++i].c_str());
        } else {
            std::cerr << "Unknown or incomplete option: " << a << "\n";
            opts.valid = false;
        }
    }
    if (opts.rule.bundleId.empty() && opts.rule.processName.empty() && opts.rule.pathPrefix.empty() &&
        opts.rule.cmdlineContains.empty()) {
        std::cerr << "Give at least one rule option.\n";
        opts.valid = false;
    }
    opts.rule.label = "cli rule";
    return opts;
}

int list(brainrot::PlatformAdapter& adapter, bool all) {
    for (const auto& p : adapter.listProcesses()) {
        if (!all && p.identifier.empty()) continue;
        std::cout << p.pid << "\t" << (p.identifier.empty() ? "-" : p.identifier) << "\t" << p.name << "\n";
    }
    return 0;
}

// scan and close share this: both only ever act on what the matcher says to block.
int apply(brainrot::PlatformAdapter& adapter, const Options& opts, bool act) {
    Matcher matcher({opts.rule}, adapter.ownProcessChain());

    int matched = 0;
    int failures = 0;
    for (const auto& p : adapter.listProcesses()) {
        if (!matcher.matchRule(p)) continue;
        ++matched;

        std::string who = p.name + " (pid " + std::to_string(p.pid) + ")";
        if (!matcher.shouldBlock(p)) {
            std::cout << "PROTECTED  " << who << "\n";
        } else if (!act || opts.dryRun) {
            std::cout << (act ? "[dry run] would close  " : "BLOCK      ") << who << "\n";
        } else {
            bool ok = adapter.terminate(p.pid, opts.graceMs);
            std::cout << (ok ? "closed     " : "FAILED     ") << who << "\n";
            if (!ok) ++failures;
        }
    }
    if (matched == 0) {
        std::cerr << "No running process matches that rule\n";
        return 1;
    }
    return failures == 0 ? 0 : 1;
}

std::string defaultDataDir() {
    const char* home = std::getenv("HOME");
    return std::string(home ? home : "/tmp") + "/.brainrot-reduction";
}

// The daemon: receives policies over IPC and enforces them.
int serve(const std::vector<std::string>& args, brainrot::PlatformAdapter& adapter) {
    std::string socketPath = defaultDataDir() + "/enforcer.sock";
    std::string policyFile = defaultDataDir() + "/policy.json";
    for (size_t i = 1; i < args.size(); ++i) {
        if (args[i] == "--socket" && i + 1 < args.size()) {
            socketPath = args[++i];
        } else if (args[i] == "--policy-file" && i + 1 < args.size()) {
            policyFile = args[++i];
        } else {
            std::cerr << "Unknown or incomplete option: " << args[i] << "\n";
            usage();
            return 2;
        }
    }

    std::mutex outputMutex;
    auto log = [&outputMutex](const std::string& message) {
        std::lock_guard<std::mutex> lock(outputMutex);
        std::cout << message << std::endl;
    };

    brainrot::Enforcer enforcer(adapter, log, [] {
        return static_cast<std::int64_t>(std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()).count());
    });

    brainrot::PolicyStore store(policyFile);

    // The controller re-sends its policy every minute as a heartbeat. Only a change is worth a log
    // line, a disk write or a rescan.
    std::optional<brainrot::Policy> current;  // guarded by outputMutex

    brainrot::MessageHandler handler([&](const brainrot::Policy& policy) {
        {
            std::lock_guard<std::mutex> lock(outputMutex);
            if (current && *current == policy) return;
            current = policy;
        }

        std::ostringstream line;
        line << "policy: " << (policy.locked ? "locked" : "unlocked")
             << ", dryRun=" << (policy.dryRun ? "yes" : "no")
             << ", warning=" << policy.closeWarningMinutes << "min";
        if (policy.unlockedUntil) line << ", unlockedUntil=" << *policy.unlockedUntil;
        line << ", apps=[";
        for (size_t i = 0; i < policy.apps.size(); ++i) {
            line << (i ? ", " : "") << (policy.apps[i].label.empty() ? "(unlabelled)" : policy.apps[i].label);
        }
        line << "]";
        log(line.str());

        std::string saveError = store.save(policy);
        if (!saveError.empty()) log("warning: could not save policy: " + saveError);
        enforcer.setPolicy(policy);
    });

    adapter.onProcessStart([&enforcer](const ProcessInfo& p) { enforcer.onProcessStarted(p); });

    brainrot::IpcServer server(socketPath, handler);
    std::string error = server.start();
    if (!error.empty()) {
        std::cerr << "Cannot start: " << error << "\n";
        return 1;
    }

    // Enforce the last known policy right away, before the controller has reconnected.
    std::string loadProblem;
    if (std::optional<brainrot::Policy> saved = store.load(loadProblem)) {
        log(std::string("loaded saved policy: ") + (saved->locked ? "locked" : "unlocked") + ", " +
            std::to_string(saved->apps.size()) + " app rule(s)");
        {
            std::lock_guard<std::mutex> lock(outputMutex);
            current = *saved;  // so an identical re-send is not mistaken for news
        }
        enforcer.setPolicy(*saved);
    } else if (!loadProblem.empty()) {
        log("warning: ignoring saved policy: " + loadProblem);
    }

    enforcer.start();
    log("Listening on " + socketPath + ". Ctrl+C to stop.");
    adapter.run();  // returns when a signal calls stop()

    server.stop();
    enforcer.stop();
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    std::vector<std::string> args(argv + 1, argv + argc);
    if (args.empty()) {
        usage();
        return 2;
    }

    auto adapter = brainrot::createPlatformAdapter();
    g_adapter = adapter.get();
    std::signal(SIGINT, onSignal);
    std::signal(SIGTERM, onSignal);

    if (args[0] == "serve") return serve(args, *adapter);

    if (args[0] == "list") {
        return list(*adapter, args.size() > 1 && args[1] == "--all");
    }

    if (args[0] == "watch") {
        adapter->onProcessStart([](const ProcessInfo& p) {
            std::cout << "[" << p.detectedBy << "] started " << p.name << " (pid " << p.pid << ", "
                      << (p.identifier.empty() ? "-" : p.identifier) << ")" << std::endl;
        });
        std::cout << "Watching for new processes. Ctrl+C to stop." << std::endl;
        adapter->run();
        return 0;
    }

    if (args[0] == "scan" || args[0] == "close") {
        Options opts = parseOptions(args);
        if (!opts.valid) {
            usage();
            return 2;
        }
        return apply(*adapter, opts, args[0] == "close");
    }

    usage();
    return 2;
}
