package za.co.neroland.neroeconomy.vault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.economy.EconomySection;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.goods.LotHolder;
import za.co.neroland.neroeconomy.goods.StackLimit;

/**
 * The vault section of the economy store: every account's goods, as lots. It replaces the item
 * claims the market section used to keep, so everything the system owes a player in goods — a
 * purchase made while away, a refund, an expired listing, a filled order — waits here.
 *
 * <p>This class is the {@link Vault} rules plus what makes them a section: the saved form, the
 * dirty flag, the eraser and the export. It holds lots only, never an item stack, so it loads,
 * saves and is tested without the game's registries. A lot's payload id points into the prototype
 * table of {@code GoodsSection}; this section reports its lots to that table through
 * {@link LotHolder}.
 *
 * <p><b>Bounds.</b> A player can only add stacks up to the cap ({@code vaultStacks}). The system
 * may deliver past it, because refusing would destroy goods; such a delivery is reported, and the
 * caller warns an operator once an account is past the hard bound. <b>Purge.</b> An account that
 * holds nothing has no row. <b>Erasure.</b> {@link #eraseFor} destroys the player's vault.
 * <b>Export.</b> {@link #exportFor} lists item id and count only.
 */
public final class VaultSection extends EconomySection implements LotHolder {
    public static final Codec<VaultSection> CODEC = codec();

    private final Vault vault = new Vault();

    public VaultSection() { }

    /** The vault section of the world's economy store. */
    public static VaultSection get(MinecraftServer server) { return EconomyStore.get(server).vault(); }

    /**
     * Erasure entry point; tolerates an unknown player. The erased player's goods are destroyed,
     * prototypes nothing refers to any more are dropped with them, and the purge is pushed into
     * Core's recovery backup in the same request.
     */
    public static void eraseFor(MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        EconomyStore store = EconomyStore.get(server);
        store.vault().forgetPlayer(player);
        // A prototype can carry a name or other data; it must not outlive the last lot that used it.
        store.goods().sweep();
        EconomyStore.afterErasure(server, player);
    }

    private static AccountId account(UUID player) { return player == null ? null : new PlayerAccountId(player); }

    // --- reads -------------------------------------------------------------------------------

    public synchronized List<Lot> stacks(AccountId account) { return vault.stacks(account); }
    public synchronized List<Lot> stacks(UUID player) { return vault.stacks(account(player)); }
    public synchronized int stackCount(AccountId account) { return vault.stackCount(account); }
    public synchronized int stackCount(UUID player) { return vault.stackCount(account(player)); }
    public synchronized long total(AccountId account, String item, int payload) { return vault.total(account, item, payload); }
    public synchronized Map<Lot.Key, Long> totals(AccountId account) { return vault.totals(account); }
    public synchronized boolean overCap(AccountId account, int cap) { return vault.overCap(account, cap); }
    public synchronized long revision(AccountId account) { return vault.revision(account); }
    public synchronized java.util.Set<AccountId> accounts() { return vault.accounts(); }

    @Override public synchronized void forEachLot(Consumer<Lot> visitor) { vault.forEachLot(visitor); }

    @Override public synchronized boolean isEmpty() { return vault.isEmpty(); }

    // --- goods coming in ---------------------------------------------------------------------

    /** See {@link Vault#canAccept}: the pre-flight for anything a player asks for. */
    public synchronized boolean canAccept(AccountId account, Collection<Lot> lots, int cap, StackLimit limit) {
        return vault.canAccept(account, lots, cap, limit);
    }

    /** See {@link Vault#deposit}: a player's deposit, all or nothing against the cap. */
    public synchronized boolean deposit(AccountId account, Collection<Lot> lots, int cap, StackLimit limit) {
        if (!vault.deposit(account, lots, cap, limit)) return false;
        setDirty();
        return true;
    }

    /** See {@link Vault#deliver}: a system delivery, which always books. */
    public synchronized Vault.Delivery deliver(AccountId account, Collection<Lot> lots, int cap, int hardBound,
            StackLimit limit) {
        Vault.Delivery delivery = vault.deliver(account, lots, cap, hardBound, limit);
        setDirty();
        return delivery;
    }

