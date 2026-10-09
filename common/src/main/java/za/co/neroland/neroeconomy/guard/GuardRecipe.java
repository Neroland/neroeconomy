package za.co.neroland.neroeconomy.guard;

import java.util.List;

/**
 * One recipe as the arbitrage guard sees it: what goes in, what comes out and how many. Tools,
 * fuel, energy and time are not part of it — the guard assumes they are free, which is the worst
 * case for the Merchant Houses.
 *
 * @param id     the recipe's id ({@code minecraft:iron_ingot_from_smelting_raw_iron})
 * @param type   its type ({@code minecraft:smelting}), used to apply a known output bonus
 * @param inputs every ingredient that is used up by one operation
 * @param result the item id that comes out
 * @param count  how many come out of one operation; at least 1
 */
public record GuardRecipe(String id, String type, List<Input> inputs, String result, int count) {
    /** The most of anything one operation may take or give; a recipe that says more is not read. */
    public static final int MAX_COUNT = 6400;

    public GuardRecipe {
        if (id == null || type == null || inputs == null || inputs.isEmpty() || result == null || result.isEmpty()
                || count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("guard recipe");
        inputs = List.copyOf(inputs);
    }

    /**
     * One ingredient.
     *
     * @param options what may be used for it: item ids, and tag ids with a leading {@code #}; any
     *                one of them does, so the guard takes the cheapest
     * @param count   how many one operation uses up; at least 1
     */
    public record Input(List<String> options, int count) {
        public Input {
            if (options == null || options.isEmpty() || count < 1 || count > MAX_COUNT)
                throw new IllegalArgumentException("guard recipe input");
            options = List.copyOf(options);
        }
    }

    /** The same recipe giving {@code extra} more per operation. */
    public GuardRecipe withExtraOutput(int extra) {
        return extra <= 0 ? this : new GuardRecipe(id, type, inputs, result, Math.min(MAX_COUNT, count + extra));
    }
}
