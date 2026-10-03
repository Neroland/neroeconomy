package za.co.neroland.neroeconomy.rules;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.trade.Trading;

/**
 * Auto-trade rules as the rest of the game reaches them: the edge between the pure
 * {@link RulesSection} and the commands, the rule screens and the companion app. A rule works
 * from the store alone, so none of these needs its owner in game. Each value-moving call checks
 * the frozen flag and records its request id in the shared request ledger first.
 */
public final class RuleStacks {
    private RuleStacks() { }

    private static AccountId account(UUID player) { return new PlayerAccountId(player); }

    /**
     * Creates a rule for {@code owner}. A buy rule's whole budget is escrowed here.
     *
     * @param tag     whether {@code target} is an item tag, given without the leading {@code #}
     * @param total   a buy rule's budget in credits; a sell rule's quantity
     * @param binding the inventory the rule works from; {@link RulesSection#VAULT} for the owner's vault
     */
    public static RuleOutcome add(MinecraftServer server, UUID owner, RuleSide side, boolean tag, String target,
            long limitPrice, int perDay, long total, String binding, UUID requestId) {
        if (EconomyHealth.frozen()) return RuleOutcome.refused(RuleResult.FROZEN);
        if (server == null || owner == null || requestId == null) return RuleOutcome.refused(RuleResult.INVALID);
        EconomyStore store = EconomyStore.get(server);
        if (!store.market().rememberRequest(owner, requestId)) return RuleOutcome.refused(RuleResult.STALE_REQUEST);
        return store.rules().add(Trading.env(store), account(owner), side, tag, target, limitPrice, perDay, total,
                binding, null);
    }

    /** Raises a rule's total: more budget (escrowed now) for a buy rule, more quantity for a sell rule. */
    public static RuleOutcome raise(MinecraftServer server, UUID owner, UUID ruleId, long extra, UUID requestId) {
        if (EconomyHealth.frozen()) return RuleOutcome.refused(RuleResult.FROZEN);
        if (server == null || owner == null || requestId == null) return RuleOutcome.refused(RuleResult.INVALID);
        EconomyStore store = EconomyStore.get(server);
        if (!store.market().rememberRequest(owner, requestId)) return RuleOutcome.refused(RuleResult.STALE_REQUEST);
        return store.rules().raise(Trading.env(store), account(owner), ruleId, extra);
    }

    /** Deletes a rule and pays what is left of its budget back. Idempotent: a second call finds nothing. */
    public static RuleOutcome delete(MinecraftServer server, UUID owner, UUID ruleId) {
        if (EconomyHealth.frozen()) return RuleOutcome.refused(RuleResult.FROZEN);
        if (server == null || owner == null) return RuleOutcome.refused(RuleResult.NOT_FOUND);
        EconomyStore store = EconomyStore.get(server);
        return store.rules().delete(Trading.env(store), account(owner), ruleId);
    }

    /** Pauses or resumes one rule. */
    public static RuleOutcome setPaused(MinecraftServer server, UUID owner, UUID ruleId, boolean paused) {
        if (EconomyHealth.frozen()) return RuleOutcome.refused(RuleResult.FROZEN);
        if (server == null || owner == null) return RuleOutcome.refused(RuleResult.NOT_FOUND);
        EconomyStore store = EconomyStore.get(server);
        return store.rules().setPaused(Trading.env(store), account(owner), ruleId, paused);
    }

    /** The "pause all my rules" switch. @return how many rules the player has */
    public static int setPausedAll(MinecraftServer server, UUID owner, boolean paused) {
        if (server == null || owner == null || EconomyHealth.frozen()) return 0;
        EconomyStore store = EconomyStore.get(server);
        return store.rules().setPausedAll(Trading.env(store), account(owner), paused);
    }
}
