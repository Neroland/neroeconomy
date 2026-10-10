package za.co.neroland.neroeconomy.economy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.nerolandcore.economy.Currency;
import za.co.neroland.nerolandcore.economy.CurrencyProvider;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.admin.AdminSection;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.config.EconomyConfig;

/**
 * The ledger: every balance, player and system alike, and the audit trail of how it got there. It
 * is the ledger section of the combined economy store; every method runs on the server thread.
 *
 * <p><b>Money never leaves the ledger.</b> There are exactly three ways a balance changes:
 * <ul>
 *   <li>a <b>transfer</b> between two accounts ({@link #apply}, {@link #transfer}), which is what
 *       every payment, escrow, claim, fee and refund is;</li>
 *   <li>a <b>mint</b> ({@link #deposit(AccountId, Currency, long, TransactionType)}), accepted only
 *       under the four reasons {@link TransactionType#mints()} names;</li>
 *   <li>a <b>burn</b> ({@link #withdraw(AccountId, Currency, long, TransactionType)}), accepted only
 *       under the two reasons {@link TransactionType#burns()} names.</li>
 * </ul>
 * Every mint and burn is counted in {@link Supply}, so the sum of all balances always equals
 * {@code minted − burned} ({@link #supplyBalanced()}).
 *
 * <p>The three {@link CurrencyProvider} methods are what another mod reaches through Core without a
 * reason of its own: a deposit is paid by the Reserve within the sibling budget, a withdrawal is
 * paid to the Reserve, and a transfer is a player payment.
 */
public final class EconomySavedData extends EconomySection implements CurrencyProvider {
    /** The id this section was stored under when it was a store of its own; read once, on migration. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "ledger");
    // Declared before the codec, which uses it as the default of a field that old saves leave out.
    private static final String CREDITS_ID = NeroEconomyCommon.CREDITS.id().toString();
    static final Codec<EconomySavedData> CODEC = codec();
    /** The pre-combined-store type. Only {@link EconomyStore} uses it, to migrate and then empty the old file. */
    public static final SavedDataType<EconomySavedData> TYPE =
            new SavedDataType<>(ID, EconomySavedData::new, CODEC, null);
    private static final long MILLIS_PER_DAY = 86_400_000L;
    /** How far the audit list may run past its bound before an append pays for a purge. */
    private static final int PURGE_SLACK = 256;
    /** The clock's wall-time watermark is re-saved at most this often; an offset change saves at once. */
    private static final long CLOCK_SAVE_INTERVAL_MILLIS = 60_000L;
    /** Stands in for the other party's pseudonym in a data-subject export. */
    public static final String COUNTERPARTY = "counterparty";
    /** The wall clock every ledger reads. Only a test replaces it, to make a long run repeatable. */
    private static volatile LongSupplier wallClock = System::currentTimeMillis;

    /** Credit Chips one account redeemed on one economy day; yesterday's row is swept, not kept. */
    private record ChipCount(long day, int count) { }

    private final Map<AccountId, Long> balances = new LinkedHashMap<>();
    private final Set<AccountId> initialized = new LinkedHashSet<>();
    private final Set<AccountId> loggingOptOut = new LinkedHashSet<>();
    /** Accounts that were given the Trader's Handbook on joining; it is never given twice. */
    private final Set<AccountId> handbookGiven = new LinkedHashSet<>();
    /** Salted pseudonyms of erased accounts. Never a raw id, so the tombstone carries no identity. */
    private final Set<String> erased = new LinkedHashSet<>();
    private final List<TransactionRecord> transactions = new ArrayList<>();
    private final Map<AccountId, ChipCount> chipRedemptions = new LinkedHashMap<>();
    /** What is left of an unvested account's starting grant; such credits only reach system counterparties. */
    private final Map<AccountId, Long> locked = new LinkedHashMap<>();
    /** Accounts opened under vesting whose play time has not reached the threshold yet. */
    private final Set<AccountId> unvested = new LinkedHashSet<>();
    private final Supply supply = new Supply();
    private final Reserve reserve = new Reserve();
    /** False for a ledger written before supply was counted, until {@link #adoptLegacySupply} has run. */
    private boolean supplyTracked = true;
    private String pseudonymSalt = UUID.randomUUID().toString();
    private EconomyClock clock = new EconomyClock();
    /** The wall-time watermark as last marked for saving, so the encoded store is stable in between. */
    private long savedWallMillis;
    /**
     * While a simulation step runs, the thread running it reads the step's own time from
     * {@link #now()} instead of the clock (see {@link #runAt}). Not saved.
     */
    private Thread pinnedThread;
    private long pinnedMillis;
    /**
     * Which other mod's call is running now, as {@code <modid>/<reason>}: written on every row the
     * call appends, so an operator can tell which mod paid or charged what. Empty otherwise. Not saved.
     */
    private String origin = "";

    private final Reserve.Book book = new Reserve.Book() {
        @Override public long reserveBalance() { return balanceOf(SystemAccountId.RESERVE); }
        @Override public boolean canCredit(AccountId account, long amount) {
            return account != null && amount >= 0L && Long.MAX_VALUE - balanceOf(account) >= amount;
        }
        @Override public void payOut(AccountId account, long amount, TransactionType reason) {
            moveUnchecked(SystemAccountId.RESERVE, account, amount, reason);
        }
        @Override public boolean mintReserve(long amount) {
            return mint(SystemAccountId.RESERVE, amount, TransactionType.RESERVE_MINT);
        }
    };

    public EconomySavedData() { }

    /** The ledger section of the world's economy store. */
    public static EconomySavedData get(MinecraftServer server) {
        return EconomyStore.get(server).ledger();
    }

    /**
     * Erasure entry point. Works without a bound provider, tolerates an unknown player, and pushes
     * the purge into Core's recovery backup in the same request, because that backup is a second
     * copy of the same rows.
     */
    public static void eraseFor(MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        EconomyStore.get(server).ledger().forgetPlayer(player);
        EconomyStore.afterErasure(server, player);
    }

    // --- clock -------------------------------------------------------------------------------

    /**
     * Economy time: the wall clock corrected by {@link EconomyClock}. Every expiry, quote, retention
     * cut-off and transaction timestamp in the mod is measured against this, never the raw wall clock.
     */
    public synchronized long now() {
        if (pinnedThread == Thread.currentThread()) return pinnedMillis;
        long wall = wallClock.getAsLong();
        long offsetBefore = clock.offsetMillis();
        long value = clock.observe(wall);
        if (clock.offsetMillis() != offsetBefore || Math.abs(wall - savedWallMillis) >= CLOCK_SAVE_INTERVAL_MILLIS) {
            savedWallMillis = wall;
            setDirty();
        }
        return value;
    }

    /**
     * Runs {@code work} with {@link #now()} answering {@code economyMillis} on this thread: what a
     * simulation step does, so that everything it books — a budget's day, a transaction's time, an
     * order's expiry — follows the step's own time and a step replayed after a pause does exactly
     * what it would have done on time. Other threads, and this one afterwards, read the clock as
     * usual; the clock itself is not moved. A pin inside a pin keeps the outer one's time afterwards.
     */
    public void runAt(long economyMillis, Runnable work) {
        Thread previousThread;
        long previousMillis;
        synchronized (this) {
            previousThread = pinnedThread;
            previousMillis = pinnedMillis;
            pinnedThread = Thread.currentThread();
            pinnedMillis = economyMillis;
        }
        try {
            work.run();
        } finally {
            synchronized (this) {
                pinnedThread = previousThread;
                pinnedMillis = previousMillis;
            }
        }
    }

