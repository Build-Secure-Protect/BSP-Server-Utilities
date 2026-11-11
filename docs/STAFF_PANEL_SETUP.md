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
phone / computer ──https──> Cloudflare (Access: staff emails only) ──tunnel──> cloudflared ──> the panel (port 8100)
                                                                                                 │ MariaDB, Redis
                                                     game servers (BSP-Server Utilities) <───────┘
```

## What you need
- **Pterodactyl admin** access, and a shell on the node for part 5 (installing cloudflared) and the map's mounts.
- The **Cloudflare account** that holds `buildsecureprotect.com` (its DNS is on Cloudflare).
- The network's **MariaDB** and **Redis** (the ones BSP-Core and BSP-Server Utilities already share).
- The jars, all built from their repositories:

| What | Version | Where | What the panel uses it for |
|---|---|---|---|
| BSP-Server Utilities | 0.10.0 | every game server **and every player's game** | everything; network protocol 6, so players need this version to join |
| BSP-Core | 1.16.0 or newer (1.16.2 now) | every game server and game | teams on the Players tab; the market and the lottery |
| BSP-InventorySync | 0.7.0 | every game server and game | a player's items, adding and removing them, graves |
| BSP-Teleport | the build with API 2 (3.8.1 now) | every game server | teleports, warps and spawns (also on the map) |
| BSP-Velocity Additions | 0.2.0 | the Velocity proxy | kicks and bans that remove a player from the whole network |
| The staff panel | `bsp-staff-panel-0.2.0.jar` (`staff-panel/build/libs/`) | its own Pterodactyl server | |

Every part is optional apart from BSP-Server Utilities and the panel: without BSP-InventorySync the Items section says
so, without BSP-Teleport there is no TELEPORT, without BSP-Core 1.16 there is no market or lottery, and without
Velocity Additions 0.2.0 a kick may land the player on another server.

**Plan a quiet hour**: parts 1 and 2 restart every game server and the proxy, and players must update their pack
(protocol 6).

## 1. The proxy (BSP-Velocity Additions 0.2.0)
1. Stop the proxy, replace its BSP-Velocity Additions jar in `plugins/` with 0.2.0, start it.
2. Its console says `Network kicks: on, listening on bsp_server_utilities:proxy`. If it says off: in
   `plugins/bsp_velocity_additions/config.toml`, `[networkKick] enabled = true`, restart.

## 2. The game servers (every one)
1. Stop the server. Put the new jars in `mods/`: BSP-Server Utilities, BSP-Core 1.16+, BSP-InventorySync 0.7.0,
   BSP-Teleport (API 2). Start it once and stop it again, so the new config lines are written.
2. In `<world>/serverconfig/bsp_server_utilities-server.toml`:
   - `[storage] mode = "auto"` (or `"mysql"`): the panel needs the shared database. `serverId` empty uses BSP-Core's
     `storage.serverId`; whatever it is, it is the name the panel shows for this server (and the map uses).
   - `[storage.redis] enabled = true` and `channel = "network"` (the same on every server; the panel needs the same
     name, part 3).
3. In `config/bsp_server_utilities-network.toml` (the network file, not the world's):
   - the database and Redis connection, if BSP-Core does not provide them already;
   - `[webPanel] enabled = true`;
   - `actionKey`: **leave it for now**; part 3 gives you the line to paste.
   - `banAppeal`: optional, one line shown under a ban, such as `"Appeal on our Discord: discord.gg/..."` (the same
     on every server).
4. Start the server. Until part 3 is done, `/su status` says `Staff web panel: on, sending this server's players every
   5 s; nothing sent yet.` and `Staff web panel: nothing is sent and no action is taken until webPanel.actionKey is set.`
   That is expected.

## 3. The panel's own server (Pterodactyl)
1. **Create a server** named **BSP-Staff-Panel** with the **generic Java egg** on the same node as MariaDB and Redis.
   Docker image **`ghcr.io/pterodactyl/yolks:java_17`**. Memory **1 GB** (it uses a few hundred MB), disk **2 GB**,
   1 CPU core is plenty.
2. **Its port.** Pterodactyl gives it an allocation (an IP and a port). Use the node's **local network address**
   (192.168.x.x), never a public one, and note the port: Cloudflare's connector will reach the panel there (part 5).
   Do not forward this port on your router. (With cloudflared installed as a service on the node, part 5, an
   allocation on `127.0.0.1` also works and keeps the panel off the local network altogether; Docker's cloudflared needs
   the local address.)
3. **Upload** `bsp-staff-panel-0.2.0.jar` to the server's root (file manager or SFTP).
4. **Startup command** (the egg's startup, or the server's Startup tab):
   ```
   java -Xms128M -Xmx512M -jar bsp-staff-panel-0.2.0.jar
   ```
5. **Start it once.** It writes `panel.properties` and stops with
   `Wrote .../panel.properties: fill in the Cloudflare, database and Redis settings, then start the panel again.`
6. **Back up `panel.properties` now** (download it): its `secretKey` seals every authenticator; losing it means every
   staff member sets up their authenticator again.
7. **Fill in `panel.properties`** (file manager › Edit), line by line:

   | Line | Set it to |
   |---|---|
   | `listen.host` | `0.0.0.0` (inside its container; Pterodactyl decides which address outside reaches it, step 2) |
   | `listen.port` | the allocation's port from step 2 |
   | `publicUrl` | `https://staff.buildsecureprotect.com` (exactly what staff type; no slash at the end) |
   | `cloudflare.teamDomain` | from part 4, like `bsp.cloudflareaccess.com` |
   | `cloudflare.aud` | from part 4, the application's **Application Audience (AUD) Tag** |
   | `database.host`, `.port`, `.name`, `.user`, `.password` | the same MariaDB as BSP-Server Utilities (the network file's database lines, or BSP-Core's). The user needs to create tables. |
   | `database.ssl` | `false` on the local network |
   | `database.tablePrefix` | the game servers' table prefix, `bsp_su_` unless you changed it |
   | `redis.host`, `.port`, `.username`, `.password` | the network's Redis |
   | `redis.channel` | the game servers' `[storage.redis] channel` (part 2), `network` by default |
   | `map.root` | empty for now (the map's guide fills it) |
   | `secretKey`, `actionKey` | made by the panel; never change them |

   Leave `cloudflare.*` empty until part 4 is done: the panel refuses to start without them and says which are missing.
8. **The action key to the game servers.** The first start already wrote it: copy the `actionKey=` value from
   `panel.properties` (once the panel runs, its console's `action-key` prints the whole line too). On **every** game
   server, in
   `config/bsp_server_utilities-network.toml`:
   ```toml
   [webPanel]
   enabled = true
   actionKey = "<the key>"
   ```
   and restart each. Anyone with this key can kick players: keep it in these files only.

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
   - **Service URL**: `http://<the node's local IP>:<the panel's port>` (part 3, step 2), for example
     `http://192.168.1.25:8100`. Plain `http`: the tunnel encrypts the way from Cloudflare, and this last hop stays on
     the node.
   - **Add route**. Cloudflare makes the DNS record for `staff.buildsecureprotect.com` itself.
5. If you made the route before the Access application by mistake: anyone could open the address until part 4 is done.
   The panel still asks for Cloudflare's signed token and refuses without it, but finish part 4 first anyway.

## 6. Start the panel
1. Start BSP-Staff-Panel. Its console ends with `BSP staff panel listening on 0.0.0.0:<port> for
   https://staff.buildsecureprotect.com` and `Type help for the console's commands.`
2. Anything else is a sentence saying what to fix (a missing line, the database or Redis not reachable): fix it and
   start again.

## 7. The first Owner (you)
1. In the panel's console: `create-owner <your email> <your Minecraft name>` (the email you listed in part 4; the
   Minecraft name exactly as in game).
2. It prints a link valid **15 minutes** that works **once**. Open it on your phone or computer: Cloudflare asks for
   your email and sends a code; then the panel's setup page opens.
3. Choose a password (at least 12 characters), scan the QR code with an authenticator app (Google Authenticator,
   Microsoft Authenticator, 1Password, Aegis ...), type the 6-digit code, and **save the ten backup codes** somewhere
   safe. Each works once if the phone is lost.
4. You are in. A link that ran out: `reset-login <email>` in the console prints a new one.

## 8. The other staff
1. More › **Staff accounts** › **+ Add staff**: their email (the one Cloudflare will see), their Minecraft name, the
   rank (Moderator or Admin). Add the same email to the Access policy (part 4) if it is not there yet.
2. The panel shows a link valid 15 minutes: send it to them (privately). They open it, Cloudflare asks for their email
   and code, then they choose a password, set up their authenticator and save their backup codes.
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
   (N players)` and `what this server sends is signed, and kicks and announcements from the panel are taken`.
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
| Cloudflare's page "Bad gateway" or error 1033 | the tunnel cannot reach the panel: the panel is stopped, or the tunnel's Service URL is not the panel's allocation `http://<node's local IP>:<port>` (part 5), or `listen.port` is not the allocation's port |
| "Access denied" from Cloudflare, or no code arrives | the email is not in the Access policy, or One-time PIN is not added or not ticked on the application (part 4) |
| The panel answers "Open this panel through its address, signed in to Cloudflare." | `cloudflare.teamDomain` or `cloudflare.aud` in `panel.properties` is not this application's (part 4), or the page was opened some other way than `https://staff.buildsecureprotect.com`; restart the panel after fixing the file |
| The panel opens but no servers show | the game servers' `webPanel.enabled` is off, their `actionKey` differs from the panel's (unsigned snapshots are dropped), Redis's `channel` differs, or the panel cannot reach Redis or MariaDB (its console says) |
| Snapshots arrive but every action answers "No answer" | Redis must be plain TCP on the local network (the mod and the panel do not speak TLS to Redis); answers come back over Redis only |
| `/su status`: "it needs shared storage" | the server's `storage.mode` is `local`, or the database is not reachable |
| Actions answer "No answer from <server> in 6 seconds" | that server is not hearing the panel: its `actionKey`, Redis channel or Redis connection; the action may still have happened (check the LOG tab in game) |
| A kicked player lands on another server | the proxy runs an older BSP-Velocity Additions, or `[networkKick] enabled` is false (part 1) |
| "Items need BSP-InventorySync 0.7.0" / "Teleports need BSP-Teleport (its API 2)" / "need BSP-Core 1.16.0" | that mod is missing or older on the server the panel asked |
| A staff member lost their phone | they log in with a backup code; or an Owner uses ⋯ › Reset authenticator; an Owner's own: `reset-owner <email>` in the console |
| Locked out after 5 wrong tries | wait 15 minutes, or an Owner unlocks them (⋯ › Unlock now) |
| `secretKey is not a key this panel made` | `panel.properties` was edited or replaced: restore it from the backup (part 3, step 6) |
