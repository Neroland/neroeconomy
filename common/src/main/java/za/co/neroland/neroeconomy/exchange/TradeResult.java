package za.co.neroland.neroeconomy.exchange;

import java.util.Locale;

import za.co.neroland.neroeconomy.economy.EconomyHealth;

/**
 * Why a trade on the exchange did or did not happen. Nothing has moved unless the result is
 * {@link #OK}. Every one has wording ({@link #translationKey()}); {@link #GAIN_REDUCED} and
 * {@link #GAIN_CHANGED} take two arguments — what can be paid of the gain, then the whole gain —
 * and the others take none.
 */
public enum TradeResult {
    OK,
    /** A number or an id the rules do not accept. */
    INVALID,
    /** The request id was already acted on. */
    STALE_REQUEST,
    /** The economy is frozen for this session. */
    FROZEN,
    /** {@code exchangeEnabled} is false. */
    EXCHANGE_OFF,
    /** The account has made too many exchange actions in the last minute. */
    TOO_FAST,
    /** No stock with that id is listed. */
    UNKNOWN_STOCK,
    /** The company is no longer listed; its holders are paid out by the exchange itself. */
    DELISTED,
    /** Trading in the stock is halted: its price reached the daily limit. */
    HALTED,
    INSUFFICIENT_FUNDS,
    /** The amount does not buy a single unit. */
    TOO_SMALL,
    /** The purchase would take the position in this stock past {@code maxPositionPerStock}. */
    POSITION_LIMIT,
    /** The purchase would take the portfolio past {@code maxPortfolio}. */
    PORTFOLIO_LIMIT,
    /** Too many recent purchases of this stock are still inside the holding period. */
    SETTLING,
    /** The price moved, and the purchase would now cost more than was agreed to. */
    PRICE_MOVED,
    /** The account holds none of the stock. */
    NOTHING_HELD,
    /** The account holds fewer units than it asked to sell. */
    NOT_ENOUGH_UNITS,
    /** Some of the units are still inside the holding period. */
    HOLDING_PERIOD,
    /** The Reserve or today's exchange budget cannot pay the whole gain, and the lower amount was not accepted. */
    GAIN_REDUCED,
    /** What can be paid of the gain is now less than the lower amount that was accepted. */
    GAIN_CHANGED,
    /** The Reserve or today's exchange budget cannot reserve the bond's interest. */
    BONDS_SOLD_OUT,
    /** The account already holds {@code maxBondsPerPlayer} bonds. */
    BOND_LIMIT,
    /** No bond of the account has that id. */
    BOND_NOT_FOUND,
    /** The exchange keeps as many portfolios as it can. */
    EXCHANGE_FULL,
    /** A practice portfolio can be reset once per economy-day. */
    PRACTICE_RESET_USED,
    /** The ledger refused the movement; nothing moved. */
    NOT_COMPLETED;

    public boolean ok() { return this == OK; }

    /** Lang key of the message shown to the player. */
    public String translationKey() {
        if (this == FROZEN) return EconomyHealth.FROZEN_KEY;
        return "message.neroeconomy.invest." + name().toLowerCase(Locale.ROOT);
    }
}
