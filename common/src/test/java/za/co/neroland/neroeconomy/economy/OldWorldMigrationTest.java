package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.obligation.EconomyJob;
import za.co.neroland.neroeconomy.obligation.JobState;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;
import za.co.neroland.neroeconomy.shop.ShopOwnershipState;

/**
 * K2: a world saved by 0.2.0-alpha.1 (commit 8818250), opened by this build. 0.2 kept four files
 * ({@code neroeconomy:ledger}, {@code :market}, {@code :obligations}, {@code :shop_owners}); each is
 * written here field for field as 0.2's codecs wrote it (the field names and shapes are copied from
 * that commit), converted to NBT as on disk, read through today's codecs and handed to the store's
 * first-load migration ({@link EconomyStore#setUpFrom}), exactly as {@code EconomyStore.open} does.
 *
 * <p>Covered: balances, the initialised and logging-opt-out lists, audit rows, the pseudonym salt,
 * credit claims, Market Board buy offers (onto the order book, escrow and all), contracts, delivery
 * jobs, bounties and bounty progress, shop owners; the escrow 0.2 had written off the ledger restored;
 * every invariant; and a second load that changes nothing. Not covered, because they hold item
 * stacks and JUnit here runs without the game's registries: Market Board listings, shop listings,
 * uncollected item claims (and so their move into the Vault) and player auctions. Those need the
 * in-game smoke test.
 */
