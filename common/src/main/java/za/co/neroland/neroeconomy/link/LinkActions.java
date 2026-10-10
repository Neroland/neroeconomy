package za.co.neroland.neroeconomy.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.nerolandcore.link.LinkActionResult;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.book.BookSection;
import za.co.neroland.neroeconomy.book.BookStacks;
import za.co.neroland.neroeconomy.book.Placed;
import za.co.neroland.neroeconomy.commodity.CommodityData;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.KnownAccounts;
import za.co.neroland.neroeconomy.exchange.BondOutcome;
import za.co.neroland.neroeconomy.exchange.BondTerm;
import za.co.neroland.neroeconomy.exchange.ExchangeDesk;
import za.co.neroland.neroeconomy.exchange.Investing;
import za.co.neroland.neroeconomy.exchange.Quotes;
import za.co.neroland.neroeconomy.exchange.TradeQuote;
import za.co.neroland.neroeconomy.exchange.TradeResult;
import za.co.neroland.neroeconomy.goods.GoodsSource;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.market.CancelResult;
import za.co.neroland.neroeconomy.market.FillOutcome;
import za.co.neroland.neroeconomy.market.ListingSource;
import za.co.neroland.neroeconomy.market.MarketBuyOffer;
import za.co.neroland.neroeconomy.market.MarketListing;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.market.PostResult;
import za.co.neroland.neroeconomy.market.Posted;
import za.co.neroland.neroeconomy.market.PurchaseOutcome;
import za.co.neroland.neroeconomy.market.PurchaseResult;
import za.co.neroland.neroeconomy.menu.BankTransferRules;
import za.co.neroland.neroeconomy.offers.Offer;
import za.co.neroland.neroeconomy.offers.OfferOutcome;
import za.co.neroland.neroeconomy.offers.OfferResult;
import za.co.neroland.neroeconomy.offers.OfferStacks;
import za.co.neroland.neroeconomy.offers.OffersSection;
import za.co.neroland.neroeconomy.rules.RuleOutcome;
import za.co.neroland.neroeconomy.rules.RuleSide;
import za.co.neroland.neroeconomy.rules.RuleStacks;
import za.co.neroland.neroeconomy.rules.RulesSection;
import za.co.neroland.neroeconomy.rules.TradeRule;
import za.co.neroland.neroeconomy.trade.TradeEnv;
import za.co.neroland.neroeconomy.vault.VaultStacks;

/**
 * Runs one companion-app action against the economy's own entry points, which check everything
 * again: the frozen economy, the operator market switch, the shape of each number, ownership, the
 * creative-mode goods guard, and the shared request ledger, so a replayed request acts once.
 *
 * <p>The module has already checked {@code remoteTradeEnabled}, the frozen economy, the player's
 * shared {@code VALUE} budget, the per-action limit, the presence of a {@code requestId} and, for
 * the two actions that touch a live inventory, that the player is in game. What is left here is
 * each action's own rule, the operator market switch for the actions that trade, and the daily
 * remote spending limit: an action that can spend credits states the most it could spend before
 * it runs and is refused when that would pass the limit; afterwards what it really spent is
 * counted. Goods that leave the account (an offer's goods, a sell order or sell rule from the vault,
 * resuming a sell rule, an accepted offer that asks for goods) are valued
 * ({@link RemoteGoodsValue}) and counted the same way against the daily remote goods limit.
 *
 * <p>A reply never names another player. A recipient the player typed is used to find the
 * account and is not echoed back.
 */
final class LinkActions {
    static final String TRADE = "trade";
    static final String ORDER_PLACE = "order_place";
    static final String ORDER_CANCEL = "order_cancel";
    static final String RULE_SET = "rule_set";
    static final String RULE_DELETE = "rule_delete";
    static final String RULES_PAUSE = "rules_pause";
    static final String STOCK_BUY = "stock_buy";
    static final String STOCK_SELL = "stock_sell";
    static final String BOND_BUY = "bond_buy";
    static final String PAY = "pay";
    static final String OFFER_SEND = "offer_send";
    static final String OFFER_ACCEPT = "offer_accept";
    static final String OFFER_DECLINE = "offer_decline";
    static final String CLAIM_COLLECT = "claim_collect";
    static final String VAULT_DEPOSIT = "vault_deposit";
    static final String VAULT_WITHDRAW = "vault_withdraw";
    static final String OFFER_CANCEL = "offer_cancel";

    /** Every action id, in the order discovery lists them; one added later goes at the end. */
    static final List<String> ALL = List.of(TRADE, ORDER_PLACE, ORDER_CANCEL, RULE_SET, RULE_DELETE, RULES_PAUSE,
            STOCK_BUY, STOCK_SELL, BOND_BUY, PAY, OFFER_SEND, OFFER_ACCEPT, OFFER_DECLINE, CLAIM_COLLECT, VAULT_DEPOSIT,
            VAULT_WITHDRAW, OFFER_CANCEL);
    /** The actions the design table marks as working while the player is away: all but the two vault moves. */
    static final Set<String> OFFLINE = Set.of(TRADE, ORDER_PLACE, ORDER_CANCEL, RULE_SET, RULE_DELETE, RULES_PAUSE,
            STOCK_BUY, STOCK_SELL, BOND_BUY, PAY, OFFER_SEND, OFFER_ACCEPT, OFFER_DECLINE, CLAIM_COLLECT, OFFER_CANCEL);
    /**
     * The actions an operator's market switch stops. Taking back an order or an offer, deleting or
     * pausing a rule, collecting and the vault keep working, as in game.
     */
    static final Set<String> TRADING = Set.of(TRADE, ORDER_PLACE, RULE_SET, STOCK_BUY, STOCK_SELL, BOND_BUY,
            OFFER_SEND, OFFER_ACCEPT);
    /** The actions that never reach a store that spends the request id itself, so it is spent here. */
    static final Set<String> RECORDS_OWN_REQUEST = Set.of(ORDER_CANCEL, RULE_DELETE, RULES_PAUSE, OFFER_DECLINE,
            CLAIM_COLLECT, VAULT_DEPOSIT, VAULT_WITHDRAW, OFFER_CANCEL);

    /**
     * What one action works with. {@code online} is null for a player who is away. {@code env} is
     * what the store's trading rules are given ({@code Trading.env(store)} in game); {@code players}
     * answers who else is in game and whose account a typed recipient names.
     */
    record Call(MinecraftServer server, EconomyStore store, TradeEnv env, Players players, UUID player,
            ServerPlayer online, JsonObject params, UUID request, Function<UUID, JsonObject> balance) {
        AccountId account() { return new PlayerAccountId(player); }
    }

