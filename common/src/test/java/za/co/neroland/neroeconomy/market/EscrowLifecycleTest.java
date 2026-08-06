package za.co.neroland.neroeconomy.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class EscrowLifecycleTest {
    @Test void createPartialFillFinalFillAndExpiryRefundAreConservative() {
        long escrow = EscrowLifecycle.create(10, 25);
        assertEquals(250, escrow);
        escrow = EscrowLifecycle.fill(escrow, 3, 25);
        assertEquals(175, escrow);
        assertEquals(175, EscrowLifecycle.expire(escrow));
        assertEquals(0, EscrowLifecycle.fill(escrow, 7, 25));
    }
    @Test void rejectsOverflowAndOverfill() {
        assertThrows(ArithmeticException.class, () -> EscrowLifecycle.create(2, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> EscrowLifecycle.fill(10, 2, 10));
    }
}