class OldWorldMigrationTest {
    private static final UUID ALICE = UUID.fromString("0a000000-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("0a000000-0000-4000-8000-000000000002");
    private static final UUID CAROL = UUID.fromString("0a000000-0000-4000-8000-000000000003");
    private static final UUID OFFER = UUID.fromString("0b000000-0000-4000-8000-000000000001");
    private static final UUID CONTRACT = UUID.fromString("0c000000-0000-4000-8000-000000000001");
    private static final UUID DELIVERY = UUID.fromString("0c000000-0000-4000-8000-000000000002");
    private static final UUID BOUNTY = UUID.fromString("0c000000-0000-4000-8000-000000000003");
    private static final UUID DONE = UUID.fromString("0c000000-0000-4000-8000-000000000004");
    private static final String SHOP = "minecraft:overworld|274877906944";

    /** What 0.2 had taken out of balances and recorded as owed: the offer, the claim, the open jobs. */
    private static final long OWED = 64L + 75L + 120L + 30L + 50L;

    /** One old file: its JSON form, converted to NBT as it lies on disk, read through today's codec. */
    private static <T> T read(Codec<T> codec, String json) {
        Tag tag = JsonOps.INSTANCE.convertTo(NbtOps.INSTANCE, JsonParser.parseString(json));
        return codec.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }

    private static EconomyStore oldWorld() {
        long now = System.currentTimeMillis();
        long later = now + 86_400_000L;
        String credits = NeroEconomyCommon.CREDITS.id().toString();
        EconomySavedData ledger = read(EconomySavedData.CODEC, """
                {"balances": [
                   {"account_kind": "player", "player": "%1$s", "currency": "%4$s", "balance": 1000},
                   {"account_kind": "player", "player": "%2$s", "balance": 250},
                   {"player": "%3$s", "balance": 40}],
                 "initialized": ["%1$s", "%2$s", "%3$s"],
                 "logging_opt_out": ["%3$s"],
                 "transactions": [{"type": "PLAYER_PAYMENT", "amount": 50, "currency": "%4$s", "timestamp": %5$d}],
                 "pseudonym_salt": "salt-from-0.2",
                 "erased": []}
                """.formatted(ALICE, BOB, CAROL, credits, now - 3_600_000L));
        MarketSavedData market = read(MarketSavedData.CODEC, """
                {"credit_claims": [{"player": "%2$s", "amount": 75}],
                 "buy_offers": [{"id": "%3$s", "buyer": "%1$s", "item": "minecraft:oak_log", "quantity": 16,
                                 "price": 4, "escrow": 64, "expires": %4$d}]}
                """.formatted(ALICE, BOB, OFFER, later));
        ObligationSavedData obligations = read(ObligationSavedData.CODEC, """
                {"jobs": [
                   {"id": "%4$s", "type": "CONTRACT", "issuer": "%1$s", "target": "minecraft:iron_ingot",
                    "quantity": 8, "payout": 120, "expires": %8$d, "state": "OPEN"},
                   {"id": "%5$s", "type": "DELIVERY", "issuer": "%3$s", "claimant": "%2$s",
                    "target": "minecraft:oak_log", "quantity": 32, "destination": "minecraft:overworld|0|64|0",
                    "payout": 30, "expires": %8$d, "state": "CLAIMED"},
                   {"id": "%6$s", "type": "BOUNTY", "issuer": "%2$s", "target": "minecraft:zombie",
                    "quantity": 5, "payout": 50, "expires": %8$d, "state": "OPEN"},
                   {"id": "%7$s", "type": "CONTRACT", "issuer": "%1$s", "claimant": "%3$s",
                    "target": "minecraft:diamond", "quantity": 1, "payout": 10, "expires": %8$d, "state": "FULFILLED"}],
                 "bounty_progress": [{"job": "%6$s", "player": "%1$s", "kills": 2}]}
                """.formatted(ALICE, BOB, CAROL, CONTRACT, DELIVERY, BOUNTY, DONE, later));
        ShopOwnershipState shops = read(ShopOwnershipState.CODEC, """
                {"shops": [{"location": "%s", "owner": "%s"}]}
                """.formatted(SHOP, CAROL));
        EconomyStore store = new EconomyStore();
        store.setUpFrom(ledger, market, obligations, shops);
        return store;
    }

    @Test void a02WorldCarriesEverythingIntoTheCombinedStoreAndEveryInvariantHolds() {
        EconomyStore store = oldWorld();
        assertEquals(EconomyStore.SCHEMA, store.schema());
        EconomySavedData ledger = store.ledger();
        // Balances, exactly.
        assertEquals(1_000L, ledger.getBalance(ALICE, NeroEconomyCommon.CREDITS));
        assertEquals(250L, ledger.getBalance(BOB, NeroEconomyCommon.CREDITS));
        assertEquals(40L, ledger.getBalance(CAROL, NeroEconomyCommon.CREDITS));
        // The audit row 0.2 wrote is still there.
        assertTrue(ledger.rowsForTest().stream().anyMatch(row -> row.type() == TransactionType.PLAYER_PAYMENT
                && row.amount() == 50L), "the 0.2 audit row was lost");
        // The Market Board buy offer is an order on the book now, with its id, price, expiry and escrow.
        BuyOrder offer = store.book().buy(OFFER);
        assertNotNull(offer, "the 0.2 buy offer did not reach the order book");
        assertEquals(new PlayerAccountId(ALICE), offer.owner());
        assertEquals("minecraft:oak_log", offer.item());
        assertEquals(16, offer.remaining());
        assertEquals(4L, offer.unitPrice());
        assertEquals(64L, offer.escrow());
        // Exactly once: the market's view of Alice's offers is the book order, not a second copy.
        assertEquals(1, store.book().buysOf(new PlayerAccountId(ALICE)).size());
        assertEquals(1, store.market().buyOffersOf(ALICE).size());
        assertEquals(OFFER, store.market().buyOffersOf(ALICE).get(0).offerId());
        // The credit claim.
        assertEquals(75L, store.market().creditClaimTotal(BOB));
        // Jobs, with their states; bounty progress; the shop owner.
        ObligationSavedData obligations = store.obligations();
        assertJob(obligations.job(CONTRACT), JobState.OPEN, 120L);
        assertJob(obligations.job(DELIVERY), JobState.CLAIMED, 30L);
        assertEquals(BOB.toString(), obligations.job(DELIVERY).claimant());
        assertJob(obligations.job(BOUNTY), JobState.OPEN, 50L);
        assertJob(obligations.job(DONE), JobState.FULFILLED, 10L);
        JsonObject encoded = ObligationSavedData.CODEC.encodeStart(JsonOps.INSTANCE, obligations).getOrThrow()
                .getAsJsonObject();
        JsonObject progress = encoded.getAsJsonArray("bounty_progress").get(0).getAsJsonObject();
        assertEquals(BOUNTY.toString(), progress.get("job").getAsString());
        assertEquals(2, progress.get("kills").getAsInt());
        assertEquals(List.of(SHOP), store.shopOwners().locations());
        // The escrow 0.2 had written off the ledger is back in the escrow account, owed exactly.
        assertEquals(OWED, store.escrowObligations());
        assertEquals(OWED, ledger.balance(SystemAccountId.ESCROW));
        assertTrue(ledger.balance(SystemAccountId.RESERVE) > 0L, "the Reserve was not filled");
        assertTrue(store.checkInvariants());
        SupplyReport report = store.supplyReport();
        assertTrue(report.healthy());
        assertEquals(1_000L + 250L + 40L + OWED + ledger.balance(SystemAccountId.RESERVE), report.total());
    }

    private static void assertJob(EconomyJob job, JobState state, long payout) {
        assertNotNull(job, "a 0.2 job was lost");
        assertEquals(state, job.state());
        assertEquals(payout, job.payout());
    }

    @Test void theSecondLoadIsANoOp() {
        EconomyStore store = oldWorld();
        JsonObject first = StoreTesting.encode(store).getAsJsonObject();
        // In the session that migrated, the market section is also the old market file, which is
        // written with a mirror of the book in place of its board rows (flagged as a mirror), so
        // that a store lost before its first save could be rebuilt from it.
        JsonObject firstMarket = first.getAsJsonObject("market");
        assertTrue(firstMarket.get("board_rows_mirror_book").getAsBoolean());
        // The next start reads the combined store back. Its schema is current, so the first-load
        // set-up is not run again (EconomyStore.open), and the mirrored rows are dropped, not adopted.
        EconomyStore again = StoreTesting.reload(store);
        assertEquals(EconomyStore.SCHEMA, again.schema());
        JsonObject second = StoreTesting.encode(again).getAsJsonObject();
        for (String field : first.keySet())
            if (!field.equals("market")) assertEquals(first.get(field), second.get(field), field);
        assertEquals(first.keySet(), second.keySet());
        JsonObject secondMarket = second.getAsJsonObject("market");
        assertTrue(!secondMarket.has("buy_offers") && !secondMarket.has("board_rows_mirror_book")
                && !secondMarket.has("item_claims_mirror_vault"), secondMarket.toString());
        assertEquals(firstMarket.get("credit_claims"), secondMarket.get("credit_claims"));
        // Nothing was adopted a second time and nothing moved.
        assertEquals(1, again.book().buysOf(new PlayerAccountId(ALICE)).size());
        assertEquals(store.book().buy(OFFER), again.book().buy(OFFER));
        assertTrue(again.market().drainLegacyBoardRows(false).isEmpty());
        assertTrue(again.market().drainLegacyItemClaims(false).isEmpty());
        assertEquals(OWED, again.ledger().balance(SystemAccountId.ESCROW));
        assertEquals(OWED, again.escrowObligations());
        assertTrue(again.checkInvariants());
        // And every later load is byte for byte the same.
        assertEquals(second, StoreTesting.encode(StoreTesting.reload(again)));
    }
}
