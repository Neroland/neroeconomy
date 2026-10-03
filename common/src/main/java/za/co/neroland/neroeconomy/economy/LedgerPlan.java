package za.co.neroland.neroeconomy.economy;

import java.util.ArrayList;
import java.util.List;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;

/**
 * An ordered set of transfers the ledger applies as one operation: every leg, or none. A plan can
 * only move credits between accounts; it can never create or destroy them.
 *
 * <p>Each leg is one audit row. A leg out of the Reserve may name the daily budget it is counted
 * against. A leg out of a player account is either <em>player-facing</em> — it may not use a
 * starting balance that has not vested — or <em>system-facing</em>, which may. By default a leg is
 * player-facing when it pays a player or the escrow account (credits promised to somebody else)
 * and system-facing otherwise.
 */
public final class LedgerPlan {
    /**
     * @param budget          the Reserve budget this leg is counted against; null for a leg that does
     *                        not leave the Reserve or is limited only by its balance
     * @param lockedSpendable whether a player paying this leg may use unvested starting credits
     */
    public record Leg(AccountId from, AccountId to, long amount, TransactionType type, BudgetKind budget,
            boolean lockedSpendable) { }

    private final List<Leg> legs;

    private LedgerPlan(List<Leg> legs) { this.legs = List.copyOf(legs); }

    public List<Leg> legs() { return legs; }

    public static Builder builder() { return new Builder(); }

    /** A single transfer under the default spending rule. */
    public static LedgerPlan of(AccountId from, AccountId to, long amount, TransactionType type) {
        return builder().move(from, to, amount, type).build();
    }

    /** A single payment out of the Reserve, counted against {@code budget}. */
    public static LedgerPlan pay(BudgetKind budget, AccountId to, long amount, TransactionType type) {
        return builder().pay(budget, to, amount, type).build();
    }

    /** What the plan does to one account: positive when it gains, negative when it pays. */
    public long net(AccountId account) {
        long net = 0L;
        for (Leg leg : legs) {
            if (leg.from() != null && leg.from().equals(leg.to())) continue;
            if (account.equals(leg.to())) net = Math.addExact(net, leg.amount());
            if (account.equals(leg.from())) net = Math.subtractExact(net, leg.amount());
        }
        return net;
    }

    public static final class Builder {
        private final List<Leg> legs = new ArrayList<>();

        private Builder() { }

        /** A transfer under the default rule: unvested credits only reach system counterparties. */
        public Builder move(AccountId from, AccountId to, long amount, TransactionType type) {
            boolean system = to instanceof SystemAccountId && !SystemAccountId.ESCROW.equals(to);
            legs.add(new Leg(from, to, amount, type, null, system));
            return this;
        }

        /**
         * A transfer that may use unvested credits wherever it goes. Only for a payment whose
         * counterparty is the system even though the credits pass through escrow.
         */
        public Builder moveAllowingLocked(AccountId from, AccountId to, long amount, TransactionType type) {
            legs.add(new Leg(from, to, amount, type, null, true));
            return this;
        }

        /**
         * A transfer that may not use unvested credits wherever it goes. For a payment that is part
         * of buying from another player even though this leg of it lands in a system account.
         */
        public Builder movePlayerFacing(AccountId from, AccountId to, long amount, TransactionType type) {
            legs.add(new Leg(from, to, amount, type, null, false));
            return this;
        }

        /** A payment out of the Reserve, counted against {@code budget}. */
        public Builder pay(BudgetKind budget, AccountId to, long amount, TransactionType type) {
            legs.add(new Leg(SystemAccountId.RESERVE, to, amount, type, budget, true));
            return this;
        }

        public LedgerPlan build() { return new LedgerPlan(legs); }
    }
}
