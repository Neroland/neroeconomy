package za.co.neroland.neroeconomy.sibling;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.EconomyResult;
import za.co.neroland.neroeconomy.api.ForeignAccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.BudgetKind;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.LedgerPlan;
import za.co.neroland.neroeconomy.economy.LedgerResult;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.network.RateLimiter;

/**
 * The engine behind {@code api.EconomyCaller}: every check, in the order the API promises, then one
 * ledger call. Works on an {@link EconomyStore}, so a unit test drives it with a store whose ledger
 * is bound to the currency provider exactly as a running server binds it.
 *
 * <p>Guards, in order: a malformed argument ({@code INVALID}); a reason the caller did not claim
 * ({@code UNKNOWN_REASON}); no store ({@code UNAVAILABLE}); the freeze ({@code FROZEN}); a request
 * key already applied ({@code DUPLICATE}); the per-caller rate limit ({@code RATE_LIMITED}); then
 * the Reserve and budget checks, all before anything moves.
 *
 * <p>Only an applied request's key is remembered: a refused one may be retried with the same key.
 * Nothing about a payment is logged at info; the debug line names the caller, the reason and the
 * result, never the player or the amount.
 */
public final class SiblingPayments {
    private static RateLimiter limiter = new RateLimiter(System::nanoTime);
    private static volatile String overridesText;
    private static volatile Map<String, Long> overrides = Map.of();

    private SiblingPayments() { }

    /** Server stop: the per-caller rate buckets go. */
    public static void clear() { limiter.clear(); }

    /** Test seam: a limiter on a clock the test controls; null restores the real one. */
    static void limiterForTest(RateLimiter replacement) {
        limiter = replacement == null ? new RateLimiter(System::nanoTime) : replacement;
    }

    // --- the calls ---------------------------------------------------------------------------

    /** Reserve to player, within the caller's, the shared and the per-player daily limits. */
    public static EconomyResult pay(EconomyStore store, String modId, Set<String> reasons, UUID player, long amount,
            String reason, String requestKey) {
        if (player == null || amount < 1L || amount > EconomyConfig.SIBLING_MAX_SINGLE_PAYOUT.get())
            return EconomyResult.INVALID;
        EconomyResult refused = guard(store, modId, reasons, reason, requestKey);
        if (refused != null) return finish(modId, reason, "pay", refused);
        EconomySavedData ledger = store.ledger();
        long now = ledger.now();
        long today = EconomySavedData.economyDay(now);
        EconomyResult verdict = budget(store, modId, player, amount, today);
        if (verdict != EconomyResult.APPLIED) return finish(modId, reason, "pay", verdict);
        if (requestKey != null && !store.sibling().hasKeyRoom(now))
            return finish(modId, reason, "pay", EconomyResult.RATE_LIMITED);
        EconomyResult result = map(NeroEconomyCommon.CURRENCY_PROVIDER.payFromReserveOn(ledger, BudgetKind.SIBLING,
                player, amount, TransactionType.SIBLING_PAYOUT, SiblingRules.origin(modId, reason)));
        if (result == EconomyResult.APPLIED) {
            store.sibling().count(modId, playerCapOn() ? player : null, amount, today);
            if (requestKey != null) store.sibling().remember(modId, requestKey, now);
            EconomyEventBus.paid(player, modId, reason, amount, now);
        }
        return finish(modId, reason, "pay", result);
    }

    /** Player to Reserve: a sink. May use a starting balance that has not vested, like any system fee. */
    public static EconomyResult charge(EconomyStore store, String modId, Set<String> reasons, UUID player, long amount,
            String reason, String requestKey) {
        if (player == null || amount < 1L) return EconomyResult.INVALID;
        EconomyResult refused = guard(store, modId, reasons, reason, requestKey);
        if (refused != null) return finish(modId, reason, "charge", refused);
        long now = store.ledger().now();
        if (requestKey != null && !store.sibling().hasKeyRoom(now))
            return finish(modId, reason, "charge", EconomyResult.RATE_LIMITED);
        LedgerPlan plan = LedgerPlan.builder().moveAllowingLocked(new PlayerAccountId(player), SystemAccountId.RESERVE,
                amount, TransactionType.SIBLING_CHARGE).build();
        EconomyResult result = map(NeroEconomyCommon.CURRENCY_PROVIDER.executeOn(store.ledger(), plan,
                SiblingRules.origin(modId, reason)));
        if (result == EconomyResult.APPLIED && requestKey != null) store.sibling().remember(modId, requestKey, now);
        return finish(modId, reason, "charge", result);
    }

