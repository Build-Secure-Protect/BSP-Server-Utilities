# BSP-Server Utilities 0.20.0: singleplayer worlds as a grid

For Minecraft 1.20.1, Forge 47.4 or newer. **A change in the game only:** servers on 0.19.0 need nothing new, and the network protocol is still 11, so a 0.20.0 game joins them. Coming from 0.18.x or older, 0.19.0's protocol change applies: players and every server move to 0.19.0 or newer together. Works on its own; uses **BSP-Core 1.4.0** or newer, **LuckPerms**, **BSP-InventorySync** and **BSP-Velocity Additions** when they are installed.

## New in 0.20.0: singleplayer worlds as a grid

- Singleplayer's Select World screen shows your worlds as a scrolling grid of cards in place of Minecraft's list: "+ Create new world" first, then each world with its picture (cropped to fill the card), its name, its game mode and when you last played it, the most recent first as before.
- Tags on the picture: HARDCORE in gold; LOCKED (open in another game), UNSUPPORTED, OLDER VERSION and NEWER VERSION (saved by an older or a newer Minecraft) in coral; EXPERIMENTAL in gold; SELECTED in teal. Hovering the picture shows Minecraft's own warning for that world.
- Click a card to select it, double-click or press Enter to play. The arrow keys move across the grid and Tab still reaches the search box and the buttons. The mouse wheel and the scrollbar scroll, and the selected card stays in view.
- The search box and the Play, Edit, Delete, Re-Create and Cancel buttons are Minecraft's own and work exactly as before. The grid uses 2 to 6 columns, whatever fits the window and the GUI scale.
- To keep Minecraft's plain list, set `worldGrid = false` under `[menus]` in `config/bsp_server_utilities-client.toml`.
