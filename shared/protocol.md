# IPC protocol (controller <-> enforcer)

Transport: Unix domain socket. Messages are single-line JSON objects terminated by `\n`.
The controller is the client; the enforcer is the server.

## Controller -> enforcer

### `set_policy`
```json
{
  "cmd": "set_policy",
  "locked": true,
  "unlockedUntil": null,
  "closeWarningMinutes": 10,
  "dryRun": true,
  "apps": [
    { "name": "Minecraft Launcher", "bundleId": "com.mojang.minecraftlauncher" }
  ]
}
```
- `unlockedUntil`: Unix seconds, or `null`. While in the future the enforcer treats the policy as unlocked and relocks on its own when it passes.
- The enforcer persists the last policy to disk and enforces it on restart.

### `ping`
```json
{ "cmd": "ping" }
```

## Enforcer -> controller

### `ack`
```json
{ "event": "ack", "cmd": "set_policy" }
```

### `blocked`
```json
{ "event": "blocked", "app": "Minecraft Launcher", "pid": 4821, "dryRun": false }
```

Open questions: app matching rules beyond `bundleId` (executable path, command-line substring) are not specified yet.
