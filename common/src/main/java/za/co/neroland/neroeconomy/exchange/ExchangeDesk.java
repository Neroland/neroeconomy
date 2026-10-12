package za.co.neroland.neroeconomy.exchange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.economy.BudgetKind;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.LedgerPlan;
import za.co.neroland.neroeconomy.economy.LedgerResult;
import za.co.neroland.neroeconomy.economy.Reserve;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.sibling.MilestoneSection;
import za.co.neroland.neroeconomy.trade.Accounts;

/**
 * The exchange's real-money desk: every account's real {@link Portfolio}, and the only code on the
 * exchange that moves a credit. It is part of the exchange section and is saved with it.
 *
 * <p><b>The exchange is the counterparty.</b> Every trade is one ledger plan — every leg or none:
 *
 * <pre>
 *   buy    principal            player   → system:exchange    SHARE_BUY
 *          fee on the principal player   → Reserve            FEE_EXCHANGE
 *
 *   sell   at a gain:
 *          cost of the units    exchange → player             SHARE_SELL
 *          the gain             Reserve  → player             CAPITAL_GAIN   (exchange budget)
 *          at a loss:
 *          what they fetched    exchange → player             SHARE_SELL
 *          the loss             exchange → Reserve            CAPITAL_LOSS
 *          fee on what was received  player → Reserve         FEE_EXCHANGE
 *
 *   bond   principal            player   → system:exchange    BOND_BUY
 *          interest, reserved   Reserve  → system:exchange    BOND_INTEREST_RESERVED  (exchange budget)
 *   at maturity  principal and interest   exchange → player   BOND_REDEEM, BOND_INTEREST
 *   cashed in early  principal  exchange → player             BOND_REDEEM
 *                    interest   exchange → Reserve            BOND_INTEREST_RELEASED
 * </pre>
 *
 * <p>So the exchange account always holds exactly the cost of every open share and index position,
 * the principal of every open bond and the interest reserved for those bonds
 * (<b>Invariant C</b>, {@link #held()}), and nothing here creates or destroys a credit.
 *
 * <p><b>A gain the Reserve cannot pay.</b> The gain of a sale is limited by what the Reserve holds
 * and by what is left of today's exchange budget. A sale is never quietly cut short: its quote
 * says what can be paid and what would be given up, and it only goes ahead when the request
 * accepts that amount. The principal always comes back in full.
 *
 * <p><b>Bounds.</b> At most {@link #MAX_PORTFOLIOS} accounts; a portfolio that holds nothing is
 * dropped at once; a holding is a dozen lots at most ({@link Holding}); bonds per account are
 * capped by {@code maxBondsPerPlayer}. <b>Erasure</b> {@link #forgetPlayer}. <b>Export</b>
 * {@link #exportFor}.
 *
 * <p>The practice book ({@link PracticeBook}) is a different class with no way to reach the store:
 * nothing in this file is shared with it but the pure arithmetic of {@link Quotes},
 * {@link ExchangeMath} and {@link Holding}.
 */
public final class ExchangeDesk {
    /** The most accounts that may hold a real portfolio. */
    public static final int MAX_PORTFOLIOS = 65_536;
    /** Asks {@link #sell} not to go ahead at a reduced gain. */
    public static final long FULL_GAIN_ONLY = -1L;
    /** No ceiling on what a purchase may take from the balance. */
    public static final long ANY_PRICE = Long.MAX_VALUE;
    /**
     * A dividend is {@code units × price × yield / 7}: the price is in hundredths of a credit and
     * the yield in hundredths of a percent per week, so the product is divided by 100 × 10000 × 7.
     */
    static final long DIVIDEND_DIVISOR = 7_000_000L;

