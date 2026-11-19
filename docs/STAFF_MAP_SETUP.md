# Setting up the staff panel's map (BlueMap)

Every step, in order, for the Map tab of the staff panel. Do the panel first ([STAFF_PANEL_SETUP.md](STAFF_PANEL_SETUP.md));
its **Words used here** and **Your network's values** apply here too.

The Map tab shows every server's overworld, Nether and End (and other modded worlds where they hold anything) as a
flat map, with everyone online moving on it. **BlueMap**'s command-line program draws the map on its own Pterodactyl
server, **BSP-Map**, on `192.168.1.25` beside the panel. Your game servers are on four machines, so:

```
192.168.1.26 (Spawn, Sanctuary) ──┐
192.168.1.27 (Frostwarf, Boulderbrook) ──┼─ each world folder shared read-only (NFS) ──> 192.168.1.25: /mnt/bsp-worlds/<server>
192.168.1.28 (Moongrove, Stormfall) ──┘                         SafeRoom (already on .25) ──┘
                                                                        │ read-only, into BSP-Map
                                              BSP-Map (BlueMap) draws the map into its web folder
                                                                        │ read-only, into the panel
                                              the panel serves it at /map/, behind its own login
```

- Only each server's **world folder** is shared, never the whole server folder: a game server's `config/` holds the
  database and Redis passwords and the panel's keys, and is not shared. (`.25` is trusted in any case: it holds the
  database, Redis and the panel itself.)
- The game machines only hand over files when BlueMap asks; all the drawing happens on `192.168.1.25`.
- BlueMap checks every world for changes every 5 minutes (it cannot see changes on another machine as they happen),
  so the map is at most about 5 minutes behind the game.
- Nothing is added to the game servers, and no port is opened to the internet.

## What you need
- The panel already working (its guide).
- SSH to the four machines (`ssh <your user>@192.168.1.25`, and `.26`, `.27`, `.28`; see **Words used here**).
- BlueMap's command-line program **5.29**: `bluemap-5.29-cli.jar` from
  <https://github.com/BlueMap-Minecraft/BlueMap/releases/tag/v5.29> (under **Assets**; 7.3 MB). It needs **Java 25**;
  only BSP-Map runs Java 25, the game servers and the panel keep theirs.
- `tools/gen_bluemap.py` from this repository (on your Mac at `Github/BSP-Server Utilities/tools/gen_bluemap.py`).
- Room on `192.168.1.25`: about **3 GB** of map in the end (flat), **4 GB of memory** and as many CPU cores as you can
  spare while the first drawing runs (about a day); afterwards only changed land is drawn. To see what `.25` has:
  `nproc` (cores), `free -h` (memory), `df -h /var/lib/pterodactyl` (disk), typed on it after `ssh`.

## 1. Find each server's folder name (its UUID) and world name
Every Pterodactyl server's files live on its machine at `/var/lib/pterodactyl/volumes/<UUID>`, where the UUID is a
long id like `3f9a2c1b-7d4e-4a0b-9c11-2b7f6e8d1a90`.

1. Pterodactyl › Admin area › **Servers** › click a server. Its page shows its **UUID** (the long one, not the short
   8-character id). Copy it into your private note with the server's name and machine.
