package za.co.neroland.neroeconomy.shop;

import java.util.function.BooleanSupplier;

/** Small loader-free settlement coordinator used by the shop and its unit tests. */
public final class ShopSettlement {
    private ShopSettlement() { }

    public static boolean settle(long price, BooleanSupplier charge, BooleanSupplier deliver,
            Runnable consumeStock, Runnable refund) {
        if (price <= 0L || charge == null || deliver == null || consumeStock == null || refund == null
                || !charge.getAsBoolean()) {
            return false;
        }
        if (!deliver.getAsBoolean()) {
            refund.run();
            return false;
        }
        consumeStock.run();
        return true;
    }
}
