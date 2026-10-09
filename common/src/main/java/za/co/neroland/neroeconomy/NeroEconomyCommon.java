package za.co.neroland.neroeconomy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import za.co.neroland.nerolandcore.data.PlayerDataErasure;
import za.co.neroland.nerolandcore.economy.Currency;
import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.nerolandcore.economy.CoreCurrencies;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.data.LedgerExportFiles;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.ServerCurrencyProvider;
import za.co.neroland.neroeconomy.registry.ModBlockEntities;
import za.co.neroland.neroeconomy.registry.ModBlocks;
import za.co.neroland.neroeconomy.registry.ModItems;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;
import za.co.neroland.neroeconomy.network.NeroEconomyNetwork;
import za.co.neroland.neroeconomy.platform.Services;
import za.co.neroland.neroeconomy.shop.ShopOwnershipState;
import za.co.neroland.neroeconomy.simulation.Simulation;
import za.co.neroland.neroeconomy.telemetry.NeroEconomyTelemetry;
import za.co.neroland.neroeconomy.trade.Trading;
import za.co.neroland.neroeconomy.trade.TradingSteps;
import za.co.neroland.neroeconomy.vault.VaultSection;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;

/**
 * Loader-agnostic entry point for NeroEconomy. Each loader entry point
 * (Fabric / Forge / NeoForge) calls {@link #init()} once during mod
 * construction. Shared gameplay stays in common code and reaches lifecycle
 * and networking behavior only through the eager platform seams.
 */
public final class NeroEconomyCommon {

    public static final String MOD_ID = "neroeconomy";
    public static final Logger LOGGER = LoggerFactory.getLogger("NeroEconomy");
    public static final Currency CREDITS = CoreCurrencies.CREDITS;
    public static final ServerCurrencyProvider CURRENCY_PROVIDER = new ServerCurrencyProvider();
    private static boolean initialized;

    private NeroEconomyCommon() {
    }

    /** Called once per loader during mod construction. */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        // 1. Privacy hooks are registered before any gameplay store can be exposed. Obligation
        //    settlement runs before the market sweep, so a claim it books for the erased player's
        //    counterparty survives while anything owed to the erased player is then dropped.
        //    Each store's eraser also pushes the purge into Core's recovery backup, which is a second
        //    copy of the same rows. The ledger eraser here is the only ledger pass: Core's built-in
        //    currency eraser reaches ServerCurrencyProvider.forgetPlayer, which is deliberately empty.
        PlayerDataErasure.register(EconomySavedData::eraseFor);
        PlayerDataErasure.register(ShopOwnershipState::eraseFor);
        PlayerDataErasure.register(ObligationSavedData::eraseFor);
        //    The order book goes before the market sweep too: nothing it erases is owed to anyone
        //    else, and a seller's proceeds from the erased player's purchases are claims that stay.
        PlayerDataErasure.register(Trading::eraseFor);
        //    The Merchant Houses: what the erased player traded with them today, and their count
        //    toward reference-price drift.
        PlayerDataErasure.register(za.co.neroland.neroeconomy.house.Houses::eraseFor);
        //    The exchange: the erased player's share cost, bond principal and reserved interest move
        //    from the exchange account to the Reserve, and their practice portfolio is dropped.
        PlayerDataErasure.register(za.co.neroland.neroeconomy.exchange.Investing::eraseFor);
        PlayerDataErasure.register(MarketSavedData::eraseFor);
        //    The vault goes after the stores that could still deliver goods to it: the erased
        //    player's goods are destroyed, and the prototypes only they referred to with them.
        PlayerDataErasure.register(VaultSection::eraseFor);
        PlayerDataErasure.register(LedgerExportFiles::delete);
        PlayerDataErasure.register((server, player) -> za.co.neroland.neroeconomy.link.EconomyAlerts.forget(player));
        // 2. Configuration and the real Core currency provider.
        EconomyConfig.init();
        NeroEconomyTelemetry.init();
        CurrencyApi.setProvider(CURRENCY_PROVIDER);
        // Resolve both ServiceLoader seams now; never discover services from a tick or packet path.
        Services.PLATFORM.getPlatformName();
        Services.NETWORK.getClass();
        NeroEconomyNetwork.init();
        // The trading rules' simulation handlers, after the housekeeping ones the pipeline starts with.
        TradingSteps.register(Simulation.PIPELINE, context -> Trading.env(context.store()));
        // The exchange's price step. Not housekeeping: autonomousEconomy=false switches it off.
        Simulation.PIPELINE.register(new za.co.neroland.neroeconomy.exchange.ExchangeStep(
                za.co.neroland.neroeconomy.exchange.ExchangeStep.GAME));
        // After the prices: the day's dividends, then bond maturities and delisting payouts.
        za.co.neroland.neroeconomy.exchange.ExchangeSteps.register(Simulation.PIPELINE,
                za.co.neroland.neroeconomy.exchange.Investing::env);
        // The Merchant Houses, after the exchange: drift, funding, restock and quotes. Not housekeeping.
        za.co.neroland.neroeconomy.house.Houses.register(Simulation.PIPELINE);
        // The Houses keep the stock of their own accounts; until this is plugged in the book makes no House order.
        Trading.systemDelivery(za.co.neroland.neroeconomy.house.Houses.DELIVERY);
        // Auto-Trader blocks are the inventories a rule can work from besides its owner's vault.
        Trading.ruleBinder(za.co.neroland.neroeconomy.trader.AutoTraders.BINDER);
        // Server-funded delivery jobs (soft logistics seam); without this the overload refuses jobs.
        za.co.neroland.neroeconomy.api.DeliveryJobApi.setSystemIssuer(
                (server, type, target, quantity, destination, payout, expiresAt) -> ObligationSavedData
                        .get(server).postSystemJob(type, target, quantity, destination, payout, expiresAt));
        // 3. Content registrations.
        ModBlocks.init();
        ModItems.init();
        ModBlockEntities.init();
        ModMenuTypes.init();
        // 4. Link is always last and can never prevent the mod from loading.
        try {
            za.co.neroland.neroeconomy.link.NeroEconomyLinkModule.register();
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("[NeroEconomy] Link module registration failed; gameplay remains available.");
            NeroEconomyTelemetry.captureHandledException(failure, "link_registration");
        }
        LOGGER.info("[NeroEconomy] common init");
    }
}
