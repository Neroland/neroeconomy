package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.StockType;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.economy.BudgetKind;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.simulation.SimRunner;

/**
 * Dividends: once per economy-day, on units held through the whole day before, rounded down per
 * holding, paid as one batch that is scaled down for everyone alike when the Reserve is short —
 * and the same whether the day's step runs live or in a catch-up.
 */
class DividendTest {
    private static final String CALM = ExchangeWorld.CALM.id();
    private static final String PLAIN = ExchangeWorld.PLAIN.id();
    private static final String WILD = ExchangeWorld.WILD.id();
    private static final long DAY1 = ExchangeWorld.FIRST_DAY + 1L;

    private ExchangeWorld exchange = new ExchangeWorld();

    @AfterEach void restore() {
        EconomyHealth.clear();
        exchange.close();
    }

    private UUID player(int number, long credits) { return exchange.world.player(number, credits); }
    private long balance(UUID player) { return exchange.world.balance(player); }

    private void roomy() { exchange.terms = exchange.terms.withLimits(100_000L, 400_000L, 8); }

    private DividendRun pay(long day) { return exchange.exchange().payDividends(exchange.env(), day); }

    @Test void aDividendIsPaidOnUnitsHeldForAFullDayAtASeventhOfTheWeeklyYield() {
        roomy();
        UUID alice = player(1, 50_000L);
        UUID bob = player(2, 50_000L);
        // Alice buys on the stroke of the day, Bob an hour into it.
        exchange.buy(alice, CALM, 100L);
        exchange.advance(ExchangeWorld.HOUR);
        exchange.buy(bob, CALM, 100L);
        exchange.advance(ExchangeWorld.DAY - ExchangeWorld.HOUR);
        long aliceBefore = balance(alice);
        long bobBefore = balance(bob);
        long budget = exchange.budgetLeft();

        // 100 units at 100.00, 1.4 % a week: 0.2 a day each, 20 credits. Bob has not held a full day yet.
        DividendRun run = pay(DAY1);
        assertEquals(DAY1, run.day());
        assertEquals(20L, run.requested());
        assertEquals(20L, run.paid());
        assertFalse(run.scaled());
        assertEquals(100, run.percent());
        assertEquals(1, run.accounts());
        assertEquals(aliceBefore + 20L, balance(alice));
        assertEquals(bobBefore, balance(bob));
        assertEquals(20L, exchange.rows(alice, TransactionType.DIVIDEND).get(0).amount());
        assertEquals(budget - 20L, exchange.budgetLeft());
        assertEquals(List.of(Map.entry(ExchangeWorld.account(alice), EconomyAlerts.Kind.DIVIDEND_PAID)), exchange.alerts);
        Portfolio portfolio = exchange.desk().portfolio(ExchangeWorld.account(alice));
        assertEquals(DAY1, portfolio.dividendDay());
        assertEquals(20L, portfolio.dividend());
        assertEquals(run, exchange.exchange().dividends());
        // The principal is untouched: a dividend is on top.
        assertEquals(100L, exchange.holding(alice, CALM).units());
        exchange.check();

        // The same day is never paid twice.
        assertSame(run, pay(DAY1));
        assertSame(run, pay(DAY1 - 3L));
        assertEquals(aliceBefore + 20L, balance(alice));

        // A day later both have held for a full day.
        exchange.advance(ExchangeWorld.DAY);
        DividendRun next = pay(DAY1 + 1L);
        assertEquals(40L, next.paid());
        assertEquals(2, next.accounts());
        assertEquals(aliceBefore + 40L, balance(alice));
        assertEquals(bobBefore + 20L, balance(bob));
        exchange.check();
    }

