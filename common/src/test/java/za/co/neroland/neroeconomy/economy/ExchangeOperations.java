package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.exchange.Bond;
import za.co.neroland.neroeconomy.exchange.BondOutcome;
import za.co.neroland.neroeconomy.exchange.BondTerm;
import za.co.neroland.neroeconomy.exchange.ExchangeDesk;
import za.co.neroland.neroeconomy.exchange.ExchangeEnv;
import za.co.neroland.neroeconomy.exchange.Holding;
import za.co.neroland.neroeconomy.exchange.IndexState;
import za.co.neroland.neroeconomy.exchange.Portfolio;
import za.co.neroland.neroeconomy.exchange.PracticeBook;
import za.co.neroland.neroeconomy.exchange.Quotes;
import za.co.neroland.neroeconomy.exchange.StockDefinition;
import za.co.neroland.neroeconomy.exchange.TradeQuote;
import za.co.neroland.neroeconomy.exchange.TradeResult;

/**
 * The fuzzer's exchange operations, against the real exchange section: shares and the index fund,
 * Savings Bonds, practice mode, and companies leaving and rejoining the exchange. Prices, splits,
 * dividends, bond maturities and delisting payouts happen in the simulation steps the world
 * registers, exactly as in the game.
 *
 * <p>Nothing here needs a model of its own: the supply never changes (every exchange movement is
 * a transfer), and Invariant C — the exchange account equals what the section records — is
 * checked by the world after every operation of every kind. Each operation only asserts what is
 * local to it: a purchase took exactly what it said, a sale paid exactly what it said, a practice
 * operation moved no credit at all.
 */
final class ExchangeOperations {
    private ExchangeOperations() { }

    static List<FuzzOperation> all() {
        return List.of(new Buy(), new Sell(), new BondBuy(), new BondRedeem(), new PracticeTrade(), new PracticeBond(),
                new Listing());
    }

    private static long balance(FuzzWorld world, UUID player) {
        return world.ledger().getBalance(player, NeroEconomyCommon.CREDITS);
    }

    private static long exchangeAccount(FuzzWorld world) { return world.ledger().balance(SystemAccountId.EXCHANGE); }

    /** Mostly one of the companies, sometimes the index fund, now and then something that does not exist. */
    private static String product(FuzzWorld world) {
        int pick = world.random.nextInt(20);
        if (pick == 0) return "fuzz:nothing";
        if (pick < 4) return IndexState.ID;
        return FuzzWorld.STOCKS.get(world.random.nextInt(FuzzWorld.STOCKS.size())).id();
    }

    /** Something the account holds, when it holds anything: so that sales mostly have something to sell. */
    private static String held(FuzzWorld world, Portfolio portfolio) {
        if (portfolio == null || world.random.nextInt(8) == 0) return product(world);
        List<Holding> holdings = portfolio.holdings();
        return holdings.isEmpty() ? product(world) : holdings.get(world.random.nextInt(holdings.size())).stockId();
    }

    /** A purchase with real credits: for an amount, or of a number of units with or without a price ceiling. */
    static final class Buy implements FuzzOperation {
        @Override public String name() { return "exchange_buy"; }
        @Override public int weight() { return 60; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            AccountId account = FuzzWorld.account(player);
            ExchangeDesk desk = world.exchange().desk();
            ExchangeEnv env = world.exchangeEnv();
            String product = product(world);
            long before = balance(world, player);
            long held = exchangeAccount(world);
            TradeQuote done;
            if (world.random.nextBoolean()) {
                long credits = world.random.nextInt(12) == 0 ? world.amount() : 1L + world.random.nextInt(2_500);
                done = desk.buyForCredits(env, account, product, credits);
                if (done.ok()) assertTrue(done.net() <= credits);
            } else {
                long units = world.random.nextInt(30);
                // Sometimes a ceiling that the price may be past.
                long ceiling = world.random.nextInt(5) == 0 ? world.random.nextInt(300) : ExchangeDesk.ANY_PRICE;
                done = desk.buy(env, account, product, units, ceiling);
                if (done.ok()) assertTrue(done.net() <= ceiling);
            }
            if (!done.ok()) {
                assertEquals(before, balance(world, player));
                assertEquals(held, exchangeAccount(world));
                return 0L;
            }
            assertEquals(done.total() + done.fee(), done.net());
            assertEquals(before - done.net(), balance(world, player));
            assertEquals(held + done.total(), exchangeAccount(world));
            return done.net() * 31L + done.units();
        }
    }

