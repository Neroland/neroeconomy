package za.co.neroland.neroeconomy.rules;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.trade.TradeEnv;

/**
 * The owner's vault as a rule's inventory. It is always reachable — which is what lets a
 * vault-backed rule keep trading with its owner offline and the server empty. Goods put back
 * always book, even past the vault's cap; {@link #room} is what a rule may still <em>buy</em>
 * into it, and that respects the cap.
 */
public final class VaultRuleInventory implements RuleInventory {
    private final EconomyStore store;
    private final TradeEnv env;
    private final AccountId owner;

    public VaultRuleInventory(EconomyStore store, TradeEnv env, AccountId owner) {
        this.store = store;
        this.env = env;
        this.owner = owner;
    }

    @Override public boolean reachable() { return true; }

    @Override public long available(String item) { return store.vault().total(owner, item, Lot.PRISTINE); }

    @Override public Map<String, Long> goods() {
        Map<String, Long> out = new TreeMap<>();
        store.vault().totals(owner).forEach((key, count) -> { if (key.pristine()) out.merge(key.item(), count, Long::sum); });
        return out;
    }

    @Override public boolean take(Lot lot) { return lot != null && lot.pristine() && store.vault().take(owner, lot); }

    @Override public void put(Lot lot) { VaultGoods.deliver(store, env, owner, lot); }

    /** Free stacks under the trade cap, plus the space left in stacks of the same goods. */
    @Override public long room(String item) {
        List<Lot> stacks = store.vault().stacks(owner);
        int cap = env.settings().vaultTradeCap();
        if (stacks.size() > cap) return 0L;
        int perStack = Math.max(1, env.limit().limit(item, Lot.PRISTINE));
        long room = (long) (cap - stacks.size()) * perStack;
        for (Lot stack : stacks) if (stack.is(item, Lot.PRISTINE) && stack.count() < perStack) room += perStack - stack.count();
        return room;
    }
}
