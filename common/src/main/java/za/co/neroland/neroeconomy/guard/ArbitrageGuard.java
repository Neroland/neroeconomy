package za.co.neroland.neroeconomy.guard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import za.co.neroland.neroeconomy.commodity.CommodityDefinition;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry.Clash;
import za.co.neroland.neroeconomy.guard.GuardReport.Failure;
import za.co.neroland.neroeconomy.guard.GuardReport.Kind;

/**
 * The arbitrage guard: proves that nobody can make credits out of the Merchant Houses by buying
 * goods from them, running those goods through recipes and selling the result back.
 *
 * <p>Pure: it is handed the commodities, the recipes as {@link GuardRecipe}s and a way to look up
 * a tag, so the very same check runs in JUnit against the shipped data and in the game against the
 * live recipe set ({@code /eco admin reload-check}, and after every data-pack reload).
 *
 * <p><b>What it checks</b>, at the prices it is given — the reference prices with each
 * commodity's spread unless the caller supplies others:
 *
 * <ol>
 *   <li>every commodity the Houses both sell and buy has an ask strictly above its bid;</li>
 *   <li>for every recipe whose ingredients can <em>all</em> be had from the Houses — bought
 *       directly at the ask, or made from such goods by earlier recipes, up to {@code depth} steps
 *       — and whose output the Houses buy: the output's revenue at the bid is strictly below what
 *       the ingredients cost. An ingredient with alternatives, or a tag, is priced by its cheapest
 *       member; cost is carried per unit, so a recipe that gives four is a quarter each. Tools,
 *       fuel, energy and time are free: the worst case;</li>
 *   <li>the same for anything redeemable for a fixed amount (the Credit Chip): its ingredients
 *       must cost more than it redeems for;</li>
 *   <li>no item is claimed by two commodities.</li>
 * </ol>
 *
 * <p><b>What a failure switches off.</b> A commodity that fails 1 or 2 must have its NPC
 * <em>buy</em> side switched off: nothing can then be sold to the Houses at a profit through it.
 * For 3 the outlet is not a House, so the NPC <em>sell</em> side of the commodities the chain
 * starts from is switched off instead, and the check is repeated until no redeemable chain is
 * left.
 */
public final class ArbitrageGuard {
    /** How many recipes long a chain is followed when the caller does not say. */
    public static final int DEFAULT_DEPTH = 3;
    public static final int MAX_DEPTH = 6;
    /** The sell side is switched off in rounds; far more than any real data needs. */
    private static final int MAX_REDEEM_ROUNDS = 64;
    /** The most failures a report keeps; counts stay exact for the sides that are switched off. */
    public static final int MAX_FAILURES = 512;

    private ArbitrageGuard() { }

    /** The prices the check runs at. */
    public interface Quotes {
        /** What the Houses sell one unit for. */
        long ask(CommodityDefinition commodity);

        /** What the Houses pay for one unit; 0 = they do not buy it at this price. */
        long bid(CommodityDefinition commodity);

        /** The reference price plus and minus the commodity's own spread. */
        Quotes REFERENCE = new Quotes() {
            @Override public long ask(CommodityDefinition commodity) { return commodity.ask(); }
            @Override public long bid(CommodityDefinition commodity) { return commodity.bid(); }
        };
    }

    /** The ids of the items in a tag (given without {@code #}); empty for a tag that does not exist. */
    public interface Tags {
        Collection<String> items(String tag);

        /** No tag has any member. */
        Tags NONE = tag -> List.of();
    }

    /**
     * Everything one check needs.
     *
     * @param registry       the commodities; its own {@code buyOff}/{@code sellOff} are honoured
     * @param quotes         the prices; null for {@link Quotes#REFERENCE}
     * @param recipes        every recipe that could be read
     * @param recipesSkipped how many recipe files could not be read, for the report
     * @param bonuses        code-side bonuses and extra conversions; null for none
     * @param tags           tag lookup for recipe ingredients; null for none
     * @param redeemable     item id to the most credits one of it can be redeemed for (the Credit
     *                       Chip and {@code creditChipValue}); null or empty for none
     * @param depth          the most recipes a chain may be long, 1 to {@link #MAX_DEPTH}
     */
    public record Input(CommodityRegistry registry, Quotes quotes, List<GuardRecipe> recipes, int recipesSkipped,
            GuardBonuses bonuses, Tags tags, Map<String, Long> redeemable, int depth) {
        public Input {
            if (registry == null) throw new IllegalArgumentException("guard input");
            if (quotes == null) quotes = Quotes.REFERENCE;
            recipes = recipes == null ? List.of() : List.copyOf(recipes);
            if (bonuses == null) bonuses = GuardBonuses.NONE;
            if (tags == null) tags = Tags.NONE;
            redeemable = redeemable == null ? Map.of() : Map.copyOf(redeemable);
            depth = Math.max(1, Math.min(MAX_DEPTH, depth));
        }
    }

