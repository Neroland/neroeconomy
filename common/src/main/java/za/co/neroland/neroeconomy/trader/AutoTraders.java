package za.co.neroland.neroeconomy.trader;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.block.AutoTraderBlock;
import za.co.neroland.neroeconomy.block.AutoTraderBlockEntity;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.rules.RuleBinder;
import za.co.neroland.neroeconomy.rules.RuleOutcome;
import za.co.neroland.neroeconomy.rules.RuleResult;
import za.co.neroland.neroeconomy.rules.RuleSide;
import za.co.neroland.neroeconomy.rules.TradeRule;
import za.co.neroland.neroeconomy.shop.ShopOwnershipState;
import za.co.neroland.neroeconomy.shop.ShopSweep;
import za.co.neroland.neroeconomy.trade.Trading;

/**
 * Auto-Trader blocks as the rest of the game reaches them: the edge between the block in the
 * world, the owner index in the store ({@link AutoTraderSection}) and the one auto-trade rule
 * bound to each block (the rules section).
 *
 * <p><b>The binding.</b> A rule bound to an Auto-Trader is saved with the binding
 * {@code trader:<dimension>|<packed position>}. {@link #BINDER} turns that back into the block's
 * slots — but only for the block's owner, and only while the block's chunk is loaded and ticking.
 * It never loads a chunk to find out: an unloaded block is an inventory that cannot be reached,
 * so the rule pauses, says so, and takes its order off the book.
 *
 * <p>Each block has at most one rule. It counts towards the owner's {@code maxRulesPerPlayer} and
 * daily value cap exactly like a rule that works from the vault, because it is one.
 *
 * <p>Everything that needs no game — the binder itself, replacing a block's rule, letting go of a
 * block that is gone — is in {@link TraderRules}; this class only finds the block in the world.
 */
public final class AutoTraders {
    /** What the binding of a rule that works from an Auto-Trader starts with. */
    public static final String BINDING_PREFIX = TraderRules.BINDING_PREFIX;

    /** Knows the inventories of Auto-Trader blocks; plugged into the trading rules at start-up. */
    public static final RuleBinder BINDER = TraderRules.binder(location -> {
        AutoTraderBlockEntity trader = loaded(location);
        return trader == null ? null : trader.slots();
    });

    private AutoTraders() { }

    // --- locations and bindings --------------------------------------------------------------

    /** The {@code dimension|packedPosition} key an Auto-Trader at this position is recorded under. */
    public static String location(ServerLevel level, BlockPos pos) { return ShopOwnershipState.key(level, pos); }

    public static String binding(String location) { return TraderRules.binding(location); }

    /** Who owns the Auto-Trader at a position, or null. Nothing is known while the economy is frozen. */
    public static UUID owner(ServerLevel level, BlockPos pos) {
        if (level == null || EconomyHealth.frozen()) return null;
        return EconomyStore.get(level.getServer()).autoTraders().owner(location(level, pos));
    }

    /** Records the placer as the owner. @return false when they already own as many as one player may */
    public static boolean claim(ServerLevel level, BlockPos pos, UUID player) {
        if (level == null || player == null || EconomyHealth.frozen()) return false;
        return EconomyStore.get(level.getServer()).autoTraders().claim(location(level, pos), player);
    }

    // --- finding the block -------------------------------------------------------------------

