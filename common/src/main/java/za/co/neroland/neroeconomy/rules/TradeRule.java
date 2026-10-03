package za.co.neroland.neroeconomy.rules;

import java.util.UUID;

import za.co.neroland.neroeconomy.api.AccountId;

/**
 * One auto-trade rule as a reader sees it: what it trades, its limits, how far it has got and the
 * one status it reports. A snapshot; the rules section holds the live rule.
 *
 * @param tag         whether {@code target} is an item tag (without the leading {@code #}) rather than an item id
 * @param limitPrice  the most a buy rule pays per unit, or the least a sell rule asks
 * @param perDay      how many it may trade per economy-day
 * @param total       a buy rule's whole budget in credits; a sell rule's whole quantity
 * @param binding     the inventory it works from: {@link RulesSection#VAULT}, or a block's binding
 * @param filledToday how many it has traded in the current economy-day
 * @param done        a buy rule: credits spent on purchases so far; a sell rule: how many sold so far
 * @param budget      a buy rule: credits still held in escrow for it and not committed to an order
 * @param orderId     the order it keeps on the book right now, or null
 */
public record TradeRule(UUID id, AccountId owner, RuleSide side, boolean tag, String target, long limitPrice,
        int perDay, long total, String binding, boolean paused, RuleStatus status, int filledToday, long done,
        long budget, UUID orderId, long createdAt) {
    public boolean buys() { return side == RuleSide.BUY; }
}