    /** See {@link EconomyClock#toWallMillis}: the form in which an economy timestamp leaves the server. */
    public synchronized long toWallMillis(long economyMillis) { return clock.toWallMillis(economyMillis); }

    /** The economy day a timestamp falls in: the unit budgets, daily mints and chip counters use. */
    public static long economyDay(long economyMillis) { return Math.floorDiv(economyMillis, MILLIS_PER_DAY); }

    private long today() { return economyDay(now()); }

    // --- reads -------------------------------------------------------------------------------

    @Override public synchronized long getBalance(UUID player, Currency currency) {
        if (player == null) return 0L;
        return getBalance(new PlayerAccountId(player), currency);
    }

    /**
     * A read never opens an account. An account that has not been written to yet reports the
     * starting balance its first write would be paid — what the Reserve can cover, possibly nothing —
     * so simply looking at a stranger's balance cannot create ledger state or an audit row for them.
     */
    public synchronized long getBalance(AccountId account, Currency currency) {
        AccountId id = canonical(account);
        if (id == null || !creditsOnly(currency)) return 0L;
        Long balance = balances.get(id);
        if (balance != null) return balance;
        if (!(id instanceof PlayerAccountId) || initialized.contains(id) || tombstoned(id)) return 0L;
        return Math.min(EconomyConfig.STARTING_BALANCE.get(), balanceOf(SystemAccountId.RESERVE));
    }

    /** A system account's balance. */
    public synchronized long balance(SystemAccountId account) { return account == null ? 0L : balanceOf(account); }

    /**
     * What is left of the player's unvested starting grant. These credits pay fees and system
     * counterparties, but cannot be paid, offered or bid to another player. An account nobody has
     * written to yet reports what its first write would lock.
     */
    public synchronized long locked(UUID player) {
        if (player == null) return 0L;
        AccountId account = new PlayerAccountId(player);
        if (balances.containsKey(account) || initialized.contains(account)) return lockedOf(account);
        return vestingEnabled() ? getBalance(account, NeroEconomyCommon.CREDITS) : 0L;
    }

    /** What the player can pay, offer or bid to another player right now: the balance less {@link #locked}. */
    public synchronized long spendable(UUID player) {
        return player == null ? 0L : Math.max(0L, getBalance(player, NeroEconomyCommon.CREDITS) - locked(player));
    }

    /** Whether the account was opened under vesting and has not reached the play time that ends it. */
    public synchronized boolean unvested(UUID player) {
        return player != null && unvested.contains(new PlayerAccountId(player));
    }

    /** Whether any account is still waiting to vest; lets the periodic check skip its work. */
    public synchronized boolean hasUnvested() { return !unvested.isEmpty(); }

    // --- sibling mods: the untyped calls -----------------------------------------------------

    /**
     * A deposit with no reason of its own: paid by the Reserve as {@code SIBLING_PAYOUT} and counted
     * against {@code siblingDailyBudget}. False when the budget or the Reserve cannot cover it.
     */
    @Override public synchronized boolean deposit(UUID player, Currency currency, long amount) {
        return player != null && creditsOnly(currency) && payFromReserve(BudgetKind.SIBLING,
                new PlayerAccountId(player), amount, TransactionType.SIBLING_PAYOUT).ok();
    }

    /** A withdrawal with no reason of its own: paid to the Reserve as {@code SIBLING_CHARGE}. */
    @Override public synchronized boolean withdraw(UUID player, Currency currency, long amount) {
        return player != null && creditsOnly(currency) && apply(LedgerPlan.of(new PlayerAccountId(player),
                SystemAccountId.RESERVE, amount, TransactionType.SIBLING_CHARGE)).ok();
    }

    /** A payment from one player to another; unvested starting credits cannot be sent. */
    @Override public synchronized boolean transfer(UUID from, UUID to, Currency currency, long amount) {
        return transfer(from, to, currency, amount, TransactionType.PLAYER_PAYMENT);
    }

    // --- mint and burn -----------------------------------------------------------------------

    /** See {@link #deposit(AccountId, Currency, long, TransactionType)}. */
    public synchronized boolean deposit(UUID player, Currency currency, long amount, TransactionType type) {
        return player != null && deposit(new PlayerAccountId(player), currency, amount, type);
    }

    /**
     * Creates credits. This is the only way credits come into existence, and it accepts only the
     * minting reasons: {@code RESERVE_MINT} into the Reserve, {@code MIGRATION} into escrow, and an
     * operator's {@code ADMIN_GRANT} or {@code ADMIN_SET} into a player account or the Reserve.
     * Every other reason is refused; a payout is a transfer out of the Reserve instead.
     */
    public synchronized boolean deposit(AccountId account, Currency currency, long amount, TransactionType type) {
        AccountId id = canonical(account);
        if (id == null || !creditsOnly(currency) || amount < 0L || type == null || EconomyHealth.frozen()) return false;
        if (!type.mints()) return false;
        open(id);
        return mint(id, amount, type);
    }

    /** See {@link #withdraw(AccountId, Currency, long, TransactionType)}. */
    public synchronized boolean withdraw(UUID player, Currency currency, long amount, TransactionType type) {
        return player != null && withdraw(new PlayerAccountId(player), currency, amount, type);
    }

    /**
     * Destroys credits. This is the only way credits leave existence, and it accepts only an
     * operator's {@code ADMIN_TAKE} or {@code ADMIN_SET}, from a player account or the Reserve.
     * Every other reason is refused; a charge is a transfer into the Reserve instead.
     */
    public synchronized boolean withdraw(AccountId account, Currency currency, long amount, TransactionType type) {
        AccountId id = canonical(account);
        if (id == null || !creditsOnly(currency) || amount < 0L || type == null || EconomyHealth.frozen()) return false;
        if (!type.burns()) return false;
        open(id);
        return burn(id, amount, type);
    }

    /** An operator sets a player's balance: the difference is minted or burned as {@code ADMIN_SET}. */
    public synchronized boolean setBalance(UUID player, long amount, TransactionType type) {
        if (player == null || amount < 0L || type != TransactionType.ADMIN_SET || EconomyHealth.frozen()) return false;
        AccountId account = new PlayerAccountId(player);
        open(account);
        long before = balanceOf(account);
        if (before == amount) return true;
        return amount > before ? mint(account, amount - before, type) : burn(account, before - amount, type);
    }

    private boolean mint(AccountId account, long amount, TransactionType type) {
        if (!mintTarget(account, type) || !supply.canMint(type, amount)) return false;
        long current = balanceOf(account);
        if (Long.MAX_VALUE - current < amount) return false;
        if (amount == 0L) return true;
        long now = now();
        supply.mint(type, amount, economyDay(now));
        balances.put(account, current + amount);
        append(type, amount, null, account, now);
        setDirty();
        countAdmin(type);
        return true;
    }

    private boolean burn(AccountId account, long amount, TransactionType type) {
        boolean target = account instanceof PlayerAccountId || SystemAccountId.RESERVE.equals(account);
        long current = balanceOf(account);
        if (!target || amount > current || !supply.canBurn(type, amount)) return false;
        if (amount == 0L) return true;
        long now = now();
        supply.burn(type, amount, economyDay(now));
        balances.put(account, current - amount);
        clampLock(account);
        append(type, amount, account, null, now);
        setDirty();
        countAdmin(type);
        return true;
    }

