package za.co.neroland.neroeconomy.offers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.book.GoodsOrigin;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.trade.TradeSettings;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/**
 * Escrowed offers against a real store: escrow at send, the atomic swap on accept, every refusal
 * leaving both sides as they were, refunds, the flood caps, the mute flag, the trade tax, the
 * vesting lock, erasure and the saved form. Every test ends with both ledger invariants holding.
 */
class OffersSectionTest {
    private static final String DIAMOND = TradeWorld.DIAMOND;
    private static final String IRON = TradeWorld.IRON;
    private final TradeWorld world = new TradeWorld();

    @AfterEach void done() {
        assertTrue(world.invariants(), "the ledger invariants must hold at the end of every test");
        world.close();
    }

    private OffersSection offers() { return world.store.offers(); }

    /** Sends an offer that gives {@code lots} from the sender's vault. */
    private OfferOutcome send(UUID sender, UUID recipient, List<Lot> lots, long credits, String askItem, int askCount,
            long askCredits) {
        AccountId account = TradeWorld.account(sender);
        List<OffersSection.Given> given = new ArrayList<>();
        GoodsOrigin vault = VaultGoods.origin(world.store, world.env, account);
        for (Lot lot : lots) given.add(new OffersSection.Given(lot, vault));
        return offers().send(world.env, account, TradeWorld.account(recipient), given, credits, askItem, askCount,
                askCredits, null);
    }

    /** Everything about two players that an offer could change. */
    private String snapshot(UUID... players) {
        StringBuilder out = new StringBuilder();
        for (UUID player : players) out.append(world.balance(player)).append('/').append(world.claim(player)).append('/')
                .append(world.store.vault().stacks(player)).append(';');
        return out.append(world.escrow()).append(';').append(world.reserve()).append(';').append(offers().size()).toString();
    }

    // --- sending -----------------------------------------------------------------------------

    @Test void everythingOfferedIsEscrowedAtSend() {
        UUID sender = world.player(1, 500L);
        UUID recipient = world.player(2, 100L);
        world.stock(sender, Lot.of(DIAMOND, 30));
        world.stock(sender, new Lot("minecraft:diamond_sword", 1, 4));

        OfferOutcome sent = send(sender, recipient, List.of(Lot.of(DIAMOND, 20), new Lot("minecraft:diamond_sword", 1, 4)),
                150L, IRON, 10, 25L);

        assertTrue(sent.ok());
        // The goods left the sender's vault and are held by the offer; the credits are in escrow.
        assertEquals(10L, world.held(sender, DIAMOND));
        assertEquals(0L, world.held(sender, "minecraft:diamond_sword", 4));
        assertEquals(20L + 10L, world.inStore(DIAMOND, 0));
        assertEquals(350L, world.balance(sender));
        assertEquals(150L, world.escrow());
        assertEquals(150L, offers().escrowObligations());
        // Nothing has reached the recipient yet, except the alert that an offer is waiting.
        assertEquals(100L, world.balance(recipient));
        assertEquals(0L, world.held(recipient, DIAMOND));
        assertEquals(List.of(Map.entry(TradeWorld.account(recipient), EconomyAlerts.Kind.OFFER_RECEIVED)), world.alerts);
        Offer offer = offers().offer(sent.offer().id());
        assertEquals(world.now() + 48L * TradeWorld.HOUR, offer.expiresAt());
        assertEquals(21L, offer.itemCount());
        assertEquals(1, offers().outgoing(TradeWorld.account(sender)));
        assertEquals(1, offers().incoming(TradeWorld.account(recipient)));
        assertEquals(List.of(offer), offers().sentBy(TradeWorld.account(sender)));
        assertEquals(List.of(offer), offers().receivedBy(TradeWorld.account(recipient)));
    }

