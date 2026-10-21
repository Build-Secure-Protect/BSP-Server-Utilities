package com.mrgregles.bsp_server_utilities.gametest;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import com.mrgregles.bsp_server_utilities.chunks.ChunkAdmin;
import com.mrgregles.bsp_server_utilities.chunks.ChunkData;
import com.mrgregles.bsp_server_utilities.network.AdminChunksEditPacket;
import com.mrgregles.bsp_server_utilities.network.AdminChunksPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.players.ServerOpListEntry;
import com.mrgregles.bsp_server_utilities.chunks.ChunkOwners;
import com.mrgregles.bsp_server_utilities.chunks.ChunkService;
import com.mrgregles.bsp_server_utilities.chunks.ChunkSettings;
import com.mrgregles.bsp_server_utilities.chunks.ChunkTickets;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chunk unloading (owner decisions D86 to D99): a machine's Forge ticket is taken away once its owner has been away for
 * the set minutes and given back when they join, never BSP-Core's or an exempt mod's, again when a mod adds it back,
 * not when the machine is gone; always loaded players, RESTORE NOW holding, UNLOAD NOW; owners read from saved data;
 * machines with no owner listed and unloaded by hand; the settings within their limits. Tickets are made under this
 * mod's own id (Forge accepts only loaded mods' ids), with a chest as the machine.
 */
@GameTestHolder(BSPServerUtilities.MODID)
@PrefixGameTestTemplate(false)
public class ChunkGameTests {
    private static final String EMPTY = SetupGameTests.EMPTY;
    private static final long MIN = 60_000L;

    private record Rig(MinecraftServer server, ServerLevel level, BlockPos pos, ChunkTickets.Ticket ticket, ChunkSettings before, AtomicLong now, List<ServerPlayer> online) {
        boolean live() {
            return ChunkTickets.live(level).stream().anyMatch(t -> t.key().equals(ticket.key()));
        }

        boolean suspended() {
            return ChunkData.get(server).suspended().containsKey(ticket.key());
        }

        void settings(ChunkSettings s) {
            ChunkData.get(server).setSettings(s.withRevision(before.revision()).sanitised());
        }

        void close() {
            ForgeChunkManager.forceChunk(level, ticket.modId(), pos, ticket.chunkPos().x, ticket.chunkPos().z, false, false);
            ChunkData.get(server).takeSuspended(ticket.key());
            ChunkData.get(server).putPlacer(ChunkOwners.posKey(level, pos), null);
            ChunkData.get(server).setSettings(before);
            ChunkService.drainRestores(server, 1000);
            ChunkService.useReal();
        }
    }

    /** A chest in the test area held loaded by a ticket under {@code modId}, its placer recorded when given. */
    private static Rig rig(GameTestHelper h, String modId, BlockPos rel, ServerPlayer placer) {
        MinecraftServer server = h.getLevel().getServer();
        ServerLevel level = h.getLevel();
        BlockPos pos = h.absolutePos(rel);
        h.setBlock(rel, Blocks.CHEST);
        ChunkPos c = new ChunkPos(pos);
        ForgeChunkManager.forceChunk(level, modId, pos, c.x, c.z, true, false);
        if (placer != null) {
            ChunkData.get(server).putPlacer(ChunkOwners.posKey(level, pos), placer.getUUID());
        }
        AtomicLong now = new AtomicLong(System.currentTimeMillis());
        List<ServerPlayer> online = new ArrayList<>();
        ChunkService.useForTests(now::get, () -> online);
        Rig r = new Rig(server, level, pos, ChunkTickets.Ticket.ofBlock(level, modId, pos, c.toLong(), false), ChunkData.get(server).settings(), now, online);
        r.settings(new ChunkSettings(true, 5, List.of(), List.of(), 0));
        return r;
    }

