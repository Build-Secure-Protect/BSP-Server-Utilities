# Setting up the staff panel's map (BlueMap)

The Map tab of the staff web panel shows every server's overworld, Nether and End (and AllTheModium's worlds where
they are used) as a flat map, with everyone online moving on it. BlueMap's command-line renderer draws the map on its
own Pterodactyl server, reading the game servers' worlds through read-only mounts; the panel reads BlueMap's finished
files through another read-only mount and serves them behind its own login. Nothing is added to the game servers, and
no new port is opened.

The first run is the trial (D388): the numbers it gives (time, memory, disk) are the ones to note.

## What you need
- The Pterodactyl panel's **admin** access and a shell on the node (for the mounts).
- BlueMap's command-line renderer **5.29**: `bluemap-5.29-cli.jar` from <https://github.com/BlueMap-Minecraft/BlueMap/releases/tag/v5.29> (7.3 MB). It needs **Java 25**; only BlueMap's server runs Java 25, the game servers and the panel keep theirs.
- Python 3 on the node or your computer, for `tools/gen_bluemap.py` from this repository.
- Expected: about **3 GB** of map for the whole network (flat, D397, D398), **2 to 4 GB of memory**, and a first run of roughly a day on 4 threads (the Mac trial drew 190 chunks a second on 8 threads; the network has about 10.5 million chunks). After that, only changed land is drawn again, within minutes of each server's save.

