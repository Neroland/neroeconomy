package za.co.neroland.neroeconomy.rules;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.trade.TradeEnv;

/**
 * Turns the binding a rule was saved with into the inventory it works from. The vault binding
 * ({@link RulesSection#VAULT}) is built in; anything else — an Auto-Trader block at a position —
 * is resolved by whoever plugs a binder in ({@code Trading.ruleBinder}). A binding is an opaque
 * string to the rules section: it only stores it and hands it back here.
 */
@FunctionalInterface
public interface RuleBinder {
    /**
     * @return the inventory behind {@code binding} for a rule of {@code owner}, or null when this
     *         binder does not know the binding at all (the rule then cannot be created, and an
     *         existing one reports itself unreachable)
     */
    RuleInventory resolve(EconomyStore store, TradeEnv env, AccountId owner, String binding);
}
