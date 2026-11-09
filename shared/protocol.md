# IPC protocol (controller <-> enforcer)

Transport: Unix domain socket. Messages are single-line JSON objects terminated by `\n`.
The controller is the client; the enforcer is the server. Every message from the controller gets
exactly one response line, in order. Several messages may share a write, and one message may
arrive in pieces.

- Default socket: `~/.brainrot-reduction/enforcer.sock` (directory mode 0700, socket mode 0600, so only the current user can connect).
  On Windows it is a Winsock `AF_UNIX` socket with no mode bits; it inherits the user profile folder's access list instead.
- A second enforcer refuses to start while one is listening; a socket left by a crashed run is replaced.
- Messages over 1 MiB without a newline drop the connection.

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
- `locked` (boolean) and `apps` (array) are required. `closeWarningMinutes` defaults to 10 and `dryRun` defaults to `true`, so a client that forgets the field cannot start closing apps.
- An invalid `set_policy` is rejected with an `error` response and the previous policy stays in force.
- The enforcer saves every accepted policy to `~/.brainrot-reduction/policy.json` (mode 0600) and enforces it again on restart, before the controller reconnects.

### How a policy is enforced
- Apps that were **already running** when the lock began get a desktop notification and are closed after `closeWarningMinutes` (immediately if 0). Re-sending the same policy does not restart the countdown.
- Apps that **start while locked** are closed immediately.
- While `locked` is false, or before `unlockedUntil`, nothing is closed. When `unlockedUntil` passes, the lock begins and the rules above apply, with no message from the controller needed.
- `dryRun: true` only logs what would be closed.

### Recommended use by the controller
Express "solved today" as `locked: true` with `unlockedUntil` set to the start of the next day, not as `locked: false`. The unlock then ends by itself even if the controller has crashed or the machine was off, so the system fails closed. Emergency unlocks work the same way, with `unlockedUntil` an hour away.

### `ping`
```json
{ "cmd": "ping" }
```

## Enforcer -> controller

### `ack`
```json
{ "event": "ack", "cmd": "set_policy" }
```

### `error`
```json
{ "event": "error", "message": "apps[0] has no matching criteria" }
```
Sent for invalid JSON, a missing or unknown `cmd`, or a policy that fails validation. The connection stays open.

### `blocked`
```json
{ "event": "blocked", "app": "Discord", "pid": 4821, "dryRun": false }
```
