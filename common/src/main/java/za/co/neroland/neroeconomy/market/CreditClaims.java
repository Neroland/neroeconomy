package za.co.neroland.neroeconomy.market;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import za.co.neroland.neroeconomy.economy.TransactionType;

/**
 * Credits owed to players and not yet collected, kept per reason so that collection can write the
 * right audit reason (a refund is an {@code ESCROW_REFUND}, not a payout). The credits themselves
 * sit in the ledger's escrow account; this is the record of whose they are. A player's total across
 * every reason never exceeds {@link Long#MAX_VALUE}: a claim that would overflow is refused, and
 * the caller keeps the escrow it was about to release. Pure Java so it is unit tested directly.
 */
public final class CreditClaims {
    private final Map<UUID, EnumMap<TransactionType, Long>> claims = new LinkedHashMap<>();

    public long total(UUID player) {
        EnumMap<TransactionType, Long> buckets = claims.get(player);
        if (buckets == null) return 0L;
        long total = 0L;
        for (long value : buckets.values()) total += value;
        return total;
    }

    /** Everything owed to every player together: the claims' share of what the escrow account holds. */
    public long grandTotal() {
        long total = 0L;
        for (UUID player : claims.keySet()) {
            long owed = total(player);
            total = Long.MAX_VALUE - total < owed ? Long.MAX_VALUE : total + owed;
        }
        return total;
    }

    /** Pre-flight for callers that must not debit or delete anything before a claim can be booked. */
    public boolean canAccept(UUID player, long amount) {
        return player != null && amount > 0L && Long.MAX_VALUE - total(player) >= amount;
    }

    public boolean add(UUID player, TransactionType reason, long amount) {
        if (reason == null || !canAccept(player, amount)) return false;
        claims.computeIfAbsent(player, ignored -> new EnumMap<>(TransactionType.class)).merge(reason, amount, Long::sum);
        return true;
    }

    /** A copy of one player's claims by reason; empty when nothing is owed. */
    public Map<TransactionType, Long> buckets(UUID player) {
        EnumMap<TransactionType, Long> buckets = claims.get(player);
        return buckets == null ? Map.of() : new EnumMap<>(buckets);
    }

    /** Removes one reason's claim once it has been paid out. @return the amount that was removed. */
    public long settle(UUID player, TransactionType reason) {
        EnumMap<TransactionType, Long> buckets = claims.get(player);
        if (buckets == null) return 0L;
        Long removed = buckets.remove(reason);
        if (buckets.isEmpty()) claims.remove(player);
        return removed == null ? 0L : removed;
    }

    /** Erasure: drops everything owed to one player. @return true when anything was removed. */
    public boolean forget(UUID player) { return player != null && claims.remove(player) != null; }

    /** Every claim by player and reason, for persistence. */
    public Map<UUID, Map<TransactionType, Long>> snapshot() {
        Map<UUID, Map<TransactionType, Long>> copy = new LinkedHashMap<>();
        claims.forEach((player, buckets) -> copy.put(player, new EnumMap<>(buckets)));
        return copy;
    }
}
