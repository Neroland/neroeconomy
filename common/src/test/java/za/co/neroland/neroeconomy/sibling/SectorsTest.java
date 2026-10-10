package za.co.neroland.neroeconomy.sibling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.ExchangeApi;
import za.co.neroland.neroeconomy.api.StockType;

class SectorsTest {
    private static ExchangeApi.StockQuote stock(String id, String sector, long price, long open, boolean delisted) {
        return new ExchangeApi.StockQuote(id, "n", "d", sector, StockType.STEADY, price, open, 0, false, delisted, 0.0D,
                open);
    }

    @Test void aSectorIndexIsTheEqualWeightedMeanOfItsListedCompanies() {
        List<ExchangeApi.SectorQuote> sectors = Sectors.of(List.of(
                stock("a", "mining", 1_000L, 1_000L, false),
                stock("b", "mining", 3_000L, 2_000L, false),
                stock("c", "mining", 99_999L, 99_999L, true),
                stock("d", "farming", 500L, 400L, false)));
        assertEquals(2, sectors.size());
        ExchangeApi.SectorQuote farming = sectors.get(0);
        assertEquals("farming", farming.sector());
        assertEquals(2_500, farming.dayChangeBasisPoints());
        ExchangeApi.SectorQuote mining = sectors.get(1);
        assertEquals(2_000L, mining.indexCents(), "the delisted company is left out");
        assertEquals(1_500L, mining.dayOpenCents());
        assertEquals(2, mining.members());
        assertEquals(3_333, mining.dayChangeBasisPoints());
    }

    @Test void aSectorWithNoListedCompanyHasNoIndex() {
        assertTrue(Sectors.of(List.of(stock("c", "mining", 10L, 10L, true))).isEmpty());
        assertTrue(Sectors.of(List.of()).isEmpty());
    }

    @Test void bandsParseAndLevelsAreExact() {
        assertEquals(List.of(5, 10), Sectors.parseBands("5, 10,abc,0,5"));
        assertEquals(10_500L, Sectors.bandLevel(10_000L, 5, true));
        assertEquals(9_000L, Sectors.bandLevel(10_000L, 10, false));
        assertEquals(4_842_270_319_348_757_298L, Sectors.bandLevel(Long.MAX_VALUE / 2L, 5, true),
                "no overflow on a large open");
    }
}
