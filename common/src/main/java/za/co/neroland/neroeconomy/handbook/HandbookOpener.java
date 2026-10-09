package za.co.neroland.neroeconomy.handbook;

/**
 * How the Trader's Handbook item reaches its screen without common code naming a client class:
 * each loader's client setup hands over the opener, and a dedicated server never does, so there
 * the item opens nothing. Opening the book reads nothing from and sends nothing to the server.
 */
public final class HandbookOpener {
    private static volatile Runnable opener = () -> { };

    private HandbookOpener() { }

    /** Called once from a loader's client setup. */
    public static void set(Runnable clientOpener) {
        if (clientOpener != null) opener = clientOpener;
    }

    /** Opens the book on this client; does nothing on a dedicated server. */
    public static void open() { opener.run(); }
}
