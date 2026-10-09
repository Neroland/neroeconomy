package za.co.neroland.neroeconomy.link;

import za.co.neroland.nerolandcore.link.LinkActionResult;

/**
 * The checks every companion-app action passes before it reaches a store, in the order they run,
 * as one pure function so the whole matrix is unit tested: the action is known; remote actions
 * are switched on ({@code remoteTradeEnabled}); the economy is not frozen; the player is within
 * their shared {@code VALUE} budget and the action's own limit; a {@code requestId} was sent; the
 * player is in game when the action touches a live inventory; and the operator's market switch is
 * open when the action trades.
 */
final class LinkGate {
    /** Why an action was stopped before it ran; {@link #NONE} when it may run. */
    enum Refusal { NONE, UNKNOWN_ACTION, DISABLED, FROZEN, TOO_MANY, TOO_OFTEN, NO_REQUEST, OFFLINE, MARKET_CLOSED }

    /** What is known about one action request. */
    record Request(String action, boolean remoteEnabled, boolean frozen, boolean withinBudget,
            boolean withinActionLimit, boolean hasRequestId, boolean online, boolean marketClosed) { }

    private LinkGate() { }

    /** The checks that need no player state: run before the work is handed to the server thread. */
    static Refusal early(String action, boolean remoteEnabled, boolean frozen) {
        if (action == null || !LinkActions.ALL.contains(action)) return Refusal.UNKNOWN_ACTION;
        if (!remoteEnabled) return Refusal.DISABLED;
        return frozen ? Refusal.FROZEN : Refusal.NONE;
    }

    /** Every check, in order. Budgets are only consulted when the earlier checks pass. */
    static Refusal check(Request request) {
        Refusal early = early(request.action(), request.remoteEnabled(), request.frozen());
        if (early != Refusal.NONE) return early;
        if (!request.withinBudget()) return Refusal.TOO_MANY;
        if (!request.withinActionLimit()) return Refusal.TOO_OFTEN;
        if (!request.hasRequestId()) return Refusal.NO_REQUEST;
        if (!request.online() && !LinkActions.OFFLINE.contains(request.action())) return Refusal.OFFLINE;
        if (request.marketClosed() && LinkActions.TRADING.contains(request.action())) return Refusal.MARKET_CLOSED;
        return Refusal.NONE;
    }

    /** The error the app is sent for a refusal. */
    static LinkActionResult result(Refusal refusal) {
        return switch (refusal) {
            case UNKNOWN_ACTION -> LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    LinkText.get("link.neroeconomy.unknown_action"));
            case DISABLED -> LinkActionResult.error(LinkActionResult.Error.ACTION_DISABLED, LinkPayloads.TRADE_DISABLED);
            case FROZEN -> LinkActionResult.error(LinkActionResult.Error.ACTION_DISABLED, LinkPayloads.ECONOMY_FROZEN);
            case TOO_MANY -> LinkActionResult.error(LinkActionResult.Error.VALIDATION, LinkPayloads.TOO_MANY_REQUESTS);
            case TOO_OFTEN -> LinkActionResult.error(LinkActionResult.Error.VALIDATION,
                    LinkText.get("link.neroeconomy.action_too_often"));
            case NO_REQUEST -> LinkActionResult.error(LinkActionResult.Error.VALIDATION, LinkPayloads.REQUEST_ID_REQUIRED);
            case OFFLINE -> LinkActionResult.error(LinkActionResult.Error.PLAYER_OFFLINE_REQUIRED,
                    LinkText.get("link.neroeconomy.needs_player"));
            case MARKET_CLOSED -> LinkActionResult.error(LinkActionResult.Error.ACTION_DISABLED, LinkPayloads.MARKET_CLOSED);
            case NONE -> null;
        };
    }
}
