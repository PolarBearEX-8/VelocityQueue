# VelocityQueue

A Velocity proxy plugin that provides a 2b2t-style queue for routing players from a queue server to a main server when slots become available.

## AuthMeVelocity 4.3.0 compatibility (1.0.5)

Versions through 1.0.4 consumed `authmevelocity:main` messages at FIRST event order.
AuthMeVelocity 4.3.0 skips messages already marked handled, so its own login state remained false
even when `/queue debug <player>` reported authenticated=true. Its pre-connect listener then denied main transfers.

Version 1.0.5 validates the source, current session, carrier name and complete payload at FIRST order.
Only validated messages remain available to other proxy listeners; client-origin, stale, malformed and
unknown messages are blocked immediately. A LAST-order listener consumes the channel to prevent forwarding
to clients even when AuthMeVelocity is absent. An already handled event is never reopened.

Replace the VelocityQueue JAR on Velocity with `build/libs/VelocityQueue-1.0.5-RELEASE.jar`, restart Velocity,
then reconnect. Keep AuthMeVelocity 4.3.0 and the existing Paper QueueLoginBridge. No authentication bypass
permission is needed. Disable AuthMeVelocity's automatic send-on-login feature if enabled so the queue
controls transfers. When QueueLoginBridge supplies the reports, AuthMe's native `Hooks.velocity` can remain false.
The change was checked against the supplied AuthMeVelocity JAR's bytecode and regression-tested locally;
a live proxy integration test remains necessary.

## Features

### Queue stability (1.0.3)

Queue membership is atomic and unique per UUID, with a new entry ticket on leave/rejoin or reconnect.
Removing a player immediately compacts FIFO positions. Tab list and changed position titles refresh
on the next worker check (normally 0.5 seconds); chat keeps its configured interval.
Disconnected players and players already on main are pruned before slot reservations and display updates.
Departed entries release pending slot reservations on the next check, and obsolete completions cannot
clear a newer transfer or apply an old retry delay. A backend kick during a failed main transfer preserves
the waiting player's place; an actual proxy disconnect removes it. A live network request already started
cannot be undone by removing local queue membership.

Backend pings run asynchronously, at most once a second, with a three-second timeout and stale-data limit.
Main arrivals invalidate cached capacity before further admissions. An unavailable backend pauses transfers
without blocking queue cleanup or position updates. Priority ordering, retry delays and AntiFlood still apply.

Install `build/libs/VelocityQueue-1.0.3-RELEASE.jar` on Velocity, replacing the older queue JAR, then restart.
The existing QueueLoginBridge remains compatible. These changes do not fix a backend read timeout itself.

- Separate normal and priority queues
- Automatic queue routing when the configured main server is full
- Periodic chat and title queue position updates, with rotating action bar wait estimates and priority skip counts
- Full, available, and restarting status in the tab list
- Tab list header/footer updates with queue status and estimated wait placeholders
- Optional server-list hover query showing queue counts
- Runtime config reload command
- AuthMe login required before queue entry, with a configurable login title

## Requirements

- Java 21
- Velocity proxy
- A configured queue server and main server in `velocity.toml`

## Building

Use the Gradle wrapper:

```powershell
.\gradlew.bat build
```

The plugin jar is written to:

```text
build/libs/
```

## Running Locally

This project uses `xyz.jpenilla.run-velocity` for local Velocity testing:

```powershell
.\gradlew.bat runVelocity
```

The local Velocity files are created under `run/`.

## Installation

1. Build the plugin.
2. Copy the generated jar from `build/libs/` into your Velocity proxy `plugins/` directory.
3. Start the proxy once to generate `plugins/queue/config.yml`.
4. Edit `config.yml` so `queue-server` and `main-server` match server names in `velocity.toml`.
5. Restart the proxy or run `/queue reload`.

### AuthMe login requirement (1.0.2)

Install this queue JAR on **Velocity**. Install the supplied `AuthMe-5.7.0-FORK-Universal.jar` on the **login backend**, which must match `queue-server`. In that backend's `plugins/AuthMe/config.yml`, enable the existing setting:

```yaml
Hooks:
  velocity: true
```

Merge this setting into the existing `Hooks` section; do not replace the entire AuthMe config. Restart the login backend and Velocity after installing the updated plugins. No additional AuthMeVelocity plugin is required for this queue integration. Avoid another proxy plugin that independently moves players after login.

Unauthenticated players stay on `login`, see `Please login`, and do not count toward either queue. AuthMe's `LOGIN` message automatically puts them into the appropriate normal/priority queue. They are transferred to `main` when a slot is available. `/queue` and `/join` require authentication, and `queue.bypass` does not bypass login. `REGISTER` alone does not authenticate a player; AuthMe must confirm `LOGIN` (including its successful session/automatic login flow).

`LOGOUT`, `UNREGISTER`, and `FORCE_UNREGISTER` from the login backend remove the player from the queue and restore the login prompt. Disconnecting or returning from another server to the login backend requires a fresh AuthMe confirmation. Repeated `LOGIN` notifications do not undo `/leave`.