    /**
     * An operator's mint or burn has changed a balance: it is counted in the store's admin section
     * under its own action, whichever way it came in ({@code AdminOps}, the provider's public typed
     * calls, or this ledger directly). Counts only, never whose account or how much. A ledger with
     * no store (a unit test's) has nowhere to count.
     */
    private void countAdmin(TransactionType type) {
        AdminSection.Action action = switch (type) {
            case ADMIN_GRANT -> AdminSection.Action.GRANT;
            case ADMIN_TAKE -> AdminSection.Action.TAKE;
            case ADMIN_SET -> AdminSection.Action.SET;
            default -> null;
        };
        EconomyStore store = owner();
        if (action != null && store != null) store.admin().record(action);
    }

    /** Each minting reason has the accounts it may create credits in, and no others. */
    private static boolean mintTarget(AccountId account, TransactionType type) {
        return switch (type) {
            case RESERVE_MINT -> SystemAccountId.RESERVE.equals(account);
            case MIGRATION -> SystemAccountId.ESCROW.equals(account);
            case ADMIN_GRANT, ADMIN_SET -> account instanceof PlayerAccountId || SystemAccountId.RESERVE.equals(account);
            default -> false;
        };
    }

    // --- transfers ---------------------------------------------------------------------------

    public synchronized boolean transfer(UUID from, UUID to, Currency currency, long amount, TransactionType type) {
        return from != null && to != null && transfer(new PlayerAccountId(from), new PlayerAccountId(to),
                currency, amount, type);
    }

    /** One transfer under the default spending rule; see {@link #apply} for the reason it was refused. */
    public synchronized boolean transfer(AccountId from, AccountId to, Currency currency, long amount,
            TransactionType type) {
        return creditsOnly(currency) && apply(LedgerPlan.of(from, to, amount, type)).ok();
    }

    /**
     * Applies every leg of {@code plan}, or none. Player accounts the plan names are opened first
     * (which is a write of its own, as it always was); then every leg is checked against the
     * balances as the earlier legs leave them, and only when all of them pass does anything move.
     */
    public synchronized LedgerResult apply(LedgerPlan plan, String callOrigin) {
        String before = origin;
        origin = za.co.neroland.neroeconomy.sibling.SiblingRules.validOrigin(callOrigin) ? callOrigin : "";
        try {
            return apply(plan);
        } finally {
            origin = before;
        }
    }

    /** {@link #apply(LedgerPlan, String)} with no origin: every plan this mod makes on its own. */
    public synchronized LedgerResult apply(LedgerPlan plan) {
        if (plan == null) return LedgerResult.INVALID;
        if (EconomyHealth.frozen()) return LedgerResult.FROZEN;
        List<LedgerPlan.Leg> legs = new ArrayList<>(plan.legs().size());
        for (LedgerPlan.Leg leg : plan.legs()) {
            AccountId from = canonical(leg.from());
            AccountId to = canonical(leg.to());
            if (from == null || to == null || leg.amount() < 0L || leg.type() == null || leg.type().mints()
                    || leg.type().burns()) return LedgerResult.INVALID;
            if (leg.budget() != null && !SystemAccountId.RESERVE.equals(from)) return LedgerResult.INVALID;
            legs.add(new LedgerPlan.Leg(from, to, leg.amount(), leg.type(), leg.budget(), leg.lockedSpendable()));
        }
        for (LedgerPlan.Leg leg : legs) {
            open(leg.from());
            open(leg.to());
        }
        long now = now();
        long day = economyDay(now);
        Map<AccountId, Long> next = new HashMap<>();
        Map<AccountId, Long> nextLocked = new HashMap<>();
        EnumMap<BudgetKind, Long> drawn = new EnumMap<>(BudgetKind.class);
        for (LedgerPlan.Leg leg : legs) {
            AccountId from = leg.from();
            long source = next.containsKey(from) ? next.get(from) : balanceOf(from);
            if (leg.amount() > source) return SystemAccountId.RESERVE.equals(from)
                    ? LedgerResult.RESERVE_SHORT : LedgerResult.INSUFFICIENT_FUNDS;
            if (leg.amount() == 0L || from.equals(leg.to())) continue;
            if (from instanceof PlayerAccountId) {
                long held = Math.min(source, nextLocked.containsKey(from) ? nextLocked.get(from) : lockedOf(from));
                if (leg.lockedSpendable()) nextLocked.put(from, Math.max(0L, held - leg.amount()));
                else if (leg.amount() > source - held) return LedgerResult.FUNDS_LOCKED;
            }
            if (leg.budget() != null) {
                long total = drawn.merge(leg.budget(), leg.amount(), EconomySavedData::saturatingAdd);
                if (total > reserve.remaining(leg.budget(), EconomySavedData::dailyLimit, day))
                    return LedgerResult.BUDGET_SPENT;
            }
            AccountId to = leg.to();
            long target = next.containsKey(to) ? next.get(to) : balanceOf(to);
            if (Long.MAX_VALUE - target < leg.amount()) return LedgerResult.OVERFLOW;
            next.put(from, source - leg.amount());
            next.put(to, target + leg.amount());
        }
        if (next.isEmpty()) return LedgerResult.APPLIED;
        balances.putAll(next);
        nextLocked.forEach((account, amount) -> { if (amount <= 0L) locked.remove(account); else locked.put(account, amount); });
        next.keySet().forEach(this::clampLock);
        drawn.forEach((kind, amount) -> reserve.spend(kind, amount, day));
        for (LedgerPlan.Leg leg : legs)
            if (leg.amount() > 0L && !leg.from().equals(leg.to()))
                append(leg.type(), leg.amount(), leg.from(), leg.to(), now);
        setDirty();
        return LedgerResult.APPLIED;
    }

    /** Whether a player-facing payment of {@code amount} is refused only because starting credits are unvested. */
    public synchronized boolean lockBlocks(UUID player, long amount) {
        if (player == null || amount <= 0L) return false;
        long balance = getBalance(player, NeroEconomyCommon.CREDITS);
        return balance >= amount && balance - locked(player) < amount;
    }

    // --- the Reserve -------------------------------------------------------------------------

    /**
     * Pays {@code amount} out of the Reserve, or refuses before anything moves: when the Reserve
     * does not hold it ({@code RESERVE_SHORT}) or the day's budget for {@code kind} is used up
     * ({@code BUDGET_SPENT}).
     */
    public synchronized LedgerResult payFromReserve(BudgetKind kind, AccountId account, long amount,
            TransactionType reason, String callOrigin) {
        String before = origin;
        origin = za.co.neroland.neroeconomy.sibling.SiblingRules.validOrigin(callOrigin) ? callOrigin : "";
        try {
            return payFromReserve(kind, account, amount, reason);
        } finally {
            origin = before;
        }
    }

    /** One payment out of the Reserve with no origin; see {@link #payFromReserve(BudgetKind, AccountId, long, TransactionType, String)}. */
    public synchronized LedgerResult payFromReserve(BudgetKind kind, AccountId account, long amount,
            TransactionType reason) {
        AccountId id = canonical(account);
        if (id == null || kind == null || amount < 0L) return LedgerResult.INVALID;
        if (EconomyHealth.frozen()) return LedgerResult.FROZEN;
        open(id);
        return reserve.tryPay(book, EconomySavedData::dailyLimit, today(), kind, id, amount, reason);
    }

