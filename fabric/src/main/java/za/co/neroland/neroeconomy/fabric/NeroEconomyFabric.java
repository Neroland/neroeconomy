package za.co.neroland.neroeconomy.fabric;

import net.fabricmc.api.ModInitializer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.platform.Services;

/** Fabric entry point for NeroEconomy. */
public final class NeroEconomyFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        NeroEconomyCommon.LOGGER.info("[NeroEconomy] Fabric bootstrap");
        NeroEconomyCommon.init();
        Services.PLATFORM.register(null);
        Services.NETWORK.register(null);
    }
}
