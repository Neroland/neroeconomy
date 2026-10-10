package za.co.neroland.neroeconomy.offers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.book.BookSection;
import za.co.neroland.neroeconomy.book.GoodsOrigin;
import za.co.neroland.neroeconomy.book.VaultGoods;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomySection;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.EscrowHolder;
import za.co.neroland.neroeconomy.economy.EscrowMath;
import za.co.neroland.neroeconomy.economy.Fees;
import za.co.neroland.neroeconomy.economy.LedgerPlan;
import za.co.neroland.neroeconomy.economy.LedgerResult;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.goods.LotHolder;
import za.co.neroland.neroeconomy.goods.Lots;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.market.MarketLimits;
import za.co.neroland.neroeconomy.trade.Accounts;
import za.co.neroland.neroeconomy.trade.TradeEnv;
import za.co.neroland.neroeconomy.trade.TradeSettings;
import za.co.neroland.neroeconomy.vault.VaultSection;

/**
 * The offers section of the economy store: escrowed offers from one account to another.
 *
 * <p><b>Sending.</b> Everything offered is escrowed at send: the lots leave the sender's hand or
 * vault and are held here, and the credits move to the ledger's escrow account. An offer may ask
 * for an item and quantity, credits, or both in return.
 *
 * <p><b>Accepting</b> is one swap. The recipient's side is taken from their vault (unmodified
 * items only) and their balance; then, as a single ledger plan, the credits given reach the
 * recipient and the credits asked leave them, each less {@code tradeTaxPercent}, which goes to the
 * Reserve. The sender's credits become their credit claim: the ledger reports at most one paying
 * and one receiving player per operation, and a claim also reaches a sender who is away. The given
 * lots go to the recipient's vault and the asked lots to the sender's. It is refused before
 * anything moves when either vault cannot take the goods or either side lacks what it must give.
 * Credits promised to another player cannot come out of an unvested starting balance, at send or
 * at accept.
 *
 * <p><b>Refunds.</b> A decline, a cancel by the sender and an expiry return everything to the
 * sender: lots to their vault, credits to their balance. All three always book, whatever the
 * vault's cap.
 *
 * <p><b>Bounds.</b> {@code maxOffersPerPlayer} open offers sent per account, and at most
 * {@code TradeSettings.MAX_OFFERS_IN} waiting for any one recipient, so nobody can be flooded;
 * nine lots per offer. A recipient can mute offers altogether. A sender refused for either reason
 * is told the same thing, which says nothing about the recipient. <b>Purge.</b> {@link #expire}.
 * <b>Erasure.</b> {@link #forgetPlayer}. <b>Export.</b> {@link #exportFor}.
 */
public final class OffersSection extends EconomySection implements LotHolder, EscrowHolder {
    public static final Codec<OffersSection> CODEC = codec();
    /** The most lots one offer may give: one row of an inventory. */
    public static final int MAX_LOTS = 9;

    /** One lot to give, and where it comes from: the sender's hand, or their vault. */
    public record Given(Lot lot, GoodsOrigin origin) { }

    private final Map<UUID, Offer> offers = new LinkedHashMap<>();
    private final Map<AccountId, Integer> outgoing = new HashMap<>();
    private final Map<AccountId, Integer> incoming = new HashMap<>();
    /** Accounts that take no offers and no trade requests. */
    private final Set<AccountId> muted = new LinkedHashSet<>();

    public OffersSection() { }

    // --- reads -------------------------------------------------------------------------------

    public synchronized Offer offer(UUID id) { return id == null ? null : offers.get(id); }
    public synchronized int size() { return offers.size(); }
    public synchronized int outgoing(AccountId sender) { return outgoing.getOrDefault(sender, 0); }
    public synchronized int incoming(AccountId recipient) { return incoming.getOrDefault(recipient, 0); }

    /** Open offers from {@code sender} waiting with {@code recipient}. */
    public synchronized int between(AccountId sender, AccountId recipient) {
        if (outgoing(sender) == 0) return 0;
        int count = 0;
        for (Offer offer : offers.values())
            if (offer.sender().equals(sender) && offer.recipient().equals(recipient)) count++;
        return count;
    }

