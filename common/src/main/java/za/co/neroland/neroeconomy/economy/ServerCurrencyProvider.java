package za.co.neroland.neroeconomy.economy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import za.co.neroland.nerolandcore.economy.Currency;
import za.co.neroland.nerolandcore.economy.CurrencyProvider;
import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.data.LedgerExportFiles;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.shop.ShopOwnershipState;
import za.co.neroland.neroeconomy.simulation.Simulation;
import za.co.neroland.neroeconomy.telemetry.NeroEconomyTelemetry;

/**
 * Global Core provider whose delegate is explicitly rebound/cleared for each server lifecycle.
 *
 * <p>It is also the one way the rest of the mod moves credits that involve a player:
 * <ul>
 *   <li>{@link #execute(LedgerPlan)} — any set of transfers, applied as one (escrow, claims, fees,
 *       refunds, purchases);</li>
 *   <li>{@link #payFromReserve} — one payment out of the Reserve, within a daily budget;</li>
 *   <li>{@link #depositTyped} / {@link #withdrawTyped} — an operator's mint or burn;</li>
 *   <li>{@link #transferTyped} — a payment between two players.</li>
 * </ul>
 * Each of them goes through Core's {@code CurrencyApi}, so balance listeners still see every change
 * to a player's balance, and each reports what the ledger actually did even when a listener throws.
 */
public final class ServerCurrencyProvider implements CurrencyProvider {
    /** Market and buy-offer expiry cannot wait for the daily sweep, or escrow outlives a restart. */
    private static final int MARKET_SWEEP_TICKS = 600;
    /** A requested save is coalesced: the stores are flushed at most once in this many ticks. */
    private static final int SAVE_COOLDOWN_TICKS = 20;
    /** Play time is counted in ticks; this many make a minute. */
    private static final long TICKS_PER_MINUTE = 1_200L;

    /**
     * One typed ledger call in flight on this thread, and what the store did with it. Exactly one of
     * three things: a mint or burn ({@code type} alone), a payment out of the Reserve ({@code type}
     * with {@code budget}), or a plan.
     */
    private static final class TypedCall {
        private final TransactionType type;
        private final BudgetKind budget;
        private final LedgerPlan plan;
        private LedgerResult result;
        private TypedCall(TransactionType type, BudgetKind budget, LedgerPlan plan) {
            this.type = type;
            this.budget = budget;
            this.plan = plan;
        }
    }

    private volatile EconomySavedData active;
    private volatile MinecraftServer server;
    private final ThreadLocal<TypedCall> pending = new ThreadLocal<>();
    private final AtomicBoolean unboundReported = new AtomicBoolean();
    private final AtomicBoolean listenerFailureReported = new AtomicBoolean();
    private volatile boolean saveRequested;
    /** Optional listeners for the companion-app module; see {@link #setLifecycleHooks}. */
    private volatile Runnable tickHook;
    private volatile Runnable unbindHook;
    private int marketSweepCountdown;
    private int saveCooldown;

    public synchronized void bind(MinecraftServer nextServer) {
        if (nextServer == null) {
            throw new IllegalArgumentException("server");
        }
        server = nextServer;
        // Resolves the economy store: loads it, migrates the older stores into it, or freezes.
        active = EconomySavedData.get(nextServer);
        long now = active.now();
        if (!EconomyHealth.frozen()) {
            active.purgeExpired(now);
            active.topUpReserve();
            // A daily-restarted idle server would otherwise never reach the retention sweep at all.
            sweepMarket(nextServer, now);
        }
        // Export files are aged by their file-system timestamp, so that sweep runs on the wall clock.
        LedgerExportFiles.purgeExpired(nextServer, System.currentTimeMillis());
        marketSweepCountdown = MARKET_SWEEP_TICKS;
        saveCooldown = 0;
        saveRequested = false;
        unboundReported.set(false);
        listenerFailureReported.set(false);
    }

