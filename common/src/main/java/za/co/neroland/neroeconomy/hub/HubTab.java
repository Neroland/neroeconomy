package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tabs of the Economy Hub, in the order the tab bar shows them. Each tab names its wording by
 * lang key and its icon by item id, so the same list serves the server (which tab a block opens,
 * which tabs a player may see) and the client (what the bar draws). Pure Java.
 *
 * <p>Tabs that a later part of the release builds report {@link #built()} false and show a plain
 * "coming in this release" page until then; a new tab is one view class plus its lang keys.
 */
public enum HubTab {
    WALLET("neroeconomy:credit_chip", true),
    VAULT("minecraft:chest", true),
    MARKET("neroeconomy:trading_terminal", true),
    ORDERS("minecraft:writable_book", true),
    INVEST("neroeconomy:exchange_terminal", true),
    TRADE("minecraft:emerald", true),
    JOBS("minecraft:iron_pickaxe", true),
    AUCTIONS("minecraft:bell", true),
    SHOP("neroeconomy:player_shop", true),
    ADMIN("minecraft:command_block", true);

    private final String icon;
    private final boolean built;

    HubTab(String icon, boolean built) {
        this.icon = icon;
        this.built = built;
    }

    /** The item whose icon stands for the tab in the bar. */
    public String icon() { return icon; }

    /** Whether this build has the tab's page; otherwise it shows the "coming in this release" page. */
    public boolean built() { return built; }

    /** Whether only a level-3 operator sees the tab. */
    public boolean adminOnly() { return this == ADMIN; }

    /** Whether a viewer sees the tab at all. */
    public boolean visible(boolean admin) { return admin || !adminOnly(); }

    /** The id used in commands and lang keys: {@code wallet}, {@code orders}, ... */
    public String id() { return name().toLowerCase(Locale.ROOT); }

    /** Lang key of the tab's name. */
    public String key() { return "gui.neroeconomy.hub.tab." + id(); }

    /** Lang key of the tab button's tooltip. */
    public String tooltipKey() { return key() + ".tooltip"; }

    /** Lang key of the "?" button's tooltip on this tab. */
    public String helpKey() { return "gui.neroeconomy.hub.help." + id(); }

    /** The tab a command or stored value names; null for anything else. */
    public static HubTab parse(String value) {
        if (value == null) return null;
        String wanted = value.trim().toLowerCase(Locale.ROOT);
        for (HubTab tab : values()) if (tab.id().equals(wanted)) return tab;
        return null;
    }

    /** The tab at {@code ordinal}; null when it is out of range. */
    public static HubTab byOrdinal(int ordinal) {
        HubTab[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : null;
    }

    /** The tabs a viewer sees, in bar order. */
    public static List<HubTab> visibleTabs(boolean admin) {
        List<HubTab> out = new ArrayList<>();
        for (HubTab tab : values()) if (tab.visible(admin)) out.add(tab);
        return out;
    }
}