    // --- goods going out ---------------------------------------------------------------------

    /** See {@link Vault#withdraw}: up to {@code count} of one kind of goods. */
    public synchronized long withdraw(AccountId account, String item, int payload, long count) {
        long taken = vault.withdraw(account, item, payload, count);
        if (taken > 0L) setDirty();
        return taken;
    }

    /** See {@link Vault#take}: exactly {@code lot}, or nothing. */
    public synchronized boolean take(AccountId account, Lot lot) {
        if (!vault.take(account, lot)) return false;
        setDirty();
        return true;
    }

    /** See {@link Vault#withdrawAt}: up to {@code count} from one stack. */
    public synchronized Lot withdrawAt(AccountId account, int index, int count) {
        Lot taken = vault.withdrawAt(account, index, count);
        if (taken != null) setDirty();
        return taken;
    }

    /** See {@link Vault#replaceAll}: the live view's write, and nothing else's. */
    public synchronized boolean replaceAll(AccountId account, List<Lot> stacks) {
        if (!vault.replaceAll(account, stacks)) return false;
        setDirty();
        return true;
    }

    // --- erasure and export ------------------------------------------------------------------

    /**
     * Destroys one player's vault. @return what was destroyed, so a caller that counts goods can
     * account for it
     */
    public synchronized List<Lot> forgetPlayer(UUID player) {
        if (player == null) return List.of();
        List<Lot> destroyed = vault.erase(account(player));
        if (!destroyed.isEmpty()) setDirty();
        return destroyed;
    }

    /**
     * Data-subject export: the player's stacks as item id and count. Components are never
     * exported: a stored stack can carry a name or contents that are somebody else's data.
     */
    public synchronized JsonArray exportFor(UUID player) {
        JsonArray out = new JsonArray();
        for (Lot lot : vault.stacks(account(player))) {
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
                // Left out for an unmodified stack, which is nearly all of them.
                Codec.INT.optionalFieldOf("payload", Lot.PRISTINE).forGetter(LotRow::payload))
                .apply(instance, LotRow::new));
    }

    private record AccountRow(String account, List<LotRow> stacks) {
        static final Codec<AccountRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("account").forGetter(AccountRow::account),
                LotRow.CODEC.listOf().optionalFieldOf("stacks", List.of()).forGetter(AccountRow::stacks))
                .apply(instance, AccountRow::new));
    }

    private static Codec<VaultSection> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                AccountRow.CODEC.listOf().optionalFieldOf("accounts", List.of()).forGetter(VaultSection::rows))
                .apply(instance, VaultSection::fromRows));
    }

    private synchronized List<AccountRow> rows() {
        List<AccountRow> rows = new ArrayList<>();
        vault.snapshot().forEach((account, stacks) -> rows.add(new AccountRow(account.stableKey(),
                stacks.stream().map(lot -> new LotRow(lot.item(), lot.count(), lot.payload())).toList())));
        return rows;
    }

    private static VaultSection fromRows(List<AccountRow> rows) {
        VaultSection section = new VaultSection();
        for (AccountRow row : rows) {
            AccountId account = parseAccount(row.account());
            if (account == null) continue;
            List<Lot> stacks = new ArrayList<>(row.stacks().size());
            // A row that is not a lot is skipped; every other stack of the account still loads.
            for (LotRow lot : row.stacks())
                if (Lot.valid(lot.item(), lot.count(), lot.payload())) stacks.add(new Lot(lot.item(), lot.count(), lot.payload()));
            section.vault.restore(account, stacks);
        }
        return section;
    }

    /** {@code player:<uuid>} or {@code system:<name>}; null for anything else. */
    private static AccountId parseAccount(String key) {
        if (key == null) return null;
        int separator = key.indexOf(':');
        if (separator < 1) return null;
        String kind = key.substring(0, separator);
        String value = key.substring(separator + 1);
        if (SystemAccountId.KIND.equals(kind)) return SystemAccountId.parse(value);
        if (!"player".equals(kind)) return null;
        try { return new PlayerAccountId(UUID.fromString(value)); }
        catch (IllegalArgumentException malformed) { return null; }
    }
}