    /** What an action needs to know about players besides the one acting. */
    interface Players {
        /** The player when in game; null when away. */
        ServerPlayer online(UUID player);

        /** The account a typed name or UUID names, found as {@code /eco pay} finds it; null when none. */
        UUID resolve(String recipient);

        /** The running server's player list and saved player data. */
        static Players of(MinecraftServer server) {
            return new Players() {
                @Override public ServerPlayer online(UUID player) {
                    return player == null ? null : server.getPlayerList().getPlayer(player);
                }

                @Override public UUID resolve(String recipient) { return KnownAccounts.resolve(server, recipient); }
            };
        }
    }

    private LinkActions() { }

    /** Runs {@code action}, which is one of {@link #ALL}. */
    static LinkActionResult run(String action, Call call) {
        // A request id these actions' stores do not record is recorded here, before anything moves.
        if (RECORDS_OWN_REQUEST.contains(action) && !call.store().market().rememberRequest(call.player(), call.request()))
            return invalid(LinkPayloads.DUPLICATE_REQUEST);
        return switch (action) {
            case TRADE -> trade(call);
            case ORDER_PLACE -> orderPlace(call);
            case ORDER_CANCEL -> orderCancel(call);
            case RULE_SET -> ruleSet(call);
            case RULE_DELETE -> ruleDelete(call);
            case RULES_PAUSE -> rulesPause(call);
            case STOCK_BUY -> stockBuy(call);
            case STOCK_SELL -> stockSell(call);
            case BOND_BUY -> bondBuy(call);
            case PAY -> pay(call);
            case OFFER_SEND -> offerSend(call);
            case OFFER_ACCEPT -> offerAccept(call);
            case OFFER_DECLINE -> offerDecline(call);
            case OFFER_CANCEL -> offerCancel(call);
            case CLAIM_COLLECT -> claimCollect(call);
            case VAULT_DEPOSIT -> vaultDeposit(call);
            case VAULT_WITHDRAW -> vaultWithdraw(call);
            default -> invalid(LinkText.get("link.neroeconomy.unknown_action"));
        };
    }

    // --- shared rules ------------------------------------------------------------------------

    static LinkActionResult invalid(String message) {
        return LinkActionResult.error(LinkActionResult.Error.VALIDATION, message);
    }

    private static LinkActionResult badNumber(String key, long min, long max) {
        return invalid(LinkText.get("link.neroeconomy.bad_number", key, min, max));
    }

    private static LinkActionResult badText(String key) { return invalid(LinkText.get("link.neroeconomy.bad_text", key)); }

    /** A refusal reason from the game's lang, made one sentence; a few app-specific rewordings first. */
    static String reason(String key) {
        String own = switch (key) {
            case "message.neroeconomy.frozen" -> LinkPayloads.ECONOMY_FROZEN;
            case "message.neroeconomy.market_closed" -> LinkPayloads.MARKET_CLOSED;
            case "message.neroeconomy.offer.vault_full", "command.neroeconomy.cancel.claims_full" ->
                    LinkText.get("link.neroeconomy.offer_vault_full");
            case "message.neroeconomy.invest.unknown_stock" -> LinkText.get("link.neroeconomy.unknown_stock");
            case "message.neroeconomy.order.stale_request", "message.neroeconomy.offer.stale_request",
                    "message.neroeconomy.rule.stale_request", "message.neroeconomy.invest.stale_request" ->
                    LinkPayloads.DUPLICATE_REQUEST;
            default -> null;
        };
        return own != null ? own : LinkText.sentence(key);
    }

    private static LinkActionResult refused(String key) { return invalid(reason(key)); }

    /**
     * Null when spending up to {@code bound} more today stays within {@code remoteDailySpendLimit};
     * otherwise the refusal, with the limit and what is left of it.
     */
    static LinkActionResult overLimit(Call call, long bound) {
        long limit = EconomyConfig.REMOTE_DAILY_SPEND_LIMIT.get();
        long day = EconomySavedData.economyDay(LinkReads.now());
        RemoteSpendSection spend = call.store().remoteSpend();
        if (spend.allows(call.player(), day, Math.max(0L, bound), limit)) return null;
        if (spend.spentToday(call.player(), day) == 0L && spend.size() >= RemoteSpendSection.MAX_ACCOUNTS)
            return invalid(LinkText.get("link.neroeconomy.remote_table_full"));
        return invalid(LinkText.get("link.neroeconomy.remote_limit", limit,
                Math.max(0L, limit - spend.spentToday(call.player(), day))));
    }

    /** Counts what an action really spent. */
    static void spent(Call call, long amount) {
        if (amount > 0L)
            call.store().remoteSpend().record(call.player(), EconomySavedData.economyDay(LinkReads.now()), amount);
    }

    /** How goods leaving the player's account through the app are valued. */
    static RemoteGoodsValue.Pricing goodsPricing(Call call) {
        return new RemoteGoodsValue.Pricing(CommodityData.forServer(call.server()), call.store().houses()::referenceMillis,
                EconomyConfig.REMOTE_GOODS_FLOOR_VALUE.get(), EconomyConfig.REMOTE_MODIFIED_GOODS_VALUE.get());
    }

    /**
     * Null when goods worth {@code value} more may leave the account through the app today within
     * {@code remoteDailyGoodsLimit}; otherwise the refusal, with the value, the limit and what is left of it.
     */
    static LinkActionResult overGoodsLimit(Call call, long value) {
        long limit = EconomyConfig.REMOTE_DAILY_GOODS_LIMIT.get();
        long day = EconomySavedData.economyDay(LinkReads.now());
        RemoteSpendSection spend = call.store().remoteSpend();
        if (spend.allowsGoods(call.player(), day, Math.max(0L, value), limit)) return null;
        if (spend.spentToday(call.player(), day) == 0L && spend.goodsToday(call.player(), day) == 0L
                && spend.size() >= RemoteSpendSection.MAX_ACCOUNTS)
            return invalid(LinkText.get("link.neroeconomy.remote_table_full"));
        return invalid(LinkText.get("link.neroeconomy.remote_goods_limit", value, limit,
                Math.max(0L, limit - spend.goodsToday(call.player(), day))));
    }

    /** Counts goods worth {@code value} that left the account through an action that ran. */
    static void goodsSent(Call call, long value) {
        if (value > 0L)
            call.store().remoteSpend().recordGoods(call.player(), EconomySavedData.economyDay(LinkReads.now()), value);
    }