    /**
     * Pays a batch out of the Reserve. A batch the Reserve or the day's budget cannot cover in full
     * is scaled down pro rata; the result carries the factor that was applied.
     */
    public synchronized Reserve.ProRata payProRata(BudgetKind kind, Map<AccountId, Long> amounts,
            TransactionType reason) {
        Map<AccountId, Long> canonical = new LinkedHashMap<>();
        if (amounts != null && !EconomyHealth.frozen()) amounts.forEach((account, amount) -> {
            AccountId id = canonical(account);
            if (id != null && amount != null) canonical.merge(id, Math.max(0L, amount), EconomySavedData::saturatingAdd);
        });
        canonical.keySet().forEach(this::open);
        return reserve.payProRata(book, EconomySavedData::dailyLimit, today(), kind, canonical, reason);
    }

    /** What a pro-rata batch would pay right now, without paying it. */
    public synchronized Reserve.ProRata quoteProRata(BudgetKind kind, Map<AccountId, Long> amounts) {
        long available = Math.min(balanceOf(SystemAccountId.RESERVE),
                reserve.remaining(kind, EconomySavedData::dailyLimit, today()));
        return Reserve.shares(amounts, EconomyHealth.frozen() ? 0L : available);
    }

    /**
     * The Reserve's initial fill: minted once in the life of a world, when its economy is first
     * created or migrated. @return the amount minted now; 0 when the Reserve was already seeded
     */
    public synchronized long seedReserve(long initial) {
        long minted = reserve.seed(book, today(), initial);
        setDirty();
        return minted;
    }

    /** The daily top-up; see {@link Reserve#topUp}. Cheap when no new economy day has started. */
    public synchronized long topUpReserve() { return topUpReserve(today()); }

    /**
     * The daily top-up as of economy day {@code day}. The simulation's housekeeping step calls this
     * with the day its step falls in, so a catch-up after a pause tops up day by day exactly as a
     * server that had been running would have. A day at or before the last top-up does nothing.
     */
    public synchronized long topUpReserve(long day) {
        if (EconomyHealth.frozen() || !reserve.seeded()) return 0L;
        if (day <= reserve.lastMintDay()) return 0L;
        long minted = reserve.topUp(book, day, EconomyConfig.RESERVE_DAILY_MINT.get(), EconomyConfig.RESERVE_CAP.get());
        setDirty();
        return minted;
    }

    /** What {@code kind} has drawn from the Reserve today, and what it may still draw. */
    public synchronized long budgetUsed(BudgetKind kind) { return reserve.used(kind, today()); }
    public synchronized long budgetRemaining(BudgetKind kind) {
        return reserve.remaining(kind, EconomySavedData::dailyLimit, today());
    }

    /** The configured daily limit of each Reserve budget. */
    public static long dailyLimit(BudgetKind kind) {
        return switch (kind) {
            case NPC -> EconomyConfig.NPC_DAILY_BUDGET.get();
            case EXCHANGE -> EconomyConfig.EXCHANGE_DAILY_BUDGET.get();
            case SIBLING -> EconomyConfig.SIBLING_DAILY_BUDGET.get();
            case SYSTEM_JOB -> EconomyConfig.SYSTEM_JOB_DAILY_PAYOUT_CAP.get();
            case UNBUDGETED -> Long.MAX_VALUE;
            case CREDIT_CHIP -> EconomyConfig.CREDIT_CHIP_DAILY_BUDGET.get();
        };
    }

    // --- supply ------------------------------------------------------------------------------

    /**
     * Brings a ledger from before supply was counted into the accounting: the balances it already
     * holds are recorded as the migrated supply, and escrow the old version had written off the
     * ledger is minted back into the escrow account (reason {@code MIGRATION}, once). A ledger that
     * is already tracked only has an escrow shortfall made up, which on a healthy one is nothing.
     *
     * @param obligations the sum of every recorded escrow obligation
     * @return what was minted into escrow
     */
    public synchronized long adoptLegacySupply(long obligations) {
        long day = today();
        if (!supplyTracked) {
            long held = 0L;
            for (long balance : balances.values()) held = saturatingAdd(held, balance);
            // The existing balances are not a movement, so they are counted without an audit row.
            if (!supply.mint(TransactionType.MIGRATION, held, day))
                NeroEconomyCommon.LOGGER.warn("[NeroEconomy] The existing balances are too large to count; "
                        + "the supply check will report a difference.");
            supplyTracked = true;
            setDirty();
        }
        long shortfall = EscrowMath.shortfall(obligations, balanceOf(SystemAccountId.ESCROW));
        if (shortfall == 0L) return 0L;
        if (mint(SystemAccountId.ESCROW, shortfall, TransactionType.MIGRATION)) return shortfall;
        NeroEconomyCommon.LOGGER.warn("[NeroEconomy] Escrow from the previous version could not be restored in "
                + "full; run the economy health command.");
        return 0L;
    }

    /** Invariant A: every balance together equals what was minted less what was burned. */
    public synchronized boolean supplyBalanced() {
        long total = 0L;
        for (long balance : balances.values()) {
            if (Long.MAX_VALUE - total < balance) return false;
            total += balance;
        }
        return supplyTracked && total == supply.net();
    }

    /**
     * Where every credit is, for a ledger that stands alone: with no exchange section to say what
     * the exchange account should hold, Invariant C is taken as met.
     *
     * @param obligations the sum of every recorded escrow obligation, for Invariant B
     */
    public synchronized SupplyReport supplyReport(long obligations) {
        return supplyReport(obligations, balanceOf(SystemAccountId.EXCHANGE));
    }

    /**
     * Where every credit is, as aggregates.
     *
     * @param obligations  the sum of every recorded escrow obligation, for Invariant B
     * @param exchangeHeld what the exchange section records as held for open positions, for Invariant C
     */
    public synchronized SupplyReport supplyReport(long obligations, long exchangeHeld) {
        long circulation = 0L;
        long houses = 0L;
        for (Map.Entry<AccountId, Long> entry : balances.entrySet()) {
            if (entry.getKey() instanceof PlayerAccountId) circulation = saturatingAdd(circulation, entry.getValue());
            else if (entry.getKey() instanceof SystemAccountId system && system.isHouse())
                houses = saturatingAdd(houses, entry.getValue());
        }
        long day = today();
        EnumMap<BudgetKind, Long> budgets = new EnumMap<>(BudgetKind.class);
        for (BudgetKind kind : BudgetKind.values())
            if (kind != BudgetKind.UNBUDGETED) budgets.put(kind, reserve.used(kind, day));
        long escrow = balanceOf(SystemAccountId.ESCROW);
        long exchange = balanceOf(SystemAccountId.EXCHANGE);
        return new SupplyReport(circulation, balanceOf(SystemAccountId.RESERVE), escrow, exchange, houses,
                supply.mintedToday(day), supply.burnedToday(day), supply.mintedTotal(), supply.burnedTotal(),
                supply.mintedByReason(), supply.burnedByReason(), budgets, obligations, supplyBalanced(),
                escrow == obligations, exchangeHeld, exchange == exchangeHeld);
    }

    public synchronized boolean reserveSeeded() { return reserve.seeded(); }

