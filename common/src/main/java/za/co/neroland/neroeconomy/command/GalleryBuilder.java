package za.co.neroland.neroeconomy.command;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.block.PlayerShopBlockEntity;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.gallery.GalleryLayout;
import za.co.neroland.neroeconomy.gallery.GalleryLayout.Cluster;
import za.co.neroland.neroeconomy.gallery.GallerySandbox;
import za.co.neroland.neroeconomy.gallery.GallerySamples;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.registry.ModBlocks;

/**
 * Builds and clears the {@code /neroeconomy gallery} scene in the world: one cluster per system
 * around the operator, laid out by {@link GalleryLayout} (the coordinate contract with the capture
 * harness), each with a label stand. Every block placed and every stand spawned is recorded in the
 * store's {@link GallerySandbox}, and a clear removes exactly those: a recorded block only while it
 * is still the block the gallery put there, a stand only while it carries {@link #ENTITY_TAG}.
 *
 * <p>Nothing here touches value. The sample board rows live in the sandbox, the Auto-Trader shows
 * a sample loop on its label and runs no rule, the exchange tickers are sample prices, and the
 * demo Player Shops are flagged demo before any stock goes in. The only callers are the gallery
 * commands, behind level 3, {@code adminMutationsEnabled} and creative mode, counted through
 * {@code AdminOps}.
 */
public final class GalleryBuilder {
    /** Scoreboard tag on every stand the gallery spawns; a clear discards no entity without it. */
    public static final String ENTITY_TAG = "neroeconomy_gallery";
    /** Server ticks between two refreshes of the live labels (tickers and the Auto-Trader loop). */
    private static final int REFRESH_TICKS = 40;
    /** Economy time one sample ticker step lasts. */
    private static final long TICKER_STEP_MILLIS = 4_000L;
    /** Legacy sweep (a gallery built before blocks were recorded): slack beyond the inner walls. */
    private static final int LEGACY_MARGIN = 8;
    /** Legacy sweep: height above the pad level. */
    private static final int LEGACY_TOP = 12;

    private static int refreshCountdown = REFRESH_TICKS;

    private final ServerLevel level;
    private final ServerPlayer operator;
    private final GallerySandbox sandbox;
    private final int ox;
    private final int oy;
    private final int oz;

    private GalleryBuilder(ServerPlayer operator, GallerySandbox sandbox) {
        this.level = operator.level();
        this.operator = operator;
        this.sandbox = sandbox;
        BlockPos origin = operator.blockPosition();
        this.ox = origin.getX();
        this.oy = origin.getY();
        this.oz = origin.getZ();
    }

    /** What a build did. */
    public enum Built { BUILT, ALREADY_STANDING, FROZEN }

    /** What a clear removed. */
    public record Cleared(int blocks, int entities, int rows) { }

    // ================================================================================================
    // Build
    // ================================================================================================

    /** Builds the whole gallery around {@code operator}; refuses while one is standing or the economy is frozen. */
    public static Built build(ServerPlayer operator) {
        if (EconomyHealth.frozen()) return Built.FROZEN;
        GallerySandbox sandbox = EconomyStore.get(operator.level().getServer()).gallery();
        if (sandbox.built()) return Built.ALREADY_STANDING;
        sandbox.seed(NeroEconomyCommon.CURRENCY_PROVIDER.now());
        GalleryBuilder builder = new GalleryBuilder(operator, sandbox);
        builder.originalClusters();
        builder.exchangeFloor(Cluster.EXCHANGE_FLOOR);
        builder.autoTraderLine(Cluster.AUTO_TRADER);
        builder.jobsBoard(Cluster.JOBS_BOARD);
        builder.bankVault(Cluster.BANK_VAULT);
        builder.directTradeBooth(Cluster.DIRECT_TRADE);
        builder.merchantStall(Cluster.MERCHANT_STALL);
        builder.auctionPodium(Cluster.AUCTION_PODIUM);
        return Built.BUILT;
    }

