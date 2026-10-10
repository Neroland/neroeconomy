package za.co.neroland.neroeconomy.sibling;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import za.co.neroland.neroeconomy.api.ExchangeApi;

/**
 * Sector indices: the equal-weighted mean of the prices of a sector's listed companies, now and at
 * the day's open. A delisted company is left out; a sector with no listed company has no index.
 * Pure, so the arithmetic is unit tested.
 */
public final class Sectors {
    private Sectors() { }

    /** Every sector with at least one listed company, in sector order. */
    public static List<ExchangeApi.SectorQuote> of(List<ExchangeApi.StockQuote> stocks) {
        Map<String, long[]> sums = new TreeMap<>();
        if (stocks != null) for (ExchangeApi.StockQuote stock : stocks) {
            if (stock == null || stock.delisted() || stock.sector() == null || stock.sector().isBlank()) continue;
            long[] sum = sums.computeIfAbsent(stock.sector(), ignored -> new long[3]);
            sum[0] = saturatingAdd(sum[0], Math.max(0L, stock.priceCents()));
            sum[1] = saturatingAdd(sum[1], Math.max(0L, stock.dayOpenCents()));
            sum[2]++;
        }
        List<ExchangeApi.SectorQuote> out = new ArrayList<>(sums.size());
        sums.forEach((sector, sum) -> {
            long index = sum[0] / sum[2];
            long open = sum[1] / sum[2];
            out.add(new ExchangeApi.SectorQuote(sector, index, open, changeBasisPoints(index, open), (int) sum[2]));
        });
        return List.copyOf(out);
    }

    /** How far {@code now} is from {@code open}, in hundredths of a percent; 0 when there is no open. */
    public static int changeBasisPoints(long now, long open) {
        if (open <= 0L) return 0;
        double change = (now - (double) open) * 10_000.0D / open;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(change)));
    }

    /** Reads {@code sectorBands}: whole percentages 1 to 100, comma-separated, at most 8. */
    public static List<Integer> parseBands(String text) {
        List<Integer> out = new ArrayList<>();
        if (text == null) return out;
        for (String entry : text.split(",")) {
            try {
                int band = Integer.parseInt(entry.trim());
                if (band >= 1 && band <= 100 && !out.contains(band) && out.size() < 8) out.add(band);
            } catch (NumberFormatException malformed) {
                // skipped
            }
        }
        return out;
    }

    /** The index level {@code band} percent above ({@code up}) or below the day's open. */
    public static long bandLevel(long open, int band, boolean up) {
        long step = open / 100L * band + open % 100L * band / 100L;
        return up ? open + step : open - step;
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