    /** A sale: some units or everything, with the reduced gain refused, accepted as quoted, or accepted whatever it is. */
    static final class Sell implements FuzzOperation {
        @Override public String name() { return "exchange_sell"; }
        @Override public int weight() { return 70; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            AccountId account = FuzzWorld.account(player);
            ExchangeDesk desk = world.exchange().desk();
            ExchangeEnv env = world.exchangeEnv();
            String product = held(world, desk.portfolio(account));
            long units = world.random.nextInt(3) == 0 ? 1L + world.random.nextInt(25) : Quotes.ALL;
            long accepted = switch (world.random.nextInt(3)) {
                case 0 -> ExchangeDesk.FULL_GAIN_ONLY;
                case 1 -> 0L;
                default -> desk.quoteSell(env, account, product, units).gainPayable();
            };
            long before = balance(world, player);
            long held = exchangeAccount(world);
            TradeQuote done = desk.sell(env, account, product, units, accepted);
            if (!done.ok()) {
                assertEquals(before, balance(world, player));
                assertEquals(held, exchangeAccount(world));
                // A gain cut short is only ever refused, never taken without the acceptance.
                if (done.result() == TradeResult.GAIN_REDUCED) assertEquals(ExchangeDesk.FULL_GAIN_ONLY, accepted);
                return 0L;
            }
            // The principal always comes back out of the exchange account in full.
            assertEquals(held - done.costBasis(), exchangeAccount(world));
            assertEquals(before + done.net(), balance(world, player));
            assertEquals(done.gain(), done.gainPayable() + done.gainForfeited());
            assertTrue(done.gainForfeited() == 0L || accepted >= 0L);
            assertTrue(done.gain() == 0L || done.loss() == 0L);
            return 1L + done.net() * 31L + done.units() + done.gainForfeited() * 7L;
        }
    }

    /** A Savings Bond is bought: of any term, for an amount that is sometimes nothing or negative. */
    static final class BondBuy implements FuzzOperation {
        @Override public String name() { return "bond_buy"; }
        @Override public int weight() { return 25; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            long principal = world.random.nextInt(6) == 0 ? world.amount() : 1L + world.random.nextInt(1_500);
            BondTerm term = BondTerm.values()[world.random.nextInt(BondTerm.values().length)];
            long before = balance(world, player);
            long held = exchangeAccount(world);
            long reserve = world.ledger().balance(SystemAccountId.RESERVE);
            BondOutcome done = world.exchange().desk().buyBond(world.exchangeEnv(), FuzzWorld.account(player), term,
                    principal, BookOperations.id(world));
            if (!done.ok()) {
                assertEquals(before, balance(world, player));
                assertEquals(held, exchangeAccount(world));
                return 0L;
            }
            Bond bond = done.bond();
            assertEquals(principal, bond.principal());
            assertEquals(before - principal, balance(world, player));
            // The interest was set aside at once: it is in the exchange account, out of the Reserve.
            assertEquals(held + principal + bond.interest(), exchangeAccount(world));
            // (An account opened by this very purchase was also paid its starting balance by the Reserve.)
            assertTrue(world.ledger().balance(SystemAccountId.RESERVE) <= reserve - bond.interest());
            return principal * 31L + bond.interest() + term.days();
        }
    }

    /** A bond is cashed in — early, or after its term but before the step that would have paid it. */
    static final class BondRedeem implements FuzzOperation {
        @Override public String name() { return "bond_redeem"; }
        @Override public int weight() { return 12; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            AccountId account = FuzzWorld.account(player);
            Portfolio portfolio = world.exchange().desk().portfolio(account);
            UUID id = portfolio == null || portfolio.bondCount() == 0 || world.random.nextInt(10) == 0
                    ? BookOperations.id(world) : portfolio.bonds().get(world.random.nextInt(portfolio.bondCount())).id();
            long before = balance(world, player);
            long held = exchangeAccount(world);
            BondOutcome done = world.exchange().desk().redeemBond(world.exchangeEnv(), account, id);
            if (!done.ok()) {
                assertEquals(before, balance(world, player));
                assertEquals(held, exchangeAccount(world));
                return 0L;
            }
            // A bond never returns less than was put in, and leaves nothing of itself behind.
            assertTrue(done.paid() >= done.bond().principal());
            assertEquals(before + done.paid(), balance(world, player));
            assertEquals(held - done.bond().held(), exchangeAccount(world));
            return done.paid() * 31L + done.interest();
        }
    }