    @Test void anOfferThatCannotBeFundedLeavesTheSenderExactlyAsTheyWere() {
        UUID sender = world.player(1, 50L);
        UUID recipient = world.player(2, 100L);
        world.stock(sender, Lot.of(DIAMOND, 30));
        String before = snapshot(sender, recipient);

        assertEquals(OfferResult.INSUFFICIENT_FUNDS, send(sender, recipient, List.of(Lot.of(DIAMOND, 20)), 60L, null, 0, 0L).result());
        assertEquals(OfferResult.NOT_HOLDING, send(sender, recipient, List.of(Lot.of(DIAMOND, 31)), 0L, null, 0, 0L).result());
        // Two lots that are each there, but not both together.
        assertEquals(OfferResult.NOT_HOLDING, send(sender, recipient, List.of(Lot.of(DIAMOND, 20), Lot.of(DIAMOND, 20)), 0L,
                null, 0, 0L).result());
        assertEquals(OfferResult.EMPTY, send(sender, recipient, List.of(), 0L, IRON, 5, 0L).result());
        assertEquals(OfferResult.SELF, send(sender, sender, List.of(Lot.of(DIAMOND, 1)), 0L, null, 0, 0L).result());
        assertEquals(OfferResult.INVALID, send(sender, recipient, List.of(Lot.of(DIAMOND, 1)), -1L, null, 0, 0L).result());
        assertEquals(OfferResult.INVALID, send(sender, recipient, List.of(Lot.of(DIAMOND, 1)), 0L, null, 0, -5L).result());
        assertEquals(OfferResult.INVALID, send(sender, recipient, List.of(Lot.of(DIAMOND, 1)), 0L, "", 3, 0L).result());
        List<Lot> tooMany = new ArrayList<>();
        for (int index = 0; index < 10; index++) tooMany.add(Lot.of(DIAMOND, 1));
        assertEquals(OfferResult.INVALID, send(sender, recipient, tooMany, 0L, null, 0, 0L).result());

        assertEquals(before, snapshot(sender, recipient));
        assertTrue(world.alerts.isEmpty());
    }

    @Test void creditsOfferedCannotComeOutOfAStartingBalanceThatHasNotVested() {
        UUID newcomer = world.newcomer(1);
        UUID recipient = world.player(2, 100L);
        world.stock(newcomer, Lot.of(DIAMOND, 5));
        assertEquals(OfferResult.FUNDS_LOCKED, send(newcomer, recipient, List.of(Lot.of(DIAMOND, 5)), 40L, null, 0, 0L).result());
        // The goods that had been taken for it are back.
        assertEquals(5L, world.held(newcomer, DIAMOND));
        assertEquals(100L, world.balance(newcomer));
        // Goods alone are fine: nothing locked is being paid to anyone.
        assertTrue(send(newcomer, recipient, List.of(Lot.of(DIAMOND, 5)), 0L, null, 0, 0L).ok());
    }

    // --- accepting ---------------------------------------------------------------------------

    @Test void acceptingSwapsEverythingInOneGoAndTaxesEachCreditSide() {
        world.env = world.env.with(TradeWorld.settings().withFees(2.0D, 1L, 5.0D));
        UUID sender = world.player(1, 500L);
        UUID recipient = world.player(2, 300L);
        world.stock(sender, Lot.of(DIAMOND, 30));
        world.stock(recipient, Lot.of(IRON, 40));
        OfferOutcome sent = send(sender, recipient, List.of(Lot.of(DIAMOND, 20)), 100L, IRON, 10, 60L);
        long reserve = world.reserve();
        world.alerts.clear();

        OfferOutcome accepted = offers().accept(world.env, sent.offer().id(), TradeWorld.account(recipient));

        assertTrue(accepted.ok());
        // Given: 20 diamonds and 100 credits less 5 % tax reach the recipient.
        assertEquals(20L, world.held(recipient, DIAMOND));
        assertEquals(95L, accepted.recipientGot());
        // Asked: 10 iron and 60 credits less 5 % tax reach the sender; the credits wait as their claim.
        assertEquals(30L, world.held(recipient, IRON));
        assertEquals(10L, world.held(sender, IRON));
        assertEquals(60L, accepted.recipientPaid());
        assertEquals(57L, accepted.senderGets());
        assertEquals(57L, world.claim(sender));
        assertEquals(300L + 95L - 60L, world.balance(recipient));
        assertEquals(400L, world.balance(sender));
        // The tax on both sides went to the Reserve.
        assertEquals(8L, accepted.tax());
        assertEquals(reserve + 8L, world.reserve());
        assertEquals(57L, world.escrow());
        assertNull(offers().offer(sent.offer().id()));
        assertEquals(0, offers().outgoing(TradeWorld.account(sender)));
        assertEquals(List.of(Map.entry(TradeWorld.account(sender), EconomyAlerts.Kind.OFFER_ACCEPTED)), world.alerts);
        // It can only be accepted once.
        assertEquals(OfferResult.NOT_FOUND, offers().accept(world.env, sent.offer().id(), TradeWorld.account(recipient)).result());
    }

