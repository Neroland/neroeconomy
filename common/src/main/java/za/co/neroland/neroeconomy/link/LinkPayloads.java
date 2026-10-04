package za.co.neroland.neroeconomy.link;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.market.FillResult;
import za.co.neroland.neroeconomy.market.PostResult;
import za.co.neroland.neroeconomy.market.PurchaseResult;

/**
 * Every JSON shape the companion-app module emits, and the reading of every parameter it accepts.
 * Nothing here touches the game: the module gathers plain values from the stores and this class
 * turns them into JSON, so the wire contract is pinned by a plain unit test.
 *
 * <p><b>Two key sets, one object.</b> Schema 1 shipped keys the companion app does not read, and the
 * app is already installed, so each object carries the schema-1 keys <em>and</em> the keys the app
 * parses. The schema-1 keys stay for one release.
 *
 * <p><b>Privacy.</b> No method takes or emits a player name, a UUID of a player, an account
 * pseudonym, a location or a counterparty. A market row's id is the listing's or offer's own id,
 * and a transaction row's id is derived from when the row was written.
 */
public final class LinkPayloads {
    /** Display symbol for credits; the same abbreviation the in-game screens use. */
    public static final String CURRENCY_SYMBOL = "cr";
    /** Balance samples in {@code history}, including the current balance. */
    public static final int HISTORY_POINTS = 20;
    /** Rows in the schema-1 {@code recentTrend}. */
    public static final int TREND_ROWS = 10;
    public static final int MIN_QUANTITY = 1;
    public static final int MAX_QUANTITY = 64;
    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final int MAX_PAGE_SIZE = 50;
    /** Longest display name sent; an item name is short, and anything longer is cut. */
    public static final int MAX_NAME_LENGTH = 64;

    public static final String REQUEST_ID_REQUIRED = "requestId is required";
    public static final String DUPLICATE_REQUEST = "Duplicate request: this trade was already handled.";
    public static final String QUANTITY_OUT_OF_RANGE =
            "qty must be a whole number from " + MIN_QUANTITY + " to " + MAX_QUANTITY + ".";
    public static final String TOO_MANY_REQUESTS = "Too many requests. Try again shortly.";
    public static final String TRADE_DISABLED = "Remote trading is turned off on this server.";
    public static final String UNKNOWN_OPERATION = "Unknown trade operation. Use buy or sell.";
    public static final String LISTING_ID_REQUIRED = "listingId is required.";
    public static final String SELL_NEEDS_BUY_OFFER = "Selling from the app needs a buy offer to fill. Pick a "
            + "market row marked sell, or list your items at a Trading Terminal in game.";
    public static final String SELL_NEEDS_PLAYER =
            "Selling takes the items from your main hand, so you need to be in the game.";
    public static final String ECONOMY_FROZEN = "The economy on this server is paused, so nothing can be "
            + "traded right now. Ask a server operator.";
    public static final String CREATIVE_SOURCE =
            "Items cannot be sold from creative mode or spectator mode. Switch to survival first.";
    public static final String BUY_NEEDS_LISTING =
            "That row is a request to buy, so there is nothing to buy from it. Switch to sell to fill it.";

