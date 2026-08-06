package za.co.neroland.neroeconomy.forge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.platform.Services;

/** MinecraftForge entry point for NeroEconomy. */
@Mod(NeroEconomyCommon.MOD_ID)
public final class NeroEconomyForge {

    public NeroEconomyForge(FMLJavaModLoadingContext context) {
        NeroEconomyCommon.LOGGER.info("[NeroEconomy] Forge bootstrap");
        NeroEconomyCommon.init();
        Services.PLATFORM.register(context.getModBusGroup());
        Services.NETWORK.register(context.getModBusGroup());
        if (FMLEnvironment.dist == Dist.CLIENT) ForgeClientSetup.init(context.getModBusGroup());
    }
}
