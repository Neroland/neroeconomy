package za.co.neroland.neroeconomy.book;

/**
 * What was traded in one item on the order book over a stretch of time, after the anti-abuse caps:
 * totals only, never who traded. See {@link BookSection#drainTradedVolume}.
 *
 * @param quantity how many changed hands, counted
 * @param value    for how many credits, counted
 */
public record ItemVolume(long quantity, long value) { }
