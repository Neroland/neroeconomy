package za.co.neroland.neroeconomy.economy;

import java.nio.file.Files;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Who may be named as the other side of a payment. {@code /eco pay}, {@code /eco balance} and the
 * Bank Terminal all resolve a recipient here, so no entry point is laxer than another.
 *
 * <p>The ledger opens an account, and mints its starting balance, the first time the account is
 * written to. Accepting any well-formed UUID would therefore let a payment open an unlimited number
 * of funded accounts for ids nobody has ever played as. A recipient is known only if they are
 * online, named either way, or have a player-data file in this world.
 */
public final class KnownAccounts {
    private KnownAccounts() { }

    /**
     * @param value an online player's name, or a UUID
     * @return the account's id, or null when {@code value} names nobody this world knows
     */
    public static UUID resolve(MinecraftServer server, String value) {
        if (server == null || value == null || value.isBlank()) return null;
        String name = value.trim();
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return online.getUUID();
        UUID id = parse(name);
        return id != null && (server.getPlayerList().getPlayer(id) != null || hasPlayerData(server, id)) ? id : null;
    }

    /** A UUID in its canonical text form, or null; anything else is at best a player name. */
    public static UUID parse(String value) {
        if (value == null) return null;
        try { return UUID.fromString(value.trim()); }
        catch (IllegalArgumentException notAnId) { return null; }
    }

    private static boolean hasPlayerData(MinecraftServer server, UUID player) {
        try {
            return Files.isRegularFile(server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
                    .resolve(player + ".dat"));
        } catch (RuntimeException ignored) { return false; }
    }
}
