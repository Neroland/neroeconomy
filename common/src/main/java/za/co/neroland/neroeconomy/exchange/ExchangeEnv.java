package za.co.neroland.neroeconomy.exchange;

import za.co.neroland.neroeconomy.trade.AlertSink;

/**
 * Everything the exchange's trading rules need from outside the economy store, for one operation:
 * the configured numbers, the listed companies, where alerts go and the two rate limiters. The
 * desk and the practice book take one as the first argument of every operation and keep nothing
 * from it, so the very same code runs in the game (see {@code Investing.env}) and in plain JUnit.
 *
 * @param limiter         counts real exchange actions per account
 * @param practiceLimiter counts practice actions per account, separately
 */
public record ExchangeEnv(TradingTerms terms, StockRegistry registry, AlertSink alerts, ActionLimiter limiter,
        ActionLimiter practiceLimiter) {
    public ExchangeEnv {
        if (terms == null || limiter == null || practiceLimiter == null)
            throw new IllegalArgumentException("exchange environment");
        if (registry == null) registry = StockRegistry.UNAVAILABLE;
        if (alerts == null) alerts = AlertSink.NONE;
    }

    /** The same environment with other terms. */
    public ExchangeEnv with(TradingTerms next) {
        return new ExchangeEnv(next, registry, alerts, limiter, practiceLimiter);
    }

    /** The same environment with other listed companies. */
    public ExchangeEnv with(StockRegistry next) {
        return new ExchangeEnv(terms, next, alerts, limiter, practiceLimiter);
    }
}
