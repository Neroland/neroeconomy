package za.co.neroland.neroeconomy.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/** NeoForge entry point for NeroEconomy. */
@Mod(NeroEconomyCommon.MOD_ID)
public final class NeroEconomyNeoForge {

    public NeroEconomyNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        NeroEconomyCommon.LOGGER.info("[NeroEconomy] NeoForge bootstrap");
        NeroEconomyCommon.init();
    }
}
