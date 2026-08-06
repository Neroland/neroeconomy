package za.co.neroland.neroeconomy.api;

import java.util.UUID;

/** The current player-backed ledger account. */
public record PlayerAccountId(UUID playerId) implements AccountId {
    public PlayerAccountId {
        if (playerId == null) throw new IllegalArgumentException("playerId");
    }
    @Override public String kind() { return "player"; }
    @Override public String value() { return playerId.toString(); }
}
