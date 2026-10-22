# BSP-Server Utilities

Server quality of life for the Build Secure Protect (BSP) network: Minecraft 1.20.1, Forge 47.4.x, Mohist backends behind a Velocity proxy. MIT.

Features:

- **Rules popup**: new players read and tick the server's rules before they can move; admins write the rules in game. Agreements count for the whole network.
- **Display panels**: an admin wall block that joins into panels up to 8 x 6 and shows text, a picture, the staff online or the action log.
- **Staff online**: which staff are on, on which server, or away, across the network; staff groups come from LuckPerms.
- **Chunk unloading**: chunks other mods keep loaded for a player's machines unload when that player has been away from the server for 5 minutes, and load again when they return; BSP-Core's totem and projector chunks never unload.
- **Despawn times**: dropped items and experience orbs last a set time of the server's running time, counted whether or not anyone is near (5 minutes; death drops 15; exceptions per item); `/su clearitems` clears this server, one or all.
- **AFK detection**: players idle for 5 minutes are marked AFK (staff show as away on the staff panels); admins can have players AFK too long kicked, never staff or operators.
- **Action log**: every command staff run (private messages never), items they take from the creative menu or give, and game-mode changes, kept for a week and shared across the servers. Admins switch single commands off; every switch change is logged. Players read it with coordinates hidden.

The mod is in development and has not yet been tried on a live server.

## Commands

- `/rules`: the rules, read only (everyone).
- `/actionlog`: the newest 100 log entries, coordinates hidden (everyone).
- `/serverutils` or `/su`: the admin panel with the RULES, STAFF and LOG tabs (operators and BSP-Core admins); `/su status`, `/su rules ...`.
- `/su away`: show as away on the staff panels (staff).
- `/afk`: mark yourself AFK until your next action (everyone).
- `/su clearitems here | all | <server>`: clear dropped items after a 60-second warning (operators, BSP-Core admins).

## Requirements

- Minecraft 1.20.1 with Forge 47.4.10 or newer (Mohist 1.20.1 on the servers).
- Optional: BSP-Core 1.4.0 or newer (its database settings, server id and Score Screens are used when it is there), LuckPerms (staff groups).

## Configuring

The server config is `serverconfig/bsp_server_utilities-server.toml` in each world.

- **Storage**: with BSP-Core installed nothing needs filling in. `storage.mode = "auto"` shares records across the network when BSP-Core's `storage.mode` is `mysql`, and uses BSP-Core's database, Redis and `storage.serverId`. The tables are this mod's own (prefix `bsp_su_`). Turn `useBspCoreDatabase` or `useBspCoreRedis` off to give this mod its own settings. Redis is reached over plain TCP; when BSP-Core uses Redis with TLS this mod reads the database table instead.
- **Staff**: `staff.groups` lists the LuckPerms groups that count as staff, highest first, as `group:Label:#RRGGBB` (for example `"admin:Admin:#B58CFF"`). `staff.operators` makes server operators staff too.
- **Action log**: `log.keepDays` (7) is how long entries are kept on every server and in the shared table.
- **Images**: Display Panels show pictures from a web address or from `config/bsp_server_utilities/images/` on that server. The server fetches the address itself (players never do), so only admins can set one; any address the server can reach is allowed, including ones on its own network. Limits are in the `[images]` section.
- `/serverutils status` (or `/su status`, operators and BSP-Core admins) shows what the server is using.

## Building

```
./gradlew build
```

The jar is written to `build/libs/`. BSP-Core is compiled against from its sibling checkout (`../BSP-Core/build/libs`): build BSP-Core first.

Headless tests:

```
./gradlew runGameTestServer
```

## Documentation

- `docs/API.md`: what other mods may call.
- `docs/TESTING.md`: in-game checks, a section per task.
- `CHANGELOG.md`: changes per version.
