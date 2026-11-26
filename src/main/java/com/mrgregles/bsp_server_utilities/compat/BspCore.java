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

    /**
     * BSP-Core's Shatter Coin tiers, cheapest first ({@code copper, gold, ...}; its {@code api.BspCoins}), for the staff
     * panel's coin tier of a plot (D779). Empty when BSP-Core is absent or has no coins.
     */
    public static java.util.List<String> coinTiers() {
        if (!isLoaded()) {
            return java.util.List.of();
        }
        try {
            return java.util.List.copyOf(com.mrgregles.bsp_core.api.BspCoins.tiers());
        } catch (RuntimeException | LinkageError e) {
            return java.util.List.of();
        }
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

    // ------------------------------------------------------------------ a player's bank and market for staff (SU-69; BSP-Core 1.18's staff calls)

    /** The oldest BSP-Core with the staff calls: {@code BspBank.account/log/refund/findPlayer}, {@code BspMarket.lotsOf/ordersOf/cancelLot/cancelOrder}. */
    public static final String BANK_SINCE = "1.18.0";

    /** A player found by name. */
    public record Named(java.util.UUID id, String name) {
    }

    /** What the bank holds for a player: pieces, the coins kept in the bank by tier (cheapest first); {@code known} false: the bank never saw them. */
    public record Account(java.util.UUID player, String name, long pieces, java.util.List<Integer> coins, boolean known) {
    }

    /**
     * One bank log row, as BSP-Core's {@code BspBank.Row}, with what is left to refund and whether it is a payment read from
     * BSP-Core itself ({@code piecesLeft}, {@code coinsLeft}, {@code payment}), so its rules are never copied here.
     */
    public record Row(long id, long ms, String type, String with, String detail, long pieces, int coinTier, int coinCount, String serverId,
                      long refundedPieces, int refundedCoins, long refundOf, long piecesLeft, int coinsLeft, boolean payment) {
    }

    /** A staff cancel's answer: {@code key} ok, gone, unreachable (or failed: BSP-Core threw); the pieces a request gave back. */
    public record Cancelled(String key, long piecesBack) {
    }

    /** What the staff panel's Bank & Market needs; the real one is BSP-Core 1.18+, tests give their own. Server thread only. */
    public interface Bank {
        /** {@code then} gets the player or null (nobody by that name); {@code failed} runs instead when BSP-Core failed. */
        void findPlayer(net.minecraft.server.MinecraftServer server, String name, java.util.function.Consumer<Named> then, Runnable failed);

        /** {@code then} gets null when the network's database is not connected; {@code failed} runs instead when BSP-Core failed. */
        void account(net.minecraft.server.MinecraftServer server, java.util.UUID player, java.util.function.Consumer<Account> then, Runnable failed);

        /** Newest first; {@code then} gets null when the database cannot be read; {@code failed} runs instead when BSP-Core failed. */
        void log(net.minecraft.server.MinecraftServer server, java.util.UUID player, java.util.function.Consumer<java.util.List<Row>> then, Runnable failed);

        /** Throws when BSP-Core failed. */
        java.util.List<Lot> lotsOf(net.minecraft.server.MinecraftServer server, java.util.UUID seller);

        /** Throws when BSP-Core failed. */
        java.util.List<Order> ordersOf(net.minecraft.server.MinecraftServer server, java.util.UUID buyer);

        /** The coin tiers' names, cheapest first (empty when not readable). */
        java.util.List<String> tiers();

        /** {@code then} gets BSP-Core's key (ok, or why nothing changed), or "failed". No permission check. */
        void refund(net.minecraft.server.MinecraftServer server, java.util.UUID player, long rowId, long pieces, int coins, boolean alsoOther, String by,
                    java.util.function.Consumer<String> then);

        void cancelLot(net.minecraft.server.MinecraftServer server, String lotId, String by, java.util.function.Consumer<Cancelled> then);

        void cancelOrder(net.minecraft.server.MinecraftServer server, String orderId, String by, java.util.function.Consumer<Cancelled> then);
    }

    @org.jetbrains.annotations.Nullable
    private static Bank bankOverride;
    private static boolean bankForced;
    private static Boolean bankChecked;

    /** BSP-Core's staff bank and market calls, or null without BSP-Core 1.18.0 or newer. */
    @org.jetbrains.annotations.Nullable
    public static Bank bank() {
        if (bankForced) {
            return bankOverride;
        }
        return hasBank() ? RealBank.INSTANCE : null;
    }

    private static boolean hasBank() {
        if (bankChecked != null) {
            return bankChecked;
        }
        if (ModList.get() == null || !isLoaded()) {
            return false; // not cached: asked again once mods are loaded
        }
        boolean ok;
        try {
            ok = ModList.get().getModContainerById(MODID)
                    .map(c -> c.getModInfo().getVersion().compareTo(new org.apache.maven.artifact.versioning.DefaultArtifactVersion(BANK_SINCE)) >= 0)
                    .orElse(false);
            if (ok) {
                Class.forName("com.mrgregles.bsp_core.api.BspBank$Row");
                Class.forName("com.mrgregles.bsp_core.api.BspMarket$Cancelled");
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            ok = false;
        }
        bankChecked = ok;
        return ok;
    }

    /** Loaded only once BSP-Core 1.18+ is known to be there. Failures answer once, never after BSP-Core answered (as {@link RealEconomy}). */
    private static final class RealBank implements Bank {
        static final Bank INSTANCE = new RealBank();

        private static void failed(String what, Throwable e, java.util.concurrent.atomic.AtomicBoolean ran, Runnable fallback) {
            if (ran.get()) {
                BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: the staff panel's {} answer failed after BSP-Core answered: {}", what, e.toString());
            } else {
                BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: BSP-Core's {} failed: {}", what, e.toString());
                fallback.run();
            }
        }

        @Override
        public void findPlayer(net.minecraft.server.MinecraftServer server, String name, java.util.function.Consumer<Named> then, Runnable fail) {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                com.mrgregles.bsp_core.api.BspBank.findPlayer(server, name, n -> {
                    ran.set(true);
                    then.accept(n == null ? null : new Named(n.id(), n.name()));
                });
            } catch (LinkageError | RuntimeException e) {
                failed("player search", e, ran, fail);
            }
        }

        @Override
        public void account(net.minecraft.server.MinecraftServer server, java.util.UUID player, java.util.function.Consumer<Account> then, Runnable fail) {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                com.mrgregles.bsp_core.api.BspBank.account(server, player, acc -> {
                    ran.set(true);
                    then.accept(acc == null ? null : new Account(acc.player(), acc.name(), acc.pieces(),
                            acc.coins().stream().map(c -> c == null ? 0 : c).toList(), acc.known())); // a null tier never stops the answer (audit)
                });
            } catch (LinkageError | RuntimeException e) {
                failed("bank account read", e, ran, fail);
            }
        }

        @Override
        public void log(net.minecraft.server.MinecraftServer server, java.util.UUID player, java.util.function.Consumer<java.util.List<Row>> then, Runnable fail) {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                com.mrgregles.bsp_core.api.BspBank.log(server, player, rows -> {
                    ran.set(true);
                    then.accept(rows == null ? null : rows.stream().map(r -> new Row(r.id(), r.ms(), r.type(), r.with(), r.detail(), r.pieces(), r.coinTier(),
                            r.coinCount(), r.serverId(), r.refundedPieces(), r.refundedCoins(), r.refundOf(), r.piecesLeft(), r.coinsLeft(), r.payment())).toList());
                });
            } catch (LinkageError | RuntimeException e) {
                failed("bank log read", e, ran, fail);
            }
        }

        @Override
        public java.util.List<Lot> lotsOf(net.minecraft.server.MinecraftServer server, java.util.UUID seller) {
            try {
                return com.mrgregles.bsp_core.api.BspMarket.lotsOf(server, seller).stream()
                        .map(l -> new Lot(l.id(), l.seller(), l.sellerName(), l.price(), l.listedMs(), l.endsMs(), l.serverId(), l.items())).toList();
            } catch (LinkageError e) {
                throw new IllegalStateException(e.toString(), e);
            }
        }

        @Override
        public java.util.List<Order> ordersOf(net.minecraft.server.MinecraftServer server, java.util.UUID buyer) {
            try {
                return com.mrgregles.bsp_core.api.BspMarket.ordersOf(server, buyer).stream()
                        .map(o -> new Order(o.id(), o.buyer(), o.buyerName(), o.item(), o.wanted(), o.filled(), o.each(), o.placedMs(), o.endsMs(), o.serverId())).toList();
            } catch (LinkageError e) {
                throw new IllegalStateException(e.toString(), e);
            }
        }

        @Override
        public java.util.List<String> tiers() {
            try {
                return java.util.List.copyOf(com.mrgregles.bsp_core.api.BspCoins.tiers());
            } catch (LinkageError | RuntimeException e) {
                return java.util.List.of();
            }
        }

        @Override
        public void refund(net.minecraft.server.MinecraftServer server, java.util.UUID player, long rowId, long pieces, int coins, boolean alsoOther, String by,
                           java.util.function.Consumer<String> then) {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                com.mrgregles.bsp_core.api.BspBank.refund(server, player, rowId, pieces, coins, alsoOther, by, key -> {
                    ran.set(true);
                    then.accept(key == null ? "failed" : key);
                });
            } catch (LinkageError | RuntimeException e) {
                failed("refund", e, ran, () -> then.accept("failed"));
            }
        }

        @Override
        public void cancelLot(net.minecraft.server.MinecraftServer server, String lotId, String by, java.util.function.Consumer<Cancelled> then) {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                com.mrgregles.bsp_core.api.BspMarket.cancelLot(server, lotId, by, c -> {
                    ran.set(true);
                    then.accept(c == null ? new Cancelled("failed", 0) : new Cancelled(c.key(), c.piecesBack()));
                });
            } catch (LinkageError | RuntimeException e) {
                failed("lot cancel", e, ran, () -> then.accept(new Cancelled("failed", 0)));
            }
        }

        @Override
        public void cancelOrder(net.minecraft.server.MinecraftServer server, String orderId, String by, java.util.function.Consumer<Cancelled> then) {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                com.mrgregles.bsp_core.api.BspMarket.cancelOrder(server, orderId, by, c -> {
                    ran.set(true);
                    then.accept(c == null ? new Cancelled("failed", 0) : new Cancelled(c.key(), c.piecesBack()));
                });
            } catch (LinkageError | RuntimeException e) {
                failed("request cancel", e, ran, () -> then.accept(new Cancelled("failed", 0)));
            }
        }
    }

    /** Tests: the bank to use (null: none, as without BSP-Core 1.18). */
    public static void useBankForTests(@org.jetbrains.annotations.Nullable Bank b) {
        bankOverride = b;
        bankForced = true;
    }

    public static void useRealBank() {
        bankOverride = null;
        bankForced = false;
    }

    // ------------------------------------------------------------------ bounties for staff (SU-74; BSP-Core 1.19's BspBounties)

    /** The oldest BSP-Core with {@code BspBounties}. */
    public static final String BOUNTIES_SINCE = "1.19.0";

    /** A hunter who accepted a bounty. */
    public record BountyHunter(java.util.UUID id, String name, long acceptedMs) {
    }

    /**
     * A bounty as BSP-Core's {@code BspBounties.Info}: {@code coinTier} -1 for pieces, else a tier index; {@code amount} and
     * {@code bonus} pieces or a count of coins; {@code bonusEndsMs} 0 for no bonus; {@code byServer} true for a staff bounty
     * (placed by {@code BspBounties.SERVER}).
     */
    public record Bounty(String id, java.util.UUID target, String targetName, java.util.UUID placer, String placerName, boolean byServer, int coinTier,
                         long amount, long bonus, long bonusEndsMs, long fee, long placedMs, long endsMs, String notes, String serverId,
                         java.util.List<BountyHunter> hunters) {
    }

    /** A closed bounty: {@code ending} CLAIMED, CANCELLED, EXPIRED, REMOVED, REMOVED_NO_REFUND, TARGET_STAFF or newer; {@code hunter} null unless claimed. */
    public record BountyClosed(Bounty bounty, String ending, long closedMs, @org.jetbrains.annotations.Nullable java.util.UUID hunter, String hunterName,
                               String by, boolean bonusPaid) {
    }

    /** A player the bank knows, for the add form. */
    public record BountyPlayer(java.util.UUID id, String name, boolean online) {
    }

    /** A staff change's answer: BSP-Core's key (or "failed": BSP-Core threw) and the bounty as it is after it, when there is one. */
    public record BountyResult(String key, @org.jetbrains.annotations.Nullable Bounty bounty) {
    }

    /** What the staff panel's bounties need; the real one is BSP-Core 1.19+, tests give their own. Server thread only. */
    public interface Bounties {
        /** Every running bounty, newest first. Throws when BSP-Core failed. */
        java.util.List<Bounty> open(net.minecraft.server.MinecraftServer server);

        /** Closed bounties, newest first. Throws when BSP-Core failed. */
        java.util.List<BountyClosed> history(net.minecraft.server.MinecraftServer server);

        /** Players whose name contains {@code search}, online first; {@code failed} runs instead when BSP-Core failed. */
        void players(net.minecraft.server.MinecraftServer server, String search, java.util.function.Consumer<java.util.List<BountyPlayer>> then, Runnable failed);

        /** Null, or {@code staff} / {@code protected}. Throws when BSP-Core failed. */
        @org.jetbrains.annotations.Nullable
        String targetRefusal(net.minecraft.server.MinecraftServer server, java.util.UUID target, String name);

        /** The coin tiers' names, cheapest first. */
        java.util.List<String> tiers();

        void add(net.minecraft.server.MinecraftServer server, String by, java.util.UUID target, String targetName, int coinTier, long amount, long bonus,
                 long bonusWithinMs, long durationMs, String notes, java.util.function.Consumer<BountyResult> then);

        void remove(net.minecraft.server.MinecraftServer server, String id, boolean refund, String by, java.util.function.Consumer<BountyResult> then);

        void extend(net.minecraft.server.MinecraftServer server, String id, long addMs, String by, java.util.function.Consumer<BountyResult> then);

        void blankNotes(net.minecraft.server.MinecraftServer server, String id, String by, java.util.function.Consumer<BountyResult> then);
    }

    @org.jetbrains.annotations.Nullable
    private static Bounties bountiesOverride;
    private static boolean bountiesForced;
    private static Boolean bountiesChecked;

    /** BSP-Core's bounty calls, or null without BSP-Core 1.19.0 or newer. */
    @org.jetbrains.annotations.Nullable
    public static Bounties bounties() {
        if (bountiesForced) {
            return bountiesOverride;
        }
        return hasBounties() ? RealBounties.INSTANCE : null;
    }

    private static boolean hasBounties() {
        if (bountiesChecked != null) {
            return bountiesChecked;
        }
        if (ModList.get() == null || !isLoaded()) {
            return false;
        }
        boolean ok;
        try {
            ok = ModList.get().getModContainerById(MODID)
                    .map(c -> c.getModInfo().getVersion().compareTo(new org.apache.maven.artifact.versioning.DefaultArtifactVersion(BOUNTIES_SINCE)) >= 0)
                    .orElse(false);
            if (ok) {
                Class.forName("com.mrgregles.bsp_core.api.BspBounties$Result");
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            ok = false;
        }
        bountiesChecked = ok;
        return ok;
    }

    /** Loaded only once BSP-Core 1.19+ is known to be there. A failure answers once, never after BSP-Core answered. */
    private static final class RealBounties implements Bounties {
        static final Bounties INSTANCE = new RealBounties();

        private static Bounty bounty(com.mrgregles.bsp_core.api.BspBounties.Info i) {
            return new Bounty(i.id(), i.target(), i.targetName(), i.placer(), i.placerName(), com.mrgregles.bsp_core.api.BspBounties.SERVER.equals(i.placer()),
                    i.coinTier(), i.amount(), i.bonus(), i.bonusEndsMs(), i.fee(), i.placedMs(), i.endsMs(), i.notes() == null ? "" : i.notes(),
                    i.serverId() == null ? "" : i.serverId(),
                    i.hunters() == null ? java.util.List.of() : i.hunters().stream().map(h -> new BountyHunter(h.id(), h.name(), h.acceptedMs())).toList());
        }

        private static BountyResult result(com.mrgregles.bsp_core.api.BspBounties.Result r) {
            return r == null ? new BountyResult("failed", null) : new BountyResult(r.key(), r.bounty() == null ? null : bounty(r.bounty()));
        }

        private static void failed(String what, Throwable e, java.util.concurrent.atomic.AtomicBoolean ran, Runnable fallback) {
            if (ran.get()) {
                BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: the staff panel's {} answer failed after BSP-Core answered: {}", what, e.toString());
            } else {
                BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: BSP-Core's {} failed: {}", what, e.toString());
                fallback.run();
            }
        }

        @Override
        public java.util.List<Bounty> open(net.minecraft.server.MinecraftServer server) {
            try {
                return com.mrgregles.bsp_core.api.BspBounties.open(server).stream().map(RealBounties::bounty).toList();
            } catch (LinkageError e) {
                throw new IllegalStateException(e.toString(), e);
            }
        }

        @Override
        public java.util.List<BountyClosed> history(net.minecraft.server.MinecraftServer server) {
            try {
                return com.mrgregles.bsp_core.api.BspBounties.history(server).stream().map(c -> new BountyClosed(bounty(c.bounty()), c.ending(), c.closedMs(), c.hunter(),
                        c.hunterName() == null ? "" : c.hunterName(), c.by() == null ? "" : c.by(), c.bonusPaid())).toList();
            } catch (LinkageError e) {
                throw new IllegalStateException(e.toString(), e);
            }
        }

        @Override
        public void players(net.minecraft.server.MinecraftServer server, String search, java.util.function.Consumer<java.util.List<BountyPlayer>> then, Runnable fail) {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                com.mrgregles.bsp_core.api.BspBounties.players(server, search, list -> {
                    ran.set(true);
                    then.accept(list == null ? java.util.List.of() : list.stream().map(x -> new BountyPlayer(x.id(), x.name(), x.online())).toList());
                });
            } catch (LinkageError | RuntimeException e) {
                failed("bounty player search", e, ran, fail);
            }
        }

        @Override
        public String targetRefusal(net.minecraft.server.MinecraftServer server, java.util.UUID target, String name) {
            try {
                return com.mrgregles.bsp_core.api.BspBounties.targetRefusal(server, target, name);
            } catch (LinkageError e) {
                throw new IllegalStateException(e.toString(), e);
            }
        }

        @Override
        public java.util.List<String> tiers() {
            try {
                return java.util.List.copyOf(com.mrgregles.bsp_core.api.BspCoins.tiers());
            } catch (LinkageError | RuntimeException e) {
                return java.util.List.of();
            }
        }

        private interface Call {
            void run(java.util.function.Consumer<com.mrgregles.bsp_core.api.BspBounties.Result> then);
        }

        private static void change(String what, Call call, java.util.function.Consumer<BountyResult> then) {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                call.run(r -> {
                    ran.set(true);
                    then.accept(result(r));
                });
            } catch (LinkageError | RuntimeException e) {
                failed(what, e, ran, () -> then.accept(new BountyResult("failed", null)));
            }
        }

        @Override
        public void add(net.minecraft.server.MinecraftServer server, String by, java.util.UUID target, String targetName, int coinTier, long amount, long bonus,
                        long bonusWithinMs, long durationMs, String notes, java.util.function.Consumer<BountyResult> then) {
            change("bounty add", t -> com.mrgregles.bsp_core.api.BspBounties.add(server, by, target, targetName, coinTier, amount, bonus, bonusWithinMs, durationMs, notes, t), then);
        }

        @Override
        public void remove(net.minecraft.server.MinecraftServer server, String id, boolean refund, String by, java.util.function.Consumer<BountyResult> then) {
            change("bounty remove", t -> com.mrgregles.bsp_core.api.BspBounties.remove(server, id, refund, by, t), then);
        }

        @Override
        public void extend(net.minecraft.server.MinecraftServer server, String id, long addMs, String by, java.util.function.Consumer<BountyResult> then) {
            change("bounty extend", t -> com.mrgregles.bsp_core.api.BspBounties.extend(server, id, addMs, by, t), then);
        }

        @Override
        public void blankNotes(net.minecraft.server.MinecraftServer server, String id, String by, java.util.function.Consumer<BountyResult> then) {
            change("bounty notes", t -> com.mrgregles.bsp_core.api.BspBounties.blankNotes(server, id, by, t), then);
        }
    }

    /** Tests: the bounties to use (null: none, as without BSP-Core 1.19). */
    public static void useBountiesForTests(@org.jetbrains.annotations.Nullable Bounties b) {
        bountiesOverride = b;
        bountiesForced = true;
    }

    public static void useRealBounties() {
        bountiesOverride = null;
        bountiesForced = false;
    }

    // ------------------------------------------------------------------ totem steals for the staff panel (SU-95, D613 to D622)

    /**
     * What was stolen at a place: {@code source} "totem", "forge" (a Totem Forge's pour) or "other"; {@code tier} 1 to 5, 0
     * unknown; {@code points} on the board, 0 unknown; {@code ownerName} the owner's (or pourer's) name as BSP-Core keeps it
     * on the block, empty when unknown.
     */
    public record TotemInfo(String source, int tier, int points, String ownerName) {
        public static final TotemInfo UNKNOWN = new TotemInfo("other", 0, 0, "");

        public TotemInfo(String source, int tier, int points) {
            this(source, tier, points, "");
        }
    }

    /** BSP-Core's steal events, as this mod reads them (server thread). */
    public interface StealListener {
        /** {@code owner} is who the steal is against (null: unclaimed); {@code seconds} what BSP-Core gave it. */
        void started(net.minecraft.server.MinecraftServer server, net.minecraft.core.GlobalPos where, @org.jetbrains.annotations.Nullable java.util.UUID owner,
                     java.util.UUID thief, String thiefName, int seconds);

        /** {@code owner} is who the steal was against (null: unclaimed); {@code reasonKey} empty on success. */
        void ended(net.minecraft.server.MinecraftServer server, net.minecraft.core.GlobalPos where, @org.jetbrains.annotations.Nullable java.util.UUID owner,
                   java.util.UUID thief, String thiefName, boolean success, String reasonKey);
    }

    // ------------------------------------------------------------------ player reset (BSP-Core's /bsp player reset, PR17)

    /** BSP-Core's event posted on every server a player reset reaches, read by name: this mod builds against older BSP-Core jars. */
    public static final String PLAYER_RESET_EVENT = "com.mrgregles.bsp_core.api.event.PlayerResetEvent$Forgotten";

    /** What a reset says: who, under which name, when (the same on every server), whether it was typed here, by whom. */
    public interface PlayerResetListener {
        void forgotten(net.minecraft.server.MinecraftServer server, java.util.UUID player, String name, long resetMs, boolean origin, String by);
    }

    private static boolean resetHooked;

    /**
     * Calls {@code listener} for every BSP-Core player reset that reaches this server; once (at server start), and
     * nothing without BSP-Core or with a BSP-Core older than the event. True when listening.
     */
    public static boolean onPlayerReset(PlayerResetListener listener) {
        if (resetHooked) {
            return true;
        }
        if (!isLoaded()) {
            return false;
        }
        resetHooked = hookPlayerReset(PLAYER_RESET_EVENT, listener) != null;
        return resetHooked;
    }

    /**
     * Listens for the event class of that name, its getters read by reflection ({@code getServer}, {@code getPlayer},
     * {@code getName}, {@code getResetMs}, {@code isOrigin}, {@code getBy}). Returns the registered listener (for
     * {@code EVENT_BUS.unregister}), or null, with nothing registered, when the class or a getter is missing. Tests call
     * it with their own stand-in class.
     */
    @SuppressWarnings("unchecked")
    @org.jetbrains.annotations.Nullable
    public static Object hookPlayerReset(String className, PlayerResetListener listener) {
        try {
            Class<?> type = Class.forName(className);
            if (!net.minecraftforge.eventbus.api.Event.class.isAssignableFrom(type)) {
                return null;
            }
            java.lang.reflect.Method server = type.getMethod("getServer"), player = type.getMethod("getPlayer"), name = type.getMethod("getName"),
                    resetMs = type.getMethod("getResetMs"), origin = type.getMethod("isOrigin"), by = type.getMethod("getBy");
            java.util.function.Consumer<net.minecraftforge.eventbus.api.Event> handler = e -> {
                try {
                    Object n = name.invoke(e), b = by.invoke(e);
                    listener.forgotten((net.minecraft.server.MinecraftServer) server.invoke(e), (java.util.UUID) player.invoke(e),
                            n == null ? "" : n.toString(), (Long) resetMs.invoke(e), (Boolean) origin.invoke(e), b == null ? "" : b.toString());
                } catch (ReflectiveOperationException | LinkageError | RuntimeException x) {
                    // nothing thrown here may reach BSP-Core's reset
                    BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: a player reset was not applied: {}", x.toString());
                }
            };
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                    (Class<net.minecraftforge.eventbus.api.Event>) type, handler);
            return handler;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            BSPServerUtilities.LOGGER.debug("BSP-Core player reset event not reachable ({}): {}", className, e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ season reset (BSP-Core's /bsp season reset, SR15)

    /** BSP-Core's season end, read by name; only its {@code isSeasonReset()} ones are a season reset (older jars lack the getter). */
    public static final String SEASON_ENDED_EVENT = "com.mrgregles.bsp_core.api.event.SeasonEvent$Ended";

    /** What a season reset says: the season that ended, whether it was typed here, by whom. */
    public interface SeasonResetListener {
        void reset(net.minecraft.server.MinecraftServer server, int endedNumber, boolean origin, String by);
    }

    private static boolean seasonResetHooked;

    /**
     * Calls {@code listener} for every BSP-Core season reset that reaches this server; once (at server start), and nothing
     * without BSP-Core. True when listening (with a BSP-Core older than the season reset the listener is never called).
     */
    public static boolean onSeasonReset(SeasonResetListener listener) {
        if (seasonResetHooked) {
            return true;
        }
        if (!isLoaded()) {
            return false;
        }
        seasonResetHooked = hookSeasonReset(SEASON_ENDED_EVENT, listener) != null;
        return seasonResetHooked;
    }

    @org.jetbrains.annotations.Nullable
    private static java.lang.reflect.Method getter(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException | LinkageError | SecurityException e) {
            return null;
        }
    }

    private static boolean flag(@org.jetbrains.annotations.Nullable java.lang.reflect.Method m, Object e) throws ReflectiveOperationException {
        return m != null && Boolean.TRUE.equals(m.invoke(e)); // a getter an older BSP-Core lacks reads as false
    }

    /**
     * Listens for the season end class of that name: {@code getServer} and {@code getEndedNumber} are needed;
     * {@code isSeasonReset} and {@code isOrigin} are read when there (missing: false, so an older BSP-Core's season end is
     * never a season reset), {@code getBy} too (missing: empty). The listener hears season resets only. Returns the
     * registered listener (for {@code EVENT_BUS.unregister}), or null, with nothing registered, when the class or a needed
     * getter is missing. Tests call it with their own stand-in class.
     */
    @SuppressWarnings("unchecked")
    @org.jetbrains.annotations.Nullable
    public static Object hookSeasonReset(String className, SeasonResetListener listener) {
        try {
            Class<?> type = Class.forName(className);
            if (!net.minecraftforge.eventbus.api.Event.class.isAssignableFrom(type)) {
                return null;
            }
            java.lang.reflect.Method server = type.getMethod("getServer"), number = type.getMethod("getEndedNumber");
            java.lang.reflect.Method seasonReset = getter(type, "isSeasonReset"), origin = getter(type, "isOrigin"), by = getter(type, "getBy");
            java.util.function.Consumer<net.minecraftforge.eventbus.api.Event> handler = e -> {
                if (!type.isInstance(e)) {
                    return;
                }
                try {
                    if (!flag(seasonReset, e)) {
                        return; // an ordinary season end, or a BSP-Core older than the season reset
                    }
                    Object b = by == null ? null : by.invoke(e);
                    listener.reset((net.minecraft.server.MinecraftServer) server.invoke(e), ((Number) number.invoke(e)).intValue(),
                            flag(origin, e), b == null ? "" : b.toString());
                } catch (ReflectiveOperationException | LinkageError | RuntimeException x) {
                    // nothing thrown here may reach BSP-Core's season reset
                    BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: a season reset was not applied: {}", x.toString());
                }
            };
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false,
                    (Class<net.minecraftforge.eventbus.api.Event>) type, handler);
            return handler;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            BSPServerUtilities.LOGGER.debug("BSP-Core season end event not reachable ({}): {}", className, e.toString());
            return null;
        }
    }

    private static boolean stealsHooked;

    /**
     * Calls {@code listener} for every steal that starts or ends on this server (BSP-Core's {@code TotemEvent.StealStarted}
     * and {@code StealEnded}, posted for placed totems and Totem Forges alike); once, and nothing without BSP-Core.
     */
    public static void onSteals(StealListener listener) {
        if (!isLoaded() || stealsHooked) {
            return;
        }
        try {
            // the events' own getters inside the guard too: nothing thrown here may reach BSP-Core's steal (audit of 0.16.0)
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((com.mrgregles.bsp_core.api.event.TotemEvent.StealStarted e) -> {
                try {
                    listener.started(e.getServer(), e.getWhere(), e.getOwner(), e.getThief(), e.getThiefName(), e.getSeconds());
                } catch (LinkageError | RuntimeException x) {
                    BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: a steal start was not noted: {}", x.toString());
                }
            });
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((com.mrgregles.bsp_core.api.event.TotemEvent.StealEnded e) -> {
                try {
                    listener.ended(e.getServer(), e.getWhere(), e.getOwner(), e.getThief(), e.getThiefName(), e.isSuccess(), e.getReasonKey() == null ? "" : e.getReasonKey());
                } catch (LinkageError | RuntimeException x) {
                    BSPServerUtilities.LOGGER.warn("BSP-Server Utilities: a steal was not recorded: {}", x.toString());
                }
            });
            stealsHooked = true;
        } catch (LinkageError | RuntimeException e) {
            BSPServerUtilities.LOGGER.debug("BSP-Core steal events not reachable: {}", e.toString());
        }
    }

    @org.jetbrains.annotations.Nullable
    private static java.util.function.BiFunction<net.minecraft.server.MinecraftServer, net.minecraft.core.GlobalPos, TotemInfo> totemInfoOverride;

    /**
     * What stands at {@code where} on this server, read while a steal's end event runs (the totem is still there, its
     * owner not yet changed): a Shatter Totem's tier and what BSP-Core's board counts it as, or a Totem Forge, whose pour
     * comes out a tier I totem. Never loads a chunk; {@link TotemInfo#UNKNOWN} when nothing is readable.
     */
    public static TotemInfo totemInfo(net.minecraft.server.MinecraftServer server, net.minecraft.core.GlobalPos where) {
        if (totemInfoOverride != null) {
            return totemInfoOverride.apply(server, where);
        }
        if (!isLoaded()) {
            return TotemInfo.UNKNOWN;
        }
        try {
            net.minecraft.server.level.ServerLevel level = server.getLevel(where.dimension());
            if (level == null || !level.isLoaded(where.pos())) {
                return TotemInfo.UNKNOWN;
            }
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(where.pos());
            if (be instanceof com.mrgregles.bsp_core.totem.ShatterTotemBlockEntity totem) {
                int tier = totem.getTier(); // 0 (I) to 4 (V)
                String owner = totem.getOwner().map(com.mrgregles.bsp_core.totem.TotemOwner::name).orElse("");
                return new TotemInfo("totem", tier + 1, tierPoints(tier), owner == null ? "" : owner);
            }
            if (be instanceof com.mrgregles.bsp_core.machine.TotemForgeBlockEntity forge) {
                String pourer = forge.pourerName();
                return new TotemInfo("forge", 1, tierPoints(0), pourer == null ? "" : pourer);
            }
        } catch (LinkageError | RuntimeException e) {
            BSPServerUtilities.LOGGER.debug("BSP-Core totem not readable: {}", e.toString());
        }
        return TotemInfo.UNKNOWN;
    }

    /**
     * Points a totem of {@code tier} (0 = I) is worth on BSP-Core's board ({@code scoring.tierPoints}). BSP-Core keeps this
     * outside its api (request SU-BC4 asks for it there), so a BSP-Core without it answers 0: unknown.
     */
    private static int tierPoints(int tier) {
        try {
            return Math.max(0, com.mrgregles.bsp_core.score.ScoreService.tierPoints(tier));
        } catch (LinkageError | RuntimeException e) {
            return 0;
        }
    }

    /** One line of BSP-Core's network board: {@code rank} 1 for the leader, {@code bestTier} 1 to 5. */
    public record BoardLine(java.util.UUID player, String name, int rank, int points, int totems, int bestTier) {
    }

    /** A steal running anywhere on the network: {@code fractionLeft} 1 at the start to 0 at the end (estimated for another server's). */
    public record StealNow(String serverId, boolean local, String dimension, int x, int y, int z, @org.jetbrains.annotations.Nullable java.util.UUID owner,
                           java.util.UUID thief, String thiefName, float fractionLeft) {
    }

    /** BSP-Core's board and the steals in progress, as the staff panel's TOTEMS page reads them; the real one is {@code BspScores} and {@code BspTotems}, tests give their own. Server thread only. */
    public interface Scores {
        /** The whole board, best first; with network storage every server's combined, rebuilt every {@code scoring.refreshSeconds}. Throws when BSP-Core failed. */
        java.util.List<BoardLine> board(net.minecraft.server.MinecraftServer server);

        /** The steals running on the network (this server's exactly, the others' as last read). Throws when BSP-Core failed. */
        java.util.List<StealNow> stealing(net.minecraft.server.MinecraftServer server);
    }

    @org.jetbrains.annotations.Nullable
    private static Scores scoresOverride;
    private static boolean scoresForced;
    private static Boolean scoresChecked;

    /** BSP-Core's board and steals in progress, or null without a BSP-Core that has {@code BspScores} (1.19.0 is the oldest built against). */
    @org.jetbrains.annotations.Nullable
    public static Scores scores() {
        if (scoresForced) {
            return scoresOverride;
        }
        if (scoresChecked == null) {
            if (ModList.get() == null || !isLoaded()) {
                return null; // not cached: asked again once mods are loaded
            }
            boolean ok;
            try {
                // the record shapes read, not only the classes (audit of 0.16.0): an older shape answers as BSP-Core absent
                Class<?> score = Class.forName("com.mrgregles.bsp_core.api.BspScores$Score"), steal = Class.forName("com.mrgregles.bsp_core.api.BspTotems$StealAnywhere");
                for (String m : new String[]{"player", "name", "rank", "points", "totems", "bestTier"}) {
                    score.getMethod(m);
                }
                for (String m : new String[]{"serverId", "local", "dimension", "pos", "owner", "thief", "thiefName", "fractionLeft"}) {
                    steal.getMethod(m);
                }
                ok = true;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                ok = false;
            }
            scoresChecked = ok;
        }
        return scoresChecked ? RealScores.INSTANCE : null;
    }

    /** Loaded only once {@code BspScores} is known to be there. */
    private static final class RealScores implements Scores {
        static final Scores INSTANCE = new RealScores();

        @Override
        public java.util.List<BoardLine> board(net.minecraft.server.MinecraftServer server) {
            try {
                return com.mrgregles.bsp_core.api.BspScores.board(server).stream()
                        .map(x -> new BoardLine(x.player(), x.name() == null ? "" : x.name(), x.rank(), x.points(), x.totems(), x.bestTier())).toList();
            } catch (LinkageError e) {
                throw new IllegalStateException(e.toString(), e);
            }
        }

        @Override
        public java.util.List<StealNow> stealing(net.minecraft.server.MinecraftServer server) {
            try {
                return com.mrgregles.bsp_core.api.BspTotems.stealsAnywhere(server).stream()
                        .map(x -> new StealNow(x.serverId(), x.local(), x.dimension().location().toString(), x.pos().getX(), x.pos().getY(), x.pos().getZ(),
                                x.owner(), x.thief(), x.thiefName() == null ? "" : x.thiefName(), x.fractionLeft())).toList();
            } catch (LinkageError e) {
                throw new IllegalStateException(e.toString(), e);
            }
        }
    }

    /** Tests: the board to use (null: none, as without BSP-Core). */
    public static void useScoresForTests(@org.jetbrains.annotations.Nullable Scores sc) {
        scoresOverride = sc;
        scoresForced = true;
    }

    public static void useRealScores() {
        scoresOverride = null;
        scoresForced = false;
    }

    /** Tests: what {@link #totemInfo} answers (null: the real lookup). */
    public static void useTotemInfoForTests(@org.jetbrains.annotations.Nullable java.util.function.BiFunction<net.minecraft.server.MinecraftServer, net.minecraft.core.GlobalPos, TotemInfo> info) {
        totemInfoOverride = info;
    }
}
