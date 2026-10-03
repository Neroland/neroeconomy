package za.co.neroland.neroeconomy.obligation;

import java.util.UUID;

/**
 * Escrow-backed server job. Empty claimant means unclaimed. {@code claimedAt} is the economy time
 * the current claim was made, which drives the claim timeout; 0 means unclaimed, or claimed by a
 * build that did not record it.
 */
public record EconomyJob(UUID id, JobType type, UUID issuer, String claimant, String target,
        int quantity, String destination, long payout, long expiresAt, JobState state, long claimedAt) {
    public EconomyJob {
        if (id == null || type == null || issuer == null || claimant == null || target == null
                || quantity < 1 || destination == null || payout < 1L || expiresAt < 1L || state == null)
            throw new IllegalArgumentException("Invalid economy job");
        if (claimedAt < 0L) claimedAt = 0L;
    }
    /** A job whose claim time is not recorded. */
    public EconomyJob(UUID id, JobType type, UUID issuer, String claimant, String target,
            int quantity, String destination, long payout, long expiresAt, JobState state) {
        this(id, type, issuer, claimant, target, quantity, destination, payout, expiresAt, state, 0L);
    }
    public UUID claimantId() {
        try { return claimant.isBlank() ? null : UUID.fromString(claimant); }
        catch (IllegalArgumentException ignored) { return null; }
    }
    public EconomyJob claimedBy(UUID player, long at) {
        return new EconomyJob(id, type, issuer, player.toString(), target, quantity, destination, payout,
                expiresAt, JobState.CLAIMED, at);
    }
    /** The same job with no claimant, open to be claimed again. */
    public EconomyJob reopened() {
        return new EconomyJob(id, type, issuer, "", target, quantity, destination, payout, expiresAt,
                JobState.OPEN, 0L);
    }
    public EconomyJob withClaimedAt(long at) {
        return new EconomyJob(id, type, issuer, claimant, target, quantity, destination, payout, expiresAt,
                state, at);
    }
    public EconomyJob withState(JobState next) {
        return new EconomyJob(id, type, issuer, claimant, target, quantity, destination, payout, expiresAt,
                next, claimedAt);
    }
}
