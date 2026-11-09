# BSP staff web panel

A small program of its own, built from this folder: `./gradlew -p staff-panel build` from the repository's root. The jar, with its libraries inside, is `staff-panel/build/libs/bsp-staff-panel-<version>.jar`. It runs beside the game servers behind a Cloudflare tunnel at `staff.buildsecureprotect.com`, and shows what each game server's BSP-Server Utilities sends it (`docs/API.md`, "The staff web panel's feed").

## Two locks
1. **Cloudflare Access** in front of the address: Cloudflare asks for the staff member's email and a one-time code before anything reaches the panel. The panel checks Cloudflare's signed token on every request, pages and files included.
2. **The panel's own login** for that same person: a password and a 6-digit code from an authenticator app, or one of ten backup codes. Five wrong tries lock the account for 15 minutes. A login lasts 7 days for Moderators and 48 hours for Admins and Owners, and works only for the Cloudflare identity that made it.

Nothing secret is stored in the clear: passwords as PBKDF2 hashes, authenticator secrets sealed with the panel's own key (in `panel.properties`, never in the database), session and link tokens and backup codes as keyed fingerprints. Every POST must come from the panel's own pages (a JSON body, an `X-BSP` header and the panel's own `Origin`); pages run under a strict Content Security Policy and load nothing from other sites. Setup links carry their token after `#`, so it never appears in a logged address.

## Setting it up
1. **The game servers:** in each server's `config/bsp_server_utilities-network.toml`, `webPanel.enabled = true` (they need shared storage and Redis) and `webPanel.actionKey` set to the panel's `actionKey` (the panel's console `action-key` prints the line). `/su status` then says when the last snapshot went and whether actions are taken.
2. **The panel's server:** on Pterodactyl, a plain Java 17 server (a "generic Java" egg) running `java -jar bsp-staff-panel-<version>.jar`, on the same Docker network as MariaDB and Redis. Start it once: it writes `panel.properties` and stops. Fill in the database and Redis (the same as BSP-Server Utilities uses, `redis.channel` as the servers' `storage.redis.channel`) and the Cloudflare settings below. Keep `listen.host=127.0.0.1` if cloudflared runs in the same container; otherwise the container's own address, never a port open to the internet. Back up `panel.properties`: its `secretKey` seals the authenticator secrets.
3. **Cloudflare:** a tunnel (cloudflared) with a public hostname `staff.buildsecureprotect.com` pointing at the panel (`http://<panel>:8100`); then Zero Trust > Access > Applications > Add a self-hosted application for that hostname, with a policy that allows only the staff's emails, login method "One-time PIN" (or Google), and a session of a week. Copy the team domain (`<team>.cloudflareaccess.com`) and the application's "Application Audience (AUD) Tag" into `panel.properties`.
4. **The first Owner:** in the panel's console, `create-owner <email> <minecraft name>`. It prints a link valid 15 minutes: open it signed in to Cloudflare as that email, choose a password, scan the QR code, type the code, save the ten backup codes.
5. Owners add Moderators and Admins from the panel's More tab; each gets their own 15-minute link.

## The console
`create-owner <email> <minecraft name>`, `reset-owner <email>` (clears their authenticator; they set up a new one after their password), `reset-login <email>` (clears password and authenticator; prints a new link), `staff`, `status`, `stop`, `help`.

## A look on this computer
`java -jar staff-panel/build/libs/bsp-staff-panel-<version>.jar --demo` listens on `127.0.0.1:8100` only, with made-up servers and players, records in memory and no Cloudflare: it prints a setup link for a demo Owner. Nothing it does reaches a game server, and it never reads `panel.properties`.

## Status
- Built and tested (JUnit, `./gradlew -p staff-panel test`, 27 tests): the security core and staff accounts, the web server (Cloudflare check, POST checks, security headers, cookies, Owner-only pages), the live feed from Redis and the table, the config file, the login, setup and backup code pages, the frame with its tabs and staff online, the More tab with your login and the staff accounts.
- Seen in a browser in `--demo` only. **Not yet run against Cloudflare, the real MariaDB or Redis, or the game servers.**
- The Players tab as designed (SU-34): phone sheet and computer card, heads from skins (the panel needs to reach `textures.minecraft.net` over HTTPS for them; without it, plain faces).
- Actions (SU-35): kick (the network kick through BSP-Velocity Additions 0.2.0), broadcast with the preview.
- Graves (D295 to D302): look up and return; the items once BSP-InventorySync's SU-IS3 is built.
- Not built yet: chat, map, staff tools, inventories, teleports, bans, market, lottery.