    @Test void aGiftIsAcceptedWithNothingAskedAndNoTaxWhenNoneIsConfigured() {
        UUID sender = world.player(1, 500L);
        UUID recipient = world.player(2, 0L);
        world.stock(sender, Lot.of(DIAMOND, 3));
        OfferOutcome sent = send(sender, recipient, List.of(Lot.of(DIAMOND, 3)), 25L, null, 0, 0L);
        long reserve = world.reserve();
        OfferOutcome accepted = offers().accept(world.env, sent.offer().id(), TradeWorld.account(recipient));
        assertTrue(accepted.ok());
        assertEquals(25L, world.balance(recipient));
        assertEquals(3L, world.held(recipient, DIAMOND));
        assertEquals(0L, accepted.tax());
        assertEquals(reserve, world.reserve());
        assertEquals(0L, world.escrow());
    }

    @Test void everyRefusedAcceptLeavesBothSidesExactlyAsTheyWere() {
        UUID sender = world.player(1, 500L);
        UUID recipient = world.player(2, 30L);
        UUID stranger = world.player(3, 500L);
        world.stock(sender, Lot.of(DIAMOND, 30));
        world.stock(recipient, Lot.of(IRON, 5));
        OfferOutcome sent = send(sender, recipient, List.of(Lot.of(DIAMOND, 20)), 100L, IRON, 10, 60L);
        UUID id = sent.offer().id();
        AccountId who = TradeWorld.account(recipient);
        String before = snapshot(sender, recipient, stranger);

        // Not theirs to accept: somebody else, and the sender themselves.
        assertEquals(OfferResult.NOT_FOUND, offers().accept(world.env, id, TradeWorld.account(stranger)).result());
        assertEquals(OfferResult.NOT_FOUND, offers().accept(world.env, id, TradeWorld.account(sender)).result());
        assertEquals(OfferResult.NOT_FOUND, offers().accept(world.env, UUID.randomUUID(), who).result());
        // The recipient lacks the items asked for.
        assertEquals(OfferResult.MISSING_ITEMS, offers().accept(world.env, id, who).result());
        assertEquals(before, snapshot(sender, recipient, stranger));

        // With the items but not the credits.
        world.stock(recipient, Lot.of(IRON, 5));
        before = snapshot(sender, recipient, stranger);
        assertEquals(OfferResult.INSUFFICIENT_FUNDS, offers().accept(world.env, id, who).result());
        assertEquals(before, snapshot(sender, recipient, stranger));
        assertEquals(10L, world.held(recipient, IRON));

        // With both, but a vault that cannot take what is given.
        world.player(2, 400L);
        world.env = world.env.with(TradeWorld.settings().withVault(1, 1, 4));
        world.stock(recipient, Lot.of(TradeWorld.GOLD, 1));
        before = snapshot(sender, recipient, stranger);
        assertEquals(OfferResult.VAULT_FULL, offers().accept(world.env, id, who).result());
        assertEquals(before, snapshot(sender, recipient, stranger));

        // With room on the recipient's side, but none on the sender's for what is asked.
        world.env = world.env.with(TradeWorld.settings().withVault(2, 2, 8));
        world.stock(sender, Lot.of(TradeWorld.GOLD, 1));
        world.stock(sender, new Lot(TradeWorld.GOLD, 1, 3));
        before = snapshot(sender, recipient, stranger);
        assertEquals(OfferResult.OTHER_VAULT_FULL, offers().accept(world.env, id, who).result());
        assertEquals(before, snapshot(sender, recipient, stranger));
        assertNotNull(offers().offer(id));

        // Once it has expired it cannot be accepted any more either.
        world.wall += 49L * TradeWorld.HOUR;
        world.env = world.env.with(TradeWorld.settings());
        before = snapshot(sender, recipient, stranger);
        assertEquals(OfferResult.NOT_FOUND, offers().accept(world.env, id, who).result());
        assertEquals(before, snapshot(sender, recipient, stranger));
    }

    @Test void creditsAskedCannotBePaidOutOfAStartingBalanceThatHasNotVested() {
        UUID sender = world.player(1, 100L);
        UUID newcomer = world.newcomer(2);
        world.stock(sender, Lot.of(DIAMOND, 4));
        OfferOutcome sent = send(sender, newcomer, List.of(Lot.of(DIAMOND, 4)), 0L, null, 0, 50L);
        String before = snapshot(sender, newcomer);
        assertEquals(OfferResult.FUNDS_LOCKED, offers().accept(world.env, sent.offer().id(), TradeWorld.account(newcomer)).result());
        assertEquals(before, snapshot(sender, newcomer));
        world.ledger().markVested(newcomer);
        assertTrue(offers().accept(world.env, sent.offer().id(), TradeWorld.account(newcomer)).ok());
        assertEquals(50L, world.claim(sender));
        assertEquals(50L, world.balance(newcomer));
    }

