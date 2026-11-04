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

## Build

Requirements: JDK 21+, Gradle, CMake 3.20+, a C++20 compiler.

```bash
gradle :controller:run
```

```bash
cmake -S enforcer -B enforcer/build && cmake --build enforcer/build && ./enforcer/build/enforcer
```
