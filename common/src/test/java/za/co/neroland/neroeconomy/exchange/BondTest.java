package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.economy.BudgetKind;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.simulation.SimRunner;

/**
 * Savings Bonds with real credits: the interest is reserved when the bond is bought, a bond the
 * Reserve cannot back is not sold, it pays out by itself at maturity with nobody online, cashed in
 * early it returns the principal only — and it can never lose.
 */
class BondTest {
    private final ExchangeWorld exchange = new ExchangeWorld();

    @AfterEach void restore() {
        EconomyHealth.clear();
        exchange.close();
    }

    private UUID player(int number, long credits) { return exchange.world.player(number, credits); }
    private long balance(UUID player) { return exchange.world.balance(player); }
    private long reserve() { return exchange.world.reserve(); }

    private BondOutcome buy(UUID player, BondTerm term, long principal) {
        return exchange.desk().buyBond(exchange.env(), ExchangeWorld.account(player), term, principal, null);
    }

    private BondOutcome redeem(UUID player, UUID bond) {
        return exchange.desk().redeemBond(exchange.env(), ExchangeWorld.account(player), bond);
    }

    @Test void theTermsAreOneThreeAndSevenDaysAndTheInterestIsRoundedDown() {
        assertEquals(List.of("1d", "3d", "7d"), List.of(BondTerm.ONE_DAY.key(), BondTerm.THREE_DAYS.key(),
                BondTerm.SEVEN_DAYS.key()));
        assertEquals(80, exchange.terms.bondRateBps(BondTerm.ONE_DAY));
        assertEquals(300, exchange.terms.bondRateBps(BondTerm.THREE_DAYS));
        assertEquals(800, exchange.terms.bondRateBps(BondTerm.SEVEN_DAYS));
        // 8 % of 1012 is 80.96: the bond pays 80.
        assertEquals(80L, BondTerm.interest(1_012L, 800));
        assertEquals(0L, BondTerm.interest(10L, 80));
    }

    @Test void buyingABondMovesThePrincipalInAndReservesTheWholeInterestAtOnce() {
        UUID alice = player(1, 10_000L);
        long reserve = reserve();
        long bought = exchange.now();
        BondOutcome done = buy(alice, BondTerm.SEVEN_DAYS, 1_000L);
        assertTrue(done.ok());
        assertEquals("", done.reasonKey());
        Bond bond = done.bond();
        assertEquals(1_000L, bond.principal());
        assertEquals(80L, bond.interest());
        assertEquals(bought, bond.boughtAt());
        assertEquals(bought + 7L * ExchangeWorld.DAY, bond.maturesAt());
        // The principal left the balance — and nothing else: a bond carries no fee.
        assertEquals(9_000L, balance(alice));
        assertTrue(exchange.rows(alice, TransactionType.FEE_EXCHANGE).isEmpty());
        assertEquals(1_000L, exchange.rows(alice, TransactionType.BOND_BUY).get(0).amount());
        // The exchange account holds the principal and the interest; the Reserve paid the interest now.
        assertEquals(1_080L, exchange.exchangeBalance());
        assertEquals(reserve - 80L, reserve());
        assertEquals(80L, exchange.ledger().budgetUsed(BudgetKind.EXCHANGE));
        assertEquals(List.of(bond), exchange.desk().portfolio(ExchangeWorld.account(alice)).bonds());
        exchange.check();
    }

    @Test void aBondWhoseInterestTheBudgetOrTheReserveCannotReserveIsNotSold() {
        exchange.terms = exchange.terms.withLimits(5_000L, 1_000_000L, 8);
        UUID alice = player(1, 500_000L);
        // 8 % of 180000 is 14400 of today's 15000.
        assertTrue(buy(alice, BondTerm.SEVEN_DAYS, 180_000L).ok());
        assertEquals(600L, exchange.budgetLeft());
        long before = balance(alice);
        long held = exchange.exchangeBalance();
        // 800 of interest is more than is left: sold out, and nothing moved.
        BondOutcome soldOut = buy(alice, BondTerm.SEVEN_DAYS, 10_000L);
        assertEquals(TradeResult.BONDS_SOLD_OUT, soldOut.result());
        assertEquals("message.neroeconomy.invest.bonds_sold_out", soldOut.reasonKey());
        assertNull(soldOut.bond());
        assertEquals(before, balance(alice));
        assertEquals(held, exchange.exchangeBalance());
        // A smaller one, whose interest is exactly what is left, still sells.
        assertTrue(buy(alice, BondTerm.SEVEN_DAYS, 7_500L).ok());
        assertEquals(0L, exchange.budgetLeft());
        assertEquals(TradeResult.BONDS_SOLD_OUT, buy(alice, BondTerm.ONE_DAY, 1_000L).result());
        // A bond too small to earn anything needs nothing reserved, and is still a bond.
        assertTrue(buy(alice, BondTerm.ONE_DAY, 100L).ok());
        exchange.check();
        // The next economy-day has a budget of its own.
        exchange.advance(ExchangeWorld.DAY);
        assertTrue(buy(alice, BondTerm.ONE_DAY, 1_000L).ok());
        exchange.check();
    }

