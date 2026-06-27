package za.co.neroland.neroeconomy.fabric;

import net.fabricmc.api.ClientModInitializer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/** Fabric client entry point for NeroEconomy. */
public final class NeroEconomyFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        NeroEconomyCommon.LOGGER.info("[NeroEconomy] Fabric client bootstrap");
    }
}
