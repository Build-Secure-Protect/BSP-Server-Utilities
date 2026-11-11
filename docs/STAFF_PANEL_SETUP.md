# Setting up the BSP staff web panel

Step by step, in order, for the whole staff panel: the mods on the game servers and the proxy, the panel's own
Pterodactyl server, Cloudflare in front of it, the first Owner and the rest of the staff, then the checks. The map has
its own guide, [STAFF_MAP_SETUP.md](STAFF_MAP_SETUP.md) (part 9 here says when).

**How it fits together.** Each game server's BSP-Server Utilities sends its players to the panel every 5 seconds over
the network's Redis (and its MariaDB as the safety net), and carries out what the panel asks (kick, ban, teleport,
items, chat, the lottery draw) after checking the panel's signature. The panel is a small Java program on its own
Pterodactyl server. Nobody reaches it directly: Cloudflare's tunnel carries `https://staff.buildsecureprotect.com` to
it, and Cloudflare Access lets through only the staff emails you list. Then the panel asks for its own password and
authenticator code. No port is opened on your router.

```
phone / computer ──https──> Cloudflare (Access: staff emails only) ──tunnel──> cloudflared ──> the panel (its port)
                                                                                                 │ MariaDB, Redis
                                                     game servers (BSP-Server Utilities) <───────┘
```