    private static final BlockState QUARTZ = Blocks.SMOOTH_QUARTZ.defaultBlockState();
    private static final BlockState QUARTZ_PILLAR = Blocks.QUARTZ_PILLAR.defaultBlockState();
    private static final BlockState QUARTZ_CAP = Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState();
    private static final BlockState STONE = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState ANDESITE = Blocks.POLISHED_ANDESITE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
    private static final BlockState DEEPSLATE_TRIM = Blocks.CHISELED_DEEPSLATE.defaultBlockState();

    /**
     * The four clusters the gallery always had, exactly where and as they always stood (see the
     * {@code GalleryLayout} table), now each with a label stand above its centre.
     */
    private void originalClusters() {
        int r = GalleryLayout.INNER_RADIUS;

        // ---- NORTH: bank kiosk — quartz pad, deepslate back wall, two capped pillars framing the terminal.
        int bz = oz - r;
        pad(ox - 3, bz - 2, ox + 3, bz + 2, oy, QUARTZ);
        fill(ox - 3, oy + 1, bz - 2, ox + 3, oy + 4, bz - 2, DEEPSLATE);
        fill(ox - 2, oy + 1, bz, ox - 2, oy + 2, bz, QUARTZ_PILLAR);
        fill(ox + 2, oy + 1, bz, ox + 2, oy + 2, bz, QUARTZ_PILLAR);
        place(new BlockPos(ox - 2, oy + 3, bz), QUARTZ_CAP);
        place(new BlockPos(ox + 2, oy + 3, bz), QUARTZ_CAP);
        place(new BlockPos(ox, oy + 1, bz), ModBlocks.BANK_TERMINAL.get().defaultBlockState());
        title(new BlockPos(ox, oy + 2, bz), Cluster.BANK_TERMINAL);

        // ---- EAST: three primed demo Player Shops in a north-south line.
        int px = ox + r;
        pad(px - 2, oz - 4, px + 2, oz + 4, oy, ANDESITE);
        fill(px + 2, oy + 1, oz - 4, px + 2, oy + 4, oz + 4, DEEPSLATE);
        primeShop(new BlockPos(px, oy + 1, oz - 2), new ItemStack(Items.DIAMOND, 12), 180L);
        primeShop(new BlockPos(px, oy + 1, oz), new ItemStack(Items.GOLD_INGOT, 32), 24L);
        primeShop(new BlockPos(px, oy + 1, oz + 2), new ItemStack(Items.IRON_INGOT, 48), 6L);
        place(new BlockPos(px, oy + 1, oz - 4), DEEPSLATE_TRIM);
        place(new BlockPos(px, oy + 1, oz + 4), DEEPSLATE_TRIM);
        title(new BlockPos(px, oy + 2, oz), Cluster.PLAYER_SHOP);

        // ---- SOUTH: Trading Terminal and Bank Terminal on a one-block deepslate dais.
        int tz = oz + r;
        pad(ox - 3, tz - 2, ox + 3, tz + 2, oy, STONE);
        fill(ox - 2, oy + 1, tz - 1, ox + 2, oy + 1, tz + 1, DEEPSLATE);
        fill(ox - 3, oy + 1, tz + 2, ox + 3, oy + 5, tz + 2, DEEPSLATE);
        place(new BlockPos(ox - 1, oy + 2, tz), ModBlocks.TRADING_TERMINAL.get().defaultBlockState());
        place(new BlockPos(ox + 1, oy + 2, tz), ModBlocks.BANK_TERMINAL.get().defaultBlockState());
        place(new BlockPos(ox, oy + 2, tz), DEEPSLATE_TRIM);
        title(new BlockPos(ox, oy + 3, tz), Cluster.TRADING_TERMINAL);

        // ---- WEST: two Market Boards against a wall; the board rows they show are the sandbox's.
        int mx = ox - r;
        pad(mx - 2, oz - 3, mx + 2, oz + 3, oy, ANDESITE);
        fill(mx - 2, oy + 1, oz - 3, mx - 2, oy + 5, oz + 3, DEEPSLATE);
        place(new BlockPos(mx, oy + 1, oz - 1), ModBlocks.MARKET_BOARD.get().defaultBlockState());
        place(new BlockPos(mx, oy + 1, oz + 1), ModBlocks.MARKET_BOARD.get().defaultBlockState());
        place(new BlockPos(mx, oy + 1, oz), DEEPSLATE_TRIM);
        title(new BlockPos(mx, oy + 2, oz), Cluster.MARKET_BOARD);
    }