    /** The open offers the account sent, oldest first. */
    public synchronized List<Offer> sentBy(AccountId sender) {
        List<Offer> out = new ArrayList<>();
        for (Offer offer : offers.values()) if (offer.sender().equals(sender)) out.add(offer);
        return out;
    }

    /** The open offers waiting for the account's answer, oldest first. */
    public synchronized List<Offer> receivedBy(AccountId recipient) {
        List<Offer> out = new ArrayList<>();
        for (Offer offer : offers.values()) if (offer.recipient().equals(recipient)) out.add(offer);
        return out;
    }

    /** Whether the account has muted offers and trade requests. */
    public synchronized boolean muted(AccountId account) { return account != null && muted.contains(account); }

    /** Mutes or unmutes offers and trade requests for an account. Offers already waiting stay. */
    public synchronized void setMuted(AccountId account, boolean mute) {
        if (account == null || !(mute ? muted.add(account) : muted.remove(account))) return;
        setDirty();
    }

    @Override public synchronized void forEachLot(Consumer<Lot> visitor) {
        for (Offer offer : offers.values()) offer.lots().forEach(visitor);
    }

    /** The credits given by every open offer. */
    @Override public synchronized long escrowObligations() {
        long total = 0L;
        for (Offer offer : offers.values()) total = EscrowMath.total(total, offer.credits());
        return total;
    }

    @Override public synchronized boolean isEmpty() { return offers.isEmpty() && muted.isEmpty(); }

    // --- sending -----------------------------------------------------------------------------