    /** One account's portfolio as saved. */
    record Row(String account, Portfolio portfolio) {
        static final Codec<Row> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("account").forGetter(Row::account),
                Portfolio.CODEC.fieldOf("portfolio").forGetter(Row::portfolio)).apply(instance, Row::new));
    }

    private final ExchangeSection section;
    private final Map<AccountId, Portfolio> portfolios = new LinkedHashMap<>();

    ExchangeDesk(ExchangeSection section) { this.section = section; }

    // --- persistence -------------------------------------------------------------------------

    List<Row> rows() {
        List<Row> rows = new ArrayList<>(portfolios.size());
        portfolios.forEach((account, portfolio) -> rows.add(new Row(account.stableKey(), portfolio)));
        return rows;
    }

    void load(List<Row> rows) {
        for (Row row : rows) {
            if (row == null || row.portfolio() == null || row.portfolio().isEmpty()) continue;
            AccountId account = Accounts.parse(row.account());
            if (!(account instanceof PlayerAccountId)) continue;
            if (portfolios.size() >= MAX_PORTFOLIOS) break;
            portfolios.putIfAbsent(account, row.portfolio());
        }
    }

    // --- reads -------------------------------------------------------------------------------

    /**
     * Everything the exchange account should hold: the cost of every open share and index
     * position, the principal of every open bond and the interest reserved for those bonds.
     * Invariant C is this against the balance of {@code system:exchange}.
     */
    public long held() {
        synchronized (section) {
            long total = 0L;
            for (Portfolio portfolio : portfolios.values()) total = ExchangeMath.add(total, portfolio.held());
            return total;
        }
    }

    /** The same total, in its three parts: share and index cost, bond principal, reserved interest. */
    public long[] heldParts() {
        synchronized (section) {
            long[] parts = new long[3];
            for (Portfolio portfolio : portfolios.values()) {
                parts[0] = ExchangeMath.add(parts[0], portfolio.shareCost());
                parts[1] = ExchangeMath.add(parts[1], portfolio.bondPrincipal());
                parts[2] = ExchangeMath.add(parts[2], portfolio.bondInterest());
            }
            return parts;
        }
    }

    /** How many accounts hold a real portfolio. */
    public int size() { synchronized (section) { return portfolios.size(); } }

    public boolean isEmpty() { synchronized (section) { return portfolios.isEmpty(); } }

    /** The live portfolio of an account, or null. For the views and the tests; do not keep it. */
    public Portfolio portfolio(AccountId account) {
        synchronized (section) { return account == null ? null : portfolios.get(account); }
    }

    /** Whether any account holds the stock. */
    boolean anyHolds(String stockId) {
        for (Portfolio portfolio : portfolios.values()) if (portfolio.holding(stockId) != null) return true;
        return false;
    }

    // --- shares and the index fund -----------------------------------------------------------

    /** What buying {@code units} would come to now. Moves nothing and counts against no limit. */
    public TradeQuote quoteBuy(ExchangeEnv env, AccountId account, String stockId, long units) {
        synchronized (section) {
            TradeResult gate = gate(env, account, true);
            if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, units);
            return buyQuote(env, account, stockId, units, 0L, now());
        }
    }

    /** What buying as many whole units as {@code credits} cover, fee included, would come to now. */
    public TradeQuote quoteBuyForCredits(ExchangeEnv env, AccountId account, String stockId, long credits) {
        synchronized (section) {
            TradeResult gate = gate(env, account, true);
            if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, 0L);
            return buyQuote(env, account, stockId, 0L, credits, now());
        }
    }

    /**
     * Buys {@code units} at the ask as it is now.
     *
     * @param maxSpend the most the purchase may take from the balance, fee included: what the
     *                 confirm step showed. A price that has since risen past it is refused with
     *                 {@link TradeResult#PRICE_MOVED}. {@link #ANY_PRICE} for no ceiling
     */
    public TradeQuote buy(ExchangeEnv env, AccountId account, String stockId, long units, long maxSpend) {
        synchronized (section) {
            TradeResult gate = act(env, account, true);
            if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, units);
            long now = now();
            return completeBuy(env, account, buyQuote(env, account, stockId, units, 0L, now), maxSpend, now);
        }
    }

    /** Buys as many whole units as {@code credits} cover at the ask as it is now, fee included. */
    public TradeQuote buyForCredits(ExchangeEnv env, AccountId account, String stockId, long credits) {
        synchronized (section) {
            TradeResult gate = act(env, account, true);
            if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.BUY, stockId, 0L);
            long now = now();
            return completeBuy(env, account, buyQuote(env, account, stockId, 0L, credits, now), credits, now);
        }
    }

    private TradeQuote buyQuote(ExchangeEnv env, AccountId account, String stockId, long units, long credits, long now) {
        Portfolio portfolio = portfolios.get(account);
        if (portfolio == null && portfolios.size() >= MAX_PORTFOLIOS)
            return TradeQuote.refused(TradeResult.EXCHANGE_FULL, TradeSide.BUY, stockId, units);
        Listing listing = section.listing(env, stockId);
        Holding holding = portfolio == null ? null : portfolio.holding(stockId);
        long invested = portfolio == null ? 0L : portfolio.invested();
        long balance = ledger().getBalance(account, NeroEconomyCommon.CREDITS);
        return credits > 0L
                ? Quotes.buyForCredits(env.terms(), listing, holding, invested, balance, stockId, credits, now)
                : Quotes.buy(env.terms(), listing, holding, invested, balance, stockId, units, now);
    }

    private TradeQuote completeBuy(ExchangeEnv env, AccountId account, TradeQuote quote, long maxSpend, long now) {
        if (!quote.ok()) return quote;
        if (quote.net() > maxSpend) return quote.with(TradeResult.PRICE_MOVED);
        // Shares need earned credits: a starting balance that has not vested could otherwise come back
        // unlocked from a sale. Only the fee, which nothing ever returns, may use it.
        LedgerResult moved = store().execute(LedgerPlan.builder()
                .movePlayerFacing(account, SystemAccountId.EXCHANGE, quote.total(), TransactionType.SHARE_BUY)
                .move(account, SystemAccountId.RESERVE, quote.fee(), TransactionType.FEE_EXCHANGE).build());
        if (!moved.ok()) return quote.with(refusal(moved));
        Holding holding = portfolios.computeIfAbsent(account, ignored -> new Portfolio()).holdingFor(quote.stockId());
        holding.settle(now, env.terms().holdMillis(), section.seasonedBefore(now));
        holding.add(quote.units(), quote.total(), now);
        section.setDirty();
        return quote;
    }

    /**
     * What selling {@code units} — or everything that may be sold, for {@link Quotes#ALL} — would
     * come to now. Moves nothing. The result is {@link TradeResult#GAIN_REDUCED} when the gain
     * cannot be paid in full; {@link TradeQuote#gainPayable()} is then what a sale may accept.
     */
    public TradeQuote quoteSell(ExchangeEnv env, AccountId account, String stockId, long units) {
        synchronized (section) {
            TradeResult gate = gate(env, account, true);
            if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.SELL, stockId, units == Quotes.ALL ? 0L : units);
            return sellQuote(env, account, stockId, units, now());
        }
    }

    /**
     * Sells {@code units} — or everything that may be sold, for {@link Quotes#ALL} — at the bid as
     * it is now.
     *
     * @param acceptReducedGain {@link #FULL_GAIN_ONLY} to sell only when the whole gain can be
     *                          paid; otherwise the reduced gain the quote showed and the account
     *                          accepted. The sale then goes ahead when at least that much can be
     *                          paid, and is refused with {@link TradeResult#GAIN_CHANGED} when it
     *                          no longer can
     */
    public TradeQuote sell(ExchangeEnv env, AccountId account, String stockId, long units, long acceptReducedGain) {
        synchronized (section) {
            TradeResult gate = act(env, account, true);
            if (!gate.ok()) return TradeQuote.refused(gate, TradeSide.SELL, stockId, units == Quotes.ALL ? 0L : units);
            long now = now();
            TradeQuote quote = sellQuote(env, account, stockId, units, now);
            if (quote.result() == TradeResult.GAIN_REDUCED) {
                if (acceptReducedGain < 0L) return quote;
                if (quote.gainPayable() < acceptReducedGain) return quote.with(TradeResult.GAIN_CHANGED);
                quote = quote.with(TradeResult.OK);
            }
            if (!quote.ok()) return quote;
            LedgerResult moved = store().execute(salePlan(account, quote));
            if (!moved.ok()) return quote.with(refusal(moved));
            Portfolio portfolio = portfolios.get(account);
            portfolio.holding(stockId).take(quote.units(), now, env.terms().holdMillis());
            portfolio.dropIfEmpty(stockId);
            if (portfolio.isEmpty()) portfolios.remove(account);
            section.setDirty();
            return quote;
        }
    }

    private TradeQuote sellQuote(ExchangeEnv env, AccountId account, String stockId, long units, long now) {
        Portfolio portfolio = portfolios.get(account);
        return Quotes.sell(env.terms(), section.listing(env, stockId), portfolio == null ? null
                : portfolio.holding(stockId), stockId, units, now, gainCap());
    }

    /** The one plan of a sale; see the table on the class. */
    private static LedgerPlan salePlan(AccountId account, TradeQuote quote) {
        LedgerPlan.Builder plan = LedgerPlan.builder();
        if (quote.loss() > 0L) {
            plan.move(SystemAccountId.EXCHANGE, account, quote.total(), TransactionType.SHARE_SELL);
            plan.move(SystemAccountId.EXCHANGE, SystemAccountId.RESERVE, quote.loss(), TransactionType.CAPITAL_LOSS);
        } else {
            plan.move(SystemAccountId.EXCHANGE, account, quote.costBasis(), TransactionType.SHARE_SELL);
            plan.pay(BudgetKind.EXCHANGE, account, quote.gainPayable(), TransactionType.CAPITAL_GAIN);
        }
        return plan.move(account, SystemAccountId.RESERVE, quote.fee(), TransactionType.FEE_EXCHANGE).build();
    }

    /** What the Reserve can pay out for the exchange right now: its balance, within what is left of today's budget. */
    private long gainCap() {
        EconomySavedData ledger = ledger();
        return Math.min(ledger.balance(SystemAccountId.RESERVE), ledger.budgetRemaining(BudgetKind.EXCHANGE));
    }

    // --- Savings Bonds -----------------------------------------------------------------------

    /**
     * Buys a Savings Bond of {@code principal}. The interest for the whole term is moved from the
     * Reserve into the exchange account now, so the bond cannot fail to pay; when the Reserve or
     * today's exchange budget cannot cover it the bond is refused
     * ({@link TradeResult#BONDS_SOLD_OUT}). No fee.
     *
     * @param id the bond's id, or null for a new random one
     */
    public BondOutcome buyBond(ExchangeEnv env, AccountId account, BondTerm term, long principal, UUID id) {
        synchronized (section) {
            TradeResult gate = act(env, account, true);
            if (!gate.ok()) return BondOutcome.refused(gate);
            if (term == null || principal < 1L) return BondOutcome.refused(TradeResult.INVALID);
            Portfolio portfolio = portfolios.get(account);
            if (portfolio == null && portfolios.size() >= MAX_PORTFOLIOS) return BondOutcome.refused(TradeResult.EXCHANGE_FULL);
            if (portfolio != null && id != null && portfolio.bond(id) != null) return BondOutcome.refused(TradeResult.INVALID);
            TradingTerms terms = env.terms();
            if ((portfolio == null ? 0 : portfolio.bondCount()) >= terms.maxBonds())
                return BondOutcome.refused(TradeResult.BOND_LIMIT);
            if (ExchangeMath.add(portfolio == null ? 0L : portfolio.invested(), principal) > terms.maxPortfolio())
                return BondOutcome.refused(TradeResult.PORTFOLIO_LIMIT);
            if (principal > ledger().getBalance(account, NeroEconomyCommon.CREDITS))
                return BondOutcome.refused(TradeResult.INSUFFICIENT_FUNDS);
            long interest = BondTerm.interest(principal, terms.bondRateBps(term));
            if (interest > gainCap()) return BondOutcome.refused(TradeResult.BONDS_SOLD_OUT);
            // Earned credits only: cashed in early, the principal comes back in full and unlocked.
            LedgerResult moved = store().execute(LedgerPlan.builder()
                    .movePlayerFacing(account, SystemAccountId.EXCHANGE, principal, TransactionType.BOND_BUY)
                    .pay(BudgetKind.EXCHANGE, SystemAccountId.EXCHANGE, interest, TransactionType.BOND_INTEREST_RESERVED)
                    .build());
            if (!moved.ok()) return BondOutcome.refused(moved == LedgerResult.RESERVE_SHORT
                    || moved == LedgerResult.BUDGET_SPENT ? TradeResult.BONDS_SOLD_OUT : refusal(moved));
            long now = now();
            Bond bond = new Bond(id == null ? UUID.randomUUID() : id, term, principal, interest, now,
                    ExchangeMath.add(now, term.millis()));
            portfolios.computeIfAbsent(account, ignored -> new Portfolio()).add(bond);
            section.setDirty();
            return new BondOutcome(TradeResult.OK, bond, 0L, 0L);
        }
    }

    /**
     * Cashes a bond in. Before maturity it returns the principal only and the reserved interest
     * goes back to the Reserve; a bond that has matured but not been paid out yet pays in full.
     * This takes money out of the exchange, so it works while {@code exchangeEnabled} is false.
     */
    public BondOutcome redeemBond(ExchangeEnv env, AccountId account, UUID id) {
        synchronized (section) {
            TradeResult gate = act(env, account, false);
            if (!gate.ok()) return BondOutcome.refused(gate);
            Portfolio portfolio = portfolios.get(account);
            if (portfolio == null || id == null) return BondOutcome.refused(TradeResult.BOND_NOT_FOUND);
            Bond bond = portfolio.bond(id);
            if (bond == null) return BondOutcome.refused(TradeResult.BOND_NOT_FOUND);
            boolean matured = bond.matured(now());
            LedgerResult moved = store().execute(payOutPlan(account, bond, matured));
            if (!moved.ok()) return BondOutcome.refused(refusal(moved));
            drop(account, portfolio, bond);
            return new BondOutcome(TradeResult.OK, bond, matured ? bond.held() : bond.principal(),
                    matured ? bond.interest() : 0L);
        }
    }

    private static LedgerPlan payOutPlan(AccountId account, Bond bond, boolean withInterest) {
        LedgerPlan.Builder plan = LedgerPlan.builder()
                .move(SystemAccountId.EXCHANGE, account, bond.principal(), TransactionType.BOND_REDEEM);
        if (withInterest) plan.move(SystemAccountId.EXCHANGE, account, bond.interest(), TransactionType.BOND_INTEREST);
        else plan.move(SystemAccountId.EXCHANGE, SystemAccountId.RESERVE, bond.interest(),
                TransactionType.BOND_INTEREST_RELEASED);
        return plan.build();
    }

    private void drop(AccountId account, Portfolio portfolio, Bond bond) {
        portfolio.remove(bond);
        if (portfolio.isEmpty()) portfolios.remove(account);
        section.setDirty();
    }

    /**
     * Pays out every bond that has matured at {@code time}: principal and interest to the owner's
     * balance, whether or not they are online. A bond whose payment the ledger refuses stays and
     * is tried again at the next step.
     *
     * @return how many bonds were paid out
     */
    public int matureBonds(ExchangeEnv env, long time) {
        synchronized (section) {
            if (EconomyHealth.frozen() || store() == null || portfolios.isEmpty()) return 0;
            int paid = 0;
            for (Map.Entry<AccountId, Portfolio> entry : new ArrayList<>(portfolios.entrySet())) {
                boolean any = false;
                for (Bond bond : entry.getValue().bonds()) {
                    if (!bond.matured(time)) continue;
                    if (!store().execute(payOutPlan(entry.getKey(), bond, true)).ok()) continue;
                    drop(entry.getKey(), entry.getValue(), bond);
                    any = true;
                    paid++;
                }
                if (any && env != null) env.alerts().raise(entry.getKey(), EconomyAlerts.Kind.BOND_MATURED);
            }
            return paid;
        }
    }

    // --- dividends ---------------------------------------------------------------------------

    /**
     * The dividends of the economy-day that begins at day {@code day}: for every holder of a
     * company that pays one, {@code units held for a full day × price × yield / 7}, rounded down
     * per holding. The whole day's batch is paid from the Reserve under the exchange budget; when
     * that is short, everyone is paid the same smaller share.
     *
     * <p>A unit has been held for a full day when its lot was bought at or before the start of
     * the day that has just ended.
     */
    DividendRun payDividends(ExchangeEnv env, long day) {
        synchronized (section) {
            long cutoff = (day - 1L) * PriceModel.DAY_MILLIS;
            Map<UUID, Long> due = new LinkedHashMap<>();
            for (Map.Entry<AccountId, Portfolio> entry : portfolios.entrySet()) {
                long amount = dividend(env, entry.getValue(), cutoff);
                if (amount > 0L) due.put(((PlayerAccountId) entry.getKey()).playerId(), amount);
            }
            if (due.isEmpty()) return new DividendRun(day, 0L, 0L, 1L, 1L, 0);
            Reserve.ProRata paid = store().payProRata(BudgetKind.EXCHANGE, due, TransactionType.DIVIDEND);
            int accounts = 0;
            for (Map.Entry<AccountId, Long> share : paid.shares().entrySet()) {
                if (share.getValue() <= 0L) continue;
                Portfolio portfolio = portfolios.get(share.getKey());
                if (portfolio != null) portfolio.paidDividend(day, share.getValue());
                env.alerts().raise(share.getKey(), EconomyAlerts.Kind.DIVIDEND_PAID);
                if (share.getKey() instanceof PlayerAccountId holder)
                    store().milestones().earn(holder.playerId(), MilestoneSection.Milestone.FIRST_DIVIDEND);
                accounts++;
            }
            section.setDirty();
            return new DividendRun(day, paid.requested(), paid.paid(), paid.numerator(), paid.denominator(), accounts);
        }
    }

    /** What one portfolio is due: shared with nothing — the practice book works its own out. */
    private long dividend(ExchangeEnv env, Portfolio portfolio, long cutoff) {
        long total = 0L;
        for (Holding holding : portfolio.holdings()) {
            long[] terms = section.dividendTerms(env.registry(), holding.stockId());
            if (terms == null) continue;
            total = ExchangeMath.add(total, ExchangeMath.mulMulDiv(holding.unitsHeldSince(cutoff), terms[0], terms[1],
                    DIVIDEND_DIVISOR));
        }
        return total;
    }

    // --- delisting and splits ----------------------------------------------------------------

    /**
     * Pays every holder of a delisted company out at {@code bidCents}: the sale of the whole
     * holding, whatever its age, through the same plan as any other sale. The principal always
     * comes back; a gain is paid as far as the Reserve and today's budget allow, without asking.
     *
     * @return how many holdings were paid out; a holding whose payment the ledger refused stays
     */
    int payOutDelisted(ExchangeEnv env, String stockId, long bidCents) {
        int paid = 0;
        for (Map.Entry<AccountId, Portfolio> entry : new ArrayList<>(portfolios.entrySet())) {
            Portfolio portfolio = entry.getValue();
            Holding holding = portfolio.holding(stockId);
            if (holding == null) continue;
            TradeQuote quote = Quotes.payOut(env.terms(), holding, bidCents, gainCap());
            if (!store().execute(salePlan(entry.getKey(), quote)).ok()) continue;
            portfolio.remove(stockId);
            if (portfolio.isEmpty()) portfolios.remove(entry.getKey());
            env.alerts().raise(entry.getKey(), EconomyAlerts.Kind.STOCK_DELISTED);
            section.setDirty();
            paid++;
        }
        return paid;
    }

    /** A share split: every holding of the stock holds {@code factor} times the units, at the cost it had. */
    void split(String stockId, int factor) {
        for (Portfolio portfolio : portfolios.values()) {
            Holding holding = portfolio.holding(stockId);
            if (holding != null) holding.split(factor);
        }
    }

    /** Merges old lots in every portfolio, so no holding grows. */
    void settle(TradingTerms terms, long now) {
        long seasoned = section.seasonedBefore(now);
        boolean changed = false;
        for (Portfolio portfolio : portfolios.values()) changed |= portfolio.settle(now, terms.holdMillis(), seasoned);
        if (changed) section.setDirty();
    }

    // --- erasure and export ------------------------------------------------------------------

    /**
     * Erases one player's real portfolio: the cost of their shares, the principal of their bonds
     * and the interest reserved for them move from the exchange account to the Reserve as
     * {@code ERASURE_SWEEP}, and the holdings are removed. Works while frozen.
     *
     * @return the credits swept
     */
    public long forgetPlayer(UUID player) {
        synchronized (section) {
            if (player == null) return 0L;
            Portfolio portfolio = portfolios.remove(new PlayerAccountId(player));
            if (portfolio == null) return 0L;
            long held = portfolio.held();
            EconomySavedData ledger = ledger();
            if (ledger != null && held > 0L) ledger.sweepErasedExchange(held);
            section.setDirty();
            return held;
        }
    }

    /** Data-subject export: the subject's own holdings and bonds. Nobody else is involved in any of it. */
    public JsonObject exportFor(UUID player) {
        synchronized (section) {
            return export(player == null ? null : portfolios.get(new PlayerAccountId(player)));
        }
    }

    /** One portfolio as export rows: stock id, units, cost and dates; bond term, amounts and dates. */
    static JsonObject export(Portfolio portfolio) {
        JsonObject out = new JsonObject();
        JsonArray holdings = new JsonArray();
        JsonArray bonds = new JsonArray();
        if (portfolio != null) {
            for (Holding holding : portfolio.holdings()) {
                JsonObject row = new JsonObject();
                row.addProperty("stock", holding.stockId());
                row.addProperty("units", holding.units());
                row.addProperty("cost", holding.cost());
                JsonArray lots = new JsonArray();
                for (Holding.Lot lot : holding.lots()) {
                    JsonObject value = new JsonObject();
                    value.addProperty("units", lot.units());
                    value.addProperty("cost", lot.cost());
                    value.addProperty("acquired_at", lot.acquiredAt());
                    lots.add(value);
                }
                row.add("lots", lots);
                holdings.add(row);
            }
            for (Bond bond : portfolio.bonds()) {
                JsonObject row = new JsonObject();
                row.addProperty("id", bond.id().toString());
                row.addProperty("term", bond.term().key());
                row.addProperty("principal", bond.principal());
                row.addProperty("interest", bond.interest());
                row.addProperty("bought_at", bond.boughtAt());
                row.addProperty("matures_at", bond.maturesAt());
                bonds.add(row);
            }
            if (portfolio.dividendDay() != StockState.NO_DAY) {
                out.addProperty("last_dividend_day", portfolio.dividendDay());
                out.addProperty("last_dividend", portfolio.dividend());
            }
        }
        out.add("holdings", holdings);
        out.add("bonds", bonds);
        return out;
    }

    // --- internals ---------------------------------------------------------------------------

    /** Whether the account may be quoted for at all. */
    private TradeResult gate(ExchangeEnv env, AccountId account, boolean needsExchangeOn) {
        if (EconomyHealth.frozen()) return TradeResult.FROZEN;
        if (env == null || !(account instanceof PlayerAccountId) || store() == null) return TradeResult.INVALID;
        if (needsExchangeOn && !env.terms().enabled()) return TradeResult.EXCHANGE_OFF;
        return TradeResult.OK;
    }

    /** {@link #gate}, and one action counted against the account's rate limit. */
    private TradeResult act(ExchangeEnv env, AccountId account, boolean needsExchangeOn) {
        TradeResult gate = gate(env, account, needsExchangeOn);
        if (!gate.ok()) return gate;
        // Buying and selling stop while an operator has closed the market; cashing in a bond does not.
        EconomyStore store = store();
        if (needsExchangeOn && store != null && store.marketClosed()) return TradeResult.MARKET_CLOSED;
        return env.limiter().allow(account, now(), env.terms().actionsPerMinute()) ? TradeResult.OK : TradeResult.TOO_FAST;
    }

    private static TradeResult refusal(LedgerResult result) {
        return switch (result) {
            case FROZEN -> TradeResult.FROZEN;
            case INSUFFICIENT_FUNDS -> TradeResult.INSUFFICIENT_FUNDS;
            case FUNDS_LOCKED -> TradeResult.FUNDS_LOCKED;
            default -> TradeResult.NOT_COMPLETED;
        };
    }

    private EconomyStore store() { return section.store(); }

    private EconomySavedData ledger() {
        EconomyStore store = section.store();
        return store == null ? null : store.ledger();
    }

    private long now() { return ledger().now(); }
}