    /** Exchange Terminal flanked by two ticker pillars; three sample tickers that move on a refresh. */
    private void exchangeFloor(Cluster c) {
        scaffold(c, QUARTZ, DEEPSLATE);
        place(at(c, 0, 0, 1), facing(ModBlocks.EXCHANGE_TERMINAL.get(), c));
        place(at(c, 0, -2, 1), QUARTZ_PILLAR);
        place(at(c, 0, 2, 1), QUARTZ_PILLAR);
        label(at(c, 0, -2, 2), tickerText(0, 0L), GallerySandbox.ROLE_TICKER + 0);
        label(at(c, 0, 0, 2), tickerText(1, 0L), GallerySandbox.ROLE_TICKER + 1);
        label(at(c, 0, 2, 2), tickerText(2, 0L), GallerySandbox.ROLE_TICKER + 2);
        title(at(c, 1, 0, 4), c);
    }

    /** Chest, Auto-Trader, chest: the label shows the sample rule's loop. The block runs no rule. */
    private void autoTraderLine(Cluster c) {
        scaffold(c, STONE, DEEPSLATE);
        place(at(c, 0, -2, 1), facing(Blocks.CHEST, c));
        place(at(c, 0, -1, 1), DEEPSLATE_TRIM);
        place(at(c, 0, 0, 1), facing(ModBlocks.AUTO_TRADER.get(), c));
        place(at(c, 0, 1, 1), DEEPSLATE_TRIM);
        place(at(c, 0, 2, 1), facing(Blocks.CHEST, c));
        label(at(c, 0, 0, 2), loopText(GallerySamples.loopAt(0L)), GallerySandbox.ROLE_LOOP);
        title(at(c, 1, 0, 4), c);
    }

    /** Two Market Boards as the jobs board, with a sample job on a card between them. */
    private void jobsBoard(Cluster c) {
        scaffold(c, ANDESITE, DEEPSLATE);
        place(at(c, 0, -1, 1), facing(ModBlocks.MARKET_BOARD.get(), c));
        place(at(c, 0, 0, 1), DEEPSLATE_TRIM);
        place(at(c, 0, 1, 1), facing(ModBlocks.MARKET_BOARD.get(), c));
        label(at(c, 0, 0, 2), Component.translatable("command.neroeconomy.gallery.label.sample_job"),
                GallerySandbox.ROLE_TITLE);
        title(at(c, 1, 0, 4), c);
    }

    /** A Bank Terminal beside a vault door of iron, with the vault's sign on a stand above it. */
    private void bankVault(Cluster c) {
        scaffold(c, QUARTZ, DEEPSLATE);
        place(at(c, 0, -2, 1), QUARTZ_PILLAR);
        place(at(c, 0, 0, 1), facing(ModBlocks.BANK_TERMINAL.get(), c));
        place(at(c, 0, 2, 1), Blocks.IRON_BLOCK.defaultBlockState());
        place(at(c, 0, 2, 2), Blocks.IRON_BLOCK.defaultBlockState());
        label(at(c, 0, 2, 3), Component.translatable("command.neroeconomy.gallery.label.vault_sign"),
                GallerySandbox.ROLE_TITLE);
        title(at(c, 1, 0, 4), c);
    }

    /** Two Trading Terminals side by side, the two ends of a direct trade, with the booth's sign between. */
    private void directTradeBooth(Cluster c) {
        scaffold(c, ANDESITE, DEEPSLATE);
        place(at(c, 0, -1, 1), facing(ModBlocks.TRADING_TERMINAL.get(), c));
        place(at(c, 0, 0, 1), DEEPSLATE_TRIM);
        place(at(c, 0, 1, 1), facing(ModBlocks.TRADING_TERMINAL.get(), c));
        label(at(c, 0, 0, 2), Component.translatable("command.neroeconomy.gallery.label.booth_sign"),
                GallerySandbox.ROLE_TITLE);
        title(at(c, 1, 0, 4), c);
    }

