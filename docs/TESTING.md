# Testing

Headless checks run with `./gradlew runGameTestServer`. The sections below are the in-game checks, one per task, newest at the bottom.

## 2026-10-20: SU-0, the project

Headless: 1 GameTest passes (`mod_is_loaded_beside_bsp_core_and_a_mock_player_can_be_made`).

In game:
1. Put `bsp-server-utilities-0.1.0.jar` in a client's and a server's `mods` folder beside BSP-Core 1.4.0. The game and the server start, and the mod list shows "BSP-Server Utilities 0.1.0".
2. Remove BSP-Core from a test server: it still starts with this mod alone (BSP-Core is optional).
3. On Mohist, the server log shows "BSP-Server Utilities loaded".

## 2026-10-20: SU-1, config, shared storage, LuckPerms and staff

Headless: 7 GameTests pass: storage settings against BSP-Core's (auto, mysql, local; BSP-Core's database and Redis or this mod's own; a TLS Redis left off; the server id), notices through the table once per server, notices through a fake Redis with the table as the safety net, staff group entries, operators as editors and staff, and LuckPerms groups and their members (with the LuckPerms dev jar in `libs/`).

In game (an operator on each server):
1. Start a server with BSP-Core in `storage.mode = "local"`: the log says "BSP-Server Utilities keeps its records on this server only". `/su status` shows the server name from BSP-Core, "Records are kept on this server only", LuckPerms FORGE or BUKKIT, BSP-Core installed.
2. A player who is not an operator (and not in BSP-Core's `admin.admins`) gets "Unknown or incomplete command" for `/su status`.
3. On two Mohist backends with BSP-Core in `storage.mode = "mysql"` and Redis on: each log says "BSP-Server Utilities storage connected: mysql ... (this server is '<name>')" and "Redis connected"; the database gains the table `bsp_su_notices`. `/su status` shows "Shared storage: ... no errors ... redis ...: connected".
4. Stop Redis: `/su status` shows Redis down and the servers carry on; start it again: it reconnects by itself within 30 s.
5. Put `staff.groups = ["admin:Admin:#B58CFF", "mod:Moderator:#19D3B0"]` (your real LuckPerms group names) in `serverconfig/bsp_server_utilities-server.toml` and restart: `/su status` lists the groups. A wrong group name is skipped with a warning in the log.

## 2026-10-20: SU-2, the rules, server side

Headless: 13 GameTests pass, six of them for the rules: the limits and the save format; the gate (nobody held at login itself, held once settled), AGREE refused with a box unticked or for an older copy, taken with every box ticked, recorded, not asked again; ask everyone again (version up, held again, an old answer refused), an edit keeping them held and an empty rule set releasing them, a reset; LEAVE SERVER with the admin's message; a held player's toss coming back, no damage, chat and commands blocked except `/rules`, a step undone, a landing moving the anchor; the shared storage (rules written through and announced, an agreement made on another server counting here, the agreement written for the network, the count, a newer shared copy replacing ours and an older one never).

The popup screen is SU-3, so in game a held player sees nothing yet: test with an admin account that can type in a second window, or wait for SU-3. Checks once SU-3 is in:
1. `/su rules add "Be kind" No hate or spam.` and two more; `/su rules list` shows them numbered; `/su rules status` says version 1, 3 rules, 0 agreed.
2. A fresh player joins: two seconds after landing (or once BSP-InventorySync has restored them) they cannot move, break, place, open chests, drop items (a dropped item stays in the inventory), chat or use commands; `/rules` works. Mobs cannot hurt them.
3. They agree: they move again and get "Thank you for agreeing to the rules". `/su rules status` counts 1. They log out and in, and on another server of the network: not asked again.
4. `/su rules askagain`: everyone online is held at once with the popup.
5. `/su rules reset <name>` on one server while the player is on another: they are held there.
6. LEAVE SERVER disconnects with the message (default "You need to agree to the rules to play. Come back any time.").
7. `rules.enabled = false` in the server config and restart: nobody is held.

## 2026-10-20: SU-3, the popup screen

Headless: 14 GameTests pass (new: the two rules packets survive their byte round trip). **The screen itself has not run in a client.** With SU-2 and SU-3 together, the SU-2 checks above can now be run in game. Also:
1. The popup matches `tools/preview/rules_refined.html`: teal frame, heading in capitals, intro, numbered rules with bold titles, tick boxes on the right, the count at the top right, LEAVE SERVER bottom left, AGREE bottom right.
2. Clicking anywhere on a rule's row ticks and unticks it; the count follows; AGREE lights only with every box ticked and "Every rule ticked" shows in the middle.
3. With more rules than fit (add eight or more, some with long text), the mouse wheel scrolls the list and the bar on the right follows; nothing is drawn outside the list box.
4. Escape and E do nothing; pressing keys does not trigger other mods' key bindings.
5. AGREE closes the popup and the chat says "Thank you for agreeing to the rules. Have fun!".
6. `/rules` afterwards opens the read-only copy: numbers, no boxes, "You agreed on <date>", CLOSE (and Escape) close it.
7. On a small window or a big GUI scale (try GUI scale 4 on a 1280 x 720 window) the panel shrinks to fit and stays readable.
8. While the popup shows, an admin runs `/su rules add "New rule"`: the popup is replaced by the new list (ticks are kept only when the rules did not change).

## 2026-10-20: SU-4, the admin panel and the RULES tab

Headless: 16 GameTests pass (new: every RULES tab action through the server with its refusals, the 20-rule limit, a blank title refused, and both panel packets' byte round trip). **The panel has not run in a client.**

In game (an operator):
1. `/su` opens the violet SERVER UTILITIES panel on RULES, as on `tools/preview/rules_refined.html` (only the RULES tab is there for now; STAFF and LOG come with their tasks). A player who is not an operator or BSP-Core admin gets "Unknown or incomplete command".
2. + Add rule adds "New rule" at the bottom and picks it; type a title and text: about a second after you stop, "Saved" shows at the bottom right, and `/su rules list` in chat shows the change. The counters follow what you type (gold at the limit); the boxes stop at 48 and 400 characters.
3. Clear a rule's title and wait: "A rule needs a title" shows and the rule keeps its old title.
4. UP and DOWN move the rule and the pick follows it; UP on the first and DOWN on the last are greyed.
5. DELETE turns into SURE?; a second click within three seconds deletes, after that it is DELETE again.
6. POPUP TEXT: change the heading, the intro and the leave message; PREVIEW shows the popup with them; ticking, AGREE, LEAVE SERVER and Escape all go back to the panel without sending anything.
7. With twenty rules + Add rule is grey and shows 20 / 20; the list scrolls with the wheel.
8. ASK EVERYONE AGAIN, then SURE?: the version in the footer goes up, the count starts again, and every player online (you too) gets the popup.
9. Two admins with the panel open on two servers: an edit on one shows on the other after it reopens or refreshes (opening the panel always reads afresh).

## 2026-10-20: SU-5, the Display Panel block

Headless: 20 GameTests pass (new: the panel settings and their limits, joining into the largest full rectangle with the join flags, the 8-wide cap, facing, the settings following added blocks, a broken bottom-left block and two panels joined, admins only for placing, digging and breaking, pistons and explosions, the update tag). **The block's look has not been seen in a client** (models and textures from `tools/gen_panel_assets.py`).

In game (an operator, then a player who is not one):
1. The Display Panel is in the Functional Blocks creative tab with its tooltip; its icon shows a black face with a bezel and the teal light.
2. Place one on a wall: a black face with a slim dark bezel all round and a small teal light at the bottom right (as seen from the front), 3 pixels deep, like `tools/preview/panel_refined.html`.
3. Place a 4 x 2: one bezel round the outside, none between the blocks, one light at the bottom right of the whole panel. Right-click any block: "Display Panel 4 x 2, showing text, placed by <you>".
4. Add a column on the left, break the bottom-left block, add one above the top row: the size in the message follows (the largest full rectangle from the bottom-left).
5. As a player who is not an operator: the item cannot be placed ("Only admins can place a Display Panel."), the panel does not crack when mined, right-clicking does nothing; in creative it does not break.
6. As an admin in survival: mining it gives the block back; in creative nothing drops. A piston does not push it; TNT next to it leaves it.

## 2026-10-20: SU-6, text on the panel and its settings screen

Headless: 22 GameTests pass (new: the text layout (sizes as a share of the panel's height on any size, centring, anchors, shrinking a wide line, scaling many lines down inside the bezel, & codes), and saving checked on the server (not an admin, too far, not a panel, saved to every block with the placer kept), both packets' round trip). **Neither the face nor the screen has run in a client.**

In game (an operator):
1. Right-click a 4 x 2 panel: the DISPLAY PANEL screen opens as on `tools/preview/panel_refined.html`: modes on the left (only TEXT lit; the others are dim for now), background swatches, the face preview in 4 x 2 proportions, "No lines yet. + ADD LINE starts one.".
2. + ADD LINE, type "WELCOME TO", then another line "&6BUILD &lSECURE&r PROTECT" at XL: the preview follows as you type; the count shows 20 / 80; gold and bold parts show.
3. Click a line on the preview: it is outlined and the inspector edits it. − and + change the size (S to XL); the colour swatch opens the 16 colours and a # box (typing six hex digits sets it); clicking elsewhere closes the picker. L, C, R and B work.
4. UP, DOWN move lines; DELETE LINE asks SURE? and deletes on the second click within three seconds; with 16 lines + ADD LINE is greyed.
5. CLOSE (or Escape): the panel in the world is unchanged. SAVE: the panel shows the text across the whole 4 x 2, bright at night, inside the bezel, the same as the preview; other players see it too.
6. Pick the chequered background and SAVE: the wall shows behind the text.
7. Make the panel bigger (add a column or a row): the text keeps its proportions; a 1 x 1 panel with long lines shrinks them to fit.
8. A player who is not an operator: right-clicking does nothing.

## 2026-10-20: SU-7, pictures, server side

Headless: 27 GameTests pass (new: PNG, JPEG and GIF read; scaled down to cover the panel and never past its longer side or up; not-a-picture and over-8192 refused; fetching from a local web server (whole, 404, over the limit, a bad address); the images folder (listed with sizes, no way out of it, a missing file); pieces of 32 KB joined again; a panel taking a result on every block, ignoring one for a source it no longer shows, recording a failure; the refresh timer; a real background load arriving on the panel).

Nothing of this shows in game until SU-8 (the Image pane and the picture on the face). What can be checked now on a server:
1. Start it: `config/bsp_server_utilities/images/` exists.
2. The server config has an `[images]` section (`maxDownloadKB` 4096, `timeoutSeconds` 10, `pixelsPerBlock` 128, `cacheMB` 64).

## 2026-10-20: SU-8, pictures on the panel and the Image pane

Headless: 29 GameTests pass (new: the three fits' rectangles and texture coordinates; a save keeping the server's own record of the picture whatever the client sends). **The pane, the textures and the picture on the face have not run in a client.**

In game (an operator; put a picture or two in `config/bsp_server_utilities/images/` on the server first):
1. Right-click a 4 x 2 panel, pick IMAGE: the pane shows SOURCE (WEB ADDRESS, SERVER FOLDER), FIT, REFRESH; the footer says "Not loaded yet" or "Changed: SAVE fetches the picture". The preview says the picture shows after SAVE.
2. SERVER FOLDER lists your files with their pixel size and KB; click one, SAVE: within a second or two the panel in the world shows it, full bright, inside the bezel; open the screen again: the preview shows it and the footer reads "Loaded · W x H PNG · N KB · shown at w x h".
3. Try each fit: FIT INSIDE leaves bands of the background; FILL AND CROP covers the panel and cuts the edges; STRETCH squeezes the whole picture in. With the clear background the wall shows in the bands.
4. WEB ADDRESS: paste the address of a PNG or JPEG on the web, SAVE: it appears the same way. A wrong address shows the reason in red in the footer when the screen is opened again ("The address answered 404", "could not be reached", "bigger than 4096 KB", "not a PNG, JPEG or GIF picture").
5. Set a timer of 5 min, SAVE, change the picture at that address: within five minutes the panel shows the new one. REFRESH NOW does it at once (and is greyed until a change is saved).
6. A second player joins (or walks up): the picture appears for them too; restart the server: the panel shows the picture again after the restart (the server fetches it again when a player needs it).
7. Switch the panel back to TEXT: the lines come back as they were.

## 2026-10-20: SU-9, staff online, server side

Headless: 33 GameTests pass (new: staff groups shared and the newest copy winning; staff on another server shown until their list is 90 s old, away, role order; an operator here online, `/su away` on and off, the last-seen record at logout and the newer sighting winning; offline LuckPerms members in the roster with their group's label).

Nothing shows on a panel until SU-10. What can be checked now:
1. A staff member who is not an operator (in a LuckPerms group listed in `staff.groups`) types `/su away`: "You show as away on the staff panels."; again: "online again". `/su` alone tells them only `/su away` is for them. A player who is neither gets "Unknown or incomplete command".
2. On two servers sharing storage: the database gains `bsp_su_staff_settings`, `bsp_su_staff_groups` (filled from `staff.groups` the first time) and `bsp_su_staff_seen` (a row when a staff member leaves).

## 2026-10-20: SU-10, the Staff online face and the STAFF tab

Headless: 34 GameTests pass (new: the roster and STAFF tab packets round trip, panel view choices kept and round tripped, the tab refusing a non-admin and setting the network's groups for an admin). **Nothing of this has run in a client.**

In game (an operator; LuckPerms with a group or two for staff):
1. `/su`, the STAFF tab: LuckPerms groups listed? Click + ADD A LUCKPERMS GROUP: a group is added and its editor opens: pick its LuckPerms group from the chips, type the label, pick a colour (or type six hex digits), UP / DOWN, REMOVE (SURE?). PEOPLE goes back to the list of everyone with role and where. The operators switch at the bottom left.
2. On a second server the same groups appear in the tab (shared storage).
3. Set a panel to STAFF ONLINE: the face matches `tools/preview/staff_log_refined.html`: the count, a column per role, dots, servers, "away", "3 h". `/su away` turns your dot amber within a second; leaving makes you grey with "now", then minutes.
4. The panel's SHOW chips: ONLINE ONLY hides the offline; the role chips limit the columns; EVERY ROLE shows all.
5. With BSP-Core: a Score Screen's board picker lists STAFF ONLINE; it shows name · role and the server / away / ago at the right.

## 2026-10-20: SU-11, the action log, server side

Headless: 38 GameTests pass (new: commands by an operator, the console, kinds for game mode and give, private messages and non-staff not logged, switches and their own entries; coordinates hidden and counts kept; entries shared once, filtered, pruned after 7 days from the world and the table; items from the creative menu through the packet hook, moving them not logged).

Nothing shows in game until SU-12 (the face, the LOG tab, `/actionlog`). What can be checked now:
1. **On Mohist** (Region Guard's local test server or a backend): an operator runs `/tp`, a WorldEdit command (`//pos1`) and a LuckPerms command (`/lp info`); `/su status` still works; the database table `bsp_su_log` (or the world's `bsp_server_utilities_log` data) holds all three. This checks that plugin commands pass Forge's command event on Mohist.
2. In creative, take a stack from the creative menu: within five seconds one row "took 64 × diamond from creative" appears; moving stacks around adds nothing.
3. `/msg` and `/tell` never appear; a player who is not staff or an operator never appears; console commands appear as Console.

## 2026-10-20: SU-12a, the Action log face and its pane

Headless: 39 GameTests pass (new: the newest 200 entries sent to players with coordinates hidden, how long ago and who in their colour, the view round tripping; a face's filters for every server or this one, kinds and 6 / 8 / 12 lines; a log panel keeping known kinds only; what the server sends right after an entry). **Nothing of this has run in a client.**

In game (an operator; staff groups set up in the STAFF tab):
1. Right-click a panel, pick ACTION LOG: the face in the preview matches the log face on `tools/preview/staff_log_refined.html` (violet ACTION LOG, "all servers · kept 7 days", the table). Under it: SHOW (ALL SERVERS / THIS SERVER), KINDS (EVERY KIND and one chip per kind; several can be on), LINES (6 / 8 / 12). SAVE.
2. In the world the face fills the panel; try a 2 x 1, a 4 x 2 and an 8 x 6 panel: the rows fit, long commands end in "…", names keep their role colour.
3. Run `/tp 100 64 100`: within a second the top row reads now, your name, `/tp · · ·` and the server. Leave it a few minutes: the time counts up by itself ("2 min").
4. THIS SERVER: the server column goes and the heading reads "<server> only · kept 7 days"; on two servers sharing storage the other server's entries show only with ALL SERVERS.
5. KINDS set to GAME MODES: only `/gamemode` rows; a log with nothing to show reads "Nothing logged yet."
6. A player who is not staff, standing at the panel, sees the same face (coordinates hidden for everyone on panels).

## 2026-10-20: SU-12b, the LOG tab

Headless: 40 GameTests pass (new: the tab's switches for every known command with private messages last and marked, full text for staff, server and kind filters, the packets round tripping, a player refused, an admin turning `/weather` off and on with the change written as an entry). **Nothing of this has run in a client.**

In game (an operator):
1. `/su`, the LOG tab: the look matches the LOG tab on `tools/preview/staff_log_refined.html`. The left column lists commands (`/advancement` …) with violet switches, `/msg`, `/tell`, `/w` and the other private messages at the end as "never (private)". Typing `tp` in the box narrows it to `/tp`, `/tphere` …; the wheel over the column scrolls it.
2. Turn `/tp` off: the switch goes grey at once, and within a second the right side's top entry reads "stopped logging /tp" by you. Run `/tp ~ ~ ~`: nothing new. Turn it on again: "started logging /tp".
3. The right side shows coordinates in full (`/tp 100 64 100`); a long command ends in "…" and hovering it shows the whole text. The wheel over the entries scrolls them.
4. The footer keys: ALL SERVERS ▾ opens a list of the servers in the log (pick one: only its entries); ALL KINDS ▾ lists the kinds. A click outside an open list closes it.
5. With the tab open, run a command in another window or on another server: the entry appears by itself within a second or two.
6. On a second server sharing storage, the switch you turned shows the same.

## 2026-10-20: SU-12c, `/actionlog` and the ACTION LOG board

Headless: 41 GameTests pass, twice (new: `/actionlog` usable by a player who is not staff and running for them; what the screen gets has coordinates hidden; its packet round trips). **Nothing of this has run in a client.**

In game:
1. As a player who is not staff, type `/actionlog`: a teal screen as on `tools/preview/staff_log_refined.html` (section 3): ACTION LOG, "what staff did, kept 7 days", the intro, the entries with `/tp · · ·` style coordinates, the server chips, "Newest 100 · scroll for more · coordinates hidden", CLOSE. The wheel scrolls, Escape closes, hovering a cut line shows all of it.
2. Leave it open while an operator runs a command: the entry appears at the top within a second or two.
3. A player held by the rules popup cannot run `/actionlog` (only `/rules`).
4. With BSP-Core: a Score Screen's board picker lists ACTION LOG; it shows the name in the role's colour, the masked text, and how long ago at the right. STAFF ONLINE still shows as before.

## 2026-10-20: SU-13, AFK detection, server side

Headless: 46 GameTests pass, twice (new: the settings within their limits, saved and announced for the network, the newest copy winning; four minutes idle is not AFK, five is, a push does not count, a head turn and using an item bring a player back, `/afk` marks them and another command clears it, detection off marks nobody; AFK staff shown away and a typed `/su away` staying; a warning a minute before the limit, the kick with the message at 30 minutes, operators never warned or kicked, kicking turned on sending off a player already past the limit; the network AFK list with another server's players until their list is 90 s old). The settings are edited in the AFK tab (SU-14); until then they are the defaults (AFK after 5 minutes, kicking off). **Nothing of this has run in a client.**

In game:
1. Stand still without touching the mouse for 5 minutes: "You are now AFK." in gold. Move the mouse: "You are back." in green. Walking forward without turning, chatting, breaking a block, opening a chest or clicking in your inventory also bring you back.
2. Stand in flowing water or ride a minecart on a loop without touching anything: you still go AFK after 5 minutes.
3. `/afk`: AFK at once; your next action brings you back.
4. As staff: go AFK and look at a Staff online panel: your dot is amber with "away" (from another server within a second or two with Redis); act again and it is green. `/su away`, then go AFK and come back: still away until `/su away` again.
5. `/su status` ends with the AFK line ("marked AFK after 5 min idle; kicking is off").
6. A player who has not agreed to the rules yet is not marked AFK while the popup is open.

## 2026-10-20: SU-14, the AFK tab

Headless: 47 GameTests pass, twice (new: the tab's packets round trip, a player refused, an admin's settings becoming the network's, an AFK player listed with how long and as kickable, an operator away by `/su away` listed as such and never kicked). **Nothing of this has run in a client.**

In game (an operator):
1. `/su`, the AFK tab: the look matches `tools/preview/afk_refined.html` (four tabs RULES, STAFF, LOG, AFK; settings on the left, AFK NOW on the right, the footer line and REFRESH).
2. AFK AFTER − and + move 1 minute at a time between 1 and 60; KICK AFTER moves 5 at a time between 5 and 240; the switches flip at once. `/su status` shows the new values; so does a second server sharing storage.
3. Type a kick message: the counter follows up to 160; a second after typing stops it is saved (close and reopen the tab to see it kept).
4. With a second player idle past the AFK time: they appear under AFK NOW with the minutes counting up. Turn kicking on with KICK AFTER at 5: within five minutes of the limit a red "kicked in 4 min" appears; the player sees the warning a minute before and is sent off with your message at the limit.
5. A staff member who typed `/su away` shows as "away (/su away)" and never gets a countdown.

## 2026-10-21: SU-15, chunk unloading, server side

Headless: 52 GameTests pass, twice (new: a machine's ticket kept at four minutes away, taken at five and kept as the owner's, taken again when the mod adds it back, back as it was on join; BSP-Core's tickets (BSP-Core loaded in the test run) and an exempt mod's left alone, exempting a mod giving its tickets back; a machine broken while unloaded forgotten with its placer record; always loaded players kept, UNLOAD NOW, marking always loaded giving back at once, RESTORE NOW holding until the player joins and leaves; owners read from saved data (an `ownerUUID` deep down, a skull's owner, a Most / Least pair, a UUID string, none); a machine with no owner listed, unloaded by hand, kept unloaded when the mod adds it back, restored; the settings within their limits). The tests use this mod's own id for the tickets, since Forge accepts only loaded mods' ids. Settings are edited in the CHUNKS tab (SU-16); until then they are the defaults (on, 5 minutes). **Nothing of this has run in a live game.**

On a test server (the pack, one other player or a second account):
1. The other player places an RFTools Builder with chunk loading on (or a Mekanism machine with an Anchor upgrade, an AE2 spatial anchor, a Flux point) somewhere you can watch, e.g. a furnace line next to it. They leave.
2. Stay far away. After 5 to 6 minutes `/su status` shows tickets unloaded; the machine's area stops working (a furnace there stops smelting; check with `/forceload query` or a mod's chunk overlay if you have one).
3. They join again: "Non-totem chunks were unloaded while you were away; they are loaded again now." in gold, and the area runs again within a few seconds.
4. A placed Shatter Totem with Anchor stays loaded the whole time (BSP-Core's chunks are never touched).
5. A machine placed before this mod was installed: its owner comes from its own data (most of these mods save one) or the Region Guard claim; if neither, it stays loaded (it will show in the CHUNKS tab under "no owner found").
6. Restart the server while the player is away: their machines stay unloaded after the restart and come back when they join.

## 2026-10-21: SU-16, the CHUNKS tab

Headless: 53 GameTests pass, twice (new: the tab's data with the owner away two minutes and one chunk loaded by which mod, a machine with no owner listed under nobody, BSP-Core left out of the mod chips, the packets round trip, a player refused, UNLOAD NOW and RESTORE NOW, UNLOAD and RESTORE on a machine with no owner, the settings becoming the network's). **Nothing of this has run in a client.**

In game (an operator, with SU-15's machines from the section above):
1. `/su`, CHUNKS (the fifth tab; RULES to CHUNKS all fit beside the title): the look matches `tools/preview/chunks_refined.html`.
2. Left: the switch, AFTER − / + (1 to 60), the chips of mods holding chunks here (`bsp_core (always)` greyed), click one to exempt it (violet); ALWAYS LOADED: + add a player, type a name that has played here, ADD; click a name to remove it.
3. Right: the player with machines, "away 2 min" in amber while under the time, grey after; the green and red counts follow the minute's check. Click the row: their machines with mod, place and chunks; RESTORE NOW brings them back (and they stay while the player is away); UNLOAD NOW wants a second click within three seconds. PLAYERS goes back.
4. NO OWNER FOUND lists machines nobody could be matched to (placed before the mod, no saved owner, no claim); UNLOAD turns the line red and the chunk unloads; RESTORE brings it back.

## 2026-10-22: SU-17, despawn times and clearing items, server side

Headless: 58 GameTests pass, twice (new: items, an orb and a death drop removed at 5 and 15 minutes of running time, an exception of 1 minute and a never exception, vanilla's lifespan set a little longer than ours, the count for `/su status`, BSP-Core's items never timed or cleared; items loaded from disk that ran out, or lay there before a clear, turned away, a never item and a fresh one kept; the clear warning at 60 and 30 seconds, replaced by a second clear, taking the item and the orb and keeping the never item, the admin's count, the time kept for unloaded chunks; the check over 5,000 items timed at about 1.5 ms; settings within their limits). The settings are edited in the DROPS tab (SU-18); until then the defaults hold (items 5 minutes, death drops 15, orbs as items). **Nothing of this has run in a live game.**

In game:
1. Drop a stack and walk 200 blocks away for 5 minutes, then come back: it is gone (vanilla would have kept it, since its chunk stopped ticking).
2. Die with things in your inventory: they are still there after 6 minutes, gone after 15.
3. `/su status` ends with the Drops line and the counts of the last hour.
4. `/su clearitems here`: a red warning at once ("60 seconds"), another at 30, then "Items on the ground were cleared (n)." in gold and your own grey line with the count. A Shatter Totem on the ground stays.
5. With two servers sharing storage: `/su clearitems all` from one; both warn and clear; you get a line for each server. `/su clearitems <the other server>` clears only that one; tab completion offers the server names.
6. Clear, then visit a far chunk where items lay before: they are gone on arrival.

## 2026-10-22: SU-18, the DROPS tab

Headless: 59 GameTests pass (new: the tab's packets round trip, a player refused for settings and for a clear, an admin's times and an exception becoming the network's, an unknown server starting nothing, CLEAR ITEMS for this server starting the countdown and the tab showing it). **Nothing of this has run in a client.**

In game (an operator):
1. `/su`, DROPS (the sixth tab; the title shrinks to fit): the look matches `tools/preview/drops_refined.html`.
2. Left: the switch, ITEMS and DEATH DROPS − / + (1 to 60), the orbs switch; `/su status` shows the new times.
3. CLEAR ITEMS NOW: the chips This server, All servers and the other servers heard from; CLEAR ITEMS wants a second click; while counting down it reads COUNTING DOWN · CLEAR AGAIN (pressing it twice again restarts the countdown).
4. Right: + add an item, type `diam`: Diamond, Diamond Block, Diamond Pickaxe ... (modded items too, by name or id); pick one, − / + up to 120 or NEVER, ADD: the line appears with its time (never in gold); × removes it. Drop that item and check it lasts its own time.

## 2026-10-23: SU-19, the client check: the client's report

Headless: 62 GameTests pass (new: a report read back exactly over the wire and as text; bounded counts and lengths, the largest report under a login answer's 1 MB, a packet claiming too many mods refused; the latest report kept per player with Forge's mod list, a report in game replacing it at most once a second). The verdict comes in SU-20: until then every report is kept and nobody is refused. **The handshake itself has not run with a real client, through Velocity and Ambassador, or on Mohist.**

In game (a client with this version, a dedicated server; the log at debug level shows the handshake):
1. Join: the login takes no longer than before (the mods' files were read while the game started). The server's log has no "Client check failed" line.
2. Join through the proxy, then switch servers: each server asks again (Ambassador re-runs the handshake).
3. A client with an older version of this mod is refused by Forge's channel check (protocol 1 against 2).
4. Turn on a resource pack in Options while on the server: after the reload the client sends its report again (no message yet; SU-20 judges it).
5. Singleplayer: opening a world is not slowed and asks nothing.

## 2026-10-23: SU-20, the client check: rules, verdict, kicks, commands

Headless: 69 GameTests pass, twice (new: every rule of the verdict (a word in an id, in a display name, an exact id, allowed anyway, a pack by fingerprint whatever its name, a pack word, the stone test with its pack, a shader word, an exempt player, the flags: not trusted, file differs, brightness, left out of the report; nothing "not trusted" without a setup); rules and the setup kept to their limits and round tripping; a refusal's message, its log entry and today's list (two days ago is not today); a pack switched on in game kicking with only the new thing named, a newly blocked mod flagging and kicking nobody; TRUST MY SETUP judging everyone again and writing the file, the same file older, a newer file taken, a broken one ignored; the network's rules saved and announced, a server's own copy kept apart and the network's kept underneath; another server's player listed, a staff kick sent on as a notice, a kick from another server reaching a player here with the reason cleaned). The CLIENTS tab comes in SU-21. **Nothing of this has run with a real client, on Mohist, through the proxy, or against real MySQL and Redis.**

In game (an operator; a second client to be refused helps):
1. `/su status` ends with "Client check: the network's rules, 6 block-list entries, trusted setup not taken yet; 0 refused today."
2. Join with a resource pack whose file name has "xray" in it (any small pack renamed): refused on the "Failed to connect to the server" screen, red "You can't join with:", the pack in gold "(resource pack)", the white line under it. The Action Log (LOG tab, kind Client check) shows "refused <name>: ...".
3. A real xray pack renamed to something harmless: refused for see-through stone, naming the pack. Try the pack's own name too.
4. Join clean, then switch the pack on in Options: kicked with the same message after the reload.
5. Set the brightness past 100 % (options.txt `gamma:5.0`): joining shows the gold line once; `/su client <you>` lists "Brightness 500 %".
6. `/su client <player>`: the mods and packs counts, the shader and the stone test line.
7. `/su clientcheck export` before any setup: told to press TRUST MY SETUP first (the tab arrives in SU-21). Drop a `trusted_setup.json` from another server into `config/bsp_server_utilities/` and `/su clientcheck reload`: read when newer, "not newer" otherwise.
8. Two servers: a player refused on one appears in the other's `/su status` count for today.

## 2026-10-23: SU-21, the CLIENTS tab

Headless: 70 GameTests pass, twice (new: a player who may not edit changes nothing; the picked player's report and everyone's rows; the tab's data and actions round trip; TRUST MY SETUP flags the other player and puts them first; BLOCK adds a mod, marks them blocked and kicks nobody; a pack without a fingerprint cannot be blocked; own rules from the tab; EXPORT writes the file; KICK with the typed reason). **The tab has not been drawn in a client.**

In game (an operator, and a second player with an extra mod such as a minimap):
1. `/su`, CLIENTS (the seventh tab; all seven fit beside the title, which shrinks): the look matches `tools/preview/clients_refined.html`.
2. PLAYERS: both players listed, yourself with ✓. RULES, TRUST MY SETUP, a second click: the footer reads "Trusted setup <date> from <you>"; back on PLAYERS the other player has 1 ⚑ in gold and is listed first.
3. Pick them: the flag chips (mods not trusted, stone test passed, the shader if any); MODS lists the minimap first in gold "not trusted"; PACKS shows their packs with fingerprints. The wheel scrolls the list under the mouse.
4. BLOCK beside the minimap mod, then SURE?: the note "Added to the block list; nobody was kicked"; they show "blocked" in red and stay online; when they rejoin they are refused.
5. Type a reason and KICK, SURE?: they see "Removed by staff" in gold and your reason; the LOG tab (kind Client check) has "kicked <name>: <reason>".
6. RULES: switch to Its own rules (the violet line appears; the header reads "<server>'s own rules"); add a word, a mod id, an allowed name, an exempt player with a note; × removes each; type in the refusal message and watch the counter; a second server keeps the network's rules.
7. EXPORT: the note names the file; it is in `config/bsp_server_utilities/`.
8. With two servers: a player on the other server is listed with that server's name; KICK reaches them there.

## 2026-10-23: D142, client-check entries for staff only

Headless: 70 GameTests pass (the refusal test also checks that players' copy of the log leaves client-check entries out). In game: after a refusal, the LOG tab (kind Client check) shows it; `/actionlog`, a log panel and the ACTION LOG board do not; a log panel's settings offer no Client check chip.

## 2026-10-23: SU-22, the setup guide: state, off switches, the offer

Headless: 75 GameTests pass, twice (new `SetupGuideGameTests`: the state round trips and the newest copy wins across servers; NEXT, SETUP LATER, closing part way, a change in the AFK tab counting, an off feature staying off and TURN ON; every off switch stopping its feature (rules, the action log, despawn times, the client check, staff online, panels, AFK); an admin waiting a few seconds, the first offer listing every page, the reminder listing what was left, nothing left or ignored meaning no offer, a non-admin refused; the packets round trip; the server's name checked and written). The corner card and the guide's screens come in SU-23. **Nothing of this has run in a client.**

In game (an operator, on a world that has run an earlier version):
1. Join: about five seconds later (after the rules popup, if any) a gold chat line "Server Utilities is not set up on this network yet. [Setup now] [Later] [Ignore setup]"; a player who is not an admin gets nothing.
2. [Later]: "You will be reminded at your next join."; rejoin: the line comes back. [Ignore setup]: rejoin, nothing; another operator still gets it.
3. `/su status` ends with "Setup guide: 0 of 9 pages set up; off: -; left: Welcome, Staff groups, ...".
4. Change the AFK time in the AFK tab: `/su status` shows AFK as set up.
5. The switches (until the guide's screens exist, check them after SU-23): rules off lets a new player move at once; action log off records no commands; despawn off leaves items for vanilla's five minutes; client check off lets a blocked pack in; staff online off empties the staff panels; panels off darkens panels and refuses placing.

## 2026-10-23: SU-23, the setup guide's card and screens

Headless: 76 GameTests pass, twice (new: the guide opening at the first page not finished; the card's SETUP NOW, LATER and IGNORE SETUP and the guide's TURN OFF, SETUP LATER, closing part way and TURN ON as the client sends them). **The card and the guide have not been drawn in a client.**

In game (an operator, every page not set up):
1. Join: after about five seconds (after the rules popup, if any) the card in the top right (`tools/preview/setup_refined.html`), with "Press T to click, or use the line in chat."; you can still move. Open an inventory: the card hides; close it: it is back.
2. Press T: the card's keys light on hover; LATER (or ×) removes it and says you will be reminded; rejoin: it is back. The chat line's [Setup now] does the same as the key.
3. SETUP NOW: the admin panel opens on Welcome, a little taller, with the progress bar and the strip "SETUP 1/10"; no tab is lit and clicking tabs does nothing. Type a server name and NEXT: "This server's name is ... after the next restart."; the config file has it.
4. Each page shows its tab under the strip (STAFF, RULES, LOG, AFK, CHUNKS, DROPS, CLIENTS on its RULES half), with the guide's footer instead of the tab's own (no REFRESH, PREVIEW or ASK AGAIN). The RULES tab's UP / DOWN / DELETE still work.
5. On Action log, TURN OFF FEATURE: the red warning and SURE?; a second click moves on, and commands stop being logged. On a page that is off: "This feature is off." and TURN ON FEATURE.
6. Display panels: the text and the /give line; TURN OFF darkens every panel at once (for every player) and stops placing one.
7. Escape on AFK: rejoin, the card reads "SERVER UTILITIES: n LEFT" with AFK and the pages after it in gold; CONTINUE SETUP opens on AFK.
8. Finish: every page with set up, off, later or not yet; DONE closes the panel. `/su` RULES tab: SETUP GUIDE reopens the guide.

## 2026-10-23: the network on plain Forge 47.4.23 (no Mohist)

Every server now runs Forge 1.20.1-47.4.23 with the LuckPerms Forge mod; the Mohist-only checks above (SU-11 item 1, plugin commands reaching the log; container clicks through Mohist) no longer apply: check the same things on Forge instead. Headless: 77 GameTests pass (new: a MySQL driver found in BSP-Core's full jar and registered). The Forge methods the client check uses exist unchanged in 47.4.23 (checked against the server's universal jar).

In game:
1. With BSP-Core's storage on MySQL, start a server: `/su status` shows "Shared storage: ..." with no errors; the log has no "No MySQL driver" line. With BSP-Core's -slim jar instead, the log says to install the full jar and records stay on this server.

## 2026-10-24: the setup card under JourneyMap's minimap (owner's in-game report)

Seen in game: the card was hidden behind JourneyMap's minimap in the top right. JourneyMap draws its minimap when Forge's HUD finishes, at the lowest priority, after the card. The card is now drawn by a client mixin at the end of Forge's HUD render (`ForgeGuiMixin`, RETURN), after every such listener, with depth testing off. The dev client reached its title screen with the mixin applied; 77 GameTests pass. Released as 0.4.1.

In game: join as an operator with JourneyMap's minimap in the top right: the card is drawn over the minimap; open chat (T): still over it and clickable.

## 2026-10-25: the setup guide opened without NEXT or DONE (owner's in-game report)

Seen in game on the network: the guide showed no NEXT or DONE, so no page could be finished. Cause: with shared storage the admin panel opens only after the database has counted who agreed to the rules, while the guide's data was sent at once, reached the client first and was dropped; the plain panel opened instead of the guide (singleplayer and test servers without a database never showed it). Fix: the guide's data is sent after the panel's view; the client also keeps guide data that arrives early for ten seconds. Headless: 78 GameTests pass (new: with shared storage the guide's data waits for the panel). Released as 0.4.2. **Not yet seen in a client on the network.**

In game (on a server with MySQL): SETUP NOW on the card, the chat line and `/su setup`: the panel opens taller with the progress bar and "SETUP n/10"; BACK, TURN OFF FEATURE, SETUP LATER and NEXT at the foot; Finish has DONE. SETUP GUIDE on the RULES tab does the same from an open panel.

## 2026-10-25: SU-24, the player counter, server side

Headless: 85 GameTests pass (new: a player counted once across two servers sharing storage, with the welcome on the server joined and "[alpha] Welcome ..." on the other; a returning player not counted on a server of its own, and the welcome in typed words; set, add, reset and back to the real count, each in the Action Log, the commands not logged twice; the players' copy (title, number, today, this week, newest, how long ago); the newest settings winning between servers; turned off, nobody counted or welcomed and players see nothing; the guide page's actions for admins only). **Not yet run on the network's MySQL, or with real players joining.**

In game (two servers of the network with shared storage, and a second account or a friend who has never joined):
1. The new account joins server A: everyone on A reads a gold "Welcome <name>, player number N on BSP!"; everyone on server B reads "[A] Welcome <name>, player number N on BSP!" with A's name in grey. N is one more than `/su players` showed before.
2. The same account leaves and joins B: no welcome, and `/su players` on either server shows the same number.
3. `/su players set 1500`, `/su players add 5`, `/su players add -10`, `/su players reset`, `/su players true`: each answers with the number shown and the real count; `/actionlog` (or the LOG tab) has one entry for each ("set the player count to 1,500 (real count ...)") and none for the command itself.
4. `/su status` has "Players: N shown (real count R), counted for the network."; on a server without shared storage "on this server only".
5. A player refused by the client check (a blocked pack) is not counted.

## 2026-10-25: SU-25, the PLAYERS board, Player count panels and the guide page

Headless: 86 GameTests pass, twice (new: a Player count panel keeps its own title, cut to 32 characters, and the new-counts tick, saved by an admin). **The board, the face, the panel's pane and the guide page have not been drawn in a client.**

In game:
1. A BSP-Core Score Screen: in its settings, Show: Board, the board "Players of BSP" is listed; it shows "Have played BSP" with the number in gold, "New today", "New this week" and "Newest" with a name and how long ago (`tools/preview/counter_refined.html`, part 1).
2. Change the title on the guide page: the board's title follows (in the board list too).
3. A Display Panel (4 x 2): the mode keys read TEXT, IMAGE, STAFF ONLINE, ACTION LOG, PLAYER COUNT. PLAYER COUNT: the preview shows the counter's title in teal, the number in gold, "players have joined the network" and "12 new today · 58 this week"; type a title in "Title on this panel": the preview follows; untick "New today and this week": the line goes. SAVE: the panel in the world shows the same. Try 1 x 1 and 8 x 6 panels: nothing runs off the face.
4. `/su setup`, then NEXT to page 10/11 "Player counter": the number shown and the real count at the left; type 1,500 and SET: the number becomes 1,500 and the board follows within a second; ADD with -500; RESET TO 0 asks SURE? first; REAL COUNT. At the right, the title and the welcome words, with the count against 160 and the line as it will read in gold (with your own name). Change both, then NEXT: a new player is welcomed in the new words; the board has the new title.
5. On that page TURN OFF FEATURE: the board shows no lines, Player count panels show only their background, the panel's pane says the counter is off, and a new player is neither counted nor welcomed. TURN ON FEATURE: all back.
6. Finish lists Player counter with the other pages.


## 2026-10-28: network passwords out of the synced server config

Headless: 88 GameTests pass, twice, against BSP-Core 1.6.2 (new `NetworkConfigGameTests`: the one-time move copies old server-config settings to `config/bsp_server_utilities-network.toml`, a value already there wins, the server config is cleared, the log line names keys and files but no value, a second start moves nothing, the old names read the new file; BSP-Core's moved settings still answer through its old field names; the notes name both files). The guide-ordering test from 0.4.2 now runs in its own batch: it kept test storage on for a few ticks and, with 1.6.2's timing, other tests running beside it saw it. Released as 0.5.1. **Not yet run on a live server.**

On a server (with BSP-Core 1.6.2 or newer):
1. Before updating, note whether `serverconfig/bsp_server_utilities-server.toml` has a `storage.password` or `storage.redis.password` set (it does only if `useBspCoreDatabase` / `useBspCoreRedis` were turned off).
2. Start the updated server: if anything was set, one warning "BSP-Server Utilities: Moved storage.password ... to config/bsp_server_utilities-network.toml ... Change the database and Redis passwords ..." with no value in it; the new file holds the values, the server config's old keys are empty and say "Moved to ...". Nothing set: no warning, and the new file has the defaults.
3. `/su status`: "Shared storage: ..." works as before; the notes say "database settings from BSP-Core's config/bsp_core-network.toml" (or this mod's network file when its own settings are used).
4. Restart: no second warning.
5. Change the database and Redis passwords if one was ever in a server config, and keep every `config/*-network.toml` out of the client pack and `defaultconfigs/`.

## 2026-11-03: SU-26, server images, server side

Headless: 94 GameTests pass, twice (new `ServerImagesGameTests`: each file checked (a 384 x 64 PNG taken and known by its hash; over 512 x 64, wider than 96 in chat, over 256 KB, not a PNG, animated and a name that cannot be a server's refused); a refresh becoming the network's set in the shared storage with its bytes, written to the Action Log, a second server taking it from the notice and fetching the bytes, a file left out removing the image and its bytes; replacing or removing waiting for the second click while adding goes through, a refused file for a server in use counting as a removal; each player sent the list and every image once a login, an empty list when the feature is off; the tag's key, grey fallback, white, font and hover; the SERVERS tab's alphabetical names and marks for admins only, with what a refresh did, would do or that nothing changed). The counter's welcome test also checks the other servers' line starts with the server's tag. **Not yet run against the network's MySQL (MemoryBackend stands in), or on a live server.**

On a server with shared storage (two servers of the network):
1. Put `BSP-Spawn.png` (for example 256 x 64) and a 640 x 64 `BSP-Test.png` in `config/bsp_server_utilities/server_images/` on server A. `/su serverimages`: "Server images in use: 0", the folder with 2 files, BSP-Test refused "640 x 64 is over 512 x 64" in red.
2. `/su serverimages refresh`: "refreshed the server images: added BSP-Spawn". `/actionlog` has that entry and none for the command. `/su serverimages` on server B lists BSP-Spawn in use, though B's folder is empty. `/su status` on both: "Server images: 1 in use, for the network."
3. Replace `BSP-Spawn.png` with a different picture and refresh: a red "This replaces BSP-Spawn and removes -." with [Confirm]; nothing changes until [Confirm] is clicked (or `/su serverimages refresh confirm`).
4. Delete the file and refresh with confirm: the image is gone on both servers.
5. With Redis on: the image reaches server B within a second of the refresh (the notice is sent only after MySQL has the bytes). If B ever logs "Server image ... is in the set but not in the shared storage", the next player to join B makes it try again.
6. A 512 x 64 file: `/su serverimages` lists it as 256 x 32 (scaled to the game's 256-pixel font sheets), and in chat it shows as the picture, not an empty box.

## 2026-11-03: SU-27, server images in the client's chat

Headless: the dev client starts with the built-in pack `bsp_server_utilities_server_images` in its resources and no font errors. **The font reload, the language wrapper beside RankUp's and an image in chat have not been seen in a client.** Velocity's side (request SU-VA1) is not built yet, so relayed chat still shows the plain grey tag; until then this mod's own welcome line is the place to see an image.

In game (BSP-RankUp on the server too, as on the network):
1. With BSP-Spawn's image in use, a new account joins BSP-Spawn: on the other servers the gold "Welcome ..." line starts with the image, one chat line high, in its own colours, not grey; hovering it shows "BSP-Spawn". On a server with no image for the joined server it reads "[BSP-Test] Welcome ..." in grey as before.
2. RankUp's prefix images still show beside names in chat, before and after the server images arrive; F3+T (reload resources) and then a new chat line: both kinds of image still show. Play for a few minutes: no growing lag (the two mods' language wrappers settle).
3. Turn the feature off on the guide page: new lines read the grey name; on again: images at the next login.
4. Once Velocity Additions has SU-VA1 and `chat.relayStaff = "<server_tag> <line>"` in its `messages.toml`: a staff member sees each other server's image in front of its relayed lines, players see no tag, and the proxy console still reads `[BSP-Spawn] <Name> text`.

## 2026-11-03: SU-28, the SERVERS tab and the guide page

Headless: the tab's data is covered by SU-26's tests. **The tab and the guide page have not been drawn in a client.**

In game:
1. `/su`: eight tab keys, SERVERS last, all readable, the title smaller beside them.
2. SERVERS: one alphabetical list; this server marked "here", a server down for a while "off", a file named after no known server "?" (for example a misspelt `BSP-Spwan.png`, with the gold note to check the spelling against velocity.toml). Each row shows the image as chat will, or the grey name, and its state: image, text, ready (a file not refreshed yet) or refused. It opens on this server (`tools/preview/server_images_refined.html`).
3. Click a row: the right side shows the file and its size, the image at chat size and twice that, the sample line "<Pip> heading to the mines" after the image on chat's dark backing, and a refused file's problem in red.
4. REFRESH with only new files: they go straight in, "Images added: n" in green. With a file changed or removed: the footer turns red, "Replaces ..., removes .... Click again within 3 s." and the key reads SURE?; a second click within three seconds applies it, waiting longer does nothing.
5. On a server without shared storage the footer says in gold "This server only: no shared storage, so each server uses its own folder."
6. `/su setup` to page 11/12 "Server images": the same list and pane, with REFRESH in the right box (the guide's keys take the footer); TURN OFF FEATURE, SETUP LATER, NEXT; Finish lists Server images.

## 2026-11-03: SU-29, the presence check and macros, server side

Headless: GameTests in `AfkGameTests` (the settings, their 0.6.0 encoding and world save read with the macros switch on; the check at the kick time for players and staff, never admins or operators, the warning a minute before; silence kicking players with the message and leaving staff AFK, never checked again until back, both in the Action Log with what was seen, for staff only; kicking off sending nothing, turned off mid-check taking the card away; the right word bringing a player back, a wrong one giving a new word and order with the time running on, an old word or another player's answer not taken, any other activity ending the check; a simulated auto-clicker (the same use every 1.000 s, one spot) and a mouse jiggle (two exact angles) not counting after ten minutes and AFK five minutes later, while a person's varied input and the same use with uneven timing, changing angles and walking a long row still count; a real head turn still bringing the clicker back; the switch off making repeated input count; a correct answer clearing a macro for 60 minutes, then counting against it again; the macro watch needing the whole ten minutes and 90 % from three keys; the network list and the AFK tab's data with "macro?", what was seen and the time left) and `ChunkGameTests` (an AFK player's ticket suspended after the chunk minutes while online and back when active). **Not yet run with real players, a real auto-clicker or jiggler, on the network, or against MySQL** (the `macros` column is added by `ALTER TABLE` on the first start).

On a test server (AFK after 1 min, kicking on, Check after 5 min AFK, macros on):
1. A player stands still: "You are now AFK." after 1 minute; at 5 min AFK minus a minute "You will be checked for being idle in 1 minute." in red; at 5 min the red line "Still there? Click the box on your screen within 2 minutes or you will be kicked." and the card.
2. Click the right word: "Thanks, you are back." in green, the card goes. Next time click a wrong key: a new word and order, the red line on the card, the clock still running; then let it run out: kicked with the kick message; `/su` LOG, kind "AFK checks": "<name> did not answer the presence check: no input for 7 min" by "AFK check". `/actionlog` and panels do not show it.
3. An operator idles: AFK, never warned or checked. A staff member (in a staff group, not an operator): checked with "or you will stay AFK"; no answer: stays AFK and away on the staff panels, not kicked, the log entry ends "Left AFK: staff are never kicked", and no second check until they move.
4. An auto-clicker on a zombie or a held item at one spot, every second, without touching the mouse: after 10 minutes the AFK tab shows the player with "macro?" once they go AFK (1 minute later); hovering it reads "the same click on a zombie every 1.0 s at the same head angle at one spot, for 11 min." Checked at the kick time; answer it: their clicks count for an hour.
5. A mouse jiggler (two positions): the same, "the same head turns between 2 exact angles ...". Moving the mouse by hand brings the player back at once.
6. Turn "Repeated input does not count" off: the macro player is active again on their next click; on another server of the network the switch is off too.
7. Chunks (CHUNKS tab, 1 minute): a player with a machine goes AFK: after a minute the CHUNKS tab shows them "AFK 1 min" and the machine's chunks unload while they are online; they move: "Non-totem chunks were unloaded while you were away; they are loaded again now." and the machine runs again.
8. Kicking off: idle and macro players are still marked AFK and their chunks unload, but no warning, card or kick.
9. With MySQL: the first start adds the `macros` column to `<prefix>afk_settings` (no error in the log); changing the switch on one server changes it on the others.

## 2026-11-03: SU-30, the presence check's card and the AFK tab

Headless: the dev client starts with the mixin applied (see below for what was checked). **The card and the tab have not been seen in a client.**

In game:
1. The card at the check: "STILL THERE?" in teal with the time counting down, "Click SPRUCE to stay, or you are kicked. Press T to use the mouse." with the word in gold, three keys (`tools/preview/afk_check_refined.html`). Each check puts it somewhere new, at least 8 pixels from the edges, never over the hotbar or the crosshair, and it stays there for that check (also after a wrong click).
2. JourneyMap's minimap on: the card is over the minimap where they meet; also over chat, the setup card and other HUDs.
3. Without a screen: the keys cannot be clicked; T opens chat, the "Press T" words go and the keys can be clicked. Open the inventory or a chest: the card is still on top and its keys click without touching the slots under them.
4. A wrong key: the red "Not that one: here is a new word." and new keys; clicking twice fast sends only one answer.
5. `/su` AFK tab: AFK DETECTION, KICKING with "Check, then kick, players AFK too long" and CHECK AFTER, the kick message box, MACROS with "Repeated input does not count" and its line; footer "Saved for every server · admins and operators are never checked · other staff never kicked" on two lines. The list: "macro?" in gold beside a macro player (hover: what was seen; during a check "... Checked at 30 min AFK."), "checked, 1:42 left" in red during a check, "never kicked" for staff.
6. LOG tab: the kind menu has "AFK checks".

## 2026-11-05: SU-31, /broadcast

Headless: `BroadcastGameTests` (4: colours by name, everyday name and hex, the title's split, the text kept clean; chat and title to everyone here or one named player, the gold prefix, the colour, `&` codes, the title written in chat too; a player not here, an unknown colour and an empty title refused; without shared storage `network` stays here; with it a notice for the other servers, shown there, a named player on another server reached by name and nobody here shown it, one online nowhere refused unless the client check is off; only admins and the console may use it, `"#RRGGBB"` in quotes). 103/103, twice. **Not yet seen in a client or across real servers.**

In game, two servers sharing storage:
1. From A's console: `broadcast chat red all server Restart in 5 minutes`. Everyone on A sees "[Announcement]" in gold, then the message in red, and hears a short chime; B's players see nothing. The console says "Sent to n players on A."
2. `broadcast title gold all network Restart | in 5 minutes`: on both servers "Restart" large and gold in the middle of the screen, "in 5 minutes" smaller under it, for about 5 seconds, and the same words in chat; the console says "... and to the other servers."; B's console logs "[Announcement] Restart in 5 minutes".
3. `broadcast chat green <a player on B> network Your build won!` from A: only that player sees it; the console names B. A name nobody has: "... is not online on the network." in red. With `server` and a player on B: "... is not online on this server."
4. Colours: `grey`, `orange`, `pink`, `1E90FF`, `"#FF8800"` work; `plaid` lists the colours. `&lbold&r` and `&cred` inside the message show as bold and red.
5. As a player who is not an operator: `/broadcast` is not offered and does not run. `/su` LOG tab: the command is there, by Console or the admin.


## 2026-11-08: SU-32, the staff web panel's feed

Headless: `WebGameTests` (4: a snapshot's fields for a player with a place, low health and hunger, air, XP and an effect, no role or team; nothing sent while off or without shared storage, then one row per server every 100 ticks, replaced each time; the snapshot reaches a listener on the web channel through Redis and the other server gets nothing by Redis or by the notice table; 50 players take about 0.6 to 0.8 ms per snapshot, about 17.5 KB). 107/107, twice. **Not yet run on a live server, against the real MySQL and Redis, or with a BSP-Core team.** The panel that reads it is not built yet (SU-33), so check it from Redis and MySQL directly.

On one server with shared storage and Redis:
1. `/su status`: "Staff web panel: off (webPanel.enabled ...)". Set `webPanel.enabled = true` in `config/bsp_server_utilities-network.toml`, restart: "on, sending this server's players every 5 s; nothing sent yet", then a few seconds later "last sent 3 s ago (n players)".
2. `redis-cli -a <password> SUBSCRIBE bsp_su:<channel>:web` (the channel from the server config): a message every 5 seconds whose fourth line is `snapshot` and fifth the JSON. Players' positions, health and hunger match the game; a potion shows in `effects`; a player in a BSP-Core team has `team` with its name and colour; staff have `role`.
3. `SELECT server, at_ms, LENGTH(snapshot) FROM <prefix>web_servers;`: one row for this server, `at_ms` moving every 5 seconds.
4. Subscribe to `bsp_su:<channel>` (the game servers' own channel) at the same time: no `snapshot` messages there.
5. `snapshotSeconds = 2`: messages every 2 seconds. Back to `enabled = false`: they stop, and the row stays with its last time.
6. With the server busy (many players), the tick time in `/forge tps` is the same with the feed on and off.

## 2026-11-08: SU-33a, the staff panel's security core

Headless: `./gradlew -p staff-panel test`, JUnit, 18 tests, twice: `CryptoTest` (authenticator codes against RFC 6238's own test values, 30 seconds either side and never twice, Base32 and the QR address, password hashes, the panel's key sealing and fingerprints, tokens and backup codes), `AccessJwtTest` (a good Cloudflare token gives the email; refused: another key, an unknown key, another team, another application, expired, not yet valid, no email, a changed body, no signature, another signing method; the keys fetched again for an unknown key at most once a minute and every hour), `AuthTest` (the first Owner from the console and the one-time link, 15 minutes, used once, only for its own email; password and a fresh code or a backup code, each once; five wrong tries lock for 15 minutes and are logged; 7 days for Moderators and 48 hours for Admins; a login copied to another Cloudflare identity is refused and ended; only Owners manage staff, Owners only from the console; an authenticator reset leads to a new one at the next login; the console's resets; nothing secret stored in the clear). **Nothing to check in game yet**: there is no web server or page until SU-33b, and `JdbcStore` has not run against a real MariaDB.

## 2026-11-09: SU-33b, the staff panel's web server, pages and live feed

Headless: `./gradlew -p staff-panel test`, 27 JUnit tests, twice (SU-33a's 18 and `PanelServerTest` 6: nothing served without Cloudflare's token; the security headers and no inline scripts or styles; POSTs only with `X-BSP`, the panel's `Origin` and JSON; setup and login over HTTP, the cookie HttpOnly, SameSite=Strict, Secure, 48 hours for an Owner and 7 days for a Moderator; a cookie under another identity refused; logout; Owner-only staff pages; a rank change ending a login; the live feed's first event and a pushed change with who is on the panel; `FeedAndConfigTest` 3: the feed's newest per server and quiet servers dropped, the Redis listener's AUTH, SUBSCRIBE and messages against a stand-in Redis, the config file made, checked and never printing passwords). Seen in the browser pane with `--demo` (setup with the QR code, backup codes, the players' feed, the More tab, the phone and computer layouts). **Not run against Cloudflare, the real MariaDB or Redis, or game servers.**

On the network (staff-panel/README.md "Setting it up"):
1. First start writes `panel.properties` and stops; after filling it in, the console says the Cloudflare keys were read and the panel listens.
2. Open `https://staff.buildsecureprotect.com` in a private window: Cloudflare asks for your email and its code first. Without passing Cloudflare (the panel's own address on the LAN), every page says to open it through its address.
3. `create-owner <your email> <your Minecraft name>` in the console; open the link on your phone: the welcome line, QR code, "Add to app" opens your authenticator; a wrong code is refused and the link still works; FINISH shows the ten backup codes, COPY ALL and SAVE AS FILE work, GO TO THE PANEL only after the tick.
4. Players tab: the game servers with `webPanel.enabled` appear as tiles within 5 seconds with their counts; players with their server, position and team; a server stopped disappears within 20 seconds. On a computer (900 px wide and more) the menu is on the left with STAFF ONLINE (staff in game with their server, ▣ for those on the panel).
5. Log out, log in with a wrong password: "4 tries left"; five wrong: locked for 15 minutes; log in with a backup code: one fewer left (More tab).
6. More tab, as an Owner: + Add staff for a test email; the link works once, only signed in to Cloudflare as that email; as that Moderator the More tab has no staff accounts. Back as Owner: the gold line after locking the Moderator out; ⋯ menu: Unlock now, Make Admin (their login ends), Reset authenticator (their next login sets up a new one), Reset whole login (a new link), Remove; each needs a second tap within 3 seconds.
7. Console: `reset-owner <your email>`, then log in: after the password, set up a new authenticator. `staff` lists everyone; `status` the open pages.
8. Stop Redis for a minute: the players keep updating every 5 seconds from the database table.

## 2026-11-09: SU-34, the panel's Players tab

Headless: `WebGameTests.the_skin_address_comes_from_the_profile_and_only_from_mojang` (Mojang's address as https, other hosts and paths left out, none for a profile without textures); 108/108 GameTests, twice. `HeadsTest` (3: the face with the hat layer, 64 px, fetched once; only https `textures.minecraft.net/texture/<hex>`; not a picture or not fetched gives nothing; the feed knows each skin) and `PanelServerTest.heads_need_a_login_and_come_from_the_panel_itself`: 31/31 JUnit, twice. Seen in the browser pane with `--demo` at 375 px and 1400 px (the sheet, the card dragged and kept through live updates, staff online). **Not seen with real players, real skins or a real phone.**

On the network, with players on two servers:
1. Phone: the servers as chips you can swipe; "n online · n teams"; the teams as cards with their colour on the left, "No team" last; tap a card's head to fold it. Each row: the player's real head, name, a violet staff chip, "AFK 12 min", a gold "creative" chip, the server and position (and "nether" or "end"), ten hearts and armour and hunger numbers; LOW in red at 3 hearts or less.
2. Servers switch: the same players grouped by server. The search box narrows the list as you type, keeping the caret through the 5-second updates.
3. Tap a player: the sheet rises with their head, team, ping, place, the hearts, hunger, armour and (under water) air as the game draws them, XP, and their potion effects with level and time left; numbers under the icons. Tap outside it or ✕ to close. It follows the player's updates while open; when they leave: "Not online any more".
4. Computer (900 px wide and more): server tiles along the top, teams in one column up to 1300 px, two up to 1700, three beyond; compact rows with a heart and the number. Click a player: the card on the right; drag it by its dotted edge anywhere; it stays there through updates and when another player is opened.
5. A player whose skin cannot be fetched (or an offline-mode server) shows a plain face.

## 2026-11-09: SU-35, actions from the panel and the network kick

Headless: `WebActionGameTests` (4: the proxy message's bytes as Velocity Additions reads them and the server's own kick after 60 ticks, none if the player went; LEAVE SERVER and the AFK kick going through it; the signature equal to the panel's for the same fixed value; refused: another key, changed, no key, older than 30 s, used twice, another server's, an unknown action; a kick and an announcement carried out, what the player reads, the Action Log by "Ana_Mod (panel)", an unknown colour refused): 112/112, twice. Panel: `ActionsTest` (3) and `PanelServerTest.a_kick_goes_to_the_player_s_server_and_announcements_are_for_admins`: 35/35 JUnit, twice. Seen with `--demo` (kick with the second tap; the Broadcast page's title preview with `&` codes). **Not run on the network: Redis, the notices table, the proxy kick with real players.**

Before: Velocity Additions 0.2.0 (SU-VA2) on the live proxy; `webPanel.actionKey` on every game server (the panel's console `action-key`).
1. `/su status` on a game server: "kicks and announcements from the panel are taken". Without the key: "refused until webPanel.actionKey is set", and a kick from the panel answers "No answer from <server> in 6 seconds…".
2. A Moderator opens a player in the panel, Kick, types a reason, taps Kick twice: the player lands on the multiplayer screen reading "You were kicked by staff: <reason>", not on another server; the panel says "<name> was kicked from the network."; the LOG tab has "kick <name>: <reason>" by "<staff> (panel)"; the proxy's console has its line.
3. With `[networkKick] enabled = false` on the proxy: the same kick removes them about 3 seconds later, from that server.
4. LEAVE SERVER on the rules popup, and an unanswered AFK check: the player leaves the network (multiplayer screen), not to another server.
5. Broadcast (as an Admin): a title with `|` to every server: shown on all servers as the preview drew it, with the chime; to one server only; to one named player on another server. As a Moderator the Broadcast row is dimmed with "Admin".
6. Stop Redis for a minute and kick from the panel: it still happens through the notices table, within the servers' poll time (a few seconds).

## 2026-11-09: audit before naming (SU-32 to SU-35 and the staff panel)

Two independent reviews (the mod since 0.8.0; the panel, security first), every finding checked against the code; 16 fixed:
- Mod: snapshot writes coalesced while the database is slow or away (one waiting at most); snapshots and answers signed with `webPanel.actionKey` (nothing sent without it); LEAVE SERVER keeps the player held (frozen) until they are gone; actions not taken while `webPanel.enabled` is off; refused-action warnings at most once a minute; the skin address checked on its raw path (hex only); a signed action with odd field types refused, not an error.
- Panel: one change at a time per account (a login can no longer write back an old copy over an Owner's removal or reset; concurrent wrong tries all count; a code or backup code works once even at the same moment); a page that stops reading its live feed is dropped without holding up the others, and at most 4 live pages per staff member; at most 4 actions waiting at once and 10 a minute per staff member; skins that cannot be fetched are not asked for again for 10 minutes, one fetch per skin, at most 64 KB read; the database fallback for the feed keeps working while Redis is connected but quiet; the page says "Not live: reconnecting…" and recovers (or goes to log in) when the feed is refused; lock-outs found by a query, not among the last 200 log lines; an authenticator secret that cannot be opened no longer gives an error (backup codes still work); broadcast fields checked before signing; snapshots and answers not signed with the panel's key are dropped; the "no answer" message says it may still have happened.
- Tests: 112/112 GameTests and 41/41 JUnit, twice (new: the panel off, odd field types, the signed-text format, LEAVE held; concurrent wrong tries and one code at the same moment, a removal during a login, lock-outs among 500 lines, an unopenable secret, the signed feed, forged and unsigned answers, ten actions a minute, the head fetch kept, unchecked broadcast fields; a test that passed for the wrong reason fixed). The reconnect banner was seen with `--demo`.
- Left as they are: the replay memory is lost at a restart within 30 s of a captured action (accepted); retired servers' rows stay in `<prefix>web_servers` (the panel ignores them by age); a page stuck on a dead connection keeps its own writer thread until the connection times out.

In the network test, add: set `webPanel.actionKey` on a server and leave it off on another: the second sends nothing to the panel and takes no actions (`/su status` says so).

## 2026-11-09: graves on the panel (D295 to D302)

Headless: `WebActionGameTests.graves_are_read_and_returned_through_inventory_sync` (without BSP-InventorySync's graves: said plainly; the graves as the answer's data; a return through it by the staff member, in the Action Log; a bad grave id refused before it), with a stand-in for BSP-InventorySync (it is not loaded in the GameTests); `PanelServerTest.graves_are_looked_up_by_name_and_returned_by_admins_where_the_player_is` (an offline player by name, any case; asked of any live server; a return sent on once to the server named in an ONLINE answer; Moderators refused). 113/113 GameTests (six runs) and 42/42 JUnit, twice. Seen with `--demo` (the Graves key, the page, RETURN with the second tap). Also fixed: `AfkGameTests`' walker started at a random spot near spawn and now starts beside the clicker (the test failed about one run in four). **Not run against BSP-InventorySync itself: it needs its 0.7.0 on the servers.**

With BSP-InventorySync 0.7.0 on the servers:
1. Die somewhere a grave cannot be placed (or let one be put away): the panel's Graves page for that player lists it "not placed" with its place, age, XP and its stacks as slots, and "The items show once BSP-InventorySync can list them."
2. A grave standing in the world is listed "in the world" with no RETURN.
3. As an Admin, RETURN twice: the player online gets the items back at once (armour worn); offline, at their next login (the answer says so); online on another server, it still works (the panel asks that server). The LOG tab: "grave return <name> <id>" by "<staff> (panel)"; BSP-InventorySync's own log line too.
4. As a Moderator: the button reads "Return (Admin)" and is refused.
5. A server without BSP-InventorySync 0.7.0: "Graves need BSP-InventorySync 0.7.0 or newer on this server."

## 2026-11-09: second audit (graves and the first audit's fixes), before committing 0.9.0 and the panel 0.1.0

One reviewer, 11 findings, all checked and fixed: the panel takes a snapshot only when its signed `server` matches where it came from and it is under a minute old (a replay under another name, or an old one, is dropped); snapshots say whether BSP-InventorySync can answer about graves, and offline players' graves are asked of such a server; a return is sent on to the server an ONLINE answer names only when that server is sending to the panel (otherwise: return it there in game); a page replaced by a staff member's newer page gets `replaced` and stops with "Open on another page or device: tap here to use this one" (no endless reconnects); pages whose connection is still closing count against the limits; an answer arriving after staff left the More tab draws nothing; the player counter's list keeps each player's current name (UPDATE at a join when it changed); a second LEAVE (or AGREE) after LEAVE counts for nothing and keeps the first 3-second deadline; a late answer with data tested through `WebActions.dispatch` (signed, sent once BSP-InventorySync answers, never waited for), and records turned into JSON tested on a record shaped like `GraveReturn`; `<prefix>web_servers` and the panel's tables in utf8mb4; heads: the whole fetch within 10 s, capitals in the hash, no other port. 114/114 GameTests and 42/42 JUnit, twice; the replaced page and the graves server seen with `--demo`.

Add to the network test: with BSP-InventorySync 0.7.0 on some servers only, an offline player's graves come from one of those; rename a player, log them in once, and find them on the Graves page by the new name; open the panel on five devices with one login: the oldest says it is open elsewhere and comes back when tapped.

## 2026-11-10: SU-36, chat on the staff panel (D304 to D313)

Headless: `WebChatGameTests` (a public line sent signed with its server, player, time and a one-time id; cancelled lines, empty lines, the panel off or no action key send nothing; section signs and control characters out, 256 characters at most; lines written to the shared table on the storage worker in one write, never twice; "say": "[Staff] Ana_Mod:" in violet with a hover, the words after it, the Action Log entry; "[Server]" in gold for Admins only; another name, an empty line or over 256 characters refused; no formatting codes slip in). 119/119 GameTests. Panel: `ChatTest` (only chat lines signed with the panel's key read, not snapshots or answers; a player's line only from its own server; each line once whether Redis or the table brought it; older lines, the lines around a search result, search by words, player and days, the 30-day prune; the panel's own lines kept signed; a network line as one action to several servers, a server not answering named; over HTTP: [Server] refused for a Moderator, the network reached server by server, a RETRY to one server not kept twice, § codes and other names refused; the chat table's definition the same on both sides). 49/49 JUnit. Seen with `--demo` at 1024 px (the column behind the CHAT button, sending with "✓ 5 servers") and on a phone (the Chat tab, the AS / TO sheet, search, a result shown among its lines). **Not run on the network: no real chat, Redis, MariaDB or game client yet.**

On the network, with the panel set up and `webPanel.enabled` on every server:
1. Say something in game on two servers: both lines appear on the panel within a second, with the server's name and colour; the Chat tab's badge on another tab shows a dot.
2. Say "can a mod help" or a staff member's name: the line has a red bar and the badge a red number.
3. Team chat (BSP-Core) and a player held by the rules popup: nothing on the panel.
4. From the panel, as yourself to the network: every player on every server sees "[Staff] Name: text" in violet and white; hovering it says "from the staff panel"; the panel shows "✓ N servers"; the LOG tab has "chat on <server>" for each server.
5. As an Admin, [Server] to one server: only that server's players see "[Server] text" in gold, hovering "From the staff". As a Moderator, [Server] is dimmed.
6. Stop one game server, send to the network: the panel names it with RETRY after 6 seconds.
7. Restart the panel: the last lines are still there (from `<prefix>web_chat`); scroll up for yesterday's lines; search for a word and a player, tap a result.
8. Stop Redis for a minute while players chat: the lines still reach the panel within a few seconds (from the table).
9. After 30 days, `<prefix>web_chat` holds nothing older (the panel's console says when lines are dropped).

## 2026-11-10: SU-37, the Staff tab on the panel (D314 to D319, D327 to D329)

Panel only (nothing in the game). Headless: `StaffTalkTest` (the room: one line only, 1000 characters, every page hears new and deleted lines; your own line deleted within 5 minutes and not after, an Admin not another's, an Owner any; paging older lines; messages: only the two people told of a direct one, unread until read, a third person neither sees nor marks it, REPLY marks read, "Read 21:40" for the sender, a message to all staff "Read by 1 of 2" with the names, not to yourself or a removed account, deleting; the 30-day prune keeps unread messages and drops them once read or once the reader's account is removed; 30 a minute; over HTTP: the room, sending, reading, the people to write to, a refusal as words, 401 without a login). 54/54 JUnit. Seen with `--demo` on a computer (room and messages side by side, sending, REPLY, the badge) and a phone (the switch, a right click on your line to delete it, the new message sheet). **Not run on the network or against MariaDB.**

On the network, with two staff accounts on two devices:
1. A says something in the room: B sees it at once; B's Staff tab shows a dot when on another tab, a red number when the line names B ("@B").
2. A deletes the line within 5 minutes: "deleted" on both screens; after 5 minutes A's long press offers nothing; an Owner can still delete it.
3. A writes to B while B's panel is closed: B opens the panel later and sees it with a teal edge and the badge's number; GOT IT clears it and A sees "Read <time>".
4. A writes to All staff: A sees "Read by k of n" grow as others open it.
5. REPLY: the answer reaches the sender and the original is marked read.
6. Remove a staff account: their unread messages go after 30 days.

## 2026-11-10: SU-38, notes and tags (D320 to D326, D330 to D332)

Headless: `WebNotesGameTests` (a signed `web_notes` notice taken and its counts kept; another key, a copy, one over two minutes old, or any while the panel is off refused; odd entries left out; a newer notice replaces the counts; the counts only to staff who can open the admin panel; the packet round trip). 122/122 GameTests, twice. Panel: `NotesTest` (notes seen by all, changed or deleted only by their author or an Admin, the earlier words in the panel's log; counts with the newest author and time; tags put on and taken off by any staff member, a typed tag joining the list grey, the same name in any case one tag, the list kept by admins with the palette's colours and no two names alike, removal from every player; the counts notice signed as actions are; over HTTP). 59/59 JUnit. Seen with `--demo` on a phone (tags on rows and in chat, the sheet with tags and notes, the picker creating a tag, adding a note, More › Tags with the colour palette). **The hover in game is untested: it needs a game client (the GameTests run a server only).**

On the network (every client on this version: protocol 6):
1. A client with 0.9.0 cannot join: it is told the mod's version differs.
2. On the panel, add a note about a player who is online. Within a few seconds, in game as staff, open the admin panel's CLIENTS tab and hover that player: "1 note / newest by <you>, just now / read them on the staff panel". A player without notes: no hover.
3. Hover a staff member with notes on the STAFF tab: the same.
4. Delete the note on the panel: within a few seconds the hover is gone.
5. A player without the admin panel's rights never receives counts (nothing to see).
6. On the panel, type a new tag on a player: grey in the list; as an Admin give it a colour; as a Moderator the Tags page has no keys to change it.
7. Edit someone else's note as a Moderator: no ⋯; as an Admin: EDIT shows "edited <time>", and the panel's log keeps the earlier words.

## 2026-11-10: audit of phase 2 (chat, the Staff tab, notes and tags)

Two reviewers (the mod; the panel). No forgery, replay across kinds, XSS, SQL injection, rank bypass or leak of direct messages found. Fixed: notes counts not shown once the panel stops sending for 2 minutes or is switched off on the server (the hovers go); chat lines kept waiting (up to 2000) when a database write fails, written with the next line; the notes notice carries `"type":"web_notes"` and only that is read as one; right-to-left overrides, zero-width and C1 control characters stripped from chat; the hover drawn after everything else on the screen; the packet's count checked. Panel: chat lines sent to pages in one event every quarter second (a burst no longer drops the pages), and after the live feed reconnects the chat, the room and the messages are fetched again; a line said from the panel is kept once it may have been shown (a server answered, or did not answer in time), and a RETRY keeps it if the first try kept nothing; a reply to a message that is not yours is refused before anything is stored; ten searches a minute per account and an 8-second query limit; older lines paged "at or before" (no skipped lines) with "more" from the raw rows; a line that cannot be read back is never stored; two staff typing the same new tag at once get the one tag; a player id that is not a UUID is dropped. Dropped as untrue: "a player's line only from its own server" (every server holds the same key, so a signature cannot prove which server sent it). Accepted limits: messages beyond a person's newest 500 cannot be seen; a replayed old chat line can show once more after 5000 others; `executeBatch` row by row on the storage worker. 122/122 GameTests and 59/59 JUnit, twice; batched chat seen live with `--demo`.

## 2026-11-10: SU-39, a player's items on the staff panel (D256, D279, D335 to D338, D360, D372)

Headless: `WebItemsGameTests` (an item's id, key, a renamed item's name, enchantments as [id, level] and a book's stored ones, durability, lore as plain text, a potion's kind, a shulker box's contents with their slots, a plain stack carrying only what it has; the hash the same for any count, different when renamed, for another item, or for a worn tool; BSP-InventorySync's records read through look-alikes with the same names and types; `inv_read` refused without BSP-InventorySync or for a bad id, answering with only the filled slots, locks, missing items, a backpack's place and counts above 64, "Nothing has been saved" for a player never saved, a failed read saying why). 125/125 GameTests, twice. Panel: `PanelServerTest` (an offline player read from a server whose BSP-InventorySync reads items, not one without; an online player from their own server, asked once more on the live server the answer names if they moved; never played: said so; a login needed; Find a player: online first, a player online not repeated from the table, offline ones without a server, a non-name finding nothing), `ActionsTest` (30 reads a minute apart from the 10 actions). 61/61 JUnit, twice. Seen with `--demo` on a computer (the card) and a phone (the sheet): the layout, the tooltip, a shulker box's contents, a backpack opened, an offline player from Find a player with "saved 40 min ago" and "removals only while offline".

**Untested: the real BSP-InventorySync.** BSP-InventorySync is not loaded in the GameTests: its records were stood in for by look-alikes with the same accessors. Its own `read` (live, snapshot, a player arriving, Curios, backpacks and danks) has not run with this mod.

On the network (BSP-InventorySync 0.7.0 on every server):
1. Open an online player's sheet: INVENTORY shows "live" with the slots as they are in game; move an item in game and within about 15 seconds (or on ↻) the panel shows it.
2. Their armour from head to feet on the left, the offhand and the first three Curios slots on the right; open CURIOS for more when they wear more.
3. Tap an enchanted tool: its enchantments, durability and lore; tap a full shulker box: what it holds.
4. A backpack on their back or in their inventory: BACKPACK with where it sits and its slots; a backpack never opened says so.
5. More › Find a player: type the start of an offline player's name, open them: "saved <time> · <server>", the items of their last save; their backpacks say "removals only while offline".
6. A player switching servers while their sheet is open: the next read comes from the new server.
7. A player who never joined since BSP-InventorySync was installed: "Nothing has been saved for that player yet."
8. A server without BSP-InventorySync 0.7.0: its players' sheets say items need it.

## 2026-11-10: SU-40, adding and removing items from the staff panel (D257, D335, D339, D342, D343, D361 to D363, D371)

Headless: `WebItemsGameTests` (a Moderator without the items power refused before BSP-InventorySync; an unknown item, unreadable item data, more than 6400 refused; a Moderator with the power adds through BSP-InventorySync with the version shown; the answer's UNDO; a gold line to the player on this server; the Action Log; a slot holding something else untouched; a locked totem never moved; a removal of at most what is there with the version just read, its UNDO adding it back, armour coming back into the inventory; the UNDO of an add taking all 100 back from the two stacks it filled, then "nothing was taken back"; ONLINE answered with the server to ask; an `EditResult` read through its accessors). 127/127 GameTests, twice. Panel: `PanelServerTest` (403 without the power; the Owner gives and takes it, shown on the staff page and in `/api/me`; an id without its mod is Minecraft's; the power mark on a Moderator's action only; the UNDO token for 5 minutes, for the same staff member, once; ONLINE asked once more on the live server named; bad ids, areas and hashes refused; the panel's log). 62/62 JUnit, twice. Seen with `--demo`: + ADD with SURE? and the answer with UNDO, REMOVE from a tooltip (1, half, ALL), UNDO, the ITEMS mark on the staff page.

**Untested: the real BSP-InventorySync `edit`** (a stand-in holding 36 slots in the GameTests), the gold line in a game client, and nothing on the network.

On the network (BSP-InventorySync 0.7.0 on every server):
1. As an Admin, open an online player's sheet, + ADD `diamond` × 64, SURE?: the player sees "Staff added 64 Diamond to your inventory." in gold; the panel shows the answer with UNDO and the slot outlined; the LOG tab has "items add ...".
2. UNDO within 5 minutes: the 64 diamonds leave the player's inventory (from the slots they went into); the player sees "Staff removed 64 Diamond from your inventory."
3. Tap an enchanted sword, REMOVE ALL, SURE?: it is gone in game; UNDO puts the same sword back (enchantments and name kept), maybe in another slot.
4. Remove the player's boots: UNDO puts them in the inventory, not on their feet.
5. Move an item in game, then tap REMOVE on its old slot on the panel without reading again: "That slot changed since you looked".
6. A Shatter Totem in the inventory: its tooltip has no REMOVE.
7. An offline player (Find a player): + ADD into the inventory: in their inventory at their next login on any server; their backpacks have no + ("removals only while offline").
8. A player who switches servers between the read and the change: the change still lands (the panel asks the server named).
9. As a Moderator: + ADD and REMOVE dimmed with "Admin or ITEMS". The Owner ticks "May add and remove items" on the staff page: the Moderator (after a reload) can add and remove; untick: refused again.
10. More than 100 stacks (e.g. `dirt` × 6400 into a full inventory): BSP-InventorySync's answer (NO_ROOM) shows in red.

## 2026-11-10: SU-41, item pictures and the picker (D271, D340, D341, D360, D373)

Headless: `WebItemsGameTests.item_pictures_are_taken_only_from_an_admin_asked_whole_and_in_order` (not an admin: nothing asked; no shared storage: said so; another player's pieces ignored; a piece out of order refused and the ticket gone; a whole export stored in 512 KB parts with its row and the same bytes; a wrong SHA-256 not stored; too big refused before any piece). 128/128 GameTests, twice. Panel: `ItemPicturesTest` (the newest export read once, its catalog and sheets, other names in the zip skipped, a newer one that is not whole leaving the one held with a reason, a missing list said, the next good one taken; a sheet that is not a PNG refused, parts gone said), `PanelServerTest.item_pictures_are_served…` (the exports list, the catalog with caching, a sheet only of the export in use, logins needed). 65/65 JUnit, twice. Seen with `--demo`: pictures in the Items section, the picker (tabs, `@create brass`, a count, Add), More › Items.

**Untested: the drawing in a game client** (`client/IconExport`: the creative tabs rebuilt, items drawn into an off-screen target, sheets read back, the zip, the pieces). Nothing of it runs in the GameTests (a server only), and it has not been run in a client yet. Also untested: MariaDB's packet limit with 512 KB parts (well under its 16 MB default), and the panel reading the parts from MariaDB.

On the network (shared storage, the panel running):
1. As an operator in your own game with the full pack, type `/su icons`: a gold line says the pictures are being drawn; the action bar counts "Drawing item pictures: N / M", then "Sending to the staff panel: N / M pieces"; then "Done: N items in M tabs are on the staff panel." The game keeps running (a short stutter at the start is the tabs being rebuilt).
2. Look at the pictures: within a minute More › Items on the panel shows the export (items, tabs, you, the time, mods, size) and a row of pictures. Modded items (Create, Sophisticated Backpacks, BSP-Core) look as in game; animated ones show one frame.
3. A player's sheet: their items as pictures with English names; an enchanted book's enchantments in English; a potion's own picture and name.
4. + ADD: the creative picker; tabs as in the game; search `@create brass`; pick, count, Add, SURE?: the item arrives in game. A potion of swiftness arrives as that potion.
5. As a player who is not an operator: `/su icons` is not offered.
6. Leave the game halfway through sending: nothing is stored; `/su icons` again starts afresh.
7. After a pack update, `/su icons` again: More › Items shows the new one on top, the old one in LAST EXPORTS.
8. Very large packs: the export's size on More › Items (expected a few MB); MariaDB stores it in 512 KB parts.

## 2026-11-10: SU-42, a grave's items on the Graves page (D298, D301, D302)

Headless: `WebItemsGameTests.a_graves_items_are_read_through_inventory_sync` (a `GraveItems` look-alike read; a BSP-InventorySync without `graveItems` said so; a bad grave id refused first; the areas and XP, a locked totem, a backpack inside with its place; an empty or unknown grave said so). 129/129 GameTests, twice. Panel: `PanelServerTest.a_graves_items_are_read…` (asked of a server answering about graves, any rank, a bad id refused, a login needed). 66/66 JUnit, twice. Seen with `--demo`: two graves' slots with pictures, a tooltip, a backpack inside.

**Untested: BSP-InventorySync's real `graveItems`.**

On the network:
1. Die with a full inventory and a backpack, then open More › Graves for yourself: the grave's stacks as slots with their pictures, the backpack's contents below it ("BACKPACK IN SLOT n").
2. Tap an enchanted item: its enchantments in English.
3. Return the grave (as an Admin): RETURN works as before.
4. A grave with a Shatter Totem: its slot shows locked.

## 2026-11-10: SU-43, teleports from the staff panel (D258, D344 to D349, D364 to D366, D374)

Headless: `WebTeleportGameTests` (without BSP-Teleport said plainly; warps with portal ones marked and the spawns known; not on this server, still arriving or not a place refused; this server's own name in any case a move here with "You were moved by staff."; the Action Log; another server by name; "on the ground" refused for another server and the top block here; a player on this server gone to where they stand now; BSP-Teleport saying no said so; the snapshot's `tp`, `tpName` and `arriving`; a Destination look-alike read). 130/130 GameTests, twice (now against BSP-Core 1.16.1). Panel: `PanelServerTest.players_are_moved…` (places kept 30 s with spawns by the panel's server ids; a warp, a spawn in any case, a player with `target`, coordinates centred with "on the ground"; refused for another server's ground, not numbers, arriving; GO TO THEM and BRING THEM TO ME; the panel's log). 67/67 JUnit, twice. Seen with `--demo`: the four tabs, a move's answer, the coordinates form, an arriving player's greyed key.

**Untested: BSP-Teleport's real API** (a stand-in in the GameTests) and every move in game.

On the network (BSP-Teleport with API 2 on every server):
1. Open a player's sheet, TELEPORT, SPAWN: each server with a `/setspawn` point; MOVE to another server's: they arrive there with "You were moved by staff."; `/back` takes them back.
2. WARP: the network's warps by server, "restricted" ones marked; portal warps under "▸ N portal warps"; MOVE works without the warp's cost.
3. PLAYER: move them to someone on another server, and to someone on the same server (landing where that player stands at that moment).
4. COORDS: change x and z, tick "on the ground": they land on the top block; the tick is greyed for another server.
5. With your own account in game: GO TO THEM (you move) and BRING THEM TO ME (they move to you).
6. Right after a player switches servers: TELEPORT is greyed "arriving on <server>" for a moment.
7. A landing near a totem being raided on another server: BSP-Teleport's raid rule sends them elsewhere (its own behaviour).
8. The LOG tab: "tp <name> to <where>" by "<you> (panel)".

## 2026-11-10: SU-44, network bans (D255, D350 to D359, D367 to D370, D375, D376)

Headless: `WebBanGameTests` (the screen with the reason, the end in UTC and the time left, the appeal line, "This ban is permanent."; `PlayerList.canPlayerLogin` refused through the mixin for a banned player, not for anyone else, not after the end, not once lifted; the time in words; the panel's ban refused for a Moderator, without a reason or under a minute; written to the table with note and author, known here at once, the player here asked off the network with the screen, the other servers told, the Action Log; edited to permanent with a new reason; lifted by whom, joining again; lifting twice said so). 132/132 GameTests, twice. Panel: `PanelServerTest.bans_are_for_admins…` (the typed name checked; to the player's server, or any server for one offline; an Admin cannot ban a panel account or a staff role in game, the Owner can; nobody bans the Owner; custom days and hours, under an hour refused; ACTIVE, PAST, a search, a player's history; edit keeping the end; lift; the panel's log). 68/68 JUnit, twice. Seen with `--demo`: the form with the preview and history, SURE?, LAST CHECK (BAN asleep until the name is typed), the answer; More › Bans with ACTIVE, PAST, EDIT.

**Untested: the refusal at a real login through Velocity** (Velocity's own words around the screen), and every ban on the network. A player with a staff role is checked by the game server only when they are on it.

On the network:
1. As an Admin, ban a test account for 1 hour with a reason: if online, it leaves the network at once with the screen; joining again shows "Unable to connect to <server>" with "You are banned from the Build Secure Protect network.", the reason and "Ends ... UTC (in 59 minutes)".
2. Set `webPanel.banAppeal = "Appeal on our Discord: ..."` in `config/bsp_server_utilities-network.toml` on every server: the line shows under the end.
3. Join each server in turn (through the proxy): every one refuses.
4. More › Bans: the ban under ACTIVE; EDIT to Permanent: the screen says "This ban is permanent."; UNBAN (SURE?): within a minute (at once on servers that heard the notice) they can join; the ban moves to PAST "lifted ... by <you>".
5. A 1-hour ban left alone: after the hour they can join; PAST says "ended".
6. As a Moderator: BAN dimmed with "Admin"; More › Bans shows the lists but UNBAN and EDIT dimmed.
7. As an Admin, open a staff member's sheet: BAN dimmed with "Owner"; as the Owner it works; nobody can ban an Owner's account.
8. The LOG tab: "ban <name> (...): <reason>", "ban edit ...", "unban <name>" by "<you> (panel)".
9. Vanilla `/ban` still works per server as before (untouched).

## 2026-11-10: audit of phase 3 (items, item pictures, a grave's items, teleports, network bans)

D377 (owner: "Audit first"). Two reviewers (the mod; the panel), given the code and decisions D335 to D376; every finding checked against the code. No forgery, XSS, SQL injection or CSRF gap; the reflection on BSP-InventorySync API 2 and BSP-Teleport API 2 and every 1.20.1 call in `client/IconExport` checked against the jars (names and signatures match).

Fixed in the mod:
- **Item data nested too deeply** (`inv_add`): over 96 levels of `{}` / `[]` refused before it is read. The game's reader has no limit, so tens of thousands of levels overflowed the server thread's stack (a crash), and over 512 the item would have locked the player out (their client and the next snapshot refuse it).
- **UNDO of a removal from an offline player** carries the version the removal was saved as; before, BSP-InventorySync always answered CHANGED. An UNDO may carry item data up to 1 MB (the removed item itself, such as a shulker box of books); typed data stays at 64 KB.
- **The UNDO of an add refused by BSP-InventorySync** (ONLINE, BUSY, FAILED) says so with the outcome and server, instead of "none of it is there any more"; the panel's ONLINE retry now works for it. Stopping after 64 slots is said as such.
- **A banned player on another server** leaves the network once the bans are read again there (at once on the notice, or within a minute). Before, only the server that wrote the ban sent them off, so a player who switched servers in the moment of the ban kept playing (D350).
- **Staff who are not on the server** asked to ban them: "only the Owner bans staff" now uses the staff roster (online anywhere, the staff groups' members in LuckPerms while offline, operators who count as staff), not only a staff member standing on that server. `ban_edit` checks it too and names the ban's player (D355).
- **Lifting or changing one ban** no longer forgets a player's other ban in force until the reload.
- **"On the ground"** under a roof (the Nether) searches below the roof for a floor with two free blocks over it; before, it put the player on the Nether roof. Where there is no ground (the End's void) it is refused, where before it sent the player to the bottom of the world.
- Teleport destinations in the Action Log cleaned of colour codes and new lines. `/su icons` stops drawing when the game leaves the server.

Fixed in the panel:
- **An Admin could change a ban on staff** (`/api/ban/edit` checked only the rank): a staff member's ban is changed by the Owner alone, as it is given (D355). Lifting stays with Admins and Owners (D356). The ban form knows staff from `/api/ban/history` (`staff`: 0, 1 staff, 2 an Owner) and dims BAN before the two checks.
- **An UNDO used twice at the same moment** went to the game server twice (`get` then `remove`): it is now spent at once. When the game server answers BUSY or ONLINE (nothing changed), the UNDO is kept for its 5 minutes.
- **A grave's items**: read one grave after another; before, a player with five graves or more used every action the panel may have waiting at once, and other staff's actions were refused meanwhile.
- **A broken item-picture export** is read once, not every minute (48 MB each time); a row larger than the game server stores is not fetched; the pictures are read on their own thread so chat is never held up.
- A rank change ends the items power (an Admin made a Moderator again starts without it; the log says so). Each stack's REMOVE starts at ALL. + Ban by name says when the panel cannot be reached. A sheet path with a number past the largest long is a 404, not a 500. The warps and spawns from a moment ago are kept when asking again fails.

Dropped as untrue: none of the reported defects. Accepted limits:
- The panel knows an in-game staff member without a panel account only while they are online; the game server's roster check covers them offline (LuckPerms members, refreshed every 5 minutes).
- A panel Owner is protected by their Minecraft name: after a name change, the panel account must be updated (the game server still refuses an Admin's ban when the Owner is in a staff group).
- `/su icons`: checking and splitting the export (up to 48 MB) on the server thread is a short pause once per export. On a Velocity server switch while drawing, the pieces go to the new server, which ignores them silently: type `/su icons` again. Variants whose data is over 4096 characters are listed without it. Each sheet is read back on the render thread (a visible hitch per sheet).
- The panel reads an export's bytes while holding its database connection (once per new export).
- The panel accepts a custom ban of up to 3650 days and 1000 hours; the game server refuses over ten years with a clear message.
- The UNDO token is spent when the answer is lost or refused in any other way (it may have happened: the LOG tab says).

Tests added: `WebItemsGameTests.deep_item_data_is_refused_and_undo_works_offline_and_says_why_it_was_refused` (the look-alike now refuses an offline add of another version, as BSP-InventorySync does), `WebBanGameTests.staff_elsewhere_are_banned_by_the_owner_alone_and_a_ban_read_later_sends_them_off`, `WebTeleportGameTests.on_the_ground_stays_under_the_nether_roof_and_refuses_the_void`; `ItemPicturesTest.an_export_that_failed_is_not_read_again_and_the_limits_hold`; `PanelServerTest` (an UNDO kept after BUSY then spent, two UNDOs at once going through once, the power ending at a rank change, a Moderator refused BAN and UNBAN, an Admin refused EDIT on a staff ban while the Owner may and an Admin lifts it, the staff level in the history, the ban's player in an edit). 135/135 GameTests and 69/69 JUnit, twice.

On the network, in addition to the checks of SU-40 to SU-44:
1. Ban a player in the moment they switch servers (or ban a player on server A from a panel whose snapshot still shows server B): within a minute they leave the network with the ban's screen.
2. As an Admin, try to ban a staff member who is offline and has no panel account: refused "only the Owner bans staff"; as the Owner it works. As an Admin, EDIT a staff member's ban: refused; UNBAN works.
3. "On the ground" in the Nether: the player lands under the roof on a floor; in the End over the void: refused.
4. Remove an item from an offline player, then UNDO: it is back at their next login.
5. Add to a player, then press UNDO twice quickly: taken back once.

## 2026-11-10: SU-45, the staff panel serves the map (D259, D381, D382, D397)

Panel only. `PanelServerTest.the_map_is_served_from_bluemaps_folder_behind_the_login_with_its_own_rules` (401 without the login; "not set up" without `map.root`; `/map` to `/map/`; the page with the looser rules and `SAMEORIGIN` on `/map/` only, the panel's own pages still strict and `DENY`; a `.gz` sent with `Content-Encoding: gzip` and its type; a PNG tile; 304 when unchanged; 204 for a tile not drawn; 404 for anything else missing, a link out of the folder, `..` and BlueMap's live stream; logged out: refused at once). Seen with `--demo 8200 <the local trial's BlueMap web folder>`: BlueMap's own page under the panel's login, its tiles (flat and 3D), no page-rule errors in the console, `players.json` read every second (its live stream refused, so it asks).

**Untested:** a real mount from BlueMap's Pterodactyl server, BlueMap 5.29's page (the look was 5.16's), through Cloudflare.

On the network (once BlueMap runs, phase 4's instructions):
1. Open `https://staff.buildsecureprotect.com/map/` while logged in to the panel: BlueMap's page with the network's maps.
2. In a private window, logged in to Cloudflare but not the panel: "Log in to the staff panel first."
3. Log out of the panel, then reload `/map/`: refused.

## 2026-11-10: SU-46, the map's live data (D259, D386)

Panel only. `MapLiveTest` (map ids for the overworld, Nether, End, a modded dimension and nothing; every player on every map with `foreign` off their own, positions at the feet, the game's yaw; names that are not plain and ids that are not ids left out; warps by the panel's server name on their own map only, portal warps in a hidden set, a warp's name escaped, spawns per dimension, the `classes` list BlueMap's page needs, no places: empty sets) and `PanelServerTest` (players.json from the feed and not BlueMap's file, `no-store`; markers.json's sets; an unknown head 404; the live stream 404). 73/73 JUnit, twice. Seen with `--demo` and a flat `frostwarf_overworld` map rendered from the Frostwarf copy: the demo players' heads on the map moving with the feed, the warp pin, the spawn pin; a pin without `classes` was refused by BlueMap's page (found in the browser, fixed, now tested). The browser pane draws about 3 frames a second when not watched, so heads glide slowly there.

**Untested:** real players and skins, BSP-Teleport's real warps, graves as pins (SU-IS4 open).

On the network (with BlueMap running):
1. Open `/map/` and a server's overworld: everyone on it as their head and name, moving within a few seconds of moving in game; nobody from another server or dimension.
2. Go into the Nether: you leave the overworld's map and appear on that server's Nether map.
3. Warps and the spawn show as pins; a tap shows the name and server; portal warps appear when their set is switched on in BlueMap's menu.

## 2026-11-10: SU-47, the Map tab on a computer (D389 to D395)

Panel pages (`static/map.js`, `players.js` `stats` / `actions` / `closeKeys`, `items.js` `hotbar`, `app.js`, `panel.css`). `PanelServerTest`: the panel's pages may frame only the panel itself (`frame-src 'self'`). 73/73 JUnit, twice. Seen with `--demo` at 1440 × 900 and BlueMap 5.16's page on flat Frostwarf overworld and Nether maps: the list by server, the bar (server, dimensions, pins, following), a name tapped and the map following, the card (stats, hotbar, keys), the map going to the Nether with the gold line when the followed player's dimension changed (a made-up feed in the page; a switch still loading no longer lands after a newer one: found here, fixed), the trail as a BlueMap line, a warp pin's box moving the followed player there, the followed player leaving (greyed card and head, the gold line) and coming back.

**Untested:** a real game (players moving, changing server or dimension), BlueMap 5.29's page, phones (SU-48), the pins' box with real BSP-Teleport warps.

On the network:
1. Map tab: everyone online by server on the left; tap a name: the map goes to them and follows; the card shows where they are, stats, hotbar.
2. Drag the map away: "Following … centre" appears; tap "centre": back to them.
3. The player goes into the Nether, then to another server: the map follows each time with a gold line.
4. A dotted gold trail behind them for the last 2 minutes.
5. ITEMS in the card: the whole inventory as on the Players tab (+ and REMOVE for those allowed); NOTES; TELEPORT, KICK and BAN as in their card.
6. Tap a warp pin: its name and server and "Move <player> here"; tap it: they are moved, the answer shows.
7. The player logs out: the card greys with "left the network at …", their head greyed where they were; they log back in within 10 minutes: followed again.
8. Untick Warps, Spawns or Players in the bar: those disappear from the map.

## 2026-11-11: SU-48, the Map tab on a phone (D393, D395 R4)

Panel pages (`map.js`, `panel.css`). Seen with `--demo` at 375 × 812: the map between the top and the tabs, the server and dimensions above it (the pin ticks only on a computer), the players button opening the list as a sheet with its search at the top, a name tapped: the map follows and the player's sheet rises small (stats, Items, Notes, Stop following), its bar pulled up: hotbar, Teleport, Graves, Kick, Ban; the players button moved out of the sheet's way while following. The computer layout unchanged (checked at 1440). 73/73 JUnit, twice. In the browser pane's phone emulation BlueMap's drawing showed narrower than its frame although its canvas measured the frame's full size (a capture artefact there); BlueMap is asked to fit again after loading and on every resize.

**Untested:** a real phone (touch, the address bar's height, BlueMap's own touch controls inside the frame).

On a phone:
1. Map tab: the map fills the screen; pinch and drag move it.
2. The players button: everyone online; tap a name: the map follows them and their sheet rises.
3. Tap the sheet's bar: it rises for everything; again: it goes down.
4. Turn the phone sideways and back: the map fills the screen each time.

## 2026-11-11: SU-49, BlueMap's settings and the setup instructions

`tools/gen_bluemap.py` (`--self-test`: its map names against the panel's MapLiveTest cases, a flat Nether map with its roof left out). Run on the world copies: Frostwarf gives its overworld, Nether and End (AllTheModium's dimensions are empty there; the storage grids never); Spawn and Sanctuary also give AllTheModium's three. BlueMap 5.16's CLI drew Spawn and Sanctuary from the script's configuration in 24 s with no warnings, and kept every map name as written: **BlueMap turns any character but letters, digits and `_` in a map file's name into `_`** (found here: `bsp-spawn_nether` became `bsp_spawn_nether`), so map names became `<server>__<dimension>` in the panel (`MapLive`, `map.js`), the script and docs/API.md. Seen with `--demo` on that folder: the server picker (BSP-Spawn, Sanctuary, a server not sending made readable), the dimensions in order (Overworld, Nether, End, then the modded ones), following RuneSmith on `bsp_spawn__overworld`. 73/73 JUnit, twice.

**Untested:** BlueMap 5.29 (the trial ran 5.16; the settings' keys are 5.29's own templates), Pterodactyl's mounts and the Java 25 image, the whole network's first run (the trial, D388).

## 2026-11-11: audit of phase 4 (the map)

D399 (owner: "audit then Phase 5 in a new conversation"). Two reviewers (the panel's map routes, `MapLive` and `tools/gen_bluemap.py`; the Map tab's pages and docs/STAFF_MAP_SETUP.md), given the code and D378 to D398, every finding checked against the code and BlueMap's source. No path out of the map folder, no serving without the login, no CSP leak to other pages; every BlueMap call the pages make exists; the script's settings match BlueMap 5.29's own.

Fixed in the panel:
- **The map card was rebuilt on every live update**, losing a reason, note or item id being typed, its scroll, and an open server picker: the card now keeps its scroll and the field being typed in (focus and caret), and the bar is not redrawn while its picker is open (seen in the demo: a kick reason kept through two updates). The pin box's move cannot be sent twice.
- **Following a player whose marker BlueMap replaced** (they left and came back, or a poll failed) kept the camera on the old one: the new marker is followed; a player back within 10 minutes and a tab opened again are centred again.
- **A map switch left waiting when the tab was closed** could stop every later switch until a reload: the queue starts afresh with each frame, and a switch gives up after 15 s.
- The pin box opens at the pin (BlueMap passes `{event, data}`); a stale 1.5 s re-follow no longer catches a player staff moved on from; a pin's answer lands only on its own box; the pins chosen in the bar apply at once after a switch; the "left" marker sits where the player was last even before a trail, its name escaped; the card's "left" look no longer borrows the Staff tab's `.gone`.
- **A followed player in a dimension without a map**: a gold line says so, the camera is not sent there, and the trail never joins two worlds.
- **markers.json asked BSP-Teleport again on every poll while it failed** (every 10 s per open map, each holding one of the panel's four action slots up to 6 s): a failure is remembered for 30 s like a success.
- Pins: the label is sent as written (BlueMap shows it as text) and only the detail, which it puts in as markup, is escaped; two warps with names alike no longer share one key; a null field no longer drops every pin. Bedrock names (a "." first) are shown.
- `/map/` serves `.html` only as BlueMap's `index.html` and scripts only from its `assets/`.

Fixed in docs/STAFF_MAP_SETUP.md: the config is put in place with `rsync -a --delete …/ …/config/` (a second `cp -r` nested it, and BlueMap's own defaults, written if it started first, stayed), BSP-Map is first started after that; Java 3 GB in the 4 GB server; the panel's mount attached to its egg and node; `-s` dropped (`-u` writes the settings); who can write BSP-Map's files is trusted like staff (no subusers, no untrusted packs or scripts); BlueMap's copy buttons do nothing under the panel's rules (D382).

Accepted limits: the pin box moves the player followed (D395 R1 asked for the teleport sheet with the place chosen); warps of a server not sending now whose BSP-Teleport name differs from its id are not placed; a removed login still opens map files for up to 30 s; whoever can write BSP-Map's files can run code in the panel's name (narrowed above, stated in the setup).

Tests added: `MapLiveTest` (Bedrock names, two warps alike kept apart with null fields, the detail escaped and the label as written), `PanelServerTest` (`..` aimed at a real file beside the folder, a page and a script outside BlueMap's own refused, HEAD; a new test: the login under another Cloudflare identity not believed). 74/74 JUnit, twice. The mod is unchanged since its 135/135 GameTests.


## 2026-11-11: SU-50, the market and the lottery, game side (D400 to D415)

`WebMarketGameTests` (2): without BSP-Core 1.16 said plainly and the snapshot says no market; every rank reads the lots (seller, price, server, an enchanted pickaxe with durability, a shulker box with what it holds, an empty stack left out) and the orders; REFRESH reads again first; the database down answered as such and never as an empty market; the lottery unreachable said so; a huge market cut to size with `cut`; the lottery's pot, names, phase and wins; a Moderator refused a draw even with a signed action; an Admin's draw goes to BSP-Core as "Ana_Admin (panel)" and is in the Action Log; busy, nobody, empty and unreachable worded; the snapshot's `market`, `lottery`, `lotterySince`, `lotteryUp`; a phase change seen once with its time. The stand-in is as strict as BSP-Core's api (server thread only, empty lists and read times 0 while not connected, `by` one line of at most 64). The second test reads the real BSP-Core 1.16.1 the test server loads (its market read time, the lottery's summary and names, the phase). 137/137 GameTests.

**Untested:** BSP-Core's market and lottery on the network (MySQL and Redis, several servers), a real draw from the panel.

On the network (once SU-51 and SU-52 are in, BSP-Core 1.16.0 or newer on every server):
1. List a lot and place a buy order in game; open More › Market: both there within 30 s, the read time moving; REFRESH reads at once.
2. Stop MariaDB for a moment (or start one server without it): its market reads "cannot be read", never an empty board; back once the database is.
3. Draw the lottery from the panel as an Admin: the countdown on every server, the win on every server's lottery board, "lottery draw" by "<name> (panel)" in the Action Log of the drawing server.
4. Draw again during the countdown from another panel: "A draw is already running on the network."
5. A Moderator sees DRAW dimmed; with the pot empty, the draw is refused with "The pot is empty".

## 2026-11-11: SU-51, the market and the lottery, the panel's side (D400 to D415)

Panel only. `PanelServerTest.the_market_and_lottery_are_read_by_everyone_drawn_by_admins_and_every_page_sees_a_draw`: no server with BSP-Core 1.16 said so; the servers chosen (database connected first, then the busiest; a server without the market never); a Moderator reads the market, kept 30 s for every page, REFRESH asks again with `refresh`; the database down passed on as such; the lottery read; a Moderator's DRAW refused with 403 and nothing sent; the Owner's draw on the busiest connected server with the rank, in the panel's log; the feed's `lottery` while a server counts; the `counting` event once, with who drew and since when; the `won` event with the winner, the pieces and who drew; a draw begun in game has no panel name, and one that ended without a newer win is `ended`. 75/75 JUnit, twice.

**Untested:** real game servers answering (SU-50's actions over Redis), the events in a browser (SU-52).

## 2026-11-11: SU-52, More › Market and More › Lottery (D400 to D415)

Pages only (75/75 JUnit unchanged). Seen with `--demo` at 1440 × 900 (with the chat column open) and 375 × 812: the Market Board as wide rows and as cards, a shulker box's tooltip with what it holds, the Wanted Board with its bars, Vexa's sheet "On the market: 1 lot, 1 order" opening the market filtered to her with the chip; the Lottery page in two columns and on a phone; DRAW, SURE? DRAW, the countdown in the pot card's corner, the winner in the card, the new win marked at the top of the list, the gold line "Lottery drawn by Demo_Owner: Kestrel_88 won 12,400 pieces", the card back to the new pot after a minute; a Moderator's DRAW dimmed with "Admin". No page errors.

**Untested:** real game servers, real players' lots, a real draw seen by two staff at once, a real phone.

On the network (with BSP-Core 1.16.0 or newer on every server):
1. More › Market: the lots and orders as in game (a Trade Post's Market Board and Wanted Board), the same prices and ends; tap a shulker box in a lot: its contents.
2. List a lot in game, then REFRESH: it appears; buy it in game, REFRESH: it is gone.
3. Open a player's sheet who has lots: "On the market: …"; tap it: only theirs, ✕ shows everyone again.
4. Two staff open More › Lottery; one draws: both see the countdown in the pot card, then the winner and the gold line; the game's countdown on every server; the winner paid in game.
5. A Moderator's DRAW: dimmed. Draw with an empty pot: refused with the reason.

## 2026-11-11: audit of phase 5 (the market and the lottery) and the setup guide (D416)

Two reviewers (the game side; the panel, its pages and `docs/STAFF_PANEL_SETUP.md`), given the code and D400 to D415. Every finding was checked against the code; all were real. Nothing critical: no way for a Moderator or anyone with Redis alone to draw, no unescaped text in the pages, the routes under the panel's POST checks, a server without BSP-Core 1.16 never touching its classes.

**Fixed (game side):**
1. The adapter could call our callback a second time with a made-up answer when our own code inside it threw (on a single server BSP-Core answers at once, inside our `try`): a draw that began could be reported as failed, an error shown as an empty lottery, a REFRESH answered twice. Now a failure is answered only when BSP-Core failed before answering; an error after is logged once. Each answer is built inside its own guard, so the panel always gets exactly one.
2. An error reading the market or lottery is said as an error ("could not be read on <server>"), never as the database being down or an empty lottery.
3. The market's answer is bounded on the server thread: at most 6,000 stacks read (shulker boxes' contents counted), and no hash computed for view-only items.
4. The Wanted Board has its own share of the answer (built first), so a large Market Board cannot leave it empty.
5. Tests: the stand-in checks the thread on every call; a REFRESH whose database answers later; an unreadable market and lottery; a refused draw not logged; a drawer's name that is not a name; a normal read not `cut`; the real BSP-Core with a callback that throws (called once, logged, nothing escapes).
6. `docs/API.md`: DRAWING also while a draw is being paid; `result` `failed`; names at most 5,000; the answers when BSP-Core errs; orders' `name`; no `h` on market items; the new caps.

**Fixed (panel):**
1. A game server that did not answer made pages queue behind each other for 6 s each (the reads held a lock while waiting). One read goes out at a time; other pages get the copy held or the failure, which is remembered 8 s; REFRESH at most once in 5 s for the whole panel (BSP-Core reads its whole database).
2. A page could stay on "Counting" for good when the draw's end was missed (a phone asleep, a reconnect): the page reads the lottery again when the feed has had no draw for 10 s, and after every reconnect.
3. One server stuck saying COUNTING blocked DRAW for every Admin: a draw said to run for over 2 minutes is ignored.
4. The winner card said "won among 0 players in" (the new round's count): the count at the draw is kept and sent with the win.
5. The end of a draw read the wins once; a win not written yet, or every action slot busy, gave "ended" without the gold line, and a win missing a field stopped the event: read again 2 s later, fields checked, always one event.
6. The first "counting" could lack who drew (the countdown's snapshot can arrive before the draw's answer): who drew is known before asking.
7. One bad snapshot could stop the whole live feed (the lottery watch ran inside the feed's single `try`): it has its own.
8. Small: "tried to draw the lottery" logged when no server could; a lot or order without a name no longer blanks the page; "ended, closing soon" instead of "ends in 0 s"; the computer's header row by the stylesheet (no redraw on resize); SURE? keeps the key focused (Enter twice works); `down` read as a value, not a key.
9. Test: `a_game_server_that_does_not_answer_is_asked_once_not_by_every_page_and_the_busiest_draws`; the stuck draw ignored.

**Fixed (setup guide):** the `/su status` lines quoted as the game prints them; Redis must be plain TCP (a troubleshooting row); an allocation on `127.0.0.1` when cloudflared runs as a service on the node; `panel.properties`'s Cloudflare comment names today's menu.

**Accepted limits:** `WebMarket`'s phase watch is static, so on an integrated server restarted in the same game `lotterySince` can be old once (a dedicated server restarts its JVM). No test follows a `lottery` event through a page's live stream end to end (the desk's events and the page are each tested; seen together in `--demo`). REFRESH's own redraw drops the search box's focus.

137/137 GameTests and 76/76 JUnit, twice; build and name check clean. Seen again with `--demo`: the market, a draw with SURE? from the keyboard, the winner among 16, the gold line.

## 2026-11-11: audit of the whole staff panel (D420 to D429)

D420 to D424 (owner: "All defaults"): five reviewers by area across all five phases, given the code and decisions D247 to D419 (not the earlier audits' findings): security; the game side; the pages; deployment (both setup guides followed step by step against the code); the join between the mod and the panel (every message as each side writes and reads it). 84 findings; every one checked against the code. All were real but one (below). The fixes that changed behaviour were put to the owner first (D425 to D429, "All defaults").

**The join between the halves** (never tested before: each side only against a stand-in for the other): the bytes match (channels, framing, tables, both signatures, every action's fields and answers). A shared test vector now checks the signatures on both sides (`WholeAuditTest`, `WebAuditGameTests`): RFC 8032's Ed25519 key for the panel's actions, a fixed HMAC key for what the servers send, worked out apart from either side's code.

**Fixed, game side:**
- A server started before MariaDB answered read the whole notices table (a day) as new: old broadcasts shown, item clears started, players named in old staff kicks kicked again; and its tables were not made until a restart. Now it waits for the database, starts after the newest notice, and skips table notices over 10 minutes old.
- Answers travel on Redis only: an action is carried out only while this server's Redis can carry the answer back (its snapshot says `answers`; `/su status` says when not). An action past the panel's deadline (`until`) is refused, so nothing happens that staff were told got no answer.
- Snapshots timed by the clock (a server at 5 TPS kept dropping off the panel); `snapshotSeconds` 2 to 10; a last empty snapshot when stopping, so the panel drops the server at once.
- Bans kept on each server's disk (`serverconfig/bsp_server_utilities_bans.json`, no staff notes) and read at start: a server started while the database is down still refuses banned players.
- A ban's staff note kept whole (500 characters, line breaks; it was cut to 256 on one line). Broadcasts and grave returns checked for Admin rank here too. A teleport's y within the world's build height. Every table utf8mb4, and older ones named in the log with the line that converts them. A market never read since a start is read, not called "not connected"; BSP-Core's own connected state decides it. `/su status` names a malformed key.
- **D426:** actions and the notes counts are checked with the panel's public key (`webPanel.panelKey`, Ed25519); a game server holds no key that can order an action. **D427:** only the Owner lifts a ban on staff.

**Fixed, panel:**
- The Redis listener took a refused SUBSCRIBE (a wrong password, a user without the channels) as listening and never said a word: every action said "No answer". It now checks Redis's replies, logs each change once (`status` shows it), never stops for good, refuses messages over 8 MB.
- Actions sent only while the panel's Redis listens and Redis took them (not through the table alone), and not to a server that cannot answer; a deadline on each; 15 s for UNDO's take-back, REFRESH and bans. Answers only from the server asked; an UNDO can only ever be an items change, and an action's fields never replace who asked or their rank. Typed item data only as the item pictures list it.
- The live view at most every 5 s (seven servers redrew every page every second), with a named `ping`. A server held by its own interval and dropped at once when stopping; one with another key named in the console; Cloudflare's refusal reasons and the servers' table errors logged once, not every request or every 5 s; database timeouts (15 s); a database that cannot be used at start said in one sentence; a slow request closed after 15 s; graves and items asked of a server that can answer; chat rows tied to their signed uid, late replays not shown live, the table read with an overlap, hidden format characters as spaces; a `__Host-` cookie over https; `panel.properties` written for a container.
- **D425:** the panel's own tables in `database.panelName` with a user only the panel has. **D426:** `signingKey` signs every action. **D427:** UNBAN and EDIT on a staff member's ban dimmed for Admins. **D428:** 48-hour logins for Moderators with the items power; giving or taking it ends their logins.
- Pages: nothing redraws under a field or chooser in use (Players, the sheet, Teleport, Find a player, the tag picker, chat search, the item picker, the map card); + BAN on More › Bans works after typing the reason (it always said "Write the reason"); chat appends lines and scrolls back past 1,500; a 45 s watchdog; notes, chat and the room read again after a reconnect without gaps; 20 s request timeouts with plain wording, never a raw error page; no double sends; 16 px fields and a ± key on phones; the staff ⋯ menu reachable on a phone; the gold line below the "Not live" banner; cosmetic Curios slots shown; SURE? keeps keyboard focus; note cards with the author's rank.

**Fixed, setup guides:** Redis follows BSP-Core's settings (the guide's `enabled = true` was not read); the `[webPanel]` lines edited in place (a second section stops a server); addresses as seen from a container, `listen.host=0.0.0.0`, the allocation on 127.0.0.1 with cloudflared as a service; a Java egg to import; one jar name; the panel's own database and its SQL; the two keys; the setup link opened again after Cloudflare's login; utf8mb4; the full BSP-Core jar (the slim one has no MySQL driver); serverIds equal to BSP-InventorySync's and Velocity's; a "Running it" part (off switch, leaked keys, backups, updates, restarts); BlueMap's resource packs kept by `rsync`.

**Dropped as untrue:** the map guide's "about 3 GB" against D398's 8.5 GB: 3 GB is the corrected figure (the assessment's section 11); D398's row is the stale one. "A stale market shown as fine when BSP-Core's database goes": BSP-Core returns empty lists when not connected (its contract), so only the other half (never read yet) was real.

**Accepted limits:**
- BlueMap's files run with the panel's rights at `/map/` (D429, as in phase 4): only the Owner writes BSP-Map's files.
- What the game servers send to the panel is still signed with the shared `actionKey` (D426): someone with a game server's files could show the panel made-up players or answers, not order anything.
- An action that started on a slow server may finish after the panel stopped waiting ("No answer … it may still have happened"); its UNDO token is not given back on a timeout.
- The table path judges freshness by the game server's clock (all on one node, one clock). Older shared tables are not converted to utf8mb4 by themselves (the owner runs the logged lines). The "replaced" page may stop a computer's page while a phone leaves dead connections. A warp name with markup in BlueMap 5.29's pin label is unchecked (scripts are blocked there). `/su icons`'s one pause per export. Chat history after a long gap restarts from the newest page.

**Tests:** `WebAuditGameTests` (7: the shared vectors and the panel's Ed25519 key, a server started before its database, actions only when answerable and before their deadline, bans on disk, a ban note whole, a market never read) and new checks in `WebGameTests` (clock timing, the 10 s cap, the stopping snapshot), `WebActionGameTests` (Moderators' broadcast and grave return refused), `WebTeleportGameTests` (y below the world), `WebBanGameTests` (an Admin's lift of a staff ban refused). Panel: `WholeAuditTest` (11: the vectors, the envelope and deadline, answers only from the asked server, refusals before sending, UNDO's fields, a server's interval and stop, chat rows and replays, typed item data, the cookie, a refused SUBSCRIBE, the own database's names), `FeedAndConfigTest` (the container template, both key pairs, the own database), `PanelServerTest` (the cookie, live every 5 s, the Owner-only lift, D428's logins), `NotesTest` (the author's rank). **144/144 GameTests and 87/87 JUnit, twice**; build, lang and name checks clean. The pages seen with `--demo` at 1440 × 900 and 375 × 812 (focus kept through live updates in the search, the Teleport chooser and the chat box; + BAN to SURE?; no console errors).

**Untested:** everything on the network. No real Redis, MariaDB, Velocity, Cloudflare, game client or phone has run any of this. The local trial with Redis and MySQL (D423) was not run: the line gave about 9 to 20 KB/s (MySQL's 164 MB would have taken about 5 hours) and there is no database on the Mac; the owner chose to skip it (D430), so the network install is the first time the two halves meet real Redis and MariaDB: run the checks below first. Real phones: the keyboard through redraws, iOS zoom, the ± key.

On the network, in addition to the checks of SU-32 to SU-52:
1. Make the panel's database and user (the guide's part 5) and start the panel: its console says `Redis …: listening on bsp_su:network:web`. Give `redis.password` a wrong value: the console says Redis refused and why, and any action answers "the panel is not connected to Redis".
2. Put `actionKey` and `panelKey` on one server only: `/su status` there says the panel's actions are taken; another server without `panelKey` says none are taken until it is set.
3. Stop Redis for a minute: a kick on the panel answers that the server cannot answer the panel (or that the panel is not connected); nothing happens in game; start Redis: within 30 s actions work again.
4. Restart a game server: it disappears from the panel at once and comes back when it starts. Start a game server while MariaDB is stopped, then start MariaDB: no old announcements appear, and a banned test account is refused all along.
5. Set `snapshotSeconds = 10` on a server: it stays on the panel.
6. As an Admin, try to UNBAN a ban the Owner put on a staff member: dimmed "Owner"; as the Owner: lifted.
7. Give a Moderator the items power: they must log in again, and their login lasts 48 hours.
8. On a phone: type a note, a kick reason, a ban reason and coordinates while players move: nothing jumps or closes; turn on airplane mode for a minute: "Not live", then back by itself.
9. Put an emoji in a ban reason: the Action Log has it (after the utf8mb4 lines the game server logged, if any).

## 2026-11-11: the map across four machines (D431 to D434)
The game servers are on four machines (`.25` SafeRoom; `.26` Spawn, Sanctuary; `.27` Frostwarf, Boulderbrook; `.28`
Moongrove, Stormfall), and Pterodactyl mounts reach only their own machine. BlueMap now runs on `.25` beside the panel
and reads the other worlds over NFS, each world folder shared read-only to `.25` only (never a server's `config/`).
BlueMap's own source shows its file watcher sees only local changes, but its region check (`region-file-check-interval`,
5 minutes, already set by `tools/gen_bluemap.py`) finds changes on any file system: the map is at most about 5 minutes
behind. Nothing in the mod or the panel changed; `docs/STAFF_MAP_SETUP.md` rewritten step by step; the script's notes.
`tools/gen_bluemap.py --self-test` ok.

**Untested:** all of it (NFS between the machines, the mounts, BlueMap 5.29 reading over the network, the first
drawing's time on `.25`).

On the network, after the map guide:
1. On `.25`, `ls /mnt/bsp-worlds/<server>/region | head` lists region files for all seven worlds.
2. BSP-Map's console: every map `updating`, then `Your maps are now all up-to-date!`.
3. Build or break something on Frostwarf (on `.27`), wait for the world to save (a few minutes, or `save-all` in its
   console), then within 5 more minutes the panel's map shows it.
4. Stop `.28` (or its NFS service) for a minute: BSP-Map keeps running; Moongrove and Stormfall's maps catch up once it
   is back (if a share says `Stale file handle`: the guide's unmount, `sudo mount -a`, and restart BSP-Map).

## 2026-11-11: audit of the map setup (the guide and `tools/gen_bluemap.py`)
Two reviewers, given the guide, the script, the panel's map code and D380 to D398 and D431 to D434 (not conclusions):
one against BlueMap 5.29's source, one against the NFS, fstab, systemd and Wings documentation and source. Every
finding checked before fixing; BlueMap's CLI source read here to confirm the stop finding.

Fixed:
- BSP-Map used the panel's egg (Stop `stop`, started text the panel's line). BlueMap's CLI never reads its console
  and stops only on a signal (its shutdown hook saves): Stop did nothing, Restart killed it after 10 minutes without
  saving, and it showed Starting forever. The guide now imports a `BlueMap` egg: Stop `^C`, started text `Start updating`.
- The two SafeRoom bind lines in `.25`'s `/etc/fstab` had no `nofail`: a wrong UUID would stop `.25` starting up
  (emergency mode: no SSH, database, Redis or panel). `nofail` added, and `sudo findmnt --verify` after every change.
- A share missing when `.25` starts is not mounted later by itself, and a container never sees a share mounted after
  it started (Docker's binds are private): the guide's step after a restart or a machine being off (mount, check,
  restart BSP-Map); the same restart added to the stale-share fix and to a server moving machine (unmount first,
  daemon-reload).
- Wings' `allowed_mounts` listed all of `/var/lib/pterodactyl/volumes` (any server's files, passwords included):
  now only BSP-Map's folder, and the guide says to replace the usual `allowed_mounts: []` line and check Wings runs.
- `--threads 4` contradicted "half of four cores": 2. Java 25 typed as a custom image; the startup changed only in
  the Admin area; Wings' log for a refused mount.
- `gen_bluemap.py`: a modded folder it cannot read (a share down) crashed it half way, leaving the settings
  incomplete; now said and left out. Bare dimension names (`the_nether`) gave other ids than the panel's `mapId`;
  now the same. Two names giving the same map id silently overwrote one another; now said and the second left out.
  Self-test has the panel's cases for these and an unreadable folder (the old script fails both, the new passes).

Checked and correct: every key the script writes exists in BlueMap 5.29 with that meaning; the CLI flags;
Java 25; `region-file-check-interval` in minutes and run by `-u`; the web folder's file names and the panel's `.gz`
serving; Wings refusing a mount under a folder the server could change (so the whole BSP-Map folder is mounted);
`ufw ... port nfs` (2049, NFSv4); `bind,ro`; `rsync --delete --exclude packs/`; the sizes.

Accepted: the void servers (Spawn, Sanctuary) show an empty Nether and End (D383: three on every server); the region
check reads every region file's 8 KB header each 5 minutes, about 80 MB over the network per check at about 10,000
region files (an estimate); a modded dimension missing from `level.dat` is drawn with overworld light; two modded
dimensions with the same path in different mods share a label; shares use `no_subtree_check`, so `.25`'s root could
reach other files on those disks (`.25` already holds the database, Redis and the panel's keys).

**Untested:** everything on the network, as above. Added checks: Stop BSP-Map: its console says `Saving...` then
`Stopped.` within a few seconds; restart `.25` with `.28` off: `.25` starts normally, and after `.28` is back the
guide's step brings Moongrove and Stormfall back.

## 2026-11-11: audit of the panel setup guide (`docs/STAFF_PANEL_SETUP.md`)
Two reviewers, given the guide, the code and D420 to D430 (not conclusions): one against the code (every key, file,
command and quoted line), one against Pterodactyl, Wings, MariaDB and Cloudflare's own documentation and source. Every
finding checked before fixing; Wings' `environment/allocations.go` read here.

Fixed:
- **The tunnel pointed at nothing.** Wings publishes a `127.0.0.1` allocation on pterodactyl0's address (normally
  `172.18.0.1`; `DockerBindings`: "replaces any reference to 127.0.0.1"), so `http://127.0.0.1:8100` gave every visitor
  Bad gateway. The route is now `http://172.18.0.1:8100` (checked with `ip -4 addr show pterodactyl0`), with a `curl`
  check and separate 1033 and 502 rows.
- **The panel's database login.** From its container on `.25` MariaDB sees `172.18.x.x`, not the `bsp` user's host:
  `bsp_panel` is now created for `172.18.%` (from pterodactyl0's address), and the Access-denied row says how to rename it.
- Locking down the game servers' logins: every login in their files is checked (not only `bsp`; LuckPerms has its
  own), `REVOKE ALL PRIVILEGES, GRANT OPTION`, and every database it used is granted back so nothing else loses access.
- **Code:** the panel's console and the mod's `web_actions_bad` status line told the owner to copy `actionKey` from
  the console's `action-key`, which prints `panelKey` only (an owner following them broke that server's feed). Both
  now say `actionKey=` in `panel.properties`; the guide says what `action-key` prints.
- The egg: the exact file (`egg-pterodactyl-java-generic.json`, not the `.yaml`), the nest made first, **Java 17**
  from the egg's list, the startup command always set with `-Xmx512M` (without it Java takes a quarter of the memory),
  **Start Server when Installed** unticked (also in the map guide); no staff member a Pterodactyl admin.
- Cloudflare: a team name of your own (`bsp` is likely taken), "Create a tunnel" and the one **Service URL** field;
  a leaked tunnel token means deleting the tunnel (a refreshed token leaves a running copy connected), the install
  command typed after a space (kept out of the history); removing someone needs **Revoke existing tokens** too.
- Velocity Additions' network kick: three cases by the proxy's console line, and the newer build if it says neither.
- `USE bsp;` before the utf8mb4 `ALTER TABLE` lines (they name tables without the database); the Redis row has
  WRONGPASS; a missing `bsp` grant shows as `[panel] reading the ... table`; the storage line's real ending; the
  start-up lines' order not fixed; the Redis settings' files named; backups with `umask 077`, `--single-transaction`,
  `mysqldump` and Docker forms (the root password read inside the container, never typed).
- The values at the top (and the map guide's world folders) were read from older copies of the servers, which the
  owner says differ from the live ones: each now says where to check it on the live servers.

Checked and right: every `panel.properties` key and default; the panel's first-start, refusal and start-up lines; the
console commands; the grants match what `JdbcStore` uses; the mod's `[webPanel]` keys and `su status` lines; the
done text and Stop Command; the Access JWT checks (team domain, AUD); One-time PIN; no `X-Forwarded-Proto` needed.

**Untested:** all of it on the network. Added checks: `curl -sI http://172.18.0.1:8100` on `.25` answers 403; the
panel's console shows no `Access denied` for `bsp_panel`; `SHOW GRANTS` for `bsp` has no `ON *.*`.

## 2026-11-11: SU-53, the Compact Machines adapter (the room limit, R-2)
The first task of the room limit (owner decisions D437 to D453): `compat/CompactMachines`, which reads Compact Machines
6.0.3's own saved data and stands aside with any other version or without the mod. Nothing a player sees yet.

Build: compiled against the pack's exact file (`curse.maven:compact-machines-224218:6000230`, compile only). The mod
loads in a dev run only with `-PwithCm`, and that run keeps a world of its own, `run/world_cm`: Compact Machines
writes its room dimension into `level.dat`, and a world that has it cannot load without the mod. Count a `-PwithCm`
run as passed only by its own "All N required tests passed" line (Gradle exits 0 when the world fails to load).

GameTests (`CompactMachinesGameTests`):
- `./gradlew runGameTestServer`: only 6.0.3 is read (absent, another build); rooms are counted per owner, and a slot
  kept for nobody (the placeholder owner, D447) counts for no one; `room_pos` is read as chunk x and z, and a missing,
  malformed or wrongly typed tag is no room; an item that is not a machine names no room whatever its tag; a chest or no
  block entity is bound to nothing; outside the room dimension nothing is a room; without the mod every answer is nothing.
- `./gradlew runGameTestServer -PwithCm`: a real room made for a mock player counts for them and for nobody else, and
  reads back with its owner and inside size 7; a placed machine bound to it names it, an unbound one names none, and the
  room's placed machines are found (and no longer found once the block is broken); a position inside the room is in
  it, a chunk with no room is not; a machine item bound with Compact Machines' own `setRoom` names its room; the
  Personal Shrinking Device is recognised; the room still counts after its machine is broken.

**Untested:** a real server with players; the rooms the network's servers already have (counts read from them only in
later tasks).

## 2026-11-11: SU-54, the network room list and the settings (R-2)
The server config gains `[rooms]`: `limit` (0, no limit: rooms are only counted, D437), `scope` (`network` or `server`,
D438), `exemptGroup` (empty, D441), `graceDays` (7, D446). Every server with Compact Machines 6.0.3 reads its rooms each
minute (and at once after a change), writes them to `<prefix>cm_rooms` when they changed and tells the others (notice
`cm_rooms`), or else only records that it was heard from (`<prefix>cm_servers`); see `docs/API.md`. Nothing is limited
yet (SU-55), and nothing a player sees.

GameTests (`RoomListGameTests`):
- `./gradlew runGameTestServer`: the defaults (no limit, network, 7 days, no group); a count is this server's rooms
  plus one for each counting row on another server, never this server's own rows from the table nor a room in grace
  nor a slot for nobody, and this server's alone per server; servers not heard from for 5 minutes are stale, never
  this one; a server's rows are replaced as a whole, each server's last word is kept with or without rows; the table
  read back counts another server's room, and a notice from another server reads it again; without Compact Machines
  this server writes nothing.
- `./gradlew runGameTestServer -PwithCm`: a real room is written under this server with its placed machine
  (`minecraft:overworld x y z`, one machine) and the time, and the other servers are told once; read again unchanged,
  only the time is written and nobody is told; with another server's row the player's count is 2; with its machine
  broken and the change marked, the room is written with none placed and the others are told again.

**Untested:** a real MySQL (the two tables' SQL runs first on the network) and two real servers sharing their rooms.

## 2026-11-11: SU-55, the room limit (R-2)
With `[rooms] limit` set, a right click with the Personal Shrinking Device in the main hand on a Compact Machine that
has no room is refused at or over the limit (D437, D438): Forge's `RightClickBlock` is cancelled before Compact
Machines' `use` runs, so no room is made and the machine stays unbound, and the player reads one action-bar line at most
once a second (D442: "You own 5 of 5 Compact Machine rooms. Give one back in /su rooms to make another.", or how many
to give back when over). Both hands' events are looked at, since Compact Machines reads the main hand whichever hand
clicks. Entering a machine that has a room is never refused; an owner over the limit entering their own room is told
in chat, at most once a minute, how far over they are (D440, Z5). Operators and `exemptGroup` are never limited (D441).
`/su rooms`, named in the lines, comes in SU-58.

GameTests (`RoomLimitGameTests`):
- `./gradlew runGameTestServer`: refused at and over the limit, never without one or when exempt; give back down to
  one under the limit; operators and the configured group (case ignored) exempt; the two refusal lines' keys and the
  number to give back; a click on a chest does nothing.
- `./gradlew runGameTestServer -PwithCm`, through Forge's real event on real machines: the first room passes and
  marks the room list for a refresh; at the limit the next is cancelled, nothing is bound and no room made; the line
  at most once a second; the off hand's event refused too, the device in the off hand alone refused nothing; entering
  a bound machine allowed at and over the limit; the warning over the limit only, once a minute, never for someone
  else's room; an operator not refused; another player's count their own; a room on another server counts in network
  scope and not per server; no limit refuses nothing.

Checked broken: with the handler looking at the main hand's event only, the `-PwithCm` test fails on the off hand.

**Untested:** a real client (the action-bar line and the chat warning as players see them, the client's arm swing and
any flicker after a refused click) and LuckPerms groups on a real server.

## 2026-11-12: SU-56, losses and the grace list (R-2)
A dropped item bound to a room, fully destroyed, puts the room into grace (D444, D446): `ItemEntity.hurt` (a mixin, HEAD
and RETURN: alive before, removed after) gives burnt, exploded, cactus, crushed or destroyed by the damage; the void (a
mixin on `Entity.onBelowWorld`); vanilla's expiry (`ItemExpireEvent` at the lowest priority, not cancelled); this
mod's despawn timer, in its check and when an item that ran out while unloaded comes back, and its clear (both a
hook before the item goes). The room's server is the item's `bsp_inventorysync:home`: this one acts, another is sent
`cm_lost`, an unmarked item is ignored where BSP-InventorySync runs (D451). The room is not put in grace when a placed
machine is still bound to it. In grace it stops counting at once, its shared row says `grace` with when and why, and
placing a machine bound to it takes it out (checked at the next tick after a machine is placed, and every minute).
Owners are told at once when online on the room's server, otherwise three seconds after their next login anywhere;
a server telling an owner about another server's room sends that server `cm_told` (Z6). Clearing is SU-57.

GameTests (`RoomLossGameTests`):
- `./gradlew runGameTestServer`: the route (here, case ignored; forwarded; ignored unmarked with BSP-InventorySync;
  here unmarked without it); the reason words for fire, lava, an explosion, a cactus, an anvil and anything else; days
  left rounded up, at least 1; the grace list through a save and a load, marked told, a room leaving it; a non-machine
  item frees nothing; another server's room in grace told once (not the told one nor an active one), that server told
  back with the room, not told twice; `cm_told` from another server marks this server's room told; the lines wait 60
  ticks after login.
- `./gradlew runGameTestServer -PwithCm`, with real bound machine items: burnt (in grace for 7 days, not told, the
  count down by one, not put in twice); blown up while a placed machine is still bound (nothing); discarded as a pickup
  is (nothing); fallen into the void; expired by vanilla; despawned by this mod's timer; marked for beta and burnt in
  lava (nothing here, beta sent `cm_lost`); `cm_lost` from beta (in grace here); five in grace, two count; the shared row
  says grace, void, until when and not told; at login the owner is told five times and each is marked told; a machine
  bound to a room in grace placed again revives it.

Checked broken: without the hook in the despawn timer's check, the `-PwithCm` test fails on "despawned by this mod's timer".

**Untested:** the clear's and the unloaded-item paths of the despawn timer (the same one-line hook, read but not run in
a test), real items burning in real lava over ticks, two real servers (`cm_lost` and `cm_told` across them), a loss
whose home server is down for more than 10 minutes (the notice table's limit: it is not acted on), and the lines in a
real client.

## 2026-11-12: SU-57, clearing a room after its grace (R-2)
Every minute, rooms whose grace has ended are cleared, at most four at a time, soonest first (D446). Each clear runs on
the server thread: a room with a placed machine bound to it again is taken out of grace instead; otherwise its chunk is
loaded, players inside go to their respawn point (Compact Machines' own eject), machines inside are noted and unbound,
every block inside is removed with its block entity first (containers drop nothing, D449) and no neighbour updates,
tunnels in the walls become plain wall, entities left inside are removed (a bound machine item among them counts as
lost), Compact Machines forgets the room's tunnels, upgrades and graph node, and its record is replaced in the same tick
by one owned by nobody (D447, the handoff's 7.1). The rooms of machines that stood inside go into grace (`inside`, D450).
A player logging in inside a room kept for nobody is sent out. A machine bound to a cleared room is unbound when entered
and makes a new room under the limit (Z8).

Found while testing: removing a machine's block entity before its block skips Compact Machines' own unbinding, so the
machine graph still listed the inner machine and its room was never put in grace. Each machine inside is now unbound
explicitly first.

GameTests (`RoomClearGameTests`):
- `./gradlew runGameTestServer`: due rooms soonest first, four at a time, none before their time; nobody must leave a
  place outside the room dimension; without Compact Machines nothing is cleared.
- `./gradlew runGameTestServer -PwithCm`: a real room with a chest of 64 diamonds, a machine bound to another room, a
  dropped machine item bound to a third, a tunnel in its east wall and the chunk-loading upgrade is cleared: chest and
  machine gone, nothing dropped or left lying, the tunnel plain wall, the upgrade gone, the slot kept for nobody at size
  7, the number of registered rooms unchanged, out of the grace list; the other two rooms in grace (`inside`); none of
  the owner's rooms counts; a login in the cleared room must leave, in a room in grace not; the next new room lands on a
  fresh position and is its maker's (the 7.1 regression); a machine bound to the cleared room is a fresh machine when
  entered; a room with a placed machine bound to it is not cleared and leaves grace.

Checked broken: with a bare remove instead of the slot kept for nobody, the `-PwithCm` test fails ("the slot is kept").
That run made the hole in `run/world_cm`; the world was moved aside and the suite passed again on a fresh one.

**Untested:** a player really inside a room being cleared (the eject's teleport) and one logging in there, rooms with
modded blocks whose own removal does more than their block entity (AE2, Refined Storage, Create), a dense 13-block room's
cost on a real server, and two real servers.

## 2026-11-12: SU-58, the player's `/su rooms` page (R-2)
`/su rooms` opens the page drawn in `tools/preview/rooms_refined.html` (look B, D452, D453). `/su` and `/serverutils`
are now open to every player on a server running Compact Machines 6.0.3, who are shown `rooms` only (every other
subcommand keeps its own staff or editor check; `/su` alone tells a player to use `/su rooms`). The page lists the
player's rooms on this server and, with network scope, on the others (Z3), this server's first, then other servers',
then rooms in grace, biggest first (R1). GIVE BACK, with SURE? within three seconds, is offered only for this server's
rooms (Z4) that are not in grace, have no placed machine (R3: dimmed with "Pick up its machine first") and nobody
inside; the server checks it all again, puts the room into grace (reason `given_back`), tells the owner and writes the
Action log (kind `room`, staff only, D448). The protocol is 7.

GameTests (`RoomsPageGameTests`):
- `./gradlew runGameTestServer`: the page's order; the packet through the wire; a player who is not staff reaches
  `/su rooms` exactly where Compact Machines runs, and never `/su status`.
- `./gradlew runGameTestServer -PwithCm`: a player's real rooms (empty, with a placed machine, lost) and one on beta:
  the settings, the count (3: the lost one does not count), four rooms with the lost one last and its time left,
  beta's after this server's with its machine's place and given back only on beta, the placed one "pick it up first",
  the empty one offered; give back refused for someone else's room, a placed machine, a room in grace; given back:
  in grace, no longer counted, in the Action log as "Player"; twice does nothing more.

Checked broken: without the owner check, the `-PwithCm` test fails (run in a throwaway `world_cm`).

**Untested:** the screen itself in a client (it is compiled and its data tested; not seen), the scroll with many rooms,
SURE? timing, someone inside a room (GIVE_INSIDE), the page refreshed after a give-back, the Action log's new kind in
the LOG tab and on panels, and players on an older client (protocol 6 is refused at login, as for every version change).

## 2026-11-12: SU-59, the admins' ROOMS tab (R-2)
The admin panel (`/su`) has a ninth tab, ROOMS, after SERVERS (look A, D452; R4 to R7, D453), for operators and
BSP-Core admins as the other tabs (R6). On the left every owner of rooms with a search: their rooms against the limit
("7 / 5", or the count alone with no limit or when exempt), how many over (red) or "exempt", and on how many servers;
most over the limit first, then most rooms (R4). On the right the picked owner's rooms on every server in the player
page's order (size, name, server, machine placed or not, "grace · N d" in amber), with FREE for this server's rooms in
use with no placed machine (SURE? within three seconds), "there" for another server's, a dimmed "free" with "Pick up its
machine first" when a machine is placed; hovering a room shows its position, its machine's place and, in grace, why and
how long. FREE is checked again on the server (still that owner's, not in grace, no placed machine), puts the room into
grace (reason `freed`), tells the owner "Staff freed your Compact Machine room on <server> (<size>)" without the staff
member's name (R7, at once or at their next login, as for losses) and writes the Action log (kind `room`) under the staff
member. The footer: the limit, the scope, the owners, the rooms in grace, and any server not heard from for five minutes
in amber with its minutes (R5). The tab refreshes every five seconds; REFRESH reads the shared table again. Room rows now
carry the owner's name (`owner_name`, docs/API.md) so other servers' owners are named; an operator shows as exempt even
offline, a LuckPerms exempt group only for players online here.

GameTests (`RoomsAdminGameTests`):
- `./gradlew runGameTestServer`: the owners' order (two over, exempt with nine, at the limit, on three servers, only
  rooms in grace; with no limit, most rooms first), slots kept for nobody left out, names taken from any row; both
  packets through the wire; FREE offered only for an empty room in use here (not placed, another server's, in grace);
  the owner's line is `rooms.freed` with the server and size only; an offline operator exempt; a player who may not
  edit gets no data and cannot FREE; an operator gets the tab.
- `./gradlew runGameTestServer -PwithCm`: Ann with an empty room and one with a placed machine here and one on beta,
  Bob with one: her row (three counted, two servers, two over, named from beta's row), the whole list in R4 order with
  Ann before Bob, her three rooms with FREE, placed and "there", beta named as not heard from for 12 minutes; FREE by Bob
  refused and nothing changed; refused for the placed room and for a room not the named owner's; freed by an operator:
  in grace for seven days with the reason `freed`, the Action log entry under the operator ("Operator"), freeing twice
  does nothing more, and her count drops to two with one room in grace.

158/158 without and with `-PwithCm`, each twice. Checked broken: without the admin check in `RoomsAdmin.apply`, the
plain test fails ("a player who may not edit gets no data"); run without Compact Machines, so `run/world_cm` is untouched.

**Untested:** the tab in a client (compiled and its data tested; never seen): the nine tabs and the smaller title in the
row, the columns' fit with long names, the search box, scrolling both lists, SURE? timing, the hover lines, the amber
footer; the owner's chat line as seen; two real servers (other servers' rows and names, a stale server); real MySQL
(the new `owner_name` column); LuckPerms exempt groups.

## 2026-11-12: audit of the room limit (SU-53 to SU-59), before naming 0.11.0
Four reviewers by area, given the code, the Compact Machines 6.0.3 jar and decisions D437 to D454 (not each other's
findings): the adapter and clearing; losses, mixins and notices; the room list, the limit and storage; packets, pages,
permissions and screens. Each finding was checked against the code before anything changed.

**Fixed:**
- Clearing a room stood the whole room limit aside until a restart: Compact Machines 6.0.3's own `disconnect` ends by
  clearing a graph node that is null for an unbound machine or one in a chunk just loaded (as rooms being cleared
  are), and the error made the adapter stop. The machine is now unbound as Compact Machines' own `onRemove` does (out
  of the machine graph, its tunnels unregistered), and that one null is caught.
- A clear that throws (another mod's block on removal) no longer reaches the server tick (a crash, then the same room
  again after every restart): it is logged, the room stays in grace and is tried again after the next start. A room
  whose record could not be kept for nobody stays in grace (counting for nobody) instead of counting again.
- A room's entities load on another thread since 1.17: a room in an unloaded chunk was emptied of blocks while its
  items, frames, mobs and bound machine items were not there to remove. The clear now asks for the chunk (a ticket of
  our own) and clears at the next check, once its entities have loaded.
- A room with tunnels stayed force-loaded for ever after its clear (Compact Machines only lets go when a bound machine
  loads): the clear now lets it go.
- A loss or "told" notice about another server's room was lost for good when that server was down or starting (a
  starting server skips earlier notices). Each is kept (`RoomOutbox`) and sent again when that server has written its
  rows well after the notice without showing it (in grace, a placed machine, or marked told), at most 10 times in a day.
- BSP-InventorySync installed but not syncing on a server: lost machines there are unmarked and free nothing (as
  before, D451), and the server log now says so once.
- An unknown or overlong reason (from a notice) is stored and shown as "destroyed", never a raw key or a value longer
  than its column. The player page no longer shows a raw key for an unknown reason, and says "Freed by staff" for a
  freed room (it said "Lost · freed by staff").
- This server's rows were sent to be written again only when they differed from the last write that finished: a change
  undone before its write left the table wrong for a minute. They are compared with the last sent now, and written
  again after a failed write. Any failure inside the rows' write rolls back (the server's rows were deleted on a
  non-SQL error). Placing machines over and over rewrote the rows each tick: now at most every 5 seconds.
- Server names compared ignoring case everywhere (the count and the stale list compared exactly; MySQL does not). A
  machine's place is cut to its column (160).
- Long server ids or names no longer make the page's and the tab's packets throw (cut to their limits).
- FREE is null-safe on the room's owner. Give-backs from a modified client are taken at most twice a second, and only
  where Compact Machines runs. The picked owner is always among the first 1,000 owners sent.
- The ROOMS tab says in amber when, with network scope, the shared list has not been read since the start (its
  database was down), so only this server's rooms are counted; before, nothing said so.

**Tests:** `RoomAuditGameTests` (3: the notices sent again and when not; reasons, case and long strings; rewrites
spaced, the picked owner kept, a player inside a room seen, give-backs spaced). `RoomClearGameTests` now has an unbound
machine inside the room, a room force-loaded for its tunnels, and waits for the room's entities. `RoomsAdminGameTests`
calls the real offline-operator check. `RoomsPageGameTests` checks ten staff subcommands are out of a player's reach.

**Decided after the audit (defaults, asked in chat; the owner said "carry on"):**
- D455: giving a room back, making a new one and placing the old machine again brought the old room back past the
  limit, as often as wanted. Placing a machine bound to a room in grace is now refused while the room's owner is at or
  over the limit (exempt owners never), with an action-bar line; the machine Compact Machines had just bound is unbound.
- D456: a renamed or retired server's rows counted for ever (R5). `/su rooms forget <server>` (operators, BSP-Core
  admins) drops them from both tables, refused for this server, a server heard from in the last 5 minutes and a name
  the list lacks; action log; the ROOMS tab's stale line names the command.
- D457: the player's page showed the coordinates of every machine bound to their room, also one another player placed.
  Players now get the dimension only; staff still see the place on the ROOMS tab.
- Tests: `RoomAuditGameTests` `owners_decisions_after_the_audit_d455_to_d457` (the rule, `dimensionOnly`, `mayForget`,
  the memory backend's `forgetRooms` ignoring case, and the console's forget through storage) and
  `with_compact_machines_a_given_back_room_is_not_placed_back_past_the_limit` (refused at the limit and unbound, placed
  under it, a room in use never refused). Untested: the place event itself in a client (the refused block and item put
  back by Forge), and `forget` against real MySQL.

**Accepted limits (as designed, D444 to D451):** a room can be given back while another player carries its machine (only
the owner is told); bound machines inside containers in a cleared room, or in a destroyed shulker or backpack, free
nothing (their rooms keep counting until given back); a creative copy of a bound machine burnt while the real one sits
in a chest puts the room in grace (the owner is told and can place it); notices kept for sending again are forgotten
when this server restarts; players inside a room whose machine stood in a cleared room are not sent out; another mod's
teardown in its block's removal may be skipped when its block entity goes first (AE2, Refined Storage, Create: the
network checks' item 11); BSP-InventorySync and this mod must use the same server id (each has its own setting); a fake
player with a fixed id owns the rooms it makes.

**Untested:** a clear in a chunk that really was unloaded (the test's room is force-loaded), a clear that throws, a
notice really missed by a restarting server, and everything in "SU-60 … checks on the network" below.

## 2026-11-12: SU-60, release 0.11.0 (the room limit, R-2): checks on the network
Nothing of SU-53 to SU-59 has run on the network, in a real client, against a real MariaDB or across two real servers;
every server-side rule has its GameTest (163 after the audit and D455 to D457, passing twice without and twice with `-PwithCm`, the pack's real Compact
Machines 6.0.3; and against BSP-Core 1.17.1). Install first: Compact Machines exactly **6.0.3** on every server that has it (another version leaves
the rooms off and logs it); BSP-Core **1.17.1** or newer on every server that has Compact Machines (it keeps Shatter
Totems out of rooms, so an emptied room never takes one with it: SU-BC2, D450); BSP-InventorySync **0.8.0** on every
server (the home mark, D451); Server Utilities
**0.11.0** on every server and in the client pack (protocol 7: older clients are refused at login). Leave `[rooms]
limit = 0` until checks 1 to 4 pass, and use a test player's rooms only: an emptied room cannot be brought back.

1. Start every server: the log has no Compact Machines warning, and MariaDB has `<prefix>cm_rooms` (with `owner_name`)
   and `<prefix>cm_servers`, one row per server running Compact Machines, `heard_at` moving every minute.
2. As a player: `/su` says to use `/su rooms`; `/su rooms` lists your rooms on this server and the others (other
   servers' with a dimmer square), with the dimension each machine is placed in and never its coordinates (D457). As an operator: the ROOMS tab lists every owner with
   their names (an owner known only on another server too), and the footer says every server has been heard from.
3. Stop one server running Compact Machines: after 5 minutes the ROOMS tab's footer names it in amber, and its rooms
   still count. Start it: the name goes within a minute.
4. Look at the ROOMS tab with long names and many owners: the columns fit, the search finds a name, both lists scroll.
5. Set `limit = 2` on every server (the same values everywhere) and restart. A test player with two rooms right-clicks
   a new machine with the Personal Shrinking Device: refused with one action-bar line, the machine stays unbound, no
   flicker. With three rooms (made while `limit = 0`), entering one says in chat how far over, at most once a minute.
   An operator is never refused; a player in `exemptGroup` is not either, and shows violet "exempt" while online.
6. On server A, the test player gives back a room (its machine picked up, nobody inside) with GIVE BACK and SURE?: it
   shows as given back with its days left, stops counting, and the LOG tab has the entry (kind `room`). From server B,
   the same room shows "give back on A" and cannot be given back there.
7. Drop a test machine item (bound, marked home A by BSP-InventorySync) into lava on server B: within seconds server A
   puts its room into grace; the owner is told once: at once if they are on A, else at their next login anywhere.
   Place a machine bound to it: the room is back, not in grace.
8. On the ROOMS tab, FREE a test room on this server: the owner reads "Staff freed your Compact Machine room on …"
   without the staff member's name; the LOG tab names the staff member. FREE on a room with its machine placed is
   dimmed; on another server's room it says "there".
9. Set `graceDays = 1` for a test room in grace with a chest of junk, a player standing inside and a machine placed
   inside: after a day the room is emptied (the chest drops nothing), the player is sent to their respawn point, the
   inner machine's room goes into grace (`inside`), Compact Machines' `/cm` commands still list the rooms right, and
   the next new room is made in a fresh place. A player logging in where that room was is sent out too.
10. A machine item bound to that emptied room, placed and entered: it makes a new room (counted under the limit).
    With `limit = 2`, the test player gives back a room, makes a new one, then places the given-back room's machine:
    refused with one action-bar line, the block and the item come back, the room stays in grace (D455).
11. Put rooms with AE2, Refined Storage or Create inside one to be emptied, and watch the server's tick time at the
    clear (a dense 13-block room).
12. Rename a test server running Compact Machines (`storage.serverId`), restart it, and after 5 minutes type
    `/su rooms forget <old name>`: the old name leaves the ROOMS tab's footer and its rooms stop counting on every
    server; the LOG tab has the entry. The same for this server's own name, or a server just heard from: refused (D456).

## 2026-11-17: SU-61, the AFK and macro kick check

An audit of AFK detection, the presence check, macro detection and the kick against the decisions (D70 to D85, D207 to D232, D254), with fixes. Headless: `AfkGameTests` (19; new: a still player opening the same furnace every few minutes stays active while a click every 90 s is a macro (D470); the end of the AFK time followed through to the network kick, as Velocity Additions reads it, and this server's own kick 3 s later when the proxy does not act (D466); a jiggle between two exact angles every 4 minutes caught while a person's new looks still count and two looks then nothing show no "macro?" (D464); steering a macro only at one spot (D465); a machine's fake player never keeps its owner active; different chat lines and commands are different inputs while the same line again is a macro; a check behind the rules popup ends and comes back with its full 2 minutes; `/afk` after a look stays, a login takes any card away, long AFK tab fields are cut; edges: the right word at the end of the 2 minutes is too late, the macro watch at a millisecond short of ten minutes and at ten, the hour's clearance a second before and on the hour, "what was seen" counting from after the hour). **Still not seen in a client or on the network**: the card has never been drawn in a game; the macro signs have only met simulated input, never a real auto-clicker or jiggler; the network kick has never run with real players.

Test settings (AFK tab): AFK after 1 min, kicking on, check after 5 min AFK, macros on; CHUNKS tab 1 minute. Before: every server and client on the same jar; the proxy's console line for the network kick (on, off or absent); `/su status`'s AFK lines. Screenshots for 1, 2, 3, 6 and 12.
1. Stand still: "You are now AFK." after 1 minute; at 4 minutes "You will be checked for being idle in 1 minute." in red; at 5 minutes the red "Still there? ..." line and the card.
2. The card: at least 8 pixels from the edges, off the hotbar and the crosshair. With JourneyMap's minimap where the card meets it, the card is on top.
3. With no screen open the keys cannot be clicked; T opens chat, the "Press T" words go and the keys click. Open the inventory or a chest: the card is on top; a left click on its title or padding over a slot picks nothing up, and a right click on it does nothing. Pick up a stack in a chest, then click the card's right word (the card outside the chest window, then over a slot): the stack stays on the cursor. With JEI's list under the card, clicking a key answers the check and opens no recipe.
4. A wrong key: the red "Not that one: here is a new word.", new keys, the card in the same place, the clock still running. A fast double click sends one answer.
5. The right key: "Thanks, you are back." in green, the card goes.
6. Next time let it run out: the player lands on the multiplayer screen with the kick message (off the network, not moved to another server); `/su` LOG, kind "AFK checks": "<name> did not answer the presence check: no input for 7 min".
7. Change the GUI scale, or resize the window, during a check: the card moves somewhere new, still off the hotbar and the crosshair.
8. Move to another server through the proxy during a check: the card does not follow.
9. Press F1 before a check: when it comes, the HUD shows again with the card and the red line (D467).
10. Chat set to Hidden: T does nothing; E opens the inventory and the card's keys click there.
11. An operator idles: never warned or checked. A staff member (a staff group, not an operator): the card reads "or you will stay AFK"; no answer: still AFK, not kicked, the log entry ends "Left AFK: staff are never kicked", and no second check until they move. In the AFK tab their row reads "checked, 1:42 left" in gold, not red (D468).
12. An auto-clicker on a zombie, or a fishing rod, at one spot every second, the mouse untouched: 10 minutes, then AFK a minute later; the AFK tab shows "macro?" and hovering it says what was seen. Checked at 5 minutes AFK; answer it: the clicks count again for an hour.
13. A mouse jiggler between two positions: the same, "the same head turns between 2 exact angles ...". Moving the mouse by hand brings the player back at once. Set the jiggler to once every 3 or 4 minutes: still "macro?" after about 15 minutes and AFK (D464).
14. A Create deployer working beside an idle player who placed it: the player still goes AFK after a minute and is checked; the deployer's work does not answer or end the check.
15. Chat different lines at one spot for 12 minutes without moving the mouse: never AFK. The same line pasted every 20 seconds: "macro?" after 10 minutes.
16. During a check, an admin asks everyone to agree to the rules again: the card goes and the rules popup shows; after agreeing, a new card with 2:00.
17. A player with a machine goes AFK: after a minute the CHUNKS tab shows them AFK and the machine stops while they are online; they move: the machine runs again.
18. Turn "Repeated input does not count" off on one server: it is off on another. Turn kicking off: idle and macro players are still marked AFK, with no warning, card or kick.
19. A long ride in a boat on ice, W held and the mouse untouched for 15 minutes: never AFK. The boat against a wall, W held: "macro?" and AFK (D465).
20. The network kick (D466): with the proxy's network kick on, an unanswered check leaves the player on the multiplayer screen and the proxy's console has "kicked <name>"; the game server's console has no warning. Switch `[networkKick] enabled = false` on the proxy and repeat: about 3 s later the player is kicked by the server, its console warns "was still on this server 3 s after the proxy was asked ...", and Velocity may move them to another server, which is why the proxy's network kick must stay on.
21. With MySQL: the first start of a server adds the `macros` column to `<prefix>afk_settings` without an error in the log.

## 2026-11-18: SU-62 to SU-65, mob limits and the MOBS tab

Headless: `MobGameTests` (6: the settings kept to 0 to 1000 with ids cleaned, round tripping, every number OFF at first, the most specific number winning, a chunk on another server another place, a dimension at OFF overriding every chunk's, shared with a rising revision and an older copy ignored; a full chunk refusing new mobs spawned for each refused reason and a real `/summon`, while an armour stand is let in uncounted, mobs spawned from a bucket, as jockeys, raiders, conversions or trap-triggered are let in, a real skeleton freezing into a stray survives, a never-counted type is let in, and the limit taken away lets mobs in; two cows bred in a full chunk giving no calf, a spawn egg used there on a block, on water or on a cow cancelled (so kept), a cow egg and cow breeding allowed once cows are never counted, and the egg working again with room; the fullest list with the chunk's count and kinds, the named one kept, REMOVE EXTRAS refused to a player and taking 10 endermites (the most numerous) for an admin, never the named silverfish, the Action Log entry, nothing more at the limit; HERE and LIMIT adding a chunk at the number that applies, or what it holds; another server's list decoded, dimmed after 30 s, left out after 2 minutes, its dimensions offered; the tab's packets round tripping). **The MOBS tab has not been seen in a client**, and nothing has run on the network or against MySQL (`<prefix>mob_settings` is created on the first start).

In game (an operator, two servers sharing storage; a Compact Machines room with a dark floor):
1. `/su` shows ten tabs with MOBS last; the title still reads. MOBS: EVERY CHUNK OFF, no dimensions, no single chunks, nothing never counted; the footer says no limits are set. The right side lists the fullest chunks on both servers within a few seconds ("asking the servers..." first).
2. Stand in the dark room until it fills with monsters (or bring cows in): its row reads "room · <owner>" with the count. Pick it: what is in it, LIMIT.
3. ADD under DIMENSIONS: the menu lists every dimension of both servers by name (Compact Machines rooms, Overworld, The Nether, The End, modded ones in words). Pick Compact Machines rooms: its line appears; set it to 25 with + (shift + for 10). The room's row turns red "96 / 25"; REMOVE 71 EXTRAS shows on the server the room is on, and on the other server "Removing extras works on <server> itself."
4. REMOVE EXTRAS, then SURE? within 3 s: the most numerous kind goes down to 25, a name-tagged or tamed mob stays; the footer says "Removed n mobs."; LOG tab, kind "Mobs removed": "<you> removed n mobs from <owner>'s room on <server> (96, limit 25): n zombie".
5. In the full room: no new monster appears; breed two cows: no calf and the action bar says "This chunk is at its mob limit (25)."; a spawn egg does nothing and stays in the hand; `/summon zombie` says it cannot summon. Leave and come back (the chunk reloads): the mobs are all still there.
6. HERE in an ordinary chunk: SINGLE CHUNKS gets "<server> x z" at the number that applies (or what the chunk holds), lit for a moment; hover shows its block coordinates; × takes it away. LIMIT on a listed row of the other server adds that chunk for that server.
7. NEVER COUNTED: type `villager`, ADD: the chip "Villager" appears; a villager breeds in a full chunk. A wrong name shows "No mob is called minecraft:...".
8. On the other server, open MOBS: the same numbers. Stop one server: after 30 s its rows dim, after 2 minutes they go.
9. Lower EVERY CHUNK to OFF and take the dimension away: nothing is refused; `/su status`: "Mob limits: none set".
10. In a full chunk: a zombie villager cured, a villager struck into a witch, a skeleton in powder snow turning stray, a tadpole growing into a frog: each changes, none vanishes. A fish or axolotl emptied from a bucket comes out. Mobs through a nether portal arrive. A raid whose spawn point is in a full chunk still runs its waves. A dispenser firing spawn eggs into it uses the eggs up and makes nothing.

## 2026-11-18: SU-66 to SU-68, crowded drops and arrows (the ENTITIES tab's DROPS & ARROWS page)

Headless: `CrowdGameTests` (4; also the time starting again after a dip under the number, and the item lying longest going first when it was never stamped (despawn times off); the numbers on from the start at 256, 128 and 5 minutes, below 0 OFF, kept to their limits, round tripping with the mob limits and kept when a mob limit changes; a chunk of 9 items (one a death drop) and 5 arrows over 5 and 3 left alone at once and a second before the minute, then cleared to 5 items (the four oldest others; the death drop kept) and 3 arrows (the two oldest), a trident never counted, both in the Action Log by "Drops check", counted for the last hour, the time starting again after a dip under the number, items at OFF left alone; the list showing a chunk with a quarter of a number and, once over, for how long; CLEAR NOW refused to a player and for another server's chunk, taken for an admin down to the number at once with the log naming them; another server's list first when more over, and left out after 2 minutes). `MobGameTests`' tab packet carries the drops list and the last hour. **The page has not been seen in a client**, nor has anything run on the network.

In game (an operator; a test chunk where items can pile up, e.g. a hopper-less mob farm or `/give` and Q):
1. `/su` shows the tab ENTITIES; under the tab row MOBS and DROPS & ARROWS. The MOBS page is as before (a little shorter).
2. DROPS & ARROWS: ITEMS PER CHUNK 256, ARROWS PER CHUNK 128, OVER FOR 5 min; − and + step 8 (shift 64) down to OFF; minutes 1 to 60 (shift 5). "Last hour on this server: nothing cleared."
3. Set items to 16 and minutes to 1. Drop 40 stacks in one chunk: within a few seconds it is listed with "40 it." in red and a bar with the time over, counting up.
4. After a minute (plus up to 30 s): 24 stacks vanish, the oldest first; players within 32 blocks of the chunk (corners too) read "Items lying in a crowded chunk near you were cleared (24): too many for 1 min."; LOG tab, kind "Drops cleared": "24 items cleared on <server>, overworld x z (40 for 1 min, limit 16)" by "Drops check". The last-hour line counts it.
5. Die in a chunk holding many items: your death drops stay when the chunk is cleared.
6. Pick a crowded chunk on this server: CLEAR NOW, then SURE? within 3 s: it goes down to the number at once; the log names you. On another server's chunk: "Clearing now works on <server> itself."
7. Arrows: a skeleton farm or dispensers firing into a chunk that does not tick entities; past 128 (or a lower number set) for the minutes: the oldest arrows go. A trident thrown into it is never touched.
8. Set items to OFF: a chunk full of items is listed with "items off" and nothing goes.
9. On another server, the same numbers; its crowded chunks appear in the list within a few seconds while the tab is open.

## 2026-11-18: a player's Bank & Market on the staff panel, the agent (SU-69, SU-70)

Built against BSP-Core 1.18.0 (its staff calls `BspBank.findPlayer/account/log/refund`, `BspMarket.lotsOf/ordersOf/cancelLot/cancelOrder`, checked with javap against its `docs/API.md`). GameTests: 183/183 twice, and twice with `-PwithCm`. `WebBankGameTests` (2): with a fake BSP-Core as strict as the real one (server thread only, no nulls, `by` at most 64 characters): the read by UUID and by name, the account, coin tiers, log rows with what is left, a long detail cut, an unknown type passed on, only the player's own lots as display copies (a 50-page book and a filled shulker box sent without pages or contents), their request; an unreadable log and the database down said as such; a Moderator refused a refund and a cancel even with a signed action, no reason refused, a Moderator with the BANK power allowed; BSP-Core given exactly the row, amounts, other and "Ana_Admin (panel)"; bad amounts, rows, players and lot ids refused before BSP-Core; all 14 refusal keys and the cancel keys worded with "Nothing changed"; the refund without the other player offered only for `other_short` and `other_row`; the Action Log (kind "Refunds & cancels") only on `ok`, with the reason. With the real BSP-Core 1.18 in the test server: 500 pieces credited to a new player, read, 200 refunded through `bank_refund`, 301 refused as `too_much`, `other` refused as `not_payment`, 300 left with a REFUND row naming the staff member, an unknown lot `gone`. Checked broken: letting every rank refund fails the Moderator assertion.

**Not tested:** anything on a network (BSP-Core's refund transaction against real MySQL, two staff refunding one row from two servers, the next-login notice), and the panel's pages, which are not built yet (mock-up set AC, `tools/preview/staff_bank_concepts.html`).

## 2026-11-18: the panel's side of Bank & Market (SU-71)

GameTests 183/183 again both ways after the snapshot's `bank` flag. JUnit: 88/88 twice (`PanelServerTest.a_players_bank_is_read_by_everyone_and_refunds_and_cancels_need_admin_or_the_bank_power`): no server with BSP-Core 1.18 refused; `liveWithBank` connected first, then the busiest, none without the snapshot's `bank`; a Moderator reads a player's bank from the player's own server; nobody by that name refused; a Moderator without the power gets 403 for a refund and a cancel with nothing sent; the Owner gives the BANK power, which ends his logins and gives a 48-hour login, shown on /api/me and the staff page; a missing reason, negative, zero, non-numeric amounts and row 0 refused with nothing signed; a refund sent with `bank: true`, the row, amounts, other and reason, the game server's refusal passed on with `withoutOther`, the panel log line kept back from the page and written with the refusal; a cancel naming both a lot and a request refused; a request cancelled on the first able server; a change of rank ends the power, and an Admin is refused the power. Checked broken: every account allowed fails the test. The staff page's switch "May refund and cancel (bank & market)" and the BANK badge are built, **not seen in a browser yet**.

## 2026-11-18: the Bank & Market page (SU-72)

JUnit 88/88 twice. Checked in the browser pane on the demo panel (`--demo`, made-up bank answers in `Demo.demoBank`), on a computer and at phone width (375 px, no sideways scroll): the key on an online player's sheet and on an offline player's Find a player page; the money, the three page keys with counts, the six chips; rows of every kind (a payment both ways, market and tax, a conversion refunded in part with its violet bar, a REFUND row "of row 1200", a deposit "their own coins · not refunded", a type the page does not know shown as it is); a payment's refund (FULL REFUND filled, the other player's switch on, SURE? naming both sides, the demo's refusal for the other player, REFUND WITHOUT STEVE_42 arming a SURE? where Steve_42 keeps theirs, the answer, the page read again); a conversion's two amounts typed apart; a request's CANCEL with its reason and SURE?; a Moderator without the power (the gold line, no row opens, no CANCEL). **Not tested:** a real phone, a real game server's answers, BSP-Core's refunds on a network.

In the panel (an Owner, a Moderator account, a test player with some bank history, BSP-Core 1.18.0 on the servers):
1. Open a player's sheet: a Bank & Market key beside Graves. It opens their page: pieces, the coins in their bank, LOG with their rows newest first.
2. The chips filter the log; MORE shows 50 more; REFRESH reads again ("read 0 s ago").
3. As a Moderator without the power: the gold line, rows do not open, LOTS and REQUESTS have no CANCEL.
4. As an Admin: tap a payment row. FULL REFUND fills what is left; the switch "Also take it back from <other>" is on; REFUND stays off until a reason is typed. Tap REFUND: "SURE? REFUND" and the red line naming both players; tap again within 3 s. The answer is green, the row shows the refund, a REFUND row appears, both players are told in game, the Action log (kind "Refunds & cancels") names you and the reason.
5. Refund a payment when the other player has spent it: "The other player no longer has that much…" and REFUND WITHOUT <name>; taking it refunds only the player.
6. A coin conversion: AN AMOUNT, give some coins back and take some pieces back; the bank shows both.
7. LOTS: CANCEL a lot with a reason: the items are in the seller's Collect box at any Trade Post. REQUESTS: CANCEL a request: the held pieces are back in their bank.
8. The Owner gives a Moderator the BANK power (staff page menu): they log in again, see BANK beside their rank, and may do 4 to 7.

## 2026-11-18: CANCEL on the Market page (SU-73) and the audit of Bank & Market

SU-73: More › Market shows CANCEL under every lot and request for those who may (D507), opening the Bank & Market page's own cancel box (reason, SURE? naming the items and the seller or buyer, the answer at the top, the market read again). Checked in the browser pane on the demo panel.

Audit (an independent read of the mod and panel code; nothing serious in the rank checks, JDBC, callbacks, wording or escaping). Fixed:
- Opening another player while a read ran left the page on "Reading their bank…": a read asked during another now runs after it, for the player on screen. Checked in the pane (Vexa then Kestrel_88 at once: Kestrel_88's page).
- Opening the same player again showed the old read: it reads again on every opening (D518).
- A refund or cancel waited 6 s; on a network a slow transaction could still happen after "No answer", and a second press on a partly left row refunded twice. Now 15 s, and the page reads the player again after any answer, refusals and no-answers included. The panel log says "no answer in time: it may still have happened" instead of "refused".
- A refund or cancel now goes to the server the page read from while it can answer (row and lot ids are that server's database's), else as a read would; a server without BSP-Core 1.18 is never used.
- A payment row without the other player's name never sends "also on the other player".
- A null coin count from BSP-Core no longer stops the read's answer.
- Every refund and cancel tried is in the panel's log, early refusals and the no-power 403 included.
JUnit 88/88 twice (the bank test checks the chosen server and an early refusal logged). GameTests 183/183 twice, and twice with `-PwithCm`.

## 2026-11-18: second audit of Bank & Market, before 0.13.0 / panel 0.3.0

Two independent reads (the server side: mod and panel Java against BSP-Core 1.18's source; the pages and the docs against D502 to D523). Nothing serious in the rank and power checks, replays and late actions, JSON names, log text (reasons cleaned of the log's separator), the database column, or the snapshot. Fixed:
- A refund or cancel is refused when the server the page read from has gone, never sent to another server (without BSP-Core's network database each server numbers its bank rows from 1, so row 1234 there could be another row).
- One refund or cancel at a time from the panel's pages: while one waits, every other REFUND and CANCEL says "Waiting for another answer…". An answer is shown at once (the key no longer waits for the re-read), on the page it belongs to only; a form closed meanwhile has its answer shown at the top.
- After every read an open refund follows its row: amounts kept within what is left (FULL REFUND refilled), the form closed when nothing is left.
- A refused or unanswered cancel reads again too (the Bank & Market page and the Market page); a Market read asked during another runs after it.
- A BSP-Core error during a refund or cancel is said as "may still have happened" and logged as such, never "Nothing changed" (it may throw after the money moved).
- A read that fails after a refund keeps the refund's green answer and says the re-read failed beneath it.
- Bedrock names behind Floodgate's "." are kept; a name the log cannot show is logged as the player's UUID; an unknown player's refund and a gone server are in the panel's log.
- Wording: a deposit box's change (CONVERT with no coins) is named by its detail; the confirm line says "pieces" for the other player and "gets nothing back" for a RECEIVED payment refunded without them; a request's cancel says how many pieces go back, and names the count; coins show "−"; the part-refund bar follows pieces (or coins), not both added; "read N s ago" counts on its own; reopening a player opens LOG (D517).
- Phone: lot pictures wrap within the row; sub-lines wrap beside CANCEL; the 45 % look of a disabled key is only on these pages.
- Tests: GameTests check the read with REFRESH (the market read again first, the answer waiting for it), a BSP-Core error logged as unsure, Floodgate and unreadable names, a Moderator's cancel refused with its reason; JUnit checks a gone server refused with nothing sent and an unknown player logged.
Checked in the browser pane on the demo panel: a second refund held while one waits, a refusal shown at once with REFUND WITHOUT, an answer after CLOSE shown at the top, reopening on LOG.

Known limits (not changed): a partial refund retried after a 15 s timeout whose transaction was still running can be done twice (the answer says to check the bank first); four slow bank actions at once hold the panel's four action slots for up to 15 s; staff on a 0.12.0 client see "Refunds & cancels" entries labelled Commands.

## 2026-11-19: bounties on the staff panel, the agent (SU-74, SU-75)

Built against BSP-Core 1.19.0 (`BspBounties`, checked with javap against its `docs/API.md` "Bounties"). GameTests: 185/185 twice, and twice with `-PwithCm`. `WebBountyGameTests` (2): with a fake BSP-Core as strict as the real one: without 1.19 said plainly and the snapshot's `bounties` false; the board for a Moderator with notes, hunters, fee, bonus and a staff bounty; the history with an unknown ending passed on and no hunter as empty; the player search with who may not be a target; an error said as one; a Moderator refused an add and a remove even signed, no reason refused, a staff target stopped before BSP-Core; a Moderator with the bounty power adds, BSP-Core given exactly the target, price, bonus, window, time, notes and "Ana_Admin (panel)"; coins of a tier; bad tier, amount, target and time refused before BSP-Core; every refusal key worded; remove with and without a refund, extend, blank notes, each logged (kind "Bounties") only on `ok`; a bad id refused; a BSP-Core error said and logged as "may have happened". With the real BSP-Core 1.19: a 2,000-piece staff bounty placed on a new player, on the board with its notes and "by the server", extended by an hour, notes blanked, removed with a refund, a second remove `gone`, in the history as REMOVED by the staff member. Checked broken: letting every rank change bounties fails the Moderator assertion.

**Not tested:** anything on a network (BSP-Core's bounty transactions on MySQL and Redis, two servers at once); the panel's side and pages (not built yet; mock-up set AD, `tools/preview/staff_bounty_concepts.html`).

## 2026-11-19: the panel's side of bounties (SU-76)

JUnit 89/89 twice; GameTests 185/185 twice, and twice with `-PwithCm`. `PanelServerTest.bounties_are_read_by_everyone_and_changed_by_admins_or_the_bounty_power`: no server with BSP-Core 1.19 refused; `liveWithBounties` connected first, then the busiest, none without the snapshot's `bounties`; a Moderator reads the board (notes included), the history and the player search; a bad search refused; a Moderator without the power gets 403 for an add and an extend with nothing sent; the Owner gives the BOUNTY power (logins ended, a 48-hour login, shown on /api/me and the staff page); a missing reason, a zero amount, under an hour, a bonus window as long as the bounty and a bad target refused with nothing signed; an add and an extend sent with `bountyPower`, to the server the page read, the bounty's id kept apart from the power; a remove with a refund; a gone server refused and logged; a change of rank ends the power. GameTests: a Moderator with the power extends a bounty by its id. The staff page's switch "May add and remove bounties" and the BOUNTY badge are built, not seen in a browser yet.

## 2026-11-19: the Bounties page (SU-77)

JUnit 89/89 twice. Checked in the browser pane on the demo panel (`--demo`, `Demo.demoBounties` and `demoBountyHistory`), on a computer and at 375 px (no sideways scroll): the More entry; five posters four a row, faces, a coin bounty "3" with the diamond tier's dot and name, the bonus "+5,000 if killed within 18 h 40 min", "by the Server"; a poster's screen with every detail, hunters and notes; every key off until a reason is typed; +1 DAY on one tap staying on the bounty with the answer; REMOVE + REFUND arming "Sure? Take this bounty off the board and give Kestrel_88 25,000 pieces and the 5,000 pieces bonus back to their bank. The 1,250 fee stays in the pot. Kestrel_88, the 2 hunters and Vexa are told." then back to the board with the answer; DAYS… with its number box; HISTORY with CLAIMED, REMOVED, EXPIRED, TARGET_STAFF rows, a row opened with its notes, the Removed chip; + ADD's search dimming a staff and a protected player, coins of a tier, SURE? PLACE IT, the answer; the sheet line "Bounties: 1 on them, 1 placed, hunting 1" opening the board filtered to Vexa; a Moderator without the power: the gold line, no keys, no + ADD. **Not tested:** a real phone, real game servers' answers.

In the panel (an Owner, a Moderator account, BSP-Core 1.19.0 on the servers, a test player to target):
1. More › Bounties: the posters, newest first; chips and search narrow them.
2. + ADD: search a player, pick them, a reward and a time, a reason, PLACE IT twice within 3 s: the poster appears, the target is told in game, the Action log (kind "Bounties") names you and the reason.
3. Open it: type a reason, +1 HOUR: the end moves an hour; BLANK NOTES empties the notes; DAYS… 2, EXTEND twice: two days more (never past 30 days from now).
4. Place a bounty as a player in game, accept it as another; on the panel REMOVE + REFUND twice: the placer has the reward and bonus back (the fee stays in the pot), placer, hunter and target are told; it shows in HISTORY as REMOVED by you.
5. As a Moderator without the power: everything readable, no keys; the Owner gives the BOUNTY power: they log in again, see BOUNTY beside their rank, and may do 2 to 4.

## 2026-11-19: audit of bounties (SU-78)

Two independent reads (the server side against BSP-Core 1.19's source; the page and the docs against D524 to D542). Nothing serious in the rank and power checks, `bounty` (an id) against `bountyPower`, nulls, JSON names, JDBC indexes or routing. Fixed:
- Removing a bounty the server placed "with a refund" said the placer had the reward back; BSP-Core gives nothing back for those. The answer, the Action Log ("placed by the server: nothing to give back") and the history row say so; the SURE? said so already. GameTests: the fake gives the bounty as it was, a server bounty's removal is checked, and the real BSP-Core end to end checks the wording.
- An extend BSP-Core stops at the longest logs the time added and says it stopped; the panel's log says "asked to extend … by N h"; a server bounty's extend tells "the hunters", not a placer.
- BSP-Core failing to check a target answers at once ("Nothing was placed") instead of timing out as "may have happened".
- Notes are no longer cut at 256 on the way (BSP-Core cuts to its own `bounty.notesMax`); the add form takes 512. A GameTest sends 400.
- Page: both search boxes stay where they are drawn on a phone (`.search` has order 3 for the Players tab); the bounties line shows on an offline player's Find a player page; "… are told" names only who is there ("Vexa is told.", "the 1 hunter and Vexa are told."); HISTORY is read again after every change; every opening starts clean (chips, search, history row, add form); an answer for a bounty no longer on screen shows at the top; a failed history read keeps the last list; history wording (one hunter, a server bounty removed); a server bounty's poster shows its bonus; "under a minute", "2 diamond coins"; the add SURE? names the notes; history rows keep their dividers; chip rows keep their sideways scroll.
- Not changed: the page's 30-day limit and "at least 1,000 pieces" follow BSP-Core's defaults (`bounty.maxDays`, `bounty.minPieces`): raise those and the page refuses what the server would take; countdowns tick on the next read, not while a reason is typed; up to 40 target checks per search.

## 2026-11-19: the Map tab's player window (SU-79, staff panel 0.4.1)

JUnit 89/89. Checked in the browser pane on the demo panel 0.4.1 (`--demo` with a stand-in map folder: a `settings.json` naming the demo servers' maps and a plain page in place of BlueMap) at 1440 × 860, 1000 × 560 and 375 px: on a computer the players float over the map, top-left under the bar, 236 px wide and a third of the page high (287 of 860), see-through with the map blurred behind; dragged by the PLAYERS strip (kept inside the map); sized from the corner (236 × 287 to 375 × 516, never past the map's bottom edge or two-thirds of the page; "308 × 409" shown while sizing, gone on release); "–" folds it to the strip ("+"), a click on the folded strip opens it; a row click follows the player and the window stays open; solid while over the followed player's card, see-through once moved off; the last touched of the two on top; at 1000 × 560 a 409 px window drawn 373 px (two-thirds) and inside the map, the 409 kept for when there is room; its place, size and fold kept across a reload (`localStorage` `bsp.mapWindow`); on a phone the Players button's sheet as before, no strip or corner. **Not tested:** over the real BlueMap page (pointer drags over its frame, BlueMap's own size after the stage widened), a browser with storage refused, the "map not set up" note's new place.

In the panel (a computer, then a phone):
1. Map tab: the players window top-left under the bar, a third of the page high; the map shows through, blurred.
2. Drag it by the PLAYERS strip anywhere over the map: it never leaves the map. Drag its bottom-right corner: the size shows while dragging; it stops at a fifth and two-thirds of the page high.
3. "–": only the strip is left with the number online; click the strip: it opens again.
4. Follow a player, drag the window over their card: it turns solid; click the card: the card comes on top; move the window off: see-through again.
5. Reload the page: the window is where you left it, at the size you left it.
6. Make the browser window small: the window stays inside the map and shrinks; make it big again: back to your size.
7. On a phone: the round Players button opens the list from the bottom as before.

## 2026-11-19: the chat's width and the CHAT tab (SU-80, staff panel 0.4.1)

JUnit 89/89. Checked in the browser pane on the demo panel 0.4.1 (stand-in map folder as for SU-79) at 1440 × 860, 1100 × 760, 900 × 700 and 375 px: on a wide screen the CHAT tab sits on the chat's left edge with the dotted grip below it at two-thirds of the height; the grip dragged 144 px left made the chat 504 px (kept in `localStorage` `bsp.chat`), the label "720 px" at the half-window limit and "let go to fold" under 280 px; let go there, the chat folded into the CHAT tab on the window's edge, the map took the whole width and the 504 px was kept; CHAT brought it back at 504 px; a double-click on the grip put it back to 360 px; the same width on the Players tab, with a player's card opening 40 px clear of a 500 px chat; at 1100 px the chat closed at first, CHAT slid it over the map at 500 px with the grip on its edge, dragging under 280 px closed it; the followed player's card 36 px from the map's edge, the CHAT tab clear of it; at 900 px a 500 px chat drawn 450 px (half), 500 kept; on a phone no tab, grip or column, no sideways scroll, the Chat tab as before. **Not tested:** over the real BlueMap page; storage refused; a wide screen's fold across a reload (read by the same code as the width).

In the panel (a computer, then a phone):
1. Any tab on a wide screen: the CHAT tab on the chat's left edge, a small dotted grip below it.
2. Drag the grip left: the width shows beside the pointer; it stops at half the window. Reload: the same width, on every tab.
3. Drag the grip right past 280 px: "let go to fold"; let go: the chat folds into the CHAT tab and the map or page takes the room. Click CHAT: it comes back at the width it had.
4. Double-click the grip: back to 360 px.
5. Map tab: follow a player; their card sits clear of the CHAT tab.
6. A window between 900 and 1300 px wide: CHAT slides the chat over the page at the same width; the grip sizes it there too.
7. A phone: the Chat tab as before; nothing on the page's edge.

## 2026-11-19: audit of the Map window and the chat's width (SU-81, before staff panel 0.4.1)

One independent read of `map.js`, `app.js`, `chat.js`, `panel.css` and `app.html` against D549 to D568. The phone sheet, bad or old stored values, tab changes, CSS order and a live update during a drag were found sound. Fixed:
- On a computer the map's bar, the gold line ("… went to …", "… left the network …") and a pin's box sit above the players window (z-index 7): a bar wrapped to two rows on a narrow map, or the gold line under the window where it starts, was covered; a pin's box opened under a window last touched.
- Resizing the chat by its grip or double-click no longer draws the chat again: where it was read stays (it jumped to the newest line).
- After the browser window is resized the Map's players window is fitted again once the chat has its new width, so it never sits over the chat.
- With no map set up, the message and its Try again key sit right of the window, not under it.
- The chat's grip lies inside the chat column (it lay 4 px over the page, on its scrollbar).
- Every drag (window, corner, chat grip) ends once even if the pointer's capture is lost, and the map's frame ignores the pointer during a window drag, so a lost release cannot leave a drag running.
- Not changed: the chat's fold on a wide screen and the 900–1300 px slide-over stay two states (D566: CHAT folds on a wide screen, opens the slide-over below 1300 px); a player's card is still under the open slide-over between 900 and 1300 px, as before 0.4.1; the CHAT tab shows no unread count (the side menu's Chat does); the window's width runs 200 px to two-thirds of the map (D551's fifth taken for the height).
Checked again on the demo panel 0.4.1: the chat's scroll kept through a grip drag (same element, not redrawn), two drags in a row, the grip inside the column, the bar and gold line at z 7 and the window at 4 on a computer, the bar at 3 under the phone sheet, a window drag's `mapdrag` class on and off, the no-map message clear of the window (1300 × 800). JUnit 89/89.

## 2026-11-19: the Map tab's server picker (SU-82, bug, staff panel 0.4.1)

The owner's report on the live 0.4.0: SafeRoom's Nether chosen, then BSP-Frostwarf in the picker: Frostwarf's overworld shown with "Nether" still lit; then Nether: SafeRoom's Nether again. Cause: the picker kept the keyboard's focus after a choice, and the bar is not drawn again while the picker has it (so an open picker is not closed under the pointer), so the dimension keys stayed SafeRoom's, each opening SafeRoom's map. Fixed in `map.js`: the picker lets go of the focus once a server is chosen or left, and the bar is drawn again at once; a dimension key opens that dimension of the server shown now. Checked on the demo panel 0.4.1 with a stand-in map that has BSP-Spawn's and Frostwarf's Nether and Frostwarf's End: Spawn › Nether shows `bsp_spawn__nether`; Frostwarf picked (with the picker focused, as a click leaves it): keys Overworld, Nether, End, Overworld lit, `frostwarf__overworld`; still so after a live update; Nether: `frostwarf__nether`. **Not tested:** the real BlueMap page.

In the panel:
1. Map tab: pick a server with a Nether, press Nether; pick another server with a Nether in the picker: its overworld shows, Overworld is lit, and the keys are that server's.
2. Press Nether: that server's Nether shows (its name in the picker, Nether lit).

## 2026-11-19: which maps each server shows, the panel's side (SU-83, staff panel 0.4.2)

The Owner's setting (D570 to D586), the panel's server side; the MAPS sheet (SU-84) and the Map tab following it (SU-85) come next. New: `web/MapShown.java`; a `panel_maps` table (map, hidden, by_name, at_ms) that `JdbcStore` makes by itself on start; `GET /api/maps` (the sheet: every map BlueMap has drawn, by server in the picker's order, each shown or not, and the last change's who and when) and `POST /api/maps/set` `{maps, show, how}` (Owners only; maps BlueMap has drawn only; at most 64 at once; all saved together or none); `/map/settings.json` served with the hidden maps taken off its list (`no-store`, never served unfiltered: a list the panel cannot read is a 500), and a hidden map's files under `/map/maps/<id>/` refused as missing, its live data too; a `maps` event to every open page after a change. Every change is one line in the panel's log ("hid BSP-Spawn's Nether on the Map tab", "… (NONE)", "… (UNDO)"), and refusals and failed saves too. A database that does not answer at the start shows every map until it does.

JUnit 95/95 (new: `MapShownTest`, five; `PanelServerTest` `an_owner_chooses_the_maps_shown_…`): an Admin refused and logged; every map shown until hidden; a tap saved, logged, the pages told, a second tap of the same changing nothing and logging nothing; UNDO one line; kept across a restart; NONE hides a server's overworld too and is one line; maps not drawn or not map ids refused; a failed save changes nothing, tells nobody and is logged; the list filtered with BlueMap's other settings kept; over HTTP the sheet's names from the feed, an Admin's sheet and change refused, another staff member's open page told by a `maps` event, the hidden map off the list for Owners too, its tiles, settings and live data 404, shown again on UNDO, unreadable `settings.json` a 500. **Not tested:** `JdbcStore`'s table and its all-or-none save against the network's MySQL (no database in the tests; the table is made on the first start of 0.4.2); nothing to see in the panel's pages yet.

## 2026-11-19: the Owner's MAPS sheet (SU-84, staff panel 0.4.2)

Look A of set AF (D578 to D582, D585). `map.js`: a violet **MAPS** key on the Map tab's bar for Owners only; the sheet over the map's right side (390 px, 340 px from 900 to 1299 px, from the bottom on a phone, its list scrolling), every map BlueMap has drawn by server in the picker's order with "n of m", ALL and NONE, a chip per map (lit teal shown, struck through hidden, ringed violet while saving); a tap saves at once and the line under the list says "Saved: … hidden." with UNDO for that change until the next or the sheet closes; a refused or failed save leaves the chip as it was, turns the line red and reads the sheet again; the foot "Last change: <name>, <time> · BSP-Map still draws hidden maps."; ✕, Esc or MAPS again closes it, and leaving the tab closes it. With every map hidden the Map tab says "No maps are shown." and, to Owners, "Choose them under MAPS" with the key in the message (the panel now adds `bspHidden`, the number left out, to the map list so this is told from a map not drawn yet). `panel.css`: `.mkey`, `.msheet` and its parts; the bar stops short of the open sheet; the sheet's right padding clears the CHAT tab. `MapShown`: servers sorted as the picker's `localeCompare` (a Collator: "Boulderbrook" before "BSP-Spawn"; found on the demo).

JUnit 95/95. Checked on the demo panel 0.4.2 with the stand-in map folder: at 1440 × 860 the key on the bar, the sheet beside the chat, the server order as the picker; Frostwarf's End tapped: "Saved: Frostwarf's End hidden. UNDO", the chip struck through, the focus kept on it, `/map/settings.json` without it (`bspHidden` 1), the foot naming Demo_Owner; UNDO: shown again, no UNDO after; BSP-Spawn's NONE: "every map of BSP-Spawn hidden", "0 of 2", NONE greyed; every map hidden: "No maps are shown. Choose them under MAPS." with the key opening the sheet; an Admin (rank changed in the page): the message without the second sentence and no key; a save refused (answer stubbed in the page): the chip unchanged, the red line; Esc and ✕ close, the key's light follows; at 1100 × 760 the 340 px sheet with the CHAT tab over its padding; at 375 px the sheet from the bottom above the tab bar, no sideways scroll. **Not tested:** the Map tab following a change (the picker, keys and frame read their list once; SU-85); the real BlueMap page; the network's MySQL.

In the panel, as an Owner (a computer, then a phone):
1. Map tab: **MAPS** on the bar (violet). Click it: the sheet lists every server and its maps.
2. Tap a map: it is struck through and the line says it was saved, with UNDO. UNDO: back as it was.
3. NONE on a server: all its maps struck through, "0 of n"; ALL: all back.
4. Esc closes the sheet; the foot names the last change.
5. As a Moderator or Admin: no MAPS key.

## 2026-11-19: the Map tab following the maps shown (SU-85, staff panel 0.4.2)

D572, D576, D577, D583, D584. `map.js`: the map list read again on the live `maps` event and after the live feed reconnects (`MapView.mapsChanged`, `app.js`), or when the tab next opens if it was closed; a map gone from the list is left for that server's Overworld, else the first server's first shown map, with the gold line "That map was hidden by an Owner."; for the Owner who hid it from this page's sheet, said in the saved line instead ("You were on it, so the map moved to …"), held until that line exists when the event comes first; a map back on the list reloads BlueMap's page in the frame (it knows only the maps it started with); every map hidden: "No maps are shown." in place of the frame, and back when one is shown; an open sheet read again on another Owner's change; the last map looked at kept in this browser (`bsp.mapLast`) and opened next time; the picker keeps the dimension when the chosen server shows it, else its Overworld; the gold lines for a followed player say "no map shown" (hidden or not drawn). Names and order (`serverName`, `serversInOrder`, `dimRank`) shared by the bar, the moves and the sheet.

JUnit 95/95. Checked on the demo panel 0.4.2 with the stand-in map folder, two pages of the demo Owner: BSP-Spawn › Nether, then Frostwarf in the picker: Frostwarf's Nether; Moongrove (no Nether): its Overworld; Frostwarf › End, page reloaded: the Map tab opened on Frostwarf's End; End hidden from that page's sheet: moved to Frostwarf's Overworld, the saved line "… You were on it, so the map moved to Frostwarf's Overworld.", no gold line, keys Overworld, Nether; UNDO: BlueMap's frame reloaded, keys with End again; the other page on Frostwarf's End when it was hidden elsewhere: moved to the Overworld with "That map was hidden by an Owner."; the first page's open sheet showed End hidden; every map hidden: both pages "No maps are shown. Choose them under MAPS.", the frame gone; two maps shown again: the frame back, the picker with those servers; Glimmer followed on BSP-Spawn's hidden Overworld: "There is no map of Overworld shown on BSP-Spawn." No console errors. **Not tested:** over the real BlueMap page (the stand-in has no `window.bluemap`, so switches went through the frame's address); a lost live feed's catch-up; the network's MySQL.

In the panel (two browsers or a computer and a phone, one an Owner):
1. On the second, open the Map tab on a server's Nether. On the first, hide that Nether under MAPS: the second moves to that server's Overworld with the gold line.
2. On the first, hide the map you are on: the map moves, and the saved line says so.
3. UNDO: the map is back on the keys of both.
4. Choose a server's Nether, then another server with a Nether in the picker: still the Nether. A server without one: its Overworld.
5. Reload: the Map tab opens on the map you looked at last.

## 2026-11-19: audit of the maps shown setting (SU-86, before staff panel 0.4.2)

One independent read of `MapShown`, the new parts of `PanelServer`, the stores, `map.js`, `app.js` and `panel.css` against D570 to D586. Fixed:
- `/map/` refused any `.` segment: `/map/./maps/<hidden>/…`, `/map/maps/./<hidden>/…` reached a hidden map's files, and `/map/./settings.json` the unfiltered list (the path was opened tidied but checked untidied; a browser tidies it, curl need not). A hidden map's id is matched in any case (a host whose files ignore case).
- `MapShown`: saves one at a time against the kept choices (two Owners at once could lose one change from the panel's memory until a restart; the first read racing a save likewise); a save changing nothing writes nothing (it rewrote who and when, so the sheet's foot and the log disagreed); a change of several saves only the maps it changes; a database read that fails is asked again after 30 s, not on every tile; no change is saved over choices it could not read.
- `POST /api/maps/set` without `show` is refused, never read as "hide". The `panel_maps.map` column holds the longest id (170).
- The Map tab: a move is the Owner's own only when the map moved off is one their save changed (another Owner's change during it gets the gold line); the move is told by the gold line when the sheet was closed during the save or the save's answer was lost though it was saved; a list that could not be read is read again after 5 s, then less often, up to a minute; a read when the tab opens no longer overwrites a newer one; one save at a time from a page, the sheet closed and opened again too.
- The sheet: Esc in the players search or the picker is left to them; Esc with the map clicked closes the sheet too; ✕ gives the focus back to MAPS. On a computer the followed player's card sits beside the open sheet (unless dragged), the gold line is narrowed beside it and placed under the bar however many rows it wraps to.
- The picker: a server without a shown Overworld opens on its first map in the keys' order.
- Not changed, said to the owner: showing a map again reloads BlueMap's page for everyone on the Map tab (the view goes back to the map's start); the players window or a pin's box moved to the right is under the open sheet; a follower moved off a hidden map gets "That map was hidden by an Owner." and, at the next update, "… which has no map shown"; a refused try by a non-Owner is logged each time (as the bank's are); the log line is written after the save (as every panel action's is).

JUnit 97/97 (new: a no-op tap keeps the last change, a change of several saves only its changes, two Owners at once both kept (20 rounds), a database down then back (all shown, not asked per tile, nothing saved, read after 30 s); over HTTP `.` segments refused for a hidden map's tile, the list and `index.html`, a set without `show` refused; hidden paths in any case, heads and pins). Checked on the demo panel 0.4.2 at 1300 × 800: following BrickLayerTom with the sheet open, the card right of the sheet; Esc in the players search left the sheet open; Frostwarf's Overworld hidden from the sheet and the sheet closed before the answer: moved to Boulderbrook's Overworld (Frostwarf had no other shown Overworld) with the gold line, the focus on MAPS; the gold line under a bar wrapped to four rows beside the sheet. **Not tested:** over the real BlueMap page; the network's MySQL.

## 2026-11-20: Support BSP settings (SU-89)

The server side of Support BSP (D596, D597, D602 to D606, D612). `support/SupportLinks` (the link check: https only, a host name of letters, digits and hyphens with a dot, no spaces, no name and password, at most 256 characters; empty means no link), `SupportSettings` (the support link, the chat sentence at most 200 characters with formatting codes not read, the chat line's own link, who and when, a revision; a refused link is never kept, even from the shared copy), `SupportData` (SavedData `bsp_server_utilities_support`), `SupportService` (edit, one field, clear; refused unless operator level 2 or a BSP-Core admin; a refused link saves nothing; the same settings again change nothing; each change in the Action Log; the newest revision wins across servers; the support link sent to every client at login and when it changes), `SupportCommands` (`/su support`, `link`, `message`, `chatlink`, `none`, `clear`, the `/su status` line). Table `<prefix>support_settings` (one row, a late older write never replaces a newer row), notice `support_settings`, packet `SupportLinkPacket`; protocol 9.

GameTests (`SupportGameTests`, 6): the link check (25 addresses), only operators and admins change it and each change is logged, a refused link saves nothing, CLEAR, one setting for the network with the newest winning (two servers on one store), `/su support` from the console. **Not tested:** against the network's real MySQL and Redis; anything in a client (the key, the popup and the SUPPORT tab are SU-91 and SU-92).

On a server, as an operator:
1. `/su support`: "Support link: none", "Chat line on joining the network: none", "Chat line's link: the support link".
2. `/su support link http://example.com`: refused, "Only https:// links are allowed." Nothing changes in `/su support`.
3. `/su support link https://<your store>`: "Support BSP saved for every server of the network." On another server of the network, `/su support` shows the same link within a few seconds.
4. `/actionlog`: one line "set the support link to …" under your name (not a second line for the command).
5. As a player who is not an operator: `/su support` is not offered.
6. `/su status`: "Support BSP: link set; chat line on joining the network not set."

## 2026-11-20: the Support BSP line on joining the network (SU-90)

D597 to D601. `support/NetworkJoins`: at a login, the time and whether another server still lists the player (`ClientCheckService.listedElsewhere`: the client check's network list, servers quiet for 90 s left out); a login is a **move** when the player was listed elsewhere then, or left any server of the network (this one too) from 60 s before the login up to when the line is due; every server tells the others of a leaving with the notice `support_left`, so it works with the client check off; kept in memory (a server just started knows no earlier leavings). `support/SupportChat`: the line 5 s after the login, decided then (on Velocity the old server lets go a moment after the new login); a player at the rules' gate or held waits until 5 s after they are let through; dropped if they leave first; the admin's sentence in gold, the address in aqua, underlined, `OPEN_URL` (Minecraft's own prompt and the player's chat-link settings), every `{link}` filled, without one the address after the sentence; nothing without a sentence or an address; everyone, staff too.

GameTests (`SupportJoinGameTests`, 5): a join gets the line at 5 s (not at 4.999 s), once, its parts styled; a move gets none (listed elsewhere, a leaving notice from another server after the login, a leaving 30 s before), a leaving 61 s before is a join; a quick reconnect and leaving early; the rules hold and 5 s after AGREE; the line's text without a sentence, with only the chat link, with two `{link}`. **Not tested:** a real move through Velocity (the order and timing of the two servers' login and logout), Redis delivery of `support_left` (the tests use the notices table), the line in a client's chat.

On the network, as a player (after `/su support link …` and `/su support message Enjoying BSP? Help keep it running: {link}`):
1. Join the network: about 5 s after arriving (after the "Earlier chat" replay), the gold line with the address underlined. Click it: Minecraft asks whether to open the link.
2. Move to another server (a warp or `/server`): no line there.
3. Leave the game and join again after a minute or more: the line again. Join again within a minute: no line.
4. As a new player who has to agree to the rules: no line while the rules are on screen; it comes about 5 s after AGREE.
5. `/su support message none`: the next join has no line.

## 2026-11-20: the SUPPORT BSP key and the /support popup (SU-91)

D587 to D595, D607, D608, D610, D611. `client/SupportButton`: the key on the survival and creative inventory (`ScreenEvent.Init.Post`), 62 × 14, TEAM's look with a pink ❤ before "SUPPORT BSP" at 0.75 scale, edge and words teal under the mouse, tooltip "Support the server: opens the BSP support page"; placed each frame by `support/CornerPlace` from two pixels right of BSP-Core's rightmost corner key on its row (found by class name; the window's corner without BSP-Core), stepping past every other visible widget except Region Guard's BANNED (which steps past this key, so it stands to its right), the panel with 32 px above and below, and the open recipe book (147 × 166, computed as vanilla places it); down a row past the left half; not drawn when no place is free; hidden while there is no support link or with `supportButton = false` in the new client config `config/bsp_server_utilities-client.toml`. `client/SupportScreen`: the gold popup (220 GUI px, shrinks on a small window), the heart, the gold title, the thanks line, the address cut in the middle with the whole as a tooltip, CLOSE / COPY LINK / OPEN (gold); OPEN opens the browser (`Util.getPlatform().openUri`) and closes; COPY LINK and "Copied." for 3 s; Esc or CLOSE back to the inventory or the game; the address checked again and never opened unless https. `/support` for every player (`SupportCommands.registerPlayer`): `SupportOpenPacket` with the link, or the grey line "No support page is set up yet."; from the console it prints the link.

GameTests (`SupportPopupGameTests`, 2): `/support` with and without a link; the key's place (right of KITS, the corner without BSP-Core, past another button, down a row on a narrow window, a key that just fits, nowhere when nothing is free, under something tall). **Not tested (no client run):** the key drawn on real screens and GUI scales, its tooltip, BANNED beside it, the creative inventory, the recipe book, the popup's look, the browser opening, the clipboard. JEI and EMI (SU-91b, D592): `compat/jei/SupportJeiPlugin` (`@JeiPlugin`, a global GUI handler's extra areas) and `compat/emi/SupportEmiPlugin` (`@EmiEntrypoint`, a generic exclusion area) report `SupportButton.area(screen)` while the key is drawn; `build.gradle` compiles against JEI 15.20.0.112's and EMI 1.1.22's APIs only (as BSP-Core), nothing of them shipped or loaded in the GameTests. **Not tested:** JEI's and EMI's panels leaving the key free (no client run with them).

In game (a player, after `/su support link https://<your store>`):
1. Open the inventory: SUPPORT BSP with a pink heart right of KITS; the tooltip under the mouse. The same in the creative inventory.
2. Stand where Region Guard bans something: BANNED appears right of SUPPORT BSP, which does not move.
3. Open the recipe book, then make the window small: the key never sits on the book, the panel or another button; on a tiny window it moves down a row or hides.
4. Click it: the gold popup. COPY LINK: "Copied." and the address pasted elsewhere is right. OPEN: the browser opens the page and the popup closes back to the inventory. Esc: back to the inventory.
5. `/support` in chat: the same popup over the game; Esc back to the game.
6. `/su support clear`: the key disappears from an open inventory at once; `/support` says "No support page is set up yet."
7. `config/bsp_server_utilities-client.toml`, `supportButton = false`: no key (after reopening the inventory).
8. With JEI's bookmarks shown (and again with EMI's panels): the bookmark column starts below the corner row; nothing of JEI or EMI is drawn over SUPPORT BSP.

## 2026-11-20: the SUPPORT tab (SU-92)

D605, D609, D612; look A of set AI. `client/SupportTab`, the eleventh tab of `/su` (`AdminScreen`: `SUPPORT = 10`, keys still at least 22 px, the title shrinking to fit): on the left the support link (an `EditBox`, 256), the sentence (a `MultiLineEditBox`, 200, with "n / 200" and the `{link}` hint) and the chat line's link, each link with a line under it as typed (green "A link players can open.", red with the reason, or what empty means); on the right the key as the inventory draws it (beside TEAM and KITS, `SupportButton.drawKey`) and the chat line as `SupportChat.line` builds it, in chat colours, both following the boxes before SAVE; an amber "The saved settings stay in use until SAVE." while they differ; "Last change: name, time". SAVE (violet; dim while a link is refused, nothing changed or no data yet) sends all three; UNDO (when changed) puts the boxes back; CLEAR (red; dim when nothing is set) then SURE? within 3 s with the red warning line. The server's answer shows for 3 s at the foot ("Saved for every server of the network.", "Cleared: …", "Nothing changed.", or "Not saved. The support link: <reason>"). The tab asks for the data every 5 s; another admin's change replaces the boxes unless they hold unsaved typing. Server side `support/SupportAdmin` (refused outright without `Perms.canEdit`), `AdminSupportPacket`, `AdminSupportEditPacket` (boxes cut at 768 characters before sending; the server checks every limit).

GameTests (`SupportTabGameTests`, 2): a player who may not edit gets nothing; REFRESH, SAVE (who changed it), a refused support link and a refused chat link (the reason, nothing saved), unchanged, CLEAR; both packets' round trip. **Not tested (no client run):** the tab's look and fit at the panel's size, the boxes, the live check lines, the previews, UNDO, SURE?, the eleven tab keys beside the title.

In game, as an operator, `/su` → SUPPORT:
1. The eleventh key, SUPPORT, fits beside the title; the tab shows the three boxes and the previews.
2. Type `http://example.com` in the support link: the red line "Only https:// links are allowed.", SAVE dim. Make it `https://…`: the green line, the key appears in the preview.
3. Type a sentence with `{link}`: the chat preview shows it in gold with the address underlined; the count follows.
4. SAVE: "Saved for every server of the network."; "Last change" names you; `/su support` shows the same.
5. Change a box, then UNDO: back to what is saved.
6. CLEAR: SURE? and the red warning; CLEAR again within 3 s: everything empty, "Cleared: …"; the key leaves an open inventory.
7. On another server, open the tab: the same settings.

## 2026-11-20: audit of Support BSP (SU-93)

An independent read-only audit of SU-89 to SU-92 (given the code and the decisions only). No security finding: links (`javascript:`, `file:`, `http:`, user@host) are refused on the server and checked again on the client before `openUri`; every edit path needs `Perms.canEdit`; packet limits hold; nothing client-only is reachable on a server. Fixed:
1. **A move taken for a join without Redis** (the old server's `support_left` read from the table after the 5 s decision): the line now waits 5 s plus `Storage.noticeLagMs()` (0 with Redis subscribed, else the table's poll + 2 s) before deciding.
2. **A join followed at once by a move (or a move while still at the rules) never got the line**: a player who leaves before their line is sent is "owed" it (`support_left` carries `1`), and the next server they reach within a minute sends it 5 s after arriving, though that was a move. Once sent, nothing is owed.
3. **The world's settings were read on the storage worker** at start: now read on the server thread before the worker runs.
4. **Servers could keep different settings** after a save that reached the others by Redis but not the table: a server starting with a newer copy than the table's writes it back and tells the others.
5. **The `/su` title shrank to an unreadable 0.31** with eleven tabs (and was measured without bold): measured bold, and "SU" instead when the full title would be smaller than 0.55.
6. **The key could sit on the open recipe book's tab column** (drawn by the book, not a widget): the column (32 px left of the book) is kept clear too.
7. **The key flickered on the inventory's first frame** at (2, 2), and could change TEAM's and KITS' order when they wrap: it starts hidden until placed.
Also: zero-width and direction-mark characters are refused in links (they could disguise an address); the SUPPORT tab's 5-second refresh no longer moves the cursor, and a SAVE's answer no longer overwrites typing done after SAVE; a second `/support` replaces the popup instead of stacking.

Left, as they are: a command block at level 2 can run `/su support …` and is logged as "Console"; `§6` in a sentence becomes a space and a 6; a sentence over 200 from `/su support message` is cut without a word; Enter in the sentence box becomes a space; the address tooltip on a shrunken popup can sit near the screen's edge; `/execute … run su support …` is logged twice; `ClientCheckService.listedElsewhere` itself is not GameTested (the join tests stub it); the MySQL upsert is untested against a real MySQL.

GameTests: `SupportJoinGameTests` now 7 (a quick reconnect after the line; leaving before the line, on to another server through the notice, and while at the rules; once sent, nothing owed; a 7 s notice lag with a move and a join), `SupportGameTests` checks the write-back of a newer copy and two invisible-character links.

In game, on top of the earlier checks: with Redis off on a test pair of servers, move between them several times: no line on any move; join the network and move on within 3 s: the line comes on the second server.

## 2026-11-20: audit of 0.15.0 (SU-94)

Two independent read-only audits of the 0.15.0 commit (server side, client side; given the code and the decisions only). No security finding, no crash, nothing client-only reachable on a server. Fixed:
1. **A stack on the cursor put down by the popup's CLOSE or OPEN**: the popup acted on the press and gave the release to the inventory underneath. Its keys now act when the button comes up over the key it went down on.
2. **Players moved off a stopping server got the line again** (and a line still owed there was lost): the storage closes before Minecraft logs the players out, so their leavings never went out. `SupportChat.onServerStopping` tells the network of everyone still online, with any owed line, just before the storage closes.
3. **BANNED took SUPPORT BSP's place on some windows** (1366 × 768 at GUI scale 3: SUPPORT BSP down a row, BANNED after KITS): Region Guard has to start BANNED after SUPPORT BSP too; request **SU-RG1** in the vault's Requests for Other Mods. Until Region Guard does, the order holds only on windows where SUPPORT BSP fits on the first row.
4. **The mouse wheel over the SUPPORT tab's sentence box scrolled the hidden rules list**: it goes to the box.
5. **The 5-second refresh removed a typed space or line break** (the tidied text equalled the saved one): a box whose tidied text equals the saved text keeps its text and cursor. The first answer always fills the boxes.
6. **A server waited too little for another's notices when only that other server's Redis was down**: a notice that reaches this server only through the table while Redis is subscribed makes it wait the safety poll for 10 minutes (`Storage.noticeLagMs`).
7. **A leaving read late from the table counted as new**: it is dated when it happened (the notice's age), so a player who left minutes ago is not a quick reconnect.
8. **The popup's keys did nothing from the keyboard**: Enter or Space opens the page, C copies it.
Also: an owed line stays owed until a server sends it (`support_sent` tells the others), never cancelled by a later leaving, and is kept 30 minutes for a long read of the rules; a failed write of the settings to the table is tried again every minute until it lands; zero-width and direction marks are taken out of the sentence; `/su support` from a command block or `/execute as <entity>` is logged under that source's name, not "Console"; the popup settles its height before drawing (no frame at a mixed scale); with the recipe book open on a window narrower than 379 the key counts as hidden (vanilla draws no widgets then), for JEI, EMI and the other corner keys.

Left: two saves on different servers in the same millisecond; the Forge creative page label (BSP-Core has the same); `/execute … run su support …` by an operator is logged twice (once as the command).

GameTests: 6 new or changed (`SupportJoinGameTests`: a stopping server tells the network, a late notice dated by its age, an owed line kept until sent and cleared by another server's `support_sent`; `SupportGameTests`: the start-up check that had no assertion, the table write retried, the sentence's invisible marks). The client fixes (1, 4, 5, 8, the popup's height, the narrow book) are not testable on the GameTest server.

In game, on top of the earlier checks:
1. Hold a stack on the cursor in the inventory, click SUPPORT BSP, then CLOSE: the stack is still on the cursor, nothing changed in the slots. Again with OPEN.
2. In the popup: Enter opens the page; C shows "Copied.".
3. SUPPORT tab: a 200-character sentence scrolls with the wheel anywhere over its box; type a space at the end of a saved sentence and wait 10 s: the space and the cursor stay.
4. With players on a server, stop it while Velocity sends them to another: no Support BSP line there.

## 2026-11-21: totem steals recorded for the staff panel (SU-95)

Each game server writes every Shatter Totem steal that ends there, completed or failed, to `<prefix>steals` (docs/API.md "Totem steals"), for the staff panel's TOTEMS page (D613 to D622). Only with BSP-Core and shared storage. Nothing on the panel shows it yet (SU-97, SU-98).

GameTests (`StealGameTests`, 7): a completed steal, posted as BSP-Core's own events, is one row with the server, dimension, block, thief, owner, tier, points, start, end and the seconds BSP-Core gave; a failed steal keeps its reason and start; another thief's start is never used for an end (start 0, seconds 0); a Totem Forge's pour is `forge`, tier 1; an unclaimed totem has no owner, and long names and reasons are cut to the columns; an empty block is `other`, tier 0, never guessed; nothing is kept without shared storage; a failed write waits and is written on the next minute's try; rows older than 90 days go on the first tick after a start; the real lookup reads a placed Tier III totem's tier and BSP-Core's points for it.

Not covered by the GameTests: the MySQL table itself (the memory table plays it); a steal against a real owner with BSP-Core's ledger; BSP-Core's Totem Forge block entity (only the lookup's answer is stubbed).

On the network, after installing the mod (both servers of a pair with BSP-Core and shared storage):
1. Start the server: its log has no storage warning; in MySQL, `SHOW CREATE TABLE bsp_su_steals;` shows the table.
2. Steal a test player's totem on one server and let it finish: `SELECT server, dimension, x, y, z, thief_name, owner_name, tier, points, started_ms, ended_ms, expected_s, success FROM bsp_su_steals ORDER BY id DESC LIMIT 5;` shows one row with that server, the world, the totem's block and `success` 1; `ended_ms - started_ms` is about `expected_s` seconds.
3. Start a steal and walk away so it fails: a second row with `success` 0 and a `reason` key.
4. Steal on the second server: its row names that server; no steal appears twice.
5. Steal from a Totem Forge: `source` is `forge`, `tier` 1.

## 2026-11-21: the board and live steals for the staff panel (SU-96)

The panel's TOTEMS page asks a game server for BSP-Core's board and the steals running (`totem_board`), and hears each steal that starts or ends at once (kind `steal` on the web channel); docs/API.md. Nothing on the panel shows it yet (SU-97, SU-98).

GameTests (`WebTotemsGameTests`, 3): without BSP-Core the answer says so and the snapshot's `totems` is false; with it every panel rank reads the board (each line's id, name, rank, points, totems held and best tier) and the steals running (server, world, block, thief, owner, how much is left), an unclaimed totem with an empty owner; BSP-Core failing is said as such; a steal started and ended through BSP-Core's own events is told twice, both signed with the action key, the end with its row's uid, tier, points, start, end and seconds; with the panel off on the server nothing is told and the row is still written; the real BSP-Core 1.19 board answers through the adapter.

Not covered: Redis itself (the messages are caught before `publishWeb`); a board of real players; another server's steal in `stealing` (BSP-Core's own estimate).

On the network, after installing the mod, with the panel on (until SU-97 the panel ignores these; check on Redis):
1. `redis-cli SUBSCRIBE bsp_su:<channel>:web` on mc-main, then start a steal on a game server: a message of kind `steal` with `"event":"start"`; let it finish: `"event":"end"` with `"success":true`.
2. Walk out of range so a steal fails: `"success":false` and a `reason`.

## 2026-11-21: the TOTEMS page's panel side (SU-97)

The panel reads the board every 30 s from a server whose snapshot says `totems`, keeps every change of points in `panel_points` (90 days), reads the steals from `bsp_su_steals`, hears steals live, and answers `/api/totems` and `/api/totems/player` (D613 to D622). The page itself is SU-98.

JUnit (`TotemsTest`, 5, and `PanelServerTest.the_totems_page_is_read_by_every_rank_and_by_nobody_logged_out`): no server with the board is said and nothing is asked; the read goes to the able server; the first sighting of each player is marked first (never a gain); no change, no row; a gain and a player gone from a whole board (at 0); nothing recorded twice after a restart; a cut board (over 1,000 lines) never sets the missing to 0; the window's points gained on the board; live steals dropped unsigned, named by another server or old, taken once, a quarter done after 10 of 40 s in "stealing now", gone at the end, the end flagged fast; fast only past the 2 s margin and never without a start; a burst of three in 10 minutes; the time since the player's previous steal; a failed steal in words; the thieves in order, flagged first, with their counts; the grant missing said with where to look; one player's steals and points line, oldest first; a window not offered is a week; a Moderator reads the page, nobody logged out. **103/103** panel tests.

Not covered: MySQL itself (`JdbcStore`'s new queries and `panel_points` run only against the memory store); the real Redis; two panels at once.

On the network (after installing mod 0.16.0 and panel 0.5.0, SU-99):
1. In MariaDB as root: `GRANT SELECT ON bsp.bsp_su_steals TO 'bsp_panel'@'<host>';` (the same `<host>` as the other grants; part 5 of docs/STAFF_PANEL_SETUP.md).
2. The panel's console prints no `[panel] the TOTEMS board:` line within a minute of a game server sending (one line when none can answer).
3. `SELECT COUNT(*) FROM bsp_panel.bsp_su_panel_points;` (the panel's own database and prefix) grows after the first board read, then only when points change.

## 2026-11-21: audit of the TOTEMS work (SU-98)

Two independent read-only audits of SU-95 to SU-97 (the mod side, the panel side); every finding checked against the code before fixing. Fixed:

Mod:
1. **The database down at the start stopped steal rows for the whole run**: the write batch marked itself queued and the mark was cleared only inside a task that never ran. `Storage.submit(queued, task)` clears it as the task's turn comes, whether or not the database answers; the web chat's batch had the same fault and uses it too.
2. **A failure in the recording could break a steal**: Forge hands a listener's throw back to BSP-Core, which posts the end before giving the thief the totem. Both listeners catch everything.
3. **Steal rows unsigned**: each row carries `line`, its end message signed with the action key; the panel shows only that.
4. The end news is sent only for a row that will be in the table; while writes fail only the minute's retry tries; a null line or thief from BSP-Core is left out instead of failing the board; this server's own running steals are named by this mod's server name; the board says `ready`.
5. Tests restore the shared storage as they found it; the tier test expects 4 for Tier III (the test world's `tierPoints`), not BSP-Core's own answer; posting real steal events is safe because the test world's BSP-Core storage is local (said in the test).

Panel:
6. **An empty board zeroed everyone** (BSP-Core's board is empty until its first refresh): a server not `ready` is passed over, and an empty board while points were held never records anyone at 0.
7. The same server while it answers (switching recorded changes that never were); the next able one (up to three, those that can answer) when it cannot.
8. A points save is all or none, and after a failed one the panel reads where everyone stood from the table again: never counted twice. Gained and lost summed by the database (no 20,000-row cut).
9. Rows shown only through their signed copies; the rest counted (`unverified`).
10. A start whose end was missed is dropped 10 minutes after BSP-Core's time ran out, or when a board read over a minute after it does not list it; the board and its time (taken before asking) are one; memory trimmed on every read.
11. Bursts and gaps look back a day before the window; the page's answer kept 5 s and made once for all open pages; database errors on the console, plain words on the page.

Left: an operator's finish on BSP-Core's admin screen still looks fast (request SU-BC4 (3)); `JdbcStore`'s new SQL (the all-or-none save, the totals, `lastPoints`) runs only against the memory store in tests; another server's running steal is named by BSP-Core's server name, which matches this mod's unless a server sets this mod's own `storage.serverId` differently.

GameTests: **218/218** (3 new: the database down at the start, a failing lookup never reaching BSP-Core's steal, each row's signed copy; the board test also checks `ready`, the bad entries and the local name). JUnit: **108/108** (`TotemsTest` 10: also an empty, cut or unready board, the same server kept and the next tried, a failed save, a missed end, a burst across the window's start, rows only through their signed copies; `PanelServerTest` also an Admin).

## 2026-11-21: the TOTEMS tab (SU-99)

The panel's TOTEMS tab (`static/totems.js`, its part of `panel.css`, the tab in `app.js`, `MapView.showSpot` in `map.js`), as the refined page `tools/preview/staff_totems_refined.html` (D623 to D629).

Checked in the browser pane on `java -jar staff-panel/build/libs/bsp-staff-panel-0.5.0.jar --demo 8205`, at a computer's width and at 375 px:
- TOTEMS between Map and Staff (six tabs fit on a phone).
- Stealing now: a live steal counting down each second, and the board's running one shown as "62 % left".
- The flagged cards: Vexa FAST ×2 and BURST, Pixl BURST, with fastest, BSP-Core's time, min gap and last.
- The board: gains in teal, losses in coral, flag counts; totems and steals as columns on a computer only.
- The steals: chips with counts, search, a FAILED one dimmed with its reason, FORGE, "Yest." and dated times, and "after their last".
- Vexa opened under her board row: the points line with the red and gold dots, the FAST warning, her steals, ALL STEALS, NOTES (her notes in place) and PLAYER ›.
- MAP › with no map shown: a line at the foot of the screen for 6 s, staying on TOTEMS.
- No console errors.

JUnit: `PanelServerTest.the_totems_page_is_read_by_every_rank_and_by_nobody_logged_out` also checks that `/static/totems.js` is served and loaded, and sets no markup. **108/108.**

Not tested: MAP › onto a real BlueMap map (centring and the 30 s pin; the demo has no map here); the tab with the real network's live `steal` messages and board; a steal lit for 10 s as it arrives (the demo's live end was not watched); the window kept after a reload.

On the network (after installing mod 0.16.0, panel 0.5.0 and the grant, SU-100):
1. Open TOTEMS: "board read … s ago from <server>" within a minute; the board matches BSP-Core's Score Screens.
2. Start a steal on a test totem: it appears under Stealing now and counts down; let it finish: it leaves, and the steal appears at the top of the list, lit, with the right server, world, coordinates and "0:40 of 0:40" (or that totem's time).
3. MAP › on that steal: the Map tab opens on that server and world, centred on the totem, with a pin that goes after 30 s.
4. A steal that fails (walk away): dimmed, FAILED, "Left the totem for too long".

## 2026-11-21: audit of the TOTEMS tab and release 0.16.0 / 0.5.0 (SU-100)

An independent read-only audit of SU-99 (the tab, `map.js`'s place, `app.js`, the styles, the demo). No markup injection: network text goes in as text, BlueMap's label is escaped, every new style is prefixed. Fixed:
1. **Redraws under a press or typing.** The tab is drawn again after an answer only once a press has ended and nothing in it is being typed in. Typing in the search box redraws only the list under it. The panel sends `board` only when the board, the running steals or the problem changed, not every 30 s.
2. **The last window's numbers under the new window's words** while reading: "Reading…" until the answer for the window shown.
3. **MAP › after leaving the tab** no longer pulls staff to the Map tab. An unreadable map list is said as such, not "no map shown".
4. **The map's place** is tried until BlueMap knows the map (up to 15 s), then dropped: never on a later load. It is cleared when the tab is left. It is centred again once BlueMap settles. Its pin goes when staff switch to another map.
5. The chip picked is outlined (it was white on teal). Times are 24-hour on every browser. Stealing now shows the tier or FORGE (the start message carries `source` and `tier`) and "finishing…" at 0. ALL STEALS lists that player's steals by id, with an "Only …'s steals ✕" chip. A player's line starts where their points stood before the window. Lit steals are forgotten. The foot line is made hidden when the tab opens and hidden when it is left. Flag counts have a title. A burst is a diamond on the chart. MAP › and names are bigger to tap and keep focus. Countdowns use the panel's clock.

Left: the console's connection errors after a demo restart are the old page's live feed retrying (not the tab); `lastPoints()` is read once per player opened (a whole-table query; fine for a handful of staff).

Tests: GameTests **218/218** (`WebTotemsGameTests` also checks the start's tier and source). JUnit **110/110** (`TotemsTest` 12: the board event only on a change, a running steal's tier, a line's start before the window). Checked again on the 0.5.0 demo: the chip, ALL STEALS by id, no console errors from the tab.

Release: mod **0.16.0** (`gradle.properties`; root `CHANGELOG.md` `[0.16.0]`; protocol still 9), panel **0.5.0** (`staff-panel/CHANGELOG.md` `[0.5.0]`). Jars `build/libs/bsp-server-utilities-0.16.0.jar` (reference map complete) and `staff-panel/build/libs/bsp-staff-panel-0.5.0.jar`; `bsp-staff-panel-0.4.2.jar` unchanged. `docs/STAFF_PANEL_SETUP.md` (and its User Notes copy): the jar table, the upload step, protocol 9, and Running it › Updating to panel 0.5.0 with the grant.

## 2026-11-21: second audit of the TOTEMS work (SU-101)

Two fresh independent read-only audits of everything in 0.16.0 / 0.5.0 (the mod's recording and answers, the panel's desk and tab). Fixed:
1. **FAST judged on the clock.** Minecraft catches up a tick backlog by running ticks back to back (lag spikes within 15 s of its last warning), so an honest steal could end more than 2 s early by the clock. Each row now carries `ticks` (the server ticks it took), and FAST is `ticks < expected_s * 20 - 40`. The time shown is the ticks' too.
2. **A player's row picked by an unsigned column.** Rows are shown only when their signed copy is of the same uid, thief and end as the columns. A uid seen twice is counted, not shown. Rows dated more than a day ahead are not read.
3. **A Forge pour, or another server's steal, left "stealing now" after a minute.** Running steals are matched by world, block and thief (BSP-Core and this mod may name a server differently). A forge pour, never on the board, goes only when its time runs out.
4. Mod: the events' getters are inside the guard. Repeated failed attempts by one thief at one totem within a minute are recorded once; at most 60 failed rows a thief an hour. The prune deletes 5,000 at a time. A database down from the start no longer gets a write per steal (only the minute's retry). Waiting rows get one last try at the stop, and the count is logged. Owner names come from BSP-Core's own totem or forge first. `scores()` checks the record shapes, not only the classes.
5. Panel: every TOTEMS answer carries the panel's uncached `now` for the page's clock. The lit steal and an opened row use that clock. A failed read after a window change shows its error. A player's starting points come from one targeted query. A board slightly behind never brings back an ended steal. MAP › only reads the map list until staff are known to be still on TOTEMS. The steals table's name in the message follows the configured prefix.

Left, said: a Totem Forge steal running at a server stop gets no row (BSP-Core keeps none; API.md says so); the panel's MySQL code (the points transaction, `lastPointsBefore`, the bounded steals query) runs only against the memory store in tests; reading from a lagging fallback server can still record a small loss and gain.

Tests: GameTests **220/220** (new: failed attempts folded and capped; rows waiting at the stop; also the ticks against a catch-up clock, the owner name from BSP-Core, no write per steal during an outage, the prune once an hour). JUnit **112/112** (new: speed on ticks, rows matching their copies, a future-dated row; a forge pour and another server's steal still running a minute on; an Owner on `/api/totems` with `now`). The 0.5.0 demo checked again: Vexa and Pixl flagged, FAST from ticks, stealing now, no errors.

On the network, add to the earlier checks: after a lag spike (`/tick` is not in 1.20.1; a heavy area or a backup), steal a test totem: `SELECT ticks, expected_s, ended_ms - started_ms FROM bsp_su_steals ORDER BY id DESC LIMIT 1;` shows `ticks` close to `expected_s * 20`, and the panel does not flag it.

## 2026-11-21: private staff messages, the record (SU-105)

The rules and the storage of private staff messages (docs/API.md "Private staff messages", D630 to D652): `talk/TalkStore`, `TalkBook` (the rules), `TalkTable` and `TalkData` (the world's save), `TalkService` (who may write, the limits, the Action Log), the two shared tables in `JdbcBackend`. Nothing reaches a player or the panel yet: delivery is SU-106, the commands SU-107, the screens SU-108, the panel SU-109 to SU-111.

GameTests (`TalkGameTests`, 9): a staff line opens one conversation that a second staff member's line joins, each line naming its writer, the key's flash state, one Action Log entry for the start; the player cannot write first and nothing is opened by trying, then answers, which stops the flash; a staff member who did not write last ends it, once, the player cannot write after, the next staff line opens a new conversation, the player sees only the open one, the Action Log has each start and the end; a player who is not staff cannot write or end, nobody writes to staff (in game or from the panel), the panel and the console may; empty, 257 characters, colour codes and invisible characters, the 31st staff line and the player's 11th in a minute, and both again a minute later; the 90-day prune drops old lines, ends a conversation left with no line (an end line by nobody, heard by the listeners), drops an ended one with no line, keeps one with a newer line and one being opened this minute; two servers opening at once keep one conversation and only the first end counts; a lone server keeps it in the world's save and reads it back the same; every line's signed copy matches its row, and without a key the copy is empty.

Not covered by the GameTests: the MySQL tables themselves (the memory table plays them; the unique key `(player, is_open)` with NULLs is MySQL's behaviour); LuckPerms staff groups (who is staff is given to the tests).

On the network, after installing (any one game server with shared storage):
1. Start the server: no storage warning; in MySQL, `SHOW CREATE TABLE bsp_su_talks;` and `SHOW CREATE TABLE bsp_su_talk_lines;` show both tables, `talks` with `UNIQUE KEY one_open (player, is_open)`.

## 2026-11-21: private staff messages reach people (SU-106)

Each line written is told to the other servers (notice `talk`, docs/API.md) and handled alike on each (`talk/TalkDelivery`): the player's key state (`TalkStatePacket`), the popup (`TalkPopupPacket`, the writer and a count, never the words), the chat lines (D652 R4), `told_at`; staff told of answers and of ends; at login the conversation is read and staff lines never told bring the popup 6 s later, after the rules. Protocol 10 (`ServerUtilsNetwork`). The client only keeps the state (`client/ClientTalk`): the key, the popup screen and the window are SU-108, `/su talk` and the other commands SU-107, so [OPEN] does nothing yet.

GameTests (`TalkDeliveryGameTests`, 8): a staff line gives the player here a flashing key, one popup naming the writer and the [OPEN] line (running `/su talk`) without the words, staff hear nothing, and `told_at` is recorded; the answer stops the flash and reaches every staff member here as one cut line with [OPEN] for that player, nobody else; the end takes the key away with the thank-you line and tells the other staff who ended it with whom, not the ender; a line written on another server reaches the player here through the notices table, and the told mark is shared; lines sent while away bring one popup (count 2, the one writer, the away wording) only 6 s after the rules let the player through, and never again on the next login; three lines from two writers name nobody; a line arriving during the login read is handled after it; a conversation that ends itself after 90 days takes the key away and tells no staff.

Not covered: the client (the packets reach only the client's copy); Redis itself (the notices table carries them in the test); the real player list and LuckPerms (given to the tests).

On the network, after installing on two servers with the new modpack (the key and popup are drawn from SU-108; until then check the chat lines):
1. Staff on server A: `/su msg <player> hello` (SU-107) while the player is on server B: on B the player gets "[Staff] You have a private message from staff. [OPEN]".
2. The player answers (SU-108's window): every staff member online on A and B gets "[Staff msg] <player> replied: ...".
3. With the player offline, write to them, then have them log on: the "While you were away" line comes about 6 s after joining (after the rules popup for a new player); moving to the other server does not repeat it.

## 2026-11-21: private staff messages, the commands and the window's data (SU-107)

`talk/TalkCommands` (`/su msg`, `/su talk`, `/su talk <player>`, `/su talks`), `talk/TalkWindows` (who has which window open here, the view, live lines, the window's SEND / END / close), packets `TalkViewPacket`, `TalkLinePacket`, `TalkAnswerPacket` (server to client) and `TalkActionPacket` (client to server), `StorageBackend.playerByName`. `/su` is open to every player now: each part keeps its own permission, and `/su` alone tells a player which parts are theirs. The windows themselves are drawn in SU-108.

GameTests (`TalkCommandGameTests`, 6): `/su msg` finds a player online here (answers where), one offline in the network's list in any case (answers that they see it at next login), refuses an unknown name and staff; a player who is not staff cannot run `/su msg`, `/su talks` or `/su talk <player>`, and `/su talk` tells them when nothing is open; `/su talk` sends the player's window with only the open conversation and its lines then arrive live; `/su talk <player>` sends the staff window with the earlier conversation and its end, where they are and who started the open one, and refuses staff; an answer arrives live in the player's and the staff window but not to staff without the window, an empty line is refused with its reason, the player's window has no END, a staff key for a window not open does nothing, END reaches both windows, and a closed window gets nothing more; `/su talks` lists the waiting replies first, the longest wait first, with "waiting 9 min", then the rest with "sent 10 min" and "offline, not seen yet", each name running `/su talk <name>`.

Not covered: the network-wide name lookup through the client check's list and Forge's name cache (the tests use the player list and the network's players table); the client.

In game, after installing (with SU-108 for the windows):
1. As staff: `/su msg <player on another server> hello` answers "Sent to … (online on <server>)"; for someone offline, "(offline: they see it when they next log on)".
2. `/su talks` lists the conversation; click the name: the staff window opens.
3. As a player with no conversation: `/su` says "Use /su talk to open your conversation with staff."; `/su talk` says none is open.

## 2026-11-21: private staff messages, the screens (SU-108)

The client: `client/TalkButton` (the MESSAGE STAFF key, look A of set AO, D650), `client/TalkPopupScreen` (look B of set AN, D649), `client/TalkScreen` (look A of set AM, D648, for the player and for staff), `ClientTalk` (the popup shown at a quiet moment; the window opened from the server's view), `messageButton` in `bsp_server_utilities-client.toml`, the JEI and EMI exclusion areas, `CornerPlace.place(..., w)`. SUPPORT BSP no longer steps round this key (this key steps round it). Lang lines `gui.bsp_server_utilities.talk.*`.

GameTests (`TalkKeyGameTests`, 1): the key stands two pixels right of SUPPORT BSP, where SUPPORT BSP would be when it is hidden, steps past another mod's button, goes down a row past the left half of the window, and SUPPORT BSP's own placing is unchanged. Everything else here is drawn in the player's game: **untested in a client**.

In game (two clients: a staff account and a player; mod and modpack with protocol 10):
1. Staff: `/su msg <player> Hello`. The player (no screen open): the STAFF MESSAGE popup within a moment, an envelope, "<staff> of staff sent you a private message.", LATER and OPEN; the chat line "[Staff] You have a private message from staff. [OPEN]".
2. Open the inventory: MESSAGE STAFF stands right of SUPPORT BSP (or right of KITS when there is no support link), flashing red every half second; its tooltip says a message is waiting. With the recipe book open, and with JEI or EMI, it overlaps nothing; on a narrow window it moves down a row or hides.
3. LATER: the popup closes, the key keeps flashing. Get hurt, then have staff write again: the popup waits 10 s after the hurt, and appears only once you close the inventory.
4. Click the key: the window opens over the inventory: "STAFF MESSAGES", the staff line on the left in violet, "NAME · STAFF · time". Type past 200 characters: the count shows; at 256 it stops. Enter sends: your line on the right in teal; the key stops flashing.
5. Staff: `/su talk <player>`: the staff window with the player's name and "online · <server>", their line on the left, staff lines on the right, "Open since … · <staff>"; answer from the player's window: it appears live in the staff window.
6. Staff: END CONVERSATION turns into a red SURE? END with "Their key goes and their window closes."; wait 3 s, it goes back; press twice: the player's window shows "Staff ended the conversation", closes after 5 s, the key is gone; the staff window shows "Ended by …" and "Write to start a new conversation."
7. A player with the window open and no message to send: Esc closes it and returns to the inventory or the game.
8. Turn `messageButton = false` in the player's client config: no key; `/su talk` still opens the window.

## 2026-11-21: private staff messages from the panel, the game server's part (SU-109)

`web/WebTalk`: the actions `talk_send` and `talk_end` (through `WebActions`), the live kind `talk` (every line written here, its signed copy) and the snapshot's `talk` (docs/API.md). The panel's side is SU-110 and SU-111.

GameTests (`WebTalkGameTests`, 3): a Moderator's `talk_send` writes a staff line under the staff member's name and answers with its signed copy, where the player is (empty: offline) and `SENT`, the same copy told live, the conversation opened by them and the Action Log's start by "<name> (panel)"; the snapshot says `talk`; `talk_end` ends it with a signed end line told live, a second end answers `NO_TALK`, a line to staff, an empty line and a name that is not a name are refused; lines written in game (a staff line and the player's answer) are told live too, and nothing is told or taken while the panel is off on the server (the line is still written).

Not covered: Redis (the live lines are caught before `publishWeb`); the panel.

On the network (after SU-110 and SU-111, or by hand on Redis):
1. `redis-cli SUBSCRIBE bsp_su:<channel>:web` on mc-main, then `/su msg <player> hello` in game: a message of kind `talk` with `"kind":"staff"`.

## 2026-11-22: private messages to players, the panel's side (SU-110)

Panel version raised to 0.6.0 first (no jar built). `talk/PlayerTalk` (history, the open list, send, end, live lines), `PanelStore.talksOf` / `openTalks` / `talkLines` (Jdbc and Memory), `Feed.liveWithTalk`, `Actions.message` (its own allowance, 30 a minute), `/api/pmsg`, `/api/pmsg/list`, `/api/pmsg/send`, `/api/pmsg/end`, the `pmsg` event, the Redis chain in `Main`. The setup guide (and its User Notes copy) has the two new grant lines and "Updating to panel 0.6.0". The page is SU-111.

JUnit (`PlayerTalkTest`, 7, and `PanelServerTest.player_messages_are_read_by_every_rank_and_by_nobody_logged_out`): only lines whose signed copy is of that very line are shown, with the copy's words, the rest counted; a signed end line closes a conversation its row calls open; the open list waiting on staff first, the longest wait first, with where an online player is, "not seen it yet" and the newest line, and the waiting count; send and end to the player's own server, else the busiest that takes messages, never one that does not, none when no server takes them, by the staff account's name and rank, the line shown at once and taken once when its live copy arrives; empty, long and bad requests refused before anything is asked; live lines only when signed with this panel's key and not old, once, with the waiting count; a missing grant said with the table's name. **120/120** panel tests.

Not covered: MySQL itself (`JdbcStore`'s new reads run only against the memory store); the real Redis.

On the network (after mod 0.17.0, the grants and panel 0.6.0; the page from SU-111):
1. `SHOW GRANTS FOR 'bsp_panel'@'<host>';` lists SELECT on `bsp_su_talks` and `bsp_su_talk_lines`.
2. The panel's console has no `[panel] player messages:` line after a page reads them.

## 2026-11-22: private messages to players, the page (SU-111)

`static/pmsg.js` (`PlayerMsg`: the section, the MESSAGE key, More › Player messages, the More badge, the `pmsg` event, the resync after a gap), wired in `players.js` (the card), `items.js` (the offline page), `app.js` (More, the badge, the event) and `app.html`; `panel.css` (a `pmsg` block at the end); `Demo` (three conversations; `talk_send` and `talk_end` written to the memory tables; `talk` in the demo's snapshots). Checked in the browser pane with `--demo` (panel 0.6.0 jar): the More badge, Kestrel_88's card (the earlier conversation folded behind "Ended by Ava_Builds", unfolded; SEND lit the new line and cleared the box; END asked "Sure? End" and folded the conversation behind "Ended by Demo_Owner"), More › Player messages (Newbie_Sam waiting first in red, Wren_88 "offline · not seen it yet", the ended one gone), Wren_88's offline page at MESSAGES, Newbie_Sam's red dot on MESSAGE and "waiting 5 min", a phone's sheet; no console errors.

JUnit: `PanelServerTest.player_messages_are_read_by_every_rank_and_by_nobody_logged_out` also checks the script is served, sets no markup, and is loaded by the page. **120/120**.

On the network (mod 0.17.0 with the new modpack, the grants, panel 0.6.0):
1. In game, `/su msg <player> hello` as staff: the player's card on the panel shows the line within a second, lit, without reloading.
2. The player answers in game: their line appears on the left, More's red count goes up, More › Player messages lists them first in red.
3. Send from the panel: the player gets the popup in game. END from the panel: their MESSAGE STAFF key goes in game, and the conversation folds on the panel.

## 2026-11-22: audit of private staff messages (SU-112)

Two fresh read-only audits (the mod side, the panel side); each serious finding checked against the code, then fixed:
1. **Mod: the database down since the start** left every staff message request and the login read unanswered (`Storage.submit` dropped the task when `ready()` failed): a new `Storage.submit(task, failed)` always answers; a failed login read is tried again every 30 s; a name looked up fails as "could not be saved", never as "nobody of that name".
2. **Mod: a line could land in a conversation ended a moment before**: lines go in only while it is open (one statement in MySQL); a staff line then opens a new conversation, an answer is refused; a late notice of an ended conversation never brings the key back.
3. **Mod: lines during the login read reached staff twice**: only the player's part waits for the read. A quick relog keeps its own buffer.
4. **Mod, smaller**: the login popup reads again before showing (never twice after a quick move); a conversation ending itself is in the Action Log; long names (command blocks, `/execute as`) are cut to the columns and packets, never thrown; two prunes never lose an end line; who wrote last (`last_kind`, the table's order) decides the flash, not two servers' clocks; an offline operator counts as staff when operators do.
5. **Panel: a conversation row moved to another player carried its lines**: a line is shown only under the player its signed copy names; player names on lines are the signed ones.
6. **Panel: More › Player messages could freeze the page** (a redraw loop while the list loaded): one read at a time, one redraw when it is back.
7. **Panel, smaller**: who started or ended a conversation and whether it is open come from signed lines only; the waiting count is worked out off the Redis thread after a burst (event `count`) and never taken over by an older read; the newest 20,000 lines and 2,000 conversations are kept, not the oldest; a newer history read always wins; a SEND click is never lost to a redraw; "waiting" counts up each minute; SEND is not offered when no server can answer; a send with no answer yet says it may still arrive; bounded caches; one send per player at a time.

GameTests (`TalkAuditGameTests`, 9; two older tests now let the storage run after the tick): **256/256**. JUnit (`PlayerTalkTest`, 3 new and 2 changed): **123/123**. Checked in the browser pane with `--demo`: five redraws while the list loaded (no freeze, drawn once), a send clearing the waiting mark and the count, no script errors.

Not covered: MySQL itself (the open-only insert's `INSERT ... SELECT`, the `last_kind` column and the newest-20,000 query run only against the memory tables); the panel's front end has no automatic tests (checked by hand above).

## 2026-11-22: release 0.17.0 / staff panel 0.6.0 (SU-113)

Private staff messages (SU-105 to SU-112). Versions: mod 0.17.0 (`gradle.properties`), panel 0.6.0, network protocol 10. GameTests 256/256, panel JUnit 123/123.

Install, in this order:
1. `bsp-server-utilities-0.17.0.jar` on every game server (delete the 0.16.0 jar) and in the players' modpack (protocol 10: a player on the old modpack cannot join).
2. Once one server has started with it: the two grants in `docs/STAFF_PANEL_SETUP.md` › Running it › Updating to panel 0.6.0.
3. The panel: Stop, upload `bsp-staff-panel-0.6.0.jar`, delete the old `bsp-staff-panel.jar`, rename the new one to `bsp-staff-panel.jar`, Start.

Then the checks in the sections SU-105 to SU-111 above, in game with two clients (a staff account and a player) and on the panel.


## 2026-11-22: where players were last seen (SU-114)

The shared table `<prefix>seen` and `players/LastSeen` (the logout, the server stop, an arrival after 10 s, everyone every 5 minutes, retries), `StorageBackend.saveSeen` / `loadSeen` (Jdbc and Memory). Owner decisions D653 to D671; the panel's side is SU-115 to SU-117.

GameTests (`LastSeenGameTests`, 6): a logout writes this server, the world, the block, the name and the time, signed with the action key (the copy holds the same fields); everyone online written every 5 minutes (not before) and at their new place, an arrival 10 s after it (not before); an older row never replaces a newer one written first (a late logout from the server left), a newer one does; a server stop writes everyone there as logged out and forgets their pending arrival; a failed write keeps the newest row per player and is tried a minute after it failed (not before); without shared storage nothing is kept; without a key the row is written with an empty copy. **262/262** GameTests.

Not covered: MySQL itself (`JdbcBackend.saveSeen`'s `ON DUPLICATE KEY UPDATE` guard runs only against the memory table here); real logouts and a real server stop (the events are called directly; mock players are not in the player list).

On the network (after 0.18.0 on every server):
1. Log in on one server and out again: `SELECT name, server, dimension, x, y, z, kind, FROM_UNIXTIME(at_ms/1000) FROM bsp_su_seen WHERE name = '<you>';` shows that server, world and block, kind `logout`.
2. Move to another server and stay 15 seconds: the row shows the new server, kind `seen`; leave: kind `logout` on the new server.
3. Stay online 6 minutes: `at_ms` moves on by about 5 minutes.
4. Restart a server with a player on it: their row is kind `logout` from that server, at the stop.

## 2026-11-22: offline players, the panel's side (SU-115)

Panel version raised to 0.7.0 first (no jar built). `players/OfflineDesk` (the page of offline players, one player, signed-copy checks), `PanelStore.offlinePlayers` / `countOffline` / `seenPlayer` (Jdbc and Memory), `POST /api/players/offline` and `/api/players/seen`, `Feed.skinOf` remembering the last skin seen. The setup guide (and its User Notes copy) has the new grant line and "Updating to panel 0.7.0". The page is SU-116 and SU-117.

JUnit (`OfflinePlayersTest`, 5, and `PanelServerTest.offline_players_are_read_by_every_rank_and_by_nobody_logged_out`):
- The most recently seen come first, then those with no record by name; online players are left out, and the total counts the rest. A page starts at its offset.
- The search finds any part of a name in any case; an underscore is a letter, never a wildcard; odd text finds nobody.
- A place is shown only through its signed copy: the copy's place over the columns, and a crash's record said as such. Another player's copy, a bad signature, no copy, or one dated two days ahead shows as not known. Without a key (the demo) rows are taken as they are.
- Without the grant on the seen table the names are still listed and the missing grant is named.
- One player is found in any case, online or not, with their head from the skin last seen after they leave.
- Every rank reads the list and one player; nobody logged out does; odd numbers are taken as the defaults.

**129/129** panel tests.

Not covered: MySQL itself. `JdbcStore`'s join, `LIKE` escaping and `NOT IN` run only against the memory store.

On the network (after mod 0.18.0, the grant and panel 0.7.0; the page from SU-116):
1. `SHOW GRANTS FOR 'bsp_panel'@'<host>';` lists SELECT on `bsp_su_seen`.
2. The panel's console has no `[panel] offline players:` line after the Players tab is opened.

## 2026-11-22: offline players, the Players tab (SU-116)

`static/offline.js` (the list's reads, search, SHOW MORE, kept up to date, the time words), `players.js` (the OFFLINE group, the offline card, MAP ›), `app.js` (`openOffline` opens the card on the Players tab; the live feed and the maps event told), `items.js` (Find a player opens the card), CSS, `Demo.offline`, and a demo launcher on port 8207.

JUnit: the served `offline.js` and `players.js` set no markup from the network, and the page loads `offline.js`. **129/129**.

Checked in the browser pane with `--demo` (computer 1100 px wide and phone 375 px):
- The Offline tile reads 92 and the count line "· 92 offline". The OFFLINE group shows 50 rows, newest first, greyed and readable; SHOW 50 MORE reads the rest (92 of 92), with no record last by name.
- The search "ju" shows the online Juniper_K and "Offline 3 match “ju”" (Juniper7, Jumbo_Jet, Bajuka "not recorded").
- Juniper7's card: "Last seen on …", the crash line, the hidden-map line (the demo has no BlueMap), the four keys, the inventory "saved 40 min ago" with "Changes reach … at their next login". Bajuka's: "not recorded yet".
- More › Find a player › Old_Timer opens the card on the Players tab.
- On a phone: 20 rows and SHOW MORE, the grey Offline chip, no sideways scroll, and the card as a bottom sheet.
- No console errors; every request answers 200.

Not covered:
- MAP › moving to the Map tab (the demo has no map; SU-117 makes it the map's offline card).
- A player leaving while their card is open (the demo's players never leave).
- The minute's read.
- Heads from the last skin (the demo has no skins).

On the network (after mod 0.18.0, the grant and panel 0.7.0):
1. Log off a test player: within about 5 seconds they are in OFFLINE at the top, "1 min", with the server and world they left from.
2. Open their card: "Logged off on <server> · <world>", the block, MAP › opening the Map tab there, and their inventory as saved at logout.
3. Log them on again: they leave OFFLINE at once and the open card turns live.

## 2026-11-22: offline players, the Map tab (SU-117)

`map.js`: the "Show offline players" switch (`bsp.mapOffline` in this browser), `offRows`, `chooseOff`, `placeOff`, the pin `bsp-offpin` in BlueMap's popup set (kept, no 30-second timer; dropped on another choice, on a map switch away from it, or on closing the card; put back when the tab opens again), `drawOffCard`, `showOffline` (the card's MAP ›), `offlineOn`. `players.js` MAP › goes there. The pin's look is injected into BlueMap's page (`.bsp-offpin`). `static/map-offline.svg` is the grey head for a player whose skin the panel has not seen. JUnit **129/129**.

Checked in the browser pane with `--demo` and a **stand-in for BlueMap's page** (the scratchpad's `standin-map`, launcher `panel-demo-070-map`, port 8208: a flat board answering `switchMap`, the position and the marker sets; not BlueMap):
- The switch adds OFFLINE 92 with 50 rows on a computer and 20 on a phone, and is remembered after a reload.
- Ash_Vale (Moongrove's End) switches the map to `moongrove__end`, centres it on 218, -261, places `bsp-offpin` labelled "Ash_Vale · logged off", and opens the card "logged off 1 h ago (01:07) / Moongrove · End · 218, 44, -261" with the hotbar.
- Vermillion (Stormfall's End, not shown): the map stays, Ash_Vale's pin goes, and the card says the map is not shown.
- OPEN CARD leads to the Players tab card, and its MAP › comes back to the Map tab with the pin and card.
- The pin is still there after 33 s and goes when the card is closed.
- Old_Timer (no record) gets the card with "not recorded yet" and no pin.
- Following Kestrel_88 ends the offline choice.
- Switching off hides the rows and keeps the chosen card.
- On a phone the card is the small sheet with the pin above it.
- No console errors.

Not covered: **real BlueMap**. The pin's head icon, its grey look and its label rely on BlueMap's poi marker markup (`img` and `.bm-marker-poi-label` inside the marker's element with its classes), which the stand-in imitates. Also not covered: a chosen player logging on (the demo's players never change).

On the network (after panel 0.7.0):
1. Map tab › Show offline players: a player who logged off a moment ago is first.
2. Choose them: the map moves to their server and world, and their grey head with "Name · logged off" sits where they left. If the head or the label is missing or not grey, say so: the pin's markup is BlueMap's.
3. Close the card: the pin goes.

## 2026-11-22: audit of offline players (SU-118)

Two fresh read-only audits (the mod side, SU-114; the panel side, SU-115 to SU-117). There were no HIGH findings, and every finding is fixed.

**Mod (`players/LastSeen`):**
- The 5-minute write drops only arrivals at least 10 s old, so a fresh arrival keeps its own row.
- Fake players are never recorded.
- The logout is recorded first in the shared logout listener.
- The state is cleared at server start (`onServerStarted`).
- The stop logs how many rows waited.
- A batch the database refuses is tried row by row. A row refused while others went through is marked, and dropped after 5 refusals with a warning. When every unmarked row fails, the database counts as away and all wait.
- The "writes failing" flag clears when nothing was put back.
- Arrivals are looked up one by one, not by scanning every player.

**Panel:**
- An `actionKey` that cannot be read (an empty key) believes no place; only the demo (no key) takes rows as they are.
- A copy must match its row: the name in the players table, `at_ms`, server, dimension, block and kind.
- A row dated more than a day ahead sorts with the unseen (`ORDER BY` in `JdbcStore` and `MemoryStore`).
- `p.uuid` is the last sort key. Rows whose id is not a UUID are never listed.
- A missing `bsp_su_seen` (SQLState 42S02) says to update the game servers. The console lines no longer name each player.
- `offline.js`:
  - A re-read reads back every page shown (up to 1,000 rows), so SHOW MORE past 200 no longer falls back.
  - SHOW MORE is refused while a read runs, gives way to a newer read, and starts 5 rows back so nobody is skipped when players log on between pages.
  - The search's words take effect only when its read starts (`listQ` is the list's own words).
  - A read says it is under way.
  - After a leave only the cards on screen are read again, and records nobody listed or looked at for 10 minutes are let go.
- The offline card is not rebuilt while a field in it is typed in; only the place box and the last-seen line are.
- `map.js`:
  - A TOTEMS place clears the offline choice and its pin.
  - An offline spot waits 10 minutes for a slow map, not 15 s.
  - The pin's icon address is a checked UUID, escaped.
  - Map labels are read back through an inert `DOMParser` (was a detached `div`).

**Tests:**
- GameTests (`LastSeenGameTests`, 4 new):
  - An arrival a second before the 5-minute write still gets its own row over a late logout.
  - A stop while writes fail writes everyone, and a logout after the storage closed is not kept.
  - One refused row never holds up the other and is dropped after 5 refusals, then writes go straight through.
  - A machine's fake player with its owner's UUID never writes over the owner's place.
  - **266/266**.
- JUnit (`OfflinePlayersTest`: the signed-copy test reworked, plus 3 new; `PanelServerTest`: `map.js` sets markup only from its fixed icons):
  - Copies not of that very row (name, time, place) are not believed.
  - A far-future row never sorts first.
  - An empty key believes nothing.
  - At most 50 rows whatever is asked.
  - A non-UUID row is never listed.
  - A missing table says to update.
  - **132/132**.
- Checked again in the browser pane with `--demo` and the stand-in map:
  - SHOW MORE to 92, then a re-read, keeps 92.
  - During the search's wait the header keeps the old words; then "3 match “ju”".
  - Juniper7's card reads as before.
  - A TOTEMS place (from the TOTEMS tab) clears the offline card and pin and puts its own pin.

Not covered: MySQL itself (`JdbcBackend.saveSeen`'s newer-wins guard and `JdbcStore`'s join, ordering and `LIKE` run only against the memory tables), real BlueMap's pin markup, and the in-flight re-flush (unreachable with the tests' synchronous storage).

## 2026-11-22: release 0.18.0 / staff panel 0.7.0 (SU-119)

Mod **0.18.0** (`gradle.properties`; the network protocol stays 10, so players need no new modpack) and panel **0.7.0**, with both CHANGELOG version blocks. The setup guide (and its User Notes copy) names the new jars, and its protocol line is brought up to date (0.17.0 and newer speak 10). Jars: `build/libs/bsp-server-utilities-0.18.0.jar` (reference map complete, `tools/check_refmap.py`) and `staff-panel/build/libs/bsp-staff-panel-0.7.0.jar` (JUnit 132/132). GameTests 266/266 on this code.

Install, in order:
1. `bsp-server-utilities-0.18.0.jar` on every game server (the players' modpack may stay on 0.17.0).
2. Once one server has started with it: `SHOW TABLES IN bsp LIKE 'bsp_su_seen';` shows one row, then `GRANT SELECT ON bsp.bsp_su_seen TO 'bsp_panel'@'<host>';` on `192.168.1.25` in `sudo mariadb`.
3. Panel 0.7.0: Stop, upload `bsp-staff-panel-0.7.0.jar`, delete the old `bsp-staff-panel.jar`, rename the new one to `bsp-staff-panel.jar`, Start.

Then the checks in the SU-114, SU-116 and SU-117 sections above.

## 2026-11-22: last seen in the staff window, the server's side (SU-120)

The staff window (`/su talk <player>`) for a player online on no server carries where they were last seen (owner decisions D672 to D683; `tools/preview/lastseen_refined.html`). The window packet `TalkViewPacket` gains the place (`Place`: not known, not recorded, or found with how long ago, server, dimension, block and whether it was a logout), so the network protocol is **11**. `TalkWindows.openFor` reads the conversations and the player's `bsp_su_seen` row in one storage step (`TalkService.staffWindow`). The client's line and hover come in SU-121: until then a client shows "offline" as before.

GameTests **275/275** (`TalkLastSeenGameTests`, 9 new): `/su talk` for an offline player carries 3 h ago, the server, the nether and the block, as a logout; a record written while online says no logout; no row is "not recorded" after one read; a player online here is never looked up; a failed read still opens the conversation as plain offline; without shared storage it is plain offline and the shared table is untouched; a player who logs on during the read shows as online; a row ahead of the server's clock is 0 ago, and the player's own window never carries a place; the packet keeps the place, cuts the server to 64 and the dimension to 160 characters, and sends a negative age as 0.

Untested: against real MySQL (the read is the existing `loadSeen`, by primary key); in a client (nothing on screen yet, SU-121); a player online on another server (read from the client check's list, as `where` always was; the GameTests use players on this server).

## 2026-11-22: last seen in the staff window, the screen (SU-121)

`client/TalkScreen` draws a second head line in the staff window for a player online nowhere (D682, D683 R1 to R4): a grey OFFLINE chip, then "last seen 3 h ago (21:04) on BSP-Main · nether" (the Overworld left out, a modded world by its name), a red "· no logout recorded" that is always kept while the grey part is cut with "…", or "last seen: not recorded". The thread is 8 GUI px shorter while the line shows. Hovering the line shows "Last seen 3 h ago · 22 Nov 21:04", "Logged off on BSP-Main · nether" (or "Last record on …"), "Block 120, 64, -30", and the red no-logout sentence; for no record, the sentence that the place is written at the next logout. The time counts on from when the window's packet arrived (`ClientTalk.viewAt`), less the server's age. The steps and world words are in `talk/SeenWords`; 20 lang keys `talk.window.seen.*` from `tools/gen_lang.py` (`tools/check_lang.py`: 0 missing).

GameTests **277/277** (2 new in `TalkLastSeenGameTests`): minutes (at least 1, a moment ahead counts as now), hours under a day (also when it was the day before), yesterday, 4 and 13 days, then "8 Nov" and "18 Oct 2025"; the viewer's own calendar decides yesterday (the same moment is "2 d" in UTC and "yesterday" two hours west); the three vanilla worlds, and modded ones by their path with spaces.

**Untested in a client.** To check in game, with two accounts (staff and a player) on 0.19.0 clients:
1. The player logs off in the nether; staff run `/su talk <player>`: the second line reads "last seen 1 min ago (HH:MM) on <server> · nether", and after a few minutes with the window open it counts on.
2. Hover the line: the full date, "Logged off on <server> · nether", the block where they stood.
3. Kill the player's server process while they are on it (a test server), then `/su talk` from another server: the red "· no logout recorded" and the two-line red sentence in the hover.
4. A player who has not logged off since 0.18.0 was installed: "last seen: not recorded" and its hover.
5. A long name with a long server name and a modded world: the grey part ends in "…" and the red tail still shows.
6. An online player: the head is "online · <server>" on one line, as before.

## 2026-11-22: audit of last seen in the staff window (SU-122)

A fresh read-only audit of SU-120 and SU-121 found no high-severity defect; every finding is fixed:
1. A long offline name was cut in the head (the title kept room for the old "offline" beside it): the title has the whole width to CLOSE when nothing sits beside it, so "KESTREL_MOUNTAIN" shows whole.
2. On the night the clocks go back, 24 h 20 min on one calendar day said "yesterday": the same calendar day is always "N h ago" (`SeenWords`, GameTest with Europe/London on 25 Oct 2026, plus the 59 min / 60 min edge).
3. The no-logout sentence in the hover wrapped to three lines: the hover is 300 px wide, so it is two.
4. Past 13 days the hover said the date twice: "Last seen 18 Oct 22:10" (new key `talk.window.seen.hover_on`).
5. In the second or two after a player leaves a server, the window can show the older 5-minute row as "no logout recorded": documented in `docs/API.md` (opening it again shows the logout).
6. A stalled read of `<prefix>seen` held up the whole window: the read gives up after 5 seconds (`JdbcBackend.SEEN_READ_TIMEOUT_S`) and the window says "offline". Untested against real MySQL.
7. A test-only comment in `MemoryBackend` was moved back onto its field.

GameTests **277/277**; `tools/check_lang.py` 0 missing. Still untested in a client (the checks in SU-121 above; for step 5 there, a 16-letter name now shows whole).

## 2026-11-22: release 0.19.0 (SU-123)

Mod **0.19.0** (`gradle.properties`), the `CHANGELOG.md` [0.19.0] block, and the setup guide (and its User Notes copy): the jar row names 0.19.0 and a new modpack, the protocol line says 0.19.0 and newer speak 11, and "Updating to BSP-Server Utilities 0.19.0" says what to do. The staff panel is unchanged (0.7.0), with no new grant. Jar: `build/libs/bsp-server-utilities-0.19.0.jar` (reference map complete, `tools/check_refmap.py`; it holds `talk/SeenWords` and the 21 `talk.window.seen.*` lines). GameTests 277/277 on this code.

Install, all at once (protocol 11):
1. Put `bsp-server-utilities-0.19.0.jar` into the players' modpack and publish it.
2. Put it on every game server, replacing 0.18.0, and restart them. A 0.18.0 game is turned away, told the mod's version differs.

Then the in-game checks in the SU-121 section above.

## 2026-11-22: singleplayer worlds as a grid (SU-124)

The Select World screen (Singleplayer) shows the worlds as a scrolling grid of cards (artboard W1). It is still vanilla's `SelectWorldScreen`, and vanilla's `WorldSelectionList` is still the model: it loads the worlds, filters them on search, holds the selection and enables the buttons. `client/WorldGridHook` (on `ScreenEvent.Init.Post`) takes the list out of the screen's input and narration and puts `client/WorldGridWidget` in its place, in the same bounds and Tab position. `mixin/WorldListGridMixin` (client) stops that one list drawing its rows and its scrollbar, so its background (the resource pack's, or FancyMenu's) stays. `mixin/WorldListEntryAccess` reads each entry's level summary and the icon texture vanilla loaded for it: the grid loads no texture of its own, and vanilla closes them when the list changes or the screen closes. Every action goes to vanilla's entry: click `setSelected`, double-click or Enter `joinWorld`, "+" `CreateWorldScreen.openFresh`. The layout is in `menus/WorldGridLayout`, plain arithmetic. There are 9 lang keys `worlds.*` from `tools/gen_lang.py` (`tools/check_lang.py`: 0 missing). The client setting is `menus.worldGrid` (true to begin with).

GameTests **282/282** (5 new in `WorldGridGameTests`):
- columns 4 at 480, 5 at 576, 6 at 640 and 960, at least 2, never more than 6;
- cards at least 96 wide, filling the row between the 12 px margins and clear of the 4 px track;
- 16:9 pictures over a 22 px text block, 6 px gaps;
- a row and a half visible at 480 x 270;
- the scroll clamped, the last card scrolled into view, the thumb and its drag;
- clicks in a gap or outside the area land on no card;
- the arrows step cards and rows and leave the grid at its edges;
- the picture's "cover" crop.

The GameTest server is a dedicated server: it ran with the jar's client mixins and `WorldGridHook` present and logged no mixin or class-loading error.

**Untested in a client.** To check in game, in the "Build Secure Protect (Adding Mods)" instance with this jar (FancyMenu and the BSP-UI pack as in the pack):
1. **Grid basics.** Video Settings, GUI Scale 4 on a 1920 x 1080 window (480 x 270), then 3 (640 x 360), then 2 (960 x 540); the owner's Mac at scale 5 too. Each time open Singleplayer:
   - with no worlds (move `saves/` aside): only "+ Create new world". Vanilla opens Create World by itself when there is no world at all; that stays.
   - with 1, 7 and 30 worlds (copy one world folder): 4 columns at 480 wide, 6 at 640 and 960, whatever the number of worlds; the 30 scroll with the wheel and by dragging the thumb, which turns teal under the mouse; the last row stops at the bottom.
   - resize the window while the screen is open: the grid lays itself out again with no doubled cards.
2. **Actions.** Click a world: teal border, faint glow, SELECTED tag; Play, Edit, Re-Create and Delete light up. Each of them opens exactly the vanilla screen; Cancel goes back. Double-click a card: it plays. Select, then Enter: it plays. "+ Create new world" opens Create World.
3. **Keyboard.** From the search box, Down or Tab reaches the grid and selects the first world; the arrows move across and down (the last row's Down goes to the last card); Up from the first row returns to the search box; Tab reaches the buttons. With a screen reader or the narrator on (Ctrl+B), each card reads like vanilla's entries.
4. **Search.** Type part of a name: the cards filter live; clear it: all return.
5. **States.**
   - A Hardcore world: gold HARDCORE.
   - A world from an older Minecraft: coral OLDER VERSION. One from a newer version: coral NEWER VERSION; hovering its picture shows vanilla's two red lines, and Play asks vanilla's question.
   - A world open in another instance: coral LOCKED. Hovering shows vanilla's red line, and a click does nothing, as vanilla's list does.
   - An experimental world: gold EXPERIMENTAL.
6. **Icons.** A world with `icon.png` (save and quit once in it) shows its picture cropped top and bottom, crisp (nearest-neighbour). One without shows vanilla's default picture. Open and close Singleplayer 20 times: no growth in AllTheLeaks' report and no texture warning in `logs/latest.log`.
7. **Off switch.** `worldGrid = false` under `[menus]` in `config/bsp_server_utilities-client.toml` and reopen the screen (or restart): vanilla's list.
8. **Compatibility.** FancyMenu loads its `select_world_screen` layer with no error in the log. A dedicated server with this jar starts cleanly (the GameTest server above did).

## 2026-11-22: audit of singleplayer worlds as a grid (SU-126)

A fresh read-only audit of SU-124 found one high-severity defect and six low ones; all are fixed:
1. **Opening Singleplayer crashed the client** with the default setting. `client/WorldGridHook` reordered Forge's `ScreenEvent.Init.getListenersList()`, which is read-only (`Collections.unmodifiableList`), and its fallback threw again from the same list. The hook now reorders the screen's own list (`screen.children()`), and the fallback (`undo`) touches only that list and never throws. The grid still takes the list's place in the Tab order.
2. At GUI width 640 (cards 96 wide) even the date alone was too wide ("22/11/2026, 20:04" is 92 px, the room 88) and was cut with "…". It now falls back to the date without the time ("22/11/2026").
3. The docs said OLDER VERSION is "as Minecraft flags them", but Minecraft flags no older world on a stable game. They now say the tag marks a world saved by an older Minecraft.
4. A single tag wider than its card was dropped: it is now cut with "…". SELECTED stays last, after the warnings, and drops first when the row is full (the teal border still shows the selection).
5. Two GameTest assertions proved nothing. The out-of-area click now checks a point above the area and the last row's card at the bottom. The arrow start now matches the grid: with nothing chosen, any arrow goes to the first world (the create card when there is none), and `WorldGridWidget.keyPressed` uses `WorldGridLayout.move` for it.
6. TESTING SU-124 step 1 tied columns to world counts. It now says 4 columns at 480 wide and 6 at 640 and 960, whatever the number of worlds.
7. NEWER VERSION, an extra tag beyond the brief, is kept (owner: defaults).

GameTests **282/282**. One run had one failure in a staff-panel test (`a_players_bank_and_market_are_read_and_refunded_by_those_who_may`, untouched by this work), which passed on the next run: an intermittent failure to look at separately. The jar `build/libs/bsp-server-utilities-0.20.0.jar` is rebuilt, and its reference map is complete.

**Still untested in a client.** No dev client was opened: the session had no screen control. The first in-game check is simply that Singleplayer opens (SU-124 step 1); then the rest of SU-124's steps.

In game (the owner, 2026-11-22, the rebuilt 0.20.0 jar): Singleplayer opens and the grid works. The rest of SU-124's steps (sizes, every button, keys, search, the tags, icons and leaks, the off switch, FancyMenu's log) are not reported yet.

## 2026-11-22: Multiplayer servers as a grid (SU-127)

**What it does.** The Multiplayer screen shows the servers as a grid in the Select World grid's look. The featured server (client setting `menus.featuredServer`, `buildsecureprotect.com`: a host, or a domain its hosts end with) is a card across the whole width on top. Every other saved server and every LAN game is a square card, in the list's own order. Vanilla's "Scanning for games on your local network" comes last.

**How it is built.**
- The screen is still vanilla's `JoinMultiplayerScreen`, and its `ServerSelectionList` is still the model: the saved servers, the LAN games, the selection and the buttons.
- `client/ServerGridHook` and `GriddedLists.takeOver` put `client/ServerGridWidget` in the list's place. The Select World grid takes over its list the same way.
- `mixin/GriddedListMixin` stops the list drawing rows and its scrollbar.
- Vanilla pings each server and uploads its icon inside each row's render. So for each card on screen, the grid runs that render with drawing switched off (an empty clip), as vanilla runs it for each row on screen. Builtin Servers' and Forge's additions to that render run too. `mixin/ServerEntryAccess` reads the icon texture vanilla keeps.
- Every action goes to vanilla:
  - click: `screen.setSelected`;
  - double-click or Enter: `joinSelectedServer`;
  - Shift and an arrow: the entry's own Shift+Up/Down.
- The hovers are vanilla's words: the ping, the player list on the count, the version in red when incompatible. Builtin Servers' badge has its hover, through `compat/BuiltinServers`. Forge's mods-match icon is drawn by `ForgeHooksClient.drawForgePingInfo` (top right of a square, under the count on the BSP card).
- The layout is in `menus/ServerGridLayout`, plain arithmetic. There are 2 new lang keys, `servers.tag.*`, from `tools/gen_lang.py`.

GameTests **286/286** (4 new in `ServerGridGameTests`):
- the featured card spans the row of squares, and squares use the Select World columns, inside the margins;
- without a featured server every server is a square;
- the content ends after the scanning line, the scroll is clamped and cards scroll into view;
- the arrows go between the featured card and the first row;
- which addresses are the featured server's: the domain and names under it, any case, a port, a trailing dot, IPv6 in brackets; not look-alikes; an empty setting features nothing.

**Untested in a client.** To check in game, in the Adding Mods instance with this jar (FancyMenu, Builtin Servers and BSP-UI as in the pack):
1. **Layout.** Open Multiplayer at GUI 480 x 270, 640 x 360 and 960 x 540, and the owner's Mac at scale 5. BSP Official is the full-width card on top, with its icon, name, gold BUILTIN tag, the two-line message in its colours, the player count and green bars. Other servers are squares in 4 columns at 480 and 6 at 640 and 960. "Scanning for games on your local network" comes last.
2. **Pinging and icons.** Each server's bars cycle teal while it pings, then turn green (or coral for one that is down, with "Can't connect to server" as its message). Each server's own icon appears. Add a server that does not exist: coral bars and its message. Add a server on another Minecraft version: coral INCOMPATIBLE tag and its version in red.
3. **Hovers.** Over the bars: "Ping: N ms". Over the count: who is on. Over BUILTIN: Builtin Servers' sentence. Over Forge's small icon: the mods match.
4. **Actions.**
   - Click a card: teal border, SELECTED; Join Server, Edit and Delete light up. For BSP Official, Edit and Delete stay off and their hover says the modpack forces it (Builtin Servers).
   - Double-click joins, and so does Enter.
   - Direct Connection, Add Server, Edit, Delete (its confirm), Refresh and Back are vanilla's.
   - After Add or Edit the grid shows the change.
5. **Keyboard.** Tab reaches the grid and selects the first card. Down goes from BSP to the first square, Up from the first row back to BSP, and the arrows move along the rows. Shift and an arrow moves the chosen square one place (the order is saved in `servers.dat`; BSP stays on top whatever its place).
6. **Scrolling.** With 20 or more servers: the wheel, dragging the thumb (teal under the mouse), and the selected card kept in view.
7. **LAN.** Open a singleplayer world to LAN on another instance on the same network: a square with a teal LAN tag, "LAN World", the world's name and its address (hidden if Hide Server Address is on). Double-click joins.
8. **FancyMenu.** Its header strip and background still draw, with no error in the log. Its help text at mid-left (x 12, 92 wide) now sits over the first column of squares; move it in FancyMenu's editor if it is in the way.
9. **Off switches.** `serverGrid = false`: vanilla's list. `featuredServer = ""`: every server a square.
10. **Leaks.** Open and close Multiplayer 20 times: no growth in AllTheLeaks' report and no texture warning in the log.

## 2026-11-22: audit of Multiplayer servers as a grid (SU-128)

A fresh read-only audit of SU-127 found no high-severity defect, one medium and nine low ones. All of them are fixed except the FancyMenu part of 7:
1. **Vanilla's icon actions were missing.** Vanilla joins a server with one click on the right half of its row icon, and moves it with the arrows on the left half. A hovered server card (or every card in touchscreen mode) now shows three keys:
   - `<` moves it one place earlier in the list;
   - JOIN joins it;
   - `>` moves it one place later.
   They call vanilla's own `swap` (an invoker on `mixin/ServerEntryAccess`) and `joinSelectedServer`, with vanilla's conditions (not before the first, not after the last).
2. A card partly scrolled out of the area still answered hovers over the title and the buttons. Hovers now answer only inside the area.
3. The scroll went back to the top after Edit, Add, Delete or a resize, while vanilla's list keeps its scroll. The grid now keeps it per list (`GriddedLists.scrollOf` / `rememberScroll`).
4. On the BSP card a long name pushed the tags over the player count. The tags now get up to half the room, and a tag with no room is not drawn.
5. A square's tags could run under Forge's mods-match icon. They now stop short of it when the server reports one.
6. The hover over an incompatible server's count now shows its player list, as vanilla does.
7. Other mods' settings and the featured match:
   - Builtin Servers' `badgeVisible` is respected (`compat/BuiltinServers.badgeVisible`).
   - When several entries match `featuredServer`, the one Builtin Servers added is chosen, so a player's own older entry for the domain does not take the top card.
   - **Not fixed:** FancyMenu's "show server icons" switch (on in the pack) does not hide the grid's icons.
8. If a grid setting was turned off while the screen stayed open, the list stayed marked and showed empty. A list is now released when its grid is off (`GriddedLists.release`), for both screens.
9. The failure path put the list back in the input but not the narration. It now goes back through `addListener`, without being drawn twice.
10. Docs and version:
    - the mod version is raised to 0.21.0;
    - the CHANGELOG now says what a LAN square shows;
    - `ServerEntryAccess`'s comment no longer claims vanilla closes the server icons (the grid only reads their location).

Untested in a client: the checks in SU-127 above, plus hovering a server and using `<`, JOIN and `>` (the order is saved; `<` is off on the first server and `>` on the last), and opening Edit on a long list and cancelling (the grid stays where it was).

In game (the owner, 2026-11-23, the 0.21.0 jar): the Multiplayer grid works. The individual steps above are not reported one by one.

## 2026-11-24: Streamer Mode, the proof (SU-131)

**What it does.** A second, clean picture of the game for OBS (owner decisions D700 to D714), shown in a window titled "BSP Stream". The player's own window is unchanged.

**How it is built.**
- `mixin/GameRendererStreamMixin`: right after the world is drawn (at `Lighting.setupFor3DItems` in `GameRenderer.render`), `client/StreamerMode.streamPass` copies the world into its own picture (`glBlitFramebuffer`) and draws the HUD, the open menu and the toasts into it. Then Minecraft draws them again for the player, so the player's pass comes last (D710).
- Hidden in that pass only:
  - Forge HUD parts by id, cancelled in `RenderGuiOverlayEvent.Pre`: `minecraft:debug_text`, `journeymap:waypoint_icons`, `bsp_core:sentinel_strip`, `bsp_core:recall_offer`;
  - JourneyMap's minimap through `mixin/JourneyMapHudMixin` (a `@Pseudo` mixin on `HudOverlayHandler.onRenderOverlay`, which does nothing without JourneyMap);
  - menus in `journeymap.client.ui.` and BSP-Teleport's `BandScreen` and `PortalScreen`: the game behind them, darkened, and the "Menu hidden" card.
- `client/StreamWindow`: a second GLFW window whose OpenGL context shares Minecraft's. At the end of each frame it copies the picture to its own screen (fitted whole, black bars where the shapes differ) and shows it without waiting for the screen's refresh. Only raw OpenGL runs in its context. Closing it turns Streamer Mode off.
- `client/StreamerEyeKey`: the eye key, 14 x 14 in the top-right corner of the title screen and the pause screen, stepping left past anything there. In this proof a click switches Streamer Mode on or off (the menu comes with SU-132).
- Also: a key (unbound) and `/streamer` (`on`, `off`). The hidden items are the first switch-on defaults (`streamer/StreamerRules.DEFAULT_HIDDEN`, D707).
- Every minute while on, the log says the average cost per frame: "Streamer Mode: stream picture N ms, stream window N ms a frame on average".
- `tools/check_refmap.py` skips `@Pseudo` mixins: they target another mod by its own names and need no reference map entries.

GameTests **291/291** (5 new in `StreamerGameTests`):
- what is hidden and shown at first switch-on;
- menus hidden by class, nested class or package, not by look-alike names;
- the eye key's place, stepping left, never past the middle;
- the frame rate gate;
- fitting the picture to the stream window.

The first run had one failure, `a_players_bank_and_market_are_read_and_refunded_by_those_who_may`, which waits for an Action Log line. It is not this change's and passed on the second run (a flaky test).

**Untested in a client.** To check in the Adding Mods instance with `bsp-server-utilities-0.22.0.jar` (in place of 0.21.0) and OBS on the Mac:
1. **The key on the title screen.** The eye key is in the top-right corner of the pack's FancyMenu layout, over nothing; its hover says "Streamer Mode (off)".
2. **On.** Click it. A window "BSP Stream" opens without taking the focus from the game. The key turns red with a dot. The stream window shows the title screen, moving as the game's does (and FancyMenu's animations at their usual speed).
3. **In game.** Join a world or a server, then press F3, and have JourneyMap's minimap on. The game window shows both; BSP Stream shows neither. The hotbar, chat, hearts and other mods' HUDs show in both.
4. **Menus.** Open JourneyMap's full-screen map (J), its waypoint manager and the Teleport band: BSP Stream shows the game darkened with the "Menu hidden" card. The inventory and other menus show normally in both.
5. **OBS.** Add a source: macOS Screen Capture, method Window, then choose "BSP Stream". It shows the clean picture:
   - with BSP Stream behind the game window;
   - on another monitor;
   - with the game in full screen (F11).

   Minimised, it is expected to freeze or go black.
6. **Resizing.** Resize BSP Stream: the picture keeps its shape with black bars. Resize the game window or press F11: BSP Stream follows.
7. **Off.** Click the eye key again, or close BSP Stream with its red button: the window goes and chat says Streamer Mode is off. Also try `/streamer`, `/streamer on` and `/streamer off`, and a key set in Controls › Key Binds › BSP-Server Utilities › "Streamer Mode on or off".
8. **The cost.** In the same spot, note the F3 frame rate with Streamer Mode off and then on. After a minute on, send the log's "Streamer Mode: stream picture ... ms, stream window ... ms" lines (`logs/latest.log`).
9. **With shaders and Distant Horizons.** Repeat 3 with Oculus shaders on: BSP Stream shows the shaded world.
10. **The log.** No error naming Streamer Mode, BSP Stream, `GameRendererStreamMixin` or `JourneyMapHudMixin`.
11. **Known for now:** the mouse pointer is not in the stream (Minecraft never draws it; the system does).

**First test (2026-11-24, the owner's Mac, Apple M4 Pro, the "Streamer Mode" instance):**
- **The eye key** showed in the top-right corner but covered the corner of FancyMenu's Discord box, which is a FancyMenu element, not a widget. Fixed: `compat/FancyMenu` reads where FancyMenu's elements stand (by name; none without FancyMenu). It leaves out backgrounds a quarter of the screen or bigger, and FancyMenu's stand-ins for the screen's own widgets. The key steps past them.
- **Clicking it did not turn Streamer Mode on.** The log said "NSGL: Failed to create OpenGL context" each time, and the message went only to the log because the title screen has no chat. The cause: Forge's early loading screen makes the game's window with OpenGL 4.1 core, and the stream window asked for 3.2, which macOS will not share with it. Fixed: the stream window now copies the game window's context version, profile and forward-compatibility (the log says which: "opening the stream window with OpenGL 4.1"). Messages on the title screen show as a popup in the corner.
- GameTests 291/291 after the fixes. To test again: steps 1 to 11 above with the new `bsp-server-utilities-0.22.0.jar`.

**Second test (2026-11-24, the same Mac and instance, jar sha1 d9902523…):**
- Passed:
  - step 2: BSP Stream opens and does not take the focus;
  - step 5: OBS captures it.
- The log said "opening the stream window with OpenGL 4.1 (profile 204801)", with no GL error.
- **The cost** (step 8), in a singleplayer world at 2048 x 1536 and about 70 to 107 frames a second:
  - the stream picture 1.37 to 1.97 ms a frame;
  - the stream window 0.72 to 0.87 ms a frame;
  - about 2.1 to 2.8 ms a frame in all, from the log's three one-minute lines.

  The frame rate setting (60 or 30, SU-132) cuts it in proportion.
- Step 3 passed: F3 and JourneyMap's minimap are gone from BSP Stream (owner, 2026-11-24).
- Not yet reported: steps 1 (where the eye key stands now), 4 (the "Menu hidden" card), 6, 7, 9 and 10.

## 2026-11-24: BSP-Core's player reset (PR17)

Needs a BSP-Core with `/bsp player reset` (newer than 1.22.0) on every server, with this jar. GameTests (no BSP-Core with the event exists yet, so they call the handler directly and hook a stand-in event by its class name): `RulesGameTests.the_bsp_core_reset_is_heard_by_class_name_and_nothing_is_registered_when_the_class_is_missing`, `RulesGameTests.a_bsp_core_reset_asks_for_the_rules_again_and_a_late_server_keeps_an_agreement_given_since`, `ChunkGameTests.a_bsp_core_reset_keeps_the_players_chunks_unloaded_and_leaves_other_players_alone`.

On the network, with a test player who has agreed to the rules and has a chunk-loading machine on server B:
1. On server A, `/bsp player reset <name> confirm`. A's log: "BSP-Core reset <name> (<uuid>): rules agreement cleared for the network; chunk owners checked again in a second". B's log: the same line with "unchanged here" or "cleared on this server" (whichever notice reached B first).
2. The player joins any server: the rules popup shows again, and they are not welcomed as a new player (the public player counter is unchanged).
3. On B, the player's machines stay unloaded through the reset: nothing loads. Those they placed (or that save them as owner) stay listed under their name and come back when they join B. A machine that was theirs only through a claim BSP-Region Guard gave up in the reset moves to "no owner", stays unloaded (B's log: "Chunk unloading: 1 unloaded ticket(s) of reset player ... have a new owner or none"), and does not come back when they join; an admin can RESTORE it from the CHUNKS tab.
4. Their staff panel notes and tags, bans, staff talks, Action Log lines, totem steals and last seen are all still there.
5. With a BSP-Core older than the reset: nothing in the log, nothing changes.

## 2026-11-24: Streamer Mode's menu (SU-132)

**What it does.** Look C as confirmed (D713, D714, `tools/preview/streamer_refined.html`). The eye key, and `/streamer` without a word, open `client/StreamerScreen`:
- **The first page:**
  - the on/off switch, with the key's name;
  - the output: the stream window chosen; Spout2 greyed, saying "Windows only" on a Mac and "next build" on Windows;
  - the frame rate: Match, 60 or 30;
  - a live preview of the stream picture;
  - the OBS line;
  - a tile per mod with "n of m hidden" (teal when any) and its first two items;
  - "Other mods' menus" as one switch.
- **A mod's page:** each item with its words and a HIDDEN or SHOWN switch (click the row), gold lines where a mod's update is needed, HIDE ALL, SHOW ALL, BACK (or Backspace), and the mouse wheel to scroll.
- ✕ or Esc goes back to where it was opened.

**How it is built.**
- `streamer/StreamerCatalog` is the known mods and items: Minecraft, JourneyMap, BSP-Teleport, BSP-Core and BSP Region Guard (each shown only when installed), their language keys, and the first switch-on defaults.
- Each mod's page adds every other HUD part it registers with Forge (`GuiOverlayManager.getOverlays()`), named from its path. "Other mods" lists every other mod's parts under the mod's name.
- `StreamerRules.menuHidden`: Streamer Mode's own menu is always a card in the stream. A menu is also a card when hidden by class or package, or, with "Other mods' menus" on, when it is not Minecraft's, Forge's or Mojang's.
- The settings are in `[streamer]` of the client config (`output`, `frameRate`, `hidden`), saved at each change.
- The box is 440 x 242 and made smaller to fit a narrower window (the owner's GUI scale 5 is about 409 wide).
- The key still switches Streamer Mode on and off directly.

GameTests **296/296**: 7 in `StreamerGameTests`, 2 of them new:
- the own menu is always hidden; other mods' menus are hidden only with the switch on, never Minecraft's, Forge's or Mojang's;
- the catalog lists each item once, every tile and item has its words in the language file, every default is an item that can be switched back, and parts with no words are named from their path.

The run included another session's work in the same folder (BSP-Core's player reset).

**Untested in a client.** In the Streamer Mode instance with the new `bsp-server-utilities-0.22.0.jar`:
1. **Opening.** The eye key on the title screen and the pause screen opens the menu; so does `/streamer` in game. At GUI scale 5 it fits the window, smaller, with nothing cut off.
2. **The switch.** ON opens BSP Stream; the preview then shows the stream picture live (the world without F3 or the minimap). OFF closes it, and the preview says Streamer Mode is off. The key's name shows beside it once set in Controls.
3. **Output and frame rate.** Hovering Spout2 says why it is greyed. Choosing 60 or 30 lowers the cost in the log's "Streamer Mode: stream picture ..." line against Match.
4. **Tiles.** Minecraft, JourneyMap, BSP-Teleport, BSP-Core, BSP Region Guard and Other mods, each with its count. Other mods lists the pack's other HUD parts by mod (Botania, Create, Mekanism and more).
5. **A mod's page.** Clicking a row switches it: Hidden goes from BSP Stream at once, Shown comes back. HIDE ALL and SHOW ALL. The BSP-Teleport rows have the gold line. The wheel scrolls a long page. BACK and Backspace return.
6. **Kept.** Quit and start the game again: the choices, the output and the frame rate are as left (`config/bsp_server_utilities-client.toml`, `[streamer]`).
7. **Other mods' menus.** Switch it on and open a Mekanism or Create machine: BSP Stream shows the "Menu hidden" card. The inventory and Minecraft's own menus still show.
8. **In the stream.** With the menu open, BSP Stream shows the "Menu hidden" card, not the menu.
9. **Not yet:** chat lines with coordinates only, BSP-Teleport's coordinates only (request SU-TP2), and Spout2 come with SU-133 and SU-134.

**Owner's test (2026-11-24):** steps 1 to 8 passed: the menu opens and fits; the switch and the live preview; switching items changes BSP Stream at once ("from all I was able to check"; the code to be audited in SU-135); the choices are kept after a restart; 60 and 30 lower the cost; Other mods' menus shows the card for a machine; the menu itself is a card in the stream.

## 2026-11-24: JourneyMap's minimap without its coordinates (SU-136)

**What it does.** The owner asked for a JourneyMap option that keeps the minimap in the stream but hides the coordinate lines under it (D715). JourneyMap's page has a new item, "Minimap coordinates", hidden at first.
- With "Minimap" shown and "Minimap coordinates" hidden, BSP Stream shows the map but not its location line or its region line.
- Its other lines stay: biome, time, day, light level, weather, speed, and so on.

**How it is built.**
- JourneyMap 6.0.7 draws each line over and under the minimap through `journeymap.client.ui.minimap.LabelVars.draw(GuiGraphics, MultiBufferSource, Component)`, called from `DisplayVars.drawInfoLabels` (checked with `javap`).
- `mixin/JourneyMapLabelMixin` (`@Pseudo`, nothing without JourneyMap) skips such a line in the stream picture when it holds coordinates.
- What counts as coordinates is `StreamerRules.hasCoordinates`:
  - two numbers with a comma between, with an axis label or a "(light)" allowed;
  - or two numbers each after an axis.
- That covers every form of JourneyMap's location line (`jm.common.location_*`: `x: 1, y: 2, z: 3`, `1, 2, 3`, `1, 2 (3), 4`, `1, 3`) and its region line (`Region: x:0 z:-1`).

GameTests **297/297** (8 in `StreamerGameTests`; new: those coordinate forms match, and times, days, light, speed, biome, fps, moon, weather and dimension lines do not). The first run failed only `the_board_is_read_by_everyone_and_changed_by_those_who_may`, which waits for an Action Log line, and it passed on the second. That is the same kind of flake as the bank test in SU-131 and not this change's.

**Untested in a client.** In the Streamer Mode instance with the new jar:
1. Turn Streamer Mode on. On JourneyMap's page, set "Minimap" to SHOWN and keep "Minimap coordinates" HIDDEN.
2. In JourneyMap's minimap options, show the location line, the region line, the biome and the time under the map.
3. BSP Stream shows the minimap, the biome and the time, but not the location or region lines. The game window shows them all.
4. Switch the location line between JourneyMap's plain and verbose forms (and with or without y): hidden in each.
5. Set "Minimap coordinates" to SHOWN: the lines come back in BSP Stream.

**Owner's test (2026-11-24):** passed: "The minimap coordinates are hidden, and work as intended".

## 2026-11-24: Streamer Mode, chat coordinates and the API for other mods (SU-133)

**What it does.**
- **Chat.** Minecraft's page has "Chat: lines with coordinates", hidden at first (D716).
  - The stream leaves out every chat message that holds a position, whole however it wraps.
  - When the chat screen is open and the text being typed holds a position, the stream's input box is left empty.
  - With "Chat" hidden, the input box is always empty in the stream.
  - What counts as a position (`StreamerRules.hasCoordinates`): two numbers with a comma between, two numbers each after an axis, or now also three numbers with only spaces between (`/tp 100 64 -200`).
- **The API.** `api.StreamerMode.hidesNow(String id)` (docs/API.md), for request SU-TP2:
  - BSP-Teleport's items are now "Teleport band: coordinates" (`bsp_teleport:band_coordinates`) and "Portal screen: position" (`bsp_teleport:portal_coordinates`).
  - Until BSP-Teleport asks about an item, its whole menu is hidden in its place, as before.
  - Once it asks, the menu shows and BSP-Teleport hides only the numbers, and the gold line on its page goes.
  - Settings saved by earlier builds with the whole-menu entries are read as the new items.

**How it is built.**
- `mixin/ChatComponentStreamMixin`: during the stream picture only, chat draws a copy of its lines without the left-out messages (`StreamerRules.keptChatLines`). Its own lines go back as soon as it has drawn, and `StreamerMode` puts them back even if drawing stopped half way (`client/StreamChatAccess`).
- `mixin/ChatScreenStreamMixin` skips the input box in the stream picture.
- `StreamerCatalog.Item.fallbackScreen` and `StreamerRules.menuHidden(hidden, handled, class)`.
- `client/StreamerMode.askedHidingNow` remembers which items a mod handles itself.

GameTests **299/299** on two runs in a row (10 in `StreamerGameTests`):
- the band and portal screen are hidden whole until BSP-Teleport asks, each on its own;
- earlier saved entries are migrated;
- chat positions in Minecraft's teleport message, a typed `/tp` and a player's words; not in times, counts or "Day 4 of 30";
- a message wrapped over three lines is left out whole.

The first run failed only the two flaky Action Log tests (bank and board), together; they passed on the next two runs. A separate session is proposed for them.

**Untested in a client.** In the Streamer Mode instance with the new jar:
1. Turn Streamer Mode on. In chat, say "my base is at 100 64 -200", and "come to 1200, -340" in a message long enough to wrap. Neither shows in BSP Stream; both show in the game window. A plain message shows in both.
2. Teleport yourself (`/tp ~10 ~ ~`): Minecraft's "Teleported ... to x, y, z" is missing from BSP Stream.
3. Open chat and type `/tp 100 64 -200`: BSP Stream's input box stays empty while it holds the numbers; typing plain words shows them.
4. Set "Chat: lines with coordinates" to SHOWN: the messages come back in BSP Stream.
5. The Teleport band (with BSP-Teleport before 3.9.2; for 3.9.2 see "BSP-Teleport 3.9.2 hides its own coordinates" below): still a "Menu hidden" card in BSP Stream, and its page in the menu has the gold line. Set "Teleport band: coordinates" to SHOWN: the band shows in BSP Stream.
6. If the settings file from the earlier test had BSP-Teleport's band hidden, its page now shows "Teleport band: coordinates" as HIDDEN.

**Owner's test (2026-11-24):** passed: "The chat coordinates are hidden and work as intended".

## 2026-11-24: Streamer Mode's Spout2 output (SU-134)

**What it does.** On 64-bit Windows (Intel or AMD), the menu's output can be **Spout2** (D717). There is no window: the stream picture is sent as the Spout sender "BSP Stream", which OBS takes with Off-World-Live's Spout2 plugin (Sources › Spout2 Capture › BSP Stream).
- **Swapping:** choosing it while Streamer Mode is on swaps over at once, and choosing Stream window swaps back.
- **When Spout2 fails:** if it cannot start, or Spout refuses a frame, the stream window opens instead and chat says why.
- **Elsewhere:** on a Mac, Linux or Windows on ARM, the Spout2 key stays greyed and says why.

**How it is built.**
- **The library:** Spout's `SpoutLibrary.dll` from Spout SDK 2.007.018 (github.com/leadedge/Spout2 release `Spout-SDK-binaries_2-007-018.zip`, sha256 of the zip 6b6101ad…27ea; the 64-bit statically linked "MT" build, 483,328 bytes, sha256 c76ac338…d923, needing only Windows' own libraries). It ships unchanged at `src/main/resources/natives/windows-x64/` with its BSD-2-Clause notice (`SpoutLibrary-LICENSE.txt`).
- **Loading:** `client/SpoutOutput` copies it to `<game folder>/bsp_server_utilities/natives/SpoutLibrary-2.007.018.dll`, checked against its SHA-256 every time, and loads it with JNA, which Minecraft carries. It calls `GetSpout()`, then the Spout object's methods through its method table: `SetSenderName` (slot 0), `ReleaseSender` (2), `SendTexture` (4) and `IsInitialized` (6), the slots declared before the first overloaded name in `SpoutLibrary.h`, so the compiler's grouping of overloads cannot move them.
- **Each stream frame:** `SendTexture(picture, GL_TEXTURE_2D, w, h, invert, main framebuffer)` on the render thread. The OpenGL bindings Spout may touch are read before and put back after.

GameTests **300/300** on two runs (12 in `StreamerGameTests`; new: where Spout2 may run, and the jar's library being Spout 2.007.018's unchanged 64-bit file with its licence notice).

**Untested anywhere.** It needs a Windows PC: 64-bit, Intel or AMD, with OBS 30 or newer and Off-World-Live's Spout2 plugin for that OBS version (github.com/Off-World-Live/obs-spout2-plugin/releases).
1. **Install** the pack with this jar. In OBS, check Sources › + has "Spout2 Capture".
2. **Choose Spout2.** In game, Esc › eye key › Output: SPOUT2 · WINDOWS (teal), then ON. Chat says "sending over Spout2 as BSP Stream"; no BSP Stream window opens. The menu's line says "Sending over Spout2".
3. **Add it in OBS:** Sources › + › Spout2 Capture › Sender: BSP Stream. It shows the game without F3 or the minimap, live.
4. **Hiding:** open JourneyMap's full-screen map: the "Menu hidden" card in OBS. Switch an item in the menu: OBS changes at once.
5. **Swapping:** choose STREAM WINDOW while on: the BSP Stream window opens and the Spout2 source in OBS freezes or goes empty. Choose SPOUT2 again: the window closes and OBS's source is live again.
6. **OFF:** OBS's source goes empty.
7. **The cost.** Note the F3 frame rate off and on, and the log's "Streamer Mode: stream picture ... ms, stream window ... ms" line. With Spout2 the second figure is Spout's send.
8. **The log:** "Streamer Mode: Spout 2.007.018 loaded; sending as "BSP Stream"", and no error. The game folder has `bsp_server_utilities/natives/SpoutLibrary-2.007.018.dll`.
9. **On a laptop with two graphics cards:** if OBS shows black, set OBS and Java (Minecraft) to the same card in Windows' Graphics settings.
10. **If it fails,** chat says "Spout2 could not start (...)" and the stream window opens instead. Send the log's lines from "Streamer Mode" and "Spout2".
11. **On the Mac,** the SPOUT2 key stays greyed with "Spout2 works on 64-bit Windows (Intel or AMD) only".

## 2026-11-24: audit of Streamer Mode, and its fixes (SU-135)

Two fresh, independent read-only reviews of every Streamer Mode file: one of the drawing, OpenGL, window, Spout and chat hooks, one of the rules, menu, settings, API and adapters. Each finding was checked against the code (and Minecraft's bytecode with `javap`). Fixed:
1. **Command suggestions** showed the block the player looks at (`/tp ` offers its position), and a command typed in part ("/tp 100 64") showed. Now, while chat or its coordinates are hidden, the stream leaves out:
   - the suggestion list and usage lines (`mixin/CommandSuggestionsStreamMixin`);
   - the typed text when it is a command or holds a position (`StreamerRules.hideTyping`, with the grey suggestion in the box);
   - chat's hover text and tags (`ChatComponentStreamMixin` returns none during the stream pass).

   The chat screen hook is now plain `@Inject`s, which hide the box for that one draw, so another chat mod's `@Redirect` cannot clash with it.
2. **Positions written other ways** were missed. The rule now also catches:
   - "/locate"'s `[1234, ~, -560]`;
   - `x=100, z=-200` and `X 100 Y 64 Z -200`;
   - `100/64/-200` and `XYZ: 100.5 / 64 / -200.5`;
   - `~` as a number.

   "1,000 coins" no longer counts.
3. **Menus that draw positions** were not listed. They are added and hidden at first:
   - this mod's admin screen (a new "BSP-Server Utilities" tile);
   - BSP-Teleport's admin screen;
   - BSP-Core's admin screen and Accelerator screen;
   - Region Guard's region screen.
4. **New defaults never reached existing players** (Forge writes the list once). A new setting `appliedDefaults` lists the defaults already applied; any other is added once (`StreamerCatalog.applyDefaults`).
5. **Menus ran twice as fast** in the player's window with Streamer Mode on: the title panorama and the credits move by the frame time. The stream pass gives menus no frame time.
6. **After a resize or F11** the stream window could keep showing the old picture: the game may give the new texture the old one's name. The picture is attached afresh every frame.
7. **F1:** the stream drew HUD layers F1 had hidden. It now follows Minecraft's own rule.
8. **A failure during the stream pass** now ends it after chat is whole again and the frame is drawn (`mc.tell`), not inside the drawing.
9. **Stencil:** the stream picture gets a stencil when the game's has one. **Alpha:** the picture's alpha is made solid after drawing, so Spout2 never passes on see-through areas.
10. **Smaller fixes:**
    - a failed swap of output while on now takes the whole off path;
    - BSP-Teleport's gold line goes as soon as it hides its numbers itself;
    - FancyMenu is asked again 30 s after a failed read;
    - JourneyMap's hooks name their methods with full signatures;
    - `tools/check_refmap.py` skips only `@Pseudo` mixins that set `remap = false`.

**Not changed:**
- The rules review thought JourneyMap's `journeymap:waypoint_icons` might not exist. JourneyMap 6.0.7 does register that HUD part (`registerBelow(..., "waypoint_icons", ...)` drawing its waypoint decorations, checked with `javap`), so hiding it works.
- The totem animation and the nausea warp are drawn before the stream pass's point in the frame, so they are missing from the stream: cosmetic, left.
- "Score 3, 2" still counts as a position: rare, and leaving it out is the safe side.

GameTests **302/302** on two runs (14 in `StreamerGameTests`; new: the added position forms and "1,000" not counting, what is typed, every BSP menu that draws positions hidden at first, and applying new defaults once without re-hiding what a player switched off).

**To check in game** with the new jar (a recheck of what changed; the earlier steps passed):
1. **Suggestions:** Streamer Mode on, chat open. Look at a block and type `/tp `. The game window offers its position; BSP Stream shows neither the box's text nor the suggestions.
2. **Hover text:** hover a chat message with a hover text. Nothing in BSP Stream.
3. **The panorama:** on the title screen with Streamer Mode on, it turns at its usual speed.
4. **Resizing:** resize the game window and press F11. BSP Stream follows at once and never freezes on an old picture.
5. **F1:** in game, the HUD goes from both windows.
6. **New menus:** with an admin account, open `/su`: BSP Stream shows the "Menu hidden" card. The menu's new "BSP-Server Utilities" tile lists it.
7. **Your earlier choices:** the newly listed menus are HIDDEN once; anything you set SHOWN before stays SHOWN unless it is one of those.

**Owner's test (2026-11-24):** passed: "The audit fixes work".

## 2026-11-24: the bank and bounty panel tests that failed now and then

**Cause:** the test world under `run/` persists, and its action log is full: 5000 entries (`LogData.MAX`), about two days of runs. A new entry then pushes out the oldest one. `WebBankGameTests` and `WebBountyGameTests` counted the bank or bounty lines before and after an action and expected one more. When the line pushed out was itself a bank or bounty line from an earlier run, the count stayed the same, although the new line was written. Which line was oldest changed from run to run, so a different test failed each time. Nothing is written later or asynchronously: `ActionLog.record` adds to the world's copy at once, and the tests run in one tick.

**Fix (tests only):** `gametest/LogWatch` remembers every entry's uid at a point and returns the entries added since. The bank, bounty and setup-guide checks use it in place of a count. `LogGameTests.a_new_entry_in_a_full_log_is_seen_although_the_oldest_goes` fills the log to its cap with a bank line oldest, shows that the old count misses the new line, and shows that the watch sees it. The world's entries are put back in the same tick.

**Other tests that read the log**, checked: the rest look for a line by its text or a marker among the newest, or take the newest of a kind. A full log only drops the oldest, so they are not affected.

GameTests **303/303** on five runs in a row.

**Audit of the fix (same day):**
- The setup guide's "the action log off: nothing recorded" check counted every line on every server. On a full log that count can never change, so it passed whatever happened. It now asks the watch for any new line of any kind from any server (`LogWatch.added(server)`).
- The lottery's "a refused draw is not in the Action Log" counted draws among the newest 200 lines. A wrongly logged draw would push one line out of that window, which could be an old draw, so the count could stay the same and the check pass. It now uses the watch too.
- The new full-log test puts the world's entries back in time order rather than the order they were added: the same entries, and the oldest is still pushed out first.

GameTests **303/303** on three more runs in a row after the audit.

## 2026-11-24: BSP-Teleport 3.9.2 hides its own coordinates (request SU-TP2 answered)

**What changed here:** wording only, no code. BSP-Teleport 3.9.2 asks `StreamerMode.hidesNow` at the top of every draw of the band (`bsp_teleport:band_coordinates`), the portal screen and the portal chooser (both `bsp_teleport:portal_coordinates`). Checked against its code: `compat/ServerUtilities` (looked up once by class name, false on any failure), `client/BandScreen`, `client/PortalScreen`, `client/PortalChooserScreen`. BSP-Teleport's admin screen (`AdminScreen`, portal rows with x, y, z) does not ask and needs no id: the catalog hides it whole as its own item, "Teleport admin screen", hidden by default since the audit of SU-135 (BSP-Teleport's handoff says otherwise; it is mistaken there).
- Menu lines (from `tools/gen_lang.py`): the band item reads "The x y z under homes, warps and allies", the portal item "The x, y, z on the portal screen and its chooser", and the gold line "Before BSP-Teleport 3.9.2, the whole menu is hidden; from 3.9.2, only the numbers". All three fit the menu's row.
- `docs/API.md` "Ids so far" names allies and the chooser, and 3.9.2 as the first version that asks.

**Untested in a client.** With BSP-Teleport 3.9.2 and Streamer Mode on (BSP-Teleport's own steps are in its `docs/TESTING.md`, "Coordinates hidden from a stream", steps 1 to 7):
1. Open the Teleport band. BSP Stream may show the "Menu hidden" card for its first frame only. After that it shows the band, with `• • •` in place of the x, y, z of homes, warps and the TEAM tab's allies. Server and world names stay.
2. In the Streamer Mode menu, BSP-Teleport's page: the gold line has gone from "Teleport band: coordinates". "Portal screen: position" keeps its gold line until a portal screen has been opened once.
3. Open a portal's screen, then step into a portal with several destinations: both show `• • •` in BSP Stream, and the numbers in the game window.
4. Set both items to SHOWN: the numbers appear in BSP Stream.
5. Restart the game: the gold lines are back until each screen is opened once (the menu remembers this only while the game runs).

## 2026-11-24: tutorials, the definitions and their shared table (SU-137)

The first part of the tutorial builder (owner decisions D719 to D744; the screens are `tools/preview/tutorial_refined.html`). Nothing a player or an admin can see yet: this keeps the tutorials, cuts what they hold to its limits, and shares them across the network.

**What is kept** (`tutorial/Tutorial`, `TutorialPopup`, `TutorialText`):
- Each tutorial has a name, how it starts (first join, an area, or `/tutorial` only), who the first-join one is offered to, a start area, the offer's text, its points and the links between them. It has a draft and a published copy (`TutorialStage`).
- Each point has a place (server, world, block, radius 1 to 8) and a popup. A popup is full (12 × 8 grid) or small (6 × 4, placed in thousandths of the screen, at least 80 × 30 of 480 × 270). It either freezes the player, with up to four buttons, or keeps them walking, with no buttons and 2 to 60 seconds on screen. It holds up to twelve boxes of text or a picture.
- Text is paragraphs of runs. Bold, italic, underline, strikethrough, obfuscated and the colour can change mid-line; size and alignment are per paragraph. A box holds up to 1000 letters and 16 paragraphs.
- A link drops its path unless both points are in the same world. A path holds at most 500 positions. Links that loop back are never kept.

**Where it is kept:**
- The world's `bsp_server_utilities_tutorials`.
- With shared storage, the table `<prefix>tutorials`: one row per tutorial and stage, with the copy as SNBT in `body`. A row is replaced only by a newer revision. A deletion is a copy marked deleted, so it wins the same way. A notice `tutorial` names the stage, the id and the revision; the other servers read that row back. At start, a server takes the table's newer copies and writes back its own newer ones. A write the table refused is tried again every minute.

**Rules (`TutorialService`):**
- Only operators (level 2) and BSP-Core admins create, edit, publish or delete.
- PUBLISH copies the draft; later edits stay in the draft.
- One published tutorial starts on first join. Publishing another takes it over; a draft set to first join takes it from the other drafts.
- `Tutorial.issues()` lists what the builder will mark: no points, no start or several, a fork without a GO TO BRANCH for each branch, a fork that keeps the player walking, a freezing popup with no way on, a path not drawn, an area start without an area.

**GameTests (`TutorialGameTests`):**
- `tutorials_are_kept_within_their_limits`
- `a_tutorial_reads_back_as_it_was_saved`
- `the_builder_marks_what_is_missing`
- `drafts_change_until_published_and_one_starts_on_first_join`
- `tutorials_are_one_set_for_the_network_and_the_newest_wins`

**Untested against a real MySQL:** the table is made at the next start, and the `ON DUPLICATE KEY UPDATE` keeps a row unless the revision is newer. Check once on the network with two servers: create a tutorial with `/su tutorial` (SU-142), and see it on the other server.

## 2026-11-24: tutorials in play: the offer, the flow, the popups, the path (SU-138 to SU-141)

**Server rules, GameTested** (`TutorialRunGameTests`, 5 tests):
- The first-join offer comes only after the rules popup. It goes to new players (first join from `Tutorial.since`, or not on the counter's list), or to everyone not finished.
- LATER asks again at the next login; NO THANKS never; a tutorial under way resumes at login.
- Start areas: walking in offers; LATER asks again on the next walk-in; NO THANKS never.
- The buttons: NEXT, CLOSE, GO TO BRANCH, PREVIOUS, END, and the last NEXT finishing.
- A popup shows within the radius; the guide carries the link's walked path; TAKE ME THERE goes through BSP-Teleport.
- A walking popup moves on when WALKED arrives; a removed stop ends the tutorial; one tutorial at a time.
- Places are kept in `<prefix>tutorial_progress` and resumed on another server.

**In game (the owner):** a test tutorial with three stops is enough. Make it in the builder (next section) and PUBLISH it as the first-join tutorial.
1. **First join.** Join with a player that is new to the network. After the rules popup, the offer card shows the tutorial's name and text. LATER, then log out and in: offered again. NO THANKS: the chat line, and no more offers.
2. **`/tutorial`.** It lists the tutorial with START. START: a full popup with the name, "Stop 1 of 3" and the thin line. END TUTORIAL reads SURE?, and a second press ends it. Escape hides the popup; `/tutorial resume` shows it again.
3. **Walking to a stop.** NEXT to a stop with a place and a walked path: gold dashes about a block up, faint through walls, fading with distance. A translucent sign on a post names the stop. Within 2 blocks its popup opens.
4. **Walking popups.** A small popup that keeps the player walking: it shows where it was placed, its bar empties, it fades, and the next stop follows.
5. **Forks.** A fork's GO TO BRANCH keys go their own ways; PREVIOUS goes back.
6. **Other servers.** A stop on another server reads TAKE ME THERE › SERVER. It moves the player, and the tutorial carries on there. Without BSP-Teleport the key is off, with a gold line naming the server.
7. **Pictures.** A picture box loads a file from `config/bsp_server_utilities/tutorial_images/` and a web address; it shows "Picture" until it arrives.
8. **Shader packs.** With a shader pack (Iris or Oculus), the dashes and the sign still show.

## 2026-11-24: the tutorial builder, the popup editor, paths and areas (SU-142 to SU-145)

**Server rules, GameTested** (`TutorialAdminGameTests`, 3 tests):
- Only those who may edit get the view or change anything. The view arrives in pieces that read back whole, under a client's 32 KB per piece.
- SET TO HERE stores this server, this world and the block the admin stands in.
- A saved draft keeps the server's places (only the radius is the builder's), the server's paths and the server's area (clearing is allowed).
- Removing a stop, publishing, deleting, drawing a path and setting an area are in the Action Log under "tutorial".
- Path mode: a point each time the admin is 3 blocks from the last, flying too; UNDO, pause and resume; asked in the sphere; KEEP WALKING asks again only after leaving it; FINISH PATH saves the path ending at the stop; Delete saves nothing.
- Area mode: corner 1, corner 2, UNDO, SAVE AREA only with both corners.

**In game (the owner, as an operator):**
1. **The tab.** `/su`: the TUTORIAL tab, the twelfth, lists the tutorials. OPEN BUILDER (or `/su tutorial`) opens the builder over the whole screen. The setup guide's Tutorials page shows this tab.
2. **The builder.** Tabs, +, the draft state, TEST and PUBLISH. + POINT adds a node. Drag to move it, right-drag onto another to link, drag the canvas to pan, scroll to zoom. Hold an item and click the drawer's icon to use it.
3. **Forks.** A second link from one stop makes a fork: GO TO BRANCH keys appear on its popup.
4. **Missing parts.** The red dots match what is missing. PUBLISH with something missing reads "n missing: publish?" and publishes on the second press.
5. **The popup editor.** EDIT POPUP, or a double-click on a node. Drag over empty cells to make a box, drag it to move, drag its corner to size.
6. **Text.** Type; select a word and press B, a colour and L: only that word changes, and L applies to its paragraph. Ctrl (Cmd) B, I, U, A, C, X, V work.
7. **Pictures.** + IMAGE: pick a file from the list or type a web address; the preview shows it when loaded, or the reason it failed.
8. **Small popups.** SMALL: the grid becomes 6 × 4 and the preview the whole screen. Drag the popup anywhere and size it by its corner; it snaps to the edges and centre lines. KEEPS WALKING shows the time on screen.
9. **Buttons.** + BUTTON, then the label, colour and job. GO TO BRANCH offers the fork's branches; OPEN LINK takes an address and refuses one that is not https.
10. **Paths.** SET TO HERE on two stops; pick the link; DRAW PATH moves you to the first. G starts the yellow line, with marks every 3 blocks. The next stop shows as a sphere with its name. Inside it the card shows FINISH PATH, KEEP WALKING and UNDO LAST, and the screen's edge glows. FINISH PATH: the link turns gold in the builder.
11. **Areas.** Tutorial settings, then start "Area", then SET AREA. G at one corner, then G at the other: the violet box, and a card with SAVE AREA.
12. **Keys.** Controls › Key Binds › BSP-Server Utilities lists the three tutorial keys. They do nothing outside a mode.
13. **Streamer Mode.** The builder shows "Menu hidden" in BSP Stream (its item is hidden by default).
14. **Two servers.** Edit on one: the other's builder shows the change. A player's place carries over.

**Not tested anywhere:** real MySQL for `<prefix>tutorials` and `<prefix>tutorial_progress`, every screen in a client, BSP-Teleport's TAKE ME THERE.

## 2026-11-24: audit of tutorials, and its fixes (SU-146)

Two independent reviews read the code: the server side and the client side. 24 findings: 23 fixed, 1 left (below). GameTests **317/317**.

**Server:**
- A respawn's new player entity now carries the tutorial (`TutorialRunner.rebind` on `PlayerEvent.Clone`).
- A tutorial deleted while players are partway ends for them at once, with a line; their offers are free again.
- A republish shows players partway the new version at once, so a button pressed is the button seen.
- A retried write sends its notice once it lands, so the other servers catch up (GameTested).
- A next stop in another world reads TAKE ME THERE and goes through BSP-Teleport, whose checks apply. Only admins (GO, DRAW PATH) move directly. Arriving in that world sends the way there.
- Tutorials turned off: nothing starts, resumes or answers. A player held by the rules cannot press buttons.
- "New players": with the player counter off, nobody is known, so nobody counts as new. Before, everyone counted as new.
- "Everyone not finished" offers again to those who ended early.
- The database down at login, or when the builder opens: this world's copy is used.
- The builder's uploads unpack to at most 16 MB.
- Progress times keep rising for a player whatever the servers' clocks say.
- FINISH PATH only inside the next stop's sphere.
- A walk into a start area while another offer waits is counted later, not lost.
- A draft whose text would pass 3 MB is not saved (the admin is told).

**Client:**
- **Focus.** Fields made again (picking a box, deleting one, a button moved, the server's reply) no longer keep taking keys. Before, they could crash the editor or write into the wrong box. The builder updates its fields in place, and ignores a view sent before its last save reached the server.
- **Unsaved work.** The editor and the builder save their changes when another screen takes their place.
- **Keys.** G, Z and Delete presses outside a mode are dropped instead of piling up. The finish or area card waits for an open chat box to close.
- **Pictures.** In the editor they are matched by picture and box size, not list position. Choosing a file, a fit or a web address saves the popup, so the preview loads it. SHOW AS PLAYER shows them. Pictures in a walking popup fade with it, and the card fades once, not twice.
- **Preview.** It follows the typing.
- **Hidden popups.** One hidden with Escape stays hidden (the server is told) until `/tutorial resume`.
- **TUTORIAL tab.** PUBLISH on a changed row; the picked tutorial's counts; the server stops sending views when the tab closes.
- **Links.** A link's handle off the canvas takes no clicks.
- **Text editor.** It works on fresh lines before each key.

**Left:** the respawn rebind is not GameTested: a mock player cannot respawn.

## 2026-11-24: second audit of tutorials: the whole feature and the screens' layout (SU-146)

Two more independent reviews, at the owner's request. GameTests **318/318** (one new: `TutorialRunGameTests.second_audit_rules`; `TutorialAdminGameTests` gains the refused stale save). All 38 findings are fixed.

**Layout (24 findings).** Positions were computed with Minecraft's real glyph widths at GUI 427 × 240, 480 × 270, 640 × 360 and 960 × 540.
- **Admin panel.** The tab row with twelve tabs left no room for the title and drew a tiny upside-down "SU" over RULES. Tabs are now 0.55 and measured bold; the title is left out when it does not fit.
- **Bold text** was measured plain everywhere (1 px a letter short). `Ui.widthBold` and `Ui.fitBold` now measure it: keys, titles, the offer's name, tabs, the HUD strip.
- **The popup editor:**
  - The button editor ran below the screen at 480 × 270: the preview shrinks while a button is picked.
  - The link box sat over the job keys.
  - The text toolbar ran into the preview and the colour box: it is two rows now.
  - The settings row ran off the panel in Keeps walking: BACKGROUND wraps.
  - The picture's error line sat inside the file list.
  - The button list ran past the bottom at 427.
  - Branch keys did not wrap.
  - The labels touched the keys.
  - SHOW AS PLAYER's line covered a small popup at the bottom.
- **The builder:**
  - In the settings drawer, DELETE TUTORIAL was under the offer box, and the counts crossed the bottom.
  - The start, audience and point keys ran out of the drawer (they wrap).
  - The links and buttons lists had no end ("+N" now).
  - The offer box's scroll bar sat outside it.
  - The foot's hint and legend were cut (two lines).
  - Node icons drew over the zoom keys.
  - The picked tab could be hidden.
  - The tabs jumped when PUBLISH asked.
- **Popups:**
  - The keys overlapped (END and the rest) and could leave a small card: they are laid out to fit, and the preview uses the same code.
  - A small freezing popup with buttons is at least 60 px tall.
  - The "continue on" line has its own strip.
- **Others:**
  - The text box's hint wraps.
  - The TUTORIAL tab scrolls.
  - On the setup guide's Tutorials page, OPEN BUILDER was outside the guide's clip and could not be pressed.
  - The path strip sits under any boss bars.

**Behaviour (14 findings):**
- **The builder:**
  - It dropped server views that came within 1.5 s of a save, so SET TO HERE could be undone by the next save. It reads them later, and always takes places, paths and the area from the server.
  - Two admins: a save based on a draft another admin has saved since is refused with a chat line.
  - New points take ids above the published copy's, so a removed stop's id is never reused.
- **Other servers and worlds:**
  - Without BSP-Teleport, a button to a stop on another server was off, so players were stuck. It stays on and the chat says where to go.
  - A fading popup never moves anyone; TAKE ME THERE is a press.
- **Offers and popups:**
  - A tutorial with no points is never offered.
  - The tab's PUBLISH asks SURE? when something is missing.
  - A popup hidden with Escape stays hidden on a republish, and is not marked hidden when a new copy replaces it on screen.
  - A refused press shows the popup again.
  - An answer ignored while the rules hold the player no longer blocks later offers.
  - A first-join offer behind an area offer comes after it.
- **Progress and data:**
  - Places the table did not take are kept marked in the world and written again every minute and at start.
  - Deleting marks both copies, so a publish racing a delete on another server cannot survive it.
  - FINISH PATH keeps the stop as the last point at 500 points, and says so when the link has gone.

**Still to check in game:** every screen at the owner's GUI scale 5, and at 1280 × 720.

## 2026-11-24: `/su chunks`, this server's loaded chunks (SU-147)

GameTests (`ChunkCensusGameTests`, 7): a mod's ticket loads its chunk and the two beside it, all put down to the ticket, with the held chunk and the one beside it ticking and the one two away not; entities (players not counted) and block entities counted per chunk; the busiest list orders by entities, then block entities, leaves out empty chunks and stops at five; a line runs `/su chunks tp`; the safe-place rule (ground below, room for a head, no lava or magma); the teleport lands in the chunk on a safe block and is in the Action Log as `chunk_tp`, once. **Untested in a client and on Mohist.**

In game, as an operator:
- [ ] `/su chunks` alone: a gold total line, a line per dimension with "Loaded by:" under it, "Mods' tickets", the busiest five in aqua, and a grey note. Numbers look right next to F3's chunk count (F3 counts only what this client sees, so expect fewer).
- [ ] Hover a busiest line: "Go to a safe place in this chunk, as near its middle as there is one". Click it: you land on solid ground in that chunk, and a chat line says where.
- [ ] `/su chunks tp minecraft:the_nether <x> <z>` on a chunk that is loaded: you land under the roof, not on the bedrock above it.
- [ ] Stand in the End's void, far from islands (load one empty chunk), and `/su chunks tp minecraft:the_end <that chunk>`: "No safe place to stand", and you do not move.
- [ ] `/su chunks tp minecraft:overworld 50000 50000` (not loaded): "is not loaded", you do not move, and no new land is made there (F3 or a map shows nothing).
- [ ] An ocean chunk from the list: you land on the water's surface, not in a cave under the sea floor.
- [ ] Action Log tab: the teleports show under "Chunk teleports" and not again under "Commands"; `/su chunks` itself shows under "Commands".
- [ ] A player who is not an operator or admin: `/su chunks` is not offered.
- [ ] From the console: `/su chunks` prints the readout (the lines are not clickable there).
- [ ] On a Mohist backend: `/su chunks` works (it reads the chunk map through a mixin accessor).

## 2026-11-24: audit of `/su chunks`, and its fixes (SU-147)

An independent review read the code: 5 bugs and 7 smaller points. All 5 bugs are fixed, plus 4 of the smaller points; 3 are left (below). GameTests **324/324**.

- A typed `/su chunks tp` could load and generate any chunk, freezing the tick while it was built, even past the world border. It now goes only to a loaded chunk inside the border; the coordinates are limited to ±1,875,000 (GameTested: refused, nothing loaded, nothing logged).
- The ticking count used the chunk's load level, which counts the whole view distance. It now asks whether the chunk's blocks tick, which follows the simulation distance.
- The teleport test could pass on an entry left by an earlier run. It now runs once after the chunk loads and counts this run's entries by the player's id: exactly one.
- A move between worlds stopped by another mod or a plugin was logged and reported as done. It is now checked, and reads "was stopped".
- Water or lava on top sent the admin down to a cave below. The surface is tried first in every column (dry ground within three blocks of the top), then the top of water, and only then lower down. Portal blocks and cobwebs are not safe.
- Spectators count as loading chunks only when the `spectatorsGenerateChunks` game rule is on, as in Minecraft.
- A test ticket left by a timed-out run is released at the start of the next one.
- Wording: "held by mekanism's ticket"; the hover text no longer promises the exact middle.

Left:
- On Mohist, Bukkit keeps every world's spawn area loaded, and plugins' chunks and spigot.yml's per-world view distance are not seen, so those chunks read as "other". To check in game.
- `/execute ... run su chunks tp ...` is logged twice (as a command and as a chunk teleport), as with the other self-logged `/su` commands.
- No plural forms: "1 entities".

## 2026-11-24: the staff screen almost full screen (SU-154)

Look BB B (D754 to D757), `tools/preview/staff_fullscreen_refined.html`. Protocol **13** (the counts packet). GameTests (`AdminCountsGameTests`, 3): this server, its players and today's refusals as their sources say; a chunk of 5 mobs over a number of 1 counts (under the fullest list's floor of 10); the packet carries them unchanged. The screens themselves need a client: **untested in a client.**

In game, as an operator, at 1280 x 720 (GUI scale 3), 1920 x 1080 at GUI scales 4, 3 and 2, and while resizing the window:
- [ ] `/su`: the screen fills the window less 8 GUI px on each side; the tab list on the left with PLAYERS, STAFF, WORLD and NETWORK headings; the title bar with this server's name and how many are online, and × (closes).
- [ ] Every tab opens from the list; the open one is violet with a bar on its left; the line under the page says what the page is for.
- [ ] Up and down arrows move through the list; while typing in a box they move the cursor instead.
- [ ] CLIENTS shows a gold number when someone was refused today; ENTITIES when a chunk is over a limit (wait up to 10 s after it happens).
- [ ] Each tab at each size: no text past a box's edge, no overlap, lists show more rows on bigger windows, settings boxes keep their width.
- [ ] RULES: the list is taller, the text boxes taller; UP, DOWN and DELETE sit under the text box.
- [ ] LOG: the kind and server menus stay inside the screen; the wheel scrolls a long menu.
- [ ] `/su setup`: the guide's strip under the title bar (step, a square per page, what the feature does); the tab list shows pages set up in teal and cannot be clicked; BACK, TURN OFF FEATURE, SETUP LATER and NEXT in the footer.
- [ ] The STAFF groups column with many groups: the wheel over it scrolls the groups; + Add stays above the operators switch.
- [ ] Known limit: a GUI narrower than 427 px (a 4:3 window such as 1024 x 768 gives 341) leaves a page under 330 px, where SERVERS and SUPPORT draw outside their boxes.

## 2026-11-24: audit of the staff screen at four sizes (SU-154)

Two independent layout reviews worked through every page with Minecraft's real glyph widths (from the client jar's font textures) at 427 x 240, 480 x 270, 640 x 360 and 960 x 540 GUI, and in the setup guide. 22 findings: 19 fixed, 3 left, and two smaller leftovers (below). GameTests **326/326**.

- The setup guide's Finish page lost its last rows below 255 GUI px of window height: the rows pack tighter to fit the box.
- STAFF: with many groups the rows ran over the operators switch (a click on it added a group): the groups column scrolls.
- ROOMS: the state column cut "not placed" and the days of grace at every size, SURE? read "Sur…", and the SERVERS heading was cut: widened and fitted.
- SERVERS: the list's scroll was not kept in range when the window grew or servers went; a long server name ran out of the sample line's box.
- CLIENTS: "Nobody with a report is online." stayed put while the list scrolled; the refusal message box grows with the page.
- DROPS: times of 100 to 120 min ran into the ×.
- CHUNKS: the last no-owner key ran past its box, at the old size too.
- ENTITIES: "+N more" chips ran out of the column; the never-counted box covered its ADD key's edge; the SURE sentence lost its third line on narrow pages.
- Bold key labels measured plain ran past their keys: ASK EVERYONE AGAIN, TURN OFF FEATURE, TURN ON FEATURE, REFRESH, ADD, CLEAR ITEMS, UNLOAD and RESTORE. The player counter's big number crossed the divider at nine digits.
- The LOG tab's full-text hover was clipped in the guide.
- STAFF: the ORDER label touched the swatches.

Left:
- Narrow 4:3 windows (GUI under 427 px wide): see the known limit above.
- SUPPORT on a big page is a fixed block with the rest of the page empty (D757 keeps settings boxes their width).
- Fitted text on the smallest window: six of the guide strip's descriptions and the Welcome page's config path end in "…".
- CHUNKS: machines with no owner beyond the rows shown cannot be scrolled to (as before).
- SERVERS in the guide on the smallest window: a server marked UNKNOWN with a picture in use and a different file can put the guide's status line 3 px over the sample line.

## 2026-11-24: code review of the day's work (SU-147, SU-154)

Three independent reviews read all the code written today: the server side and network, the staff screen's frame and the setup guide, and the 11 reworked tabs. 10 real bugs, 9 fixed; 13 smaller points, 8 fixed. GameTests **328/328**.

- `/su chunks tp` could land past the world border in a chunk the border cuts through: only the columns inside are tried (GameTested).
- The ENTITIES count read the fullest lists shown (8 at most each, and only chunks of 10 mobs or more), so it stopped at 16 and missed quiet chunks over a small number. It counts every chunk on this server (GameTested: a chunk of 5 mobs over a number of 1 counts), plus other servers' as last heard.
- The server's name is cut to 64 characters for the counts packet (a longer one broke every answer).
- ENTITIES: on a short window the 7th and 8th fullest chunks could not be seen: the list scrolls with the wheel.
- The arrow keys switched tabs while the LOG tab's menu was open (and the menu came back on the next visit); holding an arrow key sent each passing tab's request: one move every 150 ms; with Shift or Ctrl held they do nothing.
- The wheel over the tab list or the title bar scrolled the open tab.
- A box taken away by a refresh kept the focus, swallowing keys and blocking the arrows.
- Each step of a window resize asked the server for the counts.
- STAFF: UP and DOWN could move the group out of view: it is scrolled to.
- SERVERS: on a narrow page the chat tag ran over the server name: the list keeps at least 140 px.
- The guide strip's last square (Finish) is teal once every page is set up or off.
- The teleport GameTest no longer makes its player an operator (it wrote ops.json).

Left: STAFF's group editor (the colour code box and REMOVE) runs off a page under 330 px wide, with the narrow 4:3 windows above; on Mohist a plugin that sends the player elsewhere reads as "stopped"; the player-reach guess (view distance plus two) may be one chunk out at the edge; half-typed text in the ENTITIES and CHUNKS add boxes is lost on a resize (as before); a few 1 px touches at the old size.

## 2026-11-24: the LAG tab's loaded chunks, server side (SU-149)

Look BA C (D758), `tools/preview/lag_refined.html`. Protocol **14** (`LagAskPacket`, `LagAnswerPacket`). Notice kinds `lag_ask` and `lag_answer`. GameTests (`LagGameTests`, 3): a world's summary and map carry a chunk a mod's ticket holds, with the reason and ticking, every chunk with one reason, the list capped at 50, and the text forms read back the same; a chunk's detail counts entities and block entities by kind, says when a chunk is not loaded, and never loads one; an editor's question is answered here at once and asked of every other server (the summaries) or of the one named (a map, a detail); an answer is passed on while the question is open (15 s; the summaries stay open for every server); another server's question naming this one is answered, one naming another is not; a player who may not edit gets nothing; asks closer than 300 ms are dropped. **Nothing to see in game yet**: the screen is SU-150.

To check with two servers and Redis once SU-150 is in: the other server's worlds appear within a second or two; with Redis down, within the notice table's poll.


## 2026-11-24: the LAG tab's LOADED half (SU-150)

Look BA C (D758), frames R1 to R3, R7 and R8 of `tools/preview/lag_refined.html`. `client/LagTab.java`; the tab is LAG in the World group after DROPS. Server additions: a chunk's detail carries the safe spot its own server works out (`ChunkCommands.safeSpot`, only for a loaded chunk), and `LagGoPacket` (still protocol **14**) moves an editor to a chunk: on this server through `ChunkCommands.teleport` (the spot worked out again), on another through BSP-Teleport to the given spot, logged as `chunk_tp` with the server's name once BSP-Teleport takes the move.

GameTests (`LagGoGameTests`, 3; **334/334** in all):
- A loaded chunk's detail has a safe spot inside the chunk, standable (or on water), the same one `/su chunks tp` uses; the detail reads back the same with a spot and without; a chunk not loaded has none and is not loaded for one.
- GO THERE on this server: a player who may not edit moves nowhere and nothing is logged; an editor stands in the chunk afterwards, logged once as `chunk_tp`; this server named by its name works the same.
- GO THERE on another server: a player who may not edit is not moved; an editor's move goes through BSP-Teleport to the middle of the given spot, logged once with the server's name; a move BSP-Teleport could not start is not logged; without BSP-Teleport nothing happens.

**Untested in a client.** Everything on the screen is built but not seen in game. To check:
- The map at the four window sizes: 480 × 270 (17 × 17 squares), 427 × 240 (13 × 13, the world key without the server, the colour keys ENT., B.E., WHY), 960 × 540 (33 × 33), and the smallest page; nothing drawn under the tab list or past the page.
- ENTITIES, BLOCK ENT. and WHY colourings and their legends (WHY counts each reason); unloaded chunks near black; the viewer's chunk framed in white.
- The wheel zooms out four steps and back in, the legend saying how many chunks a square is; a zoomed-out square sums its chunks and takes the first reason; dragging moves the map without picking; HOME comes back to the viewer (from another world or server too).
- Clicking a square: CHUNK x, z, blocks, ticking, loaded by and whose, the totals and the chips of the top kinds; a square not loaded says so; zoomed out, a click picks the square's busiest chunk.
- GO THERE on this server (the screen closes, the chat line, the Action Log); on another server with BSP-Teleport (needs two servers and Redis); greyed with no safe spot, with a hover saying why.
- The world menu: every server's worlds with chunk counts and "answered N s ago"; a stopped server grey after 5 s with the age of its last answer; picking a world asks for its map once.
- LIST: every server's busiest chunks; clicking each heading sorts (again: the other way); TP on this server's row and on another's (it asks that server for its safe spot first); SHOW ON MAP with a row picked.
- The PROFILER key is drawn off with a hover; the profiler is SU-151 and SU-152.

## 2026-11-24: the lag profiler, server side (SU-151)

Frames R4 to R6 of `tools/preview/lag_refined.html`; owner decisions D749, D751 to D753, D759 to D761. Still protocol **14** (`LagSamplePacket`; `LagAskPacket.Kind.PROFILE`). Notice kinds `lag_sample` (a START or STOP passed on to the server it names) and `lag_said` (what an editor is told, passed to the server they are on). Action Log kind `lag_sample` ("Lag samples").

How it measures: two mixins, HEAD and RETURN injects only, each first reading one volatile boolean (`LagTimer.on`), so a server not sampling pays one read per hook. `BlockEntityTickLagMixin` times `LevelChunk$BoundTickingBlockEntity.tick()` (each block entity's tick); `ServerLevelLagMixin` times `tickNonPassenger` (an entity with its passengers), `tickChunk` (a chunk's own tick: random ticks, spawning, lightning) and `tickBlock` / `tickFluid` (scheduled ticks, counted in their chunk as "scheduled ticks"). The whole tick is timed between `ServerTickEvent` START (highest priority) and END (lowest). A sample starts timing at the next tick and ends after its length in ticks (20 a second), so on a server running at 10 TPS a 30 s sample takes about 60 s. Every inject has `require = 0`; `LagMixinPlugin` checks the hooks went in, and when one did not, the profiler refuses samples and logs once.

GameTests (`LagProfilerGameTests`, 5; **339/339** in all, twice in a row):
- Nothing is recorded outside a sample: the record calls leave the collector empty.
- Timings driven by hand (5 ms and 1 ms hoppers, two armour stands, a scheduled redstone tick, chunk ticks, over 20 ticks of 50 ms): the busy chunk first at 9.1 ms per tick, with its share of the tick; its kinds ranked (hoppers 6.0 ms, two of them, the slow one worst; armour stands with the worst one's place; scheduled ticks; the chunk's own tick as one line); a chunk not loaded at the end put down as "other"; 40.7 ms not in any chunk; OUTLINE's blocks the slowest first, a scheduled tick by its block's id; the result reads back the same.
- The text form: 200 chunks of 12 kinds with long names read back the same under the packet's limit; a profile carries the running sample and the last finished one, or neither; a result too big is cut by whole chunks, keeping the blocks.
- A real sample (shortened to 100 ticks): a player who may not edit starts nothing, a length not offered is refused, a second start is refused, PROFILE while it runs says who and how far; the test chunk's hoppers and armour stands are timed; it ends by itself after 100 ticks, is kept with its chunks ranked and capped, fits in the tick, and is logged once as `lag_sample` by who started it; the timings are cleared; STOP with nothing running does nothing; the last result stays while the next runs; STOP ends the next one with what it had, logged with who stopped it.
- Across servers (memory storage): a START for another server is passed on with a notice naming it, the length and who, and does not start here; that server's PROFILE is passed to the editor; a notice naming another server starts nothing; one naming this server starts here and sends this server's PROFILE back; a second start is refused, the asker told on their server who runs it and the time left; a PROFILE ask naming this server is answered; a STOP notice stops it, keeps what it had and logs it once.

Not tested by a GameTest: a chunk's own tick from a real run (it needs a player near; only the timings driven by hand test it).

**Untested in game.** No screen yet (SU-152). To check on a real server:
- The cost while sampling on a busy server: TPS and MSPT during a 60 s sample on the busiest server, against the same minute without one (the hooks cost two `System.nanoTime()` calls and a few map updates per block entity, entity and scheduled tick). With no sample running the cost should not show.
- Mohist backends, where Bukkit patches may change these methods: the server starts and runs whatever happens; if a hook did not go in, the log says once "the lag profiler is unavailable on this server" naming the missing hooks, and a START is refused with a chat line. If none is logged, check a sample's figures look sane (block entities and entities present, "not in any chunk" well under the tick).
- Two servers and Redis: START on another server from the PROFILER (once SU-152 is in) starts there, its PROFILE comes back, a refusal and "done" reach the editor on their own server.
- The Action Log shows "Lag samples" in the kind menu, with the line `sampled 30 s on <server>: tick N ms, worst chunk X, Z in <world> at N ms`.

## 2026-11-24: the lag profiler screen and the outline (SU-152)

Frames R4, R5, R6 and R8 of `tools/preview/lag_refined.html`; owner decisions D749 to D752, D759 to D761. The PROFILER half of `client/LagTab.java`, `client/LagOutline.java` (the boxes in the world and the HUD line), and the sums both use in `lag/LagView.java` (the ms ramp, a square's colour, the squares at a zoom, a running sample's time left and progress, when to ask again, the outline's clock, how long ago). Server additions: `ChunkCommands.spotNear` (the nearest place to stand within 3 blocks of a block, only in loaded chunks inside the border), `ChunkCommands.goBeside` / `teleportBeside` (there, facing the block, else the chunk's safe spot; logged as `chunk_tp`, "went beside X Y Z in chunk ..."), and `LagGoPacket.beside` (still protocol **14**): on this server the given place is the block to stand beside; on another server the screen asks that server for the chunk's safe spot and goes there through BSP-Teleport, as GO THERE does. The screen asks PROFILE when the PROFILER opens, when the menu picks another server, about every 2 s while a sample runs (only while the PROFILER is open) and once more when it should have ended.

GameTests (`LagScreenGameTests`, 6; **345/345** in all, twice in a row):
- The ramp's four colours at their edges (0.5, 2 and 5 ms); a square with a timed chunk takes its ms colour, a loaded one with no time the darkest loaded colour, one neither loaded nor timed the not-loaded colour.
- One world's timed chunks become squares, one a chunk; at ×2 the chunks' times are summed and the slowest is kept for a click; another world's chunks stay out.
- A running sample: the time left from the ticks still to go less the time since the answer, never under 0; progress; whole seconds rounded up; asked again after 2 s, and once (only once) as soon as it should have ended.
- The outline's m:ss (5:00 at the start, five minutes), how long ago in seconds, minutes, hours and days, the ms text (two decimals under 1 ms), the share of a 50 ms tick, the worst chunks of every world slowest first, one world's blocks.
- Beside a block: the nearest place on the block's own level in a fixed order, standable; the player stands there facing the block; walled in, nothing within reach, so the chunk's safe spot (the one GO THERE uses); a block in a chunk not loaded has no place and is not gone to, and nothing is loaded.
- Through the packet: a player who may not edit is refused and moves nowhere, nothing logged; an editor stands beside the block, logged once; GO THERE without beside goes to the chunk; the packet reads back the same with beside and without.

**Untested in a client.** Everything on the screen and in the world is built but not seen in game. To check:
- PROFILER on this server: 10 S / 30 S / 60 S (30 lit at first), RUN SAMPLE; the chat line; the line under the keys "Sampling <server>", the bar filling, "N s left" counting down, "started by <name>"; the length keys greyed and STOP in red while it runs; a second editor opening LAG sees the same bar.
- STOP: the sample ends with what it had; the line then gives "<server> · N of 30 s · ...".
- When it ends by itself the screen asks once more and the results come in without reopening: the map coloured by ms per tick (the four colours and the legend), chunks outside the result loaded in the darkest colour, the line "<server> · 30 s · <ago> · <by> · tick <avg> / 50 ms · not in any chunk <ms>", the tick amber from 25 ms and red from 45.
- The side panel: with no sample, the line telling the editor to run one; while one runs with an older result, "THE LAST SAMPLE · <ago> · <by>" and the note; a picked chunk "CHUNK x, z · N MS", its share and why/whose, its kinds ranked with counts and the mod or "entity" in grey, ms coloured, TP where there is a place; a chunk not in the result says it is not among the 200 slowest.
- TP beside a block entity on this server: the screen closes, the editor stands beside it facing it, the chat line, the Action Log; a block walled in sends them to the chunk's safe spot with a line saying so.
- PROFILER on another server (two servers and Redis): the world menu picks it, RUN SAMPLE starts there, the line and bar update, the results come back; TP and GO THERE ask that server for the chunk's safe spot and go through BSP-Teleport (greyed without it); OUTLINE greyed with its hover.
- OUTLINE TOP 10: the red boxes through walls with "<block> · <ms> ms" over each, only in the world the editor is in; the HUD line "Lag outline: N blocks · m:ss left" counting down; pressing it again clears them (the key lit while they show, its hover giving the time left); they clear after five minutes, on logout and on a move to another server; another player sees nothing.
- WORST ANYWHERE chips: the slowest chunks of every world, another world's named; a click moves the map to its world, centres and picks it.
- LIST in PROFILER: world, chunk, loaded by, ms/tick, share, sortable by each heading, TP, SHOW ON MAP with a row picked.
- Zoomed out, a square sums its chunks' times and a click picks the slowest one.
- The four window sizes: 480 × 270, 427 × 240 (the world key without the server; at the narrowest page the lengths as 10 / 30 / 60), 960 × 540, and the smallest page (330 × 192): nothing under the tab list or past the page, the line under the keys cut cleanly, OUTLINE shortening to OUTLINE beside GO THERE in a narrow side panel.
- Cost: no PROFILE asks while the PROFILER is closed or no sample runs; the outline's boxes and labels do not drop the frame rate.

## 2026-11-24: audit of the LAG tab (SU-153)

A review of the LAG tab and the lag profiler, each finding checked against the code first. All were real and are fixed; none was left.

Server:
- **S1 fixed.** An answer between servers is at most `LagNetwork.NOTICE_MAX` (256,000 characters, head included): `LagNetwork.answer` gives the payload what is left after the head. A map (`LagSnapshot.encodeMap(world, rows, budget, x, z)`) keeps whole rows, the nearest the ask's x, z first; the screen's MAP ask carries the map's middle (the viewer's chunk, or the centre once set; SHOW ON MAP and WORST ANYWHERE set the centre before asking). PROFILE (`LagResult.encodeProfile(profile, budget)`) keeps the slowest chunks, cut from the end. An answer to an editor on the same server keeps the packet's 1,000,000.
- **S2 fixed.** `lag_ask`, `lag_sample` and `lag_said` notices created more than 60 s before now (`LagNetwork.LATE_MS`, each class's clock) are dropped.
- **S3 fixed.** `LagProfiler.serverStopping` (the `ServerStoppingEvent` hook, at HIGH so it runs before the storage closes) finishes a running sample, logs it once as "sampled N of M s on <server>, cut short by the server stopping", tells no one, then clears.
- **S4 fixed.** `LagProfiler.Top` (a min-heap of N) keeps the 200 slowest chunks and OUTLINE's places (twice 10, as a place is at most a block entity and a scheduled tick) without sorting everything timed.
- **S5 fixed.** `ChunkCensus.reason` / `reasons(level).at(x, z)` work out one chunk's reason and who by `ChunkCensus.of`'s rules and order (players within view + 2, a Forge ticket within 2, `/forceload` within 2, spawn within 11, else other); used by `LagSnapshot.detail` and by the profiler's result. SUMMARY asks reuse summaries taken within the last second (`LagSnapshot.summariesForAsk`).
- **S6 fixed.** START and STOP each have the asks' 300 ms per-player gap (`LagNetwork.pass`, slots after the ask kinds).
- **S7 fixed.** `BlockEntityTickLagMixin` shadows nothing: the place and type come from `TickingBlockEntity.getPos()` / `getType()` on the ticker (the id looked up once per type), the level from the new `LevelLagMixin` (HEAD and RETURN of `Level.tickBlockEntities`, `require = 0`, server levels and the server thread only).
- **S8 fixed.** `LagMixinPlugin` says which lag mixins it expects and which were applied (system properties); `LagProfiler.missing()` loads the one late target class (`LevelChunk$BoundTickingBlockEntity`) first and counts a mixin never applied as missing.
- **S9 fixed.** Every server-name comparison of the LAG notices and packets cleans both sides (`LagNetwork.sameServer`; the screen compares the same way).
- **S10 fixed.** When another editor stops a sample, whoever started it is told too (`message...lag.sample_stopped_by`, through the same path as every other line, so on any server).
- **S11 fixed.** The line BSP-Teleport shows on arrival is the lang key `message.bsp_server_utilities.lag.arrived`.
- **S12 fixed.** Every Lag* GameTest starts with `LagProfiler.resetForTests()`: a running sample dropped (not logged), the real clocks and senders back, the summaries cache cleared.
- **S13 fixed.** `LagResult.Entry` says its count is how many of the kind ticked in the chunk during the sample; the side panel reads "hopper ×4 ticked" when it fits, else "hopper ×4" with the meaning in the hover.

Client:
- **C1 fixed.** The MAP answer's first line is its world (`LagSnapshot.MapAnswer`); `ClientLag.Map` keeps it; the tab takes a map only for the server and world it shows.
- **C2 fixed.** A GO THERE or TP waiting on another server is dropped in `LagTab.close()` (now also called when the staff screen closes on the LAG tab) and acts only within 5 s of asking.
- **C3 fixed.** "This server" is never guessed from an answer: until the counts name it, no map or detail is taken for it, and GO THERE, TP and OUTLINE are greyed with a waiting hover. The tab clears the last map and detail when it opens.
- **C4 fixed.** PROFILE asks are queued and flushed with the same pacing as MAP and DETAIL.
- **C5 fixed.** The WHY counts are reset with the squares.
- **C6 fixed.** LIST (LOADED): a row's hit box ends 2 px before the TP key.
- **C7 fixed.** `squareAt` stops at the map's last pixel.
- **C8 fixed.** The PROFILER's map looks the squares up once a frame; the footer's sums of the map rows are worked out once per map.
- **C9 fixed.** The outline draws through a buffer of its own and flushes only that; the HUD line is hidden while F3 is open; the outline clears when the player is in another world than the one it was shown in (and on login and logout, as before).
- **C10 fixed.** GO THERE and TP to another server are not greyed for a missing `bsp_teleport` on the client; the server says so.

Layout and wording:
- **L1 fixed.** The LOADED side panel gives entity chips at most half the rows left (more when the block entities need less), and a cut set ends with "+N more".
- **L2 fixed.** The PROFILER's ranked kinds, when cut, end with "+N more".
- **L3 fixed.** The zoom ("1 square = 4 × 4 chunks") is in the map's corner; the legend wraps to a second line above the footer when its items do not fit on one.
- **L4 fixed.** The PROFILER line measures the tick and the part not in any chunk first and fits who, when and how long to what is left.
- **L5 fixed.** The LIST headings use "B.E."; the chunk column is 48 px in both lists.
- **L6 fixed.** The side panel's TP key is measured as drawn (+ 10).
- **L7 fixed.** The world menu says "silent · N s ago"; a list longer than the menu has a 1 px scroll bar.
- **L8 fixed.** The keys sit at y + 22.
- **L9 fixed.** The world key's name is fitted to the key less " ▾", then the ▾ added.
- **Wording fixed.** "every world on <server>"; "until the running one ends"; "N ms not in any chunk"; "B.E." in the keys and the list, "block entities" spelled out elsewhere; "mod tickets" in the legend, "mod ticket" for one chunk; "HOME centres the map on you".

GameTests added (6; **351/351** in all, twice in a row):
- `LagGameTests.mapCutKeepsTheNearestAndNamesItsWorld`: a map over the cap is cut to it by whole rows, the kept rows the nearest the middle asked for, still naming its world; the full map and an empty one read back the same with their world; an answer here keeps the packet's limit; this server's own map cut to a budget.
- `LagGameTests.lateQuestionsAndSamplesAreDropped`: a MAP ask, a SUMMARY ask and a STOP over a minute old get nothing; seconds old they are answered.
- `LagGameTests.serverNamesWithABarMatchOnBothSides`: on a server called `al|pha`, an ask and a STOP naming `al/pha` are answered, and an editor's MAP ask naming it is answered at once.
- `LagProfilerGameTests.topKeepsTheBestWithoutSortingAll`: the heap's ten equal a full sort's first ten; fewer than ten; none.
- `LagProfilerGameTests.startAndStopCloserThanTheGapAreDropped`: a second STOP or START within 300 ms is dropped, START and STOP apart, heard again after the gap.
- `LagProfilerGameTests.sampleCutByTheServerStoppingIsLoggedOnce` (batch of its own): the stopping hook's method ends a running sample, logs it once as cut short by the stop, clears it; a second call logs nothing.
- Updated: `summaryAndMapCarryALoadedChunk` checks every loaded chunk's one-chunk reason against the census's and the detail against the map (S5), and the map's round trip with its world (C1); the across-servers answers check the cap and the world; a PROFILE cut to the cap between servers keeps the slowest chunks in order (`textFormReadsBackUnderTheLimit`); the notices in the across-servers test carry real times.

**Untested in a client or on a real network.** The screen changes (C1 to C10, L1 to L9) are built but not seen in game; check at 427 × 240, 480 × 270, 640 × 360 and 960 × 540: the legend's second line in WHY at 427, the zoom label in the map's corner, "+N more" chips with a chunk of many kinds, the PROFILER line at 427 with long names, the menu's scroll bar with many servers, GO THERE greyed for a moment when the screen opens before the counts. The outline through its own buffer (boxes and labels still drawn, nothing else in the world flickering), its HUD line hidden under F3, and its clearing on a portal. On a real network: a cut map through Redis, a sample stopped by another editor telling its starter, the arrival line from BSP-Teleport, and the profiler on a Mohist backend (a missing target class now refuses samples).

## 2026-11-24: review of the LAG audit fixes

Screen:
- **1 fixed.** GO THERE and TP to another server wait as long as the question is open (`LagNetwork.OPEN_MS`, 15 s; an answer without Redis takes up to about 14 s). A server is greyed after 5 s as before, but worded as waiting ("Waiting for <server> to answer…", "waiting…" in the world menu) until 15 s have passed, and only then "did not answer"; a late map, summary or PROFILE answer is still taken.
- **2 fixed.** The zoom ("1 square = 4 × 4 chunks") ends the legend's first line, its width kept free so the items wrap round it; nothing covers the map. Chosen over a "×2" in the corner, which at 337 × 206 (9 to 10 px squares) covers more than one square from ×16.
- **3 fixed.** A TP's DETAIL ask has a slot of its own (sent first, the kind's one gap kept for the server's pacing), and `ClientLag` keeps the last four detail answers, so a pick or SHOW ON MAP right after a TP neither replaces its ask nor its answer.
- **4 fixed.** While this server's name is not known, the map and the side panel show "Waiting for this server's name…" (`lag.side.wait_here`), and so do GO THERE's hover and the PROFILER's status, never "did not answer".
- **5 fixed.** The room under the map is two legend lines with 2 px above and below (`LEGEND_H` 14); a line is drawn only 2 px clear of the footer's line. At 337 × 206 the LOADED map is 129 px (it was 142, flush with the footer).
- **6 fixed.** OUTLINE is refused with "No block of this sample is in your world." (also its hover) when none of the sample's blocks is in the viewer's world.
- **7 fixed.** The LIST rows and the PROFILER list rows are kept sorted and made again only when the summaries change (`ClientLag.summariesVersion`), the sort changes, this server's name arrives, or the sample shown changes; a row's grey is read as it is drawn.
- **8 fixed.** The PROFILER's world key is measured against the wider of RUN SAMPLE and STOP, so it keeps one width and one form as a sample starts and ends.

Server:
- **9 fixed.** `LagProfiler.reset()` (start and stop) clears the summaries cache (`LagSnapshot.clearCache`) and the open questions and ask gaps (`LagNetwork.serverStopped`); where answers go and the clock are kept.
- **10 fixed.** A map ask's middle may be `LagSnapshot.BUSIEST` (`Integer.MIN_VALUE` for both x and z): a cut map keeps the rows nearest the world's busiest chunk (most entities, then block entities, as the screen centres). The screen sends it when it is not centred and the viewer is not in the world, and a change of world resets the centre.
- **11 fixed.** Another server's lag_ask is answered only while it is open (15 s, `ASK_LATE_MS`); lag_sample and lag_said keep 60 s. The first lag notice dropped as late is logged once (info). The servers' clocks must agree (NTP), as for the shared storage's own 10-minute filter: the age of a notice is this server's clock less the sender's.
- **12 fixed.** `LagTimer.blockEntitiesStart` puts the level it replaces aside and `blockEntitiesEnd` gives it back, so a nested `Level.tickBlockEntities` returns to the outer level, not to none.
- **13 fixed.** The spots' order breaks equal times by world and place, then kind (a block entity before a scheduled tick), then label.
- **14 fixed.** A player's ask gaps are dropped on `PlayerLoggedOutEvent` (and all of them on stop); `Summary.byWhy()` returns a copy.
- **15 fixed.** The BSP-Teleport arrival line was `Component.translatable(...).getString()` on the server, which on a dedicated server is the raw key (Forge loads only data-pack lang files there, not a mod's `assets`). BSP-Teleport's API takes plain text it shows as is, so the line is English in code (`LagNetwork.ARRIVED`), as `WebTeleports.MESSAGE` already is; the same was fixed in `TutorialRunner` ("Taking you to <tutorial>"). Every other lag line to a player is sent as the translatable Component; the Action Log texts stay English in code. The two unused lang keys are gone.

GameTests added (6; **357/357** in all, twice in a row):
- `LagGameTests.stoppingClearsTheCacheAndOpenQuestions` (batch of its own): the stopping path drops the summaries cache, the open questions and the ask gaps; the sender and the test's clock are kept.
- `LagGameTests.busiestFlagCutKeepsTheRowsNearestTheBusiest`: a cut with the flag keeps the busiest chunk first and only rows near it, the same as asking for its place; this server's own map and a notice with the flag are answered.
- `LagGameTests.questionsAreAnsweredOnlyWhileOpen`: a lag_ask 20 s old is dropped, one 10 s old answered, a STOP 20 s old acted on.
- `LagGameTests.logoutDropsTheAskGapAndCountsAreCopies`: logging out drops the ask gap; `byWhy()` is a copy, for a summary and for the cached ones.
- `LagProfilerGameTests.nestedBlockEntityLevelsRestoreTheOuter`: through the `LagTimer` hooks, the nested tick is the inner world's, the outer is back after it, none after the outer returns.
- `LagProfilerGameTests.spotsAtOnePlaceAndTimeKeepOneOrder`: a block entity and a scheduled tick, and two types, at one place and time give the same blocks in the same order whichever was recorded first.
- Updated: `LagGoGameTests.goElsewhereThroughBspTeleportOnly` checks the arrival line is the plain text, not a key.

**Untested in a client or on a real network.** 1 to 8 are built but not seen in game: check at 337 × 206 (the zoom at the legend's end in WHY with big counts, the 2 px gap under the second line, the smaller map), the waiting wording between 5 and 15 s with a server off Redis, a pick right after a TP to another server, the screen opened before the counts, OUTLINE refused in another world, and the world key as a sample starts and stops. On a real network: the arrival line from BSP-Teleport, and the late log with a server's clock set wrong.

## 2026-11-24: BSP-Core's season reset (SR15)

Needs a BSP-Core with `/bsp season reset` (newer than 1.24.1) on every server, with this jar. GameTests (the BSP-Core jar this mod builds against has no `isSeasonReset`, so they hook stand-in events by class name and call the handler directly): `SeasonResetGameTests.the_season_reset_is_heard_by_class_name_and_an_older_bsp_cores_season_end_is_not_one`, `SeasonResetGameTests.a_season_reset_forgets_every_players_chunk_entries_as_the_player_reset_does_and_keeps_the_rules_agreement` (in a batch of its own: it forgets every player).

On the network, with two test players who have agreed to the rules, each with a chunk-loading machine on server B, both away long enough for their machines to unload, and an admin's RESTORE NOW on one of them:
1. On server A, `/bsp season reset` as BSP-Core asks. A's log: "BSP-Core season reset (season N ended by <admin>, typed here): chunk unloading forgot ... player(s), owners checked again in a second; rules agreements kept". B's log: the same line with "ended by network" and without "typed here".
2. On B, nothing loads because of the reset: both players' machines stay unloaded, and the RESTORE NOW hold is gone (the CHUNKS tab). Machines they placed stay listed under their names and come back when each joins B. A machine that was a player's only through a claim BSP-Region Guard gave up in the reset moves to "no owner", stays unloaded, and does not come back when they join; an admin can RESTORE it from the CHUNKS tab. A claim-only machine that was still loaded at the reset (its owner online) is unloaded a second later with no owner too (B's log: "Chunk unloading: 1 loaded ticket(s) held only through a claim given up in the season reset have no owner now (unloaded)").
3. Each player logs in again: no rules popup. Their staff panel notes and tags, bans, staff talks, Action Log lines, totem steals and last seen are all still there. The always-loaded list is unchanged.
4. An ordinary season end (`End season`, or `Full reset` in BSP-Core's admin panel) changes nothing here, and with a BSP-Core older than the season reset nothing changes either.

## 2026-11-24: audit of the season reset (0.25.1)

Two independent reviews (correctness; network and edge cases). Fixed:
- The owner checks a reset leaves waiting are kept in the world (`ChunkData` `rejudges`), and queued again a second after the next start. Before, a server stopped within a second of hearing the reset lost them. This covers the player reset too.
- Loaded tickets charged to a player only through a claim at the season reset are checked a second later as well, and unloaded with no owner when the claim is gone (`ChunkService.checkClaims`).
- The record of season resets already applied is cleared at each server start, and a reset whose clearing failed is not marked as applied.
- The chunk test sets up inside its `try`, covers a player with only a hold, a loaded claim-only machine, a loaded machine with no owner, and a restart before the checks, and finishes the checks of the test world's leftover entries itself.

GameTests **359/359**, twice in a row (`SeasonResetGameTests`: 2). Untested with a BSP-Core jar that has the season reset, and on the network.

## 2026-11-25: Streamer Mode hides JourneyMap's minimap behind menus too (unreleased)
- [ ] With JourneyMap and Streamer Mode on, and "JourneyMap minimap" hidden in the streamer menu: press Esc. The stream window shows the pause menu with no minimap; the game window shows the minimap behind the pause menu as before.
- [ ] Back in game: the stream still has no minimap; with the minimap shown again in the streamer menu, it appears in the stream both in game and behind the pause menu.
