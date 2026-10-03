package za.co.neroland.neroeconomy.menu;

import java.util.Locale;

/**
 * The checks a Bank Terminal payment passes before anything reaches the ledger. The ledger opens
 * (and funds) an account the first time it is written to, so every refusal here happens before the
 * transfer call: an unknown recipient or a short balance must not leave an account or an audit row
 * behind. Pure Java so the order of the checks is unit tested directly.
 */
public final class BankTransferRules {
    public enum Check {
        OK, INVALID_AMOUNT, UNKNOWN_RECIPIENT, SELF, INSUFFICIENT_FUNDS,
        /** The sender has the credits, but part of them is a starting balance that has not vested yet. */
        FUNDS_LOCKED;

        /** Lang key of the message shown to the sender; unused for {@link #OK}. */
        public String translationKey() {
            return "gui.neroeconomy.bank.refused." + name().toLowerCase(Locale.ROOT);
        }
    }

    private BankTransferRules() { }

    /**
     * @param amount         credits the sender asked to move
     * @param recipientKnown whether the recipient resolved to an account this world already knows
     * @param self           whether the recipient is the sender
     * @param balance        the sender's balance right now
     */
    public static Check check(long amount, boolean recipientKnown, boolean self, long balance) {
        return check(amount, recipientKnown, self, balance, balance);
    }

    /**
     * @param spendable the part of {@code balance} that may be paid to another player: everything
     *                  except a starting balance that has not vested yet
     */
    public static Check check(long amount, boolean recipientKnown, boolean self, long balance, long spendable) {
        if (amount < 1L) return Check.INVALID_AMOUNT;
        if (!recipientKnown) return Check.UNKNOWN_RECIPIENT;
        if (self) return Check.SELF;
        if (balance < amount) return Check.INSUFFICIENT_FUNDS;
        if (spendable < amount) return Check.FUNDS_LOCKED;
        return Check.OK;
    }
}
