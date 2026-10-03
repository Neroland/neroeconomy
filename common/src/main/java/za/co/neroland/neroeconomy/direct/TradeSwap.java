package za.co.neroland.neroeconomy.direct;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.book.GoodsReceiver;
import za.co.neroland.neroeconomy.direct.TradeSession.Side;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.Fees;
import za.co.neroland.neroeconomy.economy.LedgerPlan;
import za.co.neroland.neroeconomy.economy.LedgerResult;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.trade.Accounts;
import za.co.neroland.neroeconomy.trade.TradeEnv;

/**
 * The swap that ends a direct trade, against the store alone: the credits of both sides as ledger
 * plans, and the goods of both sides out of {@link TradeEscrowSection} and into whatever receives
 * them. No game in it, so the same code runs in the trade window, in plain JUnit and in the
 * operation fuzzer.
 *
 * <p><b>Credits.</b> Each side's credits go to the other side less {@code tradeTaxPercent}, which
 * goes to the Reserve as {@code TRADE_TAX}. They are never escrowed: they are checked here, at the
 * swap, against the balance and against the vesting lock — credits sent to another player cannot
 * come out of a starting balance that has not vested — and then moved. Both sides move in
 * <b>one</b> ledger plan whenever the ledger can report it, which is always when at most one side
 * ends up paying. Only when both sides give credits and the tax leaves each of them a little
 * poorer does the ledger need one plan per side; the second cannot fail after the checks made
 * here, and if it did the first is put back before anything else happens.
 *
 * <p><b>Goods.</b> Only after the credits have moved are the lots taken out of escrow and handed
 * to the receivers, which must never lose them (an inventory first, a vault for whatever does not
 * fit). A swap that is refused has moved nothing at all.
 */
public final class TradeSwap {
    /** Why a swap did or did not happen. Nothing has moved unless the result is {@link #OK}. */
    public enum Result {
        OK(null, "ok"),
        /** The economy is frozen for this session. */
        FROZEN(null, "frozen"),
        /** What is in escrow is not what the trade says each side put in. */
        ESCROW_CHANGED(null, "escrow_changed"),
        FIRST_SHORT(Side.FIRST, "short"),
        SECOND_SHORT(Side.SECOND, "short"),
        /** The side has the credits, but part of them is a starting balance that has not vested yet. */
        FIRST_LOCKED(Side.FIRST, "funds_locked"),
        SECOND_LOCKED(Side.SECOND, "funds_locked"),
        /** The ledger refused the credits for a reason the checks did not foresee. */
        LEDGER(null, "ledger");

        private final Side side;
        private final String word;

        Result(Side side, String word) {
            this.side = side;
            this.word = word;
        }

        public boolean ok() { return this == OK; }

        /** The side the refusal is about, or null when it is about neither. */
        public Side side() { return side; }

        /** Lang key of the plain reason, worded for the side reading it. */
        public String translationKey(Side viewer) {
            if (this == FROZEN) return EconomyHealth.FROZEN_KEY;
            String key = "message.neroeconomy.direct_trade.swap." + word;
            return side == null ? key : key + (side == viewer ? ".you" : ".them");
        }
    }

    /**
     * The credit side of a trade, worked out once.
     *
     * @param firstCredits  what the first side gives
     * @param secondCredits what the second side gives
     * @param firstTax      the trade tax on what the first side gives
     * @param secondTax     the trade tax on what the second side gives
     */
    public record Terms(long firstCredits, long secondCredits, long firstTax, long secondTax) {
        public long credits(Side side) { return side == Side.FIRST ? firstCredits : secondCredits; }
        public long tax(Side side) { return side == Side.FIRST ? firstTax : secondTax; }
        /** What a side receives: what the other gives, less the tax on it. */
        public long receives(Side side) { return credits(side.other()) - tax(side.other()); }
        /** What the swap does to a side's balance. */
        public long net(Side side) { return receives(side) - credits(side); }
    }

    /**
     * What a swap did.
     *
     * @param terms  the credits and the tax; null unless the result is OK
     * @param first  the lots the first side gave, now with the second; empty unless the result is OK
     * @param second the lots the second side gave, now with the first
     */
    public record Outcome(Result result, Terms terms, List<Lot> first, List<Lot> second) {
        static Outcome refused(Result result) { return new Outcome(result, null, List.of(), List.of()); }

