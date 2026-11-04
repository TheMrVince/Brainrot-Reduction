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
    { "label": "Discord", "bundleId": "com.hnc.Discord" }
  ]
}
```
- Each entry in `apps` is a rule. A process matches if **any** criterion that is set matches; a rule with no criteria matches nothing. Comparisons are case-insensitive.
  - `bundleId`: the app and its helpers (`<id>` and `<id>.*`)
  - `processName`: exact process/app name
  - `pathPrefix`: executable path starts with this
  - `cmdlineContains`: list of strings that must **all** appear in the command line
  - `label`: display only
- The enforcer has a hard-coded protection list (system paths, terminals, editors, the controller, and its own process chain). It wins over any rule and cannot be configured away.
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
{ "event": "blocked", "app": "Discord", "pid": 4821, "dryRun": false }
```
