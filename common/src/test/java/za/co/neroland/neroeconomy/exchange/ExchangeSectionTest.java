package za.co.neroland.neroeconomy.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.ExchangeApi;
import za.co.neroland.neroeconomy.api.StockType;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.StoreTesting;

/** The exchange section: listing and delisting, the step, the index, what it hands out and how it is saved. */
class ExchangeSectionTest {
    private static final long STEP = ExchangeTesting.STEP;
    private static final long FIRST = ExchangeTesting.DAY_START;
    private static final ExchangeSettings SETTINGS = ExchangeSettings.DEFAULTS;
    private static final StockDefinition IRON = ExchangeTesting.steady("iron");
    private static final StockDefinition ROCKET = ExchangeTesting.risky("rocket");
    private static final StockRegistry BOTH = StockRegistry.of(List.of(IRON, ROCKET));

    private static JsonElement encode(ExchangeSection section) {
        return ExchangeSection.CODEC.encodeStart(JsonOps.INSTANCE, section).getOrThrow();
    }

    private static List<Long> prices(ExchangeSection section, StockRegistry registry) {
        List<Long> out = new ArrayList<>();
        for (ExchangeApi.StockQuote quote : section.quotes(registry)) out.add(quote.priceCents());
        out.add(section.indexQuote().priceCents());
        return out;
    }

    // --- determinism -----------------------------------------------------------------------------

    @Test void theSameSecretAndStepsGiveIdenticalPricesAndAnotherSecretDoesNot() {
        ExchangeSection one = new ExchangeSection();
        ExchangeSection two = new ExchangeSection();
        ExchangeSection other = new ExchangeSection();
        ExchangeTesting.run(one, BOTH, SETTINGS, ExchangeTesting.secret(1L), FIRST, 500);
        ExchangeTesting.run(two, BOTH, SETTINGS, ExchangeTesting.secret(1L), FIRST, 500);
        ExchangeTesting.run(other, BOTH, SETTINGS, ExchangeTesting.secret(2L), FIRST, 500);
        // Identical in every stored number, histories included.
        assertEquals(encode(one), encode(two));
        assertNotEquals(prices(one, BOTH), prices(other, BOTH));
        // The same secret at other step indexes is another path too.
        ExchangeSection later = new ExchangeSection();
        ExchangeTesting.run(later, BOTH, SETTINGS, ExchangeTesting.secret(1L), FIRST + 144L, 500);
        assertNotEquals(prices(one, BOTH), prices(later, BOTH));
    }

    @Test void aStepNeverRunsTwiceAndNeverBackwards() {
        ExchangeSection section = new ExchangeSection();
        byte[] secret = ExchangeTesting.secret(3L);
        assertTrue(section.step(BOTH, SETTINGS, FIRST, STEP, ExchangeTesting.inputs(secret, FIRST)));
        JsonElement after = encode(section);
        assertFalse(section.step(BOTH, SETTINGS, FIRST, STEP, ExchangeTesting.constant(9.0D)));
        assertFalse(section.step(BOTH, SETTINGS, FIRST - 5L, STEP, ExchangeTesting.constant(9.0D)));
        assertFalse(section.step(BOTH, SETTINGS, -1L, STEP, ExchangeTesting.constant(9.0D)));
        assertEquals(after, encode(section));
        assertEquals(FIRST, section.lastStep());
        // A gap is fine: steps that were skipped are simply never run.
        assertTrue(section.step(BOTH, SETTINGS, FIRST + 10L, STEP, ExchangeTesting.inputs(secret, FIRST + 10L)));
        assertEquals(FIRST + 10L, section.lastStep());
    }

    @Test void switchedOffNothingMovesAndNothingIsListed() {
        ExchangeSection section = new ExchangeSection();
        assertFalse(section.step(BOTH, SETTINGS.withEnabled(false), FIRST, STEP, ExchangeTesting.constant(1.0D)));
        assertTrue(section.isEmpty());
        ExchangeTesting.run(section, BOTH, SETTINGS, ExchangeTesting.secret(4L), FIRST, 20);
        List<Long> before = prices(section, BOTH);
        for (long step = FIRST + 20L; step < FIRST + 40L; step++)
            assertFalse(section.step(BOTH, SETTINGS.withEnabled(false), step, STEP, ExchangeTesting.constant(1.0D)));
        assertEquals(before, prices(section, BOTH));
        assertEquals(FIRST + 19L, section.lastStep());
        // Switched back on, it carries on from where the prices were.
        assertTrue(section.step(BOTH, SETTINGS, FIRST + 40L, STEP, ExchangeTesting.constant(1.0D)));
        assertNotEquals(before, prices(section, BOTH));
    }

