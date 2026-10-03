package za.co.neroland.neroeconomy.config;

import za.co.neroland.nerolandcore.config.ConfigManager;
import za.co.neroland.nerolandcore.config.ConfigSchema;
import za.co.neroland.nerolandcore.config.ConfigValue;
import za.co.neroland.neroeconomy.NeroEconomyCommon;

/** Server-authoritative economy policy, persisted by Neroland Core's config framework. */
public final class EconomyConfig {
    public static final ConfigSchema SCHEMA = ConfigSchema.create(NeroEconomyCommon.MOD_ID,
            "NeroEconomy server-authoritative configuration.");

    /** A starting balance near {@code Long.MAX_VALUE} would leave no room for any deposit at all. */
    public static final long MAX_STARTING_BALANCE = 1_000_000L;

    public static final ConfigValue<Long> STARTING_BALANCE = SCHEMA.longRange("startingBalance", 100L, 0L,
            MAX_STARTING_BALANCE, true,
            "Credits paid from the Reserve when a player account is first opened; less, or nothing, "
                    + "when the Reserve cannot cover it.");
    public static final ConfigValue<Integer> STARTING_BALANCE_VEST_MINUTES = SCHEMA.intRange(
            "startingBalanceVestMinutes", 30, 0, 10080, true,
            "Play time, in minutes, before a starting balance can be paid, offered or bid to another "
                    + "player. Until then it only pays fees and system counterparties. 0 turns the lock off.");
    public static final ConfigValue<Integer> TRANSACTION_RETENTION_DAYS = SCHEMA.intRange(
            "transactionRetentionDays", 90, 0, 3650, true, "Audit retention in days; 0 keeps no records.");
    public static final ConfigValue<Integer> MAX_TRANSACTION_RECORDS = SCHEMA.intRange(
            "maxTransactionRecords", 10000, 0, 1000000, true, "Hard server-wide audit record bound.");
    public static final ConfigValue<Integer> HISTORY_LIMIT_PER_PLAYER = SCHEMA.intRange(
            "historyLimitPerPlayer", 100, 1, 1000, true, "Maximum records returned to one player.");
    public static final ConfigValue<Boolean> ADMIN_MUTATIONS_ENABLED = SCHEMA.bool(
            "adminMutationsEnabled", true, true,
            "Allow the permission-level-3 grant/take/set commands, accepting a fresh start for a "
                    + "frozen economy, and the admin gallery.");
    public static final ConfigValue<Double> MAX_PRICE_MULTIPLIER = SCHEMA.doubleRange(
            "maxPriceMultiplier", 4.0D, 1.0D, 100.0D, true,
            "Maximum market-shock multiplier after all modifiers.");
    public static final ConfigValue<Double> MIN_PRICE_MULTIPLIER = SCHEMA.doubleRange(
            "minPriceMultiplier", 0.25D, 0.01D, 1.0D, true,
            "Minimum market-shock multiplier after all modifiers.");
    public static final ConfigValue<Double> EXECUTION_PRICE_TOLERANCE = SCHEMA.doubleRange(
            "executionPriceTolerance", 0.05D, 0.0D, 1.0D, true,
            "Symmetric quote-to-execution tolerance kept for API callers of executionPriceAccepted. "
                    + "Purchases ignore it: a buyer is never charged above the confirmed price.");
    public static final ConfigValue<Integer> DEFAULT_LISTING_DURATION_HOURS = SCHEMA.intRange(
            "defaultListingDurationHours", 48, 1, 720, true, "Default market offer lifetime.");
    public static final ConfigValue<Integer> AUCTION_ANTI_SNIPE_SECONDS = SCHEMA.intRange(
            "auctionAntiSnipeSeconds", 60, 0, 3600, true, "Late-bid auction extension window.");
    public static final ConfigValue<Integer> AUCTION_MIN_INCREMENT_PERCENT = SCHEMA.intRange(
            "auctionMinIncrementPercent", 5, 0, 100, true,
            "A bid must beat the standing bid by at least this percentage, and always by 1 credit.");
    public static final ConfigValue<Integer> JOB_CLAIM_TIMEOUT_MINUTES = SCHEMA.intRange(
            "jobClaimTimeoutMinutes", 120, 0, 10080, true,
            "A claimed job reopens after this long without fulfilment; 0 never releases a claim.");
    public static final ConfigValue<Integer> SHOP_LISTING_MAX_IDLE_DAYS = SCHEMA.intRange(
            "shopListingMaxIdleDays", 90, 0, 3650, true,
            "A shop listing with no owner interaction and no sale for this long expires into the "
                    + "owner's vault; 0 never expires one.");
    public static final ConfigValue<Integer> MAX_LISTINGS_PER_PLAYER = SCHEMA.intRange(
            "maxListingsPerPlayer", 64, 0, 1024, true,
            "Market Board sell listings one account may have open; 0 refuses new ones. "
                    + "Player Shop listings are bounded by placed shops and are not counted.");
    public static final ConfigValue<Integer> MAX_BUY_OFFERS_PER_PLAYER = SCHEMA.intRange(
            "maxBuyOffersPerPlayer", 32, 0, 1024, true,
            "Standing buy offers one account may have open; 0 refuses new ones.");
    public static final ConfigValue<Integer> MAX_JOBS_PER_PLAYER = SCHEMA.intRange(
            "maxJobsPerPlayer", 16, 0, 256, true,
            "Unsettled contracts, deliveries and bounties one account may have posted; 0 refuses new ones.");
    public static final ConfigValue<Integer> MAX_AUCTIONS_PER_PLAYER = SCHEMA.intRange(
            "maxAuctionsPerPlayer", 8, 0, 256, true,
            "Running auctions one account may have; 0 refuses new ones.");
    public static final ConfigValue<Integer> MAX_CLAIM_STACKS_PER_PLAYER = SCHEMA.intRange(
            "maxClaimStacksPerPlayer", 256, 16, 4096, true,
            "Stacks one account's vault may hold before purchases, bids, fills and cancels that "
                    + "would deliver more to it are refused. The smaller of this and vaultStacks applies, "
                    + "so it only matters when set below vaultStacks. Refunds and expiry always book.");
    public static final ConfigValue<Integer> VAULT_STACKS = SCHEMA.intRange(
            "vaultStacks", 54, 9, 54, true,
            "Stacks a player may keep in their vault, in whole rows of nine (a value in between is "
                    + "rounded down). Goods bought while away, refunds and expired listings are delivered "
                    + "there. Refunds and expiry always book, even past this; deposits and purchases to "
                    + "the vault are then refused until it is back under it.");
    public static final ConfigValue<Integer> SIM_STEP_MINUTES = SCHEMA.intRange(
            "simStepMinutes", 10, 1, 1440, true,
            "Length of one simulation step, in minutes of economy time. The economy advances in "
                    + "these steps whether or not anyone is online; it never follows game ticks, the "
                    + "day/night cycle, /tick, /time or sleeping.");
    public static final ConfigValue<Integer> SIM_CATCH_UP_MAX_HOURS = SCHEMA.intRange(
            "simCatchUpMaxHours", 72, 1, 720, true,
            "After a pause or a shutdown the missed steps are replayed, a few per tick. At most this "
                    + "many hours' worth are replayed; anything older is skipped.");
    public static final ConfigValue<Boolean> AUTONOMOUS_ECONOMY = SCHEMA.bool(
            "autonomousEconomy", true, true,
            "Run the parts of the simulation that act in the market on their own (Merchant Houses, "
                    + "the exchange, system jobs). false leaves only housekeeping: the Reserve top-up, "
                    + "the day roll-over and expiry.");
    public static final ConfigValue<Boolean> DEBUG_FAST_SIM = SCHEMA.bool(
            "debugFastSim", false, true,
            "Development only: one simulation step per second of real time. Ignored, with a "
                    + "warning in the log, outside a development environment.");
    public static final ConfigValue<Integer> MAX_STACK_DATA_BYTES = SCHEMA.intRange(
            "maxStackDataBytes", 8192, 256, 1048576, true,
            "Largest encoded size, in bytes of NBT, of a stack that may be listed, auctioned or "
                    + "put in a shop.");
    public static final ConfigValue<Long> CREDIT_CHIP_VALUE = SCHEMA.longRange(
            "creditChipValue", 100L, 1L, 1_000_000_000L, true,
            "Credits the Reserve pays for an account's first Credit Chip of the economy day. Each "
                    + "further chip that day pays less: 80, 60, 40, then 20 percent of this.");
    public static final ConfigValue<Integer> CREDIT_CHIP_DAILY_LIMIT = SCHEMA.intRange(
            "creditChipDailyLimit", 5, 0, 1000, true,
            "Credit chips one account may redeem per economy day; 0 disables redemption.");
    public static final ConfigValue<Boolean> SYSTEM_JOBS_ENABLED = SCHEMA.bool(
            "systemJobsEnabled", true, true,
            "Allow server-funded jobs, whose payouts come out of the Reserve; false refuses every new one.");
    public static final ConfigValue<Long> SYSTEM_JOB_DAILY_PAYOUT_CAP = SCHEMA.longRange(
            "systemJobDailyPayoutCap", 5000L, 0L, 1_000_000_000L, true,
            "Total payout of the server-funded jobs that may be posted per economy day. The payout "
                    + "is moved from the Reserve into escrow when the job is posted.");
    public static final ConfigValue<Long> RESERVE_INITIAL = SCHEMA.longRange(
            "reserveInitial", 250_000L, 0L, 1_000_000_000_000L, true,
            "Credits minted into the Reserve once, when a world's economy is first created or migrated.");
    public static final ConfigValue<Long> RESERVE_DAILY_MINT = SCHEMA.longRange(
            "reserveDailyMint", 5_000L, 0L, 1_000_000_000L, true,
            "Credits minted into the Reserve once per economy day; skipped while the Reserve is at "
                    + "or above reserveCap. At most three missed days are made up.");
    public static final ConfigValue<Long> RESERVE_CAP = SCHEMA.longRange(
            "reserveCap", 2_000_000L, 0L, 1_000_000_000_000L, true,
            "The daily top-up stops when the Reserve holds this much; fees may still take it higher.");
    public static final ConfigValue<Long> NPC_DAILY_BUDGET = SCHEMA.longRange(
            "npcDailyBudget", 20_000L, 0L, 1_000_000_000L, true,
            "Most the Merchant Houses may pay out to players per economy day.");
    public static final ConfigValue<Long> EXCHANGE_DAILY_BUDGET = SCHEMA.longRange(
            "exchangeDailyBudget", 15_000L, 0L, 1_000_000_000L, true,
            "Most the exchange may pay in gains, dividends and newly reserved bond interest per economy day.");
    public static final ConfigValue<Long> SIBLING_DAILY_BUDGET = SCHEMA.longRange(
            "siblingDailyBudget", 5_000L, 0L, 1_000_000_000L, true,
            "Most that deposits made by other mods without a reason of their own may draw from the "
                    + "Reserve per economy day; a deposit past it is refused.");
    public static final ConfigValue<Double> MARKET_FEE_PERCENT = SCHEMA.doubleRange(
            "marketFeePercent", 2.0D, 0.0D, 50.0D, true,
            "Fee on the seller's proceeds of every Market Board sale, shop sale and filled buy offer, "
                    + "rounded up and at least 1 credit; 0 charges nothing. Paid to the Reserve.");
    public static final ConfigValue<Long> LISTING_FEE = SCHEMA.longRange(
            "listingFee", 1L, 0L, 1000L, true,
            "Flat fee for posting a Market Board listing or a buy offer; not refunded. Paid to the Reserve.");
    public static final ConfigValue<Double> AUCTION_FEE_PERCENT = SCHEMA.doubleRange(
            "auctionFeePercent", 5.0D, 0.0D, 50.0D, true,
            "Fee on the winning bid, taken from the seller's proceeds, rounded up and at least 1 "
                    + "credit; 0 charges nothing. Paid to the Reserve.");
    public static final ConfigValue<Double> EXCHANGE_FEE_PERCENT = SCHEMA.doubleRange(
            "exchangeFeePercent", 1.0D, 0.0D, 50.0D, true,
            "Fee on every share buy and sell. Paid to the Reserve.");
    public static final ConfigValue<Double> TRADE_TAX_PERCENT = SCHEMA.doubleRange(
            "tradeTaxPercent", 0.0D, 0.0D, 50.0D, true,
            "Tax on the credits side of a direct trade or an accepted offer, rounded up and at least "
                    + "1 credit; 0 charges nothing. Paid to the Reserve.");
    public static final ConfigValue<Integer> TRADE_RANGE_BLOCKS = SCHEMA.intRange(
            "tradeRangeBlocks", 16, 2, 64, true,
            "How close two players must be, in blocks and in the same dimension, to trade directly "
                    + "with each other. Two players who are each at an economy terminal can trade from "
                    + "any distance.");
    public static final ConfigValue<Integer> OFFER_DURATION_HOURS = SCHEMA.intRange(
            "offerDurationHours", 48, 1, 720, true,
            "Hours an offer sent to another player stays open. An unanswered offer then expires and "
                    + "everything in it goes back to the sender.");
    public static final ConfigValue<Integer> MAX_OFFERS_PER_PLAYER = SCHEMA.intRange(
            "maxOffersPerPlayer", 16, 0, 256, true,
            "Open offers one account may have sent at a time; 0 refuses new ones. Separately, an "
                    + "account can have at most 64 offers waiting for its answer.");
    public static final ConfigValue<Integer> MAX_RULES_PER_PLAYER = SCHEMA.intRange(
            "maxRulesPerPlayer", 8, 0, 64, true,
            "Auto-buy and auto-sell rules one account may have; 0 refuses new ones.");
    public static final ConfigValue<Long> RULE_DAILY_VALUE_CAP = SCHEMA.longRange(
            "ruleDailyValueCap", 10_000L, 0L, 1_000_000_000L, true,
            "Credits' worth of trades all of one account's rules together may fill per economy day. "
                    + "Reaching it pauses that account's rules until the next day.");
    public static final ConfigValue<Boolean> PLAYER_BOUNTIES_ENABLED = SCHEMA.bool(
            "playerBountiesEnabled", false, true,
            "Allow bounties on a named player. Posting one also requires permission level 2.");
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
