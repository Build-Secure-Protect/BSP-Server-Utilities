package com.mrgregles.bsp_staff_panel;

import com.mrgregles.bsp_staff_panel.crypto.SecretBox;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The panel's settings: {@code panel.properties} beside the jar. Written with every setting and its explanation on the
 * first start, then the panel stops and asks for it to be filled in. The panel's key ({@code secretKey}) is made there and
 * then; it seals the authenticator secrets and must stay with this file (losing it means every staff member sets up their
 * authenticator again). The file holds passwords: it is made readable by its owner only where the system allows.
 */
public record PanelConfig(String listenHost, int listenPort, String publicUrl, String teamDomain, String aud,
                          String dbHost, int dbPort, String dbName, String dbPanelName, String dbUser, String dbPassword, boolean dbSsl, String tablePrefix,
                          String redisHost, int redisPort, String redisUser, String redisPassword, String redisChannel,
                          byte[] secretKey, byte[] actionKey, byte[] signingKey, String panelKey, String mapRoot) {
    public static final String FILE = "panel.properties";

    private static final String ACTION_KEY_LINES = """

            # The key the game servers sign what they send to the panel with (their players, chat, answers). Copy it into every
            # game server's config/bsp_server_utilities-network.toml as webPanel.actionKey. With it someone could show the
            # panel made-up players or answers (not order anything: that takes signingKey). Keep it in these files only.
            actionKey=%s
            """;

    private static final String SIGNING_KEY_LINES = """

            # The panel's own signing key (D426): every action the panel sends is signed with signingKey, which only this file
            # holds; the game servers check it with panelKey. Copy panelKey (public, it can only check) into every game
            # server's config/bsp_server_utilities-network.toml as webPanel.panelKey. Never copy signingKey anywhere.
            signingKey=%s
            panelKey=%s
            """;

    private static final String TEMPLATE = """
            # BSP staff web panel settings. Fill these in, then start the panel again.
            # This file holds passwords and the panel's key: keep it private and back it up.

            # Where the panel listens inside its container: 0.0.0.0 (every address of the container). Who can reach it is
            # decided by the server's allocation in Pterodactyl (127.0.0.1 on the node is best); never open it to the internet.
            # listen.port empty: the allocation's port (Pterodactyl's SERVER_PORT).
            listen.host=0.0.0.0
            listen.port=
            # The address staff open, exactly as in the browser (no slash at the end).
            publicUrl=https://staff.buildsecureprotect.com

            # Cloudflare Access (Zero Trust > Access controls > Applications > this application > Additional settings):
            # the team domain, like bsp.cloudflareaccess.com, and the application's "Application Audience (AUD) Tag".
            cloudflare.teamDomain=
            cloudflare.aud=

            # The network's MySQL / MariaDB. database.name: the game servers' shared database (BSP-Server Utilities' tables,
            # same table prefix). database.panelName: the panel's own database for its accounts, notes and staff room, with
            # database.user a user only the panel has (the setup guide's SQL); never the game servers' user, whose password
            # sits on every game server. Seen from inside the panel's container: localhost is the container itself.
            database.host=
            database.port=3306
            database.name=bsp
            database.panelName=bsp_panel
            database.user=bsp_panel
            database.password=
            database.ssl=false
            database.tablePrefix=bsp_su_

            # The network's Redis, and the channel the game servers use (storage.redis.channel in their server config).
            # Seen from inside the container, as above; plain TCP (not TLS).
            redis.host=
            redis.port=6379
            redis.username=
            redis.password=
            redis.channel=network

            # The staff map (BlueMap): the folder of BlueMap's web files (its webroot, holding index.html and maps/), mounted
            # read-only from BlueMap's own server. Empty: the Map tab says the map is not set up.
            map.root=

            # The panel's own key, made on the first start. Never share it; keep it with this file.
            secretKey=%s
            """ + ACTION_KEY_LINES;
    /** Thrown when the file is new or incomplete: the message says what to do. */
    public static final class NotReady extends Exception {
        public NotReady(String message) {
            super(message);
        }
    }

    public static PanelConfig load(Path file) throws IOException, NotReady {
        if (!Files.exists(file)) {
            String key = Base64.getEncoder().encodeToString(SecretBox.newKey());
            String[] pair = com.mrgregles.bsp_staff_panel.crypto.PanelSigner.newPair();
            Files.writeString(file, TEMPLATE.formatted(key, Base64.getEncoder().encodeToString(SecretBox.newKey())) + SIGNING_KEY_LINES.formatted(pair[0], pair[1]),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            ownerOnly(file);
            throw new NotReady("Wrote " + file.toAbsolutePath() + ": fill in the Cloudflare, database and Redis settings, then start the panel again.");
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        if (p.getProperty("actionKey", "").isBlank()) { // a file from before actions: the key is added
            String made = Base64.getEncoder().encodeToString(SecretBox.newKey());
            Files.writeString(file, ACTION_KEY_LINES.formatted(made), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            p.setProperty("actionKey", made);
        }
        if (p.getProperty("signingKey", "").isBlank()) { // made on the first start, or added to a file from before D426
            String[] pair = com.mrgregles.bsp_staff_panel.crypto.PanelSigner.newPair();
            Files.writeString(file, SIGNING_KEY_LINES.formatted(pair[0], pair[1]), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            p.setProperty("signingKey", pair[0]);
            p.setProperty("panelKey", pair[1]);
            System.out.println("Made the panel's signing key: put panelKey from " + FILE + " (or the console's action-key) in every game server's webPanel.panelKey.");
        }
        byte[] signingKey;
        try {
            signingKey = Base64.getDecoder().decode(p.getProperty("signingKey").trim());
            new com.mrgregles.bsp_staff_panel.crypto.PanelSigner(signingKey);
        } catch (IllegalArgumentException e) {
            throw new NotReady("signingKey is not a key this panel made. Empty signingKey and panelKey to make a new pair (then copy panelKey to every game server again).");
        }
        String panelKey = p.getProperty("panelKey", "").trim();
        byte[] actionKey;
        try {
            actionKey = Base64.getDecoder().decode(p.getProperty("actionKey").trim());
        } catch (IllegalArgumentException e) {
            actionKey = new byte[0];
        }
        List<String> missing = new ArrayList<>();
        String publicUrl = text(p, "publicUrl", missing).replaceAll("/+$", "");
        String team = text(p, "cloudflare.teamDomain", missing).toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceAll("/+$", "");
        String aud = text(p, "cloudflare.aud", missing);
        String keyText = text(p, "secretKey", missing);
        if (!missing.isEmpty()) {
            throw new NotReady("Fill in " + String.join(", ", missing) + " in " + file.toAbsolutePath() + ", then start the panel again.");
        }
        URI u = URI.create(publicUrl);
        if (!"https".equals(u.getScheme()) || u.getHost() == null) {
            throw new NotReady("publicUrl must be the https:// address staff open, like https://staff.buildsecureprotect.com");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(keyText);
        } catch (IllegalArgumentException e) {
            key = new byte[0];
        }
        if (key.length != SecretBox.KEY_BYTES) {
            throw new NotReady("secretKey is not a key this panel made. Restore it from your backup of " + FILE + ".");
        }
        if (actionKey.length != SecretBox.KEY_BYTES) {
            throw new NotReady("actionKey is not a key this panel made. Empty it to make a new one (then copy it to every game server again).");
        }
        String allocated = System.getenv("SERVER_PORT"); // Pterodactyl's allocation, when listen.port is left empty
        int defaultPort = allocated != null && allocated.matches("\\d{2,5}") ? Integer.parseInt(allocated) : 8100;
        if (p.getProperty("database.host", "").isBlank() || p.getProperty("redis.host", "").isBlank()) {
            throw new NotReady("database.host and redis.host are empty in " + FILE + ": fill in the addresses the game servers use (seen from this container), then start the panel again.");
        }
        return new PanelConfig(p.getProperty("listen.host", "0.0.0.0").trim(), number(p, "listen.port", defaultPort), publicUrl, team, aud,
                p.getProperty("database.host", "localhost").trim(), number(p, "database.port", 3306), p.getProperty("database.name", "bsp").trim(), p.getProperty("database.panelName", p.getProperty("database.name", "bsp")).trim(),
                p.getProperty("database.user", "bsp").trim(), p.getProperty("database.password", ""), Boolean.parseBoolean(p.getProperty("database.ssl", "false").trim()),
                p.getProperty("database.tablePrefix", "bsp_su_").trim(),
                p.getProperty("redis.host", "localhost").trim(), number(p, "redis.port", 6379), p.getProperty("redis.username", "").trim(),
                p.getProperty("redis.password", ""), p.getProperty("redis.channel", "network").trim(), key, actionKey, signingKey, panelKey,
                p.getProperty("map.root", "").trim());
    }

    /** The issuer Cloudflare writes in its tokens. */
    public String issuer() {
        return "https://" + teamDomain;
    }

    /** The web channel the game servers publish on (BSP-Server Utilities' {@code RedisLink.Settings.webChannel()}). */
    public String webChannel() {
        return "bsp_su:" + redisChannel + ":web";
    }

    /** The game servers' own channel (actions go there from SU-35). */
    public String serverChannel() {
        return "bsp_su:" + redisChannel;
    }

    private static String text(Properties p, String k, List<String> missing) {
        String v = p.getProperty(k, "").trim();
        if (v.isEmpty()) {
            missing.add(k);
        }
        return v;
    }

    private static int number(Properties p, String k, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(k, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void ownerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // not a POSIX file system: left as the system made it
        }
    }

    /** Never prints passwords or the key. */
    @Override
    public String toString() {
        return "panel on " + listenHost + ":" + listenPort + " for " + publicUrl + ", Cloudflare team " + teamDomain + ", database " + dbHost + ":" + dbPort + "/" + dbName
                + ", Redis " + redisHost + ":" + redisPort + " channel " + webChannel() + (mapRoot.isEmpty() ? ", no map" : ", map from " + mapRoot);
    }
}