    public void tick(MinecraftServer currentServer) {
        if (server != currentServer) return;
        if (EconomyHealth.frozen()) {
            // Nothing moves and nothing is swept while frozen; the app module still gets its tick.
            runHook(tickHook);
            return;
        }
        long now = now();
        // Deadlines are honoured within seconds, so expiry keeps its own short cadence here; the
        // simulation's housekeeping step runs it as well, which settles what fell due while the
        // server was paused or shut down.
        za.co.neroland.neroeconomy.obligation.ObligationSavedData.get(currentServer).tick(currentServer, now);
        if (--marketSweepCountdown <= 0) {
            marketSweepCountdown = MARKET_SWEEP_TICKS;
            sweepMarket(currentServer, now);
            // Vesting needs the players who are online, so it stays on this cadence too.
            sweepVesting(currentServer);
            // Prototypes nothing refers to are dropped before the backup below is refreshed.
            EconomyStore.get(currentServer).goods().sweepIfDue(System.currentTimeMillis());
            refreshBackups(currentServer);
        }
        // The Reserve top-up, the day roll-over and everything later stages add run as simulation
        // steps: counted in economy time, a few per tick, never tied to game ticks or the day cycle.
        Simulation.tick(currentServer, now);
        // Auto-trade rules waiting for their turn are looked at, a bounded number per tick.
        za.co.neroland.neroeconomy.trade.Trading.tick(currentServer);
        // Trade requests lapse, open direct trades are checked, and a swap that is due is made.
        za.co.neroland.neroeconomy.direct.DirectTrades.tick(currentServer);
        flushRequestedSave(currentServer);
        runHook(tickHook);
    }

    public synchronized void unbind(MinecraftServer stoppedServer) {
        if (server == stoppedServer) {
            active = null;
            server = null;
            saveRequested = false;
            // The resolved store and the frozen flag belong to the world that just stopped.
            EconomyStore.close(stoppedServer);
            // Shocks are world-scoped state: drop them entirely so a freshly loaded
            // single-player world cannot inherit a still-running shock from the last one.
            za.co.neroland.neroeconomy.api.MarketShocks.clear();
            // The alert limiter holds player-keyed entries; they must not outlive the world either.
            EconomyAlerts.clear();
            // Nor may the packet limiter's per-player buckets.
            za.co.neroland.neroeconomy.network.IntentGuard.clear();
            // Trade requests and open trade windows belong to the players of the world that stopped.
            za.co.neroland.neroeconomy.direct.DirectTrades.clear();
            // And the exchange's per-account rate limits.
            za.co.neroland.neroeconomy.exchange.Investing.clear();
            // A new session reports each failing simulation handler once again.
            Simulation.reset();
            // Nor whatever the companion-app module holds per player.
            runHook(unbindHook);
        }
    }

    /**
     * One listener pair for the companion-app module: {@code onTick} runs on the server thread at
     * the end of every bound tick, {@code onUnbind} when the server this provider was bound to
     * stops. The provider only holds two {@link Runnable}s, so it stays loadable when the module
     * could not register, and a hook that throws is contained here and never breaks the economy tick.
     */
    public void setLifecycleHooks(Runnable onTick, Runnable onUnbind) {
        tickHook = onTick;
        unbindHook = onUnbind;
    }

    private static void runHook(Runnable hook) {
        if (hook == null) return;
        try {
            hook.run();
        } catch (RuntimeException failure) {
            NeroEconomyCommon.LOGGER.debug("[NeroEconomy] A lifecycle hook failed ({}).", failure.getClass().getName());
        }
    }

    /**
     * Economy time (see {@link EconomyClock}). Use this — never {@code System.currentTimeMillis()} —
     * for anything that is compared with a stored expiry or written as a timestamp. With no server
     * bound there is no persisted clock, so it degrades to the wall clock.
     */
    public long now() {
        EconomySavedData data = active;
        return data == null ? System.currentTimeMillis() : data.now();
    }

    /**
     * Converts a stored economy timestamp into the wall-clock time it stands for, for anything that
     * leaves the server: a deadline sent to a client, a time printed in chat, a Link snapshot. With
     * no server bound the two clocks are the same.
     */
    public long toWallMillis(long economyMillis) {
        EconomySavedData data = active;
        return data == null ? economyMillis : data.toWallMillis(economyMillis);
    }

    /** Whether {@link #now()} is economy time (a server is bound) rather than its wall-clock fallback. */
    public boolean clockBound() { return active != null; }

    /**
     * Reserves one Credit Chip redemption for the current economy day. False when no server is
     * bound, when redemption is disabled ({@code dailyLimit <= 0}) or when the account is at its limit.
     */
    public boolean reserveChipRedemption(UUID player, int dailyLimit) {
        EconomySavedData data = active;
        return data != null && data.reserveChipRedemption(player, dailyLimit);
    }

