package za.co.neroland.neroeconomy.api;

/**
 * A ledger account that belongs to the economy itself rather than to a player. System accounts
 * hold real balances: credits never leave the ledger, they sit in one of these until they are
 * delivered. They are not personal data, so audit rows name them by {@link #stableKey()}.
 *
 * <ul>
 *   <li>{@link #RESERVE} — the pool every system payout comes from and every fee is paid into;</li>
 *   <li>{@link #ESCROW} — credits promised but not yet delivered;</li>
 *   <li>{@link #EXCHANGE} — principal of open exchange positions;</li>
 *   <li>{@link #house(String)} — one Merchant House's working capital.</li>
 * </ul>
 */
public record SystemAccountId(String value) implements AccountId {
    /** The {@link #kind()} of every system account. */
    public static final String KIND = "system";
    private static final String HOUSE_PREFIX = "house/";
    private static final int MAX_HOUSE_ID_LENGTH = 64;

    public static final SystemAccountId RESERVE = new SystemAccountId("reserve");
    public static final SystemAccountId ESCROW = new SystemAccountId("escrow");
    public static final SystemAccountId EXCHANGE = new SystemAccountId("exchange");

    public SystemAccountId {
        if (!valid(value)) throw new IllegalArgumentException("Invalid system account");
    }

    /** The working-capital account of the Merchant House {@code id} (lower-case letters, digits, {@code _ . -}). */
    public static SystemAccountId house(String id) {
        return new SystemAccountId(HOUSE_PREFIX + id);
    }

    /** The account a stored value names, or null when it is not a system account this build knows. */
    public static SystemAccountId parse(String value) {
        return valid(value) ? new SystemAccountId(value) : null;
    }

    public boolean isHouse() { return value.startsWith(HOUSE_PREFIX); }

    @Override public String kind() { return KIND; }

    private static boolean valid(String value) {
        if (value == null) return false;
        if (value.equals("reserve") || value.equals("escrow") || value.equals("exchange")) return true;
        if (!value.startsWith(HOUSE_PREFIX)) return false;
        int length = value.length() - HOUSE_PREFIX.length();
        if (length < 1 || length > MAX_HOUSE_ID_LENGTH) return false;
        for (int index = HOUSE_PREFIX.length(); index < value.length(); index++) {
            char c = value.charAt(index);
            boolean allowed = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '.' || c == '-';
            if (!allowed) return false;
        }
        return true;
    }
}
