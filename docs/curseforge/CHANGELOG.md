# BSP-Server Utilities 0.5.1: network passwords kept off players' machines

For Minecraft 1.20.1, Forge 47.4 or newer. 0.5.1 is 0.5.0 with one security fix; coming from 0.4 or earlier, the player counter below is new too. **Clients and servers both need 0.5.0 or newer**: the network protocol changed in 0.5.0, so a 0.4 client is refused by a 0.5 server (and the other way round); 0.5.0 and 0.5.1 work together, and the fix is on the server. Works on its own; uses **BSP-Core 1.4.0** or newer (tested with 1.6.2), **LuckPerms** and **BSP-InventorySync** when they are installed. Coming from 0.3.0 or earlier, the client check and the setup guide from 0.4 are new to you too: see the description.

## Security fix in 0.5.1
- Forge sends a server's SERVER config file to every player who joins, so a database or Redis password in `serverconfig/bsp_server_utilities-server.toml` could be read by any player. This mod's own connection (database host, port, name, user, password, SSL, table prefix; Redis host, port, username, password) now lives in **`config/bsp_server_utilities-network.toml`**, which stays on the server.
- At the first start of 0.5.1, anything still set in the server config is moved there (a value already set in the new file wins) and cleared; the log gets one warning naming what moved, never a value. The old keys stay in the server config, marked as moved.
- **If a password was ever set in the server config, change it:** it was sent to every player who joined. Keep `config/*-network.toml` files out of any client pack and `defaultconfigs/`.
- With BSP-Core 1.6.2 or newer, which moved its own settings to `config/bsp_core-network.toml`, the log and `/su status` name that file as where the database and Redis settings came from. Nothing needs changing: this mod keeps reading BSP-Core's settings as before.

## New in 0.5.0: the player counter

- **Who counts:** everyone who joins any server of the network for the first time, once each (by account, so alts count separately), from the day 0.5.0 is installed. Players the client check refuses never joined, so they never count. With shared storage the whole network keeps one list, so two servers never count the same player twice; without it each server counts its own.
- **The welcome line:** a new player is welcomed in gold, in words the admins type with `{name}` and `{number}` filled in (up to 160 characters; to begin with "Welcome {name}, player number {number} on BSP!"). Everyone on every server of the network sees it; on the servers the player did not join, the server's name comes first in grey ("[BSP-Spawn] Welcome ...").
- **The PLAYERS board** for BSP-Core's Score Screens: the counter's title, then "Have played BSP" with the number in gold, "New today", "New this week" and "Newest" with the name and how long ago.
- **Player count on Display Panels,** the fifth mode: the title in teal, the number large in gold, "players have joined the network", and the new counts underneath when the panel's tick is on. Each panel can type its own title (32 characters); left empty it shows the counter's.
- **Adjusting:** the number shown is the real count plus an adjustment, and the real list is never lost. `/su players` shows the number, the real count and the adjustment, new today and this week, and the newest player; `/su players set <n>`, `add <n>` (a minus takes away, never below 0), `reset` (0 from now) and `true` (back to the real count). Each change, and a change of the title or the welcome words, is written to the action log under who made it.
- **The setup guide's Player counter page,** after Display panels (the guide has eleven pages now): the number shown and the real count, SET and ADD, RESET TO 0 (a second click to confirm) and REAL COUNT, the title ("Players of BSP" to begin with) and the welcome words with the line as it will read. TURN OFF FEATURE stops counting and welcoming, and the board and panels show nothing. Servers updating from 0.4 are offered this one page at an admin's next join.
- A Players line in `/su status`.

## Changed in 0.5.0
- The mod's network protocol is version 3: update clients and servers together.
