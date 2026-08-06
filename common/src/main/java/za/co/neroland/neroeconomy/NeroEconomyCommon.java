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
import za.co.neroland.neroeconomy.telemetry.NeroEconomyTelemetry;
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
        PlayerDataErasure.register((server, player) -> EconomySavedData.get(server).forgetPlayer(player));
        PlayerDataErasure.register((server, player) -> ShopOwnershipState.get(server).forgetPlayer(player));
        PlayerDataErasure.register((server, player) -> ObligationSavedData.get(server).forgetPlayer(server, player));
        PlayerDataErasure.register((server, player) -> MarketSavedData.get(server).forgetPlayer(player));
        PlayerDataErasure.register(LedgerExportFiles::delete);
        // 2. Configuration and the real Core currency provider.
        EconomyConfig.init();
        NeroEconomyTelemetry.init();
        CurrencyApi.setProvider(CURRENCY_PROVIDER);
        // Resolve both ServiceLoader seams now; never discover services from a tick or packet path.
        Services.PLATFORM.getPlatformName();
        Services.NETWORK.getClass();
        NeroEconomyNetwork.init();
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
