package za.co.neroland.neroeconomy.guard;

import java.util.List;
import java.util.Set;

/**
 * What the arbitrage guard found. A report with no failures means that, at the prices it was
 * given, nothing the Merchant Houses sell can be turned into something they buy — or into Credit
 * Chips — for more than it cost.
 *
 * @param failures       everything that failed, in a fixed order
 * @param buyOff         the keys of the commodities whose NPC buy side must be switched off
 * @param sellOff        the keys of the commodities whose NPC sell side must be switched off
 * @param commodities    how many commodities were checked
 * @param recipesRead    recipes the guard could read (known conversions included)
 * @param recipesSkipped recipe files it could not read: special crafting and unknown shapes
 * @param chainsChecked  how many recipes had every input available from the Houses, directly or
 *                       through earlier steps, and an output the Houses buy or a Credit Chip, and
 *                       so were compared against what that output is worth
 * @param depth          the most steps a chain was followed for
 */
public record GuardReport(List<Failure> failures, Set<String> buyOff, Set<String> sellOff, int commodities,
        int recipesRead, int recipesSkipped, int chainsChecked, int depth) {
    /** No check has run. */
    public static final GuardReport NONE = new GuardReport(List.of(), Set.of(), Set.of(), 0, 0, 0, 0, 0);

    public GuardReport {
        failures = List.copyOf(failures);
        buyOff = Set.copyOf(buyOff);
        sellOff = Set.copyOf(sellOff);
    }

    public boolean passed() { return failures.isEmpty(); }

    /** How many failures are of this kind. */
    public int count(Kind kind) {
        int total = 0;
        for (Failure failure : failures) if (failure.kind() == kind) total++;
        return total;
    }

    /** What went wrong. */
    public enum Kind {
        /** A commodity's NPC ask is not above its NPC bid: buying and selling straight back pays. */
        SPREAD,
        /** A recipe, or a chain of them, turns goods bought from the Houses into goods they buy for at least as much. */
        CHAIN,
        /** Goods bought from the Houses craft into something redeemable (a Credit Chip) for at least as much. */
        REDEEM,
        /** Two commodities claimed the same item; the first in file order kept it. */
        CLASH
    }

    /**
     * One failure.
     *
     * @param kind      what went wrong
     * @param commodity the key of the commodity that fails; for {@link Kind#REDEEM} the redeemable
     *                  item; for {@link Kind#CLASH} the commodity that lost the item
     * @param recipe    the id of the last recipe of the chain; for {@link Kind#CLASH} the item both
     *                  claimed; empty for {@link Kind#SPREAD}
     * @param cost      what one operation's inputs cost at the Houses' ask, in credits to two
     *                  decimal places; for {@link Kind#SPREAD} the ask; empty for {@link Kind#CLASH}
     * @param revenue   what one operation's output brings at the Houses' bid; for
     *                  {@link Kind#SPREAD} the bid; empty for {@link Kind#CLASH}
     * @param steps     how many recipes the chain is long; 0 when no recipe is involved
     * @param other     for {@link Kind#CLASH} the key of the commodity that kept the item; otherwise empty
     */
    public record Failure(Kind kind, String commodity, String recipe, String cost, String revenue, int steps,
            String other) {
        /** The failure in plain words, for a test's message. Carries data-pack ids and prices only. */
        public String describe() {
            return switch (kind) {
                case SPREAD -> commodity + ": the Houses sell it for " + cost + " and buy it for " + revenue
                        + ", so selling it straight back does not lose";
                case CHAIN -> commodity + ": recipe " + recipe + " turns goods bought for " + cost
                        + " into goods sold for " + revenue + " (" + steps + (steps == 1 ? " step)" : " steps)");
                case REDEEM -> commodity + ": recipe " + recipe + " turns goods bought for " + cost
                        + " into something worth " + revenue + " (" + steps + (steps == 1 ? " step)" : " steps)");
                case CLASH -> recipe + " is claimed by " + other + " and by " + commodity + "; " + other + " keeps it";
            };
        }
    }
}