## What you need
- **Pterodactyl admin** access, and a shell on the node for part 5 (installing cloudflared) and the map's mounts.
- The **Cloudflare account** that holds `buildsecureprotect.com` (its DNS is on Cloudflare).
- The network's **MariaDB** and **Redis** (the ones BSP-Core and BSP-Server Utilities already share). Redis must be
  plain TCP (no TLS). The database's character set should be **utf8mb4**, or an emoji typed on a phone (in a ban
  reason, a chat line) cannot be stored: `ALTER DATABASE <name> CHARACTER SET utf8mb4;` once. BSP-Server Utilities says
  at start which of its older tables still need `ALTER TABLE … CONVERT TO CHARACTER SET utf8mb4;` (the line is in the
  server's log); run those once with the game servers stopped.
- The jars, all built from their repositories:

| What | Version | Where | What the panel uses it for |
|---|---|---|---|
| BSP-Server Utilities | 0.10.1 | every game server **and every player's game** | everything; network protocol 6, so players need this version to join |
| BSP-Core | 1.16.0 or newer (1.16.2 now), **the full jar, not `-slim`** (the slim one has no MySQL driver, and shared storage then stays off) | every game server and game | teams on the Players tab; the market and the lottery |
| BSP-InventorySync | 0.7.0 or newer (0.8.0 now) | every game server and game | a player's items, adding and removing them, graves |
| BSP-Teleport | API 2 or newer (3.9.0 now) | every game server | teleports, warps and spawns (also on the map) |
| BSP-Velocity Additions | 0.2.0 | the Velocity proxy | kicks and bans that remove a player from the whole network |
| The staff panel | `bsp-staff-panel-0.2.1.jar` (`staff-panel/build/libs/`) | its own Pterodactyl server | |

Every part is optional apart from BSP-Server Utilities and the panel: without BSP-InventorySync the Items section says
so, without BSP-Teleport there is no TELEPORT, without BSP-Core 1.16 there is no market or lottery, and without
Velocity Additions 0.2.0 a kick may land the player on another server.

**Plan a quiet hour**: parts 1 and 2 restart every game server and the proxy, and players must update their pack
(protocol 6).

## 1. The proxy (BSP-Velocity Additions 0.2.0)
1. Stop the proxy, replace its BSP-Velocity Additions jar in `plugins/` with 0.2.0, start it.
2. Its console says `Network kicks: on, listening on bsp_server_utilities:proxy (BSP-Server Utilities)`. If it says off: in
   `plugins/bsp_velocity_additions/config.toml`, `[networkKick] enabled = true`, restart.

## 2. The game servers (every one)
1. Stop the server. Put the new jars in `mods/`: BSP-Server Utilities, BSP-Core 1.16+ (the full jar), BSP-InventorySync
   0.7.0+, BSP-Teleport (API 2+). Start it once and stop it again, so the new config lines are written.
2. In `<world>/serverconfig/bsp_server_utilities-server.toml`:
   - `[storage] mode = "auto"` (or `"mysql"`): the panel needs the shared database. `serverId` empty uses BSP-Core's
     `storage.serverId`; whatever it is, it is the name the panel shows for this server (and the map uses). It must be
     the **same name** BSP-InventorySync and Velocity use for this server (BSP-InventorySync's `serverId`, Velocity's
     server name): the panel follows a player who moved by the name BSP-InventorySync gives.
   - **Redis.** With BSP-Core installed (the usual), BSP-Server Utilities uses **BSP-Core's Redis settings**
     (`config/bsp_core-network.toml`: Redis enabled, plain TCP, no TLS) and its own `[storage.redis] enabled` line is
     not read; `useBspCoreRedis = false` makes it use its own lines instead. Either way `channel` must be the same on
     every server (`network` by default; the panel needs the same name, part 3). The panel's actions need Redis: their
     answers travel only on it.
3. In `config/bsp_server_utilities-network.toml` (the network file, not the world's): in its existing `[webPanel]`
   section (edit the lines there; do not add a second `[webPanel]`, which stops the server from starting):
   - `enabled = true`;
   - `actionKey` and `panelKey`: **leave them for now**; part 3 gives you both;
   - `banAppeal`: optional, one line shown under a ban, such as `"Appeal on our Discord: discord.gg/..."` (the same
     on every server).
   The database and Redis connection come from BSP-Core when it is installed (as above).
4. Start the server. Until part 3 is done, `/su status` says `Staff web panel: on, sending this server's players every
   5 s; nothing sent yet.` and `Staff web panel: nothing is sent and no action is taken until webPanel.actionKey is set.`
   That is expected (then, until `panelKey` is set too: `... no action from the panel is taken until webPanel.panelKey
   ... is set.`). Its storage line must say Redis is **connected**; if it says `no Redis` or `down`, fix BSP-Core's
   Redis settings first (the panel's actions are not carried out on a server without Redis).

## 3. The panel's own server (Pterodactyl)
1. **A Java egg.** Pterodactyl does not ship a plain Java egg: import one once (**Admin › Nests › Import Egg**), for
   example the community's "Generic Java" egg (pelican-eggs / parkervcp), or copy a Minecraft egg and change its
   startup. Its **stop command** must be `stop` and its **started** text `BSP staff panel listening` (the panel prints
   both), so Pterodactyl shows it as running and stops it cleanly.
2. **Create a server** named **BSP-Staff-Panel** with that egg on the same node as MariaDB and Redis. Docker image
   **`ghcr.io/pterodactyl/yolks:java_17`**. Memory **1 GB** (it uses a few hundred MB), disk **2 GB**, 1 CPU core is
   plenty. **Subusers**: none. Whoever can read its files or its console can read its keys (part 11).
3. **Its port.** Pterodactyl gives it an allocation (an IP and a port). Best: install cloudflared **as a service on the
   node** (part 5) and give the panel an allocation on **`127.0.0.1`**: then only the node itself reaches it, not the
   local network nor the game servers' containers. (cloudflared in Docker needs the node's local network address,
   192.168.x.x, instead; never a public one.) Note the port. Do not forward it on your router.
4. **Upload** the jar to the server's root (file manager or SFTP) **renamed to `bsp-staff-panel.jar`**, so an update is
   only a new file with the same name.
5. **Startup command** (the egg's startup, or the server's Startup tab):
   ```
   java -Xms128M -Xmx512M -jar bsp-staff-panel.jar
   ```
6. **Start it once.** It writes `panel.properties` and stops with
   `Wrote .../panel.properties: fill in the Cloudflare, database and Redis settings, then start the panel again.`
7. **Back up `panel.properties` now** (download it): its `secretKey` seals every authenticator; losing it means every
   staff member sets up their authenticator again.
8. **The panel's own database** (D425). The panel keeps its staff accounts, logins, notes and staff room in a database
   of its own, with a user only the panel has: the game servers' database password sits on every game server, and
   whoever has it must not be able to make themselves Owner. On MariaDB, as an admin user (for example
   `mariadb -u root -p` on the node), after part 2 (the game servers have made their tables), with your own database
   name and prefix if they differ from `bsp` and `bsp_su_`, and the address the panel's container comes from:
   ```sql
   CREATE DATABASE bsp_panel CHARACTER SET utf8mb4;
   CREATE USER 'bsp_panel'@'172.18.%' IDENTIFIED BY '<a long new password>';
   GRANT ALL PRIVILEGES ON bsp_panel.* TO 'bsp_panel'@'172.18.%';
   GRANT SELECT ON bsp.bsp_su_players TO 'bsp_panel'@'172.18.%';
   GRANT SELECT ON bsp.bsp_su_bans TO 'bsp_panel'@'172.18.%';
   GRANT SELECT ON bsp.bsp_su_web_servers TO 'bsp_panel'@'172.18.%';
   GRANT SELECT ON bsp.bsp_su_web_items TO 'bsp_panel'@'172.18.%';
   GRANT SELECT ON bsp.bsp_su_web_item_parts TO 'bsp_panel'@'172.18.%';
   GRANT SELECT, INSERT ON bsp.bsp_su_notices TO 'bsp_panel'@'172.18.%';
   GRANT SELECT, INSERT, DELETE ON bsp.bsp_su_web_chat TO 'bsp_panel'@'172.18.%';
   ```
   Then check the game servers' own user has rights on `bsp` only, not on everything: `SHOW GRANTS FOR '<their user>'@'<their host>';`
   must not say `ON *.*` (if it does, give it `bsp.*` instead), so it cannot reach `bsp_panel`.
9. **Fill in `panel.properties`** (file manager › Edit), line by line. Addresses are **as seen from inside the panel's
   container**: `localhost` there is the container itself. The node's MariaDB and Redis are usually reached at the
   address of Pterodactyl's Docker network on the node (often `172.18.0.1`; the game servers' own settings show the
   address they use). MariaDB must listen beyond 127.0.0.1 and its user be allowed from that network (for example
   `'bsp'@'172.18.%'`); Redis must `bind` beyond loopback, with a password (`requirepass`).

   | Line | Set it to |
   |---|---|
   | `listen.host` | `0.0.0.0` (inside its container; the allocation, step 3, decides which address outside reaches it) |
   | `listen.port` | empty: the allocation's port (step 3) |
   | `publicUrl` | `https://staff.buildsecureprotect.com` (exactly what staff type; no slash at the end) |
   | `cloudflare.teamDomain` | from part 4, like `bsp.cloudflareaccess.com` |
   | `cloudflare.aud` | from part 4, the application's **Application Audience (AUD) Tag** |
   | `database.host`, `.port` | the same MariaDB as BSP-Server Utilities (the network file's database lines, or BSP-Core's) |
   | `database.name` | the game servers' shared database (`bsp`) |
   | `database.panelName` | `bsp_panel` (step 8) |
   | `database.user`, `.password` | `bsp_panel` and its password (step 8): never the game servers' user |
   | `database.ssl` | `false` on the local network |
   | `database.tablePrefix` | the game servers' table prefix, `bsp_su_` unless you changed it |
   | `redis.host`, `.port`, `.username`, `.password` | the network's Redis, as the game servers reach it (BSP-Core's Redis lines). A Redis 7 user of its own needs the channels: `&bsp_su:*` |
   | `redis.channel` | the game servers' `[storage.redis] channel` (part 2), `network` by default |
   | `map.root` | empty for now (the map's guide fills it) |
   | `secretKey`, `actionKey`, `signingKey`, `panelKey` | made by the panel; never change them |

   Leave `cloudflare.*` empty until part 4 is done: the panel refuses to start without them and says which are missing.
10. **The two keys to the game servers** (D426). The first start wrote them. On **every** game server, in
   `config/bsp_server_utilities-network.toml`, in the existing `[webPanel]` section, set two lines from
   `panel.properties`:
   ```toml
   actionKey = "<actionKey from panel.properties>"
   panelKey = "<panelKey from panel.properties>"
   ```
   The file is read again by itself within a few seconds (no restart needed); `/su status` then says what this server
   sends is signed and the panel's actions are taken.
   - `panelKey` is **public**: it can only check the panel's signature. Every action is signed with `signingKey`,
     which never leaves `panel.properties`, so nothing on a game server (its files, a mod there) can order a kick, a
     ban or a give.
   - `actionKey` signs what the game servers send to the panel (players, chat, answers): with it someone could show
     the panel made-up players or answers. Keep it in these files only.
   - **Never copy `signingKey` anywhere.** Give nobody (no Pterodactyl subuser) file or console access to
     BSP-Staff-Panel, and keep file access to the game servers to people you trust. If a key leaks: part 11.

## 4. Cloudflare: Access (who may open the address)
Cloudflare's own advice: make the Access application **before** the tunnel's route, or the address is open to anyone
for that moment. Screen names below are from Cloudflare's documentation as of November 2026 (the product is now called
**Cloudflare One**; its menus still say **Zero Trust**); a label may differ slightly on the day.