    // --- listing ---------------------------------------------------------------------------------

    @Test void aNewCompanyStartsAtItsStartPriceAndAGoneOneIsDelistedWithItsStateKept() {
        ExchangeSection section = new ExchangeSection();
        assertTrue(section.reconcile(StockRegistry.of(List.of(IRON))));
        assertFalse(section.reconcile(StockRegistry.of(List.of(IRON))));
        assertEquals(10_000L, section.state(IRON.id()).price());
        assertEquals(10_000L, section.state(IRON.id()).startCents());
        assertNull(section.state(ROCKET.id()));
        ExchangeTesting.run(section, StockRegistry.of(List.of(IRON)), SETTINGS, ExchangeTesting.secret(5L), FIRST, 100);
        long ironPrice = section.state(IRON.id()).price();

        // A reload adds the rocket company and drops the iron one.
        StockRegistry reloaded = StockRegistry.of(List.of(ROCKET));
        assertTrue(section.reconcile(reloaded));
        assertEquals(20_000L, section.state(ROCKET.id()).price());
        StockState iron = section.state(IRON.id());
        assertTrue(iron.delisted());
        assertEquals(ironPrice, iron.price());
        assertEquals(List.of(IRON.id()), section.delisted());
        assertFalse(section.halted(IRON.id()));

        // Delisted, its price does not move and its history stops; the listed one goes on.
        int points = iron.history().recentSize();
        ExchangeTesting.run(section, reloaded, SETTINGS, ExchangeTesting.secret(5L), FIRST + 100L, 100);
        assertEquals(ironPrice, section.state(IRON.id()).price());
        assertEquals(points, section.state(IRON.id()).history().recentSize());
        assertNotEquals(20_000L, section.state(ROCKET.id()).price());

        // It is still reported, named from its state, at its last price, after the listed ones.
        List<ExchangeApi.StockQuote> quotes = section.quotes(reloaded);
        assertEquals(2, quotes.size());
        assertEquals(ROCKET.id(), quotes.get(0).id());
        assertEquals(StockType.RISKY, quotes.get(0).type());
        assertFalse(quotes.get(0).delisted());
        assertEquals(IRON.id(), quotes.get(1).id());
        assertTrue(quotes.get(1).delisted());
        assertEquals(IRON.nameKey(), quotes.get(1).nameKey());
        assertEquals(ironPrice, quotes.get(1).priceCents());
        assertEquals(10_000L, quotes.get(1).startCents());

        // Once its holders are paid, it is dropped; a listed company cannot be.
        assertFalse(section.removeDelisted(ROCKET.id()));
        assertFalse(section.removeDelisted("test:nothing"));
        assertTrue(section.removeDelisted(IRON.id()));
        assertNull(section.state(IRON.id()));
        assertEquals(1, section.size());
    }

    @Test void aCompanyThatComesBackCarriesOnFromItsLastPrice() {
        ExchangeSection section = new ExchangeSection();
        ExchangeTesting.run(section, BOTH, SETTINGS, ExchangeTesting.secret(6L), FIRST, 50);
        long price = section.state(IRON.id()).price();
        section.reconcile(StockRegistry.of(List.of(ROCKET)));
        assertTrue(section.state(IRON.id()).delisted());
        section.reconcile(BOTH);
        assertFalse(section.state(IRON.id()).delisted());
        assertEquals(price, section.state(IRON.id()).price());
        assertTrue(section.delisted().isEmpty());
    }

    @Test void dataThatCouldNotBeReadDelistsNothing() {
        ExchangeSection section = new ExchangeSection();
        ExchangeTesting.run(section, BOTH, SETTINGS, ExchangeTesting.secret(7L), FIRST, 10);
        List<Long> before = prices(section, BOTH);
        assertFalse(section.reconcile(StockRegistry.UNAVAILABLE));
        assertFalse(section.reconcile(null));
        assertFalse(section.step(StockRegistry.UNAVAILABLE, SETTINGS, FIRST + 10L, STEP, ExchangeTesting.constant(1.0D)));
        assertTrue(section.delisted().isEmpty());
        assertEquals(before, prices(section, BOTH));
        // An empty registry, on the other hand, is a data pack that lists nothing: everything is delisted.
        assertTrue(section.reconcile(StockRegistry.EMPTY));
        assertEquals(2, section.delisted().size());
    }

