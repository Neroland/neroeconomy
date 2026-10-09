package za.co.neroland.neroeconomy.hub;

import java.util.Locale;

/**
 * The Jobs tab's "Post" form as one line of text: the kind of job, what it wants, how many, and
 * for a delivery where to: {@code C|D|B|P;target;quantity;destination}. A contract or delivery
 * wants an item id or a {@code #tag}; a bounty an entity type id; a player bounty ({@code P}) the
 * name of an online player, resolved on the server. A delivery goes {@code here} (where the poster
 * stands) or to {@code dimension|x|y|z}. Pure Java; the job rules check everything again.
 */
public record JobForm(Kind kind, String target, int quantity, String destination) {
    /** The most a job may ask for, as the command allows. */
    public static final int MAX_QUANTITY = 2_304;
    /** Longest target, as a job stores it. */
    public static final int MAX_TARGET = 64;
    public static final String HERE = "here";
    /** Furthest a typed destination may be from the world's centre on either axis. */
    public static final long MAX_COORDINATE = 30_000_000L;

    public enum Kind { CONTRACT, DELIVERY, BOUNTY, PLAYER_BOUNTY }

    public String encode() {
        char letter = switch (kind) {
            case CONTRACT -> 'C';
            case DELIVERY -> 'D';
            case BOUNTY -> 'B';
            default -> 'P';
        };
        return letter + ";" + target + ';' + quantity + ';' + destination;
    }

    /** Whether the delivery goes where the poster stands. */
    public boolean here() { return kind == Kind.DELIVERY && HERE.equals(destination); }

    /** The form a text describes, or null when it is not a valid form. */
    public static JobForm parse(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split(";", -1);
        if (parts.length != 4) return null;
        Kind kind = switch (parts[0].trim().toUpperCase(Locale.ROOT)) {
            case "C" -> Kind.CONTRACT;
            case "D" -> Kind.DELIVERY;
            case "B" -> Kind.BOUNTY;
            case "P" -> Kind.PLAYER_BOUNTY;
            default -> null;
        };
        if (kind == null) return null;
        String target = kind == Kind.PLAYER_BOUNTY ? parts[1].trim() : parts[1].trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty() || target.length() > MAX_TARGET || target.indexOf(' ') >= 0) return null;
        boolean tag = target.startsWith("#");
        switch (kind) {
            case CONTRACT, DELIVERY -> {
                String id = tag ? target.substring(1) : target;
                if (id.indexOf(':') < 1) return null;
            }
            case BOUNTY -> {
                if (tag || target.indexOf(':') < 1 || target.startsWith("player:")) return null;
            }
            default -> {
                if (tag || target.indexOf(':') >= 0) return null;
            }
        }
        long quantity;
        try {
            quantity = Long.parseLong(parts[2].trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
        if (quantity < 1L || quantity > MAX_QUANTITY) return null;
        String destination = parts[3].trim();
        if (kind == Kind.DELIVERY) {
            if (!HERE.equals(destination) && destination(destination) == null) return null;
        } else if (!destination.isEmpty()) {
            return null;
        }
        return new JobForm(kind, target, (int) quantity, destination);
    }

    /** A typed destination {@code dimension|x|y|z}. */
    public record Place(String dimension, int x, int y, int z) { }

    /** The typed destination, or null when it is "here" or not a valid place. */
    public static Place destination(String text) {
        if (text == null) return null;
        String[] parts = text.split("\\|", -1);
        if (parts.length != 4) return null;
        String dimension = parts[0].trim().toLowerCase(Locale.ROOT);
        if (dimension.indexOf(':') < 1 || dimension.length() > MAX_TARGET || dimension.indexOf(' ') >= 0) return null;
        try {
            long x = Long.parseLong(parts[1].trim());
            long y = Long.parseLong(parts[2].trim());
            long z = Long.parseLong(parts[3].trim());
            if (Math.abs(x) > MAX_COORDINATE || Math.abs(z) > MAX_COORDINATE || Math.abs(y) > 4_096L) return null;
            return new Place(dimension, (int) x, (int) y, (int) z);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
