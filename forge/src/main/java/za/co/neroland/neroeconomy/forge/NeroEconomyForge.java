package za.co.neroland.neroeconomy.forge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/** MinecraftForge entry point for NeroEconomy. */
@Mod(NeroEconomyCommon.MOD_ID)
public final class NeroEconomyForge {

    public NeroEconomyForge(FMLJavaModLoadingContext context) {
        NeroEconomyCommon.LOGGER.info("[NeroEconomy] Forge bootstrap");
        NeroEconomyCommon.init();
    }
}