    /**
     * Payer to payee, the fee from the payee's side to the Reserve, as one plan. Never funded by
     * the Reserve; a starting balance that has not vested cannot be used.
     */
    public static EconomyResult transfer(EconomyStore store, String modId, Set<String> reasons, UUID payer,
            UUID payee, long amount, String reason, String requestKey) {
        if (payer == null || payee == null || payer.equals(payee) || amount < 1L) return EconomyResult.INVALID;
        long fee = feeFor(amount);
        if (fee >= amount) return EconomyResult.INVALID;
        EconomyResult refused = guard(store, modId, reasons, reason, requestKey);
        if (refused != null) return finish(modId, reason, "transfer", refused);
        long now = store.ledger().now();
        if (requestKey != null && !store.sibling().hasKeyRoom(now))
            return finish(modId, reason, "transfer", EconomyResult.RATE_LIMITED);
        PlayerAccountId from = new PlayerAccountId(payer);
        LedgerPlan plan = LedgerPlan.builder()
                .movePlayerFacing(from, new PlayerAccountId(payee), amount - fee, TransactionType.PLAYER_PAYMENT)
                .movePlayerFacing(from, SystemAccountId.RESERVE, fee, TransactionType.SIBLING_FEE).build();
        EconomyResult result = map(NeroEconomyCommon.CURRENCY_PROVIDER.executeOn(store.ledger(), plan,
                SiblingRules.origin(modId, reason)));
        if (result == EconomyResult.APPLIED && requestKey != null) store.sibling().remember(modId, requestKey, now);
        return finish(modId, reason, "transfer", result);
    }

    /** Player to one of the caller's own accounts ({@code kind} = its mod id). Unvested credits cannot be used. */
    public static EconomyResult deposit(EconomyStore store, String modId, Set<String> reasons, UUID player,
            AccountId to, long amount, String reason, String requestKey) {
        if (player == null || !ownAccount(modId, to) || amount < 1L) return EconomyResult.INVALID;
        return foreignMove(store, modId, reasons, LedgerPlan.builder().movePlayerFacing(new PlayerAccountId(player), to,
                amount, TransactionType.FOREIGN_DEPOSIT).build(), reason, requestKey, "deposit");
    }

    /** One of the caller's own accounts to a player. */
    public static EconomyResult withdraw(EconomyStore store, String modId, Set<String> reasons, AccountId from,
            UUID player, long amount, String reason, String requestKey) {
        if (player == null || !ownAccount(modId, from) || amount < 1L) return EconomyResult.INVALID;
        return foreignMove(store, modId, reasons, LedgerPlan.builder().move(from, new PlayerAccountId(player), amount,
                TransactionType.FOREIGN_WITHDRAWAL).build(), reason, requestKey, "withdraw");
    }

    /** What one of the caller's own accounts holds; 0 for an account of another kind or with no store. */
    public static long balance(EconomyStore store, String modId, AccountId account) {
        if (store == null || !ownAccount(modId, account)) return 0L;
        return store.ledger().getBalance(account, NeroEconomyCommon.CREDITS);
    }

    private static boolean ownAccount(String modId, AccountId account) {
        return account instanceof ForeignAccountId foreign && foreign.kind().equals(modId);
    }

    private static EconomyResult foreignMove(EconomyStore store, String modId, Set<String> reasons, LedgerPlan plan,
            String reason, String requestKey, String call) {
        EconomyResult refused = guard(store, modId, reasons, reason, requestKey);
        if (refused != null) return finish(modId, reason, call, refused);
        long now = store.ledger().now();
        if (requestKey != null && !store.sibling().hasKeyRoom(now))
            return finish(modId, reason, call, EconomyResult.RATE_LIMITED);
        EconomyResult result = map(NeroEconomyCommon.CURRENCY_PROVIDER.executeOn(store.ledger(), plan,
                SiblingRules.origin(modId, reason)));
        if (result == EconomyResult.APPLIED && requestKey != null) store.sibling().remember(modId, requestKey, now);
        return finish(modId, reason, call, result);
    }

    /** The refuse-before-move decision for {@link #pay}: nothing moves, nothing is counted, no rate token is spent. */
    public static EconomyResult canPay(EconomyStore store, String modId, UUID player, long amount) {
        if (player == null || amount < 1L || amount > EconomyConfig.SIBLING_MAX_SINGLE_PAYOUT.get())
            return EconomyResult.INVALID;
        if (store == null) return EconomyResult.UNAVAILABLE;
        if (EconomyHealth.frozen()) return EconomyResult.FROZEN;
        return budget(store, modId, player, amount, EconomySavedData.economyDay(store.ledger().now()));
    }

    /** What the caller may still pay today: its own remaining limit, the shared one and the Reserve, whichever is least. */
    public static long remainingToday(EconomyStore store, String modId) {
        if (store == null || EconomyHealth.frozen()) return 0L;
        EconomySavedData ledger = store.ledger();
        long today = EconomySavedData.economyDay(ledger.now());
        long own = callerRemaining(store, modId, today);
        return Math.max(0L, Math.min(own, Math.min(ledger.budgetRemaining(BudgetKind.SIBLING),
                ledger.balance(SystemAccountId.RESERVE))));
    }

