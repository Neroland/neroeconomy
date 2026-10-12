package za.co.neroland.neroeconomy.platform;

import java.util.List;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.transfer.item.WorldlyContainerWrapper;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import za.co.neroland.neroeconomy.block.AutoTraderBlock;
import za.co.neroland.neroeconomy.block.PlayerShopBlock;
import za.co.neroland.neroeconomy.registry.ModBlockEntities;
import za.co.neroland.neroeconomy.obligation.BountyHooks;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.command.EconomyCommands;
import za.co.neroland.neroeconomy.commodity.CommodityData;
import za.co.neroland.neroeconomy.direct.DirectTrades;
import za.co.neroland.neroeconomy.exchange.StockData;
import za.co.neroland.neroeconomy.handbook.HandbookGift;
import za.co.neroland.neroeconomy.network.IntentGuard;

public final class NeoForgePlatformInfo implements PlatformInfo {
    @Override public void register(Object bus) {
        RegistrationProvider.attach((IEventBus) bus);
        // The Auto-Trader's slots on the standard item capability, so any mod's pipes can feed and
        // empty it; hoppers and NeroLogistics already see it as a vanilla container. Every face
        // behaves alike, and a caller that names no face gets the same view as one that does:
        // inputs take, outputs give, and nothing else.
        ((IEventBus) bus).addListener((RegisterCapabilitiesEvent event) -> event.registerBlockEntity(
                Capabilities.Item.BLOCK, ModBlockEntities.AUTO_TRADER.get(), (trader, side) ->
                        new WorldlyContainerWrapper(trader, side != null ? side : Direction.DOWN)));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> NeroEconomyCommon.CURRENCY_PROVIDER.bind(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> NeroEconomyCommon.CURRENCY_PROVIDER.unbind(event.getServer()));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> EconomyCommands.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) ->
                NeroEconomyCommon.CURRENCY_PROVIDER.tick(event.getServer()));
        // Data packs were reloaded (the event names no player then): the listed companies and the
        // commodities are read again from the new packs. The same event fires per player on login,
        // which is not a reload.
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> {
            if (event.getPlayer() == null) {
                StockData.reload(event.getPlayerList().getServer());
                CommodityData.reload(event.getPlayerList().getServer());
                za.co.neroland.neroeconomy.house.HouseData.reload(event.getPlayerList().getServer());
            }
        });
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
        // First join: the Trader's Handbook, once per account (see HandbookGift for the rule), then any
        // quest hook that opened while the player was away (see QuestHooks).
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer joined) {
                HandbookGift.joined(joined);
                za.co.neroland.neroeconomy.sibling.QuestHooks.onJoin(joined);
            }
        });
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