    /** What an item costs to get from the Houses, and how. */
    private record Cost(Ratio perUnit, GuardRecipe via, List<Cost> from, int steps, String commodity) { }

    /** A recipe with its tags already looked up. */
    private record Prepared(GuardRecipe recipe, List<List<String>> options) { }

    /** Runs the check. Never throws for bad data: what cannot be read is simply not a chain. */
    public static GuardReport check(Input input) {
        CommodityRegistry registry = input.registry();
        Quotes quotes = input.quotes();
        Map<String, Failure> failures = new LinkedHashMap<>();
        Set<String> buyOff = new TreeSet<>();
        Set<String> sellOff = new TreeSet<>();

        // 1. A commodity that is sold and bought must lose on the round trip.
        Map<String, CommodityDefinition> buyers = new HashMap<>();
        for (CommodityDefinition commodity : registry.commodities()) {
            boolean buys = registry.npcBuys(commodity) && quotes.bid(commodity) >= 1L;
            if (buys && registry.npcSells(commodity) && quotes.ask(commodity) <= quotes.bid(commodity)) {
                failures.put("spread " + commodity.key(), new Failure(Kind.SPREAD, commodity.key(), "",
                        Ratio.of(Math.max(0L, quotes.ask(commodity))).text(), Ratio.of(quotes.bid(commodity)).text(), 0, ""));
                buyOff.add(commodity.key());
            }
            if (buys) for (String item : registry.itemsOf(commodity.key())) buyers.put(item, commodity);
        }

        // The recipes, with every known bonus applied and every tag looked up once.
        List<GuardRecipe> all = new ArrayList<>(input.recipes().size() + input.bonuses().conversions().size());
        for (GuardRecipe recipe : input.recipes()) all.add(recipe.withExtraOutput(input.bonuses().extraOutput(recipe.type())));
        all.addAll(input.bonuses().conversions());
        all.sort(Comparator.comparing(GuardRecipe::id));
        Map<String, List<String>> tagCache = new HashMap<>();
        List<Prepared> prepared = new ArrayList<>(all.size());
        for (GuardRecipe recipe : all) {
            List<List<String>> options = new ArrayList<>(recipe.inputs().size());
            boolean usable = true;
            for (GuardRecipe.Input ingredient : recipe.inputs()) {
                Set<String> items = new LinkedHashSet<>();
                for (String option : ingredient.options()) {
                    if (option.charAt(0) != '#') items.add(option);
                    else items.addAll(tagCache.computeIfAbsent(option.substring(1), tag -> {
                        Collection<String> members = input.tags().items(tag);
                        return members == null ? List.of() : List.copyOf(members);
                    }));
                }
                if (items.isEmpty()) {
                    usable = false;
                    break;
                }
                options.add(List.copyOf(items));
            }
            if (usable) prepared.add(new Prepared(recipe, options));
        }

        // 2 and 3. Chains to goods the Houses buy, then chains to anything redeemable.
        Set<String> compared = new HashSet<>();
        boolean first = true;
        for (int round = 0; round < MAX_REDEEM_ROUNDS; round++) {
            Map<String, Cost> costs = costs(input, prepared, sellOff, first ? buyers : Map.of(), failures, buyOff, compared);
            first = false;
            boolean changed = false;
            for (Map.Entry<String, Long> redeem : new TreeMap<>(input.redeemable()).entrySet()) {
                Cost cost = costs.get(redeem.getKey());
                if (cost == null || cost.via() == null || redeem.getValue() == null || redeem.getValue() < 1L) continue;
                compared.add(cost.via().id());
                Ratio value = Ratio.of(redeem.getValue());
                if (value.lessThan(cost.perUnit())) continue;
                failures.putIfAbsent("redeem " + redeem.getKey() + ' ' + cost.via().id() + ' ' + round,
                        new Failure(Kind.REDEEM, redeem.getKey(), cost.via().id(),
                                cost.perUnit().times(cost.via().count()).text(), value.times(cost.via().count()).text(),
                                cost.steps(), ""));
                Set<String> leaves = new TreeSet<>();
                leaves(cost, leaves);
                if (sellOff.addAll(leaves)) changed = true;
            }
            if (!changed) break;
        }

        // 4. Clashes were settled when the files were resolved; they are reported here.
        for (Clash clash : registry.report().clashes())
            failures.putIfAbsent("clash " + clash.item() + ' ' + clash.dropped() + ' ' + clash.droppedFile(),
                    new Failure(Kind.CLASH, clash.dropped(), clash.item(), "", "", 0, clash.kept()));

        List<Failure> listed = new ArrayList<>(failures.values());
        listed.sort(Comparator.comparing(Failure::kind).thenComparing(Failure::commodity).thenComparing(Failure::recipe));
        if (listed.size() > MAX_FAILURES) listed = listed.subList(0, MAX_FAILURES);
        return new GuardReport(listed, buyOff, sellOff, registry.commodities().size(), all.size(), input.recipesSkipped(),
                compared.size(), input.depth());
    }