    // --- refunds -----------------------------------------------------------------------------

    @Test void decliningCancellingAndExpiryEachReturnEverythingToTheSenderOnce() {
        UUID sender = world.player(1, 500L);
        UUID recipient = world.player(2, 100L);
        world.stock(sender, Lot.of(DIAMOND, 30));
        AccountId from = TradeWorld.account(sender);
        AccountId to = TradeWorld.account(recipient);
        String untouched = snapshot(sender, recipient);

        // Declined by the recipient.
        UUID declined = send(sender, recipient, List.of(Lot.of(DIAMOND, 10)), 40L, IRON, 1, 0L).offer().id();
        assertEquals(OfferResult.NOT_FOUND, offers().decline(world.env, declined, from).result());
        OfferOutcome back = offers().decline(world.env, declined, to);
        assertTrue(back.ok());
        assertFalse(back.refundedToClaim());
        assertEquals(untouched, snapshot(sender, recipient));
        assertEquals(OfferResult.NOT_FOUND, offers().decline(world.env, declined, to).result());

        // Cancelled by the sender.
        UUID cancelled = send(sender, recipient, List.of(Lot.of(DIAMOND, 10)), 40L, null, 0, 5L).offer().id();
        assertEquals(OfferResult.NOT_FOUND, offers().cancel(world.env, cancelled, to).result());
        assertTrue(offers().cancel(world.env, cancelled, from).ok());
        assertEquals(untouched, snapshot(sender, recipient));
        assertEquals(OfferResult.NOT_FOUND, offers().cancel(world.env, cancelled, from).result());

        // Left unanswered until it expires.
        send(sender, recipient, List.of(Lot.of(DIAMOND, 10)), 40L, null, 0, 0L);
        world.alerts.clear();
        world.wall += 47L * TradeWorld.HOUR;
        assertEquals(0, offers().expire(world.env, world.now()));
        world.wall += 2L * TradeWorld.HOUR;
        assertEquals(1, offers().expire(world.env, world.now()));
        assertEquals(untouched, snapshot(sender, recipient));
        assertEquals(List.of(Map.entry(from, EconomyAlerts.Kind.OFFER_EXPIRED)), world.alerts);
        assertEquals(0, offers().expire(world.env, world.now()));
    }

    @Test void theSenderCanAlwaysCancelAndADeclineAlwaysReturnsTheGoodsEvenPastTheVaultCap() {
        UUID sender = world.player(1, 500L);
        UUID recipient = world.player(2, 100L);
        world.stock(sender, Lot.of(DIAMOND, 20));
        UUID cancelled = send(sender, recipient, List.of(Lot.of(DIAMOND, 10)), 30L, null, 0, 0L).offer().id();
        UUID declined = send(sender, recipient, List.of(Lot.of(DIAMOND, 10)), 0L, null, 0, 0L).offer().id();
        // The sender's vault is full: one stack is all it may hold, and it holds one.
        world.env = world.env.with(TradeWorld.settings().withVault(1, 1, 4));
        world.stock(sender, Lot.of(IRON, 1));
        // A cancel is a refund, and a refund always books: the goods come back past the cap.
        OfferOutcome taken = offers().cancel(world.env, cancelled, TradeWorld.account(sender));
        assertTrue(taken.ok());
        assertNull(offers().offer(cancelled));
        assertEquals(10L, world.held(sender, DIAMOND));
        assertEquals(500L, world.balance(sender));
        assertEquals(2, world.store.vault().stackCount(sender));
        // A second cancel finds nothing, so nothing comes back twice.
        assertEquals(OfferResult.NOT_FOUND, offers().cancel(world.env, cancelled, TradeWorld.account(sender)).result());
        // The recipient's decline books past the cap in the same way.
        assertNotNull(offers().offer(declined));
        assertTrue(offers().decline(world.env, declined, TradeWorld.account(recipient)).ok());
        assertEquals(20L, world.held(sender, DIAMOND));
        assertEquals(20L, world.inStore(DIAMOND, Lot.PRISTINE));
    }

