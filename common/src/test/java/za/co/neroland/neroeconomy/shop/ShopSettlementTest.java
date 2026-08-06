package za.co.neroland.neroeconomy.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ShopSettlementTest {
    @Test void successfulSettlementChargesDeliversThenConsumes() {
        AtomicInteger steps = new AtomicInteger();
        assertTrue(ShopSettlement.settle(10, () -> steps.compareAndSet(0, 1),
                () -> steps.compareAndSet(1, 2), () -> assertTrue(steps.compareAndSet(2, 3)),
                () -> steps.set(-1)));
        assertEquals(3, steps.get());
    }

    @Test void failedDeliveryRefundsWithoutConsumingStock() {
        AtomicInteger refunds = new AtomicInteger();
        AtomicInteger consumed = new AtomicInteger();
        assertFalse(ShopSettlement.settle(10, () -> true, () -> false, consumed::incrementAndGet,
                refunds::incrementAndGet));
        assertEquals(0, consumed.get());
        assertEquals(1, refunds.get());
    }
}
