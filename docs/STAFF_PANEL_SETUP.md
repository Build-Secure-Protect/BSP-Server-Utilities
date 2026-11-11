# Setting up the BSP staff web panel

Every step, in order, from nothing to staff using the panel on their phones. Each step says where to click or what
to type, and what you should see. Nothing is assumed: words that may be new are explained in **Words used here**. The
map (BlueMap) has its own guide, [STAFF_MAP_SETUP.md](STAFF_MAP_SETUP.md), for later (part 13).

Set aside **an evening**. Part 3 restarts every game server, so choose a quiet hour for that part.

## How it fits together
- Each game server runs **BSP-Server Utilities** (this mod). Every 5 seconds it sends its players (where they are,
  their health and so on) to the panel through the network's **Redis**, and it carries out what the panel asks (kick,
  ban, teleport, items, chat, the lottery draw) once it has checked the panel's signature.
- The **staff panel** is a small Java program that runs as its own server in **Pterodactyl**, like a game server but
  without Minecraft. It keeps its staff accounts, notes and staff chat in a **database of its own** on the network's
  **MariaDB**.
- Nobody reaches the panel directly. **Cloudflare** carries `https://staff.buildsecureprotect.com` to it through a
  **tunnel**, and **Cloudflare Access** lets through only the staff emails you list. Then the panel asks for its own
  password and a code from an **authenticator app**. No port is opened on your router.

```
phone / computer ──https──> Cloudflare (Access: staff emails only) ──tunnel──> cloudflared (on 192.168.1.25)
                                                                                     │
                                                            the panel (Pterodactyl, 172.18.0.1:8100 on 192.168.1.25)
                                                                                     │ MariaDB and Redis (192.168.1.25)
                                                  game servers (BSP-Server Utilities) ┘
```

## Words used here
- **Pterodactyl panel**: the website where you manage your servers (create, start, stop, files, console).
  **Admin area**: the part only you see, through the cog or "Admin" link at the top right of the Pterodactyl panel.
- **Node**: one physical machine that runs servers for Pterodactyl (its program there is called **Wings**). Your
  network has servers on four machines: `192.168.1.25`, `.26`, `.27` and `.28`. MariaDB and Redis are on
  `192.168.1.25`, so the panel goes there too.
- **Allocation**: an address and port Pterodactyl gives a server, such as `127.0.0.1:8100`.
- **pterodactyl0**: the small private network Pterodactyl makes on each machine for its containers, normally
  `172.18.0.1` for the machine itself and `172.18.0.x` for the containers. Pterodactyl puts a server with a
  `127.0.0.1` allocation on `172.18.0.1` instead: only that machine and its containers can reach it.
- **Egg**: a Pterodactyl template saying how to start a kind of server (which program, which Java).
- **Container**: the closed box Pterodactyl runs each server in. Inside it, `localhost` means the box itself, not the
  machine around it. That is why addresses here are written out (`192.168.1.25`).
- **Console**: the black text window on a server's page in Pterodactyl. You can type commands into it.
- **File manager**: the Files tab on a server's page in Pterodactyl, where you upload, edit and delete files.
- **SSH / shell / terminal**: typing commands on a machine from another one. On your Mac: open **Terminal**
  (Applications › Utilities › Terminal) and type `ssh <your user>@192.168.1.25`, where `<your user>` is the login
  you use on that machine (the one you installed Pterodactyl with). It asks for that user's password. Type `exit` to
  leave.
- **sudo**: put in front of a command on the node to run it as the machine's administrator. It asks for your password.
- **MariaDB**: the network's database (the same thing as MySQL for our purposes). **Redis**: the network's fast message
  service, which the servers use to tell each other things at once.
- **Cloudflare Zero Trust** (also called **Cloudflare One**): the part of Cloudflare that controls who may open an
  address. **Access application**: one protected address. **Policy**: the list of who may come in.
- **cloudflared**: Cloudflare's small program that holds the tunnel open from your node to Cloudflare.
- **Authenticator app**: a phone app that shows a new 6-digit code every 30 seconds (Google Authenticator,
  Microsoft Authenticator, 1Password, Aegis...).

## Your network's values
The steps use these values. They were read from older copies of your servers' files, so **check each against the live
servers before you start** (the right-hand column says where), and use the live value wherever it differs.