    @Test void theSectionNeverRemembersMoreThanItsBound() {
        ExchangeSection section = new ExchangeSection();
        // Five reloads, each listing 64 companies the last one did not have.
        for (int round = 0; round < 5; round++) {
            List<StockDefinition> definitions = new ArrayList<>();
            for (int index = 0; index < StockRegistry.MAX_LISTED; index++)
                definitions.add(ExchangeTesting.steady("r" + round + "_" + index));
            section.reconcile(StockRegistry.of(definitions));
        }
        assertEquals(ExchangeSection.MAX_STATES, section.size());
        // The registry itself never lists more than its own cap.
        List<StockDefinition> many = new ArrayList<>();
        for (int index = 0; index < 100; index++) many.add(ExchangeTesting.steady("many_" + index));
        assertEquals(StockRegistry.MAX_LISTED, StockRegistry.of(many).stocks().size());
    }

    // --- the index -------------------------------------------------------------------------------

    @Test void theIndexMovesByTheAverageOfItsMembersAndDoesNotJumpWhenMembershipChanges() {
        ExchangeSection section = new ExchangeSection();
        assertEquals(IndexState.START_CENTS, section.indexQuote().priceCents());
        assertEquals(0, section.indexQuote().members());
        // Both up by their step limits: +2 % and +6 %, so the index is up 4 %.
        section.step(BOTH, SETTINGS, FIRST, STEP, ExchangeTesting.constant(30.0D));
        assertEquals(10_200L, section.state(IRON.id()).price());
        assertEquals(21_200L, section.state(ROCKET.id()).price());
        ExchangeApi.IndexQuote index = section.indexQuote();
        assertEquals(10_400L, index.priceCents());
        assertEquals(2, index.members());
        assertEquals(400, index.dayChangeBasisPoints());
        assertEquals(IndexState.ID, index.id());

        // A company far from 100 credits joins: the index does not move for it.
        StockDefinition pricey = new StockDefinition("test:pricey", "stock.test.pricey", "stock.test.pricey.description",
                "space", StockType.STEADY, 5_000L, 2.0D, 15.0D, 0.0D, List.of(), true);
        StockRegistry three = StockRegistry.of(List.of(IRON, ROCKET, pricey));
        section.reconcile(three);
        assertEquals(10_400L, section.indexQuote().priceCents());
        assertEquals(3, section.indexQuote().members());
        // And leaves again, along with the rocket company: still no jump.
        section.reconcile(StockRegistry.of(List.of(IRON)));
        assertEquals(10_400L, section.indexQuote().priceCents());
        assertEquals(1, section.indexQuote().members());
        // With one member the index moves exactly as that member does.
        long iron = section.state(IRON.id()).price();
        section.step(StockRegistry.of(List.of(IRON)), SETTINGS, FIRST + 1L, STEP, ExchangeTesting.constant(-30.0D));
        double move = (double) section.state(IRON.id()).price() / iron;
        assertEquals(Math.round(10_400L * move), section.indexQuote().priceCents());
        // With no member at all it stays where it is.
        long level = section.indexQuote().priceCents();
        section.step(StockRegistry.EMPTY, SETTINGS, FIRST + 2L, STEP, ExchangeTesting.constant(9.0D));
        assertEquals(level, section.indexQuote().priceCents());
        assertEquals(3, section.history(IndexState.ID).orElseThrow().recentCents().size());
    }

    // --- what is handed out ----------------------------------------------------------------------

    @Test void quotesAndHistoriesAreCopiesAndCarryTheHaltFlag() {
        ExchangeSection section = new ExchangeSection();
        // Straight up: the steady share reaches its daily limit and is halted.
        for (long step = FIRST; step < FIRST + 8L; step++)
            section.step(BOTH, SETTINGS, step, STEP, ExchangeTesting.constant(9.0D));
        ExchangeApi.StockQuote iron = section.quote(IRON.id(), BOTH).orElseThrow();
        assertEquals(11_000L, iron.priceCents());
        assertEquals(10_000L, iron.dayOpenCents());
        assertEquals(1_000, iron.dayChangeBasisPoints());
        assertTrue(iron.halted());
        assertTrue(section.halted(IRON.id()));
        assertEquals("mining", iron.sector());
        assertEquals(StockType.STEADY, iron.type());
        assertEquals(1.5D, iron.dividendYieldPercent());
        assertEquals(IRON.nameKey(), iron.nameKey());
        assertEquals(IRON.descriptionKey(), iron.descriptionKey());
        assertFalse(section.quote("test:nothing", BOTH).isPresent());
        assertFalse(section.halted("test:nothing"));

        ExchangeApi.History history = section.history(IRON.id()).orElseThrow();
        assertEquals(8, history.recentCents().size());
        assertEquals(11_000L, history.recentCents().get(7));
        assertTrue(history.dailyCents().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> history.recentCents().add(1L));
        // Emptying what was handed out changes nothing in the section.
        section.quotes(BOTH).clear();
        assertEquals(2, section.quotes(BOTH).size());
        assertFalse(section.history("test:nothing").isPresent());
    }