    /** A Merchant Stall between two barrels. */
    private void merchantStall(Cluster c) {
        scaffold(c, STONE, DEEPSLATE);
        place(at(c, 0, -2, 1), Blocks.BARREL.defaultBlockState());
        place(at(c, 0, 0, 1), facing(ModBlocks.MERCHANT_STALL.get(), c));
        place(at(c, 0, 2, 1), Blocks.BARREL.defaultBlockState());
        title(at(c, 0, 0, 2), c);
    }

    /** A three-by-three deepslate podium with a lectern on top and a sample lot on its card. */
    private void auctionPodium(Cluster c) {
        scaffold(c, ANDESITE, DEEPSLATE);
        for (int depth = -1; depth <= 1; depth++)
            for (int lateral = -1; lateral <= 1; lateral++) place(at(c, depth, lateral, 1), DEEPSLATE);
        place(at(c, 0, 0, 2), facing(Blocks.LECTERN, c));
        label(at(c, 0, 0, 3), Component.translatable("command.neroeconomy.gallery.label.sample_lot"),
                GallerySandbox.ROLE_TITLE);
        title(at(c, 1, 0, 4), c);
    }

    // --- placement helpers -------------------------------------------------------------------

    /** The pad and backdrop wall every added cluster stands on. */
    private void scaffold(Cluster c, BlockState padState, BlockState wallState) {
        int half = GalleryLayout.HALF_WIDTH;
        int depth = GalleryLayout.HALF_DEPTH;
        for (int d = -depth; d <= depth; d++)
            for (int lateral = -half; lateral <= half; lateral++) place(at(c, d, lateral, 0), padState);
        for (int lateral = -half; lateral <= half; lateral++)
            for (int y = 1; y <= GalleryLayout.WALL_HEIGHT; y++) place(at(c, depth, lateral, y), wallState);
    }

    /** The block {@code depth} out, {@code lateral} to the right and {@code up} above the pad of a cluster. */
    private BlockPos at(Cluster c, int depth, int lateral, int up) {
        return new BlockPos(c.x(ox, depth, lateral), oy + up, c.z(oz, depth, lateral));
    }

    /** {@code block} turned to face back toward the origin (and the camera), where it has a facing. */
    private static BlockState facing(Block block, Cluster c) {
        BlockState state = block.defaultBlockState();
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return state;
        Direction toward = switch (c.bearing) {
            case NORTH -> Direction.SOUTH;
            case EAST -> Direction.WEST;
            case SOUTH -> Direction.NORTH;
            case WEST -> Direction.EAST;
        };
        return state.setValue(BlockStateProperties.HORIZONTAL_FACING, toward);
    }

    /** Places one block and records it in the sandbox, so a clear can take exactly it away again. */
    private void place(BlockPos pos, BlockState state) {
        level.setBlockAndUpdate(pos, state);
        sandbox.recordBlock(pos.asLong(), blockId(state.getBlock()));
    }

