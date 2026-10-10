package za.co.neroland.neroeconomy.api;

import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.sibling.SiblingPayments;

/**
 * One other mod's handle on the ledger, claimed once per mod id at init through
 * {@link SiblingEconomy#claim}. Every call is server-side, refuses before anything moves, and
 * reports what happened as an {@link EconomyResult}.
 *
 * <p>A {@code reason} must be one of the codes the caller claimed; it is written on the ledger row
 * as {@code <modid>/<reason>} so an operator can see which mod moved what. A {@code requestKey}
 * makes a call idempotent for 24 economy-hours: the same key again answers {@code DUPLICATE} and
 * moves nothing. It is 1 to 64 characters of {@code [A-Za-z0-9_.:/#-]} and must never hold a player
 * id or name (a key that reads as a UUID is refused). Null means no replay protection, for a
 * caller that already guarantees once-only.
 *
 * <p>Privacy: pass the player id you already hold; nothing here returns another player's data.
 */
public final class EconomyCaller {
    private final String modId;
    private final Set<String> reasons;

    EconomyCaller(String modId, Set<String> reasons) {
        this.modId = modId;
        this.reasons = Set.copyOf(reasons);
    }

    public String modId() { return modId; }

    /** The reason codes this caller claimed (a copy). */
    public Set<String> reasons() { return reasons; }

    /**
     * Reserve to player, 1 to {@code siblingMaxSinglePayout} credits, counted against this caller's
     * daily limit, the limit all other mods share and the per-player daily cap. No partial payment:
     * a batch caller loops and stops at the first refusal.
     */
    public EconomyResult pay(MinecraftServer server, UUID player, long amount, String reason, String requestKey) {
        return SiblingPayments.pay(store(server), modId, reasons, player, amount, reason, requestKey);
    }

    /** Player to Reserve (a sink). A starting balance that has not vested may be spent here. */
    public EconomyResult charge(MinecraftServer server, UUID player, long amount, String reason, String requestKey) {
        return SiblingPayments.charge(store(server), modId, reasons, player, amount, reason, requestKey);
    }

    /**
     * Payer to payee: the payee receives {@code amount - feeFor(amount)} and the fee goes to the
     * Reserve, in one operation. Never funded by the Reserve; a starting balance that has not vested
     * cannot be used ({@code FUNDS_LOCKED}). Payer and payee must differ.
     */
    public EconomyResult transfer(MinecraftServer server, UUID payer, UUID payee, long amount, String reason,
            String requestKey) {
        return SiblingPayments.transfer(store(server), modId, reasons, payer, payee, amount, reason, requestKey);
    }

    /**
     * Player to one of this caller's own accounts ({@link ForeignAccountId} with {@code kind} =
     * {@link #modId()}), such as a faction treasury. A starting balance that has not vested cannot
     * be used. Any other account is {@code INVALID}.
     */
    public EconomyResult deposit(MinecraftServer server, UUID player, AccountId to, long amount, String reason,
            String requestKey) {
        return SiblingPayments.deposit(store(server), modId, reasons, player, to, amount, reason, requestKey);
    }

    /** One of this caller's own accounts to a player. Never funded by the Reserve. */
    public EconomyResult withdraw(MinecraftServer server, AccountId from, UUID player, long amount, String reason,
            String requestKey) {
        return SiblingPayments.withdraw(store(server), modId, reasons, from, player, amount, reason, requestKey);
    }

    /** What one of this caller's own accounts holds; 0 for an account of another kind. */
    public long balance(MinecraftServer server, AccountId account) {
        return SiblingPayments.balance(store(server), modId, account);
    }

    /** Whether {@link #pay} of {@code amount} would pass the Reserve and budget checks now; nothing moves or is counted. */
    public EconomyResult canPay(MinecraftServer server, UUID player, long amount) {
        return SiblingPayments.canPay(store(server), modId, player, amount);
    }

    /** What this caller may still pay today: its own remaining limit, the shared one and the Reserve's balance, whichever is least. */
    public long remainingToday(MinecraftServer server) {
        return SiblingPayments.remainingToday(store(server), modId);
    }

    /** The fee {@link #transfer} takes on {@code amount}: {@code siblingTransferFeePercent}, rounded up, at least 1 when above 0 %. */
    public long feeFor(long amount) { return SiblingPayments.feeFor(amount); }

    /** The economy store of {@code server}, or null unless it is the running server. */
    static EconomyStore store(MinecraftServer server) {
        return server != null && server == NeroEconomyCommon.CURRENCY_PROVIDER.serverOrNull()
                ? EconomyStore.get(server) : null;
    }
}