    /**
     * Sends an offer. The lots are taken from their origins and the credits moved into escrow, or
     * nothing is: a refusal leaves the sender exactly as they were.
     *
     * @param recipient an account the caller has already resolved as known; an offer never opens one
     * @param presetId  the id the offer is to have, or null for a fresh one
     */
    public synchronized OfferOutcome send(TradeEnv env, AccountId sender, AccountId recipient, List<Given> given,
            long credits, String askItem, int askCount, long askCredits, UUID presetId) {
        EconomyStore store = owner();
        if (EconomyHealth.frozen()) return OfferOutcome.refused(OfferResult.FROZEN);
        // Sending and accepting trade; declining, cancelling and expiry are refunds and still work.
        if (marketClosed()) return OfferOutcome.refused(OfferResult.MARKET_CLOSED);
        if (store == null || env == null || given == null || credits < 0L || askCount < 0 || askCredits < 0L
                || askCount > BookSection.MAX_ORDER_QUANTITY || given.size() > MAX_LOTS
                || !(sender instanceof PlayerAccountId) || !(recipient instanceof PlayerAccountId)
                || (presetId != null && offers.containsKey(presetId))) return OfferOutcome.refused(OfferResult.INVALID);
        if (askCount > 0 && (askItem == null || askItem.isBlank() || askItem.length() > Lot.MAX_ITEM_LENGTH))
            return OfferOutcome.refused(OfferResult.INVALID);
        if (sender.equals(recipient)) return OfferOutcome.refused(OfferResult.SELF);
        if (given.isEmpty() && credits == 0L) return OfferOutcome.refused(OfferResult.EMPTY);
        List<Lot> lots = new ArrayList<>(given.size());
        for (Given part : given) {
            if (part == null || part.lot() == null || part.origin() == null
                    || part.lot().count() > BookSection.MAX_ORDER_QUANTITY) return OfferOutcome.refused(OfferResult.INVALID);
            if (!env.items().tradeable(part.lot().item())) return OfferOutcome.refused(OfferResult.UNTRADEABLE);
            lots.add(part.lot());
        }
        if (askCount > 0) {
            if (!env.items().known(askItem)) return OfferOutcome.refused(OfferResult.UNKNOWN_ITEM);
            if (!env.items().tradeable(askItem)) return OfferOutcome.refused(OfferResult.UNTRADEABLE);
        }
        if (!MarketLimits.withinCap(outgoing(sender), 1, env.settings().maxOffersOut()))
            return OfferOutcome.refused(OfferResult.LIMIT_REACHED);
        // F1: one sender's offers to one recipient are capped, so it takes many accounts to fill an inbox.
        if (!MarketLimits.withinCap(between(sender, recipient), 1, TradeSettings.MAX_OFFERS_PER_RECIPIENT))
            return OfferOutcome.refused(OfferResult.PAIR_LIMIT);
        // Muted, or already holding as many unanswered offers as an account may: the same answer.
        if (muted.contains(recipient) || !MarketLimits.withinCap(incoming(recipient), 1, env.settings().maxOffersIn()))
            return OfferOutcome.refused(OfferResult.NOT_ACCEPTING);
        // Every lot has to be there, and lots of the same goods from the same place all together.
        Map<GoodsOrigin, Map<Lot.Key, Long>> needed = new LinkedHashMap<>();
        for (Given part : given) needed.computeIfAbsent(part.origin(), ignored -> new LinkedHashMap<>())
                .merge(part.lot().key(), (long) part.lot().count(), Long::sum);
        for (Map.Entry<GoodsOrigin, Map<Lot.Key, Long>> origin : needed.entrySet())
            for (Map.Entry<Lot.Key, Long> goods : origin.getValue().entrySet())
                if (goods.getValue() > Integer.MAX_VALUE || !origin.getKey().holds(new Lot(goods.getKey().item(),
                        goods.getValue().intValue(), goods.getKey().payload())))
                    return OfferOutcome.refused(OfferResult.NOT_HOLDING);
        // Escrow before publish. The credits first: a plan applies in full or not at all, and a
        // refusal there has touched nothing. They are promised to another player, so an unvested
        // starting balance cannot fund them.
        if (credits > 0L) {
            LedgerResult escrowed = store.execute(LedgerPlan.of(sender, SystemAccountId.ESCROW, credits,
                    TransactionType.MARKET_ESCROW));
            if (!escrowed.ok()) return OfferOutcome.refused(reason(escrowed));
        }
        // Then the goods, which were all just seen to be there. Should one not be after all,
        // everything taken so far and the credits go straight back.
        List<Given> taken = new ArrayList<>(given.size());
        for (Given part : given) {
            if (part.origin().take(part.lot())) {
                taken.add(part);
                continue;
            }
            restore(taken);
            if (credits > 0L) store.execute(LedgerPlan.of(SystemAccountId.ESCROW, sender, credits,
                    TransactionType.ESCROW_REFUND));
            return OfferOutcome.refused(OfferResult.NOT_HOLDING);
        }
        long now = store.ledger().now();
        long duration = Math.max(1L, env.settings().offerDurationMillis());
        UUID id = presetId != null ? presetId : freshId();
        Offer offer = new Offer(id, sender, recipient, lots, credits, askCount > 0 ? askItem : "", askCount, askCredits,
                now, Long.MAX_VALUE - now < duration ? Long.MAX_VALUE : now + duration);
        put(offer);
        setDirty();
        env.alerts().raise(recipient, EconomyAlerts.Kind.OFFER_RECEIVED);
        return OfferOutcome.of(offer);
    }

    private static void restore(List<Given> taken) {
        for (Given part : taken) part.origin().restore(part.lot());
    }

    private static OfferResult reason(LedgerResult result) {
        return switch (result) {
            case FROZEN -> OfferResult.FROZEN;
            case FUNDS_LOCKED -> OfferResult.FUNDS_LOCKED;
            case INSUFFICIENT_FUNDS -> OfferResult.INSUFFICIENT_FUNDS;
            default -> OfferResult.INTERNAL;
        };
    }

    // --- accepting ---------------------------------------------------------------------------