    /** Hands back a reservation whose payment was then refused. */
    public void releaseChipRedemption(UUID player) {
        EconomySavedData data = active;
        if (data != null) data.releaseChipRedemption(player);
    }

    /** How many Credit Chips the account has redeemed in the current economy day. */
    public int chipRedemptionsToday(UUID player) {
        EconomySavedData data = active;
        return data == null ? 0 : data.chipRedemptionsToday(player);
    }

    /**
     * Asks for the economy store to be written to disk soon. Called after goods have moved between
     * the store and a live inventory — the one kind of operation that spans two files — so a crash
     * cannot leave them in both places.
     */
    public void requestSave() { saveRequested = true; }

    /** Coalesces requested saves into one asynchronous write of the overworld's dirty saved data. */
    private void flushRequestedSave(MinecraftServer currentServer) {
        if (saveCooldown > 0) saveCooldown--;
        if (!saveRequested || saveCooldown > 0) return;
        saveRequested = false;
        saveCooldown = SAVE_COOLDOWN_TICKS;
        try {
            currentServer.overworld().getDataStorage().scheduleSave();
        } catch (RuntimeException failure) {
            // A storage that is already closing saves everything itself; nothing to add here.
            NeroEconomyCommon.LOGGER.debug("[NeroEconomy] Prompt economy save was skipped.");
        }
    }

    /**
     * Core refreshes a store's recovery backup when the store is fetched through its guard. The
     * ledger is held by reference, so the store is fetched here on the sweep cadence to keep its
     * backup current.
     */
    private void refreshBackups(MinecraftServer currentServer) {
        EconomySavedData.get(currentServer);
        ShopOwnershipState.get(currentServer);
    }

    /**
     * Expiry refunds escrow, so it runs on bind and on a short tick cadence, not once a day. The same
     * cadence settles shops whose block was replaced without the game reporting it.
     */
    private void sweepMarket(MinecraftServer currentServer, long now) {
        za.co.neroland.neroeconomy.market.MarketSavedData.get(currentServer).expire(now);
        // Orders on the book lapse on the same cadence: goods back to the vault, escrow to a claim.
        za.co.neroland.neroeconomy.trade.Trading.expire(currentServer, now);
        za.co.neroland.neroeconomy.shop.ShopSweep.run(currentServer);
        // The same backstop for Auto-Traders: a rule never outlives a block that was swapped out silently.
        za.co.neroland.neroeconomy.trader.AutoTraders.sweep(currentServer);
        za.co.neroland.neroeconomy.api.MarketShocks.purgeExpired();
    }

    /**
     * Picks the ledger up again after the store behind it was replaced, which only an operator
     * accepting a fresh start for a frozen economy does.
     */
    public synchronized void refresh(MinecraftServer currentServer) {
        if (server != currentServer || currentServer == null) return;
        active = EconomySavedData.get(currentServer);
        active.topUpReserve();
    }

    public EconomySavedData activeData() {
        EconomySavedData data = active;
        if (data == null) {
            throw new IllegalStateException("NeroEconomy has no active Minecraft server");
        }
        return data;
    }

    /** The bound server, or null when none is: for callers that can do without one. */
    public MinecraftServer serverOrNull() { return server; }

    public MinecraftServer activeServer() {
        MinecraftServer current = server;
        if (current == null) throw new IllegalStateException("NeroEconomy has no active Minecraft server");
        return current;
    }

    // --- vesting -----------------------------------------------------------------------------

