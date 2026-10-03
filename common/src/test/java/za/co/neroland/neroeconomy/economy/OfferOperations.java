package za.co.neroland.neroeconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.book.GoodsOrigin;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.offers.Offer;
import za.co.neroland.neroeconomy.offers.OfferOutcome;
import za.co.neroland.neroeconomy.offers.OfferResult;
import za.co.neroland.neroeconomy.offers.OffersSection;

/**
 * The fuzzer's offer operations, against the real offers section. What an offer holds — its lots
 * and its credits — is the store's own record, so the world's checks cover it without a model;
 * an operation only tells the world when goods come into the store from a hand.
 */
final class OfferOperations {
    private OfferOperations() { }

    static List<FuzzOperation> all() {
        return List.of(new Send(), new Accept(), new Decline(), new Cancel(), new Expire(), new Mute());
    }

    /** Everything two accounts hold that an offer could move: for "a refusal changes nothing". */
    private static String holdings(FuzzWorld world, UUID first, UUID second) {
        EconomySavedData ledger = world.ledger();
        return ledger.getBalance(first, NeroEconomyCommon.CREDITS) + "/" + world.vault().stacks(first) + "/"
                + world.store.market().creditClaimTotal(first) + ";" + ledger.getBalance(second, NeroEconomyCommon.CREDITS)
                + "/" + world.vault().stacks(second) + "/" + world.store.market().creditClaimTotal(second) + ";"
                + ledger.balance(za.co.neroland.neroeconomy.api.SystemAccountId.ESCROW) + ";" + world.outside;
    }

    /** One account sends another an offer: goods from the vault or the hand, credits, and sometimes an ask. */
    static final class Send implements FuzzOperation {
        @Override public String name() { return "offer_send"; }
        @Override public int weight() { return 60; }
        @Override public long run(FuzzWorld world) {
            UUID sender = world.player();
            // Now and then to oneself, which is refused.
            UUID recipient = world.random.nextInt(25) == 0 ? sender : world.player();
            AccountId account = FuzzWorld.account(sender);
            List<OffersSection.Given> given = new ArrayList<>();
            List<Lot> held = world.vault().stacks(account);
            // One vault and one hand for the whole offer, as the game has.
            GoodsOrigin vault = VaultGoods.origin(world.store, world.env, account);
            GoodsOrigin inHand = BookOperations.hand(world);
            for (int index = world.random.nextInt(4); index > 0; index--) {
                boolean hand = world.random.nextInt(3) == 0;
                Lot lot = hand || held.isEmpty() ? world.lot() : held.get(world.random.nextInt(held.size()));
                lot = lot.withCount(1 + world.random.nextInt(lot.count()));
                given.add(new OffersSection.Given(lot, hand ? inHand : vault));
            }
            long credits = world.random.nextInt(3) == 0 ? world.amount() : 0L;
            boolean asks = world.random.nextInt(3) == 0;
            String askItem = asks ? FuzzWorld.ITEMS[world.random.nextInt(FuzzWorld.ITEMS.length)] : null;
            int askCount = asks ? 1 + world.random.nextInt(12) : 0;
            long askCredits = world.random.nextInt(4) == 0 ? world.random.nextInt(60) : 0L;
            String before = holdings(world, sender, recipient);
            OfferOutcome sent = world.store.offers().send(world.env, account, FuzzWorld.account(recipient), given, credits,
                    askItem, askCount, askCredits, BookOperations.id(world));
            if (!sent.ok()) {
                // A refused send leaves the sender, the recipient, escrow and every hand as they were.
                assertEquals(before, holdings(world, sender, recipient), sent.result().name());
                return 0L;
            }
            return 1L + sent.offer().itemCount() * 7L + sent.offer().credits();
        }
    }

