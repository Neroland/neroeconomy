package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;
import za.co.neroland.neroeconomy.shop.ShopOwnershipState;

/**
 * The store's on-disk shape. {@code common/src/test/resources/economy/store-0.3-full.json} was written
 * by the sixteen-field codec of the 0.3 builds before the codec was split into field groups, from a
 * fuzzed economy with every section but goods in use. Whatever the codec looks like in code, that file
 * — and every store with fewer sections, as earlier builds wrote — must load and be written back
 * exactly as it was.
 */
class EconomyStoreCodecTest {
    /** Every field the store has ever written, in the order it writes them. */
    private static final List<String> FIELDS = List.of("schema", "ledger", "market", "obligations", "shop_owners",
            "goods", "vault", "simulation", "book", "offers", "rules", "trade_escrow", "auto_traders", "exchange",
            "houses", "admin");

    @AfterEach void restoreClock() { StoreTesting.wallClock(null); }

    /**
     * Every test reads the golden store on the clock it was saved on: a ledger loaded on a wall clock
     * behind the one it saved records an offset, which would be a change of its own.
     */
    @BeforeEach void savedClock() throws IOException {
        long saved = golden().getAsJsonObject("ledger").get("clock_last_wall").getAsLong();
        StoreTesting.wallClock(() -> saved);
    }

    @Test void theGoldenStoreLoadsAndIsWrittenBackExactly() throws IOException {
        JsonObject golden = golden();
        assertEquals(List.of("schema", "ledger", "market", "obligations", "shop_owners", "vault", "simulation",
                "book", "offers", "rules", "trade_escrow", "auto_traders", "exchange", "houses", "admin"),
                new ArrayList<>(golden.keySet()));
        EconomyStore store = StoreTesting.decode(golden);
        JsonElement again = StoreTesting.encode(store);
        assertEquals(golden, again);
        // Field for field and in the same order: the groups add no nesting and do not reorder.
        assertEquals(new ArrayList<>(golden.keySet()), new ArrayList<>(again.getAsJsonObject().keySet()));
        assertEquals(EconomyStore.SCHEMA, store.schema());
        // The fuzzed economy was saved mid-run, with direct trades open whose escrow lives outside the
        // store, so its invariants are not checked here; what matters is that nothing changed.
        assertTrue(store.ledger().supplyBalanced());
        assertTrue(store.marketClosed());
    }

    @Test void everyEarlierStoreShapeLoadsAndIsWrittenBackExactly() throws IOException {
        JsonObject golden = golden();
        // The stores earlier builds wrote: the first combined store (schema and ledger), each later
        // build's set of sections, and the golden one with any one section missing.
        List<List<String>> shapes = new ArrayList<>();
        shapes.add(List.of("schema", "ledger"));
        shapes.add(List.of("schema", "ledger", "market", "obligations", "shop_owners"));
        shapes.add(List.of("schema", "ledger", "market", "obligations", "shop_owners", "vault", "simulation"));
        shapes.add(List.of("schema", "ledger", "market", "obligations", "shop_owners", "vault", "simulation", "book",
                "offers", "rules"));
        for (String missing : golden.keySet()) {
            List<String> shape = new ArrayList<>(golden.keySet());
            shape.remove(missing);
            shapes.add(shape);
        }
        for (List<String> shape : shapes) {
            JsonObject saved = new JsonObject();
            for (String field : shape) saved.add(field, golden.get(field).deepCopy());
            JsonElement again = StoreTesting.encode(StoreTesting.decode(saved));
            JsonObject expected = saved.deepCopy();
            // The ledger is always written; a store without one writes the empty ledger's fields.
            if (!shape.contains("ledger")) expected.add("ledger", again.getAsJsonObject().get("ledger"));
            assertEquals(order(expected), again, "shape " + shape);
            assertEquals(new ArrayList<>(order(expected).keySet()), new ArrayList<>(again.getAsJsonObject().keySet()),
                    "order " + shape);
        }
    }

    @Test void theOlderStoresSectionsStillLoadThroughTheirOwnTypes() throws IOException {
        // A 0.2.0-alpha.1 world has these four as files of their own, read through their own types on
        // first load; the combined store holds the same rows under the same names.
        JsonObject golden = golden();
        roundTrip(EconomySavedData.CODEC, golden.get("ledger"));
        roundTrip(MarketSavedData.CODEC, golden.get("market"));
        roundTrip(ObligationSavedData.CODEC, golden.get("obligations"));
        roundTrip(ShopOwnershipState.CODEC, golden.get("shop_owners"));
    }

    @Test void aStoreWithEverySectionRoundTrips() throws IOException {
        JsonObject saved = golden();
        // Goods: its prototypes are item stacks, which need the game's registries, so here the section
        // holds only its id counter; a store with prototypes is the same field with more in it.
        saved.add("goods", JsonParser.parseString("{\"next_id\":3}"));
        JsonObject ordered = order(saved);
        assertEquals(FIELDS, new ArrayList<>(ordered.keySet()));
        EconomyStore store = StoreTesting.decode(ordered);
        assertEquals(FIELDS.subList(1, FIELDS.size()), store.sectionsPresent());
        JsonElement again = StoreTesting.encode(store);
        assertEquals(ordered, again);
        assertEquals(FIELDS, new ArrayList<>(again.getAsJsonObject().keySet()));
        // And a second pass changes nothing.
        assertEquals(again, StoreTesting.encode(StoreTesting.decode(again)));
    }

    private static <T> void roundTrip(Codec<T> codec, JsonElement saved) {
        T decoded = codec.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(saved, codec.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow());
    }

    /** The fields of {@code saved} in the order the store writes them. */
    private static JsonObject order(JsonObject saved) {
        JsonObject ordered = new JsonObject();
        for (String field : FIELDS) if (saved.has(field)) ordered.add(field, saved.get(field));
        return ordered;
    }

    private static JsonObject golden() throws IOException {
        for (Path at = Path.of(System.getProperty("user.dir")).toAbsolutePath(); at != null; at = at.getParent()) {
            Path file = at.resolve("common/src/test/resources/economy/store-0.3-full.json");
            if (Files.isRegularFile(file))
                return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        throw new IOException("store-0.3-full.json not found above " + System.getProperty("user.dir"));
    }
}
