package za.co.neroland.neroeconomy.vault;

/**
 * The rule a vault screen applies to a slot that still holds the same goods it held when the slots
 * and the store last agreed. Free of the game so it is unit tested directly.
 */
public final class VaultSlotRule {
    private VaultSlotRule() { }

    /**
     * How many of the goods may stay in the slot; the rest is handed back to the player.
     *
     * @param agreed   how many the slot held when it last agreed with the store
     * @param seen     how many it holds now
     * @param accepted whether the stack may be put into the vault at all now (not from a creative,
     *                 spectator or endless-supply player, tradeable, the economy not frozen)
     */
    public static int mayStay(int agreed, int seen, boolean accepted) {
        // As many or fewer: the goods already here, in whatever quantity is left of them. More: what was
        // added is new goods, and stays only if it may enter the vault at all.
        return seen <= agreed || accepted ? seen : agreed;
    }
}