    /**
     * Accepts an offer as its recipient: one atomic swap, or nothing at all.
     *
     * <p>Every refusal is decided before anything moves: the goods are checked first and moved
     * last, and the credits are one ledger plan that applies in full or not at all.
     */
    public synchronized OfferOutcome accept(TradeEnv env, UUID id, AccountId actor) {
        EconomyStore store = owner();
        if (EconomyHealth.frozen()) return OfferOutcome.refused(OfferResult.FROZEN);
        // Sending and accepting trade; declining, cancelling and expiry are refunds and still work.
        if (marketClosed()) return OfferOutcome.refused(OfferResult.MARKET_CLOSED);
        if (store == null || env == null) return OfferOutcome.refused(OfferResult.INVALID);
        Offer offer = id == null ? null : offers.get(id);
        if (offer == null || actor == null || !offer.recipient().equals(actor) || offer.expiresAt() <= store.ledger().now())
            return OfferOutcome.refused(OfferResult.NOT_FOUND);
        AccountId sender = offer.sender();
        AccountId recipient = offer.recipient();
        VaultSection vault = store.vault();
        int cap = env.settings().vaultTradeCap();
        Lot asked = offer.asksItems() ? Lot.of(offer.askItem(), offer.askCount()) : null;
        if (asked != null && vault.total(recipient, asked.item(), Lot.PRISTINE) < asked.count())
            return OfferOutcome.refused(OfferResult.MISSING_ITEMS);
        if (!offer.lots().isEmpty()) {
            // The recipient's vault as it will be once the asked items have left it.
            List<Lot> after = asked == null ? vault.stacks(recipient)
                    : Lots.take(vault.stacks(recipient), asked.item(), Lot.PRISTINE, asked.count()).stacks();
            if (after.size() > cap || Lots.merge(after, offer.lots(), env.limit()).size() > cap)
                return OfferOutcome.refused(OfferResult.VAULT_FULL);
        }
        if (asked != null && !vault.canAccept(sender, List.of(asked), cap, env.limit()))
            return OfferOutcome.refused(OfferResult.OTHER_VAULT_FULL);
        double tax = env.settings().tradeTaxPercent();
        long taxOnGiven = Fees.fee(offer.credits(), tax);
        long taxOnAsked = Fees.fee(offer.askCredits(), tax);
        long recipientGets = offer.credits() - taxOnGiven;
        long senderGets = offer.askCredits() - taxOnAsked;
        UUID senderId = Accounts.player(sender);
        if (senderGets > 0L && (senderId == null || !store.market().canAcceptCreditClaim(senderId, senderGets)))
            return OfferOutcome.refused(OfferResult.PAYOUT_BLOCKED);
        LedgerPlan.Builder plan = LedgerPlan.builder();
        // What the recipient pays goes into escrow, where the sender's share waits to be collected.
        // It is paid to another player, so an unvested starting balance cannot fund it.
        if (offer.askCredits() > 0L) plan.move(recipient, SystemAccountId.ESCROW, offer.askCredits(),
                TransactionType.PLAYER_PAYMENT);
        if (taxOnAsked > 0L) plan.move(SystemAccountId.ESCROW, SystemAccountId.RESERVE, taxOnAsked, TransactionType.TRADE_TAX);
        if (taxOnGiven > 0L) plan.move(SystemAccountId.ESCROW, SystemAccountId.RESERVE, taxOnGiven, TransactionType.TRADE_TAX);
        if (recipientGets > 0L) plan.move(SystemAccountId.ESCROW, recipient, recipientGets, TransactionType.PLAYER_PAYMENT);
        LedgerResult settled = store.execute(plan.build());
        if (!settled.ok()) return OfferOutcome.refused(reason(settled));
        // Nothing below can refuse: the vault was seen to hold the asked items a moment ago, in
        // this same call. Should the take fail all the same, the items are not delivered to the
        // sender either, so goods can never exist twice.
        remove(offer);
        boolean took = asked == null || vault.take(recipient, asked);
        if (!took) NeroEconomyCommon.LOGGER.warn("[NeroEconomy] An accepted offer's asked items could not be "
                + "taken from the vault they were just counted in; they were not delivered.");
        if (senderGets > 0L && !store.market().addCreditClaim(senderId, senderGets, TransactionType.PLAYER_PAYMENT)) {
            NeroEconomyCommon.LOGGER.warn("[NeroEconomy] A credit claim could not be recorded; the amount was "
                    + "returned to the Reserve.");
            store.execute(LedgerPlan.of(SystemAccountId.ESCROW, SystemAccountId.RESERVE, senderGets,
                    TransactionType.ESCROW_REFUND));
        }
        if (asked != null && took) VaultGoods.deliver(store, env, sender, asked);
        for (Lot lot : offer.lots()) VaultGoods.deliver(store, env, recipient, lot);
        setDirty();
        env.alerts().raise(sender, EconomyAlerts.Kind.OFFER_ACCEPTED);
        // The sender's milestone: an offer of theirs was accepted.
        if (senderId != null) store.milestones().earn(senderId,
                za.co.neroland.neroeconomy.sibling.MilestoneSection.Milestone.FIRST_OFFER_ACCEPTED);
        return new OfferOutcome(OfferResult.OK, offer, recipientGets, offer.askCredits(), senderGets,
                taxOnGiven + taxOnAsked, false);
    }

