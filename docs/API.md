# BSP-Server Utilities API for other mods

Package `com.mrgregles.bsp_server_utilities.api`. Nothing is offered yet; this file grows with the features (staff online, the action log) and lists every class, method and event other mods may use. Anything outside the `api` package may change without notice.


## Server images in chat (a text contract, not a Java API)

Any chat line, from any mod or from Velocity, can show a server's image by sending this piece (Minecraft 1.20.1 JSON):

```json
{"translate": "bsp_server_utilities.server_image.bsp-spawn", "fallback": "%s",
 "with": [{"text": "[BSP-Spawn]", "color": "gray"}],
 "font": "bsp_server_utilities:server_tags", "color": "white",
 "hoverEvent": {"action": "show_text", "contents": {"text": "BSP-Spawn", "color": "white"}}}
```

- The key is `bsp_server_utilities.server_image.` followed by the server's name as Velocity knows it, in lower case (`Locale.ROOT`).
- The font must be `bsp_server_utilities:server_tags` and the colour white: a font image takes the text colour, so grey would tint it. The font falls back to the default font for every other character, so the fallback text reads normally in it.
- A client with this mod and the server's image draws the image one chat line high; anything else (no image for that server, the feature off, the console, logs, other tools) reads the fallback, the grey `[BSP-Spawn]`.
- In Java on a Forge server: `com.mrgregles.bsp_server_utilities.serverimages.ServerTag.of(name)` builds it (outside the `api` package: may change). With Adventure: `Component.translatable(key, "%s", Component.text("[" + name + "]", NamedTextColor.GRAY)).color(NamedTextColor.WHITE).font(Key.key("bsp_server_utilities", "server_tags"))` plus the hover.

## The staff web panel's feed (a data contract, not a Java API)

What each game server sends to the BSP staff web panel. The panel is a separate program; this is the shape it reads.

