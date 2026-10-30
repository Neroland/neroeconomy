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
        //? if >=1.21.6 {
        var modBus = context.getModBusGroup();
        //?} else {
        /*var modBus = context.getModEventBus();
        *///?}
        Services.PLATFORM.register(modBus);
        Services.NETWORK.register(modBus);
        if (FMLEnvironment.dist == Dist.CLIENT) ForgeClientSetup.init(modBus);
    }
}