    /**
     * What one unit a sell rule may sell counts as: its item's value, or for a tag, the dearest
     * goods in the player's vault (the only goods a vault-backed rule can sell), never below the floor.
     */
    private static long ruleUnitValue(Call call, RemoteGoodsValue.Pricing pricing, boolean tag, String target) {
        if (!tag) return RemoteGoodsValue.unitValue(pricing, target, Lot.PRISTINE);
        long unit = pricing.floor();
        for (Lot lot : call.store().vault().stacks(call.player()))
            unit = Math.max(unit, RemoteGoodsValue.unitValue(pricing, lot.item(), lot.payload()));
        return unit;
    }

    private static LinkViews.Remote remote(Call call) { return LinkReads.remote(call.store(), call.player()); }

    private static LinkActionResult ok(Call call, String action, String message, JsonObject detail, boolean spends) {
        return LinkActionResult.ok(LinkViews.actionResult(call.balance().apply(call.player()), action, message, detail,
                spends ? remote(call) : null));
    }

    static long times(long unit, long quantity) {
        try {
            return Math.multiplyExact(unit, quantity);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    static long plus(long a, long b) { return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b; }

    /** A whole-number parameter in range; {@code fallback} when absent; null when present and not valid. */
    static Long number(JsonObject params, String key, long min, long max, Long fallback) {
        JsonElement value = params.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        long whole = LinkPayloads.wholeNumber(params, key, -1L);
        return whole >= min && whole <= max && whole >= 0L ? whole : null;
    }

    static Boolean flag(JsonObject params, String key, boolean fallback) {
        JsonElement value = params.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) return null;
        return value.getAsBoolean();
    }

    /** A short text parameter: trimmed, at most {@code max} characters, never empty; null otherwise. */
    static String text(JsonObject params, String key, int max) {
        String value = LinkPayloads.text(params, key).strip();
        return value.isEmpty() || value.length() > max ? null : value;
    }

    // --- trade (schema 1) --------------------------------------------------------------------

    private static LinkActionResult trade(Call call) {
        int quantity = LinkPayloads.quantity(call.params());
        if (quantity < LinkPayloads.MIN_QUANTITY) return invalid(LinkPayloads.QUANTITY_OUT_OF_RANGE);
        return switch (LinkPayloads.text(call.params(), "op")) {
            case "buy" -> buy(call, quantity);
            case "sell" -> sell(call, quantity);
            default -> invalid(LinkPayloads.UNKNOWN_OPERATION);
        };
    }

    /** Buys from a sell listing; the store consumes the request id and re-prices at execution. */
    private static LinkActionResult buy(Call call, int quantity) {
        MarketSavedData market = call.store().market();
        UUID listingId = LinkPayloads.uuid(call.params(), "listingId");
        if (listingId == null) return invalid(LinkPayloads.LISTING_ID_REQUIRED);
        MarketListing listing = market.listing(listingId);
        if (listing == null) return invalid(market.buyOffer(listingId) != null ? LinkPayloads.BUY_NEEDS_LISTING
                : LinkPayloads.purchaseMessage(PurchaseResult.NOT_FOUND));
        // The quoted price is a ceiling. With none sent, the ceiling is the server's own quote now.
        long quoted = LinkPayloads.wholeNumber(call.params(), "quotedUnitPrice", 0L);
        long ceiling = quoted >= 1L ? quoted : MarketSavedData.buyerUnitPrice(listing, call.player());
        LinkActionResult limited = overLimit(call, times(ceiling, quantity));
        if (limited != null) return limited;
        PurchaseOutcome outcome = call.online() != null
                ? market.purchaseOutcome(call.online(), listingId, quantity, ceiling, call.request(), null)
                : market.purchaseToClaimOutcome(call.server(), call.player(), listingId, quantity, ceiling, call.request());
        if (!outcome.success()) return invalid(LinkPayloads.purchaseMessage(outcome.result()));
        // What the buyer was actually charged, as the ledger settled it rather than as it was quoted.
        long total = outcome.paid();
        spent(call, total);
        JsonObject result = LinkPayloads.tradeResult(call.balance().apply(call.player()), new LinkPayloads.Trade("buy",
                listingId.toString(), quantity, total / quantity, total, true, outcome.toVault() ? "claim" : "inventory"));
        result.add("remote", LinkViews.remote(remote(call)));
        return LinkActionResult.ok(result);
    }

    /**
     * With {@code unitPrice}: posts the main-hand stack as a new listing (schema 1). Without it:
     * fills the standing buy offer {@code listingId} names from the main hand.
     */
    private static LinkActionResult sell(Call call, int quantity) {
        MarketSavedData market = call.store().market();
        long asking = LinkPayloads.wholeNumber(call.params(), "unitPrice", 0L);
        if (asking >= 1L) {
            if (call.online() == null) return offline(LinkPayloads.SELL_NEEDS_PLAYER);
            LinkActionResult limited = overLimit(call, EconomyConfig.LISTING_FEE.get());
            if (limited != null) return limited;
            long goodsValue = handValue(call, quantity);
            LinkActionResult goodsLimited = overGoodsLimit(call, goodsValue);
            if (goodsLimited != null) return goodsLimited;
            long expires = LinkReads.now() + EconomyConfig.DEFAULT_LISTING_DURATION_HOURS.get() * 3_600_000L;
            Posted<MarketListing> posted = market.postFromHandChecked(call.online(), quantity, asking, expires,
                    ListingSource.MARKET_BOARD, "link", call.request());
            if (posted.result() != PostResult.SUCCESS) return invalid(LinkPayloads.postMessage(posted.result()));
            spent(call, posted.feeCharged());
            goodsSent(call, goodsValue);
            return LinkActionResult.ok(LinkPayloads.tradeResult(call.balance().apply(call.player()),
                    new LinkPayloads.Trade("sell", posted.row().listingId().toString(), quantity, asking,
                            times(asking, quantity), false, "listed", posted.feeCharged(), 0L)));
        }
        // No id, a sell listing or an unknown id: there is nothing here the player's items could fill.
        UUID offerId = LinkPayloads.uuid(call.params(), "listingId");
        if (offerId == null) return invalid(LinkPayloads.SELL_NEEDS_BUY_OFFER);
        MarketBuyOffer offer = market.buyOffer(offerId);
        if (offer == null) return invalid(LinkPayloads.SELL_NEEDS_BUY_OFFER);
        if (call.online() == null) return offline(LinkPayloads.SELL_NEEDS_PLAYER);
        long goodsValue = handValue(call, quantity);
        LinkActionResult goodsLimited = overGoodsLimit(call, goodsValue);
        if (goodsLimited != null) return goodsLimited;
        FillOutcome filled = market.fillBuyOfferOutcome(call.online(), offerId, quantity, call.request());
        if (!filled.success()) return invalid(LinkPayloads.fillMessage(filled.result()));
        goodsSent(call, goodsValue);
        // The proceeds, less the market fee, are booked to the seller's credit claim.
        return LinkActionResult.ok(LinkPayloads.tradeResult(call.balance().apply(call.player()),
                new LinkPayloads.Trade("sell", offerId.toString(), quantity, offer.unitPrice(), filled.gross(), true,
                        "credit_claim", filled.fee(), filled.net())));
    }

    /** What up to {@code quantity} of the in-game player's main-hand stack counts as when it leaves. */
    private static long handValue(Call call, int quantity) {
        ItemStack held = call.online().getMainHandItem();
        if (held.isEmpty()) return 0L;
        String item = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        // Any modified stack counts as modified goods; which payload it would get does not matter here.
        int payload = MarketSavedData.pristine(held) ? Lot.PRISTINE : 1;
        return RemoteGoodsValue.of(goodsPricing(call), item, payload, Math.min(quantity, held.getCount()));
    }

    static LinkActionResult offline(String message) {
        return LinkActionResult.error(LinkActionResult.Error.PLAYER_OFFLINE_REQUIRED, message);
    }

    // --- the order book ----------------------------------------------------------------------

    /**
     * {@code side} buy or sell, {@code item}, {@code qty}, {@code unitPrice}; a sell also takes
     * {@code payload} (0, the default, for unmodified goods). A sell order's goods come from the
     * vault; goods a buy order gets go to the vault.
     */
    private static LinkActionResult orderPlace(Call call) {
        JsonObject params = call.params();
        String side = LinkPayloads.text(params, "side");
        if (!side.equals("buy") && !side.equals("sell")) return invalid(LinkText.get("link.neroeconomy.bad_side"));
        String item = text(params, "item", Lot.MAX_ITEM_LENGTH);
        if (item == null) return badText("item");
        Long quantity = number(params, "qty", 1L, BookSection.MAX_ORDER_QUANTITY, null);
        if (quantity == null) return badNumber("qty", 1L, BookSection.MAX_ORDER_QUANTITY);
        Long unit = number(params, "unitPrice", 1L, MAX_CREDITS, null);
        if (unit == null) return badNumber("unitPrice", 1L, MAX_CREDITS);
        long fee = EconomyConfig.LISTING_FEE.get();
        Placed placed;
        long goodsValue = 0L;
        if (side.equals("sell")) {
            Long payload = number(params, "payload", 0L, Integer.MAX_VALUE, 0L);
            if (payload == null) return badNumber("payload", 0L, Integer.MAX_VALUE);
            LinkActionResult limited = overLimit(call, fee);
            if (limited != null) return limited;
            // The goods leave the vault for the book, whatever the price: counted at what they are worth.
            goodsValue = RemoteGoodsValue.of(goodsPricing(call), item, payload.intValue(), quantity);
            LinkActionResult goodsLimited = overGoodsLimit(call, goodsValue);
            if (goodsLimited != null) return goodsLimited;
            placed = BookStacks.sellFromVault(call.store(), call.env(), call.player(), item, payload.intValue(),
                    quantity.intValue(), unit, call.request());
        } else {
            LinkActionResult limited = overLimit(call, plus(times(unit, quantity), fee));
            if (limited != null) return limited;
            // Goods bought from the app always go to the vault, whether or not the player is in game.
            placed = BookStacks.buyOrder(call.store(), call.env(), call.player(), null, item, quantity.intValue(), unit,
                    call.request());
        }
        if (!placed.ok()) return refused(placed.result().translationKey());
        spent(call, plus(placed.escrowed(), placed.listingFee()));
        goodsSent(call, goodsValue);
        JsonObject detail = LinkViews.orderPlaced(placed.orderId().toString(), side, item, quantity, unit,
                placed.filled(), placed.rested(), placed.listingFee(), placed.escrowed());
        return ok(call, ORDER_PLACE, LinkText.get(side.equals("sell") ? "link.neroeconomy.order_placed_sell"
                : "link.neroeconomy.order_placed_buy"), detail, true);
    }

    /** The most credits one action may name: far past any balance, short of any overflow. */
    static final long MAX_CREDITS = 1_000_000_000_000L;

    private static LinkActionResult orderCancel(Call call) {
        UUID orderId = LinkPayloads.uuid(call.params(), "orderId");
        if (orderId == null) return badText("orderId");
        CancelResult result = BookStacks.cancel(call.store(), call.env(), call.player(), orderId);
        if (result != CancelResult.CANCELLED) return refused(result.translationKey());
        JsonObject detail = LinkViews.idOnly("orderId", orderId.toString());
        return ok(call, ORDER_CANCEL, LinkText.get("link.neroeconomy.order_cancelled"), detail, false);
    }

    // --- rules -------------------------------------------------------------------------------

    /**
     * Creates a vault-backed rule: {@code side}, {@code target} (an item id, or {@code #tag}),
     * {@code limitPrice}, {@code perDay} and {@code total} (a buy rule's budget, escrowed now; a
     * sell rule's quantity).
     */
    private static LinkActionResult ruleSet(Call call) {
        JsonObject params = call.params();
        String side = LinkPayloads.text(params, "side");
        if (!side.equals("buy") && !side.equals("sell")) return invalid(LinkText.get("link.neroeconomy.bad_side"));
        String target = text(params, "target", Lot.MAX_ITEM_LENGTH);
        if (target == null) return badText("target");
        boolean tag = target.startsWith("#");
        if (tag) target = target.substring(1);
        if (target.isEmpty()) return badText("target");
        Long limitPrice = number(params, "limitPrice", 1L, MAX_CREDITS, null);
        if (limitPrice == null) return badNumber("limitPrice", 1L, MAX_CREDITS);
        Long perDay = number(params, "perDay", 1L, BookSection.MAX_ORDER_QUANTITY, null);
        if (perDay == null) return badNumber("perDay", 1L, BookSection.MAX_ORDER_QUANTITY);
        Long total = number(params, "total", 1L, MAX_CREDITS, null);
        if (total == null) return badNumber("total", 1L, MAX_CREDITS);
        RuleSide ruleSide = side.equals("buy") ? RuleSide.BUY : RuleSide.SELL;
        long goodsValue = 0L;
        if (ruleSide == RuleSide.BUY) {
            LinkActionResult limited = overLimit(call, total);
            if (limited != null) return limited;
        } else {
            // A sell rule may sell its whole quantity from the vault: counted now, at what it is worth.
            goodsValue = times(ruleUnitValue(call, goodsPricing(call), tag, target), total);
            LinkActionResult goodsLimited = overGoodsLimit(call, goodsValue);
            if (goodsLimited != null) return goodsLimited;
        }
        RuleOutcome added = RuleStacks.add(call.server(), call.player(), ruleSide, tag, target, limitPrice,
                perDay.intValue(), total, RulesSection.VAULT, call.request());
        if (!added.ok()) return refused(added.result().translationKey());
        spent(call, added.escrowed());
        goodsSent(call, goodsValue);
        JsonObject detail = LinkViews.ruleSet(LinkReads.rule(added.rule()), added.escrowed());
        return ok(call, RULE_SET, LinkText.get("link.neroeconomy.rule_added"), detail, true);
    }

    /** The player's own rule with that id, only when it works from their vault; null otherwise. */
    private static TradeRule vaultRule(Call call, UUID ruleId) {
        for (TradeRule rule : call.store().rules().rulesOf(call.account()))
            if (rule.id().equals(ruleId)) return RulesSection.VAULT.equals(rule.binding()) ? rule : null;
        return null;
    }

    private static boolean ownsRule(Call call, UUID ruleId) {
        for (TradeRule rule : call.store().rules().rulesOf(call.account())) if (rule.id().equals(ruleId)) return true;
        return false;
    }

    private static LinkActionResult ruleDelete(Call call) {
        UUID ruleId = LinkPayloads.uuid(call.params(), "ruleId");
        if (ruleId == null) return badText("ruleId");
        if (vaultRule(call, ruleId) == null) return ownsRule(call, ruleId)
                ? invalid(LinkText.get("link.neroeconomy.rule_not_vault")) : refused("message.neroeconomy.rule.not_found");
        RuleOutcome deleted = RuleStacks.delete(call.server(), call.player(), ruleId);
        if (!deleted.ok()) return refused(deleted.result().translationKey());
        JsonObject detail = LinkViews.ruleDeleted(ruleId.toString(), deleted.refunded(), deleted.refundedToClaim());
        return ok(call, RULE_DELETE, LinkText.get("link.neroeconomy.rule_deleted"), detail, false);
    }

    /**
     * Pauses ({@code paused}: true, the default) or resumes one vault-backed rule ({@code ruleId}),
     * or, without an id, every vault-backed rule of the player. Rules that work from an Auto-Trader
     * block are left alone.
     */
    private static LinkActionResult rulesPause(Call call) {
        Boolean paused = flag(call.params(), "paused", true);
        if (paused == null) return badText("paused");
        List<UUID> targets = new ArrayList<>();
        if (call.params().has("ruleId")) {
            UUID ruleId = LinkPayloads.uuid(call.params(), "ruleId");
            if (ruleId == null) return badText("ruleId");
            if (vaultRule(call, ruleId) == null) return ownsRule(call, ruleId)
                    ? invalid(LinkText.get("link.neroeconomy.rule_not_vault")) : refused("message.neroeconomy.rule.not_found");
            targets.add(ruleId);
        } else {
            for (TradeRule rule : call.store().rules().rulesOf(call.account()))
                if (RulesSection.VAULT.equals(rule.binding())) targets.add(rule.id());
        }
        int changed = 0;
        RemoteGoodsValue.Pricing pricing = paused ? null : goodsPricing(call);
        for (UUID ruleId : targets) {
            // Resuming a paused sell rule lets it sell what it has left from the vault: counted as setting one.
            TradeRule rule = vaultRule(call, ruleId);
            long goodsValue = 0L;
            if (pricing != null && rule != null && rule.paused() && !rule.buys()) {
                goodsValue = times(ruleUnitValue(call, pricing, rule.tag(), rule.target()),
                        Math.max(0L, rule.total() - rule.done()));
                LinkActionResult goodsLimited = overGoodsLimit(call, goodsValue);
                if (goodsLimited != null) {
                    if (targets.size() == 1) return goodsLimited;
                    continue;
                }
            }
            RuleOutcome outcome = RuleStacks.setPaused(call.server(), call.player(), ruleId, paused);
            if (outcome.ok()) {
                changed++;
                goodsSent(call, goodsValue);
            } else if (targets.size() == 1) {
                return refused(outcome.result().translationKey());
            }
        }
        JsonObject detail = LinkViews.rulesPaused(paused, changed);
        return ok(call, RULES_PAUSE, LinkText.get(paused ? "link.neroeconomy.rules_paused"
                : "link.neroeconomy.rules_resumed", changed), detail, false);
    }

    // --- the exchange ------------------------------------------------------------------------

    /**
     * {@code stockId}; either {@code credits} (fee included) or {@code units} with
     * {@code maxSpend} (fee included); {@code practice} for play credits.
     */
    private static LinkActionResult stockBuy(Call call) {
        JsonObject params = call.params();
        String stock = stockId(call);
        if (stock == null) return invalid(LinkText.get("link.neroeconomy.unknown_stock"));
        Boolean practice = flag(params, "practice", false);
        if (practice == null) return badText("practice");
        Long credits = number(params, "credits", 1L, MAX_CREDITS, null);
        Long units = number(params, "units", 1L, MAX_CREDITS, null);
        Long maxSpend = number(params, "maxSpend", 1L, MAX_CREDITS, null);
        boolean byCredits = params.has("credits");
        if (byCredits && credits == null) return badNumber("credits", 1L, MAX_CREDITS);
        if (!byCredits && units == null) return badNumber("units", 1L, MAX_CREDITS);
        if (!byCredits && maxSpend == null) return badNumber("maxSpend", 1L, MAX_CREDITS);
        TradeQuote quote;
        if (practice) {
            quote = byCredits ? Investing.practiceBuyForCredits(call.server(), call.player(), stock, credits, call.request())
                    : Investing.practiceBuy(call.server(), call.player(), stock, units, maxSpend, call.request());
        } else {
            LinkActionResult limited = overLimit(call, byCredits ? credits : maxSpend);
            if (limited != null) return limited;
            quote = byCredits ? Investing.buyForCredits(call.server(), call.player(), stock, credits, call.request())
                    : Investing.buy(call.server(), call.player(), stock, units, maxSpend, call.request());
        }
        if (!quote.ok()) return refused(quote.reasonKey());
        if (!practice) spent(call, quote.net());
        return ok(call, STOCK_BUY, LinkText.get("link.neroeconomy.stock_bought"), quoteJson(quote, practice), !practice);
    }

    /**
     * {@code stockId}; {@code units} (absent or 0 for everything that may be sold);
     * {@code acceptedGain}: when only part of the gain can be paid, the sale goes ahead only with
     * this set to exactly the number the refusal quoted; {@code practice} for play credits;
     * {@code structuredRefusal}: true to receive a reduced-gain refusal as a result that sold nothing
     * ({@link LinkViews#gainRefusal}) instead of an error, so the app need not read the message.
     */
    private static LinkActionResult stockSell(Call call) {
        JsonObject params = call.params();
        String stock = stockId(call);
        if (stock == null) return invalid(LinkText.get("link.neroeconomy.unknown_stock"));
        Boolean practice = flag(params, "practice", false);
        if (practice == null) return badText("practice");
        Long units = number(params, "units", 0L, MAX_CREDITS, 0L);
        if (units == null) return badNumber("units", 0L, MAX_CREDITS);
        long selling = units == 0L ? Quotes.ALL : units;
        TradeQuote quote;
        if (practice) {
            quote = Investing.practiceSell(call.server(), call.player(), stock, selling, call.request());
        } else {
            Long accepted = number(params, LinkViews.ACCEPT_GAIN_PARAM, 0L, MAX_CREDITS, null);
            if (params.has(LinkViews.ACCEPT_GAIN_PARAM) && accepted == null)
                return badNumber(LinkViews.ACCEPT_GAIN_PARAM, 0L, MAX_CREDITS);
            Boolean structured = flag(params, "structuredRefusal", false);
            if (structured == null) return badText("structuredRefusal");
            // Quoted first, without spending the request id: a reduced gain is decided by the player.
            TradeQuote now = Investing.quoteSell(call.server(), call.player(), stock, selling);
            if (now.result() == TradeResult.GAIN_REDUCED) {
                if (accepted == null) return gainRefused(call, structured, GAIN_REDUCED, now,
                        LinkText.get("link.neroeconomy.gain_reduced", now.gainPayable(), now.gain()));
                if (accepted != now.gainPayable()) return gainRefused(call, structured, GAIN_CHANGED, now,
                        LinkText.get("link.neroeconomy.gain_changed", now.gainPayable(), now.gain()));
            } else if (!now.ok()) {
                return refused(now.reasonKey());
            }
            long accept = now.result() == TradeResult.GAIN_REDUCED ? accepted : ExchangeDesk.FULL_GAIN_ONLY;
            quote = Investing.sell(call.server(), call.player(), stock, selling, accept, call.request());
            if (quote.result() == TradeResult.GAIN_CHANGED || quote.result() == TradeResult.GAIN_REDUCED)
                return gainRefused(call, structured, GAIN_CHANGED, quote,
                        LinkText.get("link.neroeconomy.gain_changed", quote.gainPayable(), quote.gain()));
        }
        if (!quote.ok()) return refused(quote.reasonKey());
        JsonObject detail = quoteJson(quote, practice);
        detail.addProperty("sold", true);
        return ok(call, STOCK_SELL, LinkText.get("link.neroeconomy.stock_sold"), detail, false);
    }

    /** {@code refusal} of a {@link LinkViews#gainRefusal}: no acceptance was sent. */
    static final String GAIN_REDUCED = "gain_reduced";
    /** {@code refusal} of a {@link LinkViews#gainRefusal}: the acceptance sent no longer matches. */
    static final String GAIN_CHANGED = "gain_changed";

    /**
     * A sale stopped because only part of the gain can be paid; nothing was sold. As the plain
     * error schema-2 apps already handle, or, when the app asked with {@code structuredRefusal},
     * as a result carrying the payable amount and the parameter that accepts it, message kept.
     */
    private static LinkActionResult gainRefused(Call call, boolean structured, String refusal, TradeQuote quote,
            String message) {
        if (!structured) return invalid(message);
        JsonObject detail = LinkViews.gainRefusal(quote.stockId(), quote.units(), refusal, quote.gain(),
                quote.gainPayable());
        return ok(call, STOCK_SELL, message, detail, false);
    }

    private static String stockId(Call call) {
        String typed = text(call.params(), "stockId", 128);
        return typed == null ? null : Investing.resolve(call.server(), typed);
    }

    static JsonObject quoteJson(TradeQuote quote, boolean practice) {
        return LinkViews.stockTrade(quote.stockId(), quote.side().name().toLowerCase(java.util.Locale.ROOT),
                quote.units(), quote.unitPriceCents(), quote.total(), quote.fee(), quote.net(), quote.costBasis(),
                quote.gainPayable(), quote.gainForfeited(), quote.loss(), practice);
    }

    /** {@code term} 1d, 3d or 7d; {@code principal}; {@code practice} for play credits. */
    private static LinkActionResult bondBuy(Call call) {
        JsonObject params = call.params();
        BondTerm term = BondTerm.parse(LinkPayloads.text(params, "term"));
        if (term == null) return invalid(LinkText.get("link.neroeconomy.bad_term"));
        Long principal = number(params, "principal", 1L, MAX_CREDITS, null);
        if (principal == null) return badNumber("principal", 1L, MAX_CREDITS);
        Boolean practice = flag(params, "practice", false);
        if (practice == null) return badText("practice");
        BondOutcome outcome;
        if (practice) {
            outcome = Investing.practiceBuyBond(call.server(), call.player(), term, principal, call.request());
        } else {
            LinkActionResult limited = overLimit(call, principal);
            if (limited != null) return limited;
            outcome = Investing.buyBond(call.server(), call.player(), term, principal, call.request());
        }
        if (!outcome.ok()) return refused(outcome.reasonKey());
        if (!practice) spent(call, outcome.paid());
        JsonObject detail = LinkViews.bondBought(outcome.bond().id().toString(), term.key(), outcome.bond().principal(),
                outcome.interest(), outcome.paid(), Math.max(0L, outcome.bond().maturesAt() - LinkReads.now()), practice);
        return ok(call, BOND_BUY, LinkText.get("link.neroeconomy.bond_bought"), detail, !practice);
    }

    // --- payments and offers -----------------------------------------------------------------

    /** Longest recipient: a UUID's 36 characters, or a player name. */
    static final int MAX_RECIPIENT = 64;

    /**
     * {@code recipient} (a name or a UUID, as {@code /eco pay} takes it) and {@code amount}. Every
     * refusal comes before the ledger is called; the request id is spent only once the payment is
     * otherwise good. The recipient is never echoed back.
     */
    private static LinkActionResult pay(Call call) {
        String recipient = text(call.params(), "recipient", MAX_RECIPIENT);
        if (recipient == null) return badText("recipient");
        Long amount = number(call.params(), "amount", 1L, MAX_CREDITS, null);
        if (amount == null) return badNumber("amount", 1L, MAX_CREDITS);
        UUID target = call.players().resolve(recipient);
        long spendable = NeroEconomyCommon.CURRENCY_PROVIDER.spendable(call.player());
        BankTransferRules.Check check = BankTransferRules.check(amount, target != null, call.player().equals(target),
                CurrencyApi.getBalance(call.player(), NeroEconomyCommon.CREDITS), spendable);
        if (check == BankTransferRules.Check.FUNDS_LOCKED) return invalid(LinkText.get(check.translationKey(), spendable,
                EconomyConfig.STARTING_BALANCE_VEST_MINUTES.get()));
        if (check != BankTransferRules.Check.OK) return invalid(LinkText.get(check.translationKey(), amount));
        LinkActionResult limited = overLimit(call, amount);
        if (limited != null) return limited;
        if (!call.store().market().rememberRequest(call.player(), call.request()))
            return invalid(LinkPayloads.DUPLICATE_REQUEST);
        if (!CurrencyApi.transfer(call.player(), target, NeroEconomyCommon.CREDITS, amount))
            return invalid(LinkText.get("gui.neroeconomy.bank.payment_failed", amount));
        spent(call, amount);
        // The recipient is told an amount arrived, as in game; never by whom.
        ServerPlayer online = call.players().online(target);
        if (online != null)
            online.sendSystemMessage(Component.translatable("command.neroeconomy.payment_received", amount));
        JsonObject detail = LinkViews.paid(amount);
        return ok(call, PAY, LinkText.get("link.neroeconomy.paid"), detail, true);
    }

    /**
     * {@code recipient}; {@code goods}, up to nine {@code {item, qty, payload}} lines from the vault;
     * {@code credits}; and what is asked in return: {@code askItem} with {@code askQty}, and
     * {@code askCredits}. Everything given is escrowed at once.
     */
    private static LinkActionResult offerSend(Call call) {
        JsonObject params = call.params();
        String recipient = text(params, "recipient", MAX_RECIPIENT);
        if (recipient == null) return badText("recipient");
        List<Lot> lots = new ArrayList<>();
        JsonElement goods = params.get("goods");
        if (goods != null && !goods.isJsonNull()) {
            if (!goods.isJsonArray() || goods.getAsJsonArray().size() > OffersSection.MAX_LOTS) return badText("goods");
            for (JsonElement line : goods.getAsJsonArray()) {
                if (!line.isJsonObject()) return badText("goods");
                JsonObject given = line.getAsJsonObject();
                String item = text(given, "item", Lot.MAX_ITEM_LENGTH);
                Long qty = number(given, "qty", 1L, BookSection.MAX_ORDER_QUANTITY, null);
                Long payload = number(given, "payload", 0L, Integer.MAX_VALUE, 0L);
                if (item == null || qty == null || payload == null || !Lot.valid(item, qty.intValue(), payload.intValue()))
                    return badText("goods");
                lots.add(new Lot(item, qty.intValue(), payload.intValue()));
            }
        }
        Long credits = number(params, "credits", 0L, MAX_CREDITS, 0L);
        if (credits == null) return badNumber("credits", 0L, MAX_CREDITS);
        Long askCredits = number(params, "askCredits", 0L, MAX_CREDITS, 0L);
        if (askCredits == null) return badNumber("askCredits", 0L, MAX_CREDITS);
        Long askQty = number(params, "askQty", 0L, BookSection.MAX_ORDER_QUANTITY, 0L);
        if (askQty == null) return badNumber("askQty", 0L, BookSection.MAX_ORDER_QUANTITY);
        String askItem = params.has("askItem") ? text(params, "askItem", Lot.MAX_ITEM_LENGTH) : null;
        if (params.has("askItem") && askItem == null) return badText("askItem");
        if ((askItem == null) != (askQty == 0L)) return badText("askItem");
        LinkActionResult limited = overLimit(call, credits);
        if (limited != null) return limited;
        long goodsValue = RemoteGoodsValue.of(goodsPricing(call), lots);
        LinkActionResult goodsLimited = overGoodsLimit(call, goodsValue);
        if (goodsLimited != null) return goodsLimited;
        UUID target = call.players().resolve(recipient);
        OfferStacks.Terms terms = new OfferStacks.Terms(0, lots, credits, askItem, askQty.intValue(), askCredits);
        OfferOutcome sent = OfferStacks.send(call.store(), call.env(), call.player(), null, target, terms, call.request());
        if (!sent.ok()) return refused(sent.result().translationKey());
        spent(call, sent.offer().credits());
        goodsSent(call, goodsValue);
        // The recipient learns an offer is waiting, not who sent it.
        ServerPlayer online = call.players().online(target);
        if (online != null) online.sendSystemMessage(Component.translatable("gui.neroeconomy.hub.trade.received"));
        JsonObject detail = LinkViews.offerSent(sent.offer().id().toString(), sent.offer().credits(),
                sent.offer().itemCount(), Math.max(0L, sent.offer().expiresAt() - LinkReads.now()));
        return ok(call, OFFER_SEND, LinkText.get("link.neroeconomy.offer_sent"), detail, true);
    }

    /** {@code offerId}: an offer sent to the player. What it asks is paid from their balance and vault. */
    private static LinkActionResult offerAccept(Call call) {
        UUID offerId = LinkPayloads.uuid(call.params(), "offerId");
        if (offerId == null) return badText("offerId");
        Offer offer = call.store().offers().offer(offerId);
        if (offer == null || !offer.recipient().equals(call.account()))
            return refused(OfferResult.NOT_FOUND.translationKey());
        LinkActionResult limited = overLimit(call, offer.askCredits());
        if (limited != null) return limited;
        // Goods the offer asks for leave the player's vault.
        long goodsValue = RemoteGoodsValue.of(goodsPricing(call), offer.askItem(), Lot.PRISTINE, offer.askCount());
        LinkActionResult goodsLimited = overGoodsLimit(call, goodsValue);
        if (goodsLimited != null) return goodsLimited;
        OfferOutcome accepted = OfferStacks.accept(call.store(), call.env(), call.player(), offerId, call.request());
        if (!accepted.ok()) return refused(accepted.result().translationKey());
        spent(call, accepted.recipientPaid());
        goodsSent(call, goodsValue);
        // The sender is told their offer was answered, as in game; never by whom.
        AccountId sender = accepted.offer().sender();
        ServerPlayer online = sender instanceof PlayerAccountId id ? call.players().online(id.playerId()) : null;
        if (online != null) online.sendSystemMessage(Component.translatable("command.neroeconomy.offer.accepted_sender",
                accepted.senderGets()));
        JsonObject detail = LinkViews.offerAccepted(offerId.toString(), accepted.recipientGot(),
                accepted.recipientPaid(), accepted.tax(), accepted.offer().itemCount());
        return ok(call, OFFER_ACCEPT, LinkText.get("link.neroeconomy.offer_accepted"), detail, true);
    }

    private static LinkActionResult offerDecline(Call call) {
        UUID offerId = LinkPayloads.uuid(call.params(), "offerId");
        if (offerId == null) return badText("offerId");
        OfferOutcome declined = OfferStacks.decline(call.store(), call.env(), call.player(), offerId);
        if (!declined.ok()) return refused(declined.result().translationKey());
        AccountId sender = declined.offer().sender();
        ServerPlayer online = sender instanceof PlayerAccountId id ? call.players().online(id.playerId()) : null;
        if (online != null) online.sendSystemMessage(Component.translatable("command.neroeconomy.offer.declined_sender"));
        JsonObject detail = LinkViews.idOnly("offerId", offerId.toString());
        return ok(call, OFFER_DECLINE, LinkText.get("link.neroeconomy.offer_declined"), detail, false);
    }

    /**
     * {@code offerId}: an offer the player sent. Takes it back through the same path as the Economy
     * Hub's cancel: its goods go back to the player's vault and its credits to their balance (or,
     * when the ledger cannot pay them out just now, to their credit claim). A refund, so it works
     * with the market closed and spends nothing. The recipient is not told, as in game.
     */
    private static LinkActionResult offerCancel(Call call) {
        UUID offerId = LinkPayloads.uuid(call.params(), "offerId");
        if (offerId == null) return badText("offerId");
        OfferOutcome cancelled = OfferStacks.cancel(call.store(), call.env(), call.player(), offerId);
        if (!cancelled.ok()) return refused(cancelled.result().translationKey());
        Offer offer = cancelled.offer();
        JsonObject detail = LinkViews.offerCancelled(offerId.toString(), offer.credits(), offer.itemCount(),
                cancelled.refundedToClaim());
        return ok(call, OFFER_CANCEL, LinkText.get(cancelled.refundedToClaim() ? "link.neroeconomy.offer_cancelled_claim"
                : "link.neroeconomy.offer_cancelled"), detail, false);
    }

    // --- collecting and the vault ------------------------------------------------------------

    /** Waiting credits to the balance; goods stay in the vault. */
    private static LinkActionResult claimCollect(Call call) {
        long credits = call.store().market().collectCreditsTotal(call.player());
        JsonObject detail = LinkViews.collected(credits);
        return ok(call, CLAIM_COLLECT, credits > 0L ? LinkText.get("link.neroeconomy.collected", credits)
                : LinkText.get("link.neroeconomy.nothing_to_collect"), detail, false);
    }

    /** {@code qty} (default: the whole stack) from the main hand into the vault. In game only. */
    private static LinkActionResult vaultDeposit(Call call) {
        ServerPlayer player = call.online();
        if (player == null) return offline(LinkText.get("link.neroeconomy.needs_player"));
        if (!player.isAlive() || player.isSpectator() || !GoodsSource.allowed(player))
            return invalid(LinkPayloads.CREATIVE_SOURCE);
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) return invalid(LinkText.get("link.neroeconomy.nothing_in_hand"));
        Long quantity = number(call.params(), "qty", 1L, held.getCount(), (long) held.getCount());
        if (quantity == null) return badNumber("qty", 1L, held.getCount());
        ItemStack moving = held.copyWithCount(quantity.intValue());
        if (!MarketSavedData.tradeable(moving, player.registryAccess()))
            return invalid(LinkText.get("link.neroeconomy.untradeable"));
        Lot lot = call.store().goods().lotOf(moving);
        if (lot == null) return invalid(LinkText.get("link.neroeconomy.untradeable"));
        if (!call.store().vault().deposit(call.account(), List.of(lot), VaultStacks.cap(), call.store().goods().limits()))
            return invalid(LinkText.get("link.neroeconomy.vault_no_room"));
        held.shrink(quantity.intValue());
        JsonObject detail = LinkViews.deposited(LinkReads.goods(call.store(), lot));
        return ok(call, VAULT_DEPOSIT, LinkText.get("link.neroeconomy.deposited", quantity), detail, false);
    }

