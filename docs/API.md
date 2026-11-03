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
