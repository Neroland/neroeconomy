package za.co.neroland.neroeconomy.rules;

import java.util.Map;

import za.co.neroland.neroeconomy.goods.Lot;

/**
 * The inventory an auto-trade rule works from: where a sell rule's goods come from and where a
 * buy rule's goods go. The owner's vault is one ({@link VaultRuleInventory}); an Auto-Trader block
 * beside a container is another, plugged in through {@link RuleBinder}.
 *
 * <p>A rule only ever deals in unmodified goods, so every count here is of unmodified items.
 */
public interface RuleInventory {
    /**
     * Whether the inventory can be used right now. A block whose chunk is not loaded cannot — and
     * a rule never loads one: it pauses and says so.
     */
    boolean reachable();

    /** How many unmodified {@code item} it holds. */
    long available(String item);

    /** Every unmodified item it holds, with how many, by item id. */
    Map<String, Long> goods();

    /** Takes exactly {@code lot}. @return false, with nothing taken, when it holds fewer */
    boolean take(Lot lot);

    /**
     * Puts goods in. Must never lose them: what does not fit is the implementation's to keep safe
     * (a vault books past its cap; a block hands the rest to its owner's vault).
     */
    void put(Lot lot);

    /** How many more unmodified {@code item} it has room for right now. */
    long room(String item);
}