    @GameTest(template = EMPTY)
    public void a_machines_chunks_unload_when_its_owner_is_away_and_come_back_when_they_join(GameTestHelper h) {
        ServerPlayer owner = TestPlayers.mockServerPlayer(h);
        Rig r = rig(h, BSPServerUtilities.MODID, new BlockPos(2, 2, 2), owner);
        try {
            h.assertTrue(r.live(), "the machine's ticket is there");
            ChunkService.onLogout(owner);
            r.now().addAndGet(4 * MIN);
            ChunkService.sweep(r.server());
            h.assertTrue(r.live() && !r.suspended(), "four minutes away: still loaded");
            r.now().addAndGet(MIN);
            ChunkService.sweep(r.server());
            h.assertTrue(!r.live() && r.suspended(), "five minutes away: the ticket is taken and kept (D88)");
            h.assertTrue(owner.getUUID().equals(ChunkData.get(r.server()).suspended().get(r.ticket().key()).player()), "kept as the owner's");

            ForgeChunkManager.forceChunk(r.level(), r.ticket().modId(), r.pos(), r.ticket().chunkPos().x, r.ticket().chunkPos().z, true, false);
            ChunkService.sweep(r.server());
            h.assertTrue(!r.live() && r.suspended(), "a ticket the mod adds back while the owner is away is taken again");

            r.online().add(owner);
            ChunkService.onLogin(owner);
            ChunkService.drainRestores(r.server(), 1000);
            h.assertTrue(r.live() && !r.suspended(), "back as it was when the owner joins (D89)");
            ChunkService.sweep(r.server());
            h.assertTrue(r.live(), "and kept while they are here");
        } finally {
            r.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void bsp_core_and_exempt_mods_are_never_unloaded(GameTestHelper h) {
        ServerPlayer owner = TestPlayers.mockServerPlayer(h);
        Rig r = rig(h, BSPServerUtilities.MODID, new BlockPos(3, 2, 3), owner);
        Rig core = ModList.get().isLoaded(ChunkTickets.BSP_CORE) ? rig(h, ChunkTickets.BSP_CORE, new BlockPos(5, 2, 5), owner) : null;
        try {
            ChunkService.useForTests(r.now()::get, r::online);
            ChunkService.onLogout(owner);
            r.now().addAndGet(30 * MIN);
            r.settings(new ChunkSettings(true, 5, List.of(BSPServerUtilities.MODID), List.of(), 0));
            ChunkService.sweep(r.server());
            h.assertTrue(r.live(), "a mod the admins exempt stays loaded");
            if (core != null) {
                h.assertTrue(core.live(), "BSP-Core's tickets are never touched (the brief)");
            }
            h.assertTrue(new ChunkSettings(true, 5, List.of("bsp_core", "Mekanism", "bad id!"), List.of(), 0).sanitised().exemptMods().equals(List.of("mekanism")),
                    "BSP-Core is not a choice; ids are kept lower case and well formed");

            r.settings(new ChunkSettings(true, 5, List.of(), List.of(), 0));
            ChunkService.sweep(r.server());
            h.assertTrue(r.suspended(), "not exempt any more: taken");
            ChunkService.setSettings(r.server(), new ChunkSettings(true, 5, List.of(BSPServerUtilities.MODID), List.of(), 0));
            ChunkService.drainRestores(r.server(), 1000);
            h.assertTrue(r.live() && !r.suspended(), "exempting a mod gives its tickets back");
        } finally {
            if (core != null) {
                core.close();
            }
            r.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void a_machine_broken_while_unloaded_is_forgotten(GameTestHelper h) {
        ServerPlayer owner = TestPlayers.mockServerPlayer(h);
        Rig r = rig(h, BSPServerUtilities.MODID, new BlockPos(4, 2, 2), owner);
        try {
            ChunkService.onLogout(owner);
            r.now().addAndGet(6 * MIN);
            ChunkService.sweep(r.server());
            h.assertTrue(r.suspended(), "taken");
            h.setBlock(new BlockPos(4, 2, 2), Blocks.AIR);
            r.online().add(owner);
            ChunkService.onLogin(owner);
            ChunkService.drainRestores(r.server(), 1000);
            h.assertTrue(!r.live() && !r.suspended(), "the machine is gone: nothing comes back and nothing is kept (D98)");
            h.assertTrue(ChunkData.get(r.server()).placer(ChunkOwners.posKey(r.level(), r.pos())) == null, "its placer record goes too");
        } finally {
            r.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void always_loaded_players_restore_now_and_unload_now(GameTestHelper h) {
        ServerPlayer owner = TestPlayers.mockServerPlayer(h);
        Rig r = rig(h, BSPServerUtilities.MODID, new BlockPos(2, 2, 5), owner);
        try {
            ChunkSettings.Player me = new ChunkSettings.Player(owner.getUUID(), owner.getGameProfile().getName());
            r.settings(new ChunkSettings(true, 5, List.of(), List.of(me), 0));
            ChunkService.onLogout(owner);
            r.now().addAndGet(30 * MIN);
            ChunkService.sweep(r.server());
            h.assertTrue(r.live(), "an always loaded player's machines stay loaded (D90)");

            r.settings(new ChunkSettings(true, 5, List.of(), List.of(), 0));
            ChunkService.unloadNow(r.server(), owner.getUUID());
            h.assertTrue(r.suspended(), "UNLOAD NOW takes them at once (D94)");
            ChunkService.setSettings(r.server(), new ChunkSettings(true, 5, List.of(), List.of(me), 0));
            ChunkService.drainRestores(r.server(), 1000);
            h.assertTrue(r.live() && !r.suspended(), "marking a player always loaded gives theirs back at once (D99)");

            r.settings(new ChunkSettings(true, 5, List.of(), List.of(), 0));
            ChunkService.sweep(r.server());
            h.assertTrue(r.suspended(), "not always loaded any more: taken");
            ChunkService.restoreNow(r.server(), owner.getUUID());
            ChunkService.drainRestores(r.server(), 1000);
            r.now().addAndGet(30 * MIN);
            ChunkService.sweep(r.server());
            h.assertTrue(r.live(), "RESTORE NOW holds while the player is still away (D97)");
            r.online().add(owner);
            ChunkService.onLogin(owner);
            r.online().remove(owner);
            ChunkService.onLogout(owner);
            r.now().addAndGet(6 * MIN);
            ChunkService.sweep(r.server());
            h.assertTrue(r.suspended(), "until they join and leave again");
        } finally {
            r.close();
        }
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void owners_are_read_from_saved_data_and_machines_with_none_are_unloaded_by_hand(GameTestHelper h) {
        UUID id = UUID.randomUUID();
        CompoundTag mek = new CompoundTag();
        CompoundTag security = new CompoundTag();
        security.put("ownerUUID", NbtUtils.createUUID(id));
        mek.put("securityComponent", security);
        h.assertTrue(id.equals(ChunkOwners.savedOwner(mek)), "an ownerUUID a few levels down");
        CompoundTag skull = new CompoundTag();
        CompoundTag profile = new CompoundTag();
        profile.put("Id", NbtUtils.createUUID(id));
        profile.putString("Name", "Pip");
        skull.put("SkullOwner", profile);
        h.assertTrue(id.equals(ChunkOwners.savedOwner(skull)), "anything inside a compound under an owner key");
        CompoundTag pair = new CompoundTag();
        pair.putLong("PlayerUUIDMost", id.getMostSignificantBits());
        pair.putLong("PlayerUUIDLeast", id.getLeastSignificantBits());
        h.assertTrue(id.equals(ChunkOwners.savedOwner(pair)), "a Most / Least pair");
        CompoundTag flux = new CompoundTag();
        flux.putString("playerUUID", id.toString());
        h.assertTrue(id.equals(ChunkOwners.savedOwner(flux)), "a UUID string");
        CompoundTag none = new CompoundTag();
        none.putInt("energy", 5);
        none.put("Items", new CompoundTag());
        none.putString("ownerName", "Pip");
        h.assertTrue(ChunkOwners.savedOwner(none) == null, "no UUID, no owner");

        Rig r = rig(h, BSPServerUtilities.MODID, new BlockPos(6, 2, 2), null);
        try {
            r.now().addAndGet(60 * MIN);
            ChunkService.sweep(r.server());
            ChunkService.Live l = ChunkService.live(r.server()).stream().filter(x -> x.ticket().key().equals(r.ticket().key())).findFirst().orElse(null);
            h.assertTrue(r.live() && l != null && l.player() == null, "a machine with no owner found stays loaded and is listed (D87)");
            h.assertTrue(ChunkService.unloadTicket(r.server(), r.ticket().key()) && r.suspended() && !r.live(), "UNLOAD takes it (D96)");
            ForgeChunkManager.forceChunk(r.level(), r.ticket().modId(), r.pos(), r.ticket().chunkPos().x, r.ticket().chunkPos().z, true, false);
            ChunkService.sweep(r.server());
            h.assertTrue(!r.live() && r.suspended(), "and keeps it taken when the mod adds it back");
            h.assertTrue(ChunkService.restoreTicket(r.server(), r.ticket().key()), "RESTORE");
            ChunkService.drainRestores(r.server(), 1000);
            h.assertTrue(r.live() && !r.suspended(), "gives it back");
        } finally {
            r.close();
        }

        ChunkSettings odd = new ChunkSettings(true, 0, List.of("mekanism"), List.of(new ChunkSettings.Player(id, "Pip!")), 3).sanitised();
        h.assertTrue(odd.minutes() == 1 && new ChunkSettings(true, 99, List.of(), List.of(), 0).sanitised().minutes() == 60, "the minutes keep to 1 to 60 (D95)");
        h.assertTrue(odd.alwaysLoaded().get(0).name().equals("Pip") && ChunkSettings.decode(odd.encode()).equals(odd) && ChunkSettings.load(odd.save()).equals(odd), "settings round trip");
        h.succeed();
    }

    @GameTest(template = EMPTY)
    public void the_chunks_tab_lists_players_and_machines_and_only_admins_act(GameTestHelper h) {
        ServerPlayer owner = TestPlayers.mockServerPlayer(h);
        ServerPlayer admin = TestPlayers.mockServerPlayer(h);
        ServerPlayer player = TestPlayers.mockServerPlayer(h);
        Rig r = rig(h, BSPServerUtilities.MODID, new BlockPos(7, 2, 6), owner);
        Rig orphan = rig(h, BSPServerUtilities.MODID, new BlockPos(2, 2, 8), null);
        ChunkService.useForTests(r.now()::get, r::online);
        r.server().getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            ChunkService.onLogout(owner);
            r.now().addAndGet(2 * MIN);
            AdminChunksPacket tab = ChunkAdmin.packet(r.server());
            AdminChunksPacket.PlayerRow row = tab.players().stream().filter(p -> p.id().equals(owner.getUUID())).findFirst().orElse(null);
            h.assertTrue(row != null && !row.online() && row.awayMs() == 2 * MIN && row.loaded() == 1 && row.unloaded() == 0 && row.mods().contains(BSPServerUtilities.MODID),
                    "the owner: away two minutes, one chunk loaded, by which mod: " + row);
            String orphanKey = ChunkAdmin.machineKey(orphan.ticket());
            h.assertTrue(tab.machines().stream().anyMatch(m -> m.key().equals(orphanKey) && m.player().equals(AdminChunksPacket.NOBODY) && m.loaded() == 1),
                    "the machine with no owner is listed under nobody");
            h.assertTrue(tab.mods().contains(BSPServerUtilities.MODID) && !tab.mods().contains(ChunkTickets.BSP_CORE), "the mods for the chips, BSP-Core left out");
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            tab.encode(buf);
            h.assertTrue(AdminChunksPacket.decode(buf).equals(tab), "the tab's data round trips");
            AdminChunksEditPacket unload = AdminChunksEditPacket.of(AdminChunksEditPacket.Action.UNLOAD_PLAYER, owner.getUUID().toString());
            buf = new FriendlyByteBuf(Unpooled.buffer());
            unload.encode(buf);
            h.assertTrue(AdminChunksEditPacket.decode(buf).equals(unload), "an action round trips");

            h.assertFalse(ChunkAdmin.apply(player, unload) || r.suspended(), "a player who may not edit does nothing");
            h.assertTrue(ChunkAdmin.apply(admin, unload) && r.suspended() && !r.live(), "UNLOAD NOW from the tab");
            ChunkAdmin.apply(admin, AdminChunksEditPacket.of(AdminChunksEditPacket.Action.RESTORE_PLAYER, owner.getUUID().toString()));
            h.assertTrue(r.live() && !r.suspended(), "RESTORE NOW from the tab");
            ChunkAdmin.apply(admin, AdminChunksEditPacket.of(AdminChunksEditPacket.Action.UNLOAD_MACHINE, orphanKey));
            h.assertTrue(orphan.suspended() && !orphan.live(), "UNLOAD on a machine with no owner");
            ChunkAdmin.apply(admin, AdminChunksEditPacket.of(AdminChunksEditPacket.Action.RESTORE_MACHINE, orphanKey));
            h.assertTrue(orphan.live() && !orphan.suspended(), "and RESTORE");
            ChunkAdmin.apply(admin, new AdminChunksEditPacket(AdminChunksEditPacket.Action.SETTINGS, "", new ChunkSettings(true, 12, List.of("mekanism"), List.of(), 0)));
            ChunkSettings now = ChunkService.settings(r.server());
            h.assertTrue(now.minutes() == 12 && now.exemptMods().equals(List.of("mekanism")) && now.revision() > r.before().revision(), "the settings become the network's: " + now);
        } finally {
            r.server().getPlayerList().getOps().remove(admin.getGameProfile());
            orphan.close();
            r.close();
        }
        h.succeed();
    }
}
