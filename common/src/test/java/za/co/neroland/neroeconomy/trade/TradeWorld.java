package za.co.neroland.neroeconomy.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.StoreTesting;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.goods.StackLimit;
import za.co.neroland.neroeconomy.link.EconomyAlerts;

/**
 * What a test of the trading rules starts from: a real economy store with a filled Reserve, a
 * clock the test moves, an environment with known settings, and a few short-hands for the things
 * every such test does — open an account, hand out credits and goods, read where they ended up.
 * No game, no registries. Call {@link #close()} in an {@code @AfterEach}.
 */
public final class TradeWorld {
    public static final long RESERVE = 250_000L;
    public static final long HOUR = 3_600_000L;
    public static final long DAY = 86_400_000L;
    public static final StackLimit LIMIT = StackLimit.of(64);
    public static final String DIAMOND = "minecraft:diamond";
    public static final String IRON = "minecraft:iron_ingot";
    public static final String GOLD = "minecraft:gold_ingot";

    /** The real store. {@link #reload()} replaces it with a copy read back through its codecs. */
    public EconomyStore store = new EconomyStore();
    /** The wall clock the ledger reads; a test moves it. */
    public long wall = 1_800_000_000_000L;
    /** The environment handed to every operation; a test replaces it to change a setting. */
    public TradeEnv env;
    /** Every alert raised, in order. */
    public final List<Map.Entry<AccountId, EconomyAlerts.Kind>> alerts = new ArrayList<>();

    public TradeWorld() {
        StoreTesting.wallClock(() -> wall);
        store.ledger().seedReserve(RESERVE);
        env = new TradeEnv(settings(), LIMIT, BuyerPricing.NONE, ItemCatalog.OPEN,
                (account, kind) -> alerts.add(Map.entry(account, kind)), null);
    }

    /** The defaults of the configuration: a 2 % market fee, a listing fee of 1, no trade tax. */
    public static TradeSettings settings() {
        return new TradeSettings(2.0D, 1L, 64, 32, 48L * HOUR, 54, 54, 216, 0.0D, 48L * HOUR, 16, 64, 8, 10_000L, 64);
    }

    /** Back to the real clock. */
    public void close() { StoreTesting.wallClock(null); }

    /** A save followed by a load: the store is written with its codecs and read into a fresh one. */
    public void reload() { store = StoreTesting.reload(store); }

    public EconomySavedData ledger() { return store.ledger(); }

    public long now() { return store.ledger().now(); }

    public static AccountId account(UUID player) { return new PlayerAccountId(player); }

    /**
     * Opens player {@code number}'s account — which pays the starting balance of 100 out of the
     * Reserve — marks it vested, and tops it up to exactly {@code credits}.
     */
    public UUID player(int number, long credits) {
        UUID player = new UUID(7L, number);
        EconomySavedData ledger = store.ledger();
        ledger.withdraw(player, NeroEconomyCommon.CREDITS, 0L);
        ledger.markVested(player);
        long held = ledger.getBalance(player, NeroEconomyCommon.CREDITS);
        if (credits > held) ledger.deposit(player, NeroEconomyCommon.CREDITS, credits - held, TransactionType.ADMIN_GRANT);
        else if (credits < held) ledger.withdraw(player, NeroEconomyCommon.CREDITS, held - credits, TransactionType.ADMIN_TAKE);
        return player;
    }

    /** Opens an account and leaves its starting balance of 100 locked, as a new player's is. */
    public UUID newcomer(int number) {
        UUID player = new UUID(7L, number);
        store.ledger().withdraw(player, NeroEconomyCommon.CREDITS, 0L);
        return player;
    }

    /** Puts goods into a player's vault, as a delivery does. */
    public void stock(UUID player, Lot lot) {
        store.vault().deliver(account(player), List.of(lot), env.settings().vaultCap(), env.settings().vaultHardBound(),
                env.limit());
    }

    public long balance(UUID player) { return store.ledger().getBalance(player, NeroEconomyCommon.CREDITS); }
    public long reserve() { return store.ledger().balance(SystemAccountId.RESERVE); }
    public long escrow() { return store.ledger().balance(SystemAccountId.ESCROW); }
    /** What is waiting for the player to collect. */
    public long claim(UUID player) { return store.market().creditClaimTotal(player); }
    public long held(UUID player, String item) { return store.vault().total(account(player), item, Lot.PRISTINE); }
    public long held(UUID player, String item, int payload) { return store.vault().total(account(player), item, payload); }

    /** How many of one kind of goods the whole store holds: vaults, orders, offers, everything. */
    public long inStore(String item, int payload) {
        long[] total = new long[1];
        store.forEachLot(lot -> { if (lot.is(item, payload)) total[0] += lot.count(); });
        return total[0];
    }

    /** Invariant A and Invariant B, as the store itself checks them. */
    public boolean invariants() { return store.checkInvariants(); }
}
