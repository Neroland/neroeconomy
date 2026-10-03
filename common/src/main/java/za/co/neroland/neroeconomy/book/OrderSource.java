package za.co.neroland.neroeconomy.book;

/**
 * Where an order came from. Constants are only ever appended: the name is the saved form.
 *
 * <ul>
 *   <li>{@link #HAND} — placed by a player in game, the goods of a sell order taken from their hand;</li>
 *   <li>{@link #VAULT} — placed with goods from, or for delivery to, the owner's vault (a command,
 *       the companion app);</li>
 *   <li>{@link #RULE} — placed and kept up by one of the owner's auto-trade rules;</li>
 *   <li>{@link #HOUSE} — placed by a Merchant House, for a system account.</li>
 * </ul>
 */
public enum OrderSource {
    HAND, VAULT, RULE, HOUSE;

    /** The source a saved name stands for; an unknown name is an ordinary order. */
    public static OrderSource parse(String name) {
        for (OrderSource value : values()) if (value.name().equals(name)) return value;
        return HAND;
    }
}