    /** The fee a transfer of {@code amount} takes; see {@link SiblingRules#fee}. */
    public static long feeFor(long amount) {
        return SiblingRules.fee(amount, EconomyConfig.SIBLING_TRANSFER_FEE_PERCENT.get());
    }

    /** The daily payout limit of {@code modId}: its override in {@code siblingCallerBudgets}, or the default. */
    public static long callerLimit(String modId) {
        String text = EconomyConfig.SIBLING_CALLER_BUDGETS.get();
        if (!java.util.Objects.equals(text, overridesText)) {
            overrides = SiblingRules.parseBudgets(text);
            overridesText = text;
        }
        return SiblingRules.callerLimit(modId, EconomyConfig.SIBLING_CALLER_DAILY_BUDGET.get(), overrides);
    }

    // --- checks ------------------------------------------------------------------------------

    /** The shared guards of every value-moving call; null when they all pass. Spends one rate token. */
    private static EconomyResult guard(EconomyStore store, String modId, Set<String> reasons, String reason,
            String requestKey) {
        if (!SiblingRules.validModId(modId) || reasons == null || !SiblingRules.validReason(reason)
                || (requestKey != null && !SiblingRules.validRequestKey(requestKey))) return EconomyResult.INVALID;
        if (!reasons.contains(reason)) return EconomyResult.UNKNOWN_REASON;
        if (store == null) return EconomyResult.UNAVAILABLE;
        if (EconomyHealth.frozen()) return EconomyResult.FROZEN;
        if (requestKey != null && store.sibling().seen(modId, requestKey, store.ledger().now()))
            return EconomyResult.DUPLICATE;
        if (!limiter.tryAcquire(callerKey(modId), RateLimiter.Budget.SIBLING)) return EconomyResult.RATE_LIMITED;
        return null;
    }

    /** The Reserve and budget checks of a payout, in the order the API promises. */
    private static EconomyResult budget(EconomyStore store, String modId, UUID player, long amount, long today) {
        EconomySavedData ledger = store.ledger();
        if (amount > ledger.balance(SystemAccountId.RESERVE)) return EconomyResult.RESERVE_SHORT;
        if (amount > callerRemaining(store, modId, today)) return EconomyResult.CALLER_BUDGET_SPENT;
        if (amount > ledger.budgetRemaining(BudgetKind.SIBLING)) return EconomyResult.SIBLING_BUDGET_SPENT;
        if (playerCapOn()) {
            long cap = EconomyConfig.SIBLING_PER_PLAYER_DAILY_CAP.get();
            long used = store.sibling().playerUsed(player, today);
            if (used >= cap || amount > cap - used) return EconomyResult.PLAYER_CAP_REACHED;
        }
        if (!store.sibling().hasRoom(modId, playerCapOn() ? player : null, today)) return EconomyResult.RATE_LIMITED;
        long balance = ledger.getBalance(new PlayerAccountId(player), NeroEconomyCommon.CREDITS);
        if (Long.MAX_VALUE - balance < amount) return EconomyResult.OVERFLOW;
        return EconomyResult.APPLIED;
    }

    private static long callerRemaining(EconomyStore store, String modId, long today) {
        return Math.max(0L, callerLimit(modId) - store.sibling().callerUsed(modId, today));
    }

    private static boolean playerCapOn() { return EconomyConfig.SIBLING_PER_PLAYER_DAILY_CAP.get() > 0L; }

    /** The rate-limit bucket of one caller: a fixed id derived from its mod id, never a player's. */
    static UUID callerKey(String modId) {
        return UUID.nameUUIDFromBytes(("neroeconomy-caller:" + modId).getBytes(StandardCharsets.UTF_8));
    }

    static EconomyResult map(LedgerResult result) {
        if (result == null) return EconomyResult.UNAVAILABLE;
        return switch (result) {
            case APPLIED -> EconomyResult.APPLIED;
            case INVALID -> EconomyResult.INVALID;
            case FROZEN -> EconomyResult.FROZEN;
            case UNAVAILABLE -> EconomyResult.UNAVAILABLE;
            case INSUFFICIENT_FUNDS -> EconomyResult.INSUFFICIENT_FUNDS;
            case FUNDS_LOCKED -> EconomyResult.FUNDS_LOCKED;
            case RESERVE_SHORT -> EconomyResult.RESERVE_SHORT;
            case BUDGET_SPENT -> EconomyResult.SIBLING_BUDGET_SPENT;
            case OVERFLOW -> EconomyResult.OVERFLOW;
        };
    }

    private static EconomyResult finish(String modId, String reason, String call, EconomyResult result) {
        NeroEconomyCommon.LOGGER.debug("[NeroEconomy] Sibling {} {}/{}: {}", call, modId, reason, result);
        return result;
    }
}
