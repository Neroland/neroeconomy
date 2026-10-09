package za.co.neroland.neroeconomy.hub;

import za.co.neroland.neroeconomy.rules.RuleSide;

/**
 * The text a "New rule" form sends in the one string a hub intent carries: side, kind, quantity per
 * day and target, as {@code <B|S><I|T>:<perDay>:<target>} — {@code BI:16:minecraft:diamond} buys up
 * to sixteen diamonds a day, {@code ST:64:c:ingots} sells up to 64 of anything in the tag a day.
 * The limit price and the total travel in the intent's two numbers. Pure Java.
 *
 * @param tag    whether {@code target} is an item tag, written without its leading {@code #}
 * @param perDay at least 1
 */
public record RuleForm(RuleSide side, boolean tag, int perDay, String target) {
    /** The longest target id a form carries: the same bound a lot's item id has. */
    public static final int MAX_TARGET = 200;

    public String encode() {
        return (side == RuleSide.BUY ? "B" : "S") + (tag ? "T" : "I") + ":" + perDay + ":" + target;
    }

    /** The form a text stands for; null when it is malformed in any way. */
    public static RuleForm parse(String text) {
        if (text == null || text.length() < 6 || text.length() > MAX_TARGET + 16) return null;
        char side = text.charAt(0);
        char kind = text.charAt(1);
        if ((side != 'B' && side != 'S') || (kind != 'I' && kind != 'T') || text.charAt(2) != ':') return null;
        int split = text.indexOf(':', 3);
        if (split < 0) return null;
        int perDay;
        try {
            perDay = Integer.parseInt(text.substring(3, split));
        } catch (NumberFormatException notANumber) {
            return null;
        }
        String target = text.substring(split + 1).trim();
        if (target.startsWith("#")) target = target.substring(1);
        if (perDay < 1 || target.isEmpty() || target.length() > MAX_TARGET || target.indexOf(' ') >= 0) return null;
        return new RuleForm(side == 'B' ? RuleSide.BUY : RuleSide.SELL, kind == 'T', perDay, target);
    }
}
