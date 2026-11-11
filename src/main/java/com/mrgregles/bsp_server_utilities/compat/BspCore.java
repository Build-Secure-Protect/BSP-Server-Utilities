package com.mrgregles.bsp_server_utilities.compat;

import com.mrgregles.bsp_server_utilities.BSPServerUtilities;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;

import java.util.Optional;

/**
 * The only class that touches BSP-Core, which is optional (owner decision D2). Every method answers sensibly when
 * BSP-Core is not installed; a BSP-Core older or newer than expected (a missing class or method) counts as absent.
 */
public final class BspCore {
    public static final String MODID = "bsp_core";
    private static final String CONFIG_CLASS = "com.mrgregles.bsp_core.BSPConfig";

    private BspCore() {
    }

    public static boolean isLoaded() {
        return ModList.get() != null && ModList.get().isLoaded(MODID);
    }

    /**
     * A value of BSP-Core's configs by the name of its field on {@code BSPConfig} ({@code STORAGE_SERVER_ID},
     * {@code STORAGE_HOST}, {@code REDIS_ENABLED}, ...). Since BSP-Core moved its connection settings to
     * {@code config/bsp_core-network.toml}, the connection fields are aliases of that file's values; the names still answer. Empty when BSP-Core is absent, the field is missing or the
     * config is not loaded. Reflection, because the config is not in BSP-Core's api (BSP-InventorySync reads it the same way).
     */
    public static <T> Optional<T> setting(String field, Class<T> type) {
        if (!isLoaded()) {
            return Optional.empty();
        }
        try {
            Object value = Class.forName(CONFIG_CLASS).getField(field).get(null);
            if (value instanceof ForgeConfigSpec.ConfigValue<?> cv) {
                Object got = cv.get();
                return type.isInstance(got) ? Optional.of(type.cast(got)) : Optional.empty();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            BSPServerUtilities.LOGGER.debug("BSP-Core setting {} not readable: {}", field, e.toString());
        }
        return Optional.empty();
    }

    /** A player's BSP-Core team as the staff web panel shows it: its id, name and colour (0xRRGGBB). */
    public record TeamInfo(java.util.UUID id, String name, int colour) {
    }

    /** The player's team, through BSP-Core's api (network-wide); empty without BSP-Core, without a team, or on any failure. */
    public static Optional<TeamInfo> team(net.minecraft.server.MinecraftServer server, java.util.UUID player) {
        if (!isLoaded()) {
            return Optional.empty();
        }
        try {
            return com.mrgregles.bsp_core.api.BspTeams.teamOf(server, player).map(t -> {
                Integer rgb = t.colour() == null ? null : t.colour().getColor();
                return new TeamInfo(t.id(), t.name(), rgb == null ? 0xFFFFFF : rgb);
            });
        } catch (LinkageError | RuntimeException e) {
            BSPServerUtilities.LOGGER.debug("BSP-Core team lookup failed: {}", e.toString());
            return Optional.empty();
        }
    }

    /** Whether BSP-Core counts the player as an admin ({@code admin.admins}, or operators when that list is empty). */
    public static boolean isAdmin(Player player) {
        if (!isLoaded()) {
            return false;
        }
        try {
            return com.mrgregles.bsp_core.api.BspPlayers.isAdmin(player);
        } catch (LinkageError | RuntimeException e) {
            BSPServerUtilities.LOGGER.debug("BSP-Core admin check failed: {}", e.toString());
            return false;
        }
    }

    /**
     * A board's line for {@link #registerBoard}: the text at the left (after {@code lead} in {@code leadColour} when
     * {@code lead} is not empty), the value at the right, the value's colour (0xRRGGBB).
     */
    public record Line(String lead, int leadColour, String text, String value, int colour) {
        public Line(String text, String value, int colour) {
            this("", 0, text, value, colour);
        }

        net.minecraft.network.chat.Component left() {
            net.minecraft.network.chat.MutableComponent c = net.minecraft.network.chat.Component.empty();
            if (!lead.isEmpty()) {
                c.append(net.minecraft.network.chat.Component.literal(lead + " ").withStyle(s -> s.withColor(leadColour)));
            }
            return c.append(net.minecraft.network.chat.Component.literal(text));
        }
    }

    /**
     * Client side: shows a board on BSP-Core's Score Screens (owner decision D60), in BSP-Core's own board look; nothing
     * when BSP-Core is absent or has no boards.
     */
    public static void registerBoard(String path, String titleKey, java.util.function.Supplier<java.util.List<Line>> lines) {
        registerBoard(path, net.minecraft.network.chat.Component.translatable(titleKey), lines);
    }

    /** As {@link #registerBoard(String, String, java.util.function.Supplier)} under a given title; registering the same path again replaces the board in place. */
    public static void registerBoard(String path, net.minecraft.network.chat.Component title, java.util.function.Supplier<java.util.List<Line>> lines) {
        if (!isLoaded()) {
            return;
        }
        try {
            com.mrgregles.bsp_core.api.BspBoards.register(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(BSPServerUtilities.MODID, path),
                    title, () -> lines.get().stream()
                            .map(l -> new com.mrgregles.bsp_core.api.BspBoards.BoardLine(l.left(),
                                    l.value().isEmpty() ? null : net.minecraft.network.chat.Component.literal(l.value()), net.minecraft.world.item.ItemStack.EMPTY, l.colour()))
                            .toList());
        } catch (LinkageError | RuntimeException e) {
            BSPServerUtilities.LOGGER.debug("BSP-Core boards not reachable: {}", e.toString());
        }
    }

    /** Calls {@code then(player, kitName, how)} when BSP-Core gives a kit (its {@code KitEvent.Given}); nothing without BSP-Core. */
    public static void onKitGiven(TriConsumer then) {
        if (!isLoaded() || kitHooked) {
            return;
        }
        try {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((com.mrgregles.bsp_core.api.event.KitEvent.Given e) -> {
                net.minecraft.server.level.ServerPlayer p = e.getServer().getPlayerList().getPlayer(e.getPlayer());
                if (p != null) {
                    then.accept(p, e.getKitName(), e.getHow().name());
                }
            });
            kitHooked = true;
        } catch (LinkageError | RuntimeException e) {
            BSPServerUtilities.LOGGER.debug("BSP-Core kit events not reachable: {}", e.toString());
        }
    }

    private static boolean kitHooked;

    public interface TriConsumer {
        void accept(net.minecraft.server.level.ServerPlayer player, String kit, String how);
    }

    // ------------------------------------------------------------------ the market and the lottery (SU-50; BSP-Core 1.16's SU-BC1)

    /** The oldest BSP-Core with {@code BspMarket} and {@code BspLottery}. */
    public static final String ECONOMY_SINCE = "1.16.0";

    /** An open lot on the Market Board: the price in pieces for the whole lot; {@code serverId} where it was listed. */
    public record Lot(String id, java.util.UUID seller, String sellerName, long price, long listedMs, long endsMs, String serverId,
                      java.util.List<net.minecraft.world.item.ItemStack> items) {
    }

    /** An open buy order on the Wanted Board: {@code item} one plain stack of 1; {@code each} pieces per item. */
    public record Order(String id, java.util.UUID buyer, String buyerName, net.minecraft.world.item.ItemStack item, int wanted, int filled, long each,
                        long placedMs, long endsMs, String serverId) {
    }

    /** What this server holds of the market: both lists (newest first) and when each was last read whole (0: never). */
    public record Market(java.util.List<Lot> lots, java.util.List<Order> orders, long lotsReadMs, long ordersReadMs) {
    }

    /** One lottery win. */
    public record Win(String name, long pieces, long atMs) {
    }

    /** The lottery as this server sees it; {@code phase} IDLE, COUNTING or DRAWING. */
    public record Lottery(long pot, int entrants, String phase, java.util.List<Win> wins) {
    }

    /** What the staff panel's market and lottery need; the real one is BSP-Core 1.16+, tests give their own. Server thread only. */
    public interface Economy {
        /** Null when it could not be read at all (an error, not the database being down: that is empty lists and read times 0). */
        @org.jetbrains.annotations.Nullable
        Market market(net.minecraft.server.MinecraftServer server);

        /** Reads the market again, then runs {@code then} whether it worked or not (it may never run if the server stops). */
        void refresh(net.minecraft.server.MinecraftServer server, Runnable then);

        /** False on a network server whose database is not connected. */
        boolean connected();

        String phase();

        /** {@code then} gets null when the lottery could not be read at all. */
        void lottery(net.minecraft.server.MinecraftServer server, java.util.function.Consumer<Lottery> then);

        /** {@code then} gets null when the names could not be read at all. */
        void entrants(net.minecraft.server.MinecraftServer server, java.util.function.Consumer<java.util.List<String>> then);

        /** Draws as {@code /bsp lottery draw}; {@code then} gets ok, busy, unreachable, nobody or empty. No permission check. */
        void draw(net.minecraft.server.MinecraftServer server, String by, java.util.function.Consumer<String> then);
    }

    @org.jetbrains.annotations.Nullable
    private static Economy economyOverride;
    private static boolean economyForced;

    /** BSP-Core's market and lottery, or null without BSP-Core 1.16.0 or newer. */
    @org.jetbrains.annotations.Nullable
    public static Economy economy() {
        if (economyForced) {
            return economyOverride;
        }
        return hasEconomy() ? RealEconomy.INSTANCE : null;
    }

    private static Boolean economyChecked;

    private static boolean hasEconomy() {
        if (economyChecked != null) {
            return economyChecked;
        }
        if (ModList.get() == null || !isLoaded()) {
            return false; // not cached: asked again once mods are loaded
        }
        boolean ok;
        try {
            ok = ModList.get().getModContainerById(MODID)
                    .map(c -> c.getModInfo().getVersion().compareTo(new org.apache.maven.artifact.versioning.DefaultArtifactVersion(ECONOMY_SINCE)) >= 0)
                    .orElse(false);
            if (ok) {
                Class.forName("com.mrgregles.bsp_core.api.BspMarket");
                Class.forName("com.mrgregles.bsp_core.api.BspLottery");
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            ok = false;
        }
        economyChecked = ok;
        return ok;
    }

    /** Loaded only once BSP-Core 1.16+ is known to be there, so a server without it never touches BSP-Core's classes. */
    private static final class RealEconomy implements Economy {
        static final Economy INSTANCE = new RealEconomy();

        /**
         * A failure is answered through {@code then} only when BSP-Core failed before calling it: on a single server BSP-Core
         * calls {@code then} at once, inside our try, so an error thrown by our own callback must never call it a second
         * time with a made-up answer (audit of phase 5).
         */
        private static void failed(String what, Throwable e, java.util.concurrent.atomic.AtomicBoolean ran, Runnable fallback) {
            if (ran.get()) {
                BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: the staff panel's {} answer failed after BSP-Core answered: {}", what, e.toString());
            } else {
                BSPServerUtilities.LOGGER.debug("BSP-Core {} failed: {}", what, e.toString());
                fallback.run();
            }
        }

        @Override
        public Market market(net.minecraft.server.MinecraftServer server) {
            try {
                java.util.List<Lot> lots = com.mrgregles.bsp_core.api.BspMarket.openLots(server).stream()
                        .map(l -> new Lot(l.id(), l.seller(), l.sellerName(), l.price(), l.listedMs(), l.endsMs(), l.serverId(), l.items())).toList();
                java.util.List<Order> orders = com.mrgregles.bsp_core.api.BspMarket.openOrders(server).stream()
                        .map(o -> new Order(o.id(), o.buyer(), o.buyerName(), o.item(), o.wanted(), o.filled(), o.each(), o.placedMs(), o.endsMs(), o.serverId())).toList();
                return new Market(lots, orders, com.mrgregles.bsp_core.api.BspMarket.lotsReadMs(server), com.mrgregles.bsp_core.api.BspMarket.ordersReadMs(server));
            } catch (LinkageError | RuntimeException e) {
                BSPServerUtilities.LOGGER.debug("BSP-Core market not readable: {}", e.toString());
                return null; // not readable: said as such, never as the database being down (audit)
            }
        }

        @Override
        public void refresh(net.minecraft.server.MinecraftServer server, Runnable then) {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                com.mrgregles.bsp_core.api.BspMarket.refresh(server, () -> {
                    ran.set(true);
                    then.run();
                });
            } catch (LinkageError | RuntimeException e) {
                failed("market refresh", e, ran, then);
            }
        }

        @Override
        public boolean connected() {
            try {
                return com.mrgregles.bsp_core.api.BspLottery.connected();
            } catch (LinkageError | RuntimeException e) {
                return false;
            }
        }

        @Override
        public String phase() {
            try {
                return com.mrgregles.bsp_core.api.BspLottery.phase().name();
            } catch (LinkageError | RuntimeException e) {
                return "IDLE";
            }
        }

        @Override
        public void lottery(net.minecraft.server.MinecraftServer server, java.util.function.Consumer<Lottery> then) {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                com.mrgregles.bsp_core.api.BspLottery.summary(server, s -> {
                    ran.set(true);
                    then.accept(new Lottery(s.pot(), s.entrants(), s.phase().name(), s.lastWins().stream().map(w -> new Win(w.name(), w.pieces(), w.atMs())).toList()));
                });
            } catch (LinkageError | RuntimeException e) {
                failed("lottery read", e, ran, () -> then.accept(null)); // null: not readable (audit)
            }
        }

        @Override
        public void entrants(net.minecraft.server.MinecraftServer server, java.util.function.Consumer<java.util.List<String>> then) {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                com.mrgregles.bsp_core.api.BspLottery.entrants(server, names -> {
                    ran.set(true);
                    then.accept(names);
                });
            } catch (LinkageError | RuntimeException e) {
                failed("lottery names", e, ran, () -> then.accept(null));
            }
        }

        @Override
        public void draw(net.minecraft.server.MinecraftServer server, String by, java.util.function.Consumer<String> then) {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                com.mrgregles.bsp_core.api.BspLottery.draw(server, by, key -> {
                    ran.set(true);
                    then.accept(key);
                });
            } catch (LinkageError | RuntimeException e) {
                failed("lottery draw", e, ran, () -> then.accept("failed"));
            }
        }
    }

    /** Tests: the market and lottery to use (null: none, as without BSP-Core 1.16). */
    public static void useEconomyForTests(@org.jetbrains.annotations.Nullable Economy e) {
        economyOverride = e;
        economyForced = true;
    }

    public static void useRealEconomy() {
        economyOverride = null;
        economyForced = false;
    }
}
