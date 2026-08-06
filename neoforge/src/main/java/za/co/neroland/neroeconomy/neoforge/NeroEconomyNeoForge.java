package za.co.neroland.neroeconomy.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.platform.Services;

/** NeoForge entry point for NeroEconomy. */
@Mod(NeroEconomyCommon.MOD_ID)
public final class NeroEconomyNeoForge {

    public NeroEconomyNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        NeroEconomyCommon.LOGGER.info("[NeroEconomy] NeoForge bootstrap");
        NeroEconomyCommon.init();
        Services.PLATFORM.register(modEventBus);
        Services.NETWORK.register(modEventBus);
        if (FMLEnvironment.getDist() == Dist.CLIENT) NeoForgeClientSetup.init(modEventBus);
    }
}