```yaml
authme:
  login-title: "&ePlease login"
  login-subtitle: "&7Use /login to continue"
```

The title refreshes once per second until authentication. Queue messages start after login. If `Hooks.velocity` is disabled or AuthMe sends no confirmation, the player remains outside the queue. Only messages on `authmevelocity:main` from the player's current connection to the configured login backend are trusted; client-origin messages are consumed without forwarding.

This integration uses AuthMe's existing notification protocol; it does not query passwords or the AuthMe database. Protect backend servers from direct public connections as required by your proxy setup.

## Configuration

Default configuration is stored in `src/main/resources/config.yml`.

Important options:

```yaml
queue-server: "login"
main-server: "main"
main-server-slots: 200
messages:
  interval: 10
```

- `queue-server`: server players wait on while queued
- `main-server`: destination server players are queued for
- `main-server-slots`: maximum online count allowed before queueing begins
- `messages.interval`: seconds between queue position notifications
### AntiFlood and priority

```yaml
AntiFlood:
  interval-seconds: 3.5
  players-per-interval: 1
priority:
  mode: queue
  bypass-player-limit: false
```

`AntiFlood` limits attempts across both queues to `players-per-interval` in any rolling `interval-seconds` window. Both values have a minimum of 1. The interval supports decimals such as 3.5; the player count must be an integer. The first batch can start immediately; idle time does not accumulate quota. Failed attempts count toward the limit and retain their individual retry delays. The worker checks every 0.5 seconds and reserves slots for pending connections, so actual transfers may take longer. `/queue reload` applies changes without resetting used quota. Legacy `AntiFlood: 5` remains supported as one player per five seconds.

Players with `queue.priority` use `priority.mode`:

- `queue`: process Priority players first, FIFO within each group, sharing AntiFlood with regular players. Displayed regular positions include the Priority players ahead of them. A Priority player waiting for a retry holds up later queued players.
- `bypass`: automatic processing on the next 0.5-second check skips queue order and AntiFlood. Players still wait if the main server is full unless `bypass-player-limit` is true. Failed connections still use retry delays.

`bypass-player-limit` only applies in `bypass` mode and only overrides this plugin's `main-server-slots`; the backend must still accept the connection. AuthMe login is required in all modes. Existing `queue.bypass` permission behavior is unchanged. Direct main-server requests from regular/queued players are routed through the queue worker so they cannot skip AntiFlood or Priority order. New installations use the sample above; old configs without the new keys retain a one-player/one-second fallback.

### Placeholders

Tab list placeholders:

- `%status%`
- `%position%`
- `%wait%`
- `%online%`

Server-list query placeholders:

- `%priority%`
- `%regular%`
- `%totalinqueue%`
- `%maxplayers%`
- `%online%`

Action bar placeholders:

- `%priority_wait%`: priority queue length multiplied by one minute
- `%non_priority_wait%`: combined priority and normal queue lengths multiplied by one minute
- `%skipped_last_hour%`: successful priority queue transfers in the last hour (in memory)

Action bar messages rotate every `messages.interval` seconds. These wait times are estimates, not measured throughput. `messages.title-position` configures the title independently of chat; titles remain visible for up to one hour and are cleared when leaving the queue or connecting to the main server.

The server list displays the proxy's actual online count and preserves the original ping's maximum player count (falling back to online + 1 if unavailable). `custom-query.max-players` is not used.

## Commands

```text
/queue
/join
/leave
/queue leave
/queue version
/queue reload
```

`/queue` and `/join` join the queue. `/leave` and `/queue leave` leave it.

## Permissions

```text
queue.admin     Allows /queue reload and /queue version
queue.priority  Places the player in the priority queue
queue.bypass    Bypasses queue routing
```

## Development Notes

- Main plugin class: `me.queue.Main`
- Plugin id: `queue`
- Plugin data folder: `plugins/queue`
- Build system: Gradle Kotlin DSL

## Source Recovery

The 1.0.1 base source and default config were restored from the supplied `VelocityQueue-1.0.1-RELEASE.jar` on 2026-09-05. Version 1.0.2 adds the AuthMe login requirement described above. Original JAR SHA-256:

```text
8f73a1cf5cc22e3c1bbb2f3f5d123ff1ad50c85a22ebd97fbb68d31b637dd16c
```

Recovery restores compiled behavior; original comments, formatting, and build dependency versions cannot be recovered from this JAR. The project's existing build configuration is retained.

Recovery validation for the 1.0.1 base: Gradle `compileJava build` succeeded. All 14 compiled classes matched the original `javap -c -p` output after normalizing constant-pool indices; bundled `config.yml` and `velocity-plugin.json` matched byte for byte. The recovered base is also preserved in the sibling `recovery-jar-20260905` folder. The updated 1.0.2 intentionally differs from that JAR.

AuthMe integration tests run with `./gradlew build` and cover trusted login, queue routing, priority and bypass behavior, logout/reconnect, malformed/spoofed messages, and login title behavior. A live Velocity + AuthMe server still needs an installation smoke test.