    /** What other mods' accounts hold, summed per mod id (kind), in kind order. Aggregates only. */
    public synchronized Map<String, Long> foreignBalances() {
        Map<String, Long> out = new java.util.TreeMap<>();
        balances.forEach((account, balance) -> {
            if (account instanceof za.co.neroland.neroeconomy.api.ForeignAccountId)
                out.merge(account.kind(), balance, EconomySavedData::saturatingAdd);
        });
        return out;
    }

    // --- vesting -----------------------------------------------------------------------------

    /** Ends the lock on a starting grant: the account has reached the play time that vests it. */
    public synchronized boolean markVested(UUID player) {
        if (player == null) return false;
        AccountId account = new PlayerAccountId(player);
        boolean changed = unvested.remove(account);
        changed |= locked.remove(account) != null;
        if (changed) setDirty();
        return changed;
    }

    /** Whether {@code minutesPlayed} is enough to vest an account under the configured threshold. */
    public static boolean vestingReached(long minutesPlayed) {
        return minutesPlayed >= EconomyConfig.STARTING_BALANCE_VEST_MINUTES.get();
    }

    private static boolean vestingEnabled() { return EconomyConfig.STARTING_BALANCE_VEST_MINUTES.get() > 0; }

    // --- history, privacy --------------------------------------------------------------------

    public synchronized List<TransactionRecord> history(UUID player, int limit) {
        if (player == null || limit <= 0) return List.of();
        return retained(reference(new PlayerAccountId(player))).stream()
                .limit(Math.min(limit, EconomyConfig.HISTORY_LIMIT_PER_PLAYER.get())).toList();
    }

    /**
     * The subject's own recent rows with the direction worked out and every account reference left
     * behind: the form a companion app may be shown. Newest first and cut at
     * {@code historyLimitPerPlayer}, exactly like {@link #history}. A self-transfer writes no row
     * and an opted-out account has none, so a row always has exactly one side that is the subject.
     */
    public synchronized List<SubjectLedgerRow> subjectHistory(UUID player, int limit) {
        if (player == null || limit <= 0) return List.of();
        String ref = reference(new PlayerAccountId(player));
        return retained(ref).stream().limit(Math.min(limit, EconomyConfig.HISTORY_LIMIT_PER_PLAYER.get()))
                .map(row -> new SubjectLedgerRow(row.type(), row.amount(), ref.equals(row.destinationAccount()),
                        row.currency(), row.timestamp())).toList();
    }

    /** Every row inside the retention window that names this pseudonym, newest first, with no row limit. */
    private List<TransactionRecord> retained(String ref) {
        // Reading history is not a write: expired rows are filtered out, and the sweep deletes them.
        int days = EconomyConfig.TRANSACTION_RETENTION_DAYS.get();
        long cutoff = days <= 0 ? Long.MAX_VALUE : now() - days * MILLIS_PER_DAY;
        return transactions.stream().filter(row -> row.timestamp() >= cutoff)
                .filter(row -> ref.equals(row.sourceAccount()) || ref.equals(row.destinationAccount()))
                .sorted(Comparator.comparingLong(TransactionRecord::timestamp).reversed()).toList();
    }

    public synchronized boolean loggingOptOut(UUID player) {
        return player != null && loggingOptOut.contains(new PlayerAccountId(player));
    }

    public synchronized void setLoggingOptOut(UUID player, boolean optOut) {
        if (player == null) return;
        AccountId account = new PlayerAccountId(player);
        boolean changed = optOut ? loggingOptOut.add(account) : loggingOptOut.remove(account);
        if (changed) setDirty();
    }

    /** Whether {@code player} has already been given the Trader's Handbook on joining. */
    public synchronized boolean handbookGiven(UUID player) {
        return player != null && handbookGiven.contains(new PlayerAccountId(player));
    }

    /** How many accounts have been given the Trader's Handbook; the gift stops at its bound. */
    public synchronized int handbookGivenCount() { return handbookGiven.size(); }

    /** Records that {@code player} was given the Trader's Handbook. @return false when it already was */
    public synchronized boolean markHandbookGiven(UUID player) {
        if (player == null || !handbookGiven.add(new PlayerAccountId(player))) return false;
        setDirty();
        return true;
    }

    /**
     * The data subject's whole ledger record. Unlike {@link #history} it is not cut at
     * {@code historyLimitPerPlayer}: every row still inside the retention window is included. The
     * other party of a two-sided row is replaced by {@link #COUNTERPARTY} unless it is a system
     * account, so an export only ever carries the subject's own pseudonym.
     */
    public synchronized PlayerLedgerExport export(UUID player) {
        if (player == null) throw new IllegalArgumentException("player");
        AccountId account = new PlayerAccountId(player);
        long balance = getBalance(account, NeroEconomyCommon.CREDITS);
        String ref = reference(account);
        List<TransactionRecord> rows = retained(ref).stream().map(row -> new TransactionRecord(row.type(),
                row.amount(), row.currency(), row.timestamp(), redact(row.sourceAccount(), ref),
                redact(row.destinationAccount(), ref), row.origin())).toList();
        ChipCount chips = chipRedemptions.get(account);
        return new PlayerLedgerExport(player, balance, CREDITS_ID, loggingOptOut.contains(account), rows, ref,
                chips == null ? 0L : chips.day(), chips == null ? 0 : chips.count(), lockedOf(account),
                !unvested.contains(account), handbookGiven.contains(account));
    }

    /** Empty stays empty (a mint or burn side); the subject's own pseudonym and system accounts are kept as-is. */
    private static String redact(String reference, String own) {
        return reference.isEmpty() || reference.equals(own) || reference.startsWith(SystemAccountId.KIND + ':')
                ? reference : COUNTERPARTY;
    }

    // --- Credit Chips ------------------------------------------------------------------------

    /**
     * Reserves one Credit Chip redemption for the current economy day.
     *
     * @return false when redemption is disabled ({@code dailyLimit <= 0}) or the account is at its limit
     */
    public synchronized boolean reserveChipRedemption(UUID player, int dailyLimit) {
        return reserveChipRedemption(player, dailyLimit, now());
    }

    synchronized boolean reserveChipRedemption(UUID player, int dailyLimit, long now) {
        if (player == null || dailyLimit <= 0) return false;
        AccountId account = new PlayerAccountId(player);
        long day = economyDay(now);
        int used = chipsRedeemed(account, day);
        if (used >= dailyLimit) return false;
        chipRedemptions.put(account, new ChipCount(day, used + 1));
        setDirty();
        return true;
    }

    /** How many chips the account has redeemed in the current economy day; sets what the next one is worth. */
    public synchronized int chipRedemptionsToday(UUID player) {
        return player == null ? 0 : chipsRedeemed(new PlayerAccountId(player), today());
    }

    private int chipsRedeemed(AccountId account, long day) {
        ChipCount current = chipRedemptions.get(account);
        return current == null || current.day() != day ? 0 : current.count();
    }

    /** Hands back a reservation whose payment was refused, so a refused chip costs nothing. */
    public synchronized void releaseChipRedemption(UUID player) {
        if (player == null) return;
        AccountId account = new PlayerAccountId(player);
        ChipCount current = chipRedemptions.get(account);
        if (current == null) return;
        if (current.count() <= 1) chipRedemptions.remove(account);
        else chipRedemptions.put(account, new ChipCount(current.day(), current.count() - 1));
        setDirty();
    }