    /** A recipient accepts an offer waiting for them — or somebody tries to accept one that is not theirs. */
    static final class Accept implements FuzzOperation {
        @Override public String name() { return "offer_accept"; }
        @Override public int weight() { return 50; }
        @Override public long run(FuzzWorld world) {
            OffersSection offers = world.store.offers();
            UUID player = world.player();
            List<Offer> waiting = offers.receivedBy(FuzzWorld.account(player));
            if (world.random.nextInt(12) == 0) {
                // An offer addressed to somebody else is not found, whoever asks.
                List<Offer> theirs = offers.sentBy(FuzzWorld.account(player));
                if (!theirs.isEmpty() && !theirs.get(0).recipient().equals(FuzzWorld.account(player)))
                    assertEquals(OfferResult.NOT_FOUND, offers.accept(world.env, theirs.get(0).id(),
                            FuzzWorld.account(player)).result());
                return 0L;
            }
            if (waiting.isEmpty()) return 0L;
            Offer offer = waiting.get(world.random.nextInt(waiting.size()));
            UUID sender = ((za.co.neroland.neroeconomy.api.PlayerAccountId) offer.sender()).playerId();
            String before = holdings(world, player, sender);
            OfferOutcome accepted = offers.accept(world.env, offer.id(), FuzzWorld.account(player));
            if (!accepted.ok()) {
                // Every refused accept leaves both sides exactly as they were, and the offer open.
                assertEquals(before, holdings(world, player, sender), accepted.result().name());
                assertTrue(offers.offer(offer.id()) != null);
                return 0L;
            }
            assertEquals(offer.credits(), accepted.recipientGot() + accepted.tax() - (offer.askCredits() - accepted.senderGets()));
            return 1L + accepted.recipientGot() + accepted.senderGets() * 3L + accepted.tax() * 31L;
        }
    }

    /** A recipient declines an offer: everything goes back to its sender. */
    static final class Decline implements FuzzOperation {
        @Override public String name() { return "offer_decline"; }
        @Override public int weight() { return 20; }
        @Override public long run(FuzzWorld world) {
            OffersSection offers = world.store.offers();
            AccountId account = FuzzWorld.account(world.player());
            List<Offer> waiting = offers.receivedBy(account);
            if (waiting.isEmpty()) return 0L;
            Offer offer = waiting.get(world.random.nextInt(waiting.size()));
            if (!offers.decline(world.env, offer.id(), account).ok()) return 0L;
            assertEquals(OfferResult.NOT_FOUND, offers.decline(world.env, offer.id(), account).result());
            return 1L + offer.credits();
        }
    }

    /** A sender takes an offer back. */
    static final class Cancel implements FuzzOperation {
        @Override public String name() { return "offer_cancel"; }
        @Override public int weight() { return 20; }
        @Override public long run(FuzzWorld world) {
            OffersSection offers = world.store.offers();
            AccountId account = FuzzWorld.account(world.player());
            List<Offer> sent = offers.sentBy(account);
            if (sent.isEmpty()) return 0L;
            Offer offer = sent.get(world.random.nextInt(sent.size()));
            if (!offers.cancel(world.env, offer.id(), account).ok()) return 0L;
            assertEquals(OfferResult.NOT_FOUND, offers.cancel(world.env, offer.id(), account).result());
            return 1L + offer.credits();
        }
    }

    /** The short-cadence expiry sweep the server tick runs. */
    static final class Expire implements FuzzOperation {
        @Override public String name() { return "offer_expire"; }
        @Override public int weight() { return 15; }
        @Override public long run(FuzzWorld world) {
            if (world.random.nextInt(3) == 0) world.wall += 3_600_000L + world.random.nextInt(7_200_000);
            return world.store.offers().expire(world.env, world.ledger().now());
        }
    }

    /** A player mutes or unmutes offers. */
    static final class Mute implements FuzzOperation {
        @Override public String name() { return "offer_mute"; }
        @Override public int weight() { return 5; }
        @Override public long run(FuzzWorld world) {
            AccountId account = FuzzWorld.account(world.player());
            boolean mute = world.random.nextInt(3) == 0;
            world.store.offers().setMuted(account, mute);
            assertEquals(mute, world.store.offers().muted(account));
            return mute ? 2L : 1L;
        }
    }
}