**Where it travels.**
- **Redis:** `PUBLISH` on `bsp_su:<channel>:web` (the server config's `storage.redis.channel`, then `:web`). The game servers publish on it and never subscribe, so none of this reaches another game server. Each message is five fields on their own lines, split at the first four line breaks: a unique id, the time in ms, the source server's id (`storage.serverId`), the kind, then the payload.
- **MySQL:** `<prefix>web_servers` (`server` VARCHAR(64) primary key, `at_ms` BIGINT, `snapshot` MEDIUMTEXT), one row per server holding its newest `snapshot` payload. The panel reads it when it starts, or while Redis is down.
- Sent only while `webPanel.enabled = true` in `config/bsp_server_utilities-network.toml` (off by default), every `webPanel.snapshotSeconds` (5; 2 to 30), only with shared storage, and only with `webPanel.actionKey` set.
- **Signed:** the payload (on Redis and in the table) is the HMAC-SHA256 of the JSON's UTF-8 bytes in lower-case hex (64 characters), a dot, then the JSON, keyed with `webPanel.actionKey`. The panel drops anything else.

**Kind `snapshot`, version 1.** The payload is JSON:

```json
{"v": 1, "server": "BSP-Spawn", "at": 1794179651287, "mspt": 21.4, "max": 100,
 "players": [{"id": "uuid", "name": "Vexa", "dim": "minecraft:overworld", "x": -2210.5, "y": 12.0, "z": 905.3, "yaw": 87,
   "mode": "survival", "hp": 10.0, "maxHp": 20.0, "armor": 8, "food": 7, "sat": 0.0, "level": 7, "xp": 0.4,
   "air": 100, "maxAir": 300, "effects": [{"id": "minecraft:night_vision", "amp": 0, "sec": 280}],
   "skin": "https://textures.minecraft.net/texture/…", "afkMs": 0, "ping": 120, "role": null,
   "team": {"id": "uuid", "name": "Nightshade", "colour": "#AA00AA"}}]}
```

- `mspt`: the server's average tick time in ms (50 is the most a server can take and keep 20 ticks a second). `max`: player slots. `graves`: true when BSP-InventorySync 0.7.0 is there to answer about graves.
- The panel takes a snapshot only when its signed `server` is the server it came from (the message's source, or the table row's `server`) and its `at` is under a minute old.
- Positions to a tenth of a block; `yaw` in degrees. `mode`: `survival`, `creative`, `adventure` or `spectator`.
- `armor`: armour points (0 to 20 and more with mods). `food` 0 to 20, `sat` saturation. `level` the XP level, `xp` the progress to the next (0 to 1). `air` and `maxAir` in ticks (300 is a full bar).
- `effects`: `amp` 0 is level I; `sec` -1 for an endless effect.
- `skin`: the player's skin texture from their profile, always `https://textures.minecraft.net/texture/...` (any other address is left out), or null (an offline-mode profile); the panel cuts the head from it.
- `afkMs`: how long the player has been AFK (0: active). `ping` in ms.
- `role`: the staff role's label (as on the STAFF tab), or null. `team`: the player's BSP-Core team, or null (no BSP-Core, or no team).
- A server that stops sending is gone after about three missed snapshots; the panel decides when.
- `v` changes only when a field changes meaning or goes away; new fields may be added in version 1.

## The staff web panel's actions (a data contract, not a Java API)

What the panel asks a game server to do, and the answer.

- **The request:** a notice of kind `web_action` from source `panel`, on the game servers' own channel `bsp_su:<channel>` (Redis) and in `<prefix>notices` (the same five lines; a server takes the first copy).
- **Its payload:** the action's JSON in URL-safe Base64 without padding, a dot, then the HMAC-SHA256 of that Base64 text in lower-case hex. The key is 32 bytes, Base64 in `webPanel.actionKey` (each game server's network config) and `actionKey` (the panel's `panel.properties`).
- **The JSON:**
  - always: `v` (1), `id` (used once), `at` (ms; refused more than 30 s away from the server's clock), `by` (the staff member's Minecraft name), `rank`, `server` (the one it is for, any case);
  - `"action": "kick"`, `player` (UUID), `reason` (up to 160 characters; the player reads "You were kicked by staff: <reason>");
  - `"action": "broadcast"`, `title` (true for a title), `colour`, `target` ("all" or a name), `network` (true: every server), `message`: the same as `/broadcast`.
  - `"action": "graves"`, `player` (UUID): the player's graves on the whole network through BSP-InventorySync's api (0.7.0, API 2); the answer's `data` is a list of its `GraveInfo` fields (`id`, `shortId`, `serverId`, `dimension`, `x`, `y`, `z`, `placed`, `archived`, `diedAt`, `touchedAt`, `stacks`, `xp`, `recoverable`, `returnable`). Nothing is logged.
  - `"action": "grave_return"`, `player`, `grave` (its id or first eight characters), `name`: BSP-InventorySync's `returnGrave` by "panel: <by>"; the answer's `data` is its `GraveReturn` fields (`outcome`, `message`, `returned`, `left`, `xp`, `serverId`, `ok`); an Action Log entry when it was taken. `ONLINE` names the server to ask instead.
  - Without BSP-InventorySync 0.7.0 on the server, both answer "Graves need BSP-InventorySync 0.7.0 or newer on this server.".
- **The answer:** kind `answer` on the web channel `bsp_su:<channel>:web`, JSON `{"id", "ok", "message", "server"}`, signed as the snapshots are (64 hex, a dot, the JSON). No answer comes for a wrong signature, another server's action, or while `webPanel.enabled` is off on that server (actions are then not taken either). Answers travel on Redis only: while Redis is down an action may be carried out with no answer.
- **The network kick:** custom payload `bsp_server_utilities:proxy` on the player's own connection, version 1, kind "kick", the UUID, the reason as Component JSON (BSP-Velocity Additions' `docs/API.md`); the server's own kick follows after 3 s if the player is still there.
