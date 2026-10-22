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
