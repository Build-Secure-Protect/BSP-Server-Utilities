# BSP-Server Utilities

Server quality of life for the Build Secure Protect (BSP) network: Minecraft 1.20.1, Forge 47.4.x servers behind a Velocity proxy. MIT.

Features:

- **Rules popup**: new players read and tick the server's rules before they can move; admins write the rules in game. Agreements count for the whole network.
- **Display panels**: an admin wall block that joins into panels up to 8 x 6 and shows text, a picture, the staff online or the action log.
- **Staff online**: which staff are on, on which server, or away, across the network; staff groups come from LuckPerms.
- **Chunk unloading**: chunks other mods keep loaded for a player's machines unload when that player has been away from the server for 5 minutes, and load again when they return; BSP-Core's totem and projector chunks never unload.
- **Despawn times**: dropped items and experience orbs last a set time of the server's running time, counted whether or not anyone is near (5 minutes; death drops 15; exceptions per item); `/su clearitems` clears this server, one or all.
- **AFK detection**: players idle for 5 minutes are marked AFK (staff show as away on the staff panels, and their machines' chunks unload like those of players who left). With kicking on, a player AFK too long gets a presence check, a card asking them to click a named word within 2 minutes, and is kicked if they do not answer; admins and operators are never checked, other staff never kicked. Macros (the same few inputs again and again, with even timing, the same head angles or in one spot) do not count as activity, so they go AFK and are checked like anyone idle; each unanswered check is in the Action Log with what was seen.
- **Client check**: every client says, while it logs in, which mods, resource packs and shader it has, its brightness, and whether common stone blocks still look solid; a player with a blocked mod or pack, or a see-through stone pack, is refused with a list of what to remove. Admins see everyone's setup, what differs from a trusted setup, and can kick with a reason. A deterrent, not proof: a client built to lie can lie.
- **Action log**: every command staff run (private messages never), items they take from the creative menu or give, and game-mode changes, kept for a week and shared across the servers. Admins switch single commands off; every switch change is logged. Players read it with coordinates hidden.
- **Compact Machines rooms**: with Compact Machines 6.0.3, every player sees their rooms across the network in `/su rooms`, and admins can set how many rooms each player may own (a network total or per server). A room whose machine is certainly destroyed, or that its owner gives back or staff free, stops counting at once and is emptied after a grace period unless its machine is placed again. Admins see every owner in the ROOMS tab.
- **Mob limits**: admins set how many mobs one chunk may hold in the MOBS tab, as a number for every chunk, for each dimension (the Compact Machines rooms, say), or for one chunk, the most specific winning, the same on every server. A new mob is refused in a full chunk, whether it spawns, comes from a spawner or a mob farm, is bred, hatched from a spawn egg or summoned; mobs already there stay. The tab lists the fullest chunks on every server, and REMOVE EXTRAS takes a chunk on this server down to its limit, the most numerous kind first, never tamed or name-tagged mobs.
- **Crowded drops and arrows**: a chunk holding more than 256 dropped items or 128 arrows for 5 minutes is cleared down to that number, oldest first, keeping death drops and what `/su clearitems` keeps; players nearby are told. Set on the same tab's DROPS & ARROWS page, with the crowded chunks on every server and CLEAR NOW.
- **Lag**: the staff screen's LAG tab shows each world's loaded chunks on any server as a map around you, coloured by entities, block entities or what keeps them loaded, with a list of the busiest and GO THERE. Its profiler samples a server for 10, 30 or 60 seconds and ranks the chunks by tick time, and each chunk's block entities and entities by cost, with TP beside the worst and a red outline in the world round the top 10. It costs nothing outside a sample. `/su chunks` gives this server's loaded chunks in chat.
- **Private staff messages**: staff write privately to a player in game (`/su msg`, `/su talk <player>`) or from the staff web panel; the player gets a popup (who wrote, never the words) and a MESSAGE STAFF key in the inventory, right of SUPPORT BSP, that flashes red until they answer; a player who was offline is told when they next log on, on whichever server. One conversation per player, shared by all staff, ended by any staff member (the key goes). Lines are kept 90 days.
- **Support BSP**: a link to the page where players can support the server. A SUPPORT BSP key in the top left corner of the inventory (right of BSP-Core's TEAM and KITS, clear of every other button, the recipe book, JEI and EMI) and `/support` open a popup with the address (OPEN in the browser, COPY LINK); a chat line about 5 seconds after a player joins the network (not when they move between its servers) says the admin's sentence with the link. Admins set the link, the sentence and the chat line's own link in the SUPPORT tab, the same for every server; only https links are accepted.
- **Streamer Mode** (the player's own game): a second, clean picture of the game for OBS, while the player's window shows everything. It leaves out what the streamer chooses, per mod: the F3 screen, chat messages that hold coordinates (and what they type when it holds some), JourneyMap's minimap or only its coordinate lines, its waypoint markers and menus, BSP-Teleport's coordinates, BSP-Core's sentinel strip, recall card and Team screen, Region Guard's Land Office screen, any HUD part any mod registers, and other mods' menus. A hidden menu shows as a "Menu hidden" card. OBS takes it from a window titled "BSP Stream" (Window Capture, or macOS Screen Capture in window mode) or, on 64-bit Windows, over Spout2 (Off-World-Live's OBS plugin) with no window. The menu opens from the eye key in the top-right corner of the title and pause screens, with a live preview and a frame rate setting (Match, 60, 30). Other mods can ask whether to hide something through `api.StreamerMode` ([docs/API.md](docs/API.md)).
- **Tutorials**: guided tours of the network. A popup at each stop (full screen or small and placed anywhere; it freezes the player until they press a button, or shows while they walk and fades), laid out on a grid of text boxes (styles and colours change mid-line) and pictures (from `config/bsp_server_utilities/tutorial_images/` or a web address). Buttons move on, go back, end the tutorial, choose a branch of a fork, or open a link. A stop with a place opens its popup when the player comes within its radius; a gold dotted line leads there along the path an admin walked, with a sign over the stop; a stop on another server is reached with TAKE ME THERE (through BSP-Teleport). One tutorial can be offered right after the rules popup (to new players, or to everyone who has not finished it); others are offered on walking into a start area, and every published one can be started with `/tutorial`. Places are kept for the whole network. Admins build them in the TUTORIAL tab's builder (a canvas of stops and links, a popup editor with a live preview, path drawing and start areas in the world), with drafts that players see only once published.

The mod is in development and has not yet been tried on a live server.

## Commands

- `/rules`: the rules, read only (everyone).
- `/actionlog`: the newest 100 log entries, coordinates hidden (everyone).
- `/serverutils` or `/su`: the staff screen, almost full screen, with its tabs in a list down the left (operators and BSP-Core admins); `/su status`, `/su rules ...`.
- `/su rooms`: your Compact Machines rooms, with GIVE BACK (everyone, on servers running Compact Machines 6.0.3); `/su rooms forget <server>`: drop a renamed or retired server's rooms from the shared list (operators, BSP-Core admins).
- `/su away`: show as away on the staff panels (staff).
- `/su chunks`: this server's loaded chunks by dimension, what loads them, the entities and block entities in them, mods' tickets, and the five busiest chunks, each a line that teleports there; `/su chunks tp <dimension> <x> <z>` goes to a loaded chunk (operators, BSP-Core admins; the readout also the console).
- `/afk`: mark yourself AFK until your next action (everyone).
- `/su clearitems here | all | <server>`: clear dropped items after a 60-second warning (operators, BSP-Core admins).
- `/su setup`: the setup guide, a page per feature with NEXT, SETUP LATER and TURN OFF FEATURE (operators, BSP-Core admins; offered at their join until done or ignored).
- `/su client <player>`: what a player's client reported and what the check made of it; `/su clientcheck export | reload`: write or read `config/bsp_server_utilities/trusted_setup.json` (operators, BSP-Core admins).
- `/support`: the support page's popup (everyone).
- `/su support`: the Support BSP settings; `/su support link <url>`, `/su support message <text>`, `/su support chatlink <url>` (`none` empties one), `/su support clear` (operators, BSP-Core admins, the console).
- `/su talk`: your conversation with staff, when staff have written to you (everyone).
- `/streamer`: Streamer Mode's menu; `/streamer on`, `/streamer off` (everyone, in their own game).
- `/tutorial`: the tutorials a player can take, with START, RESUME, START AGAIN and END (everyone); `/su tutorial` opens the builder (operators, BSP-Core admins).
- `/su msg <player> <text>`: write privately to a player, online or not; `/su talk <player>`: their conversations with staff, with END CONVERSATION; `/su talks`: the open conversations, those waiting on staff first (staff; `msg` and `talks` also the console).
- `/broadcast <chat|title> <colour> <player|all> <server|network> <message>`: an announcement in chat, or as a title in the middle of the screen (a `|` splits off a smaller subtitle), in a colour (red, gold, grey, `"#FF8800"` and so on, `&` codes allowed), for everyone or one player, on this server or every server of the network, with a short chime (operators, BSP-Core admins, the console and RCON).

## Requirements

- Minecraft 1.20.1 with Forge 47.4.10 or newer (the network runs 47.4.23). On the servers, BSP-Core's full jar (not -slim) provides the MySQL driver for shared storage.
- Optional: BSP-Core 1.4.0 or newer (its database settings, server id and Score Screens are used when it is there), LuckPerms (staff groups), Compact Machines exactly 6.0.3 (the room limit; with BSP-InventorySync 0.8.0 on every server for losses on another server, and BSP-Core 1.17.1 or newer where Compact Machines runs, which keeps Shatter Totems out of rooms).

## Configuring

The server config is `serverconfig/bsp_server_utilities-server.toml` in each world. Forge sends that file to every player who joins, so it holds no addresses or passwords: this mod's own database and Redis connection is in `config/bsp_server_utilities-network.toml`, which stays on the server (keep it private, and out of any client pack).

- **Storage**: with BSP-Core installed nothing needs filling in. `storage.mode = "auto"` shares records across the network when BSP-Core's `storage.mode` is `mysql`, and uses BSP-Core's database and Redis (from BSP-Core's `config/bsp_core-network.toml`) and `storage.serverId`. The tables are this mod's own (prefix `bsp_su_`, `tablePrefix` in the network file). Turn `useBspCoreDatabase` or `useBspCoreRedis` off to use this mod's own connection in `config/bsp_server_utilities-network.toml`. Settings left from an older version in the server config are moved there at the next start, with one log warning that names them (never a value). Redis is reached over plain TCP; when BSP-Core uses Redis with TLS this mod reads the database table instead.
- **Staff**: `staff.groups` lists the LuckPerms groups that count as staff, highest first, as `group:Label:#RRGGBB` (for example `"admin:Admin:#B58CFF"`). `staff.operators` makes server operators staff too.
- **Action log**: `log.keepDays` (7) is how long entries are kept on every server and in the shared table.
- **Images**: Display Panels show pictures from a web address or from `config/bsp_server_utilities/images/` on that server. The server fetches the address itself (players never do), so only admins can set one; any address the server can reach is allowed, including ones on its own network. Limits are in the `[images]` section.
- **The player's own game**: `config/bsp_server_utilities-client.toml`, `supportButton = false` hides the SUPPORT BSP key; `[streamer]` holds Streamer Mode's output, frame rate and what it hides (set in its menu).
- **Rooms**: the `[rooms]` section: `limit` (0: no limit, rooms only counted), `scope` (`network` or `server`), `exemptGroup` (a LuckPerms group never limited; operators never are), `graceDays` (7). Use the same values on every server.
- `/serverutils status` (or `/su status`, operators and BSP-Core admins) shows what the server is using.

## Third-party code

The jar carries Spout's `SpoutLibrary.dll` (Spout SDK 2.007.018, 64-bit, by Lynn Jarvis, BSD-2-Clause, unchanged) for Streamer Mode's Spout2 output, with its licence notice beside it in `natives/windows-x64/`. It is copied to `bsp_server_utilities/natives/` in the game folder and loaded only when a player chooses Spout2 on 64-bit Windows; nothing else ever loads it.

## Building

```
./gradlew build
```

The jar is written to `build/libs/`. BSP-Core is compiled against from its sibling checkout (`../BSP-Core/build/libs`): build BSP-Core first.

Headless tests:

```
./gradlew runGameTestServer
```

`-PwithCm` also loads the pack's Compact Machines 6.0.3, so the room tests run against it (world `run/world_cm`).

## Documentation

- `docs/API.md`: what other mods may call.
- `docs/TESTING.md`: in-game checks, a section per task.
- `CHANGELOG.md`: changes per version.