    /**
     * Without {@code index}: as much of the vault as fits the inventory. With {@code index} (a line
     * of the {@code vault} section), {@code item} (that line's item, so a stale row is refused) and
     * {@code qty}: that much of that line; whatever does not fit goes straight back. In game only.
     */
    private static LinkActionResult vaultWithdraw(Call call) {
        ServerPlayer player = call.online();
        if (player == null) return offline(LinkText.get("link.neroeconomy.needs_player"));
        if (!player.isAlive() || player.isSpectator()) return offline(LinkText.get("link.neroeconomy.needs_player"));
        int moved;
        if (!call.params().has("index")) {
            moved = VaultStacks.collect(call.store(), player);
        } else {
            List<Lot> lots = call.store().vault().stacks(call.player());
            Long index = number(call.params(), "index", 0L, Math.max(0, lots.size() - 1), null);
            if (index == null || lots.isEmpty()) return invalid(LinkText.get("link.neroeconomy.vault_line_changed"));
            Lot line = lots.get(index.intValue());
            if (!line.item().equals(LinkPayloads.text(call.params(), "item")))
                return invalid(LinkText.get("link.neroeconomy.vault_line_changed"));
            Long quantity = number(call.params(), "qty", 1L, line.count(), (long) line.count());
            if (quantity == null) return badNumber("qty", 1L, line.count());
            Lot taken = call.store().vault().withdrawAt(call.account(), index.intValue(), quantity.intValue());
            if (taken == null) return invalid(LinkText.get("link.neroeconomy.vault_line_changed"));
            // Into the inventory as far as it fits; the rest goes straight back to the vault.
            moved = VaultStacks.giveOrDeliver(call.store(), player, call.store().goods().stackOf(taken));
        }
        JsonObject detail = LinkViews.withdrew(moved);
        return ok(call, VAULT_WITHDRAW, moved > 0 ? LinkText.get("link.neroeconomy.withdrew", moved)
                : LinkText.get("link.neroeconomy.nothing_fits"), detail, false);
    }

    /** Test seam and documentation: every action id and whether it works with the player away. */
    static Map<String, Boolean> offlineTable() {
        Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        for (String action : ALL) out.put(action, OFFLINE.contains(action));
        return out;
    }
}