    @Test void anEmptyReserveSellsNoBondThatPaysInterest() {
        UUID alice = player(1, 10_000L);
        assertTrue(exchange.ledger().withdraw(SystemAccountId.RESERVE, NeroEconomyCommon.CREDITS, reserve() - 30L,
                TransactionType.ADMIN_TAKE));
        assertEquals(TradeResult.BONDS_SOLD_OUT, buy(alice, BondTerm.SEVEN_DAYS, 1_000L).result());
        assertEquals(10_000L, balance(alice));
        // 8 % of 375 is exactly the 30 the Reserve holds.
        assertTrue(buy(alice, BondTerm.SEVEN_DAYS, 375L).ok());
        assertEquals(0L, reserve());
        exchange.check();
    }

    @Test void aBondPaysOutByItselfAtMaturityWithNobodyOnline() {
        UUID alice = player(1, 10_000L);
        Bond bond = buy(alice, BondTerm.ONE_DAY, 5_000L).bond();
        assertEquals(40L, bond.interest());
        assertEquals(5_040L, exchange.exchangeBalance());
        long budgetUsed = exchange.ledger().budgetUsed(BudgetKind.EXCHANGE);
        // Ten minutes before the day is up the bond is still open.
        exchange.advance(ExchangeWorld.DAY - ExchangeWorld.STEP);
        exchange.runDueSteps();
        assertEquals(5_000L, balance(alice));
        assertNotNull(exchange.desk().portfolio(ExchangeWorld.account(alice)));
        assertTrue(exchange.alerts.isEmpty());
        // The step at the day is the one that pays: no player, no server, only the store and the time.
        exchange.advance(ExchangeWorld.STEP);
        exchange.runDueSteps();
        assertEquals(10_040L, balance(alice));
        assertEquals(0L, exchange.exchangeBalance());
        assertNull(exchange.desk().portfolio(ExchangeWorld.account(alice)));
        assertEquals(5_000L, exchange.rows(alice, TransactionType.BOND_REDEEM).get(0).amount());
        assertEquals(40L, exchange.rows(alice, TransactionType.BOND_INTEREST).get(0).amount());
        // The interest came out of what was set aside when the bond was bought: paying it draws on no budget.
        assertEquals(40L, budgetUsed);
        assertEquals(0L, exchange.ledger().budgetUsed(BudgetKind.EXCHANGE));
        assertEquals(List.of(Map.entry(ExchangeWorld.account(alice), EconomyAlerts.Kind.BOND_MATURED)), exchange.alerts);
        exchange.check();
        // It is paid once.
        exchange.advance(ExchangeWorld.DAY);
        exchange.runDueSteps();
        assertEquals(10_040L, balance(alice));
    }

    @Test void maturityIsHousekeepingAndRunsWithTheAutonomousEconomyAndTheExchangeSwitchedOff() {
        UUID alice = player(1, 10_000L);
        buy(alice, BondTerm.ONE_DAY, 5_000L);
        exchange.sim = new SimRunner.Settings(ExchangeWorld.STEP, 432L, 2, false);
        exchange.terms = exchange.terms.withEnabled(false);
        exchange.advance(ExchangeWorld.DAY);
        exchange.runDueSteps();
        assertEquals(10_040L, balance(alice));
        exchange.check();
    }

