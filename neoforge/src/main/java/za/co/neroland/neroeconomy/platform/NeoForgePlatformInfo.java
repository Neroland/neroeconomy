package za.co.neroland.neroeconomy.platform;

import java.util.List;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

import net.minecraft.world.level.Level;

import za.co.neroland.neroeconomy.block.AutoTraderBlock;
import za.co.neroland.neroeconomy.block.PlayerShopBlock;
import za.co.neroland.neroeconomy.registry.ModBlockEntities;
import za.co.neroland.neroeconomy.obligation.BountyHooks;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.command.EconomyCommands;
import za.co.neroland.neroeconomy.direct.DirectTrades;
import za.co.neroland.neroeconomy.network.IntentGuard;

public final class NeoForgePlatformInfo implements PlatformInfo {
    @Override public void register(Object bus) {
        RegistrationProvider.attach((IEventBus) bus);
        // The Auto-Trader's slots on the standard item capability, sided, so any mod's pipes can
        // feed and empty it; hoppers and NeroLogistics already see it as a vanilla container.
        ((IEventBus) bus).addListener((RegisterCapabilitiesEvent event) -> event.registerBlockEntity(
                Capabilities.Item.BLOCK, ModBlockEntities.AUTO_TRADER.get(), (trader, side) -> side != null
                        ? new WorldlyContainerWrapper(trader, side) : VanillaContainerWrapper.of(trader)));
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
                    && (!PlayerShopBlock.canBreak(level, event.getPos(), event.getPlayer())
                            || !AutoTraderBlock.canBreak(level, event.getPos(), event.getPlayer())))
                event.setCanceled(true);
        });
        // Sneak-use on another player with an empty hand asks them for a trade. The listener only
        // watches: it never cancels the event, so the game and every other mod see it as it was.
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract event) ->
                DirectTrades.useEntity(event.getEntity(), event.getTarget(), event.getHand()));
        // The packet limiter keys on the player id in memory; the entry leaves with the player,
        // and so do their trade requests and a trade they had open.
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer leaving) DirectTrades.playerLeft(leaving);
            IntentGuard.forget(event.getEntity().getUUID());
        });
    }
    @Override public String getPlatformName() { return "NeoForge"; }
    @Override public boolean isDevelopmentEnvironment() { return !FMLEnvironment.isProduction(); }
    @Override public String getModVersion() {
        return ModList.get().getModContainerById(NeroEconomyCommon.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString()).orElse("unknown");
    }
    @Override public List<String> getLoadedModIds() {
        return ModList.get().getMods().stream().map(mod -> mod.getModId() + " " + mod.getVersion()).sorted().toList();
    }
}
