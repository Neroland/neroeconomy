package za.co.neroland.neroeconomy.api;

/**
 * Stable ledger-account identity. Only player accounts ship today; future group-wallet modules can
 * add another implementation without changing the ledger or market APIs.
 */
public interface AccountId {
    String kind();
    String value();

    default String stableKey() { return kind() + ':' + value(); }
}
