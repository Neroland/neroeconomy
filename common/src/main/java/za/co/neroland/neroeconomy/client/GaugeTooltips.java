package za.co.neroland.neroeconomy.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import za.co.neroland.neroeconomy.exchange.PriceText;

/**
 * Hover tooltips for NeroEconomy's meters: the Auto-Trader's progress well and the Economy Hub's price
 * chart. Each names itself, gives how much (amount of limit, low / high) and the rate (share of the daily
 * limit used, change across the chart). Builders append lines to a list; numbers come from
 * {@link GaugeMath} and {@link PriceText}; every word is an English source lang key under
 * {@code gui.neroeconomy.gauge.}. Only figures the screen already shows the viewer about their own rule or
 * a public price; no identities, no coordinates.
 */
public final class GaugeTooltips {

    private GaugeTooltips() {
    }

    /** A {@code gui.neroeconomy.gauge.<key>} line. */
    public static Component tr(String key, Object... args) {
        return Component.translatable("gui.neroeconomy.gauge." + key, args);
    }

    // --- price chart ------------------------------------------------------------------------------

    /**
     * One half of a price chart: which half, the latest price with the low and high in view, and the
     * change from the first point to the last. {@code cents} is the series as drawn.
     */
    public static void priceSeries(List<Component> out, boolean daily, long[] cents, boolean halted, boolean split) {
        out.add(tr(daily ? "chart.daily" : "chart.recent"));
        if (cents.length == 0) {
            out.add(tr("chart.empty"));
            return;
        }
        long low = Long.MAX_VALUE;
        long high = Long.MIN_VALUE;
        for (long point : cents) {
            low = Math.min(low, point);
            high = Math.max(high, point);
        }
        long first = cents[0];
        long last = cents[cents.length - 1];
        out.add(tr("chart.now", PriceText.credits(last)));
        out.add(tr("chart.range", PriceText.credits(low), PriceText.credits(high)));
        if (cents.length > 1) {
            out.add(tr("chart.change", PriceText.percent(GaugeMath.changeBasisPoints(first, last)),
                    GaugeMath.grouped(cents.length)));
        }
        if (split) {
            out.add(tr("chart.split"));
        }
        if (halted) {
            out.add(tr("chart.halted"));
        }
    }

    // --- Auto-Trader progress ---------------------------------------------------------------------

    /**
     * The Auto-Trader's progress well: the rule's status, today's fills against the daily limit, the
     * total against the rule's budget or quantity, and the owner's rules value against their daily cap.
     */
    public static void autoTrader(List<Component> out, Component status, boolean hasRule, boolean buys,
            long filledToday, long perDay, long done, long total, long valueToday, long valueCap) {
        out.add(tr("auto_trader"));
        out.add(status);
        if (hasRule) {
            out.add(tr("auto_trader.today", GaugeMath.grouped(filledToday), GaugeMath.grouped(perDay),
                    GaugeMath.percent(filledToday, perDay)));
            out.add(tr(buys ? "auto_trader.spent" : "auto_trader.sold", GaugeMath.grouped(done),
                    GaugeMath.grouped(total), GaugeMath.percent(done, total)));
        }
        out.add(tr("auto_trader.value", GaugeMath.grouped(valueToday), GaugeMath.grouped(valueCap),
                GaugeMath.percent(valueToday, valueCap)));
    }

    // --- emission ---------------------------------------------------------------------------------

    /** The lines as one component, one per line (the Hub's tooltips are a single wrapped component). */
    public static Component joined(List<Component> lines) {
        MutableComponent all = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                all.append("\n");
            }
            all.append(lines.get(i));
        }
        return all;
    }

    /** Every line wrapped to {@code width} pixels, ready for {@link GuiCompat#tooltip}. */
    public static List<FormattedCharSequence> sequences(Font font, List<Component> lines, int width) {
        List<FormattedCharSequence> out = new ArrayList<>(lines.size());
        int w = Math.max(60, width);
        for (Component line : lines) {
            out.addAll(font.split(line, w));
        }
        return out;
    }

    /** The wrap width for a screen {@code screenWidth} wide: half the window, 100..220 px. */
    public static int wrapWidth(int screenWidth) {
        return Math.max(100, Math.min(220, screenWidth / 2));
    }
}