    /**
     * Marks every online player vested whose play time has reached {@code startingBalanceVestMinutes}.
     * Runs on the sweep cadence and costs one set lookup per online player.
     */
    private void sweepVesting(MinecraftServer currentServer) {
        EconomySavedData data = active;
        if (data == null || !data.hasUnvested()) return;
        for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) vestIfDue(data, player);
    }

    private static boolean vestIfDue(EconomySavedData data, ServerPlayer player) {
        if (player == null || !data.unvested(player.getUUID())) return false;
        long minutes = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) / TICKS_PER_MINUTE;
        return EconomySavedData.vestingReached(minutes) && data.markVested(player.getUUID());
    }

    /** A payment was refused for unvested credits: checks whether any payer has in fact vested by now. */
    private boolean vestPayers(EconomySavedData data, LedgerPlan plan) {
        MinecraftServer current = server;
        if (current == null) return false;
        boolean vested = false;
        for (LedgerPlan.Leg leg : plan.legs())
            if (leg.from() instanceof PlayerAccountId payer)
                vested |= vestIfDue(data, current.getPlayerList().getPlayer(payer.playerId()));
        return vested;
    }

    /** What a system account holds; 0 when no server is bound. */
    public long systemBalance(SystemAccountId account) {
        EconomySavedData data = active;
        return data == null ? 0L : data.balance(account);
    }

    /** A player about to be told their credits are locked is checked first: they may have vested by now. */
    private void vestIfDue(EconomySavedData data, UUID player) {
        MinecraftServer current = server;
        if (current != null && player != null && data.unvested(player))
            vestIfDue(data, current.getPlayerList().getPlayer(player));
    }

    /** What the player can pay, offer or bid to another player right now. */
    public long spendable(UUID player) {
        EconomySavedData data = active;
        if (data == null) return 0L;
        vestIfDue(data, player);
        return data.spendable(player);
    }

    /**
     * Whether a player-facing payment of {@code amount} would be refused only because part of the
     * balance is an unvested starting grant; lets a caller give that reason instead of "not enough".
     */
    public boolean lockBlocks(UUID player, long amount) {
        EconomySavedData data = active;
        if (data == null) return false;
        vestIfDue(data, player);
        return data.lockBlocks(player, amount);
    }

    // --- Core's provider contract ------------------------------------------------------------
    // Core hands this provider to every sibling mod, which may call it from a client, a menu, or
    // after the server has stopped. Those calls degrade to "no money, nothing changed" rather than
    // throwing through the Core API and taking an unrelated mod down with them.

    @Override public long getBalance(UUID player, Currency currency) {
        EconomySavedData data = boundData("balance");
        return data == null ? 0L : data.getBalance(player, currency);
    }

    /**
     * Untyped, this is another mod's deposit: a transfer out of the Reserve within the sibling
     * budget, false when either cannot cover it. Typed, it is whatever the caller in this mod set up.
     */
    @Override public boolean deposit(UUID player, Currency currency, long amount) {
        TypedCall call = takePending();
        EconomySavedData data = boundData("deposit");
        if (data == null) return settled(call, LedgerResult.UNAVAILABLE);
        if (call == null) return data.deposit(player, currency, amount);
        if (call.plan != null) return settled(call, data.apply(call.plan));
        if (call.budget != null) return settled(call, player == null ? LedgerResult.INVALID
                : data.payFromReserve(call.budget, new PlayerAccountId(player), amount, call.type));
        return settled(call, data.deposit(player, currency, amount, call.type)
                ? LedgerResult.APPLIED : LedgerResult.INVALID);
    }

    /** Untyped, this is another mod's charge: a transfer into the Reserve. */
    @Override public boolean withdraw(UUID player, Currency currency, long amount) {
        TypedCall call = takePending();
        EconomySavedData data = boundData("withdraw");
        if (data == null) return settled(call, LedgerResult.UNAVAILABLE);
        if (call == null) return data.withdraw(player, currency, amount);
        if (call.plan != null) return settled(call, data.apply(call.plan));
        return settled(call, data.withdraw(player, currency, amount, call.type)
                ? LedgerResult.APPLIED : LedgerResult.INVALID);
    }

    /** Untyped, this is a payment between two players. */
    @Override public boolean transfer(UUID from, UUID to, Currency currency, long amount) {
        TypedCall call = takePending();
        EconomySavedData data = boundData("transfer");
        if (data == null) return settled(call, LedgerResult.UNAVAILABLE);
        if (call == null) {
            if (data.transfer(from, to, currency, amount)) return true;
            // Refused for an unvested starting balance: the payer may in fact have vested by now.
            if (from == null || !data.lockBlocks(from, amount)) return false;
            vestIfDue(data, from);
            return data.transfer(from, to, currency, amount);
        }
        if (call.plan != null) return settled(call, data.apply(call.plan));
        return settled(call, from == null || to == null ? LedgerResult.INVALID
                : data.apply(LedgerPlan.of(new PlayerAccountId(from), new PlayerAccountId(to), amount, call.type)));
    }

    /**
     * Deliberately empty. Core's built-in currency eraser calls this on every erasure request, and
     * this mod also registers {@link EconomySavedData#eraseFor} directly so the ledger is purged
     * even when no provider is bound. Doing the work here as well would walk the same rows twice
     * per request, so the direct eraser is the single ledger pass and this override only documents
     * that the seam is handled (and keeps Core's "default forgetPlayer" warning quiet).
     */
    @Override public void forgetPlayer(UUID player) { }

    // --- typed calls -------------------------------------------------------------------------
    // The typed calls go through Core so balance listeners still fire, but Core runs those listeners
    // inside the call and does not catch what they throw. A caller that has just debited a buyer
    // must get an answer either way, so a listener failure is absorbed here and the outcome is the
    // one the ledger recorded before any listener ran.

    /**
     * Applies {@code plan} as one operation: every leg or none. This is how every transfer that
     * touches a system account is made — escrow, claims, fees, refunds, a purchase with its fee —
     * and each leg is one audit row.
     *
     * <p>A plan may change the balance of at most two players, one paying and one receiving, which
     * is as much as one Core call can report to balance listeners; anything wider is refused as
     * {@link LedgerResult#INVALID} and must be split by the caller. A plan between system accounts
     * only is applied directly.
     *
     * <p>If the plan is refused only because a payer's starting grant is still locked, the payer's
     * play time is checked there and then, and the plan retried once if they have in fact vested.
     */
    public LedgerResult execute(LedgerPlan plan) {
        EconomySavedData data = boundData("plan");
        if (data == null) return LedgerResult.UNAVAILABLE;
        if (plan == null) return LedgerResult.INVALID;
        LedgerResult result = executeOnce(data, plan);
        if (result == LedgerResult.FUNDS_LOCKED && vestPayers(data, plan)) result = executeOnce(data, plan);
        return result;
    }

    private LedgerResult executeOnce(EconomySavedData data, LedgerPlan plan) {
        Map<UUID, Long> changes = new LinkedHashMap<>();
        try {
            for (UUID player : players(plan)) {
                long net = plan.net(new PlayerAccountId(player));
                if (net != 0L) changes.put(player, net);
            }
        } catch (ArithmeticException overflow) {
            return LedgerResult.INVALID;
        }
        if (changes.isEmpty()) return data.apply(plan);
        UUID payer = null;
        UUID payee = null;
        for (Map.Entry<UUID, Long> change : changes.entrySet()) {
            boolean pays = change.getValue() < 0L;
            if (pays ? payer != null : payee != null) return LedgerResult.INVALID;
            if (pays) payer = change.getKey();
            else payee = change.getKey();
        }
        TypedCall call = new TypedCall(null, null, plan);
        pending.set(call);
        try {
            // The amount is only what Core is told; the plan is what the ledger applies.
            if (payer != null && payee != null)
                CurrencyApi.transfer(payer, payee, NeroEconomyCommon.CREDITS, changes.get(payee));
            else if (payer != null)
                CurrencyApi.withdraw(payer, NeroEconomyCommon.CREDITS, -changes.get(payer));
            else CurrencyApi.deposit(payee, NeroEconomyCommon.CREDITS, changes.get(payee));
        } catch (RuntimeException failure) {
            reportListenerFailure(failure);
        } finally {
            clearPending(call);
        }
        return call.result == null ? LedgerResult.UNAVAILABLE : call.result;
    }

    private static Set<UUID> players(LedgerPlan plan) {
        Set<UUID> players = new LinkedHashSet<>();
        for (LedgerPlan.Leg leg : plan.legs()) {
            if (leg.from() instanceof PlayerAccountId from) players.add(from.playerId());
            if (leg.to() instanceof PlayerAccountId to) players.add(to.playerId());
        }
        return players;
    }

    /**
     * Applies {@code plan} to {@code ledger}: through {@link #execute} when that is the ledger of
     * the server this provider is bound to, so balance listeners hear of it, and straight to the
     * ledger otherwise — a store in a unit test, or one that is not bound yet. Either way a plan
     * that changes more than one paying or more than one receiving player is refused, so a rule
     * tested against a bare ledger is held to what the running game accepts.
     */
    public LedgerResult executeOn(EconomySavedData ledger, LedgerPlan plan) {
        if (ledger == null) return LedgerResult.UNAVAILABLE;
        if (plan == null) return LedgerResult.INVALID;
        if (active == ledger) return execute(plan);
        return reportable(plan) ? ledger.apply(plan) : LedgerResult.INVALID;
    }

    /** Whether a plan changes the balance of at most one paying and at most one receiving player. */
    static boolean reportable(LedgerPlan plan) {
        boolean payer = false;
        boolean payee = false;
        try {
            for (UUID player : players(plan)) {
                long net = plan.net(new PlayerAccountId(player));
                if (net == 0L) continue;
                if (net < 0L ? payer : payee) return false;
                if (net < 0L) payer = true;
                else payee = true;
            }
        } catch (ArithmeticException overflow) {
            return false;
        }
        return true;
    }

    /** One transfer between two accounts; see {@link #execute}. */
    public LedgerResult move(AccountId from, AccountId to, long amount, TransactionType type) {
        return execute(LedgerPlan.of(from, to, amount, type));
    }

    /**
     * Pays {@code amount} from the Reserve to a player, counted against {@code kind}'s daily
     * budget. Refused before anything moves when the Reserve ({@code RESERVE_SHORT}) or the budget
     * ({@code BUDGET_SPENT}) cannot cover it.
     */
    public LedgerResult payFromReserve(BudgetKind kind, UUID player, long amount, TransactionType type) {
        if (kind == null || player == null || type == null || amount < 0L) return LedgerResult.INVALID;
        TypedCall call = new TypedCall(type, kind, null);
        pending.set(call);
        try {
            CurrencyApi.deposit(player, NeroEconomyCommon.CREDITS, amount);
        } catch (RuntimeException failure) {
            reportListenerFailure(failure);
        } finally {
            clearPending(call);
        }
        return call.result == null ? LedgerResult.UNAVAILABLE : call.result;
    }

    /**
     * Pays a batch from the Reserve to players. When the Reserve or the day's budget cannot cover
     * the whole batch, every amount is scaled by the same factor, rounded down.
     *
     * @return what was asked, what was paid, the factor applied and each player's share
     */
    public Reserve.ProRata payProRata(BudgetKind kind, Map<UUID, Long> amounts, TransactionType type) {
        EconomySavedData data = boundData("batch");
        Map<AccountId, Long> wanted = new LinkedHashMap<>();
        if (amounts != null) amounts.forEach((player, amount) -> {
            if (player != null && amount != null) wanted.put(new PlayerAccountId(player), amount);
        });
        if (data == null || kind == null || type == null) return Reserve.shares(wanted, 0L);
        Reserve.ProRata planned = data.quoteProRata(kind, wanted);
        Map<AccountId, Long> paid = new LinkedHashMap<>();
        long total = 0L;
        for (Map.Entry<AccountId, Long> share : planned.shares().entrySet()) {
            boolean ok = share.getValue() > 0L && payFromReserve(kind, ((PlayerAccountId) share.getKey()).playerId(),
                    share.getValue(), type).ok();
            paid.put(share.getKey(), ok ? share.getValue() : 0L);
            if (ok) total += share.getValue();
        }
        return new Reserve.ProRata(planned.requested(), total, planned.numerator(), planned.denominator(), paid);
    }

    /**
     * {@link #payProRata} for {@code ledger}: through Core, so balance listeners hear of it, when
     * that is the ledger of the server this provider is bound to, and straight to the ledger
     * otherwise — a store in a unit test, or one that is not bound yet.
     */
    public Reserve.ProRata payProRataOn(EconomySavedData ledger, BudgetKind kind, Map<UUID, Long> amounts,
            TransactionType type) {
        if (ledger != null && active == ledger) return payProRata(kind, amounts, type);
        Map<AccountId, Long> wanted = new LinkedHashMap<>();
        if (amounts != null) amounts.forEach((player, amount) -> {
            if (player != null && amount != null) wanted.put(new PlayerAccountId(player), amount);
        });
        if (ledger == null || kind == null || type == null) return Reserve.shares(wanted, 0L);
        return ledger.payProRata(kind, wanted, type);
    }

    /**
     * An operator's mint. The ledger accepts it only under a minting reason ({@code ADMIN_GRANT},
     * {@code ADMIN_SET}); anything that is really a payout must use {@link #payFromReserve} or
     * {@link #execute} instead and is refused here.
     */
    public boolean depositTyped(UUID player, Currency currency, long amount, TransactionType type) {
        if (type == null) return false;
        long before = getBalance(player, currency);
        TypedCall call = new TypedCall(type, null, null);
        pending.set(call);
        try {
            return CurrencyApi.deposit(player, currency, amount);
        } catch (RuntimeException failure) {
            reportListenerFailure(failure);
            return call.result != null ? call.result.ok()
                    : LedgerOutcome.deposited(before, getBalance(player, currency), amount);
        } finally {
            clearPending(call);
        }
    }

    /** An operator's burn; the ledger accepts it only under {@code ADMIN_TAKE} or {@code ADMIN_SET}. */
    public boolean withdrawTyped(UUID player, Currency currency, long amount, TransactionType type) {
        if (type == null) return false;
        long before = getBalance(player, currency);
        TypedCall call = new TypedCall(type, null, null);
        pending.set(call);
        try {
            return CurrencyApi.withdraw(player, currency, amount);
        } catch (RuntimeException failure) {
            reportListenerFailure(failure);
            return call.result != null ? call.result.ok()
                    : LedgerOutcome.withdrawn(before, getBalance(player, currency), amount);
        } finally {
            clearPending(call);
        }
    }

    /** A payment from one player to another under {@code type}; unvested starting credits cannot be sent. */
    public boolean transferTyped(UUID from, UUID to, Currency currency, long amount, TransactionType type) {
        if (type == null) return false;
        long fromBefore = getBalance(from, currency);
        long toBefore = getBalance(to, currency);
        TypedCall call = new TypedCall(type, null, null);
        pending.set(call);
        try {
            return CurrencyApi.transfer(from, to, currency, amount);
        } catch (RuntimeException failure) {
            reportListenerFailure(failure);
            return call.result != null ? call.result.ok() : LedgerOutcome.transferred(fromBefore,
                    getBalance(from, currency), toBefore, getBalance(to, currency), amount);
        } finally {
            clearPending(call);
        }
    }

    /**
     * Read once at entry and cleared at once: the reason belongs to exactly one store mutation, so a
     * listener that makes its own ledger call from inside this one can never inherit it.
     */
    private TypedCall takePending() {
        TypedCall call = pending.get();
        if (call != null) pending.remove();
        return call;
    }

    private static boolean settled(TypedCall call, LedgerResult result) {
        if (call != null) call.result = result;
        return result.ok();
    }

    /** Only clears this call's own marker; a nested typed call has already replaced and cleared its own. */
    private void clearPending(TypedCall call) {
        if (pending.get() == call) pending.remove();
    }

    /** One warning and one fixed-message report per bind; a listener that always throws must not flood either. */
    private void reportListenerFailure(RuntimeException failure) {
        NeroEconomyCommon.LOGGER.debug("[NeroEconomy] A currency listener threw during a ledger call.");
        if (!listenerFailureReported.compareAndSet(false, true)) return;
        NeroEconomyCommon.LOGGER.warn("[NeroEconomy] A currency listener from another mod threw during a "
                + "ledger call ({}); the ledger result was kept.", failure.getClass().getName());
        try {
            // The foreign message is not forwarded: it is outside this mod's control and may name a player.
            NeroEconomyTelemetry.captureHandledException(new IllegalStateException(
                    "Currency listener failed: " + failure.getClass().getName()), "currency_listener");
        } catch (RuntimeException | LinkageError ignored) { }
    }

    private EconomySavedData boundData(String operation) {
        EconomySavedData data = active;
        if (data == null) reportUnbound(operation);
        return data;
    }

    /** One breadcrumb per bind: an unbound call storm must not become a telemetry storm. */
    private void reportUnbound(String operation) {
        NeroEconomyCommon.LOGGER.debug("[NeroEconomy] Currency {} ignored; no server is bound.", operation);
        if (!unboundReported.compareAndSet(false, true)) return;
        try {
            NeroEconomyTelemetry.captureHandledException(
                    new IllegalStateException("Currency call without a bound server"), "currency_unbound");
        } catch (RuntimeException | LinkageError ignored) { }
    }

    /** Test seam: binds a ledger without a server, so the typed calls can be exercised in plain JUnit. */
    void bindForTest(EconomySavedData data) { active = data; }
}