    // --- caps and the mute flag --------------------------------------------------------------

    @Test void nobodyCanSendMoreThanTheirCapOrFloodOneRecipient() {
        world.env = world.env.with(TradeWorld.settings().withOfferCaps(2, 3));
        UUID sender = world.player(1, 500L);
        UUID other = world.player(2, 500L);
        UUID third = world.player(3, 500L);
        UUID recipient = world.player(4, 100L);
        assertTrue(send(sender, recipient, List.of(), 1L, null, 0, 0L).ok());
        UUID second = send(sender, recipient, List.of(), 1L, null, 0, 0L).offer().id();
        // The sender is at their own cap.
        assertEquals(OfferResult.LIMIT_REACHED, send(sender, recipient, List.of(), 1L, null, 0, 0L).result());
        assertEquals(OfferResult.LIMIT_REACHED, send(sender, other, List.of(), 1L, null, 0, 0L).result());
        // The recipient has room for one more, from anyone, and then none.
        assertTrue(send(other, recipient, List.of(), 1L, null, 0, 0L).ok());
        assertEquals(OfferResult.NOT_ACCEPTING, send(third, recipient, List.of(), 1L, null, 0, 0L).result());
        assertEquals(500L, world.balance(third));
        // Answering one frees a place on both sides.
        assertTrue(offers().decline(world.env, second, TradeWorld.account(recipient)).ok());
        assertTrue(send(third, recipient, List.of(), 1L, null, 0, 0L).ok());
        assertTrue(send(sender, other, List.of(), 1L, null, 0, 0L).ok());
    }

    @Test void oneSenderMayKeepOnlyAFewOffersWaitingWithOneRecipientSoAFewAltsCannotFillAnInbox() {
        UUID sender = world.player(1, 500L);
        UUID recipient = world.player(2, 100L);
        UUID other = world.player(3, 100L);
        UUID first = null;
        for (int i = 0; i < TradeSettings.MAX_OFFERS_PER_RECIPIENT; i++) {
            OfferOutcome sent = send(sender, recipient, List.of(), 1L, null, 0, 0L);
            assertTrue(sent.ok());
            if (first == null) first = sent.offer().id();
        }
        long balance = world.balance(sender);
        assertEquals(OfferResult.PAIR_LIMIT, send(sender, recipient, List.of(), 1L, null, 0, 0L).result());
        assertEquals(balance, world.balance(sender), "a refused offer escrows nothing");
        assertEquals(TradeSettings.MAX_OFFERS_PER_RECIPIENT, offers().between(TradeWorld.account(sender),
                TradeWorld.account(recipient)));
        // Another recipient is not affected, and an answered offer frees its place.
        assertTrue(send(sender, other, List.of(), 1L, null, 0, 0L).ok());
        assertTrue(offers().decline(world.env, first, TradeWorld.account(recipient)).ok());
        assertTrue(send(sender, recipient, List.of(), 1L, null, 0, 0L).ok());
        // Filling the 64-offer inbox now takes at least 64 / 4 accounts.
        assertTrue(TradeSettings.MAX_OFFERS_IN / TradeSettings.MAX_OFFERS_PER_RECIPIENT >= 16);
    }

    @Test void aMutedRecipientGetsNoOffersAndTheSenderIsToldNothingAboutThem() {
        UUID sender = world.player(1, 500L);
        UUID recipient = world.player(2, 100L);
        AccountId to = TradeWorld.account(recipient);
        UUID waiting = send(sender, recipient, List.of(), 5L, null, 0, 0L).offer().id();
        assertFalse(offers().muted(to));

        offers().setMuted(to, true);

        assertTrue(offers().muted(to));
        // The same answer as a full inbox, and nothing moved.
        assertEquals(OfferResult.NOT_ACCEPTING, send(sender, recipient, List.of(), 5L, null, 0, 0L).result());
        assertEquals(495L, world.balance(sender));
        // An offer that was already waiting can still be answered.
        assertTrue(offers().accept(world.env, waiting, to).ok());
        // The flag survives a save, and is the player's to lift.
        world.reload();
        assertTrue(offers().muted(to));
        offers().setMuted(to, false);
        assertTrue(send(sender, recipient, List.of(), 5L, null, 0, 0L).ok());
    }

    // --- erasure, export and the saved form --------------------------------------------------