## 1. BlueMap's own server
1. In Pterodactyl, create a server named **BSP-Map** with a Java egg that runs a jar (Pterodactyl ships none: the one imported for the staff panel, its part 3), on the same node as the game servers. Memory **4 GB** (BlueMap used 3.4 GB on 8 threads; the startup gives Java 3 GB of it, leaving room for the rest), disk **20 GB**, CPU as many cores as you can spare (it renders on half of them).
2. Docker image: **`ghcr.io/pterodactyl/yolks:java_25`** (Startup tab, or the egg's images as admin).
3. Upload `bluemap-5.29-cli.jar` to the server's root with the file manager or SFTP.
4. Startup command (admin: the egg's startup, or the server's Startup tab if it lets you edit it):

   ```
   java -Xms1G -Xmx3G -jar bluemap-5.29-cli.jar -c config -v 1.20.1 -n /mnt/frostwarf/mods -g -u
   ```

   `-v 1.20.1` the game version (BlueMap does not detect it), `-n` one game server's `mods` folder (every server has the same pack: the mods give BlueMap their textures), `-g` write BlueMap's page into its web folder, `-u` draw everything (and write the map's settings), then keep watching the worlds and draw what changes. BlueMap's own web server stays off (`-w` is not used): the panel serves the map.
5. Pterodactyl gives the server a port; nothing listens on it. Do not open it in any firewall.
6. **Do not start it yet**: started without its `config` folder, BlueMap writes its own default settings there (step 3 replaces them).
7. **Who can write BSP-Map's files can act on the panel**: the panel serves BlueMap's page from them under its own address. Give BSP-Map no subusers, add nothing to its `config/packs` but resource packs you trust, and leave `webapp.conf`'s `scripts` empty (the panel only runs `index.html` and scripts under `assets/` there).

## 2. The mounts (Pterodactyl admin)
Each game server's files live on the node at `/var/lib/pterodactyl/volumes/<the server's UUID>` (the UUID is in the admin panel's server page). BlueMap needs to read each world, read-only.

1. On the node, allow mounts from the volumes folder: in `/etc/pterodactyl/config.yml` add
   ```yaml
   allowed_mounts:
     - /var/lib/pterodactyl/volumes
   ```
   then `systemctl restart wings`.
2. Admin › Mounts › Create, once per game server: **Source** `/var/lib/pterodactyl/volumes/<UUID>`, **Target** `/mnt/<name>` (for example `/mnt/frostwarf`), **Read Only** ticked, **User Mountable** unticked. Attach each mount to the node and to the BSP-Map server's egg, then to the **BSP-Map** server (its Mounts tab).
   Wings (1.13.4 and newer, read from its code; not tried) skips a mount whose parent folders can be written by the server's user: mount the **whole volume** (as above), never a folder inside it. `allowed_mounts` lets an admin mount any server's volume into any server: keep every mount admin-only ("User Mountable" unticked).
3. Its file manager will not show the mounts; BlueMap reads them.
4. One more mount, for the panel: **Source** `/var/lib/pterodactyl/volumes/<BSP-Map's UUID>`, **Target** `/mnt/bsp-map`, **Read Only**, attached to the node and to the staff panel's egg, then to the **staff panel's** server.

## 3. BlueMap's settings, written by the script
On the node (where the volumes are), from a copy of this repository:

```
python3 tools/gen_bluemap.py --out /tmp/bluemap-config --web /home/container/web --data /home/container/data --threads 4 \
  --server "Frostwarf=/mnt/frostwarf/frostwarf=/var/lib/pterodactyl/volumes/<Frostwarf's UUID>/frostwarf" \
  --server "Moongrove=/mnt/moongrove/moongrove=/var/lib/pterodactyl/volumes/<Moongrove's UUID>/moongrove" \
  ... one --server for each game server
```

- The first part of each `--server` is the server's **id exactly as the staff panel shows it** (`storage.serverId` in each server's `<world>/serverconfig/bsp_server_utilities-server.toml`, or BSP-Core's `storage.serverId` when that is empty: the names on the panel's server chips). The map's names come from it; a different spelling means the panel cannot find that server's maps.
- The second part is the world folder **as BlueMap's server sees it** (under `/mnt/...`), the third where the script can read it now (on the node). The world folder is each server's `level-name` (Boulderbrook's is `boulderbrook`, Spawn's `spawn_void`, Sanctuary's `sanctuary_void`, SafeRoom's `SafeRoom`).
- It writes the overworld, Nether and End of each server, other modded dimensions only where they hold anything, and never AE2's spatial storage, Compact Machines' rooms or Create Teleporters' pocket (D383). It says which maps it wrote.
- `--threads`: about half of BSP-Map's cores.
- Run `python3 tools/gen_bluemap.py --self-test` first: it checks its map names against the panel's.

Put the written folder in BSP-Map's root as `config` (so `config/core.conf`, `config/maps/...`), **replacing** whatever is there, so no older map file stays behind:

```
rsync -a --delete --exclude packs/ /tmp/bluemap-config/ /var/lib/pterodactyl/volumes/<BSP-Map's UUID>/config/
chown -R pterodactyl:pterodactyl /var/lib/pterodactyl/volumes/<BSP-Map's UUID>/config
```

(the slashes at the ends matter: the folder's contents go into `config`, not a folder inside it). Then start BSP-Map.

## 4. The panel
In the panel's `panel.properties`:

```
map.root=/mnt/bsp-map/web
```

Restart the panel. Until BlueMap has written its page, the Map tab says the map is not set up yet.

## 5. The first run (the trial)
1. Start BSP-Map. The console shows BlueMap downloading Mojang's 1.20.1 client jar, loading resources (the 265 mods: about a minute), then `updating map '<id>': n%` for each map.
2. Note for the trial: when each map finishes (the console's times), BSP-Map's highest memory (Pterodactyl's graph), and the size of `web/maps` at the end. Expected about 3 GB in all and roughly a day.
3. Meanwhile, the staff panel's Map tab shows each map as it is drawn; heads appear at once (the panel puts them there, not BlueMap).
4. When it says `Your maps are now all up-to-date!` it keeps running and watches the worlds. Leave it running.

## Afterwards
- **Pack updates:** restart BSP-Map so it reads the mods again (`-n`).
- **A new server or world:** run the script again with every `--server`, put the folder in place with the same `rsync --delete --exclude packs/` as in step 3 (BlueMap's `config/packs`, where a resource pack for modded blocks goes, is kept), restart BSP-Map. A map no longer wanted goes with it; its drawing stays in `web/maps/<id>` until you delete that too.
- **A modded dimension shows only its roof:** run the script again with `--ceiling <namespace:path>` (for example `--ceiling allthemodium:the_other`): its roof is left out like the Nether's.
- **Blocks in odd colours:** modded blocks drawn by code have no picture BlueMap can read; the worst ones are fixed later with a small resource pack (D387).
- **BlueMap's newer versions:** change the jar's name in the startup; the settings stay.

## If something is wrong
- **"The map is not set up on this panel yet"**: `map.root` is not BlueMap's `web` folder, the panel's mount is missing, or BlueMap has not written its page yet (its console shows the first `updating map`).
- **A server's maps are missing in the panel's server list**: its `--server` id differs from the name the panel shows; run the script again with the panel's spelling.
- **Heads do not move**: they come from the panel's own feed; check the Players tab shows the same players.
- **"Unsupported class file major version"** in BSP-Map's console: the Docker image is not Java 25.
- **"You must accept the required file download in order for BlueMap to work!"** (and BlueMap stops): `config/core.conf` was not the script's (it sets `accept-download: true`): put the script's folder in place again with the `rsync` of step 3.
- **The copy buttons in BlueMap's click box do nothing**: the panel's rules for `/map/` block BlueMap's inline click handlers (D382). Everything else works.
