# Brainrot Reduction

Blocks a chosen list of apps until the user has solved a LeetCode problem today. A Java controller decides
whether the user is locked; a C++ enforcer closes the blocked apps. See `README.md` for the rules and how to
install; this file is what a new session needs to carry on the work.

## How the user likes to work

- The user is learning (system design practice) and wants plain-language explanations. Define jargon.
- Small steps: one phase at a time, tests with each, then wait. **Commit only when asked, and never push**;
  the user commits and pushes.
- Be honest about what was verified and what was not. Say when something could only be checked in a fake.
- Work on a branch and merge through a pull request; CI runs on pull requests and on pushes to `main`.
- Keep `config/config.example.json` on the placeholder username. A test depends on it, and it once got edited by hand.
- Test closing behaviour on Calculator, never on the user's live apps. Discord (`com.hnc.Discord`) is the
  example blocked app; Minecraft was dropped because it is not installed. Start with `"dryRun": true`.

## Architecture

Two programs that talk over a local socket, both started at login.

```
controller/ (Java 21, Gradle)                  enforcer/ (C++20, CMake)
  LeetCodeClient  -> CheckResult                 PlatformAdapter (one per OS)
  PolicyEngine    -> Policy           socket     Matcher + protection list
  ControllerService (scheduler)  ------------>   MessageHandler -> Enforcer (warn, close, timers)
  TrayCommand / RunCommand        set_policy     PolicyStore (saved policy), IpcServer
```

- Protocol: `shared/protocol.md` (one JSON object per line, one reply per request).
- Shared folder: `~/.brainrot-reduction/` holds `config.json`, `state.json`, `policy.json`, `enforcer.sock`, `app/`, `logs/`.
- Controller commands: `tray`, `run`, `status [--send]`, `unlock [--send] <phrase>`.
- Enforcer commands: `serve`, `list [--all]`, `watch`, `scan <rule>`, `close <rule> [--dry-run]`.

### Decisions that are easy to break

- **Fail closed.** Locked unless today is solved or an emergency unlock runs. Offline falls back to remembered state.
- **An unlock is an end time.** "Solved today" is `locked: true` with `unlockedUntil` = next 4 AM, not `locked: false`,
  so it ends by itself even if the controller dies. The enforcer relocks on its own timers.
- **Day starts at 4 AM local time.** Emergency unlock: exact typed phrase, N uses in any rolling 7 days, cannot stack.
- **Warning vs immediate.** Apps already running when a lock begins get `closeWarningMinutes` (default 10) and a
  notification. Apps launched while locked close at once. They are told apart by process start time.
- **dryRun defaults to true** everywhere a client can forget to say.
- **Protection list wins over every rule** (`enforcer/src/matcher.cpp`), plus the enforcer's own process chain.
- **Rule matching:** a process matches if ANY set criterion matches (`bundleId` family, `processName`, `pathPrefix`,
  `cmdlineContains` all-of). A rule with no criteria matches nothing. Case-insensitive.
- **Closing order:** quit the app itself first (5 s grace), then sweep leftovers; processes still counting down are never swept.
- **The portable core has no OS headers** (`matcher`, `policy*`, `message_handler`, `enforcer`). OS code lives only behind
  `PlatformAdapter` and the IPC transport. `ProcessInfo.identifier` is a macOS bundle ID (a helper inherits its
  enclosing `.app`'s ID) and is empty on Windows.

## Build and test

```bash
./gradlew :controller:test                                   # gradlew.bat on Windows; 108 tests
cmake -S enforcer -B enforcer/build -DCMAKE_BUILD_TYPE=Release
cmake --build enforcer/build --config Release --parallel
ctest --test-dir enforcer/build --build-config Release --output-on-failure   # 5 suites
```

`-DBRAINROT_CORE_ONLY=ON` builds only the portable core, the socket transport and their tests (what CI does on
Linux and Windows today), because no adapter exists there yet.

On Windows: CMake picks the Visual Studio generator and finds MSVC by itself, so no Developer PowerShell is needed.
To call `cl` directly, run `Launch-VsDevShell.ps1` from the path `vswhere -latest -products * -property installationPath`
prints (`-products *` is required for a Build Tools-only install).

## Status

Done on macOS: J1 to J7 (controller), C1 to C5 (enforcer), P1 (CI), P2 (launchd install: `packaging/macos/`).

**Remaining: C6 (Windows) and P3 (installers).** C6 plan, three commits, each verified natively on Windows:

1. **C6a, socket transport (done).** `src/ipc_server_windows.cpp`, the Winsock `AF_UNIX` twin of the POSIX file.
   `ipc_server_test` passes on Windows, and the real Java `EnforcerClient` was checked against it by hand.
   The socket handle is `std::intptr_t` because a Windows `SOCKET` is 64 bits. Keep socket paths under about 100 bytes.
2. **C6b, adapter** (`src/platform_windows.cpp`), then `enforcer.exe` builds. Planned approach:
   - list processes with Toolhelp snapshots; path via `QueryFullProcessImageNameW`; start time via `GetProcessTimes`
     (convert to Unix seconds); command line via `NtQueryInformationProcess(ProcessCommandLineInformation)`
   - `name` is the exe file name including `.exe`; paths use backslashes, so `pathPrefix` rules must too
   - close politely with `WM_CLOSE` to the process's top-level windows, wait the grace period, then `TerminateProcess`;
     no windows means `TerminateProcess` straight away
   - launch detection by polling a 1 s process-list diff only (same dedupe by pid as macOS), so a blocked app may flash open briefly
   - add an adapter test that spawns a real child process and checks list, command line and terminate
3. **C6c, the rest:** notification (tray balloon via `Shell_NotifyIconW`; unverified visually until tried on a real desktop),
   Windows entries in the protection list (explorer, taskmgr, shells, Windows Terminal, VS Code, the controller's own
   `java` via the `com.brainrot.controller` command-line marker, `C:\Windows\`), default folder from `%USERPROFILE%`
   (`main.cpp` still reads `HOME`), and a per-user Task Scheduler "at logon" script in `packaging/windows/`.

Then P3: `jpackage` installers (a `.msi` on Windows, a `.dmg` on macOS) so a machine needs no JDK or CMake.

Known gaps worth knowing: the enforcer never sends `blocked` or `warning` events to the controller (the Java client
already passes unknown lines to a listener); nothing stops two tray instances; deadlines live in memory, so restarting
the enforcer restarts a countdown.

## Gotchas learned the hard way

- A JVM started from a non-interactive background shell ignores SIGINT; stop it with SIGTERM in test scripts.
- Unix socket paths are limited to about 104 bytes; JUnit's `@TempDir` can exceed that.
- macOS ships bash 3.2 and BSD `sed` (`sed -i ''`); keep `packaging/macos/*.sh` compatible.
- The tool harness blocks `rm` on a shell-variable path; avoid it in scripts you run through it.
- `launchd` only restarts a `KeepAlive: {SuccessfulExit: false}` job after a failure, so tray "Quit" stays quit.
