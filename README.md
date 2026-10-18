# Midnite Claims

Chunk claims and named homes for **Paper 26.3**. An optional Fabric client mod is published separately and replaces chest menus with native vanilla screens, buttons, text fields and tooltips.

## Installation

1. Run Paper **26.3** with **Java 25**. The plugin is built against Paper `26.3.build.169-beta`.
2. Put `plugin/build/libs/Claims-1.5.6.jar` in the server's `plugins/` folder and restart.
3. Optional: install Fabric Loader **0.19.5+** and Fabric API **0.162.0+26.3**, then install the [Claims Client mod](https://github.com/Tobias30yt/Claims-Client/releases) in the `mods/` folder. Its configurable default key is O (Controls → Claims).
4. Run `/homes` or `/claims` for the central menu. It has Homes and Claims, plus Admin for administrators. Clients with the mod automatically receive native menus; other players receive decorated, paginated chest menus.

The mod is **client-only**. Paper needs only the plugin. There are no required server plugins. LuckPerms permissions work through Bukkit's permission API.

Use `/claims chest` to force chest menus for your current connection and `/claims auto` to restore automatic detection. With the mod installed, chest mode applies to homes, claims and admin menus. Client menu detection resets when reconnecting. Set `client-menus: false` to force chest menus server-wide.

Menus follow the same path in both modes: the central menu opens Homes, Claims or (for admins) Admin. Homes shows saved homes and Create new home; selecting a home teleports immediately. Claims shows your claimed chunks; selecting one opens its coordinates, rename, whitelist, release and green particle-border preview. Claim a chunk with `/claim`. Chunks overlapping WorldGuard protected regions cannot be claimed when WorldGuard is installed. The Fabric screen uses native text fields. Without the mod, name and player inputs open the Minecraft sign editor. Lists are paginated.

On startup and every 48 hours, Claims checks GitHub for newer releases and tells the console and online operators how many releases are available. Set `update-checker.enabled: false` to disable the server check. The optional client mod also checks for updates at launch and writes a warning to the client log.

## Commands

| Command | Action |
| --- | --- |
| `/claim` | Claim the current 16×16 chunk at all heights |
| `/unclaim` | Confirm releasing the current chunk |
| `/claims` | Open the central menu |
| `/claims list` | Browse your claims |
| `/claims trust <player or UUID>` | Trust someone in the current chunk |
| `/claims untrust <player or UUID>` | Remove their access in the current chunk |
| `/homes` | Browse saved homes |
| `/home <name>` | Teleport to a home; no argument opens the GUI |
| `/sethome <name>` | Save your position, yaw and pitch; replacing requires confirmation |
| `/delhome <name>` | Confirm deleting a home |
| `/viewborder` | Show the border of your nearest claim within eight chunks |
| `/claims admin` | Administration for online and offline players |
| `/claims reload` | Reload configuration without replacing stored data |
| `/claims chest` / `/claims auto` | Choose menu mode |

Home names use 1–24 letters, digits, underscores or hyphens and are case-insensitive. Claim names allow 1–32 letters, digits, spaces, underscores or hyphens. Chest menu input uses the in-game sign editor and expires after 60 seconds. Native menus use a text field. Hover buttons for details.

## Permissions and limits

| Permission | Default | Meaning |
| --- | --- | --- |
| `claims.use` | Everyone | Commands and menus |
| `claims.homes.<number>` | None | Maximum homes, e.g. `claims.homes.8` |
| `claims.claims.<number>` | None | Maximum chunks, e.g. `claims.claims.8` |
| `claims.homes.unlimited` | False | Unlimited homes |
| `claims.claims.unlimited` | False | Unlimited chunks |
| `claims.admin` | Operators | Browse players, manage trust, release claims, visit/delete homes, reload |
| `claims.bypass` | False | Bypass player interaction/build restrictions |

Any nonnegative integer up to 2,147,483,647 is supported. The highest **granted** numeric permission overrides the configured default, even if lower than that default. A value of `0` prevents new entries. Denied nodes and malformed numbers are ignored. Lowering a limit preserves existing homes and claims; new entries are blocked until the count falls below the limit. Updating an existing home is still allowed. Limits are read on every operation, so LuckPerms changes need no Claims reload.

```text
/lp group default permission set claims.homes.3 true
/lp group vip permission set claims.homes.8 true
/lp group vip permission set claims.claims.8 true
/lp user Tobias30 permission set claims.admin true
```

Admin access and building bypass are separate. An admin can remove/manage claims and homes but requires `claims.bypass` (or owner/trusted access) to interact inside another player's claim or teleport into it. No permission disables explosion protection. Admin changes to other players' claims/homes and admin home visits are logged in the server console.

## Protection

- Owners and trusted players can build, open containers and interact with entities in their chunks. Trust is per chunk.
- Foreign players cannot break/place blocks, use containers, doors or switches, fill/empty buckets, harvest, edit signs, manipulate armor stands, shear/leash animals or fish protected entities.
- Explosions cannot destroy claimed blocks, hanging entities, cushions or damage non-player entities inside claims. Fire spread/burning and mob block griefing are blocked.
- Pistons, fluid flow, spread, tree growth, dispensers and hopper transfers cannot cross boundaries between different owners or claimed land and wilderness. Adjacent chunks with the same owner can cooperate.
- Both halves of a double chest are checked, including hopper transfers. Foreign players cannot join a chest onto a protected chest.
- Constructing entities from block patterns (golems/withers) is blocked when any consumed block is claimed.

PvP and player explosion damage are unchanged. Claims do not prevent walking into areas. Redstone signals are not isolated. Natural block growth/decay within a claim is not frozen. Other plugins that directly modify worlds or override cancelled events can bypass event-based protection; test interactions with your server's plugin set.

## Homes and teleport safety

New homes cannot be set in foreign claims without access. Teleports check access again after asynchronously loading the destination chunk. Missing worlds, changed/deleted homes, obstructed destinations, unsafe ground and world-border violations cancel the teleport. Movement or damage cancels the warmup. Mounted or dead players cannot teleport. Settings control warmup and cooldown.

## Configuration and storage

`plugins/Claims/config.yml`:

```yaml
default-homes: 3
default-claims: 8
disabled-worlds: []
teleport:
  warmup-seconds: 3
  cooldown-seconds: 5
client-menus: true
```

Disabled worlds block new claims and homes; already claimed chunks stay protected. Warmup is clamped to 0–60 seconds.

`plugins/Claims/claims.dat` stores world/player UUIDs, claims, trust, homes and last known names. Changes are written to a temporary file, flushed to disk and atomically replaced **before** being published in memory. A failed write leaves the previous state active. The filesystem must support atomic replacement. Keep backups of this file alongside your world backups. Do not edit it manually. A corrupted store stops the server on startup to avoid loading worlds without their expected protection.

Storage writes currently run on the server thread for transactional consistency; very large installations should evaluate save latency before deployment. Name lookup includes players who joined while Claims was installed; UUIDs also work.

## Building and verification

Install **JDK 25** and set `JAVA_HOME` to it. Gradle 9.6.0 is pinned in the included wrapper. Java 27 is not supported by this build toolchain.

```powershell
.\gradlew.bat build
```

```sh
./gradlew build
```

Outputs:

- `plugin/build/libs/Claims-1.1.0.jar` — server plugin
- Optional client mod and vanilla Snake screen: [Claims Client releases](https://github.com/Tobias30yt/Claims-Client/releases)

The shared wire protocol is bundled into both artifacts; it is not a separate installation. An optional GitHub Actions workflow template is included at `docs/ci/github-actions-build.yml`. To activate CI, copy it to `.github/workflows/build.yml` using GitHub authentication with workflow permission. The template builds/tests both projects and uploads the JARs. It is not active in this repository.

Automated tests cover numeric permission resolution, persistent state/restart, failed-write rollback, corrupt-file rejection, packet framing/input bounds, ownership, trust/bypass, explosions, fluids, pistons and negative chunk coordinates. See [TESTING.md](TESTING.md) for the in-game acceptance checklist and verification scope.

## License

Proprietary, all rights reserved. [LICENSE.md](LICENSE.md) uses the requested [Reports license](https://github.com/Tobias30yt/Reports/blob/main/LICENSE.md), with the software name changed to **Midnite Claims**. Third-party dependencies retain their own licenses. Minecraft and its assets are not distributed in this repository.

API references: [Paper 26.3](https://papermc.io/news/26-3/), [Fabric 26.3](https://fabricmc.net/2026/09/15/263.html), [Fabric networking](https://docs.fabricmc.net/develop/networking), [Fabric screens](https://docs.fabricmc.net/develop/rendering/gui/custom-screens).

## Alle Tobias30 MidniteSMP plugins

- Claims: _[Paper 1.5.6](https://github.com/Tobias30yt/Claims/releases/tag/paper-1.5.6)_
- Claims Client Mod: _[1.31.6](https://github.com/Tobias30yt/Claims-Client/releases/tag/v1.31.6)_
- Reports: _[1.2.36](https://github.com/Tobias30yt/Reports/releases/tag/v1.2.36)_
- EndUpdate: _[1.0.1](https://github.com/Tobias30yt/EndUpdate/releases/tag/v1.0.1)_
- GrimAC: _[Modrinth](https://modrinth.com/plugin/grimac)_
