package za.co.neroland.neroeconomy.platform;

import java.util.List;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import za.co.neroland.neroeconomy.obligation.BountyHooks;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.command.EconomyCommands;

public final class ForgePlatformInfo implements PlatformInfo {
    @Override public void register(Object bus) {
        RegistrationProvider.attach((BusGroup) bus);
        ServerStartedEvent.BUS.addListener(event -> NeroEconomyCommon.CURRENCY_PROVIDER.bind(event.getServer()));
        ServerStoppedEvent.BUS.addListener(event -> NeroEconomyCommon.CURRENCY_PROVIDER.unbind(event.getServer()));
        RegisterCommandsEvent.BUS.addListener(event -> EconomyCommands.register(event.getDispatcher()));
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> NeroEconomyCommon.CURRENCY_PROVIDER.tick(event.server()));
        LivingDeathEvent.BUS.addListener(event -> { BountyHooks.entityKilled(event.getEntity(), event.getSource()); });
    }
    @Override public String getPlatformName() { return "Forge"; }
    @Override public boolean isDevelopmentEnvironment() { return !FMLEnvironment.production; }
    @Override public boolean isClient() { return FMLEnvironment.dist == Dist.CLIENT; }
    @Override public String getModVersion() {
        return ModList.getModContainerById(NeroEconomyCommon.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString()).orElse("unknown");
    }
    @Override public List<String> getLoadedModIds() {
        return ModList.getMods().stream().map(mod -> mod.getModId() + " " + mod.getVersion()).sorted().toList();
    }
}