    @Test void unitsBoughtDuringTheDayDoNotEarnThatDaysDividendAndSoldOnesEarnNothing() {
        roomy();
        exchange.terms = exchange.terms.withHold(0L);
        UUID alice = player(1, 50_000L);
        exchange.buy(alice, CALM, 100L);
        // Topping up half way through the day, and selling thirty: the oldest go first.
        exchange.advance(12L * ExchangeWorld.HOUR);
        exchange.buy(alice, CALM, 50L);
        exchange.sell(alice, CALM, 30L);
        exchange.advance(12L * ExchangeWorld.HOUR);
        // 70 of the units bought at the start of the day are left: 14 credits. The 50 of today earn nothing yet.
        assertEquals(14L, pay(DAY1).paid());
        exchange.advance(ExchangeWorld.DAY);
        assertEquals(24L, pay(DAY1 + 1L).paid());
        exchange.check();
    }

    @Test void theDividendIsRoundedDownPerHoldingAndOnlyCompaniesWithAYieldPay() {
        roomy();
        // A second company that pays 0.7 % a week at 50 credits: 0.05 a unit a day.
        StockDefinition plain = new StockDefinition(PLAIN, "stock.test.plain", "stock.test.plain.description", "farming",
                StockType.STEADY, 50L, 2.0D, 6.0D, 0.7D, List.of(), true);
        exchange.registry = StockRegistry.of(List.of(ExchangeWorld.CALM, plain, ExchangeWorld.WILD));
        UUID alice = player(1, 50_000L);
        UUID bob = player(2, 50_000L);
        exchange.buy(alice, CALM, 8L);                  // 1.6 a day: 1
        exchange.buy(alice, PLAIN, 28L);                // 1.4 a day: 1
        exchange.buy(alice, WILD, 50L);                 // a risky share with no yield: nothing
        exchange.buy(alice, ExchangeWorld.INDEX, 40L);  // the index fund pays nothing
        exchange.buy(bob, CALM, 4L);                    // 0.8 a day: nothing
        exchange.advance(ExchangeWorld.DAY);
        long before = balance(alice);
        DividendRun run = pay(DAY1);
        // Two credits, not the three that 1.6 + 1.4 would round down to together.
        assertEquals(2L, run.paid());
        assertEquals(1, run.accounts());
        assertEquals(before + 2L, balance(alice));
        assertTrue(exchange.rows(bob, TransactionType.DIVIDEND).isEmpty());
        exchange.check();
    }

    @Test void aHaltedCompanyStillPaysAndADelistedOneDoesNot() {
        roomy();
        UUID alice = player(1, 50_000L);
        exchange.buy(alice, CALM, 100L);
        exchange.advance(ExchangeWorld.DAY);
        exchange.halt(CALM);
        assertEquals(20L, pay(DAY1).paid());
        exchange.advance(ExchangeWorld.DAY);
        exchange.delist(ExchangeWorld.CALM);
        DividendRun run = pay(DAY1 + 1L);
        assertEquals(DAY1 + 1L, run.day());
        assertEquals(0L, run.paid());
        assertEquals(0, run.accounts());
        exchange.check();
    }

    @Test void whenTheReserveIsShortEveryoneIsPaidTheSameShareAndTheFactorIsRecorded() {
        roomy();
        UUID alice = player(1, 50_000L);
        UUID bob = player(2, 50_000L);
        exchange.buy(alice, CALM, 100L);
        exchange.buy(bob, CALM, 200L);
        exchange.advance(ExchangeWorld.DAY);
        // The Reserve holds 15 credits; 60 are due.
        assertTrue(exchange.ledger().withdraw(SystemAccountId.RESERVE, NeroEconomyCommon.CREDITS,
                exchange.world.reserve() - 15L, TransactionType.ADMIN_TAKE));
        long aliceBefore = balance(alice);
        long bobBefore = balance(bob);
        DividendRun run = pay(DAY1);
        assertEquals(60L, run.requested());
        assertEquals(15L, run.paid());
        assertTrue(run.scaled());
        assertEquals(15L, run.numerator());
        assertEquals(60L, run.denominator());
        // "Dividends were paid at 25 % today because the Reserve was short."
        assertEquals(25, run.percent());
        assertEquals(aliceBefore + 5L, balance(alice));
        assertEquals(bobBefore + 10L, balance(bob));
        assertEquals(0L, exchange.world.reserve());
        assertEquals(5L, exchange.desk().portfolio(ExchangeWorld.account(alice)).dividend());
        assertEquals(run, exchange.exchange().dividends());
        exchange.check();
        // It is what is saved, too.
        exchange.world.reload();
        assertEquals(run, exchange.exchange().dividends());
    }