    private void pad(int x1, int z1, int x2, int z2, int y, BlockState state) {
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++) place(new BlockPos(x, y, z), state);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        for (int x = x1; x <= x2; x++)
            for (int y = y1; y <= y2; y++)
                for (int z = z1; z <= z2; z++) place(new BlockPos(x, y, z), state);
    }

    /**
     * A demo Player Shop brought to life: claimed for the operator, marked demo BEFORE any stock,
     * priced, then stocked through the ordinary container path. From the demo mark on the listing
     * is flagged, unbuyable, invisible to ordinary searches, and destroyed rather than claimed.
     */
    private void primeShop(BlockPos pos, ItemStack stock, long unitPrice) {
        place(pos, ModBlocks.PLAYER_SHOP.get().defaultBlockState());
        if (!(level.getBlockEntity(pos) instanceof PlayerShopBlockEntity shop)) return;
        shop.claim(operator.getUUID());
        shop.markDemo();
        shop.setPrice(unitPrice);
        shop.setItem(PlayerShopBlockEntity.OFFER_SLOT, stock);
    }

    private void title(BlockPos pos, Cluster c) {
        label(pos, Component.translatable("command.neroeconomy.gallery.label." + c.shot), GallerySandbox.ROLE_TITLE);
    }

    /**
     * A floating label: an invisible, gravity-free, invulnerable marker Armor Stand carrying the
     * gallery tag (the Nerotech label-stand recipe). Marker gives it a zero-size box, so it never
     * takes a click or pick-block meant for the block below; {@code setMarker} is private in 26.x,
     * so the flag goes in the one public way, through {@code Entity#load}.
     */
    private void label(BlockPos pos, Component text, String role) {
        ArmorStand stand = new ArmorStand(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        CompoundTag marker = new CompoundTag();
        marker.putBoolean("Marker", true);
        stand.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), marker));
        stand.refreshDimensions();
        // load() re-read every field from that tag, so the position and the label state go on afterwards.
        stand.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        stand.setCustomName(text);
        stand.setCustomNameVisible(true);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        //? if >=26.3 {
        /*stand.setPermanentlyInvulnerable(true);
        *///?} else {
        stand.setInvulnerable(true);
        //?}
        stand.addTag(ENTITY_TAG);
        if (level.addFreshEntity(stand)) sandbox.recordLabel(stand.getUUID(), role);
    }

    // --- label text --------------------------------------------------------------------------

    private static Component tickerText(int index, long step) {
        GallerySamples.Ticker ticker = GallerySamples.TICKERS.get(index);
        return Component.translatable("command.neroeconomy.gallery.label.ticker", ticker.symbol(),
                GallerySamples.tickerPrice(index, step),
                Component.translatable(GallerySamples.moveKey(GallerySamples.tickerMove(index, step))));
    }

    private static Component loopText(GallerySamples.LoopState state) {
        return Component.translatable("command.neroeconomy.gallery.label.loop", Component.translatable(state.phase().key()),
                state.sold(), GallerySamples.LOOP_SELL_QUANTITY, state.bought(), GallerySamples.LOOP_BUY_QUANTITY);
    }

    // ================================================================================================
    // Live labels
    // ================================================================================================

    /**
     * Called every server tick: every {@link #REFRESH_TICKS} it moves the sample tickers and the
     * sample Auto-Trader loop on their labels. It only renames the gallery's own stands; nothing
     * else is read or written, and with no gallery standing it does nothing.
     */
    public static void tick(MinecraftServer server) {
        if (--refreshCountdown > 0) return;
        refreshCountdown = REFRESH_TICKS;
        GallerySandbox sandbox = EconomyStore.get(server).gallery();
        if (!sandbox.built()) return;
        List<GallerySandbox.Label> labels = sandbox.labels();
        if (labels.isEmpty()) return;
        long elapsed = Math.max(0L, NeroEconomyCommon.CURRENCY_PROVIDER.now() - sandbox.builtAt());
        long step = elapsed / TICKER_STEP_MILLIS;
        for (GallerySandbox.Label label : labels) {
            Component text;
            int ticker = GallerySandbox.tickerIndex(label.role());
            if (ticker >= 0 && ticker < GallerySamples.TICKERS.size()) text = tickerText(ticker, step);
            else if (GallerySandbox.ROLE_LOOP.equals(label.role())) text = loopText(GallerySamples.loopAt(elapsed));
            else continue;
            Entity stand = find(server, label.entity());
            if (stand != null) stand.setCustomName(text);
        }
    }

    /** A stand of the gallery's by id, in whichever level it is loaded; null when none is. */
    private static Entity find(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null && entity.entityTags().contains(ENTITY_TAG)) return entity;
        }
        return null;
    }

    /** Lets a new world start counting afresh. */
    public static void reset() { refreshCountdown = REFRESH_TICKS; }

    // ================================================================================================
    // Clear
    // ================================================================================================

    /**
     * Removes the gallery: every recorded block that is still the block the gallery placed (a demo
     * Player Shop only while it is still demo), every recorded stand and any other entity with the
     * gallery tag inside the recorded footprint, every sample row, and any demo row a gallery from
     * before the sandbox left in the market section. Nothing else in the world is touched.
     */
    public static Cleared clear(ServerPlayer operator) {
        ServerLevel level = operator.level();
        MinecraftServer server = level.getServer();
        GallerySandbox.Cleared record = EconomyStore.get(server).gallery().clear();
        int blocks;
        AABB box;
        if (record.blocks().isEmpty()) {
            blocks = legacySweep(level, operator.blockPosition());
            int reach = GalleryLayout.INNER_RADIUS + LEGACY_MARGIN;
            BlockPos o = operator.blockPosition();
            box = new AABB(o.getX() - reach, o.getY() - 1, o.getZ() - reach, o.getX() + reach + 1,
                    o.getY() + LEGACY_TOP + 4, o.getZ() + reach + 1);
        } else {
            blocks = removeRecorded(level, record.blocks());
            box = footprint(record.blocks());
        }
        int entities = 0;
        for (GallerySandbox.Label label : record.labels()) {
            Entity stand = find(server, label.entity());
            if (stand != null && !stand.isRemoved()) {
                stand.discard();
                entities++;
            }
        }
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box,
                candidate -> candidate.entityTags().contains(ENTITY_TAG))) {
            entity.discard();
            entities++;
        }
        int rows = record.rows() + MarketSavedData.get(server).clearDemo();
        return new Cleared(blocks, entities, rows);
    }

    private static int removeRecorded(ServerLevel level, Map<Long, String> recorded) {
        BlockState air = Blocks.AIR.defaultBlockState();
        int cleared = 0;
        for (Map.Entry<Long, String> entry : recorded.entrySet()) {
            BlockPos pos = BlockPos.of(entry.getKey());
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !blockId(state.getBlock()).equals(entry.getValue())) continue;
            if (!demoOrNotAShop(level, pos, state)) continue;
            // Flag 2 = tell clients, skip the neighbour cascade. Block-entity side effects still run,
            // which is what tears a demo shop's listing and owner row down.
            level.setBlock(pos, air, 2);
            cleared++;
        }
        return cleared;
    }

    /** The recorded blocks' bounding box, grown a little for the stands floating above them. */
    private static AABB footprint(Map<Long, String> recorded) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long packed : recorded.keySet()) {
            BlockPos pos = BlockPos.of(packed);
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new AABB(minX - 1, minY - 1, minZ - 1, maxX + 2, maxY + 4, maxZ + 2);
    }

    /**
     * A gallery built before blocks were recorded: sweep its four-cluster footprint for the block
     * kinds it placed, from the pad layer up, exactly as {@code gallery clear} always did.
     */
    private static int legacySweep(ServerLevel level, BlockPos origin) {
        int oy = origin.getY();
        int reach = GalleryLayout.INNER_RADIUS + LEGACY_MARGIN;
        Set<Block> placed = Set.of(Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_PILLAR, Blocks.CHISELED_QUARTZ_BLOCK,
                Blocks.SMOOTH_STONE, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_DEEPSLATE, Blocks.CHISELED_DEEPSLATE,
                ModBlocks.BANK_TERMINAL.get(), ModBlocks.PLAYER_SHOP.get(), ModBlocks.TRADING_TERMINAL.get(),
                ModBlocks.MARKET_BOARD.get());
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int cleared = 0;
        for (int x = origin.getX() - reach; x <= origin.getX() + reach; x++)
            for (int z = origin.getZ() - reach; z <= origin.getZ() + reach; z++)
                for (int y = oy; y <= oy + LEGACY_TOP; y++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir() || !placed.contains(state.getBlock())) continue;
                    if (!demoOrNotAShop(level, cursor, state)) continue;
                    level.setBlock(cursor, air, 2);
                    cleared++;
                }
        return cleared;
    }

    /** A Player Shop goes only if it is a demo shop: a real shop is somebody's property. */
    private static boolean demoOrNotAShop(ServerLevel level, BlockPos pos, BlockState state) {
        return state.getBlock() != ModBlocks.PLAYER_SHOP.get()
                || level.getBlockEntity(pos) instanceof PlayerShopBlockEntity shop && shop.isDemo();
    }

    private static String blockId(Block block) { return BuiltInRegistries.BLOCK.getKey(block).toString(); }
}