    @Test void erasingTheSenderDestroysWhatTheyOfferedAndSweepsItsCredits() {
        UUID sender = world.player(1, 500L);
        UUID recipient = world.player(2, 100L);
        world.stock(sender, Lot.of(DIAMOND, 10));
        send(sender, recipient, List.of(Lot.of(DIAMOND, 10)), 70L, null, 0, 0L);
        offers().setMuted(TradeWorld.account(sender), true);
        long reserve = world.reserve();

        List<Lot> destroyed = offers().forgetPlayer(world.env, sender);

        assertEquals(List.of(Lot.of(DIAMOND, 10)), destroyed);
        assertEquals(reserve + 70L, world.reserve());
        assertEquals(0L, world.escrow());
        assertEquals(0, offers().size());
        assertFalse(offers().muted(TradeWorld.account(sender)));
        assertEquals(0, offers().incoming(TradeWorld.account(recipient)));
    }

    @Test void erasingTheRecipientGivesTheOfferBackToItsSender() {
        UUID sender = world.player(1, 500L);
        UUID recipient = world.player(2, 100L);
        world.stock(sender, Lot.of(DIAMOND, 10));
        send(sender, recipient, List.of(Lot.of(DIAMOND, 10)), 70L, IRON, 3, 9L);

        List<Lot> destroyed = offers().forgetPlayer(world.env, recipient);

        // Nothing of the sender's was destroyed: the goods are back in their vault and the credits
        // are theirs to collect.
        assertTrue(destroyed.isEmpty());
        assertEquals(10L, world.held(sender, DIAMOND));
        assertEquals(70L, world.claim(sender));
        assertEquals(70L, world.escrow());
        assertEquals(0, offers().size());
        assertFalse(offers().exportFor(sender).toString().contains(recipient.toString()));
    }

    @Test void theExportListsTheSubjectsOffersAndNeverNamesTheOtherParty() {
        UUID subject = world.player(1, 500L);
        UUID other = world.player(2, 500L);
        world.stock(subject, Lot.of(DIAMOND, 10));
        send(subject, other, List.of(Lot.of(DIAMOND, 4)), 12L, IRON, 2, 3L);
        send(other, subject, List.of(), 30L, null, 0, 0L);
        offers().setMuted(TradeWorld.account(subject), true);

        JsonObject export = offers().exportFor(subject);

        assertTrue(export.get("offers_muted").getAsBoolean());
        assertEquals(1, export.getAsJsonArray("sent").size());
        assertEquals(1, export.getAsJsonArray("received").size());
        JsonObject sent = export.getAsJsonArray("sent").get(0).getAsJsonObject();
        assertEquals(12L, sent.get("credits").getAsLong());
        assertEquals(IRON, sent.get("ask_item").getAsString());
        assertEquals(DIAMOND, sent.getAsJsonArray("items").get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(30L, export.getAsJsonArray("received").get(0).getAsJsonObject().get("credits").getAsLong());
        String text = export.toString();
        assertFalse(text.contains(other.toString()));
        assertFalse(text.contains(subject.toString()));
    }

    @Test void offersSurviveASaveAndAreStillAnsweredAndRefundedCorrectly() {
        UUID sender = world.player(1, 500L);
        UUID recipient = world.player(2, 300L);
        world.stock(sender, Lot.of(DIAMOND, 30));
        world.stock(sender, new Lot("minecraft:diamond_sword", 1, 6));
        world.stock(recipient, Lot.of(IRON, 40));
        UUID kept = send(sender, recipient, List.of(Lot.of(DIAMOND, 20), new Lot("minecraft:diamond_sword", 1, 6)), 100L,
                IRON, 10, 60L).offer().id();
        UUID lapsing = send(sender, recipient, List.of(), 15L, null, 0, 0L).offer().id();

        world.reload();

        assertTrue(world.invariants());
        Offer offer = offers().offer(kept);
        assertEquals(2, offer.lots().size());
        assertEquals(6, offer.lots().get(1).payload());
        assertEquals(10, offer.askCount());
        assertEquals(115L, world.escrow());
        assertEquals(2, offers().outgoing(TradeWorld.account(sender)));
        assertTrue(offers().accept(world.env, kept, TradeWorld.account(recipient)).ok());
        assertEquals(1L, world.held(recipient, "minecraft:diamond_sword", 6));
        assertEquals(60L, world.claim(sender));
        world.wall += 49L * TradeWorld.HOUR;
        assertEquals(1, offers().expire(world.env, world.now()));
        assertNull(offers().offer(lapsing));
        assertEquals(400L, world.balance(sender));
    }
}