    @Test void whenTheDaysBudgetIsShortTheBatchIsScaledDownTheSameWay() {
        roomy();
        UUID alice = player(1, 300_000L);
        UUID bob = player(2, 50_000L);
        exchange.buy(alice, CALM, 100L);
        exchange.buy(bob, CALM, 200L);
        exchange.advance(ExchangeWorld.DAY);
        // A bond whose interest takes all but 10 credits of today's exchange budget.
        assertTrue(exchange.desk().buyBond(exchange.env(), ExchangeWorld.account(alice), BondTerm.SEVEN_DAYS,
                187_375L, null).ok());
        assertEquals(10L, exchange.budgetLeft());
        long aliceBefore = balance(alice);
        long bobBefore = balance(bob);
        DividendRun run = pay(DAY1);
        assertEquals(60L, run.requested());
        assertEquals(9L, run.paid());
        assertEquals(16, run.percent());
        assertEquals(aliceBefore + 3L, balance(alice));
        assertEquals(bobBefore + 6L, balance(bob));
        assertTrue(exchange.ledger().budgetUsed(BudgetKind.EXCHANGE) <= 15_000L);
        exchange.check();
    }

    @Test void nothingIsPaidWhileTheExchangeIsOffOrTheEconomyFrozenAndTheDayIsNotUsedUp() {
        roomy();
        UUID alice = player(1, 50_000L);
        exchange.buy(alice, CALM, 100L);
        exchange.advance(ExchangeWorld.DAY);
        long before = balance(alice);
        exchange.terms = exchange.terms.withEnabled(false);
        assertSame(DividendRun.NONE, pay(DAY1));
        EconomyHealth.freeze();
        exchange.terms = exchange.terms.withEnabled(true);
        assertSame(DividendRun.NONE, pay(DAY1));
        EconomyHealth.clear();
        assertEquals(before, balance(alice));
        assertEquals(20L, pay(DAY1).paid());
    }

    @Test void theSimulationPaysTheDividendOnceAtTheFirstStepOfTheDay() {
        roomy();
        UUID alice = player(1, 50_000L);
        exchange.buy(alice, CALM, 100L);
        long before = balance(alice);
        // The whole of the first day: no dividend falls due for a holding bought during it.
        exchange.advance(ExchangeWorld.DAY - ExchangeWorld.STEP);
        exchange.runDueSteps();
        assertEquals(0L, exchange.exchange().dividends().paid());
        assertEquals(before, balance(alice));
        // The step that begins the next day pays it, at the price that step left.
        exchange.advance(ExchangeWorld.STEP);
        exchange.runDueSteps();
        long expected = 100L * exchange.exchange().state(CALM).price() * 140L / 7_000_000L;
        assertTrue(expected >= 15L && expected <= 25L, "the price ran away: " + expected);
        assertEquals(DAY1, exchange.exchange().dividends().day());
        assertEquals(expected, exchange.exchange().dividends().paid());
        assertEquals(before + expected, balance(alice));
        // No later step of that day pays again.
        exchange.advance(20L * ExchangeWorld.STEP);
        exchange.runDueSteps();
        assertEquals(before + expected, balance(alice));
        exchange.check();
    }

    @Test void dividendsAreNotHousekeepingSoTheAutonomousSwitchTurnsThemOff() {
        roomy();
        UUID alice = player(1, 50_000L);
        exchange.buy(alice, CALM, 100L);
        long before = balance(alice);
        exchange.sim = new SimRunner.Settings(ExchangeWorld.STEP, 432L, 2, false);
        exchange.advance(ExchangeWorld.DAY);
        exchange.runDueSteps();
        assertEquals(before, balance(alice));
        assertFalse(exchange.exchange().dividends().happened());
    }

