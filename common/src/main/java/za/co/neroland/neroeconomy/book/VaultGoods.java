package za.co.neroland.neroeconomy.book;

import java.util.List;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.trade.TradeEnv;

/**
 * An account's vault as the place a sale's goods come from, and as the place a purchase's goods
 * go. Pure: lots in, lots out, against the store's vault section.
 */
public final class VaultGoods {
    private VaultGoods() { }

    /** Goods taken from {@code account}'s vault. */
    public static GoodsOrigin origin(EconomyStore store, TradeEnv env, AccountId account) {
        return new GoodsOrigin() {
            @Override public boolean holds(Lot lot) {
                return lot != null && store.vault().total(account, lot.item(), lot.payload()) >= lot.count();
            }
            @Override public boolean take(Lot lot) { return lot != null && store.vault().take(account, lot); }
            @Override public void restore(Lot lot) { deliver(store, env, account, lot); }
        };
    }

    /**
     * Goods delivered to {@code account}'s vault, as a player's own purchase: refused beforehand
     * when the vault could not take them under the trade cap.
     */
    public static GoodsReceiver receiver(EconomyStore store, TradeEnv env, AccountId account) {
        return new GoodsReceiver() {
            @Override public boolean canTake(Lot lot) {
                return lot != null && store.vault().canAccept(account, List.of(lot), env.settings().vaultTradeCap(),
                        env.limit());
            }
            @Override public boolean give(Lot lot) {
                deliver(store, env, account, lot);
                return true;
            }
        };
    }

    /** A system delivery to a vault: always booked, whatever the cap. */
    public static void deliver(EconomyStore store, TradeEnv env, AccountId account, Lot lot) {
        if (lot == null) return;
        var delivery = store.vault().deliver(account, List.of(lot), env.settings().vaultCap(),
                env.settings().vaultHardBound(), env.limit());
        // Booked anyway; the owner is told the vault is past its cap, at most once per alert window.
        if (delivery.overCap()) env.alerts().raise(account, za.co.neroland.neroeconomy.link.EconomyAlerts.Kind.VAULT_FULL);
    }
}
