# BSP-Server Utilities 0.4.2: the client check and the setup guide

For Minecraft 1.20.1, Forge 47.4 or newer. 0.4.2 is 0.4.0 with two fixes; coming from 0.3.0 or earlier, everything below is new. **Clients and servers both need 0.4.0 or newer**: the network protocol changed in 0.4.0, so a client on 0.3.0 or earlier is refused by a 0.4 server (and the other way round); 0.4.0, 0.4.1 and 0.4.2 work together, but update both sides to 0.4.2 for the fixes. Works on its own; uses **BSP-Core 1.4.0** or newer, **LuckPerms** and **BSP-InventorySync** when they are installed.

## Fixed in 0.4.2
- On a network with shared storage, SETUP NOW (and `/su setup`) opened the plain admin panel instead of the setup guide, so there was no NEXT, SETUP LATER or DONE and no page could be finished: the guide's data reached the client before the panel. It now follows the panel, so the guide opens with its buttons.

## Fixed in 0.4.1
- The setup card in the top right corner was hidden behind JourneyMap's minimap. It is now drawn after the whole HUD, over minimaps and any other overlay there, and stays clickable with chat open.

## New in 0.4.0

### The client check
- **At login:** every client says, inside Forge's own handshake, which mods it has (with versions and file fingerprints), its resource packs (with fingerprints), the shader Oculus has chosen, its brightness, and the stone test: common stone-type blocks must still have all six faces, draw as solid blocks and not be largely see-through, which catches an xray pack whatever it is called. The login waits for the answer, so a refused player never enters the world; a client that does not answer in 20 seconds is turned away.
- **Refused, with a list:** "You can't join with:", each mod or pack to remove, and the admins' message (160 characters, editable). Switching a blocked pack on in game kicks with the same message.
- **The rules:** a block list of words (matched anywhere in a mod, pack or shader name; xray, x-ray, freecam, baritone, wurst and meteor to start), exact mod ids and packs by fingerprint; names allowed anyway; exempt players. Blocking something a player online already has kicks nobody: they are refused at their next join.
- **Flags for admins, never refusals:** mods not in the trusted setup, pack mods whose file differs, packs not in it, brightness past the slider's top (the player is told staff can see it), mods Forge listed that the report left out.
- **The trusted setup:** an admin's own game, taken with TRUST MY SETUP, saved for the network and written to `config/bsp_server_utilities/trusted_setup.json`; a newer copy dropped onto another server is read at start-up or on `/su clientcheck reload`.
- **The CLIENTS tab:** everyone on the network with their flags, their mods and packs with BLOCK, KICK with a typed reason ("Removed by staff"), today's refusals; the rules page with the network's rules or the server's own. `/su client <player>` in chat.
- Refusals and kicks are in the action log as Client check entries, seen by staff only.

### The setup guide
- **The card:** a few seconds after an operator or admin joins, while anything is not set up, a card in the top right corner and a line in chat offer SETUP NOW, LATER or IGNORE SETUP; the game goes on (press T to click it). Ignoring is per admin.
- **The guide:** the admin panel, page by page: Welcome (this server's name, written to the config file, and its storage), staff groups, rules, action log, AFK, chunk unloading, despawn times, client check, display panels, then Finish with each page set up, off or later. BACK, TURN OFF FEATURE, SETUP LATER and NEXT on every page; closing part way leaves the rest for later. Changing a feature in its own tab counts as set up.
- **Off switches for every feature,** saved for the network: rules, action log (asked twice), despawn times (vanilla's five minutes), client check, staff online, display panels (dark, not placeable), AFK and chunk unloading.
- `/su setup [now | later | ignore]`, SETUP GUIDE on the RULES tab, and a setup line in `/su status`. Servers updating from 0.3.0 see the guide once.

### Also
- The admin panel has seven tabs; the tab keys are narrower so they all fit.
- Shared storage loads its MySQL driver itself (BSP-Core's full jar carries one), so it no longer depends on BSP-Core connecting first.