    // --- erasure and retention ---------------------------------------------------------------

    /**
     * Erases one player. Whatever the account still held moves to the Reserve as
     * {@code ERASURE_SWEEP}, in a row that names no player, so the supply stays balanced. Erasure
     * also leaves a salted-pseudonym tombstone: without it the very next balance read would re-open
     * the account and pay the starting balance again, quietly undoing the erasure.
     */
    @Override public synchronized void forgetPlayer(UUID player) {
        if (player == null) return;
        AccountId account = new PlayerAccountId(player);
        String ref = reference(account);
        Long held = balances.remove(account);
        boolean changed = held != null;
        changed |= initialized.remove(account);
        changed |= loggingOptOut.remove(account);
        changed |= handbookGiven.remove(account);
        changed |= chipRedemptions.remove(account) != null;
        changed |= locked.remove(account) != null;
        changed |= unvested.remove(account);
        changed |= transactions.removeIf(row -> ref.equals(row.sourceAccount())
                || ref.equals(row.destinationAccount()));
        if (held != null && held > 0L) {
            balances.merge(SystemAccountId.RESERVE, held, EconomySavedData::saturatingAdd);
            append(TransactionType.ERASURE_SWEEP, held, null, SystemAccountId.RESERVE, now());
        }
        changed |= erased.add(ref);
        if (changed) setDirty();
    }

    /**
     * Erasure for the other sections: escrow that belonged to an erased player — a buy offer's
     * credits, a job payout, a standing bid, an uncollected claim — moves to the Reserve. The row
     * names two system accounts and nobody else. Works while frozen, because erasure always does.
     *
     * @return what was moved; less than {@code amount} only if the escrow account did not hold it
     */
    public synchronized long sweepErasedEscrow(long amount) {
        long moved = Math.min(Math.max(0L, amount), balanceOf(SystemAccountId.ESCROW));
        if (moved > 0L) {
            moveUnchecked(SystemAccountId.ESCROW, SystemAccountId.RESERVE, moved, TransactionType.ERASURE_SWEEP);
            setDirty();
        }
        return moved;
    }

    /**
     * Erasure for the exchange section: the principal of an erased player's shares and bonds, and
     * the interest reserved for those bonds, move from the exchange account to the Reserve. The
     * row names two system accounts and nobody else. Works while frozen, because erasure always does.
     *
     * @return what was moved; less than {@code amount} only if the exchange account did not hold it
     */
    public synchronized long sweepErasedExchange(long amount) {
        long moved = Math.min(Math.max(0L, amount), balanceOf(SystemAccountId.EXCHANGE));
        if (moved > 0L) {
            moveUnchecked(SystemAccountId.EXCHANGE, SystemAccountId.RESERVE, moved, TransactionType.ERASURE_SWEEP);
            setDirty();
        }
        return moved;
    }

    /** Load-time and daily purge. */
    public synchronized boolean purgeExpired(long now) {
        int before = transactions.size();
        int days = EconomyConfig.TRANSACTION_RETENTION_DAYS.get();
        long cutoff = days <= 0 ? Long.MAX_VALUE : now - days * MILLIS_PER_DAY;
        transactions.removeIf(row -> row.timestamp() < cutoff);
        int max = EconomyConfig.MAX_TRANSACTION_RECORDS.get();
        // Past the bound the oldest ordinary rows go first; minting, burning and large rows outlive them.
        AuditRetention.trim(transactions, max, row -> AuditRetention.kept(row.type(), row.amount()));
        // A chip counter only matters on its own day; keeping older rows would be storage for nothing.
        long today = economyDay(now);
        boolean changed = chipRedemptions.values().removeIf(row -> row.day() < today);
        changed |= before != transactions.size();
        if (changed) setDirty();
        return changed;
    }

    /** Nothing about any account, and a clock that was never corrected: nothing worth carrying over. */
    @Override public synchronized boolean isEmpty() {
        return balances.isEmpty() && initialized.isEmpty() && loggingOptOut.isEmpty() && handbookGiven.isEmpty()
                && erased.isEmpty()
                && transactions.isEmpty() && chipRedemptions.isEmpty() && clock.offsetMillis() == 0L;
    }

    // --- internals ---------------------------------------------------------------------------

    /**
     * Opens a player account on its first write. The starting balance is a transfer out of the
     * Reserve: the account gets what the Reserve can pay, possibly nothing, and is opened either
     * way. An erased account re-opens empty. Under vesting the grant starts out locked.
     */
    private void open(AccountId account) {
        if (!(account instanceof PlayerAccountId) || !initialized.add(account)) return;
        boolean fresh = balances.putIfAbsent(account, 0L) == null;
        long wanted = !fresh || tombstoned(account) ? 0L : EconomyConfig.STARTING_BALANCE.get();
        long grant = Math.min(wanted, balanceOf(SystemAccountId.RESERVE));
        if (grant > 0L) {
            moveUnchecked(SystemAccountId.RESERVE, account, grant, TransactionType.STARTING_BALANCE);
            if (vestingEnabled()) {
                locked.put(account, grant);
                unvested.add(account);
            }
        }
        setDirty();
    }

    /** A transfer every check has already passed for. Never call it with an amount the source lacks. */
    private void moveUnchecked(AccountId from, AccountId to, long amount, TransactionType type) {
        if (amount <= 0L || from.equals(to)) return;
        balances.put(from, balanceOf(from) - amount);
        balances.put(to, balanceOf(to) + amount);
        clampLock(from);
        append(type, amount, from, to, now());
        setDirty();
    }

    private long balanceOf(AccountId account) { return balances.getOrDefault(account, 0L); }

    /** The locked part of a balance: never more than the balance itself. */
    private long lockedOf(AccountId account) {
        Long held = locked.get(account);
        return held == null ? 0L : Math.min(held, balanceOf(account));
    }

    private void clampLock(AccountId account) {
        Long held = locked.get(account);
        if (held == null) return;
        long capped = Math.min(held, balanceOf(account));
        if (capped <= 0L) locked.remove(account);
        else if (capped != held) locked.put(account, capped);
    }

    private boolean tombstoned(AccountId account) {
        return !erased.isEmpty() && erased.contains(reference(account));
    }

    private static boolean creditsOnly(Currency currency) {
        return currency != null && NeroEconomyCommon.CREDITS.equals(currency);
    }