    // --- declining, cancelling and expiry ----------------------------------------------------

    /** Declines an offer as its recipient: everything in it goes back to the sender. */
    public synchronized OfferOutcome decline(TradeEnv env, UUID id, AccountId actor) {
        Offer offer = id == null ? null : offers.get(id);
        if (EconomyHealth.frozen()) return OfferOutcome.refused(OfferResult.FROZEN);
        if (owner() == null || env == null) return OfferOutcome.refused(OfferResult.INVALID);
        if (offer == null || actor == null || !offer.recipient().equals(actor)) return OfferOutcome.refused(OfferResult.NOT_FOUND);
        return giveBack(env, offer);
    }

    /**
     * Cancels an offer as its sender: everything in it comes back to them. A sender can always
     * take an offer back: like a decline and an expiry this is a refund, and a refund always
     * books, even into a vault that is already past its cap.
     */
    public synchronized OfferOutcome cancel(TradeEnv env, UUID id, AccountId actor) {
        Offer offer = id == null ? null : offers.get(id);
        if (EconomyHealth.frozen()) return OfferOutcome.refused(OfferResult.FROZEN);
        if (owner() == null || env == null) return OfferOutcome.refused(OfferResult.INVALID);
        if (offer == null || actor == null || !offer.sender().equals(actor)) return OfferOutcome.refused(OfferResult.NOT_FOUND);
        return giveBack(env, offer);
    }

    /**
     * Returns every offer past its expiry to its sender. Nothing lapses while the economy is
     * frozen. @return how many offers were returned
     */
    public synchronized int expire(TradeEnv env, long now) {
        if (owner() == null || env == null || EconomyHealth.frozen()) return 0;
        int returned = 0;
        for (Offer offer : new ArrayList<>(offers.values())) {
            if (offer.expiresAt() > now || !giveBack(env, offer).ok()) continue;
            env.alerts().raise(offer.sender(), EconomyAlerts.Kind.OFFER_EXPIRED);
            returned++;
        }
        return returned;
    }

    /**
     * Closes an offer and returns what it held to the sender: the credits to their balance (or,
     * if the ledger cannot pay them out just now, to their credit claim) and the lots to their
     * vault, which always books. The offer stays exactly as it is when the credits cannot be
     * booked either way.
     */
    private OfferOutcome giveBack(TradeEnv env, Offer offer) {
        EconomyStore store = owner();
        boolean toClaim = false;
        if (offer.credits() > 0L && !store.execute(LedgerPlan.of(SystemAccountId.ESCROW, offer.sender(), offer.credits(),
                TransactionType.ESCROW_REFUND)).ok()) {
            UUID sender = Accounts.player(offer.sender());
            if (sender == null || !store.market().addCreditClaim(sender, offer.credits(), TransactionType.ESCROW_REFUND))
                return OfferOutcome.refused(OfferResult.PAYOUT_BLOCKED);
            toClaim = true;
        }
        remove(offer);
        for (Lot lot : offer.lots()) VaultGoods.deliver(store, env, offer.sender(), lot);
        setDirty();
        return new OfferOutcome(OfferResult.OK, offer, 0L, 0L, 0L, 0L, toClaim);
    }

