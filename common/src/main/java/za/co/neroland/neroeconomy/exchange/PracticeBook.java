package za.co.neroland.neroeconomy.exchange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.trade.Accounts;

/**
 * Practice mode: a sandbox in which an account trades the very same stocks, index fund and Savings
 * Bonds, at the very same prices, with <b>play credits</b>.
 *
 * <p><b>It cannot touch real money, by construction.</b> This class uses no type through which a
 * credit could be moved: it imports neither the economy store, the ledger, the Reserve, the vault
 * nor the alert sink, and never calls one. What it knows of the outside world is the narrow
 * {@link Market} it is given — a stock's {@link Listing}, the economy time, and a way to say "save
 * me" — plus the terms and the rate limiter handed to each call, and a monitor to synchronise on.
 * A play balance is a number in this book and nowhere else; no operation takes or
 * returns a real credit, writes a transaction row or raises an alert, and nothing converts between
 * practice and real. (The real desk is {@link ExchangeDesk}; the two share only the pure arithmetic
 * of {@link Quotes}, {@link ExchangeMath} and {@link Holding}.)
 *
 * <p><b>Same rules.</b> The same quotes, spreads, fee, holding period, position and portfolio
 * limits, bond terms and rate limit as the real thing, and the same refusals while a stock is
 * halted or delisted or the exchange is switched off. Two things differ, because there is no
 * Reserve behind it: a gain is always paid in full, and a bond's interest is always available.
 *
 * <p><b>Reset.</b> {@link #reset} puts the play balance back to {@code practiceStartingCredits} and
 * clears the practice holdings and bonds, at most once per economy-day.
 *
 * <p><b>Bounds.</b> At most {@link #MAX_ACCOUNTS} practice accounts. <b>Purge.</b> An account not
 * used for {@link #IDLE_DAYS} economy-days is dropped ({@link #purgeIdle}); it starts afresh when
 * it is next used. <b>Erasure</b> {@link #forget}. <b>Export</b> {@link #exportFor}.
 */
public final class PracticeBook {
    /** The most accounts that may have a practice portfolio. */
    public static final int MAX_ACCOUNTS = 65_536;
    /** A practice account not used for this many economy-days is dropped. */
    public static final long IDLE_DAYS = 30L;

    /** The little of the outside world the practice book can see. */
    interface Market {
        /** Whether a stock can be traded now, its model price and its spread. */
        Listing listing(ExchangeEnv env, String stockId);

        /** Economy time. */
        long now();

        /** The economy time up to which a purchase counts for every dividend still to come. */
        long seasonedBefore(long now);

        /** The price and the yield a dividend on the stock is worked out from, or null when it pays none. */
        long[] dividendTerms(StockRegistry registry, String stockId);

        /** Whether the economy is frozen for this session. */
        boolean frozen();

        /** The book changed and must be saved. */
        void changed();
    }

    /** One account's sandbox. */
    private static final class Account {
        long balance;
        /** The economy-day of the last reset, or {@link StockState#NO_DAY}. */
        long resetDay = StockState.NO_DAY;
        /** The economy-day the account was last used. */
        long activeDay;
        final Portfolio portfolio;

        Account(long balance, long day, Portfolio portfolio) {
            this.balance = balance;
            this.activeDay = day;
            this.portfolio = portfolio;
        }
    }

