package za.co.neroland.neroeconomy.trade;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.link.EconomyAlerts;

/**
 * Where the pure trading rules report that an account should be told something while its owner may
 * be away: an offer arrived, a rule stopped. The rules only name a kind from the closed catalogue
 * ({@link EconomyAlerts.Kind}); they can pass no text, so nothing about the other party, an amount
 * or an item can reach an alert. The game forwards to {@link EconomyAlerts}; a test collects.
 */
@FunctionalInterface
public interface AlertSink {
    /** Alerts go nowhere. */
    AlertSink NONE = (account, kind) -> { };

    void raise(AccountId account, EconomyAlerts.Kind kind);
}
