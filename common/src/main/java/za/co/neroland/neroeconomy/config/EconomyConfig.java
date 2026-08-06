package za.co.neroland.neroeconomy.config;

import za.co.neroland.nerolandcore.config.ConfigManager;
import za.co.neroland.nerolandcore.config.ConfigSchema;
import za.co.neroland.nerolandcore.config.ConfigValue;
import za.co.neroland.neroeconomy.NeroEconomyCommon;

/** Server-authoritative economy policy, persisted by Neroland Core's config framework. */
public final class EconomyConfig {
    public static final ConfigSchema SCHEMA = ConfigSchema.create(NeroEconomyCommon.MOD_ID,
            "NeroEconomy server-authoritative configuration.");

    public static final ConfigValue<Long> STARTING_BALANCE = SCHEMA.longRange("startingBalance", 100L, 0L,
            Long.MAX_VALUE, true, "Credits minted once when a player account is first opened.");
    public static final ConfigValue<Integer> TRANSACTION_RETENTION_DAYS = SCHEMA.intRange(
            "transactionRetentionDays", 90, 0, 3650, true, "Audit retention in days; 0 keeps no records.");
    public static final ConfigValue<Integer> MAX_TRANSACTION_RECORDS = SCHEMA.intRange(
            "maxTransactionRecords", 10000, 0, 1000000, true, "Hard server-wide audit record bound.");
    public static final ConfigValue<Integer> HISTORY_LIMIT_PER_PLAYER = SCHEMA.intRange(
            "historyLimitPerPlayer", 100, 1, 1000, true, "Maximum records returned to one player.");
    public static final ConfigValue<Boolean> ADMIN_MUTATIONS_ENABLED = SCHEMA.bool(
            "adminMutationsEnabled", true, true, "Allow permission-level-3 grant/take/set commands.");
    public static final ConfigValue<Double> MAX_PRICE_MULTIPLIER = SCHEMA.doubleRange(
            "maxPriceMultiplier", 4.0D, 1.0D, 100.0D, true,
            "Maximum market-shock multiplier after all modifiers.");
    public static final ConfigValue<Double> MIN_PRICE_MULTIPLIER = SCHEMA.doubleRange(
            "minPriceMultiplier", 0.25D, 0.01D, 1.0D, true,
            "Minimum market-shock multiplier after all modifiers.");
    public static final ConfigValue<Double> EXECUTION_PRICE_TOLERANCE = SCHEMA.doubleRange(
            "executionPriceTolerance", 0.05D, 0.0D, 1.0D, true,
            "Maximum relative price movement accepted between quote and execution.");
    public static final ConfigValue<Integer> DEFAULT_LISTING_DURATION_HOURS = SCHEMA.intRange(
            "defaultListingDurationHours", 48, 1, 720, true, "Default market offer lifetime.");
    public static final ConfigValue<Integer> AUCTION_ANTI_SNIPE_SECONDS = SCHEMA.intRange(
            "auctionAntiSnipeSeconds", 60, 0, 3600, true, "Late-bid auction extension window.");
    public static final ConfigValue<Long> CREDIT_CHIP_VALUE = SCHEMA.longRange(
            "creditChipValue", 100L, 1L, 1_000_000_000L, true, "Credits minted by one credit chip.");
    public static final ConfigValue<Boolean> PLAYER_BOUNTIES_ENABLED = SCHEMA.bool(
            "playerBountiesEnabled", false, true, "Allow permission-gated player-target bounties.");
    public static final ConfigValue<Boolean> REMOTE_TRADE_ENABLED = SCHEMA.bool(
            "remoteTradeEnabled", true, true, "Allow validated NeroLink trade actions.");
    public static final ConfigValue<Boolean> TELEMETRY_ENABLED = SCHEMA.bool(
            "telemetryEnabled", true, false,
            "Send anonymous NeroEconomy-only crash reports; false opts out. No player data is sent.");

    private EconomyConfig() {
    }

    public static void init() {
        ConfigManager.register(SCHEMA);
    }
}