    /** One practice account as saved. */
    record Row(String account, long balance, long resetDay, long activeDay, Portfolio portfolio) {
        static final Codec<Row> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("account").forGetter(Row::account),
                Codec.LONG.optionalFieldOf("play_balance", 0L).forGetter(Row::balance),
                Codec.LONG.optionalFieldOf("reset_day", StockState.NO_DAY).forGetter(Row::resetDay),
                Codec.LONG.optionalFieldOf("active_day", 0L).forGetter(Row::activeDay),
                Portfolio.CODEC.optionalFieldOf("portfolio").forGetter(row -> Optional.of(row.portfolio())))
                .apply(instance, (account, balance, resetDay, activeDay, portfolio) -> new Row(account, balance,
                        resetDay, activeDay, portfolio.orElseGet(Portfolio::new))));
    }

    private final Market market;
    /** What every operation holds: the exchange section this book is part of, so the two never interleave. */
    private final Object lock;
    private final Map<AccountId, Account> accounts = new LinkedHashMap<>();

    PracticeBook(Market market, Object lock) {
        this.market = market;
        this.lock = lock;
    }

    // --- persistence -------------------------------------------------------------------------

    List<Row> rows() {
        List<Row> rows = new ArrayList<>(accounts.size());
        accounts.forEach((id, account) -> rows.add(new Row(id.stableKey(), account.balance, account.resetDay,
                account.activeDay, account.portfolio)));
        return rows;
    }

    void load(List<Row> rows) {
        for (Row row : rows) {
            if (row == null || row.balance() < 0L) continue;
            AccountId id = Accounts.parse(row.account());
            if (!(id instanceof PlayerAccountId) || accounts.containsKey(id)) continue;
            if (accounts.size() >= MAX_ACCOUNTS) break;
            Account account = new Account(row.balance(), row.activeDay(), row.portfolio() == null ? new Portfolio()
                    : row.portfolio());
            account.resetDay = row.resetDay();
            accounts.put(id, account);
        }
    }

    // --- reads -------------------------------------------------------------------------------

    public int size() { synchronized (lock) { return accounts.size(); } }

    public boolean isEmpty() { synchronized (lock) { return accounts.isEmpty(); } }

    /** The play balance: what the account has, or what it would start with. */
    public long balance(TradingTerms terms, AccountId account) {
        synchronized (lock) {
            Account held = accounts.get(account);
            return held != null ? held.balance : terms == null ? 0L : terms.practiceStart();
        }
    }

    /** The live practice portfolio of an account, or null. For the views and the tests; do not keep it. */
    public Portfolio portfolio(AccountId account) {
        synchronized (lock) {
            Account held = account == null ? null : accounts.get(account);
            return held == null ? null : held.portfolio;
        }
    }

    /** Whether the account may reset its practice portfolio today. */
    public boolean canReset(AccountId account) {
        synchronized (lock) {
            Account held = accounts.get(account);
            return held == null || held.resetDay != day(market.now());
        }
    }

    boolean anyHolds(String stockId) {
        synchronized (lock) {
            for (Account account : accounts.values()) if (account.portfolio.holding(stockId) != null) return true;
            return false;
        }
    }

    // --- shares and the index fund -----------------------------------------------------------

    /** What buying {@code units} with play credits would come to now. Changes nothing. */
    public TradeQuote quoteBuy(ExchangeEnv env, AccountId account, String stockId, long units) {
        synchronized (lock) {
            TradeResult gate = gate(env, account, true);
            if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, units);
            return buyQuote(env, account, stockId, units, 0L, market.now());
        }
    }

    /** What buying as many whole units as {@code credits} play credits cover would come to now. */
    public TradeQuote quoteBuyForCredits(ExchangeEnv env, AccountId account, String stockId, long credits) {
        synchronized (lock) {
            TradeResult gate = gate(env, account, true);
            if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, 0L);
            return buyQuote(env, account, stockId, 0L, credits, market.now());
        }
    }

    /** Buys {@code units} with play credits; see {@link ExchangeDesk#buy} for {@code maxSpend}. */
    public TradeQuote buy(ExchangeEnv env, AccountId account, String stockId, long units, long maxSpend) {
        synchronized (lock) {
            TradeResult gate = act(env, account, true);
            if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, units);
            long now = market.now();
            return completeBuy(env, account, buyQuote(env, account, stockId, units, 0L, now), maxSpend, now);
        }
    }

    /** Buys as many whole units as {@code credits} play credits cover, fee included. */
    public TradeQuote buyForCredits(ExchangeEnv env, AccountId account, String stockId, long credits) {
        synchronized (lock) {
            TradeResult gate = act(env, account, true);
            if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, 0L);
            long now = market.now();
            return completeBuy(env, account, buyQuote(env, account, stockId, 0L, credits, now), credits, now);
        }
    }

    private TradeQuote buyQuote(ExchangeEnv env, AccountId account, String stockId, long units, long credits, long now) {
        Account held = accounts.get(account);
        if (held == null && accounts.size() >= MAX_ACCOUNTS)
            return TradeQuote.refused(TradeResult.EXCHANGE_FULL, TradeSide.BUY, stockId, units);
        Listing listing = market.listing(env, stockId);
        Holding holding = held == null ? null : held.portfolio.holding(stockId);
        long invested = held == null ? 0L : held.portfolio.invested();
        long balance = held == null ? env.terms().practiceStart() : held.balance;
        return credits > 0L
                ? Quotes.buyForCredits(env.terms(), listing, holding, invested, balance, stockId, credits, now)
                : Quotes.buy(env.terms(), listing, holding, invested, balance, stockId, units, now);
    }

    private TradeQuote completeBuy(ExchangeEnv env, AccountId account, TradeQuote quote, long maxSpend, long now) {
        if (!quote.ok()) return quote;
        if (quote.net() > maxSpend) return quote.with(TradeResult.PRICE_MOVED);
        Account held = open(env.terms(), account, now);
        // The fee is play credits too: it leaves the play balance and goes nowhere.
        held.balance -= quote.net();
        Holding holding = held.portfolio.holdingFor(quote.stockId());
        holding.settle(now, env.terms().holdMillis(), market.seasonedBefore(now));
        holding.add(quote.units(), quote.total(), now);
        market.changed();
        return quote;
    }

    /** What selling practice units would come to now. Changes nothing. A practice gain is always paid in full. */
    public TradeQuote quoteSell(ExchangeEnv env, AccountId account, String stockId, long units) {
        synchronized (lock) {
            TradeResult gate = gate(env, account, true);
            if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.SELL, stockId, units == Quotes.ALL ? 0L : units);
            return sellQuote(env, account, stockId, units, market.now());
        }
    }

    /** Sells {@code units} practice units — or everything that may be sold, for {@link Quotes#ALL}. */
    public TradeQuote sell(ExchangeEnv env, AccountId account, String stockId, long units) {
        synchronized (lock) {
            TradeResult gate = act(env, account, true);
            if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.SELL, stockId, units == Quotes.ALL ? 0L : units);
            long now = market.now();
            TradeQuote quote = sellQuote(env, account, stockId, units, now);
            if (!quote.ok()) return quote;
            Account held = accounts.get(account);
            held.portfolio.holding(stockId).take(quote.units(), now, env.terms().holdMillis());
            held.portfolio.dropIfEmpty(stockId);
            held.balance = ExchangeMath.add(held.balance, quote.net());
            held.activeDay = day(now);
            market.changed();
            return quote;
        }
    }

    private TradeQuote sellQuote(ExchangeEnv env, AccountId account, String stockId, long units, long now) {
        Account held = accounts.get(account);
        return Quotes.sell(env.terms(), market.listing(env, stockId), held == null ? null
                : held.portfolio.holding(stockId), stockId, units, now, Long.MAX_VALUE);
    }

    // --- practice bonds ----------------------------------------------------------------------

    /** Buys a practice bond with play credits. Its interest is fixed now, as a real bond's is. */
    public BondOutcome buyBond(ExchangeEnv env, AccountId account, BondTerm term, long principal, UUID id) {
        synchronized (lock) {
            TradeResult gate = act(env, account, true);
            if (!gate.ok()) return BondOutcome.refused(gate);
            if (term == null || principal < 1L) return BondOutcome.refused(TradeResult.INVALID);
            Account held = accounts.get(account);
            if (held == null && accounts.size() >= MAX_ACCOUNTS) return BondOutcome.refused(TradeResult.EXCHANGE_FULL);
            if (held != null && id != null && held.portfolio.bond(id) != null) return BondOutcome.refused(TradeResult.INVALID);
            TradingTerms terms = env.terms();
            if ((held == null ? 0 : held.portfolio.bondCount()) >= terms.maxBonds())
                return BondOutcome.refused(TradeResult.BOND_LIMIT);
            if (ExchangeMath.add(held == null ? 0L : held.portfolio.invested(), principal) > terms.maxPortfolio())
                return BondOutcome.refused(TradeResult.PORTFOLIO_LIMIT);
            if (principal > (held == null ? terms.practiceStart() : held.balance))
                return BondOutcome.refused(TradeResult.INSUFFICIENT_FUNDS);
            long now = market.now();
            held = open(terms, account, now);
            held.balance -= principal;
            Bond bond = new Bond(id == null ? UUID.randomUUID() : id, term, principal,
                    BondTerm.interest(principal, terms.bondRateBps(term)), now, ExchangeMath.add(now, term.millis()));
            held.portfolio.add(bond);
            market.changed();
            return new BondOutcome(TradeResult.OK, bond, 0L, 0L);
        }
    }

    /** Cashes a practice bond in: the play principal, and the interest if it has matured. */
    public BondOutcome redeemBond(ExchangeEnv env, AccountId account, UUID id) {
        synchronized (lock) {
            TradeResult gate = act(env, account, false);
            if (!gate.ok()) return BondOutcome.refused(gate);
            Account held = accounts.get(account);
            if (held == null || id == null) return BondOutcome.refused(TradeResult.BOND_NOT_FOUND);
            Bond bond = held.portfolio.bond(id);
            if (bond == null) return BondOutcome.refused(TradeResult.BOND_NOT_FOUND);
            long now = market.now();
            boolean matured = bond.matured(now);
            long paid = matured ? bond.held() : bond.principal();
            held.portfolio.remove(bond);
            held.balance = ExchangeMath.add(held.balance, paid);
            held.activeDay = day(now);
            market.changed();
            return new BondOutcome(TradeResult.OK, bond, paid, matured ? bond.interest() : 0L);
        }
    }

    /** Pays every practice bond that has matured at {@code time} into its account's play balance. */
    int matureBonds(long time) {
        synchronized (lock) {
            int paid = 0;
            for (Account account : accounts.values()) for (Bond bond : account.portfolio.bonds()) {
                if (!bond.matured(time)) continue;
                account.portfolio.remove(bond);
                account.balance = ExchangeMath.add(account.balance, bond.held());
                paid++;
            }
            if (paid > 0) market.changed();
            return paid;
        }
    }

    // --- reset -------------------------------------------------------------------------------

    /**
     * Starts the account's practice portfolio afresh: the play balance back to
     * {@code practiceStartingCredits}, no holdings, no bonds. At most once per economy-day.
     */
    public TradeResult reset(ExchangeEnv env, AccountId account) {
        synchronized (lock) {
            TradeResult gate = act(env, account, false);
            if (!gate.ok()) return gate;
            long today = day(market.now());
            Account held = accounts.get(account);
            if (held != null && held.resetDay == today) return TradeResult.PRACTICE_RESET_USED;
            if (held == null && accounts.size() >= MAX_ACCOUNTS) return TradeResult.EXCHANGE_FULL;
            Account fresh = new Account(env.terms().practiceStart(), today, new Portfolio());
            fresh.resetDay = today;
            accounts.put(account, fresh);
            market.changed();
            return TradeResult.OK;
        }
    }

    // --- what the simulation does to it ------------------------------------------------------

    /**
     * The day's practice dividends, by the same rule as the real ones — but credited to the play
     * balance in full, whatever the Reserve holds, because no Reserve is involved.
     */
    void payDividends(ExchangeEnv env, long day) {
        synchronized (lock) {
            long cutoff = (day - 1L) * PriceModel.DAY_MILLIS;
            boolean any = false;
            for (Account account : accounts.values()) {
                long total = 0L;
                for (Holding holding : account.portfolio.holdings()) {
                    long[] terms = market.dividendTerms(env.registry(), holding.stockId());
                    if (terms == null) continue;
                    total = ExchangeMath.add(total, ExchangeMath.mulMulDiv(holding.unitsHeldSince(cutoff), terms[0],
                            terms[1], ExchangeDesk.DIVIDEND_DIVISOR));
                }
                if (total <= 0L) continue;
                account.balance = ExchangeMath.add(account.balance, total);
                account.portfolio.paidDividend(day, total);
                any = true;
            }
            if (any) market.changed();
        }
    }

    /** Pays every practice holding of a delisted company out at {@code bidCents}, into the play balances. */
    void payOutDelisted(ExchangeEnv env, String stockId, long bidCents) {
        synchronized (lock) {
            boolean any = false;
            for (Account account : accounts.values()) {
                Holding holding = account.portfolio.remove(stockId);
                if (holding == null) continue;
                account.balance = ExchangeMath.add(account.balance,
                        Quotes.payOut(env.terms(), holding, bidCents, Long.MAX_VALUE).net());
                any = true;
            }
            if (any) market.changed();
        }
    }

    /** A share split: every practice holding of the stock holds {@code factor} times the units. */
    void split(String stockId, int factor) {
        synchronized (lock) {
            for (Account account : accounts.values()) {
                Holding holding = account.portfolio.holding(stockId);
                if (holding != null) holding.split(factor);
            }
        }
    }

    /** Merges old lots in every practice portfolio. */
    void settle(TradingTerms terms, long now) {
        synchronized (lock) {
            long seasoned = market.seasonedBefore(now);
            boolean changed = false;
            for (Account account : accounts.values()) changed |= account.portfolio.settle(now, terms.holdMillis(), seasoned);
            if (changed) market.changed();
        }
    }

    /** Drops every practice account not used for {@link #IDLE_DAYS} economy-days. @return how many */
    int purgeIdle(long today) {
        synchronized (lock) {
            int before = accounts.size();
            accounts.values().removeIf(account -> today - account.activeDay >= IDLE_DAYS);
            if (accounts.size() != before) market.changed();
            return before - accounts.size();
        }
    }

    // --- erasure and export ------------------------------------------------------------------

    /** Erasure: drops one player's practice account. @return whether there was one */
    public boolean forget(UUID player) {
        synchronized (lock) {
            if (player == null || accounts.remove(new PlayerAccountId(player)) == null) return false;
            market.changed();
            return true;
        }
    }

    /** Data-subject export: the subject's play balance, practice holdings and practice bonds. */
    public JsonObject exportFor(UUID player) {
        synchronized (lock) {
            Account held = player == null ? null : accounts.get(new PlayerAccountId(player));
            JsonObject out = ExchangeDesk.export(held == null ? null : held.portfolio);
            if (held != null) {
                out.addProperty("play_balance", held.balance);
                out.addProperty("last_active_day", held.activeDay);
                if (held.resetDay != StockState.NO_DAY) out.addProperty("last_reset_day", held.resetDay);
            }
            return out;
        }
    }

    // --- internals ---------------------------------------------------------------------------

    private TradeResult gate(ExchangeEnv env, AccountId account, boolean needsExchangeOn) {
        if (market.frozen()) return TradeResult.FROZEN;
        if (env == null || !(account instanceof PlayerAccountId)) return TradeResult.INVALID;
        if (needsExchangeOn && !env.terms().enabled()) return TradeResult.EXCHANGE_OFF;
        return TradeResult.OK;
    }

    private TradeResult act(ExchangeEnv env, AccountId account, boolean needsExchangeOn) {
        TradeResult gate = gate(env, account, needsExchangeOn);
        if (!gate.ok()) return gate;
        return env.practiceLimiter().allow(account, market.now(), env.terms().actionsPerMinute()) ? TradeResult.OK
                : TradeResult.TOO_FAST;
    }

    /** The account's sandbox, made with the starting play balance on first use. */
    private Account open(TradingTerms terms, AccountId account, long now) {
        Account held = accounts.computeIfAbsent(account, ignored -> new Account(terms.practiceStart(), day(now),
                new Portfolio()));
        held.activeDay = day(now);
        return held;
    }

    private static long day(long time) { return Math.floorDiv(time, PriceModel.DAY_MILLIS); }
}
