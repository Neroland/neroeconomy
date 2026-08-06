package za.co.neroland.neroeconomy.economy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerolandcore.economy.Currency;
import za.co.neroland.nerolandcore.economy.CurrencyProvider;
import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.data.LedgerExportFiles;
import za.co.neroland.neroeconomy.data.SavedDataRecovery;
import za.co.neroland.neroeconomy.telemetry.NeroEconomyTelemetry;

/** Global Core provider whose delegate is explicitly rebound/cleared for each server lifecycle. */
public final class ServerCurrencyProvider implements CurrencyProvider {
    private static final long RETENTION_SWEEP_MILLIS = 86_400_000L;
    /** Market and buy-offer expiry cannot wait for the daily sweep, or escrow outlives a restart. */
    private static final int MARKET_SWEEP_TICKS = 600;

    private volatile EconomySavedData active;
    private volatile MinecraftServer server;
    private final ThreadLocal<TransactionType> reason = new ThreadLocal<>();
    private final AtomicBoolean unboundReported = new AtomicBoolean();
    private long nextRetentionSweep;
    private int marketSweepCountdown;

    public synchronized void bind(MinecraftServer nextServer) {
        if (nextServer == null) {
            throw new IllegalArgumentException("server");
        }
        server = nextServer;
        active = EconomySavedData.get(nextServer);
        long now = System.currentTimeMillis();
        active.purgeExpired(now);
        // A daily-restarted idle server would otherwise never reach the retention sweep at all.
        sweepMarket(nextServer, now);
        nextRetentionSweep = now + RETENTION_SWEEP_MILLIS;
        marketSweepCountdown = MARKET_SWEEP_TICKS;
        unboundReported.set(false);
    }

    public void tick(MinecraftServer currentServer) {
        if (server != currentServer) return;
        long now = System.currentTimeMillis();
        za.co.neroland.neroeconomy.obligation.ObligationSavedData.get(currentServer).tick(currentServer, now);
        if (--marketSweepCountdown <= 0) {
            marketSweepCountdown = MARKET_SWEEP_TICKS;
            sweepMarket(currentServer, now);
        }
        if (now < nextRetentionSweep) return;
        activeData().purgeExpired(now);
        LedgerExportFiles.purgeExpired(currentServer, now);
        nextRetentionSweep = now + RETENTION_SWEEP_MILLIS;
    }

    public synchronized void unbind(MinecraftServer stoppedServer) {
        if (server == stoppedServer) {
            active = null;
            server = null;
            // Shocks are world-scoped state: drop them entirely so a freshly loaded
            // single-player world cannot inherit a still-running shock from the last one.
            za.co.neroland.neroeconomy.api.MarketShocks.clear();
            SavedDataRecovery.reset();
        }
    }

    /** Expiry refunds escrow, so it runs on bind and on a short tick cadence, not once a day. */
    private void sweepMarket(MinecraftServer currentServer, long now) {
        za.co.neroland.neroeconomy.market.MarketSavedData.get(currentServer).expire(now);
        za.co.neroland.neroeconomy.api.MarketShocks.purgeExpired();
    }

    public EconomySavedData activeData() {
        EconomySavedData data = active;
        if (data == null) {
            throw new IllegalStateException("NeroEconomy has no active Minecraft server");
        }
        return data;
    }

    public MinecraftServer activeServer() {
        MinecraftServer current = server;
        if (current == null) throw new IllegalStateException("NeroEconomy has no active Minecraft server");
        return current;
    }

    // Core hands this provider to every sibling mod, which may call it from a client, a menu, or
    // after the server has stopped. Those calls degrade to "no money, nothing changed" rather than
    // throwing through the Core API and taking an unrelated mod down with them.
    @Override public long getBalance(UUID player, Currency currency) {
        EconomySavedData data = boundData("balance");
        return data == null ? 0L : data.getBalance(player, currency);
    }
    @Override public boolean deposit(UUID player, Currency currency, long amount) {
        EconomySavedData data = boundData("deposit");
        TransactionType type = reason.get();
        return data != null && data.deposit(player, currency, amount, type == null ? TransactionType.DEPOSIT : type);
    }
    @Override public boolean withdraw(UUID player, Currency currency, long amount) {
        EconomySavedData data = boundData("withdraw");
        TransactionType type = reason.get();
        return data != null && data.withdraw(player, currency, amount, type == null ? TransactionType.WITHDRAWAL : type);
    }
    @Override public boolean transfer(UUID from, UUID to, Currency currency, long amount) {
        EconomySavedData data = boundData("transfer");
        TransactionType type = reason.get();
        return data != null && data.transfer(from, to, currency, amount,
                type == null ? TransactionType.PLAYER_PAYMENT : type);
    }
    @Override public void forgetPlayer(UUID player) {
        EconomySavedData data = boundData("erasure");
        if (data != null) data.forgetPlayer(player);
    }

    public boolean depositTyped(UUID player, Currency currency, long amount, TransactionType type) {
        return withReason(type, () -> CurrencyApi.deposit(player, currency, amount));
    }

    public boolean withdrawTyped(UUID player, Currency currency, long amount, TransactionType type) {
        return withReason(type, () -> CurrencyApi.withdraw(player, currency, amount));
    }

    public boolean transferTyped(UUID from, UUID to, Currency currency, long amount, TransactionType type) {
        return withReason(type, () -> CurrencyApi.transfer(from, to, currency, amount));
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

    private boolean withReason(TransactionType type, java.util.function.BooleanSupplier mutation) {
        if (type == null || reason.get() != null) return false;
        reason.set(type);
        try { return mutation.getAsBoolean(); }
        finally { reason.remove(); }
    }
}
