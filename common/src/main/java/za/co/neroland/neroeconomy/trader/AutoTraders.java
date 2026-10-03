package za.co.neroland.neroeconomy.trader;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.chunk.LevelChunk;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.block.AutoTraderBlock;
import za.co.neroland.neroeconomy.block.AutoTraderBlockEntity;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.rules.RuleBinder;
import za.co.neroland.neroeconomy.rules.RuleInventory;
import za.co.neroland.neroeconomy.rules.RuleOutcome;
import za.co.neroland.neroeconomy.rules.RuleResult;
import za.co.neroland.neroeconomy.rules.RuleSide;
import za.co.neroland.neroeconomy.rules.TradeRule;
import za.co.neroland.neroeconomy.shop.ShopOwnershipState;
import za.co.neroland.neroeconomy.shop.ShopSweep;
import za.co.neroland.neroeconomy.trade.Accounts;
import za.co.neroland.neroeconomy.trade.TradeEnv;
import za.co.neroland.neroeconomy.trade.Trading;
import za.co.neroland.neroeconomy.vault.VaultStacks;

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
 */
public final class AutoTraders {
    /** What the binding of a rule that works from an Auto-Trader starts with. */
    public static final String BINDING_PREFIX = "trader:";

    /** Slots that cannot be reached: the block is not loaded, or is no longer there. */
    private static final TraderInventory.Slots UNLOADED = new TraderInventory.Slots() {
        @Override public boolean reachable() { return false; }
        @Override public boolean empty(int slot) { return false; }
        @Override public Lot unmodified(int slot) { return null; }
        @Override public void set(int slot, Lot lot) { throw new IllegalStateException("unloaded Auto-Trader"); }
        @Override public int limit(String item) { return 1; }
    };

    /** Knows the inventories of Auto-Trader blocks; plugged into the trading rules at start-up. */
    public static final RuleBinder BINDER = AutoTraders::resolve;

    private AutoTraders() { }

    // --- locations and bindings --------------------------------------------------------------

    /** The {@code dimension|packedPosition} key an Auto-Trader at this position is recorded under. */
    public static String location(ServerLevel level, BlockPos pos) { return ShopOwnershipState.key(level, pos); }

    public static String binding(String location) { return BINDING_PREFIX + location; }

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

    // --- the binder --------------------------------------------------------------------------

    private static RuleInventory resolve(EconomyStore store, TradeEnv env, AccountId owner, String binding) {
        if (store == null || binding == null || !binding.startsWith(BINDING_PREFIX)) return null;
        String location = binding.substring(BINDING_PREFIX.length());
        UUID player = Accounts.player(owner);
        // Somebody else's Auto-Trader, or none recorded there: this binding names nothing of theirs.
        if (player == null || !player.equals(store.autoTraders().owner(location))) return null;
        TradeRule rule = store.rules().boundTo(owner, binding);
        Consumer<Lot> vault = lot -> VaultGoods.deliver(store, env, owner, lot);
        AutoTraderBlockEntity trader = loaded(location);
        return new TraderInventory(trader == null ? UNLOADED : trader.slots(), rule != null && !rule.buys(), vault);
    }

    /**
     * The Auto-Trader at a location, if its chunk is loaded and ticking right now. Never loads a
     * chunk and never creates a block entity.
     */
    private static AutoTraderBlockEntity loaded(String location) {
        MinecraftServer server = NeroEconomyCommon.CURRENCY_PROVIDER.serverOrNull();
        ShopSweep.Location parsed = ShopSweep.parse(location);
        Identifier dimension = parsed == null ? null : Identifier.tryParse(parsed.dimension());
        if (server == null || dimension == null) return null;
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
        TradeEnv env = Trading.env(store);
        AccountId account = new PlayerAccountId(owner.getUUID());
        String binding = binding(location(level, trader.getBlockPos()));
        TradeRule existing = store.rules().boundTo(account, binding);
        if (existing != null) {
            RuleOutcome deleted = store.rules().delete(env, account, existing.id());
            if (!deleted.ok()) return deleted;
        }
        return store.rules().add(env, account, side, tag, target, limitPrice, perDay, total, binding, null);
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
     * had listed go to the owner's vault — the goods in its output slots go to the owner's vault
     * too, and its owner row is dropped. Safe to call when nothing is recorded, and safe to call
     * twice, so every removal path may call it.
     *
     * @param trader the block entity while it still exists, so its output slots can be emptied;
     *               null when it is already gone
     */
    public static void removed(ServerLevel level, BlockPos pos, AutoTraderBlockEntity trader) {
        // Nothing is written while frozen; the sweep settles the records once the store is back.
        if (level == null || EconomyHealth.frozen()) return;
        EconomyStore store = EconomyStore.get(level.getServer());
        String location = location(level, pos);
        UUID owner = store.autoTraders().owner(location);
        boolean changed = deleteRules(store, location);
        if (trader != null && owner != null) {
            // What the owner bought is the owner's: it goes to their vault, never onto the ground.
            for (ItemStack stack : trader.drainOutputs()) {
                VaultStacks.deliver(store, owner, stack);
                changed = true;
            }
        }
        changed |= store.autoTraders().remove(location);
        // Goods may have crossed between the world and the store; get the store to disk promptly.
        if (changed) NeroEconomyCommon.CURRENCY_PROVIDER.requestSave();
    }

    /** Deletes every rule bound to a location, whoever owns it. @return whether there was one */
    private static boolean deleteRules(EconomyStore store, String location) {
        List<TradeRule> bound = store.rules().boundTo(binding(location));
        if (bound.isEmpty()) return false;
        TradeEnv env = Trading.env(store);
        // A rule whose budget cannot be paid back just now stays, paused, with its budget in
        // escrow; its owner deletes it with the rule command once the claim has been collected.
        for (TradeRule rule : bound) store.rules().delete(env, rule.owner(), rule.id());
        return true;
    }

    /**
     * Something emptied the block without breaking it (a command, or another mod, clearing a
     * container before replacing it). Nothing is destroyed: with an owner on record the stacks go
     * to their vault. Without one they are the caller's to discard, as a chest's would be.
     */
    public static void cleared(ServerLevel level, BlockPos pos, List<ItemStack> contents) {
        if (level == null || contents == null || contents.isEmpty() || EconomyHealth.frozen()) return;
        EconomyStore store = EconomyStore.get(level.getServer());
        UUID owner = store.autoTraders().owner(location(level, pos));
        if (owner == null) return;
        for (ItemStack stack : contents) VaultStacks.deliver(store, owner, stack);
        NeroEconomyCommon.CURRENCY_PROVIDER.requestSave();
    }

    // --- the sweep ---------------------------------------------------------------------------

    /**
     * Finds Auto-Trader records whose block is no longer there and releases them. A block reports
     * its own removal whenever the game tells it, but one swapped out with side effects suppressed
     * reports nothing. For every recorded location in a chunk that is loaded right now, if the
     * block is not an Auto-Trader its rule is deleted and its owner row dropped. Unloaded chunks
     * are left alone: the sweep never loads one and never judges what it cannot see.
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
            Identifier dimension = parsed == null ? null : Identifier.tryParse(parsed.dimension());
            if (dimension == null) continue;
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
            if (level == null) continue;
            BlockPos pos = BlockPos.of(parsed.packedPosition());
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null || chunk.getBlockState(pos).getBlock() instanceof AutoTraderBlock) continue;
            deleteRules(store, location);
            store.autoTraders().remove(location);
            released++;
        }
        return released;
    }
}
