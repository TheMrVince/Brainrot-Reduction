#include "matcher.h"

#include <algorithm>
#include <cctype>
#include <array>

namespace brainrot {
namespace {

// Hard-coded on purpose: no config can remove these. Lowercase.
constexpr std::array kProtectedIdentifiers = {
    "com.apple.finder",
    "com.apple.dock",
    "com.apple.loginwindow",
    "com.apple.systempreferences",
    "com.apple.terminal",
    "com.apple.activitymonitor",
    "com.apple.dt.xcode",
    "com.googlecode.iterm2",
    "com.microsoft.vscode",
    "com.jetbrains",
    "com.anthropic.claudefordesktop",
};

constexpr std::array kProtectedPathPrefixes = {
    "/system/library/",
    "/usr/bin/",
    "/usr/sbin/",
    "/usr/libexec/",
    "/bin/",
    "/sbin/",
};

// Windows: matched right after the drive letter ("c:"), so any system drive works.
// \windows\ covers explorer, taskmgr, cmd, Windows PowerShell, conhost and the rest of the OS.
constexpr std::array kProtectedWindowsPrefixes = {
    "\\windows\\",
    "\\program files\\powershell\\",  // PowerShell 7
};

// Windows apps without bundle IDs whose install path contains the user name, so a prefix cannot work.
constexpr std::array kProtectedPathFragments = {
    "\\microsoft vs code\\",
    "\\windowsapps\\microsoft.windowsterminal",  // also the Preview build
    "\\jetbrains\\",
    "\\windowsapps\\claude_",                    // Claude desktop (Store install)
    "\\anthropicclaude\\",                       // Claude desktop (classic installer)
    "\\claude-data\\",                           // Claude Code started by the desktop app
};

// Our own Java controller is a plain `java` process, so recognise it by its main class.
constexpr std::array kProtectedCmdlineMarkers = {
    "com.brainrot.controller",
};

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return std::tolower(c); });
    return s;
}

bool startsWith(const std::string& s, const std::string& prefix) {
    return s.size() >= prefix.size() && s.compare(0, prefix.size(), prefix) == 0;
}

bool contains(const std::string& s, const std::string& part) {
    return s.find(part) != std::string::npos;
}

// "c:\windows\x.exe" starts with "\windows\" after its drive letter.
bool startsWithAfterDrive(const std::string& path, const std::string& prefix) {
    return path.size() > 2 && path[1] == ':' && path.compare(2, prefix.size(), prefix) == 0;
}

// "com.google.Chrome" covers "com.google.Chrome" and "com.google.Chrome.helper", not "com.google.ChromeX".
bool sameBundleFamily(const std::string& identifier, const std::string& bundleId) {
    return identifier == bundleId || startsWith(identifier, bundleId + ".");
}

}  // namespace

Matcher::Matcher(std::vector<AppRule> rules, std::vector<std::uint32_t> protectedPids)
    : rules_(std::move(rules)), protectedPids_(protectedPids.begin(), protectedPids.end()) {}

const AppRule* Matcher::matchRule(const ProcessInfo& process) const {
    const std::string identifier = lower(process.identifier);
    const std::string name = lower(process.name);
    const std::string path = lower(process.path);
    const std::string cmdline = lower(process.cmdline);

    for (const AppRule& rule : rules_) {
        if (!rule.bundleId.empty() && !identifier.empty() &&
            sameBundleFamily(identifier, lower(rule.bundleId))) {
            return &rule;
        }
        if (!rule.processName.empty() && name == lower(rule.processName)) {
            return &rule;
        }
        if (!rule.pathPrefix.empty() && startsWith(path, lower(rule.pathPrefix))) {
            return &rule;
        }
        if (!rule.cmdlineContains.empty() && !cmdline.empty() &&
            std::all_of(rule.cmdlineContains.begin(), rule.cmdlineContains.end(),
                        [&](const std::string& part) { return contains(cmdline, lower(part)); })) {
            return &rule;
        }
    }
    return nullptr;
}

bool Matcher::isProtected(const ProcessInfo& process) const {
    if (process.pid <= 1 || protectedPids_.count(process.pid)) return true;

    const std::string identifier = lower(process.identifier);
    for (const char* id : kProtectedIdentifiers) {
        if (!identifier.empty() && sameBundleFamily(identifier, id)) return true;
    }

    const std::string path = lower(process.path);
    for (const char* prefix : kProtectedPathPrefixes) {
        if (startsWith(path, prefix)) return true;
    }
    for (const char* prefix : kProtectedWindowsPrefixes) {
        if (startsWithAfterDrive(path, prefix)) return true;
    }
    for (const char* fragment : kProtectedPathFragments) {
        if (contains(path, fragment)) return true;
    }

    const std::string cmdline = lower(process.cmdline);
    for (const char* marker : kProtectedCmdlineMarkers) {
        if (contains(cmdline, marker)) return true;
    }
    return false;
}

const AppRule* Matcher::shouldBlock(const ProcessInfo& process) const {
    const AppRule* rule = matchRule(process);
    return (rule && !isProtected(process)) ? rule : nullptr;
}

}  // namespace brainrot
