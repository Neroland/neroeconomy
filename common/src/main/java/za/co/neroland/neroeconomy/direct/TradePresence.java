package za.co.neroland.neroeconomy.direct;

/**
 * Whether two players may trade with each other right now, from what the server observed about
 * them. Asked when a request is sent, when it is accepted, on every tick a trade is open and once
 * more at the swap. Pure Java, so the rule is unit tested directly.
 *
 * <p>Both must be in the game, alive and not spectating. Beyond that they must either stand
 * within {@code tradeRangeBlocks} of each other in the same level, or each be at an economy
 * terminal (with the Economy Hub or the vault open at one of the economy blocks) — which is what
 * lets two players trade from their own bases.
 */
public final class TradePresence {
    public enum Verdict {
        OK,
        /** A player is not in the game any more. */
        OFFLINE,
        DEAD,
        SPECTATING,
        /** Not within range of each other, and not both at a terminal. */
        TOO_FAR;

        public boolean ok() { return this == OK; }

        /** The reason a trade that was open ends with, or null for {@link #OK}. */
        public TradeSession.CancelReason cancelReason() {
            return switch (this) {
                case OK -> null;
                case OFFLINE -> TradeSession.CancelReason.DISCONNECTED;
                case DEAD -> TradeSession.CancelReason.DIED;
                case SPECTATING -> TradeSession.CancelReason.NOT_ALLOWED;
                case TOO_FAR -> TradeSession.CancelReason.OUT_OF_RANGE;
            };
        }
    }

    /**
     * What the server sees of one player.
     *
     * @param atTerminal whether they have an economy terminal's screen open, or are still within
     *                   reach of the terminal they opened the trade from
     */
    public record Party(boolean online, boolean alive, boolean spectator, boolean atTerminal) { }

    private TradePresence() { }

    /**
     * @param sameLevel   whether both are in the same level; the same coordinates in another
     *                    dimension do not count
     * @param distanceSqr the squared distance between them; only meaningful with {@code sameLevel}
     * @param rangeBlocks {@code tradeRangeBlocks}
     */
    public static Verdict check(Party first, Party second, boolean sameLevel, double distanceSqr, int rangeBlocks) {
        if (first == null || second == null || !first.online() || !second.online()) return Verdict.OFFLINE;
        if (!first.alive() || !second.alive()) return Verdict.DEAD;
        if (first.spectator() || second.spectator()) return Verdict.SPECTATING;
        if (first.atTerminal() && second.atTerminal()) return Verdict.OK;
        double range = Math.max(0, rangeBlocks);
        // A distance that is not a number is never within range.
        return sameLevel && distanceSqr >= 0.0D && distanceSqr <= range * range ? Verdict.OK : Verdict.TOO_FAR;
    }
}
