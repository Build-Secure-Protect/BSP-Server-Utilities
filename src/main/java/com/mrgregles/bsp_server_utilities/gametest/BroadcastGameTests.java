package com.mrgregles.bsp_server_utilities.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.announce.Broadcast;
import com.mrgregles.bsp_server_utilities.check.ClientCheckService;
import com.mrgregles.bsp_server_utilities.check.Verdict;
import com.mrgregles.bsp_server_utilities.setup.Feature;
import com.mrgregles.bsp_server_utilities.setup.Switches;
import com.mrgregles.bsp_server_utilities.storage.MemoryBackend;
import com.mrgregles.bsp_server_utilities.storage.Storage;
import com.mrgregles.bsp_server_utilities.storage.StorageBackend;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server announcements (owner decisions D233 to D245): colours by name, everyday name and hex; the title's split and
 * the text kept clean; chat for everyone here with the gold prefix and the colour, a title with its subtitle written in
 * chat too, one named player only; a player not here, or online nowhere, refused; {@code network} reaching the other
 * servers through the notice, and a named player on another server; without shared storage this server only; only
 * admins and the console may use it.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class BroadcastGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;

    private static CommandSourceStack console(MinecraftServer server) {
        return server.createCommandSourceStack().withSuppressedOutput();
    }

    @GameTest(template = EMPTY)
    public void colours_are_read_by_name_everyday_name_and_hex_and_the_text_is_kept_clean(GameTestHelper h) {
        h.assertTrue(Broadcast.colour("red") == 0xFF5555 && Broadcast.colour("White") == 0xFFFFFF && Broadcast.colour("dark_blue") == 0x0000AA,
                "Minecraft's names, any case (D234)");
        h.assertTrue(Broadcast.colour("grey") == 0xAAAAAA && Broadcast.colour("orange") == 0xFFAA00 && Broadcast.colour("pink") == 0xFF55FF
                && Broadcast.colour("cyan") == 0x55FFFF && Broadcast.colour("purple") == 0xAA00AA, "everyday names mapped onto them");
        h.assertTrue(Broadcast.colour("#ff8800") == 0xFF8800 && Broadcast.colour("1E90FF") == 0x1E90FF, "#RRGGBB and RRGGBB");
        h.assertTrue(Broadcast.colour("rainbow") == null && Broadcast.colour("#12345") == null, "anything else is not a colour");
        h.assertTrue(Broadcast.colourNames().size() == 16 && Broadcast.colourNames().contains("light_purple"), "the 16 names are offered");

        Broadcast.Announcement t = new Broadcast.Announcement(true, 0xFF5555, "", "Restart | in 5 minutes");
        h.assertTrue(t.titleText().equals("Restart") && t.subtitleText().equals("in 5 minutes") && t.chatText().equals("Restart in 5 minutes"),
                "a | splits the title from its subtitle; chat reads both (D236, D238)");
        h.assertTrue(Broadcast.Announcement.decode(t.encode()).equals(t), "an announcement round trips");
        h.assertTrue(Broadcast.Announcement.decode("shout\nFF0000\n\nhi") == null, "a bad notice is ignored");
        h.assertTrue(Broadcast.clean("  hi§k\nthere ").equals("hi k there") && Broadcast.clean("x".repeat(400)).length() == Broadcast.MAX_TEXT,
                "no control characters or section signs, at most 256 characters");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void chat_and_titles_reach_everyone_here_or_one_named_player(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer pip = TestPlayers.mockServerPlayer(h), kat = TestPlayers.mockServerPlayer(h);
        Map<UUID, List<Broadcast.Shown>> shown = new HashMap<>();
        Broadcast.useForTests(() -> List.of(pip, kat), (p, s) -> shown.computeIfAbsent(p.getUUID(), k -> new ArrayList<>()).add(s));
        try {
            int n = Broadcast.send(console(server), false, "red", "all", false, "Restart in &l5&r minutes");
            Broadcast.Shown s = shown.get(pip.getUUID()).get(0);
            h.assertTrue(n == 2 && shown.get(kat.getUUID()).size() == 1, "chat for all on this server reaches both players");
            String line = s.chat().getString();
            h.assertTrue((line.startsWith("[Announcement] ") || line.startsWith("message.bsp_server_utilities.broadcast.prefix ")) && line.endsWith("Restart in §l5§r minutes"),
                    "after the prefix, the message with & codes as formatting (D235, D240): " + line);
            h.assertTrue(s.chat().getStyle().getColor() == TextColor.fromLegacyFormat(ChatFormatting.GOLD), "the prefix in gold");
            h.assertTrue(s.chat().getSiblings().get(1).getStyle().getColor().getValue() == 0xFF5555 && s.title() == null, "the message in red, and no title");

            shown.clear();
            n = Broadcast.send(console(server), true, "gold", kat.getGameProfile().getName(), false, "Welcome back | the vote is open");
            Broadcast.Shown t = shown.get(kat.getUUID()).get(0);
            h.assertTrue(n == 1 && !shown.containsKey(pip.getUUID()), "a named player alone (case aside)");
            h.assertTrue(t.title().getString().equals("Welcome back") && t.subtitle().getString().equals("the vote is open") && t.title().getStyle().getColor().getValue() == 0xFFAA00,
                    "the title and the subtitle under it, in the colour");
            h.assertTrue(t.chat().getString().endsWith("Welcome back the vote is open"), "and written in chat too (D238)");

            shown.clear();
            h.assertTrue(Broadcast.send(console(server), false, "red", "Nobody_Here", false, "hello") == 0 && shown.isEmpty(), "a player not on this server: nothing sent");
            h.assertTrue(Broadcast.send(console(server), false, "plaid", "all", false, "hello") == 0 && shown.isEmpty(), "an unknown colour is refused");
            h.assertTrue(Broadcast.send(console(server), true, "red", "all", false, " | ") == 0 && shown.isEmpty(), "and so is a title with nothing to show");
        } finally {
            Broadcast.useReal();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void network_reaches_the_other_servers_and_a_named_player_wherever_they_are(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer pip = TestPlayers.mockServerPlayer(h);
        Map<UUID, Integer> shown = new HashMap<>();
        Broadcast.useForTests(() -> List.of(pip), (p, s) -> shown.merge(p.getUUID(), 1, Integer::sum));
        MemoryBackend db = new MemoryBackend();
        Storage alpha = Storage.startWith(server, db, "alpha");
        alpha.drain();
        UUID remote = UUID.randomUUID();
        try {
            // without shared storage: this server only
            h.assertTrue(Broadcast.send(console(server), false, "aqua", "all", true, "Hello network") == 1 && shown.get(pip.getUUID()) == 1, "no storage: shown here");
            h.assertTrue(Broadcast.send(console(server), false, "aqua", "Kat_Miner", true, "hi") == 0, "and a player elsewhere cannot be reached");

            Storage.useForTests(alpha);
            shown.clear();
            Broadcast.send(console(server), true, "red", "all", true, "Restart | in 5 minutes");
            alpha.drain();
            List<StorageBackend.Notice> sent = db.fetchNoticesAfter(0, 50).stream().filter(n -> n.kind().equals(Broadcast.KIND)).toList();
            h.assertTrue(shown.get(pip.getUUID()) == 1 && sent.size() == 1, "network: shown here and one notice for the other servers");
            Broadcast.Announcement a = Broadcast.Announcement.decode(sent.get(0).payload());
            h.assertTrue(a != null && a.title() && a.player().isEmpty() && a.colour() == 0xFF5555 && a.text().equals("Restart | in 5 minutes"), "carrying all of it: " + a);
            alpha.deliverForTests(new StorageBackend.Notice(0, UUID.randomUUID().toString(), System.currentTimeMillis(), "beta", Broadcast.KIND, sent.get(0).payload()));
            h.assertTrue(shown.get(pip.getUUID()) == 2, "another server's announcement is shown to this server's players");

            // a named player on another server, as the client check knows them
            String payload = String.join("\n", "beta", remote.toString(), "Kat_Miner", "123",
                    new Verdict(List.of(), List.of(), List.of(), List.of(), true, List.of(), false).encode(), ClientCheckGameTests.clean("b1").toText(), "forge");
            alpha.deliverForTests(new StorageBackend.Notice(0, UUID.randomUUID().toString(), System.currentTimeMillis(), "beta", ClientCheckService.KIND_REPORT, payload));
            shown.clear();
            int before = db.fetchNoticesAfter(0, 100).size();
            h.assertTrue(Broadcast.send(console(server), false, "green", "kat_miner", false, "hi") == 0, "server only: Kat_Miner is not here");
            Broadcast.send(console(server), false, "green", "kat_miner", true, "Your build won!");
            alpha.drain();
            List<StorageBackend.Notice> toKat = db.fetchNoticesAfter(0, 100).stream().skip(before).filter(n -> n.kind().equals(Broadcast.KIND)).toList();
            h.assertTrue(shown.isEmpty() && toKat.size() == 1 && Broadcast.Announcement.decode(toKat.get(0).payload()).player().equals("Kat_Miner"),
                    "network: sent to the server Kat_Miner is on, by their name, and nobody here sees it");
            alpha.deliverForTests(new StorageBackend.Notice(0, UUID.randomUUID().toString(), System.currentTimeMillis(), "beta", Broadcast.KIND,
                    new Broadcast.Announcement(false, 0x55FF55, pip.getGameProfile().getName(), "for you").encode()));
            h.assertTrue(shown.get(pip.getUUID()) == 1, "a notice naming a player here reaches them");
            alpha.deliverForTests(new StorageBackend.Notice(0, UUID.randomUUID().toString(), System.currentTimeMillis(), "beta", Broadcast.KIND,
                    new Broadcast.Announcement(false, 0x55FF55, "Someone_Else", "not for you").encode()));
            h.assertTrue(shown.get(pip.getUUID()) == 1, "one naming someone else is not shown here");

            // Kat_Miner's server says they left (a server gone quiet is left out the same way)
            alpha.deliverForTests(new StorageBackend.Notice(0, UUID.randomUUID().toString(), System.currentTimeMillis(), "beta", ClientCheckService.KIND_PRESENT, "beta\n"));
            int count = db.fetchNoticesAfter(0, 100).size();
            Broadcast.send(console(server), false, "green", "Kat_Miner", true, "hi");
            alpha.drain();
            h.assertTrue(db.fetchNoticesAfter(0, 100).size() == count, "a player who has left another server is no longer reached there");
            Broadcast.send(console(server), false, "green", "Nobody_Online", true, "hi");
            alpha.drain();
            h.assertTrue(db.fetchNoticesAfter(0, 100).size() == count, "a player online nowhere is refused and nothing is sent (D242)");
            java.util.Set<Feature> off = Switches.all();
            try {
                EnumSet<Feature> withCheckOff = off.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(off);
                withCheckOff.add(Feature.CHECK);
                Switches.set(withCheckOff);
                Broadcast.send(console(server), false, "green", "Nobody_Online", true, "hi");
                alpha.drain();
                h.assertTrue(db.fetchNoticesAfter(0, 100).size() == count + 1, "with the client check off nobody knows who is where: the other servers look for them");
            } finally {
                Switches.set(off);
            }
        } finally {
            ClientCheckService.forget(remote);
            Broadcast.useReal();
            Storage.useForTests(null);
            alpha.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void only_admins_and_the_console_may_broadcast(GameTestHelper h) throws Exception {
        MinecraftServer server = h.getLevel().getServer();
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        List<UUID> shown = new ArrayList<>();
        Broadcast.useForTests(() -> List.of(player), (p, s) -> shown.add(p.getUUID()));
        try {
            boolean refused = false;
            try {
                server.getCommands().getDispatcher().execute("broadcast chat red all server hello", player.createCommandSourceStack());
            } catch (CommandSyntaxException e) {
                refused = true;
            }
            h.assertTrue(refused && shown.isEmpty(), "a player who is not an admin cannot use it (D233)");
            int n = server.getCommands().getDispatcher().execute("broadcast title \"#FF8800\" all server Restart soon | save your work", console(server));
            h.assertTrue(n == 1 && shown.size() == 1, "the console can, colour in quotes as #RRGGBB, the message as plain words");
            n = server.getCommands().getDispatcher().execute("broadcast chat dark_red " + player.getGameProfile().getName() + " network You have mail", console(server));
            h.assertTrue(n == 1 && shown.size() == 2, "a named player here, with network");
        } finally {
            Broadcast.useReal();
        }
        h.succeed();
    }
}
