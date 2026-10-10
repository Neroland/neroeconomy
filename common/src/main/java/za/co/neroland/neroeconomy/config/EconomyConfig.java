package za.co.neroland.neroeconomy.config;

import za.co.neroland.nerolandcore.config.ConfigManager;
import za.co.neroland.nerolandcore.config.ConfigSchema;
import za.co.neroland.nerolandcore.config.ConfigValue;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.exchange.ExchangeSettings;
import za.co.neroland.neroeconomy.exchange.TradingTerms;

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
    public static final ConfigValue<Double> COMMODITY_SPREAD_PERCENT = SCHEMA.doubleRange(
            "commoditySpreadPercent", 10.0D, 2.0D, 75.0D, true,
            "How far above a commodity's reference price the Merchant Houses sell it and how far "
                    + "below they buy it, as a percentage, for a commodity whose data file gives no "
                    + "spread_percent. Applies from the next data-pack reload.");
    public static final ConfigValue<Integer> ARBITRAGE_GUARD_DEPTH = SCHEMA.intRange(
            "arbitrageGuardDepth", 3, 1, 6, true,
            "How many recipes long a chain the arbitrage guard follows when it checks that goods "
                    + "bought from the Merchant Houses cannot be crafted and sold back to them at a profit.");
    public static final ConfigValue<Double> REFERENCE_DRIFT_STEP_PERCENT = SCHEMA.doubleRange(
            "referenceDriftStepPercent", 1.0D, 0.0D, 10.0D, true,
            "The most a commodity's reference price may move toward what players pay each other in "
                    + "one simulation step, as a percentage of the reference price in its data file. "
                    + "0 keeps every reference price where the data puts it.");
    public static final ConfigValue<Double> REFERENCE_BAND_MIN_PERCENT = SCHEMA.doubleRange(
            "referenceBandMinPercent", 50.0D, 1.0D, 100.0D, true,
            "The lowest a commodity's reference price may drift to, as a percentage of the reference "
                    + "price in its data file.");
    public static final ConfigValue<Double> REFERENCE_BAND_MAX_PERCENT = SCHEMA.doubleRange(
            "referenceBandMaxPercent", 200.0D, 100.0D, 1000.0D, true,
            "The highest a commodity's reference price may drift to, as a percentage of the reference "
                    + "price in its data file.");
    public static final ConfigValue<Long> REFERENCE_MIN_TRADE_VALUE = SCHEMA.longRange(
            "referenceMinTradeValue", 16L, 0L, 1_000_000_000L, true,
            "A sale between two players worth less than this many credits does not count toward "
                    + "reference prices.");
    public static final ConfigValue<Long> REFERENCE_ACCOUNT_DAILY_CAP = SCHEMA.longRange(
            "referenceAccountDailyCap", 2_000L, 0L, 1_000_000_000L, true,
            "Credits of one player account's sales and purchases with other players that count toward "
                    + "reference prices per economy day, so a few accounts trading with each other cannot "
                    + "move a price far. 0 counts none.");
    public static final ConfigValue<Integer> REFERENCE_MIN_PAIRS = SCHEMA.intRange(
            "referenceMinPairs", 3, 1, 64, true,
            "How many different pairs of players must have traded a commodity with each other since the "
                    + "last simulation step before its reference price moves at all, so two accounts trading "
                    + "with each other cannot move it. No one pair counts for more than the pair that ranks "
                    + "this many places from the top.");
    public static final ConfigValue<Double> REFERENCE_DRIFT_DAY_PERCENT = SCHEMA.doubleRange(
            "referenceDriftDayPercent", 10.0D, 0.0D, 100.0D, true,
            "The most a commodity's reference price may move in one economy day, up or down from where "
                    + "the day began, as a percentage of the reference price in its data file. 0 keeps "
                    + "every reference price where the day began.");
    public static final ConfigValue<Integer> NPC_AUCTIONS_PER_DAY = SCHEMA.intRange(
            "npcAuctionsPerDay", 4, 0, 64, true,
            "Auctions the Merchant Houses start from their own stock per economy day, server-wide: "
                    + "a few rare or valuable goods, or a bundle of a staple. 0 switches them off.");
    public static final ConfigValue<Integer> NPC_AUCTION_HOURS = SCHEMA.intRange(
            "npcAuctionHours", 24, 1, 168, true,
            "How long, in hours of economy time, an auction a Merchant House starts runs.");
    public static final ConfigValue<Double> HOUSE_JOB_PREMIUM_PERCENT = SCHEMA.doubleRange(
            "houseJobPremiumPercent", 15.0D, 0.0D, 100.0D, true,
            "What a Merchant House's contract or delivery job pays above the House's current buying "
                    + "price for the goods, as a percentage. The payout comes from the Reserve within "
                    + "systemJobDailyPayoutCap.");
    public static final ConfigValue<Integer> HOUSE_JOBS_PER_HOUSE = SCHEMA.intRange(
            "houseJobsPerHouse", 2, 0, 8, true,
            "Open contract and delivery jobs one Merchant House may have for goods it is short of; "
                    + "0 switches House jobs off.");
    public static final ConfigValue<Integer> HOUSE_JOB_HOURS = SCHEMA.intRange(
            "houseJobHours", 24, 1, 168, true,
            "How long, in hours of economy time, a Merchant House's job or a server bounty stays open. "
                    + "An expired one returns its payout to the Reserve.");
    public static final ConfigValue<Integer> SYSTEM_BOUNTIES_PER_DAY = SCHEMA.intRange(
            "systemBountiesPerDay", 2, 0, 32, true,
            "Server-funded bounties on hostile mobs (listed in the bounty data files) posted per "
                    + "economy day, and the most open at once. Mob farms complete any kill bounty, so "
                    + "keep this small; 0 switches them off.");
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
    public static final ConfigValue<Long> CREDIT_CHIP_DAILY_BUDGET = SCHEMA.longRange(
            "creditChipDailyBudget", 3_000L, 0L, 1_000_000_000L, true,
            "Credits the Reserve pays for Credit Chips per economy day, all accounts together, on top of "
                    + "creditChipDailyLimit per account. A chip refused because it is spent is kept. 0 stops "
                    + "chip payouts.");
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
            "Most that other mods together may draw from the Reserve per economy day: payouts through "
                    + "the cross-mod API and deposits made without a reason of their own. A payout past it is refused.");
    public static final ConfigValue<Long> SIBLING_CALLER_DAILY_BUDGET = SCHEMA.longRange(
            "siblingCallerDailyBudget", 2_500L, 0L, 1_000_000_000L, true,
            "Most one other mod may pay out through the cross-mod API per economy day, unless "
                    + "siblingCallerBudgets names it. siblingDailyBudget still caps all of them together.");
    public static final ConfigValue<String> SIBLING_CALLER_BUDGETS = SCHEMA.string(
            "siblingCallerBudgets", "", true,
            "Per-mod daily payout limits that replace siblingCallerDailyBudget, as a comma-separated list "
                    + "of modid=amount, for example neroquests=3000,nerocolonies=1500.");
    public static final ConfigValue<Long> SIBLING_PER_PLAYER_DAILY_CAP = SCHEMA.longRange(
            "siblingPerPlayerDailyCap", 2_000L, 0L, 1_000_000_000L, true,
            "Most all other mods together may pay one player through the cross-mod API per economy day; "
                    + "0 turns the cap off and keeps no per-player count.");
    public static final ConfigValue<Long> SIBLING_MAX_SINGLE_PAYOUT = SCHEMA.longRange(
            "siblingMaxSinglePayout", 1_000L, 1L, 1_000_000_000L, true,
            "Largest single payout another mod may make through the cross-mod API.");
    public static final ConfigValue<Double> SIBLING_TRANSFER_FEE_PERCENT = SCHEMA.doubleRange(
            "siblingTransferFeePercent", 5.0D, 0.0D, 50.0D, true,
            "Fee on a player-to-player transfer another mod makes through the cross-mod API, taken from "
                    + "what the payee receives, rounded up and at least 1 credit; 0 charges nothing. Paid to the Reserve.");
    public static final ConfigValue<Long> RESERVE_ALERT_LOW = SCHEMA.longRange(
            "reserveAlertLow", 25_000L, 0L, 1_000_000_000_000L, true,
            "Reserve level other mods are told about through Core (neroeconomy:reserve): once when it "
                    + "falls below this, once when it recovers.");
    public static final ConfigValue<String> SECTOR_BANDS = SCHEMA.string(
            "sectorBands", "5,10", true,
            "Percent moves from a sector's day open that other mods are told about through Core "
                    + "(neroeconomy:sector_index), as a comma-separated list, for example 5,10.");
    public static final ConfigValue<String> SHOCK_SOURCES = SCHEMA.string(
            "shockSources", "neroevents", true,
            "Mods whose market-shock requests through Core (<modid>:market_shock_request) are applied, as a "
                    + "comma-separated list of mod ids. Empty hears none.");
    public static final ConfigValue<Integer> SHOCK_MAX_MINUTES = SCHEMA.intRange(
            "shockMaxMinutes", 1440, 1, 43200, true,
            "Longest market shock another mod may request, in economy-minutes.");
    public static final ConfigValue<Integer> MAX_ACTIVE_SHOCKS = SCHEMA.intRange(
            "maxActiveShocks", 64, 0, 4096, true,
            "Most market shocks requested by other mods that may be active at once.");
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
            "exchangeFeePercent", TradingTerms.DEFAULT_FEE_PERCENT, 0.0D, 50.0D, true,
            "Fee on every share and index purchase and sale, rounded up and at least 1 credit; 0 "
                    + "charges nothing. Savings Bonds carry no fee. Paid to the Reserve.");
    public static final ConfigValue<Boolean> EXCHANGE_ENABLED = SCHEMA.bool(
            "exchangeEnabled", true, true,
            "Run the exchange: the prices of the listed companies move once per simulation step, "
                    + "and shares, the index fund and Savings Bonds can be bought and sold. false leaves "
                    + "every price where it is and refuses every purchase and sale; a Savings Bond can "
                    + "still be cashed in and still pays out at maturity. autonomousEconomy=false stops "
                    + "the prices and the dividends, but not trading.");
    public static final ConfigValue<Double> EXCHANGE_STEP_LIMIT_STEADY_PERCENT = SCHEMA.doubleRange(
            "exchangeStepLimitSteadyPercent", ExchangeSettings.DEFAULT_STEP_LIMIT_STEADY_PERCENT, 0.1D, 50.0D, true,
            "Most a steady share's price may move in one simulation step, as a percentage.");
    public static final ConfigValue<Double> EXCHANGE_STEP_LIMIT_RISKY_PERCENT = SCHEMA.doubleRange(
            "exchangeStepLimitRiskyPercent", ExchangeSettings.DEFAULT_STEP_LIMIT_RISKY_PERCENT, 0.1D, 50.0D, true,
            "Most a risky share's price may move in one simulation step, as a percentage.");
    public static final ConfigValue<Double> EXCHANGE_DAY_LIMIT_STEADY_PERCENT = SCHEMA.doubleRange(
            "exchangeDayLimitSteadyPercent", ExchangeSettings.DEFAULT_DAY_LIMIT_STEADY_PERCENT, 0.5D, 90.0D, true,
            "Most a steady share's price may be above or below its price at the start of the "
                    + "economy day, as a percentage. A share that gets there is halted.");
    public static final ConfigValue<Double> EXCHANGE_DAY_LIMIT_RISKY_PERCENT = SCHEMA.doubleRange(
            "exchangeDayLimitRiskyPercent", ExchangeSettings.DEFAULT_DAY_LIMIT_RISKY_PERCENT, 0.5D, 90.0D, true,
            "Most a risky share's price may be above or below its price at the start of the "
                    + "economy day, as a percentage. A share that gets there is halted.");
    public static final ConfigValue<Integer> EXCHANGE_HALT_STEPS = SCHEMA.intRange(
            "exchangeHaltSteps", ExchangeSettings.DEFAULT_HALT_STEPS, 0, 1008, true,
            "Simulation steps a share is halted for after reaching its daily limit: its price stands "
                    + "still and it cannot be bought or sold. 6 steps are one hour at the default "
                    + "step length. 0 never halts.");
    public static final ConfigValue<Double> EXCHANGE_REVERSION_PERCENT = SCHEMA.doubleRange(
            "exchangeReversionPercent", ExchangeSettings.DEFAULT_REVERSION_PERCENT, 0.0D, 50.0D, true,
            "How much of the gap between a share's price and its anchor closes in one simulation "
                    + "step, as a percentage. Higher keeps prices closer to their trend.");
    public static final ConfigValue<Double> EXCHANGE_VOLUME_NUDGE_CAP_PERCENT = SCHEMA.doubleRange(
            "exchangeVolumeNudgeCapPercent", ExchangeSettings.DEFAULT_VOLUME_NUDGE_CAP_PERCENT, 0.0D, 5.0D, true,
            "Most that trade in a company's linked items may move its share price in one "
                    + "simulation step, up or down, as a percentage. 0 turns the link off.");
    public static final ConfigValue<Long> EXCHANGE_VOLUME_SCALE = SCHEMA.longRange(
            "exchangeVolumeScale", ExchangeSettings.DEFAULT_VOLUME_SCALE, 1L, 1_000_000_000L, true,
            "Credits of counted trade above (or below) the usual level, in one simulation step, "
                    + "that move a share price by the whole of exchangeVolumeNudgeCapPercent.");
    public static final ConfigValue<Long> EXCHANGE_VOLUME_PAIR_CAP = SCHEMA.longRange(
            "exchangeVolumePairCap", ExchangeSettings.DEFAULT_VOLUME_PAIR_CAP, 0L, 1_000_000_000L, true,
            "Credits of trade between the same two player accounts that count toward share prices "
                    + "per simulation step, so two accounts trading back and forth cannot move a price.");
    public static final ConfigValue<Long> EXCHANGE_VOLUME_ACCOUNT_CAP = SCHEMA.longRange(
            "exchangeVolumeAccountCap", ExchangeSettings.DEFAULT_VOLUME_ACCOUNT_CAP, 0L, 1_000_000_000L, true,
            "Credits of one player account's trade that count toward share prices per simulation step.");
    public static final ConfigValue<Double> EXCHANGE_SHOCK_STEP_PERCENT = SCHEMA.doubleRange(
            "exchangeShockStepPercent", ExchangeSettings.DEFAULT_SHOCK_STEP_PERCENT, 0.0D, 10.0D, true,
            "How much of an active market shock on a company or its linked items reaches its share "
                    + "price in one simulation step, as a percentage. 0 keeps shocks out of the exchange.");
    public static final ConfigValue<Double> EXCHANGE_FLOOR_PERCENT = SCHEMA.doubleRange(
            "exchangeFloorPercent", ExchangeSettings.DEFAULT_FLOOR_PERCENT, 0.0D, 100.0D, true,
            "A share price never falls below this percentage of the company's start price, nor "
                    + "below 0.01 credit.");
    public static final ConfigValue<Double> BOND_RATE_1_DAY_PERCENT = SCHEMA.doubleRange(
            "bondRate1DayPercent", ExchangeSettings.DEFAULT_BOND_RATE_1_DAY_PERCENT, 0.0D, 100.0D, true,
            "Interest a 1-day Savings Bond pays for its whole term, as a percentage of what was put in.");
    public static final ConfigValue<Double> BOND_RATE_3_DAY_PERCENT = SCHEMA.doubleRange(
            "bondRate3DayPercent", ExchangeSettings.DEFAULT_BOND_RATE_3_DAY_PERCENT, 0.0D, 100.0D, true,
            "Interest a 3-day Savings Bond pays for its whole term, as a percentage of what was put in.");
    public static final ConfigValue<Double> BOND_RATE_7_DAY_PERCENT = SCHEMA.doubleRange(
            "bondRate7DayPercent", ExchangeSettings.DEFAULT_BOND_RATE_7_DAY_PERCENT, 0.0D, 100.0D, true,
            "Interest a 7-day Savings Bond pays for its whole term, as a percentage of what was put in.");
    public static final ConfigValue<Double> EXCHANGE_SPREAD_STEADY_PERCENT = SCHEMA.doubleRange(
            "exchangeSpreadSteadyPercent", TradingTerms.DEFAULT_SPREAD_STEADY_PERCENT, 0.1D, 20.0D, true,
            "Gap between what a steady share is bought at and what it is sold at, as a percentage "
                    + "of its price: half goes on top of the price when buying and half comes off it "
                    + "when selling. Buying and selling at once therefore always loses.");
    public static final ConfigValue<Double> EXCHANGE_SPREAD_RISKY_PERCENT = SCHEMA.doubleRange(
            "exchangeSpreadRiskyPercent", TradingTerms.DEFAULT_SPREAD_RISKY_PERCENT, 0.1D, 20.0D, true,
            "The same gap for a risky share.");
    public static final ConfigValue<Double> EXCHANGE_SPREAD_INDEX_PERCENT = SCHEMA.doubleRange(
            "exchangeSpreadIndexPercent", TradingTerms.DEFAULT_SPREAD_INDEX_PERCENT, 0.1D, 20.0D, true,
            "The same gap for the index fund.");
    public static final ConfigValue<Integer> SHARE_HOLD_MINUTES = SCHEMA.intRange(
            "shareHoldMinutes", TradingTerms.DEFAULT_HOLD_MINUTES, 0, 10080, true,
            "Minutes of economy time before shares or index units that were just bought can be sold. "
                    + "0 turns the holding period off.");
    public static final ConfigValue<Long> MAX_POSITION_PER_STOCK = SCHEMA.longRange(
            "maxPositionPerStock", TradingTerms.DEFAULT_MAX_POSITION, 0L, 1_000_000_000L, true,
            "Most credits one account may have in one stock or in the index fund, counted at what "
                    + "was paid for the units still held; 0 refuses every purchase.");
    public static final ConfigValue<Long> MAX_PORTFOLIO = SCHEMA.longRange(
            "maxPortfolio", TradingTerms.DEFAULT_MAX_PORTFOLIO, 0L, 1_000_000_000L, true,
            "Most credits one account may have on the exchange altogether - shares, index fund and "
                    + "Savings Bonds - counted at what was paid in.");
    public static final ConfigValue<Integer> MAX_BONDS_PER_PLAYER = SCHEMA.intRange(
            "maxBondsPerPlayer", TradingTerms.DEFAULT_MAX_BONDS, 0, 64, true,
            "Savings Bonds one account may hold at a time; 0 refuses new ones.");
    public static final ConfigValue<Long> PRACTICE_STARTING_CREDITS = SCHEMA.longRange(
            "practiceStartingCredits", TradingTerms.DEFAULT_PRACTICE_START, 0L, 1_000_000_000L, true,
            "Play credits a practice portfolio starts with and is reset to. They are not credits: "
                    + "nothing converts between practice and the real economy.");
    public static final ConfigValue<Integer> EXCHANGE_ACTIONS_PER_MINUTE = SCHEMA.intRange(
            "exchangeActionsPerMinute", TradingTerms.DEFAULT_ACTIONS_PER_MINUTE, 1, 600, true,
            "Buys, sells and bond actions one account may make on the exchange per minute; practice "
                    + "is counted separately, at the same rate.");
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
    public static final ConfigValue<Long> REMOTE_DAILY_SPEND_LIMIT = SCHEMA.longRange(
            "remoteDailySpendLimit", 5_000L, 0L, 1_000_000_000_000L, true,
            "Credits one account may spend per economy day through companion-app actions, all of them "
                    + "together: purchases, buy-order escrow, rule budgets, shares, bonds, payments and offers. "
                    + "It is the most a stolen app token can cost a player in a day. 0 allows no spending from the app.");
    public static final ConfigValue<Long> REMOTE_DAILY_GOODS_LIMIT = SCHEMA.longRange(
            "remoteDailyGoodsLimit", 5_000L, 0L, 1_000_000_000_000L, true,
            "Credits' worth of goods that may leave one account per economy day through companion-app "
                    + "actions, all of them together: goods in offers, sell orders and sell rules from the vault, "
                    + "goods an accepted offer asks for. Counted apart from remoteDailySpendLimit, so the two "
                    + "together are the most a stolen app token can cost a player in a day. 0 lets no goods leave "
                    + "through the app.");
    public static final ConfigValue<Long> REMOTE_GOODS_FLOOR_VALUE = SCHEMA.longRange(
            "remoteGoodsFloorValue", 10L, 1L, 1_000_000_000L, true,
            "Credits one unit of unmodified goods that are no commodity counts as against "
                    + "remoteDailyGoodsLimit. A commodity counts at its reference price.");
    public static final ConfigValue<Long> REMOTE_MODIFIED_GOODS_VALUE = SCHEMA.longRange(
            "remoteModifiedGoodsValue", 500L, 1L, 1_000_000_000L, true,
            "Credits one unit of modified goods (renamed, enchanted, or holding contents such as a filled "
                    + "shulker box) counts as against remoteDailyGoodsLimit, or its item's reference price if higher.");
    public static final ConfigValue<Boolean> GIVE_HANDBOOK_ON_FIRST_JOIN = SCHEMA.bool(
            "giveHandbookOnFirstJoin", true, true,
            "Give each player one Trader's Handbook the first time they join (never in spectator mode, "
                    + "never twice); with a full inventory it goes to their vault. The book is craftable either way.");
    public static final ConfigValue<Boolean> TELEMETRY_ENABLED = SCHEMA.bool(
            "telemetryEnabled", true, false,
            "Send anonymous NeroEconomy-only crash reports (Sentry, EU servers). On by default; set false to opt out (client-local, takes effect on restart). No player data is sent. See PRIVACY.md.");

    private EconomyConfig() {
    }

    public static void init() {
        ConfigManager.register(SCHEMA);
    }
}
