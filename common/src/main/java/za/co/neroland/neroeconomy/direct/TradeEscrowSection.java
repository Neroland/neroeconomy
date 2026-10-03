package za.co.neroland.neroeconomy.direct;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.economy.EconomySection;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.goods.LotHolder;
import za.co.neroland.neroeconomy.goods.Lots;
import za.co.neroland.neroeconomy.trade.Accounts;
import za.co.neroland.neroeconomy.trade.TradeEnv;

/**
 * The trade-escrow section of the economy store: the goods each side of an open direct trade has
 * put on the table. While a trade window is open its "you give" slots are a view of this section,
 * so the goods are in the store — and on disk within a second — from the moment they leave an
 * inventory. Credits are never held here: a direct trade only checks them, and moves them in the
 * swap itself.
 *
 * <p><b>Why it is saved.</b> A trade lives for a minute or two and a server can stop in the
 * middle of one. What a side had put in is then still recorded here under that side's own name,
 * and {@link #recover} — run when the store is loaded — delivers it to their vault. Nothing in
 * escrow can be lost by a crash, and nothing can exist twice: the goods left the inventory before
 * they were recorded, and they leave this section before they are handed to anyone.
 *
 * <p><b>Bounds.</b> One row per account with a trade open, at most {@link #MAX_STACKS} stacks in
 * it. <b>Purge.</b> A row goes when its trade ends; {@link #recover} empties the section.
 * <b>Erasure.</b> {@link #forgetPlayer}. <b>Export.</b> {@link #exportFor}.
 */
public final class TradeEscrowSection extends EconomySection implements LotHolder {
    public static final Codec<TradeEscrowSection> CODEC = codec();
    /** The most stacks one side of a trade may put in: one row of an inventory. */
    public static final int MAX_STACKS = 9;

    private final Map<AccountId, List<Lot>> held = new LinkedHashMap<>();

    public TradeEscrowSection() { }

    // --- reads -------------------------------------------------------------------------------

    /** What the account has in escrow, stack by stack; a copy. */
    public synchronized List<Lot> held(AccountId owner) {
        List<Lot> stacks = held.get(owner);
        return stacks == null ? List.of() : List.copyOf(stacks);
    }

    /** How many of one kind of goods the account has in escrow. */
    public synchronized long total(AccountId owner, String item, int payload) {
        List<Lot> stacks = held.get(owner);
        return stacks == null ? 0L : Lots.total(stacks, item, payload);
    }

    /** Every account with anything in escrow. */
    public synchronized Set<AccountId> owners() { return Set.copyOf(held.keySet()); }

    @Override public synchronized void forEachLot(Consumer<Lot> visitor) {
        for (List<Lot> stacks : held.values()) stacks.forEach(visitor);
    }

    @Override public synchronized boolean isEmpty() { return held.isEmpty(); }

    // --- the inventory boundary --------------------------------------------------------------

    /**
     * Replaces what the account has in escrow. This is the write a trade window makes after the
     * game has moved stacks between its "you give" slots and the player's inventory: the caller
     * vouches that the difference went to, or came from, that inventory. Nothing else may use it.
     *
     * @return false, with nothing changed, when the stacks are more than {@link #MAX_STACKS} or the
     *         account is not a player's; true otherwise, changed or not
     */
    public synchronized boolean replace(AccountId owner, List<Lot> stacks) {
        if (!(owner instanceof PlayerAccountId) || stacks == null) return false;
        List<Lot> next = new ArrayList<>(stacks.size());
        for (Lot lot : stacks) if (lot != null) next.add(lot);
        if (next.size() > MAX_STACKS) return false;
        if (next.equals(held.getOrDefault(owner, List.of()))) return true;
        if (next.isEmpty()) held.remove(owner);
        else held.put(owner, next);
        setDirty();
        return true;
    }

