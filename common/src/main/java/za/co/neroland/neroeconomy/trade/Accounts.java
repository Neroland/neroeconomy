package za.co.neroland.neroeconomy.trade;

import java.util.UUID;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;

/** The saved form of an account ({@code player:<uuid>}, {@code system:<name>}) and back. Pure Java. */
public final class Accounts {
    private Accounts() { }

    public static AccountId of(UUID player) { return player == null ? null : new PlayerAccountId(player); }

    /** The player behind an account, or null for a system account. */
    public static UUID player(AccountId account) {
        return account instanceof PlayerAccountId player ? player.playerId() : null;
    }

    public static boolean system(AccountId account) { return account instanceof SystemAccountId; }

    /** The account a stored key names, or null when it names none this build knows. */
    public static AccountId parse(String key) {
        if (key == null) return null;
        int separator = key.indexOf(':');
        if (separator < 1) return null;
        String kind = key.substring(0, separator);
        String value = key.substring(separator + 1);
        if (SystemAccountId.KIND.equals(kind)) return SystemAccountId.parse(value);
        if (!"player".equals(kind)) return null;
        try { return new PlayerAccountId(UUID.fromString(value)); }
        catch (IllegalArgumentException malformed) { return null; }
    }
}
