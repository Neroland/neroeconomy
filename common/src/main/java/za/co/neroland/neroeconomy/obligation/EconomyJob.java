package za.co.neroland.neroeconomy.obligation;

import java.util.UUID;

/** Escrow-backed server job. Empty claimant means unclaimed. */
public record EconomyJob(UUID id, JobType type, UUID issuer, String claimant, String target,
        int quantity, String destination, long payout, long expiresAt, JobState state) {
    public EconomyJob {
        if (id == null || type == null || issuer == null || claimant == null || target == null
                || quantity < 1 || destination == null || payout < 1L || expiresAt < 1L || state == null)
            throw new IllegalArgumentException("Invalid economy job");
    }
    public UUID claimantId() {
        try { return claimant.isBlank() ? null : UUID.fromString(claimant); }
        catch (IllegalArgumentException ignored) { return null; }
    }
    public EconomyJob claimedBy(UUID player) {
        return new EconomyJob(id, type, issuer, player.toString(), target, quantity, destination, payout,
                expiresAt, JobState.CLAIMED);
    }
    public EconomyJob withState(JobState next) {
        return new EconomyJob(id, type, issuer, claimant, target, quantity, destination, payout, expiresAt, next);
    }
}
