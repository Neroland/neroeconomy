package za.co.neroland.neroeconomy.api;

import java.util.regex.Pattern;

/**
 * A ledger account another mod keeps, such as a faction treasury: {@code kind} is the mod id that
 * claimed an {@link EconomyCaller}, {@code value} its own id for the account. It holds real
 * credits, persists with the ledger and is never personal (its stable key is written to audit rows
 * as is). Credits move into and out of it only through that caller's
 * {@link EconomyCaller#deposit} and {@link EconomyCaller#withdraw}; a caller can only touch
 * accounts of its own kind. An account whose mod is gone stays in the ledger and is listed apart
 * by {@code /eco admin supply}.
 *
 * <p>Example: {@code new ForeignAccountId("nerofactions", "faction/ironclad")}.
 */
public record ForeignAccountId(String kind, String value) implements AccountId {
    private static final Pattern KIND = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern VALUE = Pattern.compile("[a-z0-9_.:/-]{1,64}");

    public ForeignAccountId {
        if (!validKind(kind) || !validValue(value)) throw new IllegalArgumentException("Invalid foreign account");
    }

    /** A mod id that is neither this mod's nor one of the built-in kinds. */
    public static boolean validKind(String kind) {
        return kind != null && KIND.matcher(kind).matches() && !kind.equals("player") && !kind.equals(SystemAccountId.KIND)
                && !kind.equals("neroeconomy") && !kind.equals("unknown");
    }

    /** {@code [a-z0-9_.:/-]{1,64}}: an id, never a name or free text. */
    public static boolean validValue(String value) { return value != null && VALUE.matcher(value).matches(); }

    /** The account a stored kind and value name, or null when they are not a valid foreign account. */
    public static ForeignAccountId parse(String kind, String value) {
        return validKind(kind) && validValue(value) ? new ForeignAccountId(kind, value) : null;
    }
}
