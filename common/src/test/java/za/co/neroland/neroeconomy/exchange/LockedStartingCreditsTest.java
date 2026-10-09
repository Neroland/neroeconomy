package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.TransactionType;

/**
 * A1: a new account's locked (unvested) starting credits cannot be made payable to another player
 * through the exchange. Shares and bonds are bought with earned credits only, so what an early
 * redemption or a sale returns was never locked; locked credits still pay the exchange fee.
 */
class LockedStartingCreditsTest {
    private final ExchangeWorld exchange = new ExchangeWorld();

    @AfterEach void restore() {
        EconomyHealth.clear();
        exchange.close();
    }

    private EconomySavedData ledger() { return exchange.ledger(); }

    @Test void aBondBoughtWithLockedCreditsAndCashedInEarlyUnlocksNothing() {
        UUID alt = exchange.world.newcomer(1);
        assertEquals(100L, ledger().locked(alt));
        assertEquals(0L, ledger().spendable(alt));
        BondOutcome bought = exchange.desk().buyBond(exchange.env(), ExchangeWorld.account(alt), BondTerm.ONE_DAY, 100L,
                null);
        if (bought.ok()) exchange.desk().redeemBond(exchange.env(), ExchangeWorld.account(alt), bought.bond().id());
        assertEquals(0L, ledger().spendable(alt), "nothing became payable to another player");
        assertFalse(bought.ok(), "locked credits do not buy a bond");
        assertEquals(TradeResult.FUNDS_LOCKED.translationKey(), bought.reasonKey(), "told why");
        assertEquals(100L, ledger().locked(alt));
        exchange.check();
    }

    @Test void sharesBoughtPartlyWithLockedCreditsAndSoldUnlockNothing() {
        exchange.terms = exchange.terms.withHold(0L);
        UUID alt = exchange.world.newcomer(2);
        ledger().deposit(alt, NeroEconomyCommon.CREDITS, 250L, TransactionType.ADMIN_GRANT);
        long payable = ledger().spendable(alt);
        assertEquals(250L, payable);
        TradeQuote bought = exchange.desk().buyForCredits(exchange.env(), ExchangeWorld.account(alt),
                ExchangeWorld.CALM.id(), 300L);
        assertTrue(bought.ok(), bought.reasonKey());
        TradeQuote sold = exchange.desk().sell(exchange.env(), ExchangeWorld.account(alt), ExchangeWorld.CALM.id(),
                Quotes.ALL, ExchangeDesk.FULL_GAIN_ONLY);
        assertTrue(sold.ok(), sold.reasonKey());
        assertTrue(ledger().spendable(alt) <= payable,
                "a round trip never makes more payable to another player: " + ledger().spendable(alt));
        exchange.check();
    }

    @Test void earnedCreditsStillInvestAndTheFeeMayComeFromTheLockedPart() {
        exchange.terms = exchange.terms.withHold(0L);
        UUID player = exchange.world.newcomer(3);
        ledger().deposit(player, NeroEconomyCommon.CREDITS, 2_000L, TransactionType.ADMIN_GRANT);
        assertEquals(100L, ledger().locked(player));
        assertEquals(2_000L, ledger().spendable(player));
        assertTrue(exchange.desk().buyBond(exchange.env(), ExchangeWorld.account(player), BondTerm.ONE_DAY, 1_000L,
                null).ok());
        assertEquals(100L, ledger().locked(player), "a bond does not touch the locked part");
        TradeQuote shares = exchange.desk().buy(exchange.env(), ExchangeWorld.account(player), ExchangeWorld.CALM.id(),
                5L, 2_000L);
        assertTrue(shares.ok(), shares.reasonKey());
        assertEquals(100L - shares.fee(), ledger().locked(player), "the fee came out of the locked part");
        assertEquals(2_100L - 1_000L - shares.net(), ledger().getBalance(player, NeroEconomyCommon.CREDITS));
        exchange.check();
    }
}