    /** What a run of the scenario below left behind. */
    private record Outcome(long alice, long bob, long reserve, long exchangeAccount, String section, DividendRun run,
            long playBalance, long steps) { }

    /**
     * Three days of trading and holding. Half a day in, the two runs part: one carries on step by
     * step, the other does nothing for two and a half days and then replays every step at once.
     */
    private Outcome threeDays(boolean catchUp) {
        exchange.close();
        exchange = new ExchangeWorld();
        roomy();
        UUID alice = player(1, 50_000L);
        UUID bob = player(2, 50_000L);
        UUID carol = player(3, 100L);
        exchange.buy(alice, CALM, 100L);
        assertTrue(exchange.practice().buy(exchange.env(), ExchangeWorld.account(carol), CALM, 40L,
                ExchangeDesk.ANY_PRICE).ok());
        long steps = 0L;
        for (int step = 0; step < 72; step++) {
            exchange.advance(ExchangeWorld.STEP);
            steps += exchange.runDueSteps();
        }
        exchange.buy(bob, CALM, 40L);
        assertTrue(exchange.desk().buyBond(exchange.env(), ExchangeWorld.account(bob), BondTerm.ONE_DAY, 1_000L,
                new UUID(1L, 1L)).ok());
        if (catchUp) {
            exchange.advance(360L * ExchangeWorld.STEP);
            steps += exchange.runDueSteps();
        } else {
            for (int step = 0; step < 360; step++) {
                exchange.advance(ExchangeWorld.STEP);
                steps += exchange.runDueSteps();
            }
        }
        exchange.check();
        return new Outcome(balance(alice), balance(bob), exchange.world.reserve(), exchange.exchangeBalance(),
                exchange.exchangeSnapshot().toString(), exchange.exchange().dividends(),
                exchange.practice().balance(exchange.terms, ExchangeWorld.account(carol)), steps);
    }

    @Test void aCatchUpPaysExactlyWhatALiveRunPaid() {
        Outcome live = threeDays(false);
        Outcome replayed = threeDays(true);
        assertEquals(432L, live.steps());
        assertEquals(live, replayed);
        // And it was not a run in which nothing happened: three dividend days, the last one to both holders.
        assertEquals(ExchangeWorld.FIRST_DAY + 3L, live.run().day());
        assertEquals(2, live.run().accounts());
        assertTrue(live.run().paid() > 20L);
        assertTrue(live.alice() > 50_000L - 10_151L + 40L);
        assertTrue(live.playBalance() > 10_000L - 4_061L);
    }

    @Test void practiceDividendsAreCreditedInFullWhateverTheReserveHolds() {
        roomy();
        UUID alice = player(1, 50_000L);
        UUID carol = player(3, 100L);
        exchange.buy(alice, CALM, 100L);
        assertTrue(exchange.practice().buy(exchange.env(), ExchangeWorld.account(carol), CALM, 90L,
                ExchangeDesk.ANY_PRICE).ok());
        exchange.advance(ExchangeWorld.DAY);
        assertTrue(exchange.ledger().withdraw(SystemAccountId.RESERVE, NeroEconomyCommon.CREDITS,
                exchange.world.reserve() - 5L, TransactionType.ADMIN_TAKE));
        long play = exchange.practice().balance(exchange.terms, ExchangeWorld.account(carol));
        long real = balance(carol);
        DividendRun run = pay(DAY1);
        // The real holder gets the 5 the Reserve has of her 20; the practice holder gets the whole 18, in play credits.
        assertEquals(5L, run.paid());
        assertEquals(20L, run.requested());
        assertEquals(play + 18L, exchange.practice().balance(exchange.terms, ExchangeWorld.account(carol)));
        assertEquals(real, balance(carol));
        assertEquals(18L, exchange.practice().portfolio(ExchangeWorld.account(carol)).dividend());
        // Practice raised no alert.
        assertEquals(List.of(Map.entry(ExchangeWorld.account(alice), EconomyAlerts.Kind.DIVIDEND_PAID)), exchange.alerts);
        exchange.check();
    }
}
