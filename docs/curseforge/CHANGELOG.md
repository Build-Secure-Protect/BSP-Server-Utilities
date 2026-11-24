# BSP-Server Utilities 0.22.0: Streamer Mode

For Minecraft 1.20.1, Forge 47.4 or newer. **Streamer Mode is a change in the game only:** the network protocol is still 11. Works on its own; uses **BSP-Core**, **BSP-Teleport**, **BSP Region Guard**, **JourneyMap** and **FancyMenu** when they are installed.

## New in 0.22.0: Streamer Mode

Stream your game without giving away where your base is. Streamer Mode makes a second, clean picture of the game for OBS, while your own window shows everything as usual.

- **Open it** with the eye key in the top-right corner of the title screen or the pause screen (Esc), or type `/streamer`. Switch it ON there, or with a key you set in Controls › Key Binds › BSP-Server Utilities (`/streamer on` and `/streamer off` work too).
- **Choose what OBS must not see,** mod by mod, each with a HIDDEN or SHOWN switch:
  - **Minecraft:** the F3 screen, chat, chat messages that hold coordinates, the player list, boss bars, titles, the action bar and the scoreboard. Hiding coordinates in chat also hides command suggestions (Minecraft offers the block you are looking at), what you are typing when it is a command or holds a position, and chat's hover text.
  - **JourneyMap:** the minimap, or only its coordinate lines with the map kept; waypoint markers; its menus.
  - **BSP-Teleport:** the coordinates in the Teleport band, on the portal screen and on the portal chooser (BSP-Teleport 3.9.2 or newer hides only the numbers; an older one has the band and portal screen hidden whole), and its admin screen.
  - **BSP-Core:** the sentinel strip, the recall card, the Team screen, its admin screen and the Accelerator screen.
  - **BSP Region Guard:** the Land Office and region screens.
  - **BSP-Server Utilities:** the admin screen.
  - **Other mods:** any HUD part they add, and one switch for all their menus.
- **Hidden at first:** everything that shows a place. A hidden menu shows on stream as a small "Menu hidden" card.
- **In OBS:** add a Window Capture (on a Mac, macOS Screen Capture › Window) of the window "BSP Stream". On 64-bit Windows you can choose Spout2 instead: no extra window; add "Spout2 Capture" › BSP Stream from Off-World-Live's free Spout2 plugin.
- **A live preview** in the menu shows exactly what OBS gets. A frame rate of 60 or 30 costs your game less than Match.
- **Never hidden:** what is drawn into the world itself (JourneyMap's waypoint beams, F3+B and F3+G, a map in your hand, the landscape).
- **The .dll in the jar:** Spout2 uses Spout's own `SpoutLibrary.dll` (Spout SDK 2.007.018 by Lynn Jarvis, BSD-2-Clause, unchanged, its licence beside it in `natives/windows-x64/`). It is loaded only when you choose Spout2 on 64-bit Windows; on a Mac, on Linux and on servers it is never touched.
- **For other mods:** `api.StreamerMode.hidesNow(id)` lets a mod hide something of its own in the stream (docs/API.md).

Your choices are kept in `[streamer]` of `config/bsp_server_utilities-client.toml`.
