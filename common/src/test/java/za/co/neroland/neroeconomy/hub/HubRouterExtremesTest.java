package za.co.neroland.neroeconomy.hub;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.hub.HubPages.RowRef;
import za.co.neroland.neroeconomy.hub.HubRouter.Result;

/**
 * §13 Input (J1 I4): every hub action, with {@code Long.MIN_VALUE}, {@code -1}, {@code 0} and
 * {@code Long.MAX_VALUE} in each number, against a row of every kind and the usual texts. The router
 * never throws; a negative number never reaches a server call (the one exception is the documented
 * "no accepted gain" sentinel); and {@code Long.MAX_VALUE} reaches a call only where the number is a
 * price ceiling or rule amount the economy bounds itself (tested at the book and rules).
 */
class HubRouterExtremesTest {
    private static final long[] EXTREMES = {Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE};
    private static final List<String> TEXTS = List.of("", "minecraft:wheat", "Someone", "BI:16:minecraft:wheat",
            "ST:16:minecraft:wheat");
    /** Where -1 is a sentinel the server call understands: no accepted lower gain. */
    private static final Set<String> NEGATIVE_SENTINELS = Set.of("INVEST_SELL b");
    /**
     * Numbers the router passes on unbounded above because the economy bounds them: a buyer's price
     * ceiling, a seller's or bidder's unit price (refused past 64 bits by the book) and a rule's
     * limit price and total (refused by the rules section).
     */
    private static final Set<String> MAX_PASSES = Set.of("MARKET_BUY b", "MARKET_SELL_QUOTE b", "MARKET_SELL b",
            "MARKET_BID_QUOTE b", "MARKET_BID b", "RULE_ADD a", "RULE_ADD b");

    private static Map<UUID, RowRef> rows() {
        Map<UUID, RowRef> rows = new LinkedHashMap<>();
        long n = 1L;
        for (RowRef.Kind kind : RowRef.Kind.values()) {
            UUID id = new UUID(7L, n++);
            rows.put(id, new RowRef(kind, id, "minecraft:wheat", 0));
        }
        return rows;
    }

    @Test void extremeNumbersInEveryActionAreRefusedOrNeverPassedOnUnbounded() {
        Map<UUID, RowRef> rows = rows();
        List<UUID> targets = new ArrayList<>();
        targets.add(null);
        targets.addAll(rows.keySet());
        Set<String> problems = new java.util.LinkedHashSet<>();
        int reached = 0;
        for (HubAction action : HubAction.values()) {
            HubTab tab = action.tab() == null ? HubTab.WALLET : action.tab();
            HubRouter.Context context = new HubRouter.Context(tab, rows, false, true, true, true);
            for (int field = 0; field < 2; field++) for (long value : EXTREMES) for (UUID target : targets)
                for (String text : TEXTS) {
                    long a = field == 0 ? value : 1L;
                    long b = field == 1 ? value : 1L;
                    HubRouterTest.Recorder recorder = new HubRouterTest.Recorder();
                    Result result;
                    try {
                        result = HubRouter.route(new HubRouter.Intent(UUID.randomUUID(), tab, action, target, a, b, text),
                                context, recorder);
                    } catch (RuntimeException thrown) {
                        problems.add(action + " threw " + thrown.getClass().getSimpleName());
                        continue;
                    }
                    if (result != Result.OK) {
                        if (!recorder.calls.isEmpty()) problems.add(action + " refused but called " + recorder.calls);
                        continue;
                    }
                    reached++;
                    String where = action + (field == 0 ? " a" : " b");
                    for (String call : recorder.calls) {
                        List<String> tokens = Arrays.asList(call.split(" "));
                        if (value < 0L && tokens.contains(Long.toString(value)) && !NEGATIVE_SENTINELS.contains(where))
                            problems.add(where + "=" + value + " reached " + tokens.get(0));
                        if (value == Long.MAX_VALUE && tokens.contains(Long.toString(value)) && !MAX_PASSES.contains(where))
                            problems.add(where + "=MAX reached " + tokens.get(0));
                    }
                }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        // Not a vacuous run: most actions were reached with some combination.
        assertTrue(reached > HubAction.values().length, "reached " + reached);
    }
}
