package za.co.neroland.neroeconomy.platform;

import java.util.List;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

import net.minecraft.world.level.Level;

import za.co.neroland.neroeconomy.block.PlayerShopBlock;
import za.co.neroland.neroeconomy.obligation.BountyHooks;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.command.EconomyCommands;

public final class NeoForgePlatformInfo implements PlatformInfo {
    @Override public void register(Object bus) {
        RegistrationProvider.attach((IEventBus) bus);
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> NeroEconomyCommon.CURRENCY_PROVIDER.bind(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> NeroEconomyCommon.CURRENCY_PROVIDER.unbind(event.getServer()));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> EconomyCommands.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) ->
                NeroEconomyCommon.CURRENCY_PROVIDER.tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent event) ->
                BountyHooks.entityKilled(event.getEntity(), event.getSource()));
        // Shop-break protection is otherwise Fabric-only. 26.x moved the break event out of
        // BlockEvent into event.level.block.BreakBlockEvent (ICancellableEvent).
        NeoForge.EVENT_BUS.addListener((BreakBlockEvent event) -> {
            if (event.getLevel() instanceof Level level && event.getPlayer() != null
                    && !PlayerShopBlock.canBreak(level, event.getPos(), event.getPlayer()))
                event.setCanceled(true);
        });
    }
    @Override public String getPlatformName() { return "NeoForge"; }
    @Override public boolean isDevelopmentEnvironment() { return !FMLEnvironment.isProduction(); }
    @Override public boolean isClient() { return FMLEnvironment.getDist() == Dist.CLIENT; }
    @Override public String getModVersion() {
        return ModList.get().getModContainerById(NeroEconomyCommon.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString()).orElse("unknown");
    }
    @Override public List<String> getLoadedModIds() {
        return ModList.get().getMods().stream().map(mod -> mod.getModId() + " " + mod.getVersion()).sorted().toList();
    }
}