2. Do that for all seven. Their machines and world folders (each server's `level-name` in `server.properties`) were
   read from older copies of the servers, so check each on the live server (its **Files** › `server.properties`,
   the `level-name=` line) and use the live value wherever it differs:

   | Server | Machine | World folder |
   |---|---|---|
   | BSP-SafeRoom | 192.168.1.25 | `SafeRoom` |
   | BSP-Spawn | 192.168.1.26 | `spawn_void` |
   | BSP-Sanctuary | 192.168.1.26 | `sanctuary_void` |
   | BSP-Frostwarf | 192.168.1.27 | `frostwarf` |
   | BSP-Boulderbrook | 192.168.1.27 | `boulderbrook` |
   | BSP-Moongrove | 192.168.1.28 | `moongrove` |
   | BSP-Stormfall | 192.168.1.28 | `stormfall` |

   Check each on its machine: `sudo ls /var/lib/pterodactyl/volumes/<UUID>/` lists the server's files, and the world
   folder is among them (it holds `region/`).

## 2. Share the worlds from .26, .27 and .28 (NFS)
Do this on **each** of `192.168.1.26`, `.27` and `.28`.

1. `ssh <your user>@192.168.1.27` (for example).
2. Install the sharing service: `sudo apt update` then `sudo apt install nfs-kernel-server`. Answer `Y` if asked.
3. Say what to share: `sudo nano /etc/exports` opens the file in a simple editor. Add one line per game server on
   this machine (the world folder only), with that server's UUID:
   ```
   /var/lib/pterodactyl/volumes/<Frostwarf's UUID>/frostwarf 192.168.1.25(ro,root_squash,no_subtree_check)
   /var/lib/pterodactyl/volumes/<Boulderbrook's UUID>/boulderbrook 192.168.1.25(ro,root_squash,no_subtree_check)
   ```
   `ro`: read-only. `192.168.1.25`: only that machine may read it. Save with **Ctrl+O**, Enter, and leave with **Ctrl+X**.
4. Apply it: `sudo exportfs -ra`, then check: `sudo exportfs -v` lists the lines.
5. If the machine has a firewall: `sudo ufw status`. If it says `Status: active`, allow `.25` in:
   `sudo ufw allow from 192.168.1.25 to any port nfs`. (If it says `inactive` or `command not found`, skip this.)
6. `exit`, then the next machine.

## 3. Mount them on .25
1. `ssh <your user>@192.168.1.25`.
2. Install the client: `sudo apt update` then `sudo apt install nfs-common`.
3. Make the folders, one per server: `sudo mkdir -p /mnt/bsp-worlds/saferoom /mnt/bsp-worlds/spawn /mnt/bsp-worlds/sanctuary /mnt/bsp-worlds/frostwarf /mnt/bsp-worlds/boulderbrook /mnt/bsp-worlds/moongrove /mnt/bsp-worlds/stormfall /mnt/bsp-worlds/mods`
4. Say what to mount at start: `sudo nano /etc/fstab` and add these lines at the end (the UUIDs from part 1):
   ```
   192.168.1.26:/var/lib/pterodactyl/volumes/<Spawn's UUID>/spawn_void /mnt/bsp-worlds/spawn nfs ro,soft,timeo=100,retrans=3,_netdev,nofail 0 0
   192.168.1.26:/var/lib/pterodactyl/volumes/<Sanctuary's UUID>/sanctuary_void /mnt/bsp-worlds/sanctuary nfs ro,soft,timeo=100,retrans=3,_netdev,nofail 0 0
   192.168.1.27:/var/lib/pterodactyl/volumes/<Frostwarf's UUID>/frostwarf /mnt/bsp-worlds/frostwarf nfs ro,soft,timeo=100,retrans=3,_netdev,nofail 0 0
   192.168.1.27:/var/lib/pterodactyl/volumes/<Boulderbrook's UUID>/boulderbrook /mnt/bsp-worlds/boulderbrook nfs ro,soft,timeo=100,retrans=3,_netdev,nofail 0 0
   192.168.1.28:/var/lib/pterodactyl/volumes/<Moongrove's UUID>/moongrove /mnt/bsp-worlds/moongrove nfs ro,soft,timeo=100,retrans=3,_netdev,nofail 0 0
   192.168.1.28:/var/lib/pterodactyl/volumes/<Stormfall's UUID>/stormfall /mnt/bsp-worlds/stormfall nfs ro,soft,timeo=100,retrans=3,_netdev,nofail 0 0
   /var/lib/pterodactyl/volumes/<SafeRoom's UUID>/SafeRoom /mnt/bsp-worlds/saferoom none bind,ro,nofail 0 0
   /var/lib/pterodactyl/volumes/<SafeRoom's UUID>/mods /mnt/bsp-worlds/mods none bind,ro,nofail 0 0
   ```
   The first six read the other machines' worlds over the network. The last two show SafeRoom's world and the
   modpack's `mods` folder (BlueMap takes block colours from the mods) in the same place, read-only. `nofail` on
   every line matters: without it, one mistyped UUID or a machine that is off stops `.25` from starting up properly
   (no SSH, no database, no panel). `soft`: if a machine goes off while BlueMap reads, BlueMap gets an error
   instead of hanging. Save (**Ctrl+O**, Enter, **Ctrl+X**).
5. Check the file before anything else: `sudo findmnt --verify`. It ends with `0 parse errors, 0 errors`. Two
   warnings are harmless: `non-bind mount source /swap.img is a directory or regular file` (Ubuntu's own swap line)
   and `your fstab has been modified, but systemd still uses the old version` (the next step's `daemon-reload` clears
   it). A warning that a source is not found is fine only for a machine that is off. Fix any error before going on, and run this
   again after every change to `/etc/fstab`.
6. Mount everything now: `sudo systemctl daemon-reload` then `sudo mount -a`. No output is good.
7. Check each: `ls /mnt/bsp-worlds/frostwarf/region | head` lists files like `r.0.0.mca`. Do it for all seven
   worlds, and `ls /mnt/bsp-worlds/mods | head` lists jars. If one says `Permission denied`, the world's files are
   not readable by others on its machine: on that machine, `sudo chmod -R o+rX /var/lib/pterodactyl/volumes/<UUID>/<world>`.

**Whenever `.25` restarts, or a game machine was off:** a share that was missing at that moment is not mounted later
by itself, and BSP-Map (started again by Pterodactyl) does not see a share mounted after it started. So on `.25`:
`sudo mount -a`, then `findmnt -t nfs,nfs4` lists six lines (one per network world), then **Restart** BSP-Map.

## 4. BlueMap's own server (BSP-Map)
1. **A BlueMap egg** (once). The panel's egg does not fit: BlueMap ignores anything typed into its console, so its
   **Stop** has to be a signal, and its started text is its own.
   - Pterodactyl › Admin area › **Nests** › **Import Egg**: choose the same `egg-pterodactyl-java-generic.json` you downloaded for
     the panel (its guide, part 6, step 1; download it again the same way if it is gone), the same nest.
   - Open the new egg (the second one with that name) › rename it `BlueMap` › **Save**. Then **Configuration**:
     **Stop Command** `^C` (a caret and a capital C: Pterodactyl sends the stop signal, and BlueMap saves and stops),
     and the started text `Start updating` (where the dots are in `{"done": "..."}`). **Configuration Files** and
     **Log Configuration** come in as `[]`: change each to `{}` (curly brackets), or creating BSP-Map fails with
     "Could not establish a connection to the machine running this server" (the panel's guide, **When something does
     not work**). **Save**.
2. **Create the server.** Pterodactyl › Admin area › **Servers** › **Create New**:
   - **Server Name** `BSP-Map`, **Server Owner** you, **Node** the one for `192.168.1.25` (Admin area › **Nodes**
     shows each node's address under **FQDN**), **Default Allocation** any free one (nothing listens on it; never open
     it in a firewall).
   - **Memory** `4096` MB, **Disk** `20480` MB, **CPU** as many cores as you can spare (`400` for four).
   - **Nest** the one you chose, **Egg** `BlueMap`. **Docker Image**: java 25 is not in the egg's list, so type
     `ghcr.io/pterodactyl/yolks:java_25` into the box for your own image.
   - **Startup**: the jar's name (**JAR FILE**) `bluemap-5.29-cli.jar`, and make the startup command
     `java -Xms1G -Xmx3G -jar {{JARFILE}} -c config -v 1.20.1 -n /mnt/bsp-worlds/mods -g -u`
     (`-v 1.20.1` the game's version, `-n` the mods for block colours, `-g` write BlueMap's page, `-u` draw everything
     and then keep the map up to date). Later changes to it are made only in Admin area › **Servers** › BSP-Map ›
     **Startup**.
   - Untick **Start Server when Installed** (it must not start before its settings are in place).
   - **Create Server**. **Subusers**: none, ever: whoever can change BSP-Map's files can change the page the panel
     shows under its own address.
   - Note BSP-Map's **UUID** (its page in Admin area › Servers, the long id) with the others.
3. **Upload** `bluemap-5.29-cli.jar` to BSP-Map's root (its **Files** › **Upload**). **Do not start it yet**: started
   without its settings it writes its own defaults (part 6 puts the right ones in place).
4. **Let Wings use the two folders** (once). On `.25`: `sudo nano /etc/pterodactyl/config.yml`, and press **Ctrl+W**,
   type `allowed_mounts`, Enter, to find the line. It usually reads `allowed_mounts: []`: replace that whole line
   (and add these lines at the end if there is no such line) with:
   ```yaml
   allowed_mounts:
     - /mnt/bsp-worlds
     - /var/lib/pterodactyl/volumes/<BSP-Map's UUID>
   ```
   (two spaces before each `-`, no `[]` left). Only BSP-Map's folder, never all of `volumes`: that would let a mount
   reach any server's files, passwords included. Save, then `sudo systemctl restart wings`, then
   `sudo systemctl status wings` says `active (running)` (it may say `activating` for the first 10 seconds or so) (if not, the lines are mis-spaced: fix them and restart again).
   Running servers on `.25` keep running.
5. **The worlds into BSP-Map** (a Pterodactyl mount). Admin area › **Mounts** › **Create New**: **Name** `bsp-worlds`,
   **Source** `/mnt/bsp-worlds`, **Target** `/mnt/bsp-worlds`, **Read Only** on, **User Mountable** off, **Create**.
   On the mount's page: **Eggs** add the `BlueMap` egg, **Nodes** add the `.25` node. Then Admin area › **Servers** ›
   BSP-Map › **Mounts** › add `bsp-worlds`.
6. **BSP-Map's map into the panel** (a second mount). Admin area › **Mounts** › **Create New**: **Name** `bsp-map`,
   **Source** `/var/lib/pterodactyl/volumes/<BSP-Map's UUID>`, **Target** `/mnt/bsp-map`, **Read Only** on, **User
   Mountable** off; **Eggs** the panel's Java egg, **Nodes** the `.25` node; then Admin area › **Servers** ›
   BSP-Staff-Panel › **Mounts** › add `bsp-map`. (Mount the whole BSP-Map folder, never a folder inside it: Wings
   skips a mount whose folders above it the server itself could change.)

## 5. BlueMap's settings, written by the script (on .25)
1. Copy the script to `.25`. On your Mac, in **Terminal**:
   `scp "$HOME/Github/BSP-Server Utilities/tools/gen_bluemap.py" <your user>@192.168.1.25:~/`
   (If your Mac cannot log in to `.25` that way, `Permission denied (publickey)`: upload the file to BSP-Map in
   Pterodactyl (**Files** › **Upload**), then on `.25`
   `sudo mv /var/lib/pterodactyl/volumes/<BSP-Map's UUID>/gen_bluemap.py ~/ && sudo chown $USER: ~/gen_bluemap.py`.)
2. On `.25`: check the script with `python3 ~/gen_bluemap.py --self-test`. It prints `self-test: ok`. (If
   `python3: command not found`: `sudo apt install python3`.)
3. Write BlueMap's settings, all on one line or with the `\` line ends as shown (`--threads`: about half of BSP-Map's
   cores: `2` for a CPU of `400`, `4` for `800`):
   ```
   python3 ~/gen_bluemap.py --out ~/bluemap-config --web /home/container/web --data /home/container/data --threads 2 \
     --server "BSP-SafeRoom=/mnt/bsp-worlds/saferoom" --server "BSP-Spawn=/mnt/bsp-worlds/spawn" \
     --server "BSP-Sanctuary=/mnt/bsp-worlds/sanctuary" --server "BSP-Frostwarf=/mnt/bsp-worlds/frostwarf" \
     --server "BSP-Boulderbrook=/mnt/bsp-worlds/boulderbrook" --server "BSP-Moongrove=/mnt/bsp-worlds/moongrove" \
     --server "BSP-Stormfall=/mnt/bsp-worlds/stormfall"
   ```
   - The first part of each `--server` is the server's name **exactly as the panel shows it** (the panel's server
     chips; BSP-Core's `serverId`). A different spelling means the panel cannot find that server's maps.
   - It prints `Wrote N maps to ...` and their names: each server's overworld, Nether and End, plus a modded
     dimension where it holds anything (never AE2's spatial storage, Compact Machines' rooms or Create Teleporters'
     pocket). A line `no world folder at ...` or `cannot read ...` means that mount is missing or its machine is off
     (part 3, step 7): fix it and run the script again before going on, or that world's maps are left out. A line
     `map id ... is already taken` means two `--server` names differ only in punctuation or case.
4. Put the settings in BSP-Map's folder, **replacing** whatever is there (the slashes at the ends matter):
   ```
   sudo rsync -a --delete --exclude packs/ ~/bluemap-config/ /var/lib/pterodactyl/volumes/<BSP-Map's UUID>/config/
   sudo chown -R pterodactyl:pterodactyl /var/lib/pterodactyl/volumes/<BSP-Map's UUID>/config
   ```
   (If `rsync: command not found`: `sudo apt install rsync`. If `chown` says the user is unknown, the files' owner is
   shown by `sudo ls -ln /var/lib/pterodactyl/volumes/<BSP-Map's UUID>`: use those numbers, as `sudo chown -R 988:988 ...`.)

## 6. The first drawing
1. Pterodactyl › BSP-Map › **Console** › **Start**. It downloads Mojang's 1.20.1 game files for the vanilla
   textures, loads the mods (about a minute), then shows `updating map '<id>': n%` for each map in turn.
2. Note for later: how long each map took (the console's times), BSP-Map's highest memory (the graph on its page),
   and at the end the size: `sudo du -sh /var/lib/pterodactyl/volumes/<BSP-Map's UUID>/web/maps` on `.25`. Expected
   about 3 GB and roughly a day, the big survival worlds over the network taking most of it.
3. It says `Your maps are now all up-to-date!` when done, then keeps running and checks the worlds every 5 minutes.
   Leave it running.

## 7. The panel
1. Pterodactyl › BSP-Staff-Panel › **Files** › `panel.properties`: `map.root=/mnt/bsp-map/web`. **Save Content**.
2. **Restart** the panel. Its Map tab shows each map as it is drawn (heads appear at once: the panel puts them there,
   not BlueMap). Until BlueMap has written its page, the Map tab says the map is not set up yet.

## Afterwards
- **Pack updates:** restart BSP-Map so it reads the mods again (SafeRoom's `mods` folder, so update SafeRoom's pack too).
- **A new server or world:** share and mount its world (parts 2 and 3), run the script again with every `--server`,
  put the folder in place with the same `rsync` (part 5, step 4; BlueMap's `config/packs`, where a resource pack for
  modded blocks goes, is kept), restart BSP-Map. A map no longer wanted goes with it; its drawing stays in
  `web/maps/<id>` until you delete that too.
- **A server moves to another machine:** do   part 2 in full on the new machine; on the old machine, delete its line from `/etc/exports` and `sudo exportfs -ra`.
  On `.25`: `sudo umount /mnt/bsp-worlds/<server>` first, then change its line in `/etc/fstab` (part 3) to the new
  machine's address and UUID, `sudo findmnt --verify`, `sudo systemctl daemon-reload`, `sudo mount -a`, and restart
  BSP-Map.
- **A modded dimension shows only its roof:** run the script again with `--ceiling <namespace:path>` (for example
  `--ceiling allthemodium:the_other`): its roof is left out like the Nether's.
- **Blocks in odd colours:** modded blocks drawn by code have no picture BlueMap can read; the worst ones are fixed
  later with a small resource pack (D387).
- **BlueMap's newer versions:** upload the new jar and change **JAR FILE** to its name in Admin area › Servers › BSP-Map › **Startup**; the settings stay.
- **`/etc/pterodactyl/config.yml` replaced by hand** on `.25` (for example with the block from the node's
  **Configuration** tab after resetting its key): that block has `allowed_mounts: []`, so put the two lines of part 4,
  step 4 back and restart Wings, or BSP-Map and the panel lose their mounts. (A key reset that Pterodactyl sends to
  Wings by itself keeps them, but it also sets Wings' port to the node's **Daemon Port** in Pterodactyl, used from
  Wings' next restart: if a Cloudflare tunnel route reaches Wings on another port, change the route to that port.)

## If something is wrong
- **"The map is not set up on this panel yet"**: `map.root` is not `/mnt/bsp-map/web`, the `bsp-map` mount is not on
  the panel's server (part 4, step 6), or BlueMap has not written its page yet (its console shows the first
  `updating map`).
- **One server's maps never change, or show `IOException` in BSP-Map's console**: that machine is off or its share is
  missing: on `.25`, `ls /mnt/bsp-worlds/<server>/region | head`; if empty or `Stale file handle`,
  `sudo umount -f /mnt/bsp-worlds/<server>` (if it says busy: `sudo umount -l /mnt/bsp-worlds/<server>`), then
  `sudo mount -a`, then **Restart** BSP-Map (it keeps the old, broken share until it restarts). Check the machine's
  `sudo exportfs -v` (part 2). After a world was restored from a backup, run `sudo exportfs -ra` on its machine
  first.
- **A server's maps are missing in the panel's server list**: its `--server` name differs from the name the panel
  shows; run the script again with the panel's spelling (part 5).
- **Heads do not move**: they come from the panel's own feed; check the Players tab shows the same players.
- **"Unsupported class file major version"** in BSP-Map's console: the Docker image is not Java 25 (part 4, step 2).
- **"You must accept the required file download in order for BlueMap to work!"** (and BlueMap stops): `config/core.conf`
  was not the script's (it sets `accept-download: true`): put the script's folder in place again (part 5, step 4).
- **The copy buttons in BlueMap's click box do nothing**: the panel's rules for `/map/` block BlueMap's inline click
  handlers (D382). Everything else works.
- **BSP-Map does not see `/mnt/bsp-worlds`**: Wings' `allowed_mounts` (part 4, step 4) or the mount not added to
  BSP-Map (part 4, step 5); restart BSP-Map after adding it. On `.25`, `sudo journalctl -u wings | grep -i mount`
  shows a mount Wings refused and why (the server's console only says to ask an admin).
- **Wings does not come back after its restart** (part 4, step 4; Pterodactyl says "We're having some trouble
  connecting to your server" for every server on `.25`, though the games still run): `sudo journalctl -u wings -n 40
  --no-pager` ends with `bind: address already in use` on Wings' port (443 here). Another program took the port
  while Wings restarted: `sudo ss -ltnp | grep ':443 '` names it. On this network it was Tailscale Serve (`tailscale
  serve status`); move it to another port (`sudo tailscale serve --bg --https=8443 <what it served>`, then
  `sudo tailscale serve --https=443 off`), then `sudo systemctl reset-failed wings && sudo systemctl start wings`.
  If Wings then runs but Pterodactyl still cannot reach it, and Cloudflare answers 502 for the node's address,
  `sudo journalctl -u cloudflared -n 20 --no-pager` names the port the tunnel tries (`dial tcp 127.0.0.1:8080`):
  make the tunnel route's Service URL the port Wings listens on (`sudo ss -ltnp | grep wings`). Wings' port comes from
  the node's Daemon Port in Pterodactyl whenever the node's settings or key are saved.
- **Stop does nothing, or BSP-Map shows Starting forever**: it is on the panel's egg, not `BlueMap` (part 4, step 1):
  Admin area › Servers › BSP-Map › **Startup** › choose the `BlueMap` egg.