        public boolean ok() { return result.ok(); }
    }

    private TradeSwap() { }

    /** The tax on each side's credits. Amounts below nothing are treated as nothing. */
    public static Terms terms(long firstCredits, long secondCredits, double taxPercent) {
        long first = Math.max(0L, firstCredits);
        long second = Math.max(0L, secondCredits);
        return new Terms(first, second, Fees.fee(first, taxPercent), Fees.fee(second, taxPercent));
    }

    /**
     * The ledger plans of a swap: one, unless both sides end up paying (see the class comment),
     * and none when no credits move at all.
     */
    public static List<LedgerPlan> plans(AccountId first, AccountId second, Terms terms) {
        if (terms.firstCredits() == 0L && terms.secondCredits() == 0L) return List.of();
        if (terms.net(Side.FIRST) < 0L && terms.net(Side.SECOND) < 0L)
            return List.of(pays(LedgerPlan.builder(), first, second, terms.firstCredits(), terms.firstTax()).build(),
                    pays(LedgerPlan.builder(), second, first, terms.secondCredits(), terms.secondTax()).build());
        LedgerPlan.Builder plan = LedgerPlan.builder();
        pays(plan, first, second, terms.firstCredits(), terms.firstTax());
        pays(plan, second, first, terms.secondCredits(), terms.secondTax());
        return List.of(plan.build());
    }

    /** One side's credits: to the other side less the tax, and the tax to the Reserve. Neither may use unvested credits. */
    private static LedgerPlan.Builder pays(LedgerPlan.Builder plan, AccountId payer, AccountId payee, long credits,
            long tax) {
        if (credits - tax > 0L) plan.move(payer, payee, credits - tax, TransactionType.PLAYER_PAYMENT);
        if (tax > 0L) plan.movePlayerFacing(payer, SystemAccountId.RESERVE, tax, TransactionType.TRADE_TAX);
        return plan;
    }

    /** Puts one side's credits back where they came from, after the other side's could not be moved. */
    private static LedgerPlan undo(AccountId payer, AccountId payee, long credits, long tax) {
        LedgerPlan.Builder plan = LedgerPlan.builder();
        if (credits - tax > 0L) plan.move(payee, payer, credits - tax, TransactionType.PLAYER_PAYMENT);
        if (tax > 0L) plan.move(SystemAccountId.RESERVE, payer, tax, TransactionType.TRADE_TAX);
        return plan.build();
    }

    /**
     * Whether each side can pay what it gives, before anything moves. A balance that is too small
     * and one that is large enough but still locked are told apart, so a player is given the real
     * reason.
     */
    public static Result affordable(EconomySavedData ledger, AccountId first, AccountId second, Terms terms) {
        Result firstCheck = affordable(ledger, first, terms.firstCredits(), Result.FIRST_SHORT, Result.FIRST_LOCKED);
        if (!firstCheck.ok()) return firstCheck;
        return affordable(ledger, second, terms.secondCredits(), Result.SECOND_SHORT, Result.SECOND_LOCKED);
    }

    private static Result affordable(EconomySavedData ledger, AccountId account, long credits, Result tooLittle,
            Result locked) {
        UUID player = Accounts.player(account);
        if (credits <= 0L) return Result.OK;
        if (player == null || ledger.getBalance(player, NeroEconomyCommon.CREDITS) < credits) return tooLittle;
        return ledger.spendable(player) < credits ? locked : Result.OK;
    }