1. **Zero Trust, once.** In the Cloudflare dashboard, open **Zero Trust**. The first time, it asks for a **team name**
   (for example `bsp`: your team domain becomes `bsp.cloudflareaccess.com`) and a plan: choose **Zero Trust Free**
   (up to 50 users). It asks for payment details even for Free; nothing is charged. The team name is later under
   **Zero Trust > Settings**.
2. **The login method staff use.** **Zero Trust > Integrations > Identity providers > Add new identity provider >
   One-time PIN**. New Cloudflare organisations no longer add it by themselves (their default login lets in only members
   of your Cloudflare account), so add it: staff then type their email and get a code from `noreply@notify.cloudflare.com`,
   valid 10 minutes. (Google can be added the same way, if you prefer.)
3. **Who may come in (a policy).** **Zero Trust > Access controls > Policies > Add a policy**:
   - **Policy name**: `BSP staff`
   - **Action**: **Allow**
   - **Session duration**: `1 week` (D248: phones stay signed in to Cloudflare; the panel's own login is shorter for Admins)
   - **Rules**: **Include** › selector **Emails** › every staff email, one per entry
   - **Save**. A new staff member later: add their email here (part 8).
4. **The application.** **Zero Trust > Access controls > Applications > Create new application > Self-hosted and
   private**:
   - name it `BSP Staff Panel`;
   - **Add public hostname**: subdomain `staff`, **Domain** `buildsecureprotect.com` (from the drop-down);
   - **Access policies**: select **BSP staff**;
   - login methods: tick **One-time PIN** (and nothing you do not use);
   - **Session Duration**: `1 week`;
   - **Create**. Anything not allowed is refused: an application denies by default.
5. **The two values the panel needs.**
   - The **team domain**: `<team name>.cloudflareaccess.com` (step 1) → `cloudflare.teamDomain` in `panel.properties`.
   - The **Application Audience (AUD) Tag**: **Access controls > Applications** › **Edit** on BSP Staff Panel ›
     **Additional settings** › copy **Application Audience (AUD) Tag** → `cloudflare.aud`. It changes only if the
     application is deleted and made again (then copy it again).

## 5. Cloudflare: the tunnel
The tunnel is a small program, **cloudflared**, that runs next to the panel and holds an outgoing connection to
Cloudflare; Cloudflare sends the address's visitors down it. Nothing comes in through your router (it needs only to
reach the internet out on port **7844**, which home routers allow).