    // --- persistence -----------------------------------------------------------------------------

    @Test void theSectionSurvivesASaveAndALoadExactlyAndThenGivesTheSamePrices() {
        ExchangeSection section = new ExchangeSection();
        byte[] secret = ExchangeTesting.secret(8L);
        // Long enough for daily history, a halt or two and a delisted company.
        ExchangeTesting.run(section, BOTH, SETTINGS, secret, FIRST, 400);
        StockRegistry ironOnly = StockRegistry.of(List.of(IRON));
        section.reconcile(ironOnly);
        JsonElement saved = encode(section);
        ExchangeSection restored = ExchangeSection.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(saved, encode(restored));
        assertEquals(section.lastStep(), restored.lastStep());
        assertTrue(restored.state(ROCKET.id()).delisted());
        assertEquals(section.history(IRON.id()), restored.history(IRON.id()));
        assertEquals(section.history(IndexState.ID), restored.history(IndexState.ID));
        // Both carry on identically.
        ExchangeTesting.run(section, ironOnly, SETTINGS, secret, FIRST + 400L, 200);
        ExchangeTesting.run(restored, ironOnly, SETTINGS, secret, FIRST + 400L, 200);
        assertEquals(encode(section), encode(restored));
    }

    @Test void theStoreCarriesTheSectionAndAnOlderStoreLoadsWithNothingListed() {
        EconomyStore store = new EconomyStore();
        assertTrue(store.exchange().isEmpty());
        assertFalse(StoreTesting.encode(store).getAsJsonObject().has("exchange"));
        assertFalse(store.sectionsPresent().contains("exchange"));
        // A store saved before the exchange existed is exactly a store without the field.
        EconomyStore old = StoreTesting.decode(StoreTesting.encode(store));
        assertTrue(old.exchange().isEmpty());
        assertEquals(StockState.NEVER, old.exchange().lastStep());

        ExchangeTesting.run(store.exchange(), BOTH, SETTINGS, ExchangeTesting.secret(9L), FIRST, 30);
        assertTrue(store.isDirty());
        assertTrue(store.sectionsPresent().contains("exchange"));
        EconomyStore reopened = StoreTesting.reload(store);
        assertEquals(prices(store.exchange(), BOTH), prices(reopened.exchange(), BOTH));
        assertEquals(FIRST + 29L, reopened.exchange().lastStep());
        assertEquals(encode(store.exchange()), encode(reopened.exchange()));
    }

    @Test void aDamagedSaveLoadsWhatItCanAndBringsNumbersBackIntoRange() {
        JsonObject bad = new JsonObject();
        bad.addProperty("last_step", -50L);
        com.google.gson.JsonArray stocks = new com.google.gson.JsonArray();
        JsonObject negative = new JsonObject();
        negative.addProperty("id", "test:negative");
        negative.addProperty("price", -7L);
        negative.addProperty("halted_until", -99L);
        stocks.add(negative);
        JsonObject noId = new JsonObject();
        noId.addProperty("id", "Not An Id");
        noId.addProperty("price", 5L);
        stocks.add(noId);
        JsonObject twice = new JsonObject();
        twice.addProperty("id", "test:negative");
        twice.addProperty("price", 900L);
        stocks.add(twice);
        JsonObject huge = new JsonObject();
        huge.addProperty("id", "test:huge");
        huge.addProperty("price", Long.MAX_VALUE);
        com.google.gson.JsonArray recent = new com.google.gson.JsonArray();
        for (int index = 0; index < 500; index++) recent.add(1_000L + index);
        huge.add("recent", recent);
        stocks.add(huge);
        bad.add("stocks", stocks);
        ExchangeSection section = ExchangeSection.CODEC.parse(JsonOps.INSTANCE, bad).getOrThrow();
        assertEquals(2, section.size());
        assertEquals(StockState.NEVER, section.lastStep());
        assertEquals(PriceModel.MIN_PRICE_CENTS, section.state("test:negative").price());
        assertEquals(StockState.NEVER, section.state("test:negative").haltedUntil());
        assertEquals(PriceModel.MAX_PRICE_CENTS, section.state("test:huge").price());
        // A history longer than the ring keeps its newest points.
        long[] kept = section.state("test:huge").history().recent();
        assertEquals(PriceHistory.RECENT, kept.length);
        assertEquals(1_499L, kept[kept.length - 1]);
        assertEquals(IndexState.START_CENTS, section.indexQuote().priceCents());
    }
}