    /** A translation key that was never resolved, such as {@code item.minecraft.diamond}. */
    private static final Pattern UNTRANSLATED = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_/-]+)+");

    private LinkPayloads() { }

    /**
     * One of the subject's own ledger rows.
     *
     * @param amount    unsigned, as stored
     * @param incoming  true when the subject received it
     * @param economyAt economy time; only used to derive the row id
     * @param wallAt    the same instant as wall-clock millis; the only time that is sent
     */
    public record Entry(TransactionType type, long amount, boolean incoming, String currency, long economyAt,
            long wallAt) {
        long signed() { return incoming ? amount : -amount; }
    }

    /**
     * One market row: a sell listing or a standing buy offer.
     *
     * @param id          the listing or offer id
     * @param displayName the item's name as the server resolved it; may be blank or an unresolved key
     * @param unitPrice   for a listing, the price quoted to this viewer; for an offer, what it pays
     * @param own         the viewer posted it, so they cannot trade against it
     * @param pristine    false for a renamed, enchanted, filled or worn stack
     */
    public record Row(String id, String itemId, String displayName, int quantity, long unitPrice, boolean own,
            boolean pristine) { }

    /**
     * What a successful trade did.
     *
     * @param op        {@code buy} or {@code sell}
     * @param listingId the row traded against, or the new listing when one was posted
     * @param unitPrice the price each item actually traded at, or the asking price of a new listing
     * @param total     credits that moved, or the asking total of a new listing
     * @param filled    true when goods and credits changed hands; false when a listing was only posted
     * @param delivery  {@code inventory}, {@code claim}, {@code credit_claim} or {@code listed}
     * @param fee       the fee this player paid on the trade: the market fee out of a sale's proceeds, or
     *                  the listing fee for a new listing; 0 when there was none
     * @param received  what a seller actually gets for a filled sale: {@code total} less the fee
     */
    public record Trade(String op, String listingId, int quantity, long unitPrice, long total, boolean filled,
            String delivery, long fee, long received) {
        /** A trade that carried no fee for this player. */
        public Trade(String op, String listingId, int quantity, long unitPrice, long total, boolean filled,
                String delivery) {
            this(op, listingId, quantity, unitPrice, total, filled, delivery, 0L, total);
        }
    }

    // --- balance -----------------------------------------------------------------------------

    /**
     * The balance object: the {@code balance} section, the body of a {@code balance} event and the
     * base of a trade result.
     *
     * @param newestFirst the subject's most recent rows, newest first
     * @param asOf        wall-clock millis
     */
    public static JsonObject balance(String currencyId, long balance, List<Entry> newestFirst, long asOf) {
        JsonObject out = new JsonObject();
        // Schema 1.
        out.addProperty("currency", currencyId);
        out.addProperty("balance", balance);
        JsonArray trend = new JsonArray();
        newestFirst.stream().limit(TREND_ROWS).forEach(row -> {
            JsonObject point = new JsonObject();
            point.addProperty("at", row.wallAt());
            point.addProperty("amount", row.amount());
            point.addProperty("reason", row.type().name());
            trend.add(point);
        });
        out.add("recentTrend", trend);
        // What the app reads.
        out.addProperty("amount", balance);
        out.addProperty("symbol", CURRENCY_SYMBOL);
        out.add("history", history(balance, newestFirst));
        out.addProperty("asOf", asOf);
        return out;
    }

    /**
     * Balance samples, oldest first and ending at the current balance: the ledger rows are undone
     * one by one from the balance backwards. A sample is never negative, whatever the rows say.
     */
    static JsonArray history(long balance, List<Entry> newestFirst) {
        int rows = Math.min(newestFirst.size(), HISTORY_POINTS - 1);
        long[] points = new long[rows + 1];
        points[rows] = balance;
        long running = balance;
        for (int index = 0; index < rows; index++) {
            long delta = newestFirst.get(index).signed();
            try {
                running = Math.max(0L, Math.subtractExact(running, delta));
            } catch (ArithmeticException overflow) {
                running = delta > 0L ? 0L : Long.MAX_VALUE;
            }
            points[rows - 1 - index] = running;
        }
        JsonArray out = new JsonArray();
        for (long point : points) out.add(point);
        return out;
    }

    // --- transactions ------------------------------------------------------------------------

    /**
     * One page of the subject's history.
     *
     * <p>{@code nextCursor} is a <b>string</b> holding the offset of the next page and is left out
     * on the last page. Schema 1 sent a number and {@code -1}; the app casts the value to a string,
     * so the number broke its history tab.
     *
     * @param newestFirst every retained row of the subject, newest first; ids are derived over all of them
     */
    public static JsonObject transactions(List<Entry> newestFirst, int cursor, int limit, long asOf) {
        int size = newestFirst.size();
        int from = Math.max(0, Math.min(size, cursor));
        int to = (int) Math.min(size, (long) from + Math.max(1, Math.min(MAX_PAGE_SIZE, limit)));
        List<String> ids = rowIds(newestFirst);
        JsonArray items = new JsonArray();
        JsonArray rows = new JsonArray();
        for (int index = from; index < to; index++) {
            Entry entry = newestFirst.get(index);
            JsonObject item = new JsonObject();
            item.addProperty("reason", entry.type().name());
            item.addProperty("amount", entry.amount());
            item.addProperty("currency", entry.currency());
            item.addProperty("timestamp", entry.wallAt());
            items.add(item);
            JsonObject row = new JsonObject();
            row.addProperty("id", ids.get(index));
            row.addProperty("at", entry.wallAt());
            row.addProperty("kind", kind(entry.type(), entry.incoming()));
            row.addProperty("amount", entry.signed());
            // The audit log stores no item and no quantity, by design. The app titles a row with
            // "item", so it carries a fixed description of the kind instead of staying blank.
            row.addProperty("item", label(kind(entry.type(), entry.incoming()), entry.incoming()));
            row.addProperty("qty", 0);
            row.addProperty("reason", entry.type().name());
            rows.add(row);
        }
        JsonObject out = new JsonObject();
        out.add("items", items);
        out.add("rows", rows);
        if (to < size) out.addProperty("nextCursor", Integer.toString(to));
        out.addProperty("asOf", asOf);
        return out;
    }

    /**
     * An id per row that stays the same as newer rows arrive: when the row was written, plus its
     * place among the rows written in that same millisecond. It is an opaque token, not a time for
     * display, and it contains no account reference.
     */
    static List<String> rowIds(List<Entry> newestFirst) {
        String[] ids = new String[newestFirst.size()];
        int run = 0;
        for (int index = 0; index < ids.length; index++) {
            long at = newestFirst.get(index).economyAt();
            run = index > 0 && newestFirst.get(index - 1).economyAt() == at ? run + 1 : 0;
            ids[index] = "tx-" + Long.toString(at, Character.MAX_RADIX) + "-" + run;
        }
        return List.of(ids);
    }

    /** Fixed, non-personal row title for a {@link #kind}; never an item name, amount or account. */
    public static String label(String kind, boolean incoming) {
        return switch (kind) {
            case "purchase" -> "Market purchase";
            case "sale" -> "Market sale";
            case "transfer" -> incoming ? "Payment received" : "Payment sent";
            case "escrow" -> "Held in escrow";
            case "refund" -> "Escrow refund";
            case "payout" -> "Job or auction payout";
            case "bid" -> "Auction bid";
            case "deposit" -> "Deposit";
            case "withdrawal" -> "Withdrawal";
            case "admin" -> "Admin adjustment";
            case "fee" -> "Fee";
            case "adjustment" -> "Price adjustment";
            case "investment" -> "Exchange purchase";
            case "redemption" -> "Exchange sale";
            case "earnings" -> "Exchange earnings";
            default -> "Transaction";
        };
    }

    /**
     * The app-facing word for a ledger row. {@code sale} and {@code purchase} are the two the app
     * was designed around; the rest keep to one lower-case word each.
     */
    public static String kind(TransactionType type, boolean incoming) {
        return switch (type) {
            case SHOP_PURCHASE -> incoming ? "sale" : "purchase";
            case MARKET_PURCHASE -> "purchase";
            case MARKET_PAYOUT -> "sale";
            case MARKET_ESCROW -> "escrow";
            case ESCROW_REFUND -> "refund";
            case PLAYER_PAYMENT -> "transfer";
            case CONTRACT_PAYOUT, BOUNTY_PAYOUT, AUCTION_PAYOUT -> "payout";
            case AUCTION_BID -> "bid";
            case STARTING_BALANCE, DEPOSIT, CREDIT_CHIP_REDEMPTION -> "deposit";
            case WITHDRAWAL, SIBLING_CHARGE -> "withdrawal";
            case ADMIN_GRANT, ADMIN_TAKE, ADMIN_SET -> "admin";
            case SIBLING_PAYOUT -> "deposit";
            case FEE_MARKET, FEE_LISTING, FEE_AUCTION, FEE_EXCHANGE, TRADE_TAX -> "fee";
            case PRICE_ADJUSTMENT -> "adjustment";
            // The exchange: what goes into a position, what comes back out of it, and what it earned.
            case SHARE_BUY, BOND_BUY -> "investment";
            case SHARE_SELL, BOND_REDEEM -> "redemption";
            case CAPITAL_GAIN, DIVIDEND, BOND_INTEREST -> "earnings";
            // Never part of a player's own history: they move between system accounts only.
            case RESERVE_MINT, MIGRATION, ERASURE_SWEEP, CAPITAL_LOSS, BOND_INTEREST_RESERVED,
                    BOND_INTEREST_RELEASED -> "system";
        };
    }

    // --- market ------------------------------------------------------------------------------

    /**
     * One market page.
     *
     * <p>{@code side} is the operation the <em>viewer</em> performs: a seller's listing carries
     * {@code "buy"} and a standing buy offer carries {@code "sell"}. The viewer's own rows are left
     * out of {@code listings}, because a trade against them is always refused; the schema-1
     * {@code items} array is unchanged and still holds every sell listing on the page.
     *
     * @param listings      this page's sell listings
     * @param buyOffers     this page's buy offers
     * @param listingTotal  every matching sell listing, the viewer's own included
     * @param buyOfferTotal every matching buy offer, the viewer's own included
     */
    public static JsonObject market(List<Row> listings, List<Row> buyOffers, int page, int pageSize,
            int listingTotal, int buyOfferTotal, long asOf) {
        JsonArray items = new JsonArray();
        JsonArray rows = new JsonArray();
        for (Row listing : listings) {
            JsonObject item = new JsonObject();
            item.addProperty("listingId", listing.id());
            item.addProperty("item", listing.itemId());
            item.addProperty("stock", listing.quantity());
            item.addProperty("unitPrice", listing.unitPrice());
            items.add(item);
            if (!listing.own()) rows.add(marketRow(listing, "buy"));
        }
        for (Row offer : buyOffers) if (!offer.own()) rows.add(marketRow(offer, "sell"));
        JsonObject out = new JsonObject();
        out.add("items", items);
        out.addProperty("page", page);
        out.add("listings", rows);
        out.addProperty("pageSize", pageSize);
        out.addProperty("total", (long) listingTotal + buyOfferTotal);
        out.addProperty("hasMore", (page + 1L) * pageSize < Math.max(listingTotal, buyOfferTotal));
        out.addProperty("asOf", asOf);
        return out;
    }

    private static JsonObject marketRow(Row row, String side) {
        JsonObject out = new JsonObject();
        out.addProperty("listingId", row.id());
        String name = displayName(row.displayName(), row.itemId());
        // The app shows a name only, so a stack that is not the plain item says so in the name.
        out.addProperty("displayName", row.pristine() ? name : name + " (modified)");
        out.addProperty("item", row.itemId());
        out.addProperty("qtyAvailable", row.quantity());
        out.addProperty("unitPrice", row.unitPrice());
        out.addProperty("side", side);
        out.addProperty("pristine", row.pristine());
        return out;
    }

    /**
     * A readable item name. The server resolves the name with its own language table; when that
     * gives nothing, or hands back the untranslated key, the name is built from the item id instead
     * ({@code neroeconomy:credit_chip} becomes {@code Credit Chip}).
     */
    public static String displayName(String resolved, String itemId) {
        String name = resolved == null ? "" : resolved.strip();
        if (name.isEmpty() || UNTRANSLATED.matcher(name).matches()) name = fromItemId(itemId);
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    private static String fromItemId(String itemId) {
        String id = itemId == null ? "" : itemId;
        String path = id.substring(Math.max(id.lastIndexOf(':'), id.lastIndexOf('/')) + 1);
        StringBuilder out = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return out.length() == 0 ? id : out.toString();
    }

    // --- trade -------------------------------------------------------------------------------

    /**
     * A successful trade: the balance object as it stands after the trade, plus what the trade did.
     * {@code price}, {@code total} and {@code balance} are what the app's receipt reads.
     */
    public static JsonObject tradeResult(JsonObject balance, Trade trade) {
        JsonObject out = balance.deepCopy();
        out.addProperty("op", trade.op());
        out.addProperty("listingId", trade.listingId());
        out.addProperty("qty", trade.quantity());
        out.addProperty("price", trade.unitPrice());
        out.addProperty("total", trade.total());
        out.addProperty("filled", trade.filled());
        out.addProperty("delivery", trade.delivery());
        // Only a trade that cost this player a fee says so; every other receipt is unchanged.
        if (trade.fee() > 0L) {
            out.addProperty("fee", trade.fee());
            if (trade.filled()) out.addProperty("received", trade.received());
        }
        return out;
    }

    // --- throttling --------------------------------------------------------------------------

    /** A copy of a snapshot marked as served without fresh work, because the player is over budget. */
    public static JsonObject throttled(JsonObject snapshot) {
        JsonObject out = snapshot.deepCopy();
        out.addProperty("throttled", true);
        return out;
    }

    // --- parameters --------------------------------------------------------------------------

    /** The text of a primitive member, or "" when it is absent or not a primitive. */
    public static String text(JsonObject params, String key) {
        JsonElement value = params == null ? null : params.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /** A member holding a UUID in its canonical 36-character form, or null. */
    public static UUID uuid(JsonObject params, String key) {
        String raw = text(params, key);
        if (raw.length() != 36) return null;
        try {
            UUID value = UUID.fromString(raw);
            return value.toString().equalsIgnoreCase(raw) ? value : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** The id every value-moving action must carry; null when it is missing or malformed. */
    public static UUID requestId(JsonObject params) { return uuid(params, "requestId"); }

    /**
     * {@code qty}: 1 when absent, otherwise a whole number from {@link #MIN_QUANTITY} to
     * {@link #MAX_QUANTITY}. @return the quantity, or -1 when it is out of range or not a whole number
     */
    public static int quantity(JsonObject params) {
        JsonElement value = params == null ? null : params.get("qty");
        if (value == null || value.isJsonNull()) return 1;
        long whole = wholeNumber(value);
        return whole >= MIN_QUANTITY && whole <= MAX_QUANTITY ? (int) whole : -1;
    }

    /** A whole-number member; {@code fallback} when it is absent, -1 when it is present but not one. */
    public static long wholeNumber(JsonObject params, String key, long fallback) {
        JsonElement value = params == null ? null : params.get(key);
        return value == null || value.isJsonNull() ? fallback : wholeNumber(value);
    }

    private static long wholeNumber(JsonElement value) {
        if (!value.isJsonPrimitive()) return -1L;
        try {
            // A number, or a string holding one: schema 1 accepted both.
            BigDecimal number = value.getAsBigDecimal();
            return number.signum() < 0 || number.stripTrailingZeros().scale() > 0 ? -1L : number.longValueExact();
        } catch (ArithmeticException | NumberFormatException notWhole) {
            return -1L;
        }
    }

    /** A page or cursor query parameter, clamped; anything unreadable is the fallback. */
    public static int bounded(String value, int fallback, int min, int max) {
        if (value == null) return fallback;
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value.strip())));
        } catch (NumberFormatException unreadable) {
            return fallback;
        }
    }

    // --- messages ----------------------------------------------------------------------------
    // The app shows an error message exactly as it arrives, so each one is a plain sentence. None
    // of them is built from a player, an item or an amount.

    public static String purchaseMessage(PurchaseResult result) {
        return switch (result) {
            case SUCCESS -> "Purchase completed.";
            case STALE_REQUEST -> DUPLICATE_REQUEST;
            case NOT_FOUND -> "That offer is no longer available.";
            case OWN_LISTING -> "You cannot buy your own offer.";
            case INVALID_QUANTITY -> "That quantity is not valid.";
            case OUT_OF_STOCK -> "There is not enough stock left.";
            case PRICE_MOVED -> "The price went up since you looked. Refresh the market and try again.";
            case INSUFFICIENT_FUNDS -> "You do not have enough credits.";
            case INVENTORY_FULL -> "Neither your inventory nor your vault has room for it.";
            case INTERNAL_ERROR -> "The sale could not be settled.";
            case ITEM_CHANGED -> "The item on offer changed since you looked.";
            case CLAIMS_FULL -> "Your vault is full. Take some items out at a Bank Terminal first.";
            case FUNDS_LOCKED -> "Your starting credits cannot be spent with other players until you have played a little longer.";
            case FROZEN -> ECONOMY_FROZEN;
        };
    }

    public static String fillMessage(FillResult result) {
        return switch (result) {
            case SUCCESS -> "Buy offer filled. Your proceeds are ready to collect at a Trading Terminal.";
            case INVALID -> "That request was not valid.";
            case STALE_REQUEST -> DUPLICATE_REQUEST;
            case NOT_FOUND -> "That buy offer is no longer open.";
            case OWN_OFFER -> "You cannot fill your own buy offer.";
            case WRONG_ITEM -> "Hold the requested item in your main hand in game, then try again.";
            case NOT_ENOUGH -> "You hold fewer than that in your main hand, or the offer wants fewer.";
            case MODIFIED_ITEM -> "Only unmodified items are accepted, not renamed, enchanted, filled or damaged ones.";
            case UNTRADEABLE -> "That item cannot be traded on this server.";
            case CLAIMS_FULL -> "The buyer's vault is full, so the offer cannot be filled right now.";
            case PAYOUT_BLOCKED -> "Collect your waiting credits at a Trading Terminal first.";
            case FROZEN -> ECONOMY_FROZEN;
            case CREATIVE -> CREATIVE_SOURCE;
        };
    }

    public static String postMessage(PostResult result) {
        return switch (result) {
            case SUCCESS -> "Listing posted.";
            case INVALID -> "The quantity or price is not valid.";
            case STALE_REQUEST -> DUPLICATE_REQUEST;
            case NOT_HOLDING -> "Hold the item in your main hand in game, in at least the quantity you are offering.";
            case LIMIT_REACHED -> "You already have as many open listings as this server allows.";
            case UNTRADEABLE -> "That item cannot be traded on this server.";
            case OVERSIZED -> "That stack holds too much data to be listed.";
            case UNKNOWN_ITEM -> "There is no such item.";
            case INSUFFICIENT_FUNDS -> "You do not have enough credits.";
            case FEE_UNPAID -> "You do not have enough credits to pay the listing fee.";
            case FUNDS_LOCKED -> "Your starting credits cannot be offered to other players until you have played a little longer.";
            case FROZEN -> ECONOMY_FROZEN;
            case STORAGE_FULL -> "Neither your inventory nor your vault has room for what the order would buy at once.";
            case PAYOUT_BLOCKED -> "Collect your waiting credits at a Trading Terminal first.";
            case CREATIVE -> CREATIVE_SOURCE;
        };
    }
}