    // --- erasure and export ------------------------------------------------------------------

    /**
     * Erases one player. Offers they sent go with them: the goods are destroyed and the credits
     * move to the Reserve as an erasure sweep. Offers sent <em>to</em> them are somebody else's
     * property and go back to their senders — lots to the vault, credits to the credit claim,
     * which needs no ledger operation and so works while the economy is frozen. Their mute flag
     * is dropped.
     *
     * @return the goods that were destroyed, so a caller that counts goods can account for them
     */
    public synchronized List<Lot> forgetPlayer(TradeEnv env, UUID player) {
        if (player == null) return List.of();
        EconomyStore store = owner();
        AccountId account = new PlayerAccountId(player);
        List<Lot> destroyed = new ArrayList<>();
        long swept = 0L;
        boolean changed = muted.remove(account);
        for (Offer offer : new ArrayList<>(offers.values())) {
            if (offer.sender().equals(account)) {
                remove(offer);
                destroyed.addAll(offer.lots());
                swept = EscrowMath.total(swept, offer.credits());
                changed = true;
            } else if (offer.recipient().equals(account)) {
                remove(offer);
                changed = true;
                UUID sender = Accounts.player(offer.sender());
                boolean booked = offer.credits() <= 0L || (store != null && sender != null
                        && store.market().addCreditClaim(sender, offer.credits(), TransactionType.ESCROW_REFUND));
                if (store != null && env != null && booked) {
                    for (Lot lot : offer.lots()) VaultGoods.deliver(store, env, offer.sender(), lot);
                } else {
                    // What cannot be returned now is kept for the sender under their own name, which
                    // stores nothing of the erased player; the next expiry sweep returns it.
                    put(new Offer(offer.id(), offer.sender(), offer.sender(), offer.lots(), offer.credits(), "", 0, 0L,
                            offer.createdAt(), 1L));
                }
            }
        }
        EconomySavedData ledger = ledger();
        if (ledger != null && swept > 0L) ledger.sweepErasedEscrow(swept);
        if (changed) setDirty();
        return destroyed;
    }

    /**
     * Data-subject export: the offers the player sent and the offers waiting for them, and their
     * mute flag. Items are id and count only; the other party is never named.
     */
    public synchronized JsonObject exportFor(UUID player) {
        JsonObject out = new JsonObject();
        AccountId account = Accounts.of(player);
        out.addProperty("offers_muted", muted.contains(account));
        JsonArray sent = new JsonArray();
        JsonArray received = new JsonArray();
        for (Offer offer : offers.values()) {
            if (offer.sender().equals(account)) sent.add(row(offer));
            else if (offer.recipient().equals(account)) received.add(row(offer));
        }
        out.add("sent", sent);
        out.add("received", received);
        return out;
    }

    private static JsonObject row(Offer offer) {
        JsonObject row = new JsonObject();
        row.addProperty("id", offer.id().toString());
        JsonArray lots = new JsonArray();
        for (Lot lot : offer.lots()) {
            JsonObject item = new JsonObject();
            item.addProperty("item", lot.item());
            item.addProperty("count", lot.count());
            lots.add(item);
        }
        row.add("items", lots);
        row.addProperty("credits", offer.credits());
        if (offer.asksItems()) {
            row.addProperty("ask_item", offer.askItem());
            row.addProperty("ask_count", offer.askCount());
        }
        row.addProperty("ask_credits", offer.askCredits());
        row.addProperty("created_at", offer.createdAt());
        row.addProperty("expires_at", offer.expiresAt());
        return row;
    }

    // --- internals ---------------------------------------------------------------------------

    private UUID freshId() {
        UUID id;
        do id = UUID.randomUUID(); while (offers.containsKey(id));
        return id;
    }

    private void put(Offer offer) {
        offers.put(offer.id(), offer);
        outgoing.merge(offer.sender(), 1, Integer::sum);
        incoming.merge(offer.recipient(), 1, Integer::sum);
    }