    /**
     * Makes the swap, or does nothing.
     *
     * @param firstGives     the lots the trade says the first side put in; the escrow must hold
     *                       exactly these or nothing moves
     * @param firstReceiver  where the goods the first side receives go; must never lose them
     */
    public static Outcome execute(EconomyStore store, TradeEnv env, AccountId first, long firstCredits,
            List<Lot> firstGives, GoodsReceiver firstReceiver, AccountId second, long secondCredits,
            List<Lot> secondGives, GoodsReceiver secondReceiver) {
        if (EconomyHealth.frozen()) return Outcome.refused(Result.FROZEN);
        if (store == null || env == null || first == null || second == null || first.equals(second)
                || firstReceiver == null || secondReceiver == null || firstGives == null || secondGives == null
                || firstCredits < 0L || secondCredits < 0L || firstCredits > TradeSession.MAX_CREDITS
                || secondCredits > TradeSession.MAX_CREDITS) return Outcome.refused(Result.LEDGER);
        TradeEscrowSection escrow = store.tradeEscrow();
        // The escrow is the record of what each side put in. If it does not hold what the players
        // were shown when they confirmed, nobody agreed to this swap.
        if (!sameGoods(escrow.held(first), firstGives) || !sameGoods(escrow.held(second), secondGives))
            return Outcome.refused(Result.ESCROW_CHANGED);
        Terms terms = terms(firstCredits, secondCredits, env.settings().tradeTaxPercent());
        Result affordable = affordable(store.ledger(), first, second, terms);
        if (!affordable.ok()) return Outcome.refused(affordable);
        Result paid = pay(store, first, second, terms);
        if (!paid.ok()) return Outcome.refused(paid);
        // The credits have moved; nothing below can refuse. Each lot leaves escrow before it is
        // handed over, so it is never in two places.
        List<Lot> fromFirst = escrow.release(first);
        List<Lot> fromSecond = escrow.release(second);
        for (Lot lot : fromFirst) secondReceiver.give(lot);
        for (Lot lot : fromSecond) firstReceiver.give(lot);
        return new Outcome(Result.OK, terms, fromFirst, fromSecond);
    }

    private static Result pay(EconomyStore store, AccountId first, AccountId second, Terms terms) {
        List<LedgerPlan> plans = plans(first, second, terms);
        if (plans.isEmpty()) return Result.OK;
        LedgerResult moved = store.execute(plans.get(0));
        // One plan for both sides says nothing about which side it was refused for.
        if (!moved.ok()) return reason(moved, plans.size() == 1 ? null : Side.FIRST);
        if (plans.size() == 1) return Result.OK;
        LedgerResult rest = store.execute(plans.get(1));
        if (rest.ok()) return Result.OK;
        // The first side's credits go straight back: the second side has just received them, and
        // the Reserve its tax, so this cannot be short. Should it fail all the same, say so.
        if (!store.execute(undo(first, second, terms.firstCredits(), terms.firstTax())).ok())
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] A direct trade's credits could not be moved for one side "
                    + "and the other side's could not be put back; the ledger still balances, but one payment stands.");
        return reason(rest, Side.SECOND);
    }

    private static Result reason(LedgerResult result, Side payer) {
        if (result == LedgerResult.FROZEN) return Result.FROZEN;
        if (payer == null) return Result.LEDGER;
        return switch (result) {
            case FUNDS_LOCKED -> payer == Side.FIRST ? Result.FIRST_LOCKED : Result.SECOND_LOCKED;
            case INSUFFICIENT_FUNDS -> payer == Side.FIRST ? Result.FIRST_SHORT : Result.SECOND_SHORT;
            default -> Result.LEDGER;
        };
    }

    /**
     * Hands everything one account has in escrow back to it: what a cancel does.
     *
     * @return what was handed back
     */
    public static List<Lot> giveBack(EconomyStore store, AccountId owner, GoodsReceiver receiver) {
        if (store == null || owner == null || receiver == null) return List.of();
        List<Lot> lots = store.tradeEscrow().release(owner);
        for (Lot lot : lots) receiver.give(lot);
        return lots;
    }

    /** Whether two lists hold the same goods in the same quantities, in whatever stacks and order. */
    static boolean sameGoods(List<Lot> held, List<Lot> expected) {
        Map<Lot.Key, Long> balance = new HashMap<>();
        for (Lot lot : held) balance.merge(lot.key(), (long) lot.count(), Long::sum);
        for (Lot lot : expected) {
            if (lot == null) return false;
            balance.merge(lot.key(), (long) -lot.count(), Long::sum);
        }
        for (long left : balance.values()) if (left != 0L) return false;
        return true;
    }
}