    /**
     * Takes everything the account has in escrow out of the section and hands it to the caller,
     * who must put every lot somewhere: the other side of a swap, or back with its owner.
     */
    public synchronized List<Lot> release(AccountId owner) {
        List<Lot> stacks = owner == null ? null : held.remove(owner);
        if (stacks == null) return List.of();
        setDirty();
        return List.copyOf(stacks);
    }

    // --- recovery ----------------------------------------------------------------------------

    /**
     * Delivers what one account has in escrow to its own vault. A delivery always books, so this
     * cannot fail. @return what was delivered
     */
    public synchronized List<Lot> returnToVault(TradeEnv env, AccountId owner) {
        EconomyStore store = owner();
        if (store == null || env == null) return List.of();
        List<Lot> stacks = release(owner);
        for (Lot lot : stacks) VaultGoods.deliver(store, env, owner, lot);
        return stacks;
    }

    /**
     * Returns everything in escrow to its owners' vaults: what a load does, because no trade
     * window survives a restart.
     *
     * @return how many stacks were returned
     */
    public synchronized int recover(TradeEnv env) {
        if (owner() == null || env == null) return 0;
        int returned = 0;
        for (AccountId owner : new ArrayList<>(held.keySet())) returned += returnToVault(env, owner).size();
        return returned;
    }

    // --- erasure and export ------------------------------------------------------------------

    /**
     * Destroys what one player has in escrow, as their vault is destroyed.
     *
     * @return what was destroyed, so a caller that counts goods can account for it
     */
    public synchronized List<Lot> forgetPlayer(UUID player) {
        return player == null ? List.of() : release(new PlayerAccountId(player));
    }

    /** Data-subject export: the stacks the player has in an open trade, as item id and count only. */
    public synchronized JsonArray exportFor(UUID player) {
        JsonArray out = new JsonArray();
        for (Lot lot : held(Accounts.of(player))) {
            JsonObject row = new JsonObject();
            row.addProperty("item", lot.item());
            row.addProperty("count", lot.count());
            out.add(row);
        }
        return out;
    }

    // --- persistence -------------------------------------------------------------------------

    private record LotRow(String item, int count, int payload) {
        static final Codec<LotRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("item").forGetter(LotRow::item),
                Codec.INT.fieldOf("count").forGetter(LotRow::count),
                Codec.INT.optionalFieldOf("payload", Lot.PRISTINE).forGetter(LotRow::payload))
                .apply(instance, LotRow::new));
    }

    private record AccountRow(String account, List<LotRow> stacks) {
        static final Codec<AccountRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("account").forGetter(AccountRow::account),
                LotRow.CODEC.listOf().optionalFieldOf("stacks", List.of()).forGetter(AccountRow::stacks))
                .apply(instance, AccountRow::new));
    }

    private static Codec<TradeEscrowSection> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                AccountRow.CODEC.listOf().optionalFieldOf("accounts", List.of()).forGetter(TradeEscrowSection::rows))
                .apply(instance, TradeEscrowSection::fromRows));
    }

    private synchronized List<AccountRow> rows() {
        List<AccountRow> rows = new ArrayList<>(held.size());
        held.forEach((account, stacks) -> rows.add(new AccountRow(account.stableKey(),
                stacks.stream().map(lot -> new LotRow(lot.item(), lot.count(), lot.payload())).toList())));
        return rows;
    }

    private static TradeEscrowSection fromRows(List<AccountRow> rows) {
        TradeEscrowSection section = new TradeEscrowSection();
        for (AccountRow row : rows) {
            AccountId account = Accounts.parse(row.account());
            if (!(account instanceof PlayerAccountId)) continue;
            // A row that is not a lot is skipped; every other stack of the account still loads. More
            // stacks than a trade may hold are kept all the same: they are somebody's goods.
            List<Lot> stacks = section.held.computeIfAbsent(account, ignored -> new ArrayList<>());
            for (LotRow lot : row.stacks())
                if (Lot.valid(lot.item(), lot.count(), lot.payload())) stacks.add(new Lot(lot.item(), lot.count(), lot.payload()));
            if (stacks.isEmpty()) section.held.remove(account);
        }
        return section;
    }
}
