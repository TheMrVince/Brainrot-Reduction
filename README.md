# Brainrot-Reduction

Blocks a chosen list of apps (games, etc.) until you have solved a LeetCode problem today.

- `controller/` — Java: LeetCode check, policy engine, config, tray UI
- `enforcer/` — C++: background daemon that watches for and closes blocked apps
- `shared/` — the IPC protocol both sides implement
- `config/` — example configuration
- `packaging/` — installers and auto-start (later)

## Rules

- A problem counts only if the submission was Accepted.
- The day starts at 4 AM local time.
- Blocked apps get a 10-minute warning before being closed.
- One emergency unlock per week, lasting one hour, behind a typed phrase.
- If LeetCode can't be reached and nothing is solved today, stay locked.

## Run it at login (macOS)

Set up `~/.brainrot-reduction/config.json` first (copy `config/config.example.json` and put in your
LeetCode username), then:

```bash
packaging/macos/install.sh
```

This builds both programs, copies them to `~/.brainrot-reduction/app`, and installs two per-user
LaunchAgents (no admin rights needed) that start at every login:

- the enforcer, restarted whenever it exits, so killing it only delays things
- the controller with its menu-bar icon, restarted if it crashes or is killed; **Quit** in its menu keeps it
  quit until the next login

Logs go to `~/.brainrot-reduction/logs`. Run the script again to update. The config starts with
`"dryRun": true`, which only logs what would be closed; set it to `false` when you trust the setup.
To remove everything (your config and saved data are kept):

```bash
packaging/macos/uninstall.sh
```

## Build

Requirements: JDK 21+, Gradle, CMake 3.20+, a C++20 compiler.

```bash
gradle :controller:run
```

```bash
cmake -S enforcer -B enforcer/build && cmake --build enforcer/build && ./enforcer/build/enforcer
```
