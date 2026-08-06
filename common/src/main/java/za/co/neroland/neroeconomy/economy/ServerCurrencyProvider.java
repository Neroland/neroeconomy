package za.co.neroland.neroeconomy.economy;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.nerolandcore.economy.Currency;
import za.co.neroland.nerolandcore.economy.CurrencyProvider;
import za.co.neroland.nerolandcore.economy.CurrencyApi;

/** Global Core provider whose delegate is explicitly rebound/cleared for each server lifecycle. */
public final class ServerCurrencyProvider implements CurrencyProvider {
    private volatile EconomySavedData active;
    private volatile MinecraftServer server;
    private final ThreadLocal<TransactionType> reason = new ThreadLocal<>();
    private long nextRetentionSweep;

    public synchronized void bind(MinecraftServer nextServer) {
        if (nextServer == null) {
            throw new IllegalArgumentException("server");
        }
        server = nextServer;
        active = EconomySavedData.get(nextServer);
        active.purgeExpired(System.currentTimeMillis());
        nextRetentionSweep = System.currentTimeMillis() + 86_400_000L;
    }

    public void tick(MinecraftServer currentServer) {
        if (server != currentServer) return;
        long now = System.currentTimeMillis();
        za.co.neroland.neroeconomy.obligation.ObligationSavedData.get(currentServer).tick(currentServer, now);
        if (now < nextRetentionSweep) return;
        activeData().purgeExpired(now);
        za.co.neroland.neroeconomy.market.MarketSavedData.get(currentServer).expire(now);
        za.co.neroland.neroeconomy.api.MarketShocks.purgeExpired();
        nextRetentionSweep = now + 86_400_000L;
    }

    public synchronized void unbind(MinecraftServer stoppedServer) {
        if (server == stoppedServer) {
            active = null;
            server = null;
        }
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

    @Override public long getBalance(UUID player, Currency currency) { return activeData().getBalance(player, currency); }
    @Override public boolean deposit(UUID player, Currency currency, long amount) {
        TransactionType type = reason.get();
        return activeData().deposit(player, currency, amount, type == null ? TransactionType.DEPOSIT : type);
    }
    @Override public boolean withdraw(UUID player, Currency currency, long amount) {
        TransactionType type = reason.get();
        return activeData().withdraw(player, currency, amount, type == null ? TransactionType.WITHDRAWAL : type);
    }
    @Override public boolean transfer(UUID from, UUID to, Currency currency, long amount) {
        TransactionType type = reason.get();
        return activeData().transfer(from, to, currency, amount,
                type == null ? TransactionType.PLAYER_PAYMENT : type);
    }
    @Override public void forgetPlayer(UUID player) { activeData().forgetPlayer(player); }

    public boolean depositTyped(UUID player, Currency currency, long amount, TransactionType type) {
        return withReason(type, () -> CurrencyApi.deposit(player, currency, amount));
    }

    public boolean withdrawTyped(UUID player, Currency currency, long amount, TransactionType type) {
        return withReason(type, () -> CurrencyApi.withdraw(player, currency, amount));
    }

    public boolean transferTyped(UUID from, UUID to, Currency currency, long amount, TransactionType type) {
        return withReason(type, () -> CurrencyApi.transfer(from, to, currency, amount));
    }

    private boolean withReason(TransactionType type, java.util.function.BooleanSupplier mutation) {
        if (type == null || reason.get() != null) return false;
        reason.set(type);
        try { return mutation.getAsBoolean(); }
        finally { reason.remove(); }
    }
}