1. **Make the tunnel.** In the Cloudflare dashboard (not Zero Trust): **Networking > Tunnels > Create Tunnel**. Name it
   `bsp-staff`, **Create Tunnel**. If it asks for the connector type, choose **Cloudflared**.
2. **Run cloudflared on the node.** Under **Setup Environment** pick **Debian** (Ubuntu) and **64-bit**; the page then
   shows the commands under **Install and Run**. On the node's shell, as root, either
   - **as a service** (recommended: it starts again after a reboot): install `cloudflared` from Cloudflare's package
     repository (the page's install lines, from <https://pkg.cloudflare.com/>), then run the page's
     `sudo cloudflared service install <token>`; or
   - **in Docker**: `docker run -d --restart unless-stopped --name cloudflared cloudflare/cloudflared:latest tunnel --no-autoupdate run --token <token>`.

   The `<token>` (a long `eyJ…` text) **is the key to your tunnel: anyone with it can run it.** Keep it out of chats and
   screenshots. If it ever leaks: the tunnel's page › **Rotate token**, and run the new command.
3. Back on the dashboard, when the connector shows up, **Continue**; the tunnel shows **Healthy**.
4. **The route to the panel.** **Networking > Tunnels** › `bsp-staff` › **Routes** › **Add route** › **Published
   application**:
   - **Hostname**: subdomain `staff`, **Domain** `buildsecureprotect.com`, no path;
   - **Service URL**: `http://<the allocation's IP>:<its port>` (part 3, step 3), for example
     `http://127.0.0.1:8100` with cloudflared as a service, or `http://192.168.1.25:8100` with cloudflared in Docker. Plain `http`: the tunnel encrypts the way from Cloudflare, and this last hop stays on
     the node.
   - **Add route**. Cloudflare makes the DNS record for `staff.buildsecureprotect.com` itself.
5. If you made the route before the Access application by mistake: anyone could open the address until part 4 is done.
   The panel still asks for Cloudflare's signed token and refuses without it, but finish part 4 first anyway.

## 6. Start the panel
1. Start BSP-Staff-Panel. Its console says `Redis <address>: listening on bsp_su:network:web`, then
   `BSP staff panel listening on 0.0.0.0:<port> for https://staff.buildsecureprotect.com` and `Type help for the
   console's commands.`
2. Anything else is a sentence saying what to fix (a missing line, the database not reachable): fix it and start
   again. `Redis …: not listening (…)` gives Redis's reason (a wrong password says so); the panel keeps trying, but
   sends no action until Redis listens. The console's `status` shows Redis and the game servers heard from at any time.

## 7. The first Owner (you)
1. In the panel's console: `create-owner <your email> <your Minecraft name>` (the email you listed in part 4; the
   Minecraft name exactly as in game).