    @Test void aBondThatMaturedWhileTheServerWasDownIsPaidInTheCatchUp() {
        UUID alice = player(1, 10_000L);
        UUID bob = player(2, 10_000L);
        buy(alice, BondTerm.ONE_DAY, 1_000L);
        buy(bob, BondTerm.THREE_DAYS, 1_000L);
        buy(alice, BondTerm.SEVEN_DAYS, 1_000L);
        // The store goes to disk; three days pass with nothing running; it is read back and catches up.
        exchange.world.reload();
        exchange.advance(3L * ExchangeWorld.DAY);
        exchange.runDueSteps();
        assertEquals(10_000L - 2_000L + 1_008L, balance(alice));
        assertEquals(10_000L + 30L, balance(bob));
        // The seven-day bond is still open, with its interest still set aside.
        assertEquals(1_080L, exchange.exchangeBalance());
        exchange.check();
        for (int day = 0; day < 4; day++) {
            exchange.advance(ExchangeWorld.DAY);
            exchange.runDueSteps();
        }
        assertEquals(10_000L + 8L + 80L, balance(alice));
        assertEquals(0L, exchange.exchangeBalance());
        assertTrue(exchange.desk().isEmpty());
        exchange.check();
    }

    @Test void cashedInEarlyABondReturnsThePrincipalOnlyAndTheInterestGoesBackToTheReserve() {
        UUID alice = player(1, 10_000L);
        long reserve = reserve();
        Bond bond = buy(alice, BondTerm.SEVEN_DAYS, 1_000L).bond();
        exchange.advance(6L * ExchangeWorld.DAY);
        BondOutcome done = redeem(alice, bond.id());
        assertTrue(done.ok());
        assertEquals(bond, done.bond());
        assertEquals(1_000L, done.paid());
        assertEquals(0L, done.interest());
        assertEquals(10_000L, balance(alice));
        assertEquals(reserve, reserve());
        assertEquals(0L, exchange.exchangeBalance());
        assertTrue(exchange.rows(alice, TransactionType.BOND_INTEREST).isEmpty());
        assertNull(exchange.desk().portfolio(ExchangeWorld.account(alice)));
        exchange.check();
        // It is gone: it cannot be cashed in again, and nothing matures later.
        assertEquals(TradeResult.BOND_NOT_FOUND, redeem(alice, bond.id()).result());
        exchange.advance(2L * ExchangeWorld.DAY);
        exchange.runDueSteps();
        assertEquals(10_000L, balance(alice));
    }

    @Test void aBondCashedInAfterItsTermButBeforeTheNextStepPaysInFull() {
        UUID alice = player(1, 10_000L);
        Bond bond = buy(alice, BondTerm.ONE_DAY, 5_000L).bond();
        exchange.advance(ExchangeWorld.DAY);
        BondOutcome done = redeem(alice, bond.id());
        assertTrue(done.ok());
        assertEquals(5_040L, done.paid());
        assertEquals(40L, done.interest());
        assertEquals(10_040L, balance(alice));
        exchange.check();
    }

    @Test void cashingInWorksWhileTheExchangeIsSwitchedOffButNotWhileFrozen() {
        UUID alice = player(1, 10_000L);
        Bond bond = buy(alice, BondTerm.SEVEN_DAYS, 1_000L).bond();
        EconomyHealth.freeze();
        assertEquals(TradeResult.FROZEN, redeem(alice, bond.id()).result());
        EconomyHealth.clear();
        exchange.terms = exchange.terms.withEnabled(false);
        assertEquals(TradeResult.EXCHANGE_OFF, buy(alice, BondTerm.ONE_DAY, 100L).result());
        assertTrue(redeem(alice, bond.id()).ok());
        assertEquals(10_000L, balance(alice));
        exchange.check();
    }

    @Test void anAccountMayHoldOnlySoManyBonds() {
        UUID alice = player(1, 10_000L);
        for (int bond = 0; bond < 8; bond++) assertTrue(buy(alice, BondTerm.ONE_DAY, 100L).ok(), "bond " + bond);
        long before = balance(alice);
        assertEquals(TradeResult.BOND_LIMIT, buy(alice, BondTerm.ONE_DAY, 100L).result());
        assertEquals(before, balance(alice));
        // Cashing one in makes room for one.
        UUID first = exchange.desk().portfolio(ExchangeWorld.account(alice)).bonds().get(0).id();
        assertTrue(redeem(alice, first).ok());
        assertTrue(buy(alice, BondTerm.ONE_DAY, 100L).ok());
        assertEquals(8, exchange.desk().portfolio(ExchangeWorld.account(alice)).bondCount());
        // A cap of nothing sells no bond at all.
        exchange.terms = exchange.terms.withLimits(5_000L, 20_000L, 0);
        assertEquals(TradeResult.BOND_LIMIT, buy(player(2, 1_000L), BondTerm.ONE_DAY, 100L).result());
        exchange.check();
    }

