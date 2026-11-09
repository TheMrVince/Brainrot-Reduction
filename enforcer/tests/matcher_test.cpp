#include <iostream>

#include "matcher.h"

using brainrot::AppRule;
using brainrot::Matcher;
using brainrot::ProcessInfo;

static int failures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::cerr << __FILE__ << ":" << __LINE__ << " CHECK failed: " #cond "\n"; \
            ++failures;                                                          \
        }                                                                        \
    } while (0)

namespace {

ProcessInfo proc(std::uint32_t pid, std::string name, std::string path,
                 std::string identifier = "", std::string cmdline = "") {
    ProcessInfo p;
    p.pid = pid;
    p.name = std::move(name);
    p.path = std::move(path);
    p.identifier = std::move(identifier);
    p.cmdline = std::move(cmdline);
    return p;
}

const char* kJavaGameCmd =
    "/opt/games/runtime/bin/java "
    "-Xmx2G -Dgame.launcher.brand=example com.example.game.client.Main --username me";
const char* kGradleDaemonCmd =
    "/Library/Java/JavaVirtualMachines/jdk-24.jdk/Contents/Home/bin/java "
    "-Xmx512m org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14.3";
const char* kControllerCmd =
    "/Library/Java/JavaVirtualMachines/jdk-24.jdk/Contents/Home/bin/java "
    "-cp controller.jar com.brainrot.controller.Main";

void javaGameIsDistinguishedByCommandLine() {
    AppRule javaGame{"Java game", "", "", "", {"com.example.game"}};
    Matcher matcher({javaGame}, {});

    ProcessInfo game = proc(500, "java", "/opt/games/runtime/bin/java", "", kJavaGameCmd);
    ProcessInfo gradle = proc(501, "java", "/Library/Java/JavaVirtualMachines/jdk-24.jdk/Contents/Home/bin/java",
                              "", kGradleDaemonCmd);

    CHECK(matcher.shouldBlock(game) != nullptr);
    CHECK(matcher.shouldBlock(gradle) == nullptr);
}

void allCommandLineFragmentsMustAppear() {
    AppRule rule{"Java game", "", "", "", {"com.example.game", "--username"}};
    Matcher matcher({rule}, {});

    CHECK(matcher.matchRule(proc(1000, "java", "/x/java", "", "java com.example.game.client.Main --username me")));
    CHECK(!matcher.matchRule(proc(1001, "java", "/x/java", "", "java com.example.game.client.Main")));
}

void bundleIdCoversHelpersButNotLookalikes() {
    Matcher matcher({AppRule{"Discord", "com.hnc.Discord", "", "", {}}}, {});

    CHECK(matcher.shouldBlock(proc(600, "Discord", "/Applications/Discord.app/Contents/MacOS/Discord", "com.hnc.Discord")));
    CHECK(matcher.shouldBlock(proc(601, "Discord Helper (Renderer)", "/Applications/Discord.app/Contents/Frameworks/Discord Helper (Renderer).app/x", "com.hnc.Discord.helper.Renderer")));
    CHECK(matcher.shouldBlock(proc(602, "Discord Helper (GPU)", "/Applications/Discord.app/Contents/Frameworks/Discord Helper (GPU).app/x", "com.hnc.Discord.helper.GPU")));
    CHECK(!matcher.shouldBlock(proc(603, "Other", "/Applications/O.app/Contents/MacOS/O", "com.hnc.DiscordX")));
}

void comparisonsIgnoreCase() {
    Matcher matcher({AppRule{"Steam", "COM.valvesoftware.STEAM", "", "", {}}}, {});
    CHECK(matcher.shouldBlock(proc(700, "steam", "/Applications/Steam.app/x", "com.valvesoftware.steam")));
}

void pathPrefixAndProcessName() {
    Matcher byPath({AppRule{"Game", "", "", "/Applications/Game.app/", {}}}, {});
    CHECK(byPath.shouldBlock(proc(800, "GameHelper", "/Applications/Game.app/Contents/Helper", "")));
    CHECK(!byPath.shouldBlock(proc(801, "Other", "/Applications/Other.app/Contents/x", "")));

    Matcher byName({AppRule{"Calc", "", "calculator", "", {}}}, {});
    CHECK(byName.shouldBlock(proc(802, "Calculator", "/System/Applications/Calculator.app/x", "")));
}

void ruleWithoutCriteriaMatchesNothing() {
    Matcher matcher({AppRule{"Empty", "", "", "", {}}}, {});
    CHECK(!matcher.matchRule(proc(900, "anything", "/anywhere", "com.any.thing", "cmd line")));
}

void protectedProcessesAreNeverBlocked() {
    AppRule everythingJava{"Java", "", "java", "", {}};
    AppRule terminal{"Terminal", "com.apple.Terminal", "", "", {}};
    AppRule finderByPath{"Finder", "", "", "/System/Library/", {}};
    Matcher matcher({everythingJava, terminal, finderByPath}, {4242});

    CHECK(matcher.matchRule(proc(1100, "java", "/x/java", "", kControllerCmd)) != nullptr);
    CHECK(matcher.shouldBlock(proc(1100, "java", "/x/java", "", kControllerCmd)) == nullptr);

    CHECK(matcher.shouldBlock(proc(1101, "Terminal", "/System/Applications/Utilities/Terminal.app/x", "com.apple.Terminal")) == nullptr);
    CHECK(matcher.shouldBlock(proc(1102, "Finder", "/System/Library/CoreServices/Finder.app/x", "com.apple.finder")) == nullptr);
    CHECK(matcher.shouldBlock(proc(1103, "Code Helper", "/Applications/Visual Studio Code.app/x", "com.microsoft.VSCode.helper")) == nullptr);
    CHECK(matcher.shouldBlock(proc(1104, "java", "/x/java", "", kGradleDaemonCmd)) != nullptr);
}

void ownProcessChainAndLowPidsAreProtected() {
    Matcher matcher({AppRule{"Any", "", "zsh", "", {}}}, {4242});

    CHECK(matcher.isProtected(proc(4242, "zsh", "/bin/zsh")));
    CHECK(matcher.isProtected(proc(1, "launchd", "/sbin/launchd")));
    CHECK(matcher.isProtected(proc(0, "kernel_task", "")));
    CHECK(!matcher.isProtected(proc(4243, "zsh", "/Users/me/bin/zsh")));
}

}  // namespace

int main() {
    javaGameIsDistinguishedByCommandLine();
    allCommandLineFragmentsMustAppear();
    bundleIdCoversHelpersButNotLookalikes();
    comparisonsIgnoreCase();
    pathPrefixAndProcessName();
    ruleWithoutCriteriaMatchesNothing();
    protectedProcessesAreNeverBlocked();
    ownProcessChainAndLowPidsAreProtected();

    if (failures == 0) std::cout << "matcher tests passed\n";
    return failures == 0 ? 0 : 1;
}
