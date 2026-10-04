package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.economy.TransactionType;

/**
 * Invariant C under a long random run of everything the exchange can do with real credits, with
 * the prices moving as the model moves them: after <b>every</b> operation the exchange account
 * holds exactly the cost of every open share and index position, the principal of every open bond
 * and the interest reserved for those bonds — added up here from the portfolios themselves — and
 * Invariants A and B hold as well. (The operation fuzzer runs the same operations in among
 * everything else the economy does.)
 */
class ExchangeInvariantTest {
    private static final int OPERATIONS = 8_000;
    private static final int PLAYERS = 7;

    private final ExchangeWorld exchange = new ExchangeWorld();

    @AfterEach void restore() { exchange.close(); }

    /** What the exchange account must hold, added up lot by lot and bond by bond. */
    private long recorded(List<UUID> players) {
        long total = 0L;
        for (UUID player : players) {
            Portfolio portfolio = exchange.desk().portfolio(ExchangeWorld.account(player));
            if (portfolio == null) continue;
            for (Holding holding : portfolio.holdings()) for (Holding.Lot lot : holding.lots()) total += lot.cost();
            for (Bond bond : portfolio.bonds()) total += bond.principal() + bond.interest();
        }
        return total;
    }

    @Test void theExchangeAccountAlwaysEqualsTheOpenPositionsWhateverHappens() {
        exchange.terms = exchange.terms.withHold(10L * ExchangeWorld.MINUTE).withLimits(3_000L, 8_000L, 4)
                .withActionsPerMinute(600);
        Random random = new Random(20_261_003L);
        List<UUID> players = new ArrayList<>();
        for (int number = 1; number <= PLAYERS; number++) players.add(exchange.world.player(number, 20_000L));
        List<String> products = List.of(ExchangeWorld.CALM.id(), ExchangeWorld.PLAIN.id(), ExchangeWorld.WILD.id(),
                ExchangeWorld.INDEX, "test:nothing");
        StockRegistry everything = exchange.registry;
        int[] done = new int[9];
        long forfeited = 0L;

        for (int operation = 0; operation < OPERATIONS; operation++) {
            UUID player = players.get(random.nextInt(PLAYERS));
            AccountId account = ExchangeWorld.account(player);
            String product = products.get(random.nextInt(products.size()));
            Portfolio portfolio = exchange.desk().portfolio(account);
            switch (random.nextInt(16)) {
                case 0, 1, 2 -> done[0] += exchange.desk().buyForCredits(exchange.env(), account, product,
                        1L + random.nextInt(1_200)).ok() ? 1 : 0;
                case 3 -> done[0] += exchange.buy(player, product, random.nextInt(12)).ok() ? 1 : 0;
                case 4, 5, 6 -> {
                    String held = portfolio == null || portfolio.holdings().isEmpty() ? product
                            : portfolio.holdings().get(random.nextInt(portfolio.holdings().size())).stockId();
                    long units = random.nextBoolean() ? Quotes.ALL : 1L + random.nextInt(10);
                    long accepted = random.nextBoolean() ? ExchangeDesk.FULL_GAIN_ONLY : 0L;
                    TradeQuote sold = exchange.desk().sell(exchange.env(), account, held, units, accepted);
                    if (sold.ok()) {
                        done[1]++;
                        forfeited += sold.gainForfeited();
                    }
                }
                case 7 -> done[2] += exchange.desk().buyBond(exchange.env(), account,
                        BondTerm.values()[random.nextInt(3)], 1L + random.nextInt(1_500), null).ok() ? 1 : 0;
                case 8 -> {
                    if (portfolio != null && portfolio.bondCount() > 0) done[3] += exchange.desk().redeemBond(
                            exchange.env(), account, portfolio.bonds().get(random.nextInt(portfolio.bondCount())).id())
                            .ok() ? 1 : 0;
                }
                case 9, 10, 11, 12 -> {
                    // Time passes and the simulation runs: prices, splits, dividends, maturities, payouts.
                    exchange.advance((1L + random.nextInt(random.nextInt(8) == 0 ? 200 : 12)) * ExchangeWorld.STEP);
                    done[4] += exchange.runDueSteps();
                }
                case 13 -> {
                    if (random.nextInt(12) != 0) break;
                    // A data-pack reload takes a company away, or brings them all back.
                    if (exchange.registry.stocks().size() == 3) exchange.delist(random.nextBoolean()
                            ? ExchangeWorld.WILD : ExchangeWorld.PLAIN);
                    else {
                        exchange.registry = everything;
                        exchange.exchange().reconcile(everything);
                    }
                    done[5]++;
                }
                case 14 -> {
                    if (random.nextInt(25) != 0) break;
                    // A player is erased, and comes back as a new account later.
                    exchange.exchange().forgetPlayer(player);
                    exchange.ledger().forgetPlayer(player);
                    done[6]++;
                }
                default -> {
                    // A crash and a restart, now and then; and somebody tops their balance up.
                    if (random.nextInt(20) == 0) {
                        exchange.world.reload();
                        done[7]++;
                    }
                    if (exchange.world.balance(player) < 2_000L) exchange.world.player(
                            (int) player.getLeastSignificantBits(), 20_000L);
                    done[8] += exchange.practice().buyForCredits(exchange.env(), account, product,
                            1L + random.nextInt(600)).ok() ? 1 : 0;
                }
            }
            assertEquals(recorded(players), exchange.exchangeBalance(), "Invariant C after operation " + operation);
            exchange.check();
        }
        // It was a run in which things happened: thousands of trades, bonds bought and matured,
        // days of dividends, companies delisted and paid out, players erased, the store reloaded.
        String report = "buys " + done[0] + ", sells " + done[1] + ", bonds " + done[2] + ", redeemed " + done[3]
                + ", steps " + done[4] + ", listings " + done[5] + ", erasures " + done[6] + ", reloads " + done[7]
                + ", practice " + done[8] + ", gain given up " + forfeited;
        System.out.println("[exchange invariant] " + report);
        assertTrue(done[0] > 500 && done[1] > 200, report);
        assertTrue(done[2] > 50 && done[3] > 10, report);
        assertTrue(done[4] > 5_000, report);
        assertTrue(done[5] > 5 && done[6] > 3 && done[7] > 5 && done[8] > 50, report);
        assertTrue(exchange.alerts.stream().anyMatch(alert -> alert.getValue().name().equals("BOND_MATURED")), report);
        assertTrue(exchange.alerts.stream().anyMatch(alert -> alert.getValue().name().equals("DIVIDEND_PAID")), report);
        assertTrue(exchange.alerts.stream().anyMatch(alert -> alert.getValue().name().equals("STOCK_DELISTED")), report);
        // Every credit the exchange paid above principal came out of the Reserve under a typed reason.
        assertTrue(players.stream().anyMatch(player -> !exchange.rows(player, TransactionType.CAPITAL_GAIN).isEmpty()
                || !exchange.rows(player, TransactionType.DIVIDEND).isEmpty()), report);
    }
}
