# BSP-Server Utilities 0.1.0: first release

The first public release of BSP-Server Utilities for Minecraft 1.20.1 (Forge 47.4.10 and Mohist), made for the Build Secure Protect modpack. Works on its own; uses **BSP-Core 1.4.0** or newer, **LuckPerms** and **BSP-InventorySync** when they are installed. Needed on the client and the server.

## The rules popup
- **One set of rules for the network:** a heading, an intro line, the message LEAVE SERVER shows, and up to 20 rules, each a bold title (48 characters) with optional text (400).
- **Asked once:** a player who has not agreed to the current rules sees the popup once they have settled in (after BSP-InventorySync has restored their inventory, or 2 seconds after login). Agreeing counts on every server.
- **Held until they answer:** kept in place, unable to build, use, attack, pick up or drop items, open containers, chat or run any command but `/rules`, and unhurt.
- **Read, then agree:** a tick box on the right of every rule (a click anywhere on the row ticks it); AGREE stays dim until every box is ticked, and the server checks the ticks too. LEAVE SERVER disconnects with the admin's message; Escape does nothing; the popup comes back if another screen replaces it.
- **Ask everyone again:** raises the version; players online see the popup at once, everyone else at their next join.
- `/rules` for everyone: the same panel, read only, with the date you agreed.

## Display Panels
- **The block:** a thin wall panel with a black face (Functional Blocks tab; no recipe). Panels side by side and above each other join into one, up to 8 wide and 6 high, with a slim bezel round the outside only and a small teal light in its corner. Every block keeps the settings, so breaking the bottom-left block or adding blocks keeps them. Only admins place, set up and break panels; pistons and explosions leave them alone.
- **Text:** up to 16 lines of 80 characters, each with a size (S, M, L, XL, a share of the panel's height), a colour (the 16 chat colours or a #RRGGBB value, `&` codes inside a line), alignment and bold; a dark background or clear.
- **Pictures:** from a web address or from `config/bsp_server_utilities/images/` on the server; PNG, JPEG and the first frame of a GIF, up to 4 MB; fit inside, fill and crop, or stretch; REFRESH NOW and a refresh timer (off, 5, 15 or 60 minutes). The server fetches the picture and sends it to players, so players never contact the address.
- **Staff online** and **Action log** faces (below).
- **Settings screen:** right-click a panel. The face as players will see it, the mode, the background, and the pane for the mode; nothing changes in the world until SAVE. Faces are drawn at full brightness.

## Staff online
- **Who counts:** members of the LuckPerms groups chosen in the STAFF tab (each with a label and a colour, in order), plus operators unless switched off. `staff.groups` in the config only fills the list the first time.
- **The face:** STAFF ONLINE and the count, a column per role in its colour, and each person with a dot (green online, amber away, grey offline) and the server they are on, "away", or how long ago they were seen.
- **Across the network:** every server reports its online staff on each join and leave and every 30 seconds; faces update at once on the same server, within a second or two from another with Redis, within 30 seconds without.
- `/su away` marks a staff member away until they type it again or leave. Staff who are AFK show as away too while they are.
- **Panel settings:** online and offline, or online only; which roles.

## The action log
- **What is logged:** every command staff, operators, the console and RCON run (private messages never), items staff take from the creative menu (counted per item over five seconds, so moving items around is not logged), gives, game-mode changes, and BSP-Core's admin kit gives.
- **Switches:** admins turn logging off for single commands in the LOG tab; every switch change is itself an entry, so nothing is switched off quietly.
- **Shared and kept a week:** entries go to every server and are deleted after `log.keepDays` (7). Nobody can edit or delete an entry in game.
- **Coordinates hidden for players:** panels and `/actionlog` show `/tp · · ·`; staff read the full text in the LOG tab.
- **The face:** ACTION LOG, newest first: how long ago, who in their role's colour, what, and the server; every server or this one, which kinds, 6, 8 or 12 lines.
- `/actionlog` for everyone: the newest 100 entries, read only.

## AFK detection
- **Idle for 5 minutes is AFK:** turning the head or doing anything the server sees (chat, commands, breaking, placing, using, attacking, clicking in a container, dropping, steering a vehicle) counts as active; being carried by water or a minecart does not. A player reading the rules popup is never idle.
- Players are told when they go AFK and when they are back. `/afk` marks a player AFK at once.
- **Kicking (off until turned on):** a player AFK for 30 minutes is warned a minute before, then sent off with the admin's message. Staff and operators are never kicked.

## The admin panel
- `/serverutils` (or `/su`) for operators and BSP-Core admins, with four tabs:
  - **RULES:** popup text, the rules in order with + ADD RULE, the picked row's boxes with counters, UP, DOWN, DELETE (SURE?), PREVIEW, ASK EVERYONE AGAIN (SURE?), and how many players on the network agreed.
  - **STAFF:** the staff groups (a LuckPerms group, label, colour, order, REMOVE), the operators switch, and everyone with their role and where they are.
  - **LOG:** a switch per command with a search, private messages listed as never logged; the newest entries in full text with server and kind filters.
  - **AFK:** detection on or off and its minutes (1 to 60), kicking on or off, its minutes (5 to 240) and message; everyone AFK now on any server, with a countdown beside anyone close to a kick.
- Everything the tabs change is saved for every server.

## With BSP-Core
- Its database, Redis and server name are used when it is installed (this mod keeps its own tables, prefix `bsp_su_`).
- STAFF ONLINE and ACTION LOG are boards for its Score Screens.
- Its admins can edit; its admin kit gives are logged.

## Commands
- `/rules`, `/actionlog`, `/afk` (everyone); `/su away` (staff).
- `/serverutils` or `/su` (the admin panel), `/su status`, `/su rules list | add <title> [text] | remove <n> | status | reset <player> | askagain`.

## A network of servers
- With shared storage (automatic when BSP-Core's `storage.mode` is `mysql`): the rules and agreements, the staff groups, last-seen times, the action log and its switches, and the AFK settings live in MySQL; notices between servers go through Redis, with the table as the safety net. No database driver is bundled: BSP-Core's full jar or Mohist provides one.