2. It prints a link valid **15 minutes** that works **once**. Open it on your phone or computer: Cloudflare asks for
   your email and sends a code; then the panel's setup page opens. If the page says "Open the link you were sent",
   Cloudflare's login dropped the end of the link: **open the same link again** (now that Cloudflare knows you, it
   goes straight through).
3. Choose a password (at least 12 characters), scan the QR code with an authenticator app (Google Authenticator,
   Microsoft Authenticator, 1Password, Aegis ...), type the 6-digit code, and **save the ten backup codes** somewhere
   safe. Each works once if the phone is lost.
4. You are in. A link that ran out: `reset-login <email>` in the console prints a new one.

## 8. The other staff
1. More › **Staff accounts** › **+ Add staff**: their email (the one Cloudflare will see), their Minecraft name, the
   rank (Moderator or Admin). Add the same email to the Access policy (part 4) if it is not there yet.
2. The panel shows a link valid 15 minutes: send it to them (privately). They open it, Cloudflare asks for their email
   and code, then they choose a password, set up their authenticator and save their backup codes. (If their page says
   to open the link they were sent, they open the same link again.)
3. What each rank does (D250): **Moderators** see everything and kick, teleport, chat, write notes and tags;
   **Admins** also ban, add and remove items, return graves, broadcast, speak as [Server] and draw the lottery;
   **Owners** also add and remove staff. An Owner can give one Moderator the items power (the staff account's ⋯ menu).
4. Someone leaves the team: ⋯ › **Remove** on the panel, and take their email out of the Access policy (part 4).

## 9. Afterwards (optional pieces)
- **Item pictures**: an admin types `/su icons` once in their own game (the full pack); the panel then shows every
  item with its picture and English name, and the item picker works. Again after a pack update that adds items.
- **The map**: [STAFF_MAP_SETUP.md](STAFF_MAP_SETUP.md) (BlueMap's own server, the mounts, `map.root`).
- **Ban appeals**: `banAppeal` in every game server's network file (part 2).

## 10. Checking it works
1. On a game server: `/su status` shows `Staff web panel: this server's players are sent every 5 s; last sent 2 s ago
   (N players).` and `Staff web panel: what this server sends is signed, and the panel's actions are taken
   (webPanel.actionKey).`, and no line saying Redis is not connected or a key is missing.
2. On the panel, **Players**: every server that is up, with its players, moving every few seconds. The panel's console
   `status` says how many pages are open.
3. With a second account of your own (or a willing player): **Kick** with a reason: they leave the network (not just
   the server) and see the reason. **Teleport** them to a warp. **Items**: their inventory as in the game.
4. **Chat**: say something in game: it shows on the panel within a second; send a line from the panel as [Staff].
5. **More › Market**: the lots and orders as on a Trade Post's boards. **More › Lottery**: the pot and who is in (draw
   only when you mean it: it pays out).
6. **Ban** your second account for 1 hour, try to join: refused on every server with the reason; **Unban**.
7. Open the address in a private window without logging in to Cloudflare: Cloudflare's login page, never the panel.

The detailed checks for each feature are in [TESTING.md](TESTING.md) (one dated section per task).

## When something does not work
| What you see | Why, and what to do |
|---|---|
| Cloudflare's page "Bad gateway" or error 1033 | the tunnel cannot reach the panel: the panel is stopped, or the tunnel's Service URL is not the panel's allocation `http://<allocation IP>:<port>` (part 5), or `listen.host` is not `0.0.0.0`, or `listen.port` is set to something other than the allocation's port |
| "Access denied" from Cloudflare, or no code arrives | the email is not in the Access policy, or One-time PIN is not added or not ticked on the application (part 4) |
| The panel answers "Open this panel through its address, signed in to Cloudflare." | `cloudflare.teamDomain` or `cloudflare.aud` in `panel.properties` is not this application's (part 4), or the page was opened some other way than `https://staff.buildsecureprotect.com`; the panel's console names the reason (another application, another issuer, an unknown key) once a minute; restart the panel after fixing the file |
| The panel opens but no servers show | the game servers' `webPanel.enabled` is off, their `actionKey` differs from the panel's (the console names a server whose snapshots are not signed with its key), Redis's `channel` differs, or the panel cannot reach Redis or MariaDB (its console says) |
| An action answers "the panel is not connected to Redis" | the panel's `redis.*` lines (the console's `status` gives Redis's reason); nothing was sent |
| `/su status`: "no action from the panel is taken until webPanel.panelKey ... is set" / "is not the panel's public key" | copy `panelKey` from `panel.properties` again (part 3, step 10) |
| An action answers "<server> cannot answer the panel: its Redis is not connected" | that game server's Redis (BSP-Core's Redis settings, plain TCP; its `/su status`); nothing was sent |
| `/su status`: "it needs shared storage" | the server's `storage.mode` is `local`, or the database is not reachable |
| Actions answer "No answer from <server> in 6 seconds" | that server is not hearing the panel: its `actionKey`, Redis channel or Redis connection; the action may still have happened (check the LOG tab in game). An action that reaches a server after the panel stopped waiting is refused there |
| `/su status`: "webPanel.actionKey is not a key the panel made" | the key was copied wrong (a missing character): copy it again (part 3, step 10) |
| An emoji in a ban reason or a chat line is missing from the Action Log | the database or the table is not utf8mb4 ("What you need"; the game server's log names the tables) |
| A kicked player lands on another server | the proxy runs an older BSP-Velocity Additions, or `[networkKick] enabled` is false (part 1) |
| "Items need BSP-InventorySync 0.7.0" / "Teleports need BSP-Teleport (its API 2)" / "need BSP-Core 1.16.0" | that mod is missing or older on the server the panel asked |
| A staff member lost their phone | they log in with a backup code; or an Owner uses ⋯ › Reset authenticator; an Owner's own: `reset-owner <email>` in the console |
| Locked out after 5 wrong tries | wait 15 minutes, or an Owner unlocks them (⋯ › Unlock now) |
| `secretKey is not a key this panel made` | `panel.properties` was edited or replaced: restore it from the backup (part 3, step 7); `signingKey is not a key this panel made`: the same |

## 11. Running it
- **Turning it off at once.** On a game server, `webPanel.enabled = false` in its network file: within a few seconds
  it sends nothing and takes no action (the file is read again by itself). The whole panel: stop BSP-Staff-Panel; or
  in Cloudflare, take the BSP Staff Panel application's policy off so nobody gets through.
- **A key leaked** (a file shared, a screenshot). `signingKey` (the panel's own; the serious one, it can order
  actions): empty both `signingKey=` and `panelKey=` in `panel.properties`, restart the panel (it writes a new pair
  and says so), and set the new `panelKey` on every game server (part 3, step 10): the old signature is refused on
  each server the moment it has the new key. `actionKey` (on every game server; it can only make the panel show
  made-up things): empty `actionKey=`, restart the panel (it writes a new one), and set it on every game server.
- **Backups.** `panel.properties` (its `secretKey` seals every authenticator; `signingKey` signs every action; keep
  the backup as private as the file). The panel's whole database `bsp_panel` (accounts, logins, notes, tags, the
  staff room, its log), and in the shared database the mod's `<prefix>bans`, `log` and `players`.
  `web_item_parts` is large and need not be backed up: `/su icons` makes it again.
- **Updating.** The panel: stop it, upload the new jar under the same name `bsp-staff-panel.jar`, start it. The mod
  and the panel are released together when the protocol between them changes: update both at once.
- **Restarts.** The panel, Redis, MariaDB and the game servers can each be restarted on their own: each reconnects by
  itself (Redis within 30 s). A game server that starts before MariaDB answers waits for it (it says
  `storage connected` once it does) and refuses banned players from its own copy of the bans meanwhile.