    /**
     * The cheapest way to get each item from the Houses within {@code depth} steps. While it works
     * that out, every reachable recipe whose output is in {@code buyers} is compared with that
     * output's bid, and a failure is recorded.
     */
    private static Map<String, Cost> costs(Input input, List<Prepared> prepared, Set<String> sellOff,
            Map<String, CommodityDefinition> buyers, Map<String, Failure> failures, Set<String> buyOff,
            Set<String> compared) {
        CommodityRegistry registry = input.registry();
        Map<String, Cost> cost = new HashMap<>();
        for (CommodityDefinition commodity : registry.commodities()) {
            if (!registry.npcSells(commodity) || sellOff.contains(commodity.key())) continue;
            Cost bought = new Cost(Ratio.of(Math.max(1L, input.quotes().ask(commodity))), null, List.of(), 0,
                    commodity.key());
            for (String item : registry.itemsOf(commodity.key())) cost.put(item, bought);
        }
        if (cost.isEmpty()) return cost;
        Map<String, Ratio> cheapest = new HashMap<>();
        for (int step = 1; step <= input.depth(); step++) {
            Map<String, Cost> next = new HashMap<>(cost);
            boolean changed = false;
            for (Prepared entry : prepared) {
                GuardRecipe recipe = entry.recipe();
                Ratio total = Ratio.ZERO;
                List<Cost> from = new ArrayList<>(recipe.inputs().size());
                int steps = 0;
                boolean reachable = true;
                for (int index = 0; index < recipe.inputs().size(); index++) {
                    Cost best = null;
                    for (String item : entry.options().get(index)) {
                        Cost known = cost.get(item);
                        if (known != null && (best == null || known.perUnit().lessThan(best.perUnit()))) best = known;
                    }
                    if (best == null) {
                        // An ingredient the Houses do not sell and nothing bought from them makes.
                        reachable = false;
                        break;
                    }
                    total = total.plus(best.perUnit().times(recipe.inputs().get(index).count()));
                    from.add(best);
                    steps = Math.max(steps, best.steps());
                }
                if (!reachable) continue;

                CommodityDefinition buyer = buyers.get(recipe.result());
                if (buyer != null) {
                    compared.add(recipe.id());
                    Ratio revenue = Ratio.of(input.quotes().bid(buyer)).times(recipe.count());
                    if (!revenue.lessThan(total)) {
                        String key = "chain " + buyer.key() + ' ' + recipe.id();
                        // The cheapest way to run the recipe is the one worth reporting.
                        Ratio before = cheapest.get(key);
                        if (before == null || total.lessThan(before)) {
                            cheapest.put(key, total);
                            failures.put(key, new Failure(Kind.CHAIN, buyer.key(), recipe.id(), total.text(),
                                    revenue.text(), steps + 1, ""));
                        }
                        buyOff.add(buyer.key());
                    }
                }

                Ratio perUnit = total.dividedBy(recipe.count());
                Cost known = next.get(recipe.result());
                if (known == null || perUnit.lessThan(known.perUnit())) {
                    next.put(recipe.result(), new Cost(perUnit, recipe, List.copyOf(from), steps + 1, null));
                    changed = true;
                }
            }
            cost = next;
            if (!changed) break;
        }
        return cost;
    }

    /** The commodities a chain starts from: what was bought from the Houses. */
    private static void leaves(Cost cost, Set<String> out) {
        if (cost.via() == null) {
            if (cost.commodity() != null) out.add(cost.commodity());
            return;
        }
        for (Cost input : cost.from()) leaves(input, out);
    }
}