    @Test void whatABondCannotBeBoughtWith() {
        UUID alice = player(1, 500L);
        AccountId account = ExchangeWorld.account(alice);
        assertEquals(TradeResult.INSUFFICIENT_FUNDS, buy(alice, BondTerm.ONE_DAY, 501L).result());
        assertEquals(TradeResult.INVALID, buy(alice, BondTerm.ONE_DAY, 0L).result());
        assertEquals(TradeResult.INVALID, buy(alice, BondTerm.ONE_DAY, -5L).result());
        assertEquals(TradeResult.INVALID, buy(alice, null, 100L).result());
        assertEquals(TradeResult.INVALID, exchange.desk().buyBond(exchange.env(), SystemAccountId.RESERVE,
                BondTerm.ONE_DAY, 100L, null).result());
        assertEquals(TradeResult.BOND_NOT_FOUND, redeem(alice, UUID.randomUUID()).result());
        assertEquals(TradeResult.BOND_NOT_FOUND, redeem(alice, null).result());
        // An id is the bond's own: the same one twice is refused.
        UUID id = new UUID(4L, 2L);
        assertTrue(exchange.desk().buyBond(exchange.env(), account, BondTerm.ONE_DAY, 100L, id).ok());
        assertEquals(TradeResult.INVALID, exchange.desk().buyBond(exchange.env(), account, BondTerm.ONE_DAY, 100L, id).result());
        // Somebody else cannot cash it in.
        assertEquals(TradeResult.BOND_NOT_FOUND, redeem(player(2, 100L), id).result());
        assertEquals(400L, balance(alice));
        exchange.check();
    }

    @Test void aBondCanNeverLoseWhateverIsDoneWithIt() {
        exchange.terms = exchange.terms.withLimits(5_000L, 1_000_000L, 8).withActionsPerMinute(600);
        Random random = new Random(8L);
        UUID alice = player(1, 100_000L);
        long put = 0L;
        long back = 0L;
        for (int round = 0; round < 300; round++) {
            BondTerm term = BondTerm.values()[random.nextInt(3)];
            long principal = 1L + random.nextInt(2_000);
            long before = balance(alice);
            BondOutcome bought = buy(alice, term, principal);
            if (!bought.ok()) {
                // Sold out for the day, or eight are open already: nothing moved either way.
                assertEquals(before, balance(alice));
                exchange.advance(ExchangeWorld.DAY / 3L);
                exchange.runDueSteps();
                continue;
            }
            put += principal;
            assertEquals(before - principal, balance(alice));
            if (random.nextBoolean()) {
                exchange.advance(random.nextInt(3) * ExchangeWorld.HOUR);
                long held = balance(alice);
                BondOutcome done = redeem(alice, bought.bond().id());
                assertTrue(done.ok());
                assertTrue(done.paid() >= principal);
                assertEquals(held + done.paid(), balance(alice));
            }
            exchange.advance(1_000L * random.nextInt(600));
            exchange.runDueSteps();
            exchange.check();
        }
        // Everything still open matures.
        for (int day = 0; day < 8; day++) {
            exchange.advance(ExchangeWorld.DAY);
            exchange.runDueSteps();
        }
        back = balance(alice) - 100_000L;
        assertTrue(put > 0L);
        assertTrue(back >= 0L, "bonds lost " + -back + " credits");
        assertEquals(0L, exchange.exchangeBalance());
        exchange.check();
    }

    @Test void bondsAreInTheExportAndSurviveASaveAndALoad() {
        UUID alice = player(1, 10_000L);
        Bond bond = buy(alice, BondTerm.THREE_DAYS, 2_000L).bond();
        exchange.world.reload();
        assertEquals(List.of(bond), exchange.desk().portfolio(ExchangeWorld.account(alice)).bonds());
        JsonObject row = exchange.exchange().exportFor(alice).getAsJsonArray("bonds").get(0).getAsJsonObject();
        assertEquals(bond.id().toString(), row.get("id").getAsString());
        assertEquals("3d", row.get("term").getAsString());
        assertEquals(2_000L, row.get("principal").getAsLong());
        assertEquals(60L, row.get("interest").getAsLong());
        assertEquals(bond.boughtAt(), row.get("bought_at").getAsLong());
        assertEquals(bond.maturesAt(), row.get("matures_at").getAsLong());
        assertFalse(exchange.exchange().exportFor(alice).toString().contains(alice.toString()));
        exchange.check();
    }
}