| What | Value | Where to check it on the live servers |
|---|---|---|
| Machine for the panel, MariaDB and Redis | `192.168.1.25` | where MariaDB and Redis run (the next two rows) |
| MariaDB | `192.168.1.25`, port `3306`, shared database `bsp`, the game servers' user `bsp` | a game server's `config/bsp_core-network.toml`, `[database]` (host, port, database, user) |
| BSP-Server Utilities' table prefix | `bsp_su_` | a game server's `config/bsp_server_utilities-network.toml`, `[database]` `tablePrefix` (part 3, step 4's `su status` shows it too) |
| Redis | `192.168.1.25`, port `6379`, no TLS; its password is in every game server's `config/bsp_core-network.toml`, `[redis]` `password` | the same file, `[redis]` |
| Redis channel | `network` | `su status` in part 3 shows `channel bsp_su:<this>` |
| The panel's address | `https://staff.buildsecureprotect.com` | your choice |
| The panel's port on the machine | `8100`, on pterodactyl0's address (normally `172.18.0.1`; part 6, step 2 checks it) | part 5, step 3 |
| Server names | `BSP-Spawn`, `BSP-SafeRoom`, `BSP-Sanctuary`, `BSP-Frostwarf`, `BSP-Boulderbrook`, `BSP-Moongrove`, `BSP-Stormfall` | the proxy's `velocity.toml`, `[servers]`; each must equal BSP-Core's `serverId` on that server (part 3, step 4) |

If a value differs (another host, user or channel), use the live one everywhere the steps show this one.

Keep a private note open (not a chat, not a screenshot you share) for the passwords and keys you will make: the
panel's database password (part 5), the two keys (part 7), and your panel password and backup codes (part 11).

## 1. Get the jars ready (on your Mac)
You need these files. They are already built on your Mac:

| File | Where it is on your Mac | Where it goes |
|---|---|---|
| `bsp-server-utilities-0.10.1.jar` | `Github/BSP-Server Utilities/build/libs/` | every game server's `mods/`, and the players' modpack |
| `bsp-core-1.16.2.jar` (**not** the `-slim` one: it has no database driver) | `Github/BSP-Core/build/libs/` | every game server's `mods/`, and the modpack |
| `bsp-inventorysync-0.8.0.jar` | `Github/BSP-InventorySync/build/libs/` | every game server's `mods/`, and the modpack |
| `bsp-teleport-3.9.0.jar` | `Github/BSP-Teleport/build/libs/` | every game server's `mods/` |
| `bsp-staff-panel-0.2.1.jar` | `Github/BSP-Server Utilities/staff-panel/build/libs/` | the panel's own server (part 6) |

1. **Players' modpack first.** BSP-Server Utilities 0.10.1 speaks network protocol 6: a player whose game has an
   older version is turned away, told the mod's version differs. Put the new jars (the first three rows) into the
   modpack and publish it the way you always do, so players can update before or as the servers change.
2. **Velocity Additions on the proxy.** A kick from the panel needs Velocity Additions' **network kick**, which is
   only in its newer build. Check: open the proxy's server in Pterodactyl, then **Console**, and look at its start
   (or restart it at a quiet moment). Three cases:
   - It says `Network kicks: on, listening on bsp_server_utilities:proxy (BSP-Server Utilities)`: nothing to do.
   - It says `networkKick.enabled is false; ...`: **Files** › `plugins/bsp_velocity_additions/config.toml`, set
     `enabled = true` under `[networkKick]`, **Save Content**, restart the proxy.
   - It says neither: the proxy has an older build without the network kick.
     The newer one is on your Mac at `Github/BSP-Velocity Additions/build/libs/bsp_velocity_additions-0.2.0.jar`
     (dated 8 November; same version number). Proxy › **Files** › `plugins/`: delete the old jar, upload that one,
     restart the proxy, and check for the `Network kicks: on` line. It also brings Velocity Additions' other changes
     since 0.2.0 (its changelog's "Unreleased": the tab list, rank refresh, JourneyMap ids), untested on your proxy.
   Without this, a kick may move a player to another server instead of off the network.

## 2. Back up first
On each game server, before changing anything: Pterodactyl › the server › **Backups** › **Create Backup** (if your
servers have backups enabled), or at least download its `config/` folder and its world's `serverconfig/` folder from
**Files**. And the database: on `192.168.1.25` (part 5, step 1 says how to get there),
`umask 077` (so only you can read the files you make next), then `sudo mariadb-dump --single-transaction bsp > bsp-before-panel.sql`
(if `mariadb-dump` is not found: the same with `mysqldump`; if `sudo mariadb` needs a password in part 5, step 2, add
`-u root -p` after `mariadb-dump`; if MariaDB runs in Docker,
`sudo docker exec <its name> sh -c 'exec mariadb-dump -uroot -p"$MARIADB_ROOT_PASSWORD" --single-transaction bsp' > bsp-before-panel.sql`,
which reads the root password inside the container so it is never typed).
The file holds the shared tables: keep it private.

## 3. Update every game server (quiet hour)
Do this for each of the seven servers. One at a time is fine: the others keep running. If the proxy's
`velocity.toml` lists any other server (a test server, for example) that runs the BSP mods on the same database,
update its jars the same way (steps 1 to 3) so it matches the others, and leave its `webPanel` off (skip step 5)
unless you want it in the panel too.

1. Pterodactyl › the server › **Console** › **Stop**. Wait until it says it is offline.
2. **Files** › `mods/`. Tick the old `bsp-server-utilities-*.jar`, `bsp-core-*.jar`, `bsp-inventorysync-*.jar` and
   `bsp-teleport-*.jar` and **Delete**. Then **Upload** the four new jars from part 1 (the panel's jar never goes here).
3. **Console** › **Start**. Wait until it says `Done`. This first start writes the new settings into the config files.
4. Type in the console (no slash in the console): `su status`. Find these lines:
   - `Shared storage: mysql 192.168.1.25:3306/bsp as bsp, prefix bsp_su_, this server 'BSP-Frostwarf', ...` and, at its
     end, `redis 192.168.1.25:6379, channel bsp_su:network: connected; sent N, received N, not sent N`. The name in quotes must be this server's
     Velocity name. If it says `Records are kept on this server only`, or Redis is `down` or missing, stop here: see
     **When something does not work**.
   - `Staff web panel: off (webPanel.enabled in config/bsp_server_utilities-network.toml).` That is expected for now.
5. **Files** › `config/` › `bsp_server_utilities-network.toml` (click it to edit). Find the section that starts with
   the line `[webPanel]`. **Edit the lines in that section; never add a second `[webPanel]` line**, because a file
   with two stops the server from starting. Change `enabled = false` to `enabled = true`. Leave `actionKey` and
   `panelKey` empty for now (part 7). Optional: `banAppeal = "Appeal on our Discord: discord.gg/..."` (one line shown
   to banned players; use the same on every server). **Save Content**.
6. Within a few seconds the server reads the file again by itself (no restart). `su status` now says
   `Staff web panel: on, sending this server's players every 5 s; nothing sent yet.` and
   `Staff web panel: nothing is sent and no action is taken until webPanel.actionKey is set.` Expected until part 7.

## 4. The proxy's server list (nothing to change, a check)
The proxy's `velocity.toml` `[servers]` names (`BSP-Spawn`, `BSP-Frostwarf`, ...) must be the same as each server's
name in the `su status` line of part 3 (BSP-Core's `storage.serverId`), and the same as BSP-InventorySync's
`serverId` (empty means BSP-Core's). They are on your network today; this is only to know why it matters: the panel
follows a player who moved by those names.

## 5. The panel's own database (on 192.168.1.25)
The panel keeps its staff accounts, logins, notes and staff chat in a database of its own, with a database user only
the panel has. The game servers' database password sits in every game server's files; whoever has it must not be able
to make themselves Owner of the panel.

1. On your Mac, **Terminal**: `ssh <your user>@192.168.1.25`.
2. Open MariaDB's own command line as its administrator: `sudo mariadb`. You see `MariaDB [(none)]>`.
   - If it says `command not found`, try `sudo mysql`.
   - If that is not found either, MariaDB runs in Docker: `sudo docker ps` lists the containers; find the one whose
     IMAGE says `mariadb` and copy its NAME; then `sudo docker exec -it <that name> mariadb -u root -p` and type the
     MariaDB root password.
   - If `sudo mariadb` says `Access denied`, use `sudo mariadb -u root -p` and type the MariaDB root password.
3. **Where the panel connects from.** The panel runs in a container on this machine, so MariaDB sees it coming from
   pterodactyl0's network, not from `192.168.1.25`. In a second Terminal window, `ssh` to `192.168.1.25` again and
   type `ip -4 addr show pterodactyl0`. The line with `inet` shows an address like `172.18.0.1/16`. Take its first two
   numbers and add `.%`: normally **`172.18.%`**. That is `<host>` below. (If the command says the device does not
   exist, Wings uses another name: `ip -4 addr | grep 172.` shows it.)
   Then check that no login written in the game servers' files is allowed everything: `bsp` (BSP-Core and this mod)
   and any other a plugin or mod on them uses, such as LuckPerms' (`username` under `data:` in a game server's
   `config/luckperms/config.yml`; often `luckperms`). Back in MariaDB, every login there is:
   ```sql
   SELECT user, host FROM mysql.user;
   ```
   Leave out `root`, `mariadb.sys` and `bsp_panel` (if you are doing this again). For the others the game servers use:
   For **each** row it lists, with that row's user and host in place of `<user>` and `<its host>`:
   ```sql
   SHOW GRANTS FOR '<user>'@'<its host>';
   ```
   If a line says `ON *.*` with `ALL PRIVILEGES`, that login can reach every database, the panel's too, and anyone
   who reads a game server's files could make themselves Owner. Change it at a quiet moment (game servers stopped is
   safest). First see which databases exist: `SHOW DATABASES;`. Then, for that login:
   ```sql
   REVOKE ALL PRIVILEGES, GRANT OPTION FROM '<user>'@'<its host>';
   GRANT ALL PRIVILEGES ON <its database>.* TO '<user>'@'<its host>';
   ```
   where `<its database>` is the database that login's config names (`bsp` for `bsp`; for LuckPerms, `database:` in
   the same file). If the list showed other databases
   (not `information_schema`, `mysql`, `performance_schema`, `sys`) that a plugin may use with the same login, add
   one more `GRANT ALL PRIVILEGES ON <name>.* TO ...` line for each, so nothing else loses its access.
4. Make a long new password for the panel (for example, on your Mac in another Terminal window: `openssl rand -base64 24`).
   Note it in your private note. Then, in MariaDB, one line at a time (each ends with `;`), putting your password and
   `<host>` in:
   ```sql
   CREATE DATABASE bsp_panel CHARACTER SET utf8mb4;
   CREATE USER 'bsp_panel'@'<host>' IDENTIFIED BY '<the new password>';
   GRANT ALL PRIVILEGES ON bsp_panel.* TO 'bsp_panel'@'<host>';
   GRANT SELECT ON bsp.bsp_su_players TO 'bsp_panel'@'<host>';
   GRANT SELECT ON bsp.bsp_su_bans TO 'bsp_panel'@'<host>';
   GRANT SELECT ON bsp.bsp_su_web_servers TO 'bsp_panel'@'<host>';
   GRANT SELECT ON bsp.bsp_su_web_items TO 'bsp_panel'@'<host>';
   GRANT SELECT ON bsp.bsp_su_web_item_parts TO 'bsp_panel'@'<host>';
   GRANT SELECT, INSERT ON bsp.bsp_su_notices TO 'bsp_panel'@'<host>';
   GRANT SELECT, INSERT, DELETE ON bsp.bsp_su_web_chat TO 'bsp_panel'@'<host>';
   ```
   Each answers `Query OK`. A `GRANT` that says `Table 'bsp.bsp_su_...' doesn't exist` means no game server has run
   0.10.1 yet (part 3): do part 3 first, then run that line again.
5. **Emoji** (a ban reason typed on a phone may hold one). Make the shared database store them:
   ```sql
   ALTER DATABASE bsp CHARACTER SET utf8mb4;
   ```
   Then look at a game server's console from part 3: if it printed `shared tables are not utf8mb4 ... Once, with the
   servers stopped: ALTER TABLE ...`, run them here at a quiet hour with the game servers stopped (they can take a
   minute on a big Action Log): first `USE bsp;` (the lines name the tables without their database), then paste
   those `ALTER TABLE` lines.
6. Type `exit` to leave MariaDB. Stay logged in to the machine for part 9.

## 6. The panel's server in Pterodactyl
1. **A Java egg** (once). Pterodactyl does not come with a plain "run this Java program" egg.
   - In a browser, open <https://github.com/pelican-eggs/generic>, then the `java` folder, then the file
     `egg-pterodactyl-java-generic.json` (the `.json` one; the `.yaml` beside it is for another panel and does not
     import), then **Download raw file**.
   - A nest to put it in: Pterodactyl › Admin area › **Nests** › **Create New**, name `Programs`, **Save**.
   - Admin area › **Nests** › **Import Egg**: choose that file, **Associated Nest** `Programs`, **Import**.
   - Open the imported egg › **Configuration**: set **Stop Command** to `stop`, and under **Start Configuration** the
     started text to `BSP staff panel listening` (the field reads like `{"done": "..."}`: put that text where the dots
     are). **Save**. This tells Pterodactyl when the panel is up and how to stop it cleanly.
2. **An allocation on 127.0.0.1** (once). Admin area › **Nodes** › the node for `192.168.1.25` (the **FQDN** column
   shows each node's address) › **Allocation**. On the right, **Assign New Allocations**: **IP Address** `127.0.0.1`,
   **Ports** `8100`, **Submit**. Pterodactyl puts such a server on pterodactyl0's address (normally `172.18.0.1`, the
   one you found in part 5, step 3, without the `/16`): it can be reached from that machine (where cloudflared will
   run) and from the containers on it, not from your network or the internet. Note that address: part 9 uses it.
3. **Create the server.** Admin area › **Servers** › **Create New**:
   - **Server Name** `BSP-Staff-Panel`, **Server Owner** you.
   - **Node**: the one for `192.168.1.25`. **Default Allocation**: `127.0.0.1:8100`.
   - **Memory** `1024` MB, **Disk** `2048` MB, **CPU** `100` (one core). Database and backup limits as you like.
   - **Nest** `Programs`, **Egg** the one from step 1. **Docker Image**: choose **Java 17** from the list.
   - **Startup Command**: replace it with
     `java -Xms128M -Xmx512M -Dterminal.jline=false -Dterminal.ansi=true -jar {{JARFILE}}` (without `-Xmx` Java would
     use only a quarter of the memory). The jar's name (**JARFILE** or "Jar File") below it: `bsp-staff-panel.jar`.
   - Untick **Start Server when Installed** (there is no jar to start yet).
   - **Create Server**. Wait until its install finishes (the server page shows it).
   - **Subusers**: none on this server, ever. Whoever can read its files or console can read its keys. And no staff
     member is ever a Pterodactyl admin: every admin can read every server's files, this one's keys included.
4. **Upload the jar.** The server's page (not the admin area) › **Files** › **Upload**: `bsp-staff-panel-0.2.1.jar`.
   Then on the uploaded file, the **⋯** menu › **Rename** to `bsp-staff-panel.jar` (one fixed name, so a later
   update is only a new file with the same name).
5. **First start.** **Console** › **Start**. It prints a few lines and stops by itself with
   `Wrote /home/container/panel.properties: fill in the Cloudflare, database and Redis settings, then start the panel again.`
   That is right: it has written its settings file and made its keys.
6. **Back up `panel.properties` now.** **Files** › `panel.properties` › **⋯** › **Download**. Keep the download
   somewhere private (it holds the keys that seal every staff authenticator and sign every action). If it is ever
   lost, every staff member sets up their authenticator again and every game server needs the new key.

## 7. Fill in the panel's settings, and the keys to the game servers
1. **Files** › `panel.properties` (click to edit). Each line is `name=value`. Lines starting with `#` are notes. Set:

   | Line | Set it to |
   |---|---|
   | `listen.host` | `0.0.0.0` (already) |
   | `listen.port` | leave empty: the panel uses the allocation's port, 8100 |
   | `publicUrl` | `https://staff.buildsecureprotect.com` (already) |
   | `cloudflare.teamDomain`, `cloudflare.aud` | **leave empty for now** (part 8 gives them) |
   | `database.host` | `192.168.1.25` |
   | `database.port` | `3306` (already) |
   | `database.name` | `bsp` (already) |
   | `database.panelName` | `bsp_panel` (already) |
   | `database.user` | `bsp_panel` (already) |
   | `database.password` | the password you made in part 5 |
   | `database.ssl` | `false` (already) |
   | `database.tablePrefix` | `bsp_su_` (already) |
   | `redis.host` | `192.168.1.25` |
   | `redis.port` | `6379` (already) |
   | `redis.username` | leave empty |
   | `redis.password` | the Redis password from a game server's `config/bsp_core-network.toml`, `[redis]` section |
   | `redis.channel` | `network` (already) |
   | `map.root` | leave empty (the map comes later) |
   | `secretKey`, `actionKey`, `signingKey`, `panelKey` | made by the panel: **never change them** |

   **Save Content**.
2. **The two keys to every game server.** In `panel.properties`, copy the value after `actionKey=` and the value after
   `panelKey=` (everything after the `=`, nothing else) into your private note. Never copy `signingKey` anywhere.
   (The panel's console command `action-key` prints `panelKey` only; `actionKey` is read from this file.)
3. On **each** of the seven game servers: **Files** › `config/bsp_server_utilities-network.toml`, in the existing
   `[webPanel]` section, set the two lines (keep the quotes):
   ```toml
   actionKey = "<the actionKey value>"
   panelKey = "<the panelKey value>"
   ```
   **Save Content**. Within a few seconds the server reads the file again by itself. Its `su status` now says
   `Staff web panel: this server's players are sent every 5 s; last sent 2 s ago (N players).` and
   `Staff web panel: what this server sends is signed, and the panel's actions are taken (webPanel.actionKey).`
   If it says a key "is not a key the panel made" or "is not the panel's public key", the copy lost or gained a
   character: copy it again.
4. What the two keys do: `panelKey` is **public**. It can only check the panel's signature, and the panel signs every
   action with `signingKey`, which never leaves `panel.properties`. So nothing on a game server (its files, a mod
   there) can order a kick, a ban or a give. `actionKey` signs what the game servers send to the panel. With it
   someone could show the panel made-up players, so it still stays in these files only.

## 8. Cloudflare Access (who may open the address)
Do this **before** the tunnel (part 9), so the address is never open to anyone. Cloudflare's screens as of November
2026 are below; a label may differ slightly on the day.

1. **Open Zero Trust.** Log in at <https://dash.cloudflare.com> with the account that holds `buildsecureprotect.com`.
   In the left menu, **Zero Trust**.
   - The first time, it asks for a **team name**: any short name nobody else has taken (for example `bsp-staff`; if
     it is taken, it says so: try another). Your **team domain** becomes `<team name>.cloudflareaccess.com`. Note it.
   - It asks for a plan: choose **Zero Trust Free** (up to 50 users). It asks for payment details even for Free;
     nothing is charged.
   - Later the team name is under **Zero Trust › Settings**.
2. **How staff prove who they are: One-time PIN.** **Zero Trust › Integrations › Identity providers › Add new
   identity provider › One-time PIN** › **Save**. Staff then type their email and get a code from
   `noreply@notify.cloudflare.com`, valid 10 minutes. (New Cloudflare accounts no longer add this by themselves.)
3. **Who may come in: a policy.** **Zero Trust › Access controls › Policies › Add a policy**:
   - **Policy name** `BSP staff`, **Action** **Allow**, **Session duration** `1 week`.
   - **Add include** (or **Include**) › selector **Emails** › type every staff email (yours too), one per entry.
   - **Save**. Adding a staff member later: come back here and add their email.
4. **The application (the protected address).** **Zero Trust › Access controls › Applications › Create new
   application** (or **Add an application**) › **Self-hosted** (may read "Self-hosted and private"):
   - **Application name** `BSP Staff Panel`.
   - **Add public hostname**: **Subdomain** `staff`, **Domain** `buildsecureprotect.com` (from the drop-down), no path.
   - **Access policies**: select `BSP staff`.
   - **Login methods**: tick **One-time PIN** only (untick anything you do not use).
   - **Session Duration** `1 week`.
   - **Create** (or **Save**). Anything not allowed is refused.
5. **The two values the panel needs.**
   - The **team domain**, like `bsp-staff.cloudflareaccess.com` (step 1).
   - The **Application Audience (AUD) Tag**: **Access controls › Applications** › on `BSP Staff Panel` **Edit** (or
     **Configure**) › **Additional settings** (or **Overview**) › copy **Application Audience (AUD) Tag** (a long
     row of letters and digits).
   - In Pterodactyl › BSP-Staff-Panel › **Files** › `panel.properties`: `cloudflare.teamDomain=<your team domain>`
     and `cloudflare.aud=<the tag>`. **Save Content**.

## 9. The tunnel (Cloudflare to the panel)
The tunnel is Cloudflare's program **cloudflared**, running on `192.168.1.25`. It keeps an outgoing connection to
Cloudflare and passes the address's visitors to the panel. Nothing comes in through your router.

1. **Make the tunnel.** Cloudflare dashboard (the main one, not Zero Trust) › **Networking › Tunnels** › **Create a
   tunnel** (if asked for a type: **Cloudflared**). Name `bsp-staff` › **Save tunnel** (or **Create Tunnel**).
2. **Install cloudflared on 192.168.1.25.** The next page, **Setup Environment** (or "Install and run a connector"):
   choose **Debian** (choose this for Ubuntu too) and **64-bit**. It shows commands in two boxes.
   - In your Terminal, logged in to `192.168.1.25` (part 5), paste the first box (it adds Cloudflare's package
     source and installs `cloudflared`) and press Enter. It asks for your password (sudo).
   - Then the second box, `sudo cloudflared service install <token>`: type **one space first**, then paste it (a
     command that starts with a space is not kept in the machine's command history). This makes cloudflared start
     by itself after a reboot.
   - The `<token>` (a long `eyJ…` text) **is the key to your tunnel**: anyone with it can run it. Keep it out of
     chats and screenshots. If it ever leaks: on `.25`, `sudo cloudflared service uninstall`; in the dashboard,
     **delete** the tunnel and make a new one (this part again, steps 1 to 4). Only deleting it stops a copy someone
     else already runs.
   - Check: `sudo systemctl status cloudflared` says `active (running)`. Press `q` to leave that view.
3. Back on the dashboard, the connector appears: **Continue**. The tunnel shows **Healthy**.
4. **The route to the panel.** **Networking › Tunnels** › `bsp-staff` › **Routes** (or **Public Hostname**) › **Add
   route** › **Published application**:
   - **Subdomain** `staff`, **Domain** `buildsecureprotect.com`, no path.
   - **Service URL**: `http://172.18.0.1:8100` (the address you noted in part 6, step 2; if a form has a separate
     type box, **HTTP** and `172.18.0.1:8100`). Not `127.0.0.1`: nothing listens there. Plain http is right: the
     tunnel encrypts everything between Cloudflare and the machine, and this last step never leaves the machine.
   - **Add route** (or **Save**). Cloudflare makes the address's DNS record itself.

## 10. Start the panel
1. Pterodactyl › BSP-Staff-Panel › **Console** › **Start**. Within a few seconds it shows these lines (the Redis
   line may come before or after the last two):
   - `Starting the panel on 0.0.0.0:8100 for https://staff.buildsecureprotect.com, ...`
   - `Cloudflare Access: 2 signing keys read.` (the number may differ)
   - `[panel] Redis 192.168.1.25:6379: listening on bsp_su:network:web`
   - `BSP staff panel listening on 0.0.0.0:8100 for https://staff.buildsecureprotect.com`
   - `Type help for the console's commands.`
2. Type `status` in its console: it lists Redis as listening and the game servers sending (`Game servers sending:
   BSP-Boulderbrook, BSP-Frostwarf, ...`).
3. Anything else is a sentence saying what to fix (a missing line, the database not reachable, Redis refused with its
   reason). Fix it in `panel.properties` and **Start** again.

## 11. You, the first Owner
1. In the panel's console: `create-owner <your email> <your Minecraft name>`. Use the email you put in the Access
   policy (part 8) and your Minecraft name exactly as in game. Example: `create-owner you@example.com Mrgregles`.
2. It prints `Owner ... made. Their setup link, valid 15 minutes, works once:` and a link
   (`https://staff.buildsecureprotect.com/setup#...`). Open it on your phone or computer.
3. Cloudflare asks for your email: type it, then the code it emails you. The panel's setup page opens.
   If the page says "Open the link you were sent", Cloudflare's login dropped the end of the link: **open the same
   link again** (now that Cloudflare knows you, it goes straight through).
4. On the setup page:
   - choose a password (at least 12 characters);
   - in your authenticator app, add an account by scanning the QR code (on a phone, **Add to app** does the same);
   - type the 6-digit code the app shows;
   - the page shows **ten backup codes**: **Copy all** or **Save as file**, keep them somewhere safe (each works
     once if your phone is lost), tick "I have saved these somewhere safe", **Go to the panel**.
5. You are in. If the link ran out before you used it: `reset-login <your email>` in the console prints a new one.

## 12. The other staff
1. On the panel: **More › Staff accounts › + Add staff**: their email (the one Cloudflare will see), their Minecraft
   name, the rank (Moderator or Admin). Add the same email to the Access policy (part 8, step 3) if it is not there.
2. The panel shows a link valid 15 minutes: send it to them privately. They open it, Cloudflare asks for their email
   and code, then they do part 11, step 4 for themselves (if their page says to open the link they were sent, they
   open the same link again).
3. What each rank does: **Moderators** see everything and kick, teleport, chat, write notes and tags; **Admins** also
   ban, add and remove items, return graves, broadcast, speak as [Server] and draw the lottery; **Owners** also add
   and remove staff, and are the only ones who ban staff or lift or change a ban on staff. In a Moderator's **⋯**
   menu, an Owner can tick "May add and remove items" for one they trust; their logins then last 48 hours, as Admins'.
4. Someone leaves the team: their **⋯** › **Remove** on the panel, and take their email out of the Access policy
   (then **Revoke existing tokens**, as in **Running it**, if they should be out at once).

## 13. Afterwards (optional)
- **Item pictures**: an Admin or Owner types `/su icons` once in their own game with the full modpack; the panel then
  shows every item with its picture and English name, and the item picker works. Again after a pack update.
- **The map**: [STAFF_MAP_SETUP.md](STAFF_MAP_SETUP.md) (BlueMap's own server, the mounts, `map.root`).

## 14. Checking it works
1. On every game server, `su status`: players sent every 5 s, signed, the panel's actions taken, Redis connected.
2. On the panel, **Players**: every server that is up, with its players, moving every few seconds.
3. With a second account of yours (or a willing player): **Kick** with a reason: they leave the network (not just the
   server) and see the reason. **Teleport** them to a warp. **Items**: their inventory as in the game.
4. **Chat**: say something in game: it shows on the panel within a second; send a line from the panel as [Staff].
5. **More › Market**: the lots and orders as on a Trade Post's boards. **More › Lottery**: the pot and who is in
   (draw only when you mean it: it pays out).
6. **Ban** your second account for 1 hour, try to join: refused on every server with the reason; **Unban**.
7. Open the address in a private window: Cloudflare's login page, never the panel.
8. The checks after the audit, in [TESTING.md](TESTING.md), section "2026-11-11: audit of the whole staff panel"
   (Redis stopped for a minute, a server restarted, a phone in airplane mode, and so on). This setup is the first
   time the panel meets your real Redis and MariaDB: do those checks before staff rely on it.

## When something does not work
| What you see | Why, and what to do |
|---|---|
| A game server's `su status` says `Records are kept on this server only` | BSP-Server Utilities is not using the shared database: in the world's `serverconfig/bsp_server_utilities-server.toml`, `[storage] mode` should be `auto` (it follows BSP-Core's `mysql`); BSP-Core must be the **full** jar (not `-slim`) |
| `su status`: Redis `down`, or no Redis in the storage line | BSP-Server Utilities uses BSP-Core's Redis settings: in the world's `serverconfig/bsp_core-server.toml`, `[storage.redis] enabled = true`; in `config/bsp_core-network.toml`, `[redis]` host, port and password, no TLS; and in the world's `serverconfig/bsp_server_utilities-server.toml`, `useBspCoreRedis = true`. Without Redis the panel's actions are not taken on that server |
| `su status`: "Redis is not connected here, so the panel's actions are not taken" | the same as above |
| A game server will not start after editing the network file | a second `[webPanel]` line was added: open the file, keep one `[webPanel]` section with all its lines, delete the copy |
| The panel stops with `Fill in cloudflare.teamDomain, cloudflare.aud ...` | part 8, step 5 |
| The panel stops with `database.host and redis.host are empty` | part 7, step 1 |
| The panel's console: `[panel] reading the servers' table: ...` or `[panel] reading the chat table: ...` with `command denied` | a grant on the shared `bsp` tables is missing (part 5, step 4): run the `GRANT` lines again |
| `The panel cannot use its database: ...` | the address, user or password in `panel.properties` (part 7), or a grant missing (part 5); `Access denied for user 'bsp_panel'@'172.18.0.5'` (some address) means the user's `<host>` in part 5 does not cover that address: in MariaDB, `RENAME USER 'bsp_panel'@'<old host>' TO 'bsp_panel'@'<first two numbers of that address>.%';` |
| The panel's console: `Redis ...: not listening (Redis refused to subscribe: NOAUTH ...)` or `(Redis refused the login: WRONGPASS ...)` | `redis.password` is empty (NOAUTH) or wrong (WRONGPASS) (part 7). Actions answer "the panel is not connected to Redis" until it listens |
| Cloudflare's error 1033 | cloudflared is not running on `192.168.1.25`: `sudo systemctl status cloudflared`, and `sudo journalctl -u cloudflared -n 50` for why |
| Cloudflare's page "Bad gateway" (502) | cloudflared cannot reach the panel: the panel is stopped, or the route is not `http://172.18.0.1:8100` (part 9, step 4). On `.25`, `curl -sI http://172.18.0.1:8100` must answer `HTTP/1.1 403` (the panel refusing a visitor without Cloudflare); `Connection refused` means the panel is not there; `sudo journalctl -u cloudflared -n 50` shows the address cloudflared tried |
| "Access denied" from Cloudflare, or no code arrives | the email is not in the policy, or One-time PIN is not added or not ticked on the application (part 8) |
| The panel answers "Open this panel through its address, signed in to Cloudflare." | `cloudflare.teamDomain` or `cloudflare.aud` is not this application's; the panel's console says which once a minute; fix `panel.properties` and restart the panel |
| The panel opens but no servers show | a game server's `webPanel.enabled` is off, its `actionKey` differs (the panel's console names a server whose snapshots are not signed with its key), or Redis is not connected (`status` in the panel's console) |
| An action answers "<server> cannot answer the panel: its Redis is not connected" | that game server's Redis (its `su status`); nothing was sent |
| An action answers "No answer from <server> in 6 seconds" | that server did not hear the panel or its answer was lost; it may still have happened (check the LOG tab in game). An action that reaches a server after the panel stopped waiting is refused there |
| `su status`: "no action from the panel is taken until webPanel.panelKey ... is set", or "is not the panel's public key" | part 7, step 3 (`panelKey`) |
| `su status`: "webPanel.actionKey is not a key the panel made" | part 7, step 3 (`actionKey`) |
| A kicked player lands on another server | the proxy's Velocity Additions network kick is off (part 1, step 2) |
| "Items need BSP-InventorySync 0.7.0" / "Teleports need BSP-Teleport (its API 2)" / "need BSP-Core 1.16.0" | that jar is missing or older on the server the panel asked (part 3) |
| An emoji is missing from the Action Log | part 5, step 5 |
| A staff member lost their phone | they log in with a backup code; or an Owner uses their **⋯** › **Reset authenticator**; an Owner's own: `reset-owner <email>` in the panel's console |
| Locked out after 5 wrong tries | wait 15 minutes, or an Owner uses **⋯** › **Unlock now** |
| `secretKey is not a key this panel made` or `signingKey is not a key this panel made` | `panel.properties` was edited or replaced: restore it from your backup (part 6, step 6) |

## Running it
- **Turning it off at once.** One game server: `webPanel.enabled = false` in its network file (read again by itself
  within seconds): it sends nothing and takes no action. The whole panel: **Stop** BSP-Staff-Panel. Nobody at all:
  in Cloudflare, remove the emails from the `BSP staff` policy **and** end the logins already made: Zero Trust ›
  Access controls › Applications › `BSP Staff Panel` › **Edit** › **Revoke existing tokens** (without it, someone
  already in keeps Cloudflare's pass for up to a week).
- **A key leaked** (a file shared, a screenshot):
  - `signingKey` (the serious one: it can order actions): in `panel.properties` empty both `signingKey=` and
    `panelKey=`, **Restart** the panel (it writes a new pair and says so), then put the new `panelKey` on every game
    server (part 7, step 3). Each server refuses the old signature the moment it has the new key.
  - `actionKey` (it can only make the panel show made-up things): empty `actionKey=`, restart the panel (it writes a
    new one), put it on every game server.
  - The tunnel's token: part 9, step 2.
- **Backups.** `panel.properties` (keep the copy as private as the file). The panel's whole database `bsp_panel`
  (`umask 077`, then `sudo mariadb-dump --single-transaction bsp_panel > bsp_panel.sql` on `192.168.1.25`, in the same
  forms as part 2; private, as it holds password hashes and the sealed authenticator secrets), and in `bsp` the tables `bsp_su_bans`,
  `bsp_su_log` and `bsp_su_players`. `bsp_su_web_item_parts` is large and need not be backed up: `/su icons` makes it
  again.
- **Updating the panel.** **Stop** it, **Upload** the new jar, delete the old `bsp-staff-panel.jar`, rename the new
  one to `bsp-staff-panel.jar`, **Start**. The mod and the panel are released together when their messages change:
  update both at once.
- **Restarts.** The panel, Redis, MariaDB and the game servers can each be restarted on their own: each reconnects by
  itself (Redis within 30 seconds). A game server that starts before MariaDB answers waits for it (its console says
  `storage connected` when it does) and refuses banned players from its own copy of the bans meanwhile.