    /**
     * The Auto-Trader at a location, if its chunk is loaded and ticking right now. Never loads a
     * chunk and never creates a block entity: the level is looked up by key, the ticking range is
     * read from the chunk tickets, and the chunk is only taken if it is already there in full.
     */
    private static AutoTraderBlockEntity loaded(String location) {
        MinecraftServer server = NeroEconomyCommon.CURRENCY_PROVIDER.serverOrNull();
        ShopSweep.Location parsed = ShopSweep.parse(location);
        if (server == null || parsed == null) return null;
        Identifier dimension = Identifier.tryParse(parsed.dimension());
        if (dimension == null) return null;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null) return null;
        BlockPos pos = BlockPos.of(parsed.packedPosition());
        if (!level.shouldTickBlocksAt(pos)) return null;
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(pos) instanceof AutoTraderBlockEntity trader && !trader.isRemoved()
                ? trader : null;
    }

    // --- what the owner asks for -------------------------------------------------------------

    /** The rule bound to an Auto-Trader, as its owner sees it; null when it has none. */
    public static TradeRule rule(ServerLevel level, BlockPos pos, UUID owner) {
        if (level == null || owner == null || EconomyHealth.frozen()) return null;
        return EconomyStore.get(level.getServer()).rules()
                .boundTo(new PlayerAccountId(owner), binding(location(level, pos)));
    }

    /**
     * Sets the Auto-Trader's rule. A rule it already has is deleted first — its budget goes back
     * to the balance and the goods it had listed go back into the slots — so the old budget can
     * pay for the new one; the new rule is then created, and a buy rule's whole budget is
     * escrowed, or it is not created at all.
     *
     * @return the outcome of creating the new rule; when that is refused the Auto-Trader has no
     *         rule, the old one having been returned in full
     */
    public static RuleOutcome apply(ServerPlayer owner, AutoTraderBlockEntity trader, RuleSide side, boolean tag,
            String target, long limitPrice, int perDay, long total, UUID requestId) {
        if (EconomyHealth.frozen()) return refused(RuleResult.FROZEN);
        if (owner == null || trader == null || requestId == null || !(trader.getLevel() instanceof ServerLevel level)
                || !trader.isOwner(owner.getUUID())) return refused(RuleResult.INVALID);
        EconomyStore store = EconomyStore.get(level.getServer());
        if (!store.market().rememberRequest(owner.getUUID(), requestId)) return refused(RuleResult.STALE_REQUEST);
        AccountId account = new PlayerAccountId(owner.getUUID());
        return TraderRules.apply(store, Trading.env(store), account, location(level, trader.getBlockPos()), side, tag,
                target, limitPrice, perDay, total);
    }

    /** Pauses or resumes the Auto-Trader's rule. */
    public static RuleOutcome setPaused(ServerPlayer owner, AutoTraderBlockEntity trader, boolean paused) {
        TradeRule rule = owned(owner, trader);
        if (rule == null) return refused(EconomyHealth.frozen() ? RuleResult.FROZEN : RuleResult.NOT_FOUND);
        EconomyStore store = EconomyStore.get(owner.level().getServer());
        return store.rules().setPaused(Trading.env(store), rule.owner(), rule.id(), paused);
    }

    /** Deletes the Auto-Trader's rule: its budget goes back to the balance, its goods into the slots. */
    public static RuleOutcome delete(ServerPlayer owner, AutoTraderBlockEntity trader) {
        TradeRule rule = owned(owner, trader);
        if (rule == null) return refused(EconomyHealth.frozen() ? RuleResult.FROZEN : RuleResult.NOT_FOUND);
        EconomyStore store = EconomyStore.get(owner.level().getServer());
        return store.rules().delete(Trading.env(store), rule.owner(), rule.id());
    }

    private static TradeRule owned(ServerPlayer owner, AutoTraderBlockEntity trader) {
        if (owner == null || trader == null || !(trader.getLevel() instanceof ServerLevel level)
                || !trader.isOwner(owner.getUUID())) return null;
        return rule(level, trader.getBlockPos(), owner.getUUID());
    }

    private static RuleOutcome refused(RuleResult result) { return new RuleOutcome(result, null, 0L, 0L, false); }

    // --- what the block reports --------------------------------------------------------------

    /** The block's slots changed, or its chunk loaded or unloaded: its rule is looked at again in its turn. */
    public static void wake(ServerLevel level, BlockPos pos) {
        if (level == null || EconomyHealth.frozen()) return;
        EconomyStore store = EconomyStore.get(level.getServer());
        if (store.rules().size() == 0) return;
        for (TradeRule rule : store.rules().boundTo(binding(location(level, pos)))) store.rules().enqueue(rule.id());
    }

    /**
     * The Auto-Trader at a position is gone, or is being torn down. Its rule is deleted — the
     * budget goes back to the owner and, the block being unreachable by now, the goods the rule
     * had listed go to the owner's vault — and its owner row is dropped. What the slots themselves
     * hold is not touched here: it is in the world, and drops like a chest's contents. Safe to
     * call when nothing is recorded, and safe to call twice, so every removal path may call it.
     */
    public static void removed(ServerLevel level, BlockPos pos) {
        // Nothing is written while frozen; the sweep settles the records once the store is back.
        if (level == null || EconomyHealth.frozen()) return;
        EconomyStore store = EconomyStore.get(level.getServer());
        // Goods may have crossed between the book and a vault; get the store to disk promptly.
        if (TraderRules.release(store, Trading.env(store), location(level, pos)))
            NeroEconomyCommon.CURRENCY_PROVIDER.requestSave();
    }

    // --- the sweep ---------------------------------------------------------------------------

    /**
     * Finds Auto-Trader records whose block is no longer there and releases them. A block reports
     * its own removal whenever the game tells it, but one swapped out with side effects suppressed
     * reports nothing. For every recorded location in a chunk that is loaded right now, if the
     * block is not an Auto-Trader its rule is deleted — budget back to its owner, listed goods to
     * their vault — and its owner row dropped. Unloaded chunks are left alone: the sweep never
     * loads one and never judges what it cannot see.
     *
     * @return how many stale locations were released
     */
    public static int sweep(MinecraftServer server) {
        if (server == null || EconomyHealth.frozen()) return 0;
        EconomyStore store = EconomyStore.get(server);
        if (store.autoTraders().isEmpty()) return 0;
        int released = 0;
        for (String location : store.autoTraders().locations()) {
            ShopSweep.Location parsed = ShopSweep.parse(location);
            if (parsed == null) continue;
            Identifier dimension = Identifier.tryParse(parsed.dimension());
            if (dimension == null) continue;
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
            if (level == null) continue;
            BlockPos pos = BlockPos.of(parsed.packedPosition());
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null || chunk.getBlockState(pos).getBlock() instanceof AutoTraderBlock) continue;
            if (TraderRules.release(store, Trading.env(store), location)) released++;
        }
        // A released rule's goods went from the book to a vault; get the store to disk promptly.
        if (released > 0) NeroEconomyCommon.CURRENCY_PROVIDER.requestSave();
        return released;
    }
}