    /** A practice purchase or sale. Whatever it does, not one real credit moves. */
    static final class PracticeTrade implements FuzzOperation {
        @Override public String name() { return "practice_trade"; }
        @Override public int weight() { return 45; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            AccountId account = FuzzWorld.account(player);
            PracticeBook practice = world.exchange().practice();
            ExchangeEnv env = world.exchangeEnv();
            long real = balance(world, player);
            long total = world.ledger().totalBalancesForTest();
            long held = exchangeAccount(world);
            long reserve = world.ledger().balance(SystemAccountId.RESERVE);
            int rows = world.ledger().transactionCount();
            long play = practice.balance(env.terms(), account);
            TradeQuote done;
            if (world.random.nextBoolean()) {
                done = world.random.nextBoolean()
                        ? practice.buyForCredits(env, account, product(world), 1L + world.random.nextInt(900))
                        : practice.buy(env, account, product(world), world.random.nextInt(20), ExchangeDesk.ANY_PRICE);
                if (done.ok()) assertEquals(play - done.net(), practice.balance(env.terms(), account));
            } else {
                done = practice.sell(env, account, held(world, practice.portfolio(account)),
                        world.random.nextBoolean() ? Quotes.ALL : 1L + world.random.nextInt(15));
                if (done.ok()) {
                    assertEquals(play + done.net(), practice.balance(env.terms(), account));
                    // There is no Reserve behind practice: a practice gain is always paid in full.
                    assertEquals(0L, done.gainForfeited());
                }
            }
            if (!done.ok()) assertEquals(play, practice.balance(env.terms(), account));
            unchanged(world, player, real, total, held, reserve, rows);
            return done.ok() ? 1L + done.net() * 31L + done.units() : 0L;
        }
    }

    /** A practice bond is bought or cashed in, or the sandbox is reset. Not one real credit moves. */
    static final class PracticeBond implements FuzzOperation {
        @Override public String name() { return "practice_bond"; }
        @Override public int weight() { return 20; }
        @Override public long run(FuzzWorld world) {
            UUID player = world.player();
            AccountId account = FuzzWorld.account(player);
            PracticeBook practice = world.exchange().practice();
            ExchangeEnv env = world.exchangeEnv();
            long real = balance(world, player);
            long total = world.ledger().totalBalancesForTest();
            long held = exchangeAccount(world);
            long reserve = world.ledger().balance(SystemAccountId.RESERVE);
            int rows = world.ledger().transactionCount();
            long outcome;
            Portfolio portfolio = practice.portfolio(account);
            int pick = world.random.nextInt(10);
            if (pick == 0) {
                outcome = practice.reset(env, account).ok() ? 1L : 0L;
                if (outcome != 0L) assertEquals(env.terms().practiceStart(), practice.balance(env.terms(), account));
            } else if (pick < 4 && portfolio != null && portfolio.bondCount() > 0) {
                BondOutcome done = practice.redeemBond(env, account,
                        portfolio.bonds().get(world.random.nextInt(portfolio.bondCount())).id());
                outcome = done.ok() ? done.paid() * 31L + 1L : 0L;
            } else {
                BondOutcome done = practice.buyBond(env, account,
                        BondTerm.values()[world.random.nextInt(BondTerm.values().length)], 1L + world.random.nextInt(700),
                        BookOperations.id(world));
                outcome = done.ok() ? done.bond().principal() * 31L + done.bond().interest() : 0L;
            }
            unchanged(world, player, real, total, held, reserve, rows);
            return outcome;
        }
    }

    /** What a practice operation must leave exactly as it found it. */
    private static void unchanged(FuzzWorld world, UUID player, long real, long total, long held, long reserve, int rows) {
        assertEquals(real, balance(world, player));
        assertEquals(total, world.ledger().totalBalancesForTest());
        assertEquals(held, exchangeAccount(world));
        assertEquals(reserve, world.ledger().balance(SystemAccountId.RESERVE));
        assertEquals(rows, world.ledger().transactionCount());
    }

    /**
     * A data-pack reload: a company's file is gone, or back. A company that is gone is delisted
     * and its holders are paid out by the next simulation step; one that comes back before that
     * carries on with its holders as they were.
     */
    static final class Listing implements FuzzOperation {
        @Override public String name() { return "exchange_listing"; }
        @Override public int weight() { return 2; }
        @Override public long run(FuzzWorld world) {
            StockDefinition company = FuzzWorld.STOCKS.get(world.random.nextInt(FuzzWorld.STOCKS.size()));
            boolean wasListed = world.listed.remove(company);
            if (!wasListed) world.listed.add(company);
            world.exchange().reconcile(world.registry());
            return wasListed ? 1L : 2L;
        }
    }
}
