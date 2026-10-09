package za.co.neroland.neroeconomy.hub;

import java.util.Locale;

/**
 * The Trade tab's "Send offer" form as one line of text, so it fits the hub's one intent: whom it
 * is for (the name the sender typed, resolved on the server), what is given (from the main hand,
 * from a vault line named by the intent's row, or no goods) and how many, the credits given, and
 * what is asked in return. Written as {@code recipient;H|V|N;count;credits;askItem;askCount;askCredits}.
 * Pure Java; every bound is checked here and again by the offer rules.
 */
public record OfferForm(String recipient, Source source, int count, long credits, String askItem, int askCount,
        long askCredits) {
    /** The most of one thing an offer form may give or ask for. */
    public static final int MAX_COUNT = 6_400;
    /** The most credits an offer form may give or ask for. */
    public static final long MAX_CREDITS = 1_000_000_000_000L;
    /** Longest item id an offer may ask for. */
    public static final int MAX_ITEM = 128;

    /** Where the goods given come from. */
    public enum Source {
        /** The sender's main hand. */
        HAND,
        /** A line of the sender's vault, named by the intent's row. */
        VAULT,
        /** No goods: credits only. */
        NONE
    }

    public String encode() {
        return recipient + ';' + source.name().charAt(0) + ';' + count + ';' + credits + ';' + askItem + ';' + askCount
                + ';' + askCredits;
    }

    /** The form a text describes, or null when it is not a valid form. */
    public static OfferForm parse(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split(";", -1);
        if (parts.length != 7) return null;
        String recipient = parts[0].trim();
        if (recipient.isEmpty() || recipient.length() > HubRouter.MAX_RECIPIENT || recipient.indexOf(' ') >= 0)
            return null;
        Source source = switch (parts[1].trim().toUpperCase(Locale.ROOT)) {
            case "H" -> Source.HAND;
            case "V" -> Source.VAULT;
            case "N" -> Source.NONE;
            default -> null;
        };
        if (source == null) return null;
        long count = number(parts[2]);
        long credits = number(parts[3]);
        String askItem = parts[4].trim().toLowerCase(Locale.ROOT);
        long askCount = number(parts[5]);
        long askCredits = number(parts[6]);
        if (count < 0L || credits < 0L || askCount < 0L || askCredits < 0L) return null;
        if (count > MAX_COUNT || askCount > MAX_COUNT || credits > MAX_CREDITS || askCredits > MAX_CREDITS) return null;
        if ((source == Source.NONE) != (count == 0L)) return null;
        if (count == 0L && credits == 0L) return null;
        if (askItem.isEmpty() != (askCount == 0L)) return null;
        if (!askItem.isEmpty() && (askItem.length() > MAX_ITEM || askItem.indexOf(':') < 1 || askItem.indexOf(' ') >= 0
                || askItem.startsWith("#"))) return null;
        return new OfferForm(recipient, source, (int) count, credits, askItem, (int) askCount, askCredits);
    }

    private static long number(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException notANumber) {
            return -1L;
        }
    }
}