    /**
     * The one form of an account the ledger keys on, whatever class named it: a player by id, or a
     * system account this build knows. Anything else is not an account here.
     */
    private static AccountId canonical(AccountId account) {
        if (account == null) return null;
        if (account instanceof PlayerAccountId || account instanceof SystemAccountId
                || account instanceof za.co.neroland.neroeconomy.api.ForeignAccountId) return account;
        // Another implementation is read as a player or system account only: another mod's account
        // must be a ForeignAccountId, which only its claimed caller moves (NeroFactions' own
        // FactionAccountId is refused here, as before).
        AccountId parsed = parseAccount(account.kind(), account.value());
        return parsed instanceof za.co.neroland.neroeconomy.api.ForeignAccountId ? null : parsed;
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    /**
     * A two-party row survives one party opting out: only the opted-out side's pseudonym is blanked,
     * so the other party keeps a complete history. Retention is swept on load and daily; an append
     * only pays for a purge once the list has run past its bound.
     */
    private void append(TransactionType type, long amount, AccountId source, AccountId target, long now) {
        int max = EconomyConfig.MAX_TRANSACTION_RECORDS.get();
        if (amount == 0L || max == 0 || EconomyConfig.TRANSACTION_RETENTION_DAYS.get() == 0) return;
        transactions.add(new TransactionRecord(type, amount, CREDITS_ID, now, pseudonym(source), pseudonym(target),
                origin));
        if (transactions.size() > max + PURGE_SLACK) purgeExpired(now);
    }

    /** A system account by its stable name; a player by salted pseudonym, or blank when opted out. */
    private String pseudonym(AccountId account) {
        if (account == null) return "";
        // Another mod's account is not personal either: it is written as it is.
        if (account instanceof SystemAccountId || account instanceof za.co.neroland.neroeconomy.api.ForeignAccountId)
            return account.stableKey();
        return loggingOptOut.contains(account) ? "" : reference(account);
    }

    private String reference(AccountId account) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((pseudonymSalt + ':' + account.stableKey()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Test seam: replaces the wall clock for every ledger; null restores the real one. */
    static void wallClockForTest(LongSupplier clock) { wallClock = clock == null ? System::currentTimeMillis : clock; }
    void importRecord(TransactionRecord record) { transactions.add(record); }
    void pruneForTest(long now) { purgeExpired(now); }
    int transactionCount() { return transactions.size(); }
    boolean tombstoned(UUID player) { return tombstoned(new PlayerAccountId(player)); }
    EconomyClock clockForTest() { return clock; }
    int chipRedemptionsForTest(UUID player) {
        ChipCount row = chipRedemptions.get(new PlayerAccountId(player));
        return row == null ? 0 : row.count();
    }
    Supply supplyForTest() { return supply; }
    Reserve reserveForTest() { return reserve; }
    List<TransactionRecord> rowsForTest() { return List.copyOf(transactions); }
    /** Every balance together, for the invariant tests; saturates rather than wrapping. */
    long totalBalancesForTest() {
        long total = 0L;
        for (long balance : balances.values()) total = saturatingAdd(total, balance);
        return total;
    }

    // --- persistence -------------------------------------------------------------------------

    private record BalanceRow(String accountKind, String account, String currency, long balance) {
        static final Codec<BalanceRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("account_kind", "player").forGetter(BalanceRow::accountKind),
                Codec.STRING.fieldOf("player").forGetter(BalanceRow::account),
                Codec.STRING.optionalFieldOf("currency", CREDITS_ID).forGetter(BalanceRow::currency),
                Codec.LONG.fieldOf("balance").forGetter(BalanceRow::balance)).apply(instance, BalanceRow::new));
    }

    private record AuditRow(String type, long amount, String currency, long timestamp, String source,
            String destination, String origin) {
        static final Codec<AuditRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("type").forGetter(AuditRow::type),
                Codec.LONG.fieldOf("amount").forGetter(AuditRow::amount),
                Codec.STRING.fieldOf("currency").forGetter(AuditRow::currency),
                Codec.LONG.fieldOf("timestamp").forGetter(AuditRow::timestamp),
                Codec.STRING.optionalFieldOf("source", "").forGetter(AuditRow::source),
                Codec.STRING.optionalFieldOf("destination", "").forGetter(AuditRow::destination),
                // Added with the cross-mod API: absent on every older row, which no other mod made.
                Codec.STRING.optionalFieldOf("origin", "").forGetter(AuditRow::origin))
                .apply(instance, AuditRow::new));
    }

    private record ChipRow(String account, long day, int count) {
        static final Codec<ChipRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("account").forGetter(ChipRow::account),
                Codec.LONG.fieldOf("day").forGetter(ChipRow::day),
                Codec.INT.fieldOf("count").forGetter(ChipRow::count)).apply(instance, ChipRow::new));
    }

    /** One account's locked starting credits. */
    private record LockRow(String account, long amount) {
        static final Codec<LockRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("account").forGetter(LockRow::account),
                Codec.LONG.fieldOf("amount").forGetter(LockRow::amount)).apply(instance, LockRow::new));
    }

    /** One named counter: a mint or burn reason, or a Reserve budget, and its amount. */
    private record CountRow(String name, long amount) {
        static final Codec<CountRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("name").forGetter(CountRow::name),
                Codec.LONG.fieldOf("amount").forGetter(CountRow::amount)).apply(instance, CountRow::new));
    }

    private record SupplyRows(List<CountRow> minted, List<CountRow> burned, long day, List<CountRow> mintedToday,
            List<CountRow> burnedToday) {
        static final Codec<SupplyRows> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                CountRow.CODEC.listOf().optionalFieldOf("minted", List.of()).forGetter(SupplyRows::minted),
                CountRow.CODEC.listOf().optionalFieldOf("burned", List.of()).forGetter(SupplyRows::burned),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(SupplyRows::day),
                CountRow.CODEC.listOf().optionalFieldOf("minted_today", List.of()).forGetter(SupplyRows::mintedToday),
                CountRow.CODEC.listOf().optionalFieldOf("burned_today", List.of()).forGetter(SupplyRows::burnedToday))
                .apply(instance, SupplyRows::new));
    }

    private record ReserveRows(boolean seeded, long lastMintDay, long budgetDay, List<CountRow> budgetUsed) {
        static final Codec<ReserveRows> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("seeded", false).forGetter(ReserveRows::seeded),
                Codec.LONG.optionalFieldOf("last_mint_day", 0L).forGetter(ReserveRows::lastMintDay),
                Codec.LONG.optionalFieldOf("budget_day", 0L).forGetter(ReserveRows::budgetDay),
                CountRow.CODEC.listOf().optionalFieldOf("budget_used", List.of()).forGetter(ReserveRows::budgetUsed))
                .apply(instance, ReserveRows::new));
    }

    // Every field added after the first release is optional with a safe default, so old saves load.
    private static Codec<EconomySavedData> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                BalanceRow.CODEC.listOf().optionalFieldOf("balances", List.of()).forGetter(EconomySavedData::balanceRows),
                Codec.STRING.listOf().optionalFieldOf("initialized", List.of()).forGetter(EconomySavedData::initializedRows),
                Codec.STRING.listOf().optionalFieldOf("logging_opt_out", List.of()).forGetter(EconomySavedData::optOutRows),
                AuditRow.CODEC.listOf().optionalFieldOf("transactions", List.of()).forGetter(EconomySavedData::auditRows),
                Codec.STRING.optionalFieldOf("pseudonym_salt", "").forGetter(data -> data.pseudonymSalt),
                Codec.STRING.listOf().optionalFieldOf("erased", List.of()).forGetter(EconomySavedData::erasedRows),
                Codec.LONG.optionalFieldOf("clock_offset", 0L).forGetter(data -> data.clock.offsetMillis()),
                Codec.LONG.optionalFieldOf("clock_last_wall", 0L).forGetter(data -> data.savedWallMillis),
                ChipRow.CODEC.listOf().optionalFieldOf("chip_redemptions", List.of())
                        .forGetter(EconomySavedData::chipRows),
                LockRow.CODEC.listOf().optionalFieldOf("locked", List.of()).forGetter(EconomySavedData::lockRows),
                Codec.STRING.listOf().optionalFieldOf("unvested", List.of()).forGetter(EconomySavedData::unvestedRows),
                // Absent in a save from before supply was counted: that ledger is adopted on migration.
                SupplyRows.CODEC.optionalFieldOf("supply").forGetter(EconomySavedData::supplyRows),
                ReserveRows.CODEC.optionalFieldOf("reserve").forGetter(EconomySavedData::reserveRows),
                // Added with the Trader's Handbook: absent in an older save, which gave no handbook.
                Codec.STRING.listOf().optionalFieldOf("handbook_given", List.of())
                        .forGetter(EconomySavedData::handbookRows))
                .apply(instance, EconomySavedData::fromData));
    }

    private List<BalanceRow> balanceRows() {
        return balances.entrySet().stream().map(entry -> new BalanceRow(entry.getKey().kind(),
                entry.getKey().value(), CREDITS_ID, entry.getValue())).toList();
    }
    private List<String> initializedRows() { return initialized.stream().map(AccountId::stableKey).toList(); }
    private List<String> optOutRows() { return loggingOptOut.stream().map(AccountId::stableKey).toList(); }
    private List<String> handbookRows() { return handbookGiven.stream().map(AccountId::stableKey).toList(); }
    private List<String> erasedRows() { return List.copyOf(erased); }
    private List<String> unvestedRows() { return unvested.stream().map(AccountId::stableKey).toList(); }
    private List<ChipRow> chipRows() {
        return chipRedemptions.entrySet().stream().map(entry -> new ChipRow(entry.getKey().stableKey(),
                entry.getValue().day(), entry.getValue().count())).toList();
    }
    private List<LockRow> lockRows() {
        return locked.entrySet().stream().map(entry -> new LockRow(entry.getKey().stableKey(), entry.getValue())).toList();
    }
    private List<AuditRow> auditRows() {
        return transactions.stream().map(row -> new AuditRow(row.type().name(), row.amount(), row.currency(),
                row.timestamp(), row.sourceAccount(), row.destinationAccount(), row.origin())).toList();
    }
    private Optional<SupplyRows> supplyRows() {
        if (!supplyTracked) return Optional.empty();
        return Optional.of(new SupplyRows(countRows(supply.mintedByReason()), countRows(supply.burnedByReason()),
                supply.day(), countRows(supply.mintedTodayByReason()), countRows(supply.burnedTodayByReason())));
    }
    private Optional<ReserveRows> reserveRows() {
        return Optional.of(new ReserveRows(reserve.seeded(), reserve.lastMintDay(), reserve.budgetDay(),
                countRows(reserve.usedByKind())));
    }
    private static <E extends Enum<E>> List<CountRow> countRows(Map<E, Long> values) {
        return values.entrySet().stream().filter(entry -> entry.getValue() > 0L)
                .map(entry -> new CountRow(entry.getKey().name(), entry.getValue())).toList();
    }

    private static EconomySavedData fromData(List<BalanceRow> balances, List<String> initialized,
            List<String> optOut, List<AuditRow> transactions, String salt, List<String> erased,
            long clockOffset, long clockLastWall, List<ChipRow> chips, List<LockRow> locks, List<String> unvested,
            Optional<SupplyRows> supplyRows, Optional<ReserveRows> reserveRows, List<String> handbook) {
        EconomySavedData data = new EconomySavedData();
        if (!salt.isBlank()) data.pseudonymSalt = salt;
        // An old save has neither clock field: offset 0, and the first observation becomes "last".
        data.clock = new EconomyClock(clockOffset, clockLastWall);
        data.savedWallMillis = Math.max(0L, clockLastWall);
        for (ChipRow row : chips) {
            AccountId account = parseStableAccount(row.account());
            if (account instanceof PlayerAccountId && row.count() > 0)
                data.chipRedemptions.put(account, new ChipCount(row.day(), row.count()));
        }
        // Tombstones are stored already-hashed under this salt, so they are read back verbatim.
        erased.stream().filter(value -> !value.isBlank()).forEach(data.erased::add);
        for (BalanceRow row : balances) {
            AccountId account = parseAccount(row.accountKind(), row.account());
            if (account != null && row.balance() >= 0L && row.currency().equals(CREDITS_ID))
                data.balances.put(account, row.balance());
        }
        initialized.stream().map(EconomySavedData::parseStableAccount).filter(PlayerAccountId.class::isInstance)
                .forEach(data.initialized::add);
        optOut.stream().map(EconomySavedData::parseStableAccount).filter(PlayerAccountId.class::isInstance)
                .forEach(data.loggingOptOut::add);
        handbook.stream().map(EconomySavedData::parseStableAccount).filter(PlayerAccountId.class::isInstance)
                .forEach(data.handbookGiven::add);
        for (LockRow row : locks) {
            AccountId account = parseStableAccount(row.account());
            if (account instanceof PlayerAccountId && row.amount() > 0L) data.locked.put(account, row.amount());
        }
        unvested.stream().map(EconomySavedData::parseStableAccount).filter(PlayerAccountId.class::isInstance)
                .forEach(data.unvested::add);
        for (AuditRow row : transactions) try {
            TransactionType type = TransactionType.valueOf(row.type());
            if (row.amount() >= 0L) data.transactions.add(new TransactionRecord(type, row.amount(), row.currency(),
                    row.timestamp(), row.source(), row.destination(),
                    za.co.neroland.neroeconomy.sibling.SiblingRules.validOrigin(row.origin()) ? row.origin() : ""));
        } catch (IllegalArgumentException ignored) { }
        data.supplyTracked = supplyRows.isPresent();
        supplyRows.ifPresent(rows -> {
            rows.minted().forEach(row -> data.supply.restore(true, false, reason(row.name()), row.amount()));
            rows.burned().forEach(row -> data.supply.restore(false, false, reason(row.name()), row.amount()));
            rows.mintedToday().forEach(row -> data.supply.restore(true, true, reason(row.name()), row.amount()));
            rows.burnedToday().forEach(row -> data.supply.restore(false, true, reason(row.name()), row.amount()));
            data.supply.restoreDay(rows.day());
        });
        reserveRows.ifPresent(rows -> {
            EnumMap<BudgetKind, Long> used = new EnumMap<>(BudgetKind.class);
            for (CountRow row : rows.budgetUsed()) try {
                used.put(BudgetKind.valueOf(row.name()), row.amount());
            } catch (IllegalArgumentException ignored) { }
            data.reserve.restore(rows.seeded(), rows.lastMintDay(), rows.budgetDay(), used);
        });
        // Observing here is what folds the time spent shut down into the clock, capped at its limit.
        data.purgeExpired(data.now());
        return data;
    }

    private static TransactionType reason(String name) {
        try { return TransactionType.valueOf(name); }
        catch (IllegalArgumentException unknown) { return null; }
    }

    private static AccountId parseStableAccount(String value) {
        int separator = value.indexOf(':');
        return separator < 1 ? parseAccount("player", value)
                : parseAccount(value.substring(0, separator), value.substring(separator + 1));
    }

    private static AccountId parseAccount(String kind, String value) {
        if (kind == null || value == null) return null;
        if (SystemAccountId.KIND.equals(kind)) return SystemAccountId.parse(value);
        // Another mod's account is kept whether or not that mod is loaded now, so its credits are never lost.
        if (!"player".equals(kind)) return za.co.neroland.neroeconomy.api.ForeignAccountId.parse(kind, value);
        try { return new PlayerAccountId(UUID.fromString(value)); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
