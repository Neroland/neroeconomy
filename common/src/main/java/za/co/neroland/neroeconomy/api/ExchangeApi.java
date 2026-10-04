package za.co.neroland.neroeconomy.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.exchange.BondTerm;
import za.co.neroland.neroeconomy.exchange.ExchangeSection;
import za.co.neroland.neroeconomy.exchange.ExchangeSettings;
import za.co.neroland.neroeconomy.exchange.StockData;

/**
 * Read-only view of the exchange for other mods and for screens: the listed companies and their
 * prices, the index fund, the price histories and the Savings Bond terms.
 *
 * <p>Server-side only. Everything returned is a copy, so nothing a caller does with it reaches the
 * exchange. Prices are whole <b>hundredths of a credit</b> ({@code 12345} is 123.45 credits) and
 * changes are <b>hundredths of a percent</b> ({@code -150} is −1.5 %). Nothing here is about a
 * player, and nothing here says anything about a price that has not happened yet.
 *
 * <p>Buying, selling, Savings Bonds and an account's portfolio are deliberately not part of this
 * API: what an account holds is personal data. The mod's own screens, commands and companion-app
 * module go through {@code exchange.Investing}.
 */
public final class ExchangeApi {
    /** The id the index fund is quoted, traded and held under. */
    public static final String INDEX_ID = "neroeconomy:index";

    private ExchangeApi() { }

    /**
     * One company.
     *
     * @param id                   {@code <namespace>:<path>}
     * @param nameKey              lang key of its name
     * @param descriptionKey       lang key of its one-line description; empty for a delisted company
     * @param sector               its sector word; the name is the lang key {@code sector.neroeconomy.<sector>}
     * @param type                 steady or risky
     * @param priceCents           the price now
     * @param dayOpenCents         the price at the start of its economy-day
     * @param dayChangeBasisPoints how far the price is from the day-open price
     * @param halted               trading in it is halted: its price reached the daily limit
     * @param delisted             its data file is gone; the price is its last one and does not move
     * @param dividendYieldPercent dividend per economy-week, as a percentage of the price
     * @param startCents           the price it listed at
     */
    public record StockQuote(String id, String nameKey, String descriptionKey, String sector, StockType type,
            long priceCents, long dayOpenCents, int dayChangeBasisPoints, boolean halted, boolean delisted,
            double dividendYieldPercent, long startCents) { }

    /**
     * The index fund: a basket of every listed company, equal-weighted.
     *
     * @param members how many listed companies it averages
     */
    public record IndexQuote(String id, String nameKey, String descriptionKey, long priceCents, long dayOpenCents,
            int dayChangeBasisPoints, int members) { }

    /**
     * A price history, oldest first.
     *
     * @param recentCents the price after each of the last steps (at most 144)
     * @param dailyCents  the closing price of each of the last economy-days (at most 30)
     */
    public record History(List<Long> recentCents, List<Long> dailyCents) { }

    /**
     * One Savings Bond term.
     *
     * @param id              {@code 1d}, {@code 3d} or {@code 7d}
     * @param nameKey         lang key of its name
     * @param days            its length in economy-days
     * @param rateBasisPoints the interest for the whole term, in hundredths of a percent
     */
    public record BondQuote(String id, String nameKey, int days, int rateBasisPoints) { }

    /** Whether the exchange is switched on ({@code exchangeEnabled}); prices stand still while it is off. */
    public static boolean enabled() { return EconomyConfig.EXCHANGE_ENABLED.get(); }

    /** Every company: the listed ones in id order, then any delisted ones still being wound up. */
    public static List<StockQuote> stocks(MinecraftServer server) {
        if (server == null) return List.of();
        return List.copyOf(section(server).quotes(StockData.forServer(server)));
    }

    /** One company, or empty when there is none with that id. */
    public static Optional<StockQuote> stock(MinecraftServer server, String id) {
        if (server == null || id == null) return Optional.empty();
        return section(server).quote(id, StockData.forServer(server));
    }

    /** The index fund. Its id is {@code neroeconomy:index}. */
    public static Optional<IndexQuote> index(MinecraftServer server) {
        if (server == null) return Optional.empty();
        return Optional.of(section(server).indexQuote());
    }

    /** The price history of a company, or of the index fund under its id. */
    public static Optional<History> history(MinecraftServer server, String id) {
        if (server == null || id == null) return Optional.empty();
        return section(server).history(id);
    }

    /** The three Savings Bond terms and what each pays now. */
    public static List<BondQuote> bonds() {
        ExchangeSettings settings = ExchangeSettings.fromConfig();
        List<BondQuote> out = new ArrayList<>();
        for (BondTerm term : BondTerm.values())
            out.add(new BondQuote(term.key(), term.translationKey(), term.days(), settings.bondRateBps(term)));
        return List.copyOf(out);
    }

    /**
     * The world's exchange section, with a company that was listed since the last step already in
     * it, so a screen opened right after a data-pack reload shows what the next step will price.
     */
    private static ExchangeSection section(MinecraftServer server) {
        ExchangeSection section = EconomyStore.get(server).exchange();
        // Not while frozen: the store handed out then is an empty stand-in, and nothing is listed in it.
        if (enabled() && !EconomyHealth.frozen()) section.reconcile(StockData.forServer(server));
        return section;
    }
}