    private void remove(Offer offer) {
        if (offers.remove(offer.id()) == null) return;
        outgoing.computeIfPresent(offer.sender(), (account, count) -> count <= 1 ? null : count - 1);
        incoming.computeIfPresent(offer.recipient(), (account, count) -> count <= 1 ? null : count - 1);
    }

    // --- persistence -------------------------------------------------------------------------

    private record LotRow(String item, int count, int payload) {
        static final Codec<LotRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("item").forGetter(LotRow::item),
                Codec.INT.fieldOf("count").forGetter(LotRow::count),
                Codec.INT.optionalFieldOf("payload", Lot.PRISTINE).forGetter(LotRow::payload))
                .apply(instance, LotRow::new));
    }

    private record OfferRow(String id, String sender, String recipient, List<LotRow> lots, long credits, String askItem,
            int askCount, long askCredits, long created, long expires) {
        static final Codec<OfferRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(OfferRow::id),
                Codec.STRING.fieldOf("sender").forGetter(OfferRow::sender),
                Codec.STRING.fieldOf("recipient").forGetter(OfferRow::recipient),
                LotRow.CODEC.listOf().optionalFieldOf("lots", List.of()).forGetter(OfferRow::lots),
                Codec.LONG.optionalFieldOf("credits", 0L).forGetter(OfferRow::credits),
                Codec.STRING.optionalFieldOf("ask_item", "").forGetter(OfferRow::askItem),
                Codec.INT.optionalFieldOf("ask_count", 0).forGetter(OfferRow::askCount),
                Codec.LONG.optionalFieldOf("ask_credits", 0L).forGetter(OfferRow::askCredits),
                Codec.LONG.optionalFieldOf("created", 0L).forGetter(OfferRow::created),
                Codec.LONG.fieldOf("expires").forGetter(OfferRow::expires))
                .apply(instance, OfferRow::new));
    }

    private static Codec<OffersSection> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                OfferRow.CODEC.listOf().optionalFieldOf("offers", List.of()).forGetter(OffersSection::offerRows),
                Codec.STRING.listOf().optionalFieldOf("muted", List.of()).forGetter(OffersSection::mutedRows))
                .apply(instance, OffersSection::fromRows));
    }

    private synchronized List<OfferRow> offerRows() {
        List<OfferRow> rows = new ArrayList<>(offers.size());
        for (Offer offer : offers.values()) rows.add(new OfferRow(offer.id().toString(), offer.sender().stableKey(),
                offer.recipient().stableKey(), offer.lots().stream().map(lot -> new LotRow(lot.item(), lot.count(),
                        lot.payload())).toList(), offer.credits(), offer.askItem(), offer.askCount(),
                offer.askCredits(), offer.createdAt(), offer.expiresAt()));
        return rows;
    }

    private synchronized List<String> mutedRows() { return muted.stream().map(AccountId::stableKey).toList(); }

    private static OffersSection fromRows(List<OfferRow> rows, List<String> mutedRows) {
        OffersSection section = new OffersSection();
        // A row that is not an offer is skipped; every other offer still loads.
        for (OfferRow row : rows) try {
            AccountId sender = Accounts.parse(row.sender());
            AccountId recipient = Accounts.parse(row.recipient());
            UUID id = UUID.fromString(row.id());
            if (sender == null || recipient == null || section.offers.containsKey(id)) continue;
            List<Lot> lots = new ArrayList<>(row.lots().size());
            for (LotRow lot : row.lots())
                if (Lot.valid(lot.item(), lot.count(), lot.payload())) lots.add(new Lot(lot.item(), lot.count(), lot.payload()));
            boolean asks = row.askCount() > 0 && !row.askItem().isBlank();
            section.put(new Offer(id, sender, recipient, lots, Math.max(0L, row.credits()), asks ? row.askItem() : "",
                    asks ? row.askCount() : 0, Math.max(0L, row.askCredits()), row.created(), row.expires()));
        } catch (IllegalArgumentException ignored) { }
        for (String key : mutedRows) {
            AccountId account = Accounts.parse(key);
            if (account != null) section.muted.add(account);
        }
        return section;
    }
}
