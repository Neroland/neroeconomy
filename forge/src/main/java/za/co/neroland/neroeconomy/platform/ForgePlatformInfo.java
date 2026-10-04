package za.co.neroland.neroeconomy.platform;

import java.util.EnumMap;
import java.util.List;

import net.minecraft.world.level.Level;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.common.util.Result;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.SidedInvWrapper;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import za.co.neroland.neroeconomy.block.AutoTraderBlock;
import za.co.neroland.neroeconomy.block.AutoTraderBlockEntity;
import za.co.neroland.neroeconomy.block.PlayerShopBlock;
import za.co.neroland.neroeconomy.obligation.BountyHooks;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.command.EconomyCommands;
import za.co.neroland.neroeconomy.direct.DirectTrades;
import za.co.neroland.neroeconomy.exchange.StockData;
import za.co.neroland.neroeconomy.network.IntentGuard;

public final class ForgePlatformInfo implements PlatformInfo {
    private static final Identifier TRADER_ITEMS = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "auto_trader_items");

    /**
     * One item handler per face of an Auto-Trader, each a view of its sided vanilla container.
     * Every face behaves alike, and a caller that names no face gets the same view as one that
     * does: inputs take, outputs give, and nothing else.
     */
    private static final class TraderItems implements ICapabilityProvider {
        private final AutoTraderBlockEntity trader;
        private final EnumMap<Direction, LazyOptional<IItemHandler>> sided = new EnumMap<>(Direction.class);
        private LazyOptional<IItemHandler> unsided;

        TraderItems(AutoTraderBlockEntity trader) { this.trader = trader; }

        @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
            if (capability != ForgeCapabilities.ITEM_HANDLER) return LazyOptional.empty();
            if (side == null) {
                if (unsided == null) unsided = LazyOptional.of(() -> new SidedInvWrapper(trader, Direction.DOWN));
                return unsided.cast();
            }
            return sided.computeIfAbsent(side, face -> LazyOptional.of(() -> new SidedInvWrapper(trader, face))).cast();
        }

        void invalidate() {
            sided.values().forEach(LazyOptional::invalidate);
            sided.clear();
            if (unsided != null) unsided.invalidate();
            unsided = null;
        }
    }

    @Override public void register(Object bus) {
        RegistrationProvider.attach((BusGroup) bus);
        ServerStartedEvent.BUS.addListener(event -> NeroEconomyCommon.CURRENCY_PROVIDER.bind(event.getServer()));
        ServerStoppedEvent.BUS.addListener(event -> NeroEconomyCommon.CURRENCY_PROVIDER.unbind(event.getServer()));
        RegisterCommandsEvent.BUS.addListener(event -> EconomyCommands.register(event.getDispatcher()));
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> NeroEconomyCommon.CURRENCY_PROVIDER.tick(event.server()));
        // Data packs were reloaded (the event names no player then): the listed companies are read
        // again from the new packs. The same event fires per player on login, which is not a reload.
        OnDatapackSyncEvent.BUS.addListener(event -> {
            if (event.getPlayer() == null) StockData.reload(event.getPlayerList().getServer());
        });
        LivingDeathEvent.BUS.addListener(event -> { BountyHooks.entityKilled(event.getEntity(), event.getSource()); });
        // Shop-break protection is otherwise Fabric-only: deny the break instead of cancelling, since
        // that is what the vanilla hook reads back.
        BlockEvent.BreakEvent.BUS.addListener(event -> {
            if (event.getLevel() instanceof Level level && event.getPlayer() != null
                    && (!PlayerShopBlock.canBreak(level, event.getPos(), event.getPlayer())
                            || !AutoTraderBlock.canBreak(level, event.getPos(), event.getPlayer())))
                event.setResult(Result.DENY);
        });
        // The Auto-Trader's slots on the standard item capability, sided, so any mod's pipes can
        // feed and empty it; hoppers and NeroLogistics already see it as a vanilla container.
        AttachCapabilitiesEvent.BlockEntities.BUS.addListener(event -> {
            if (event.getObject() instanceof AutoTraderBlockEntity trader) {
                TraderItems items = new TraderItems(trader);
                event.addCapability(TRADER_ITEMS, items);
                event.addListener(items::invalidate);
            }
        });
        // Sneak-use on another player with an empty hand asks them for a trade. The listener only
        // watches: it never cancels the event, so the game and every other mod see it as it was.
        PlayerInteractEvent.EntityInteractSpecific.BUS.addListener(event -> {
            DirectTrades.useEntity(event.getEntity(), event.getTarget(), event.getHand());
        });
        // The packet limiter keys on the player id in memory; the entry leaves with the player,
        // and so do their trade requests and a trade they had open.
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(event -> {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer leaving) DirectTrades.playerLeft(leaving);
            IntentGuard.forget(event.getEntity().getUUID());
        });
    }
    @Override public String getPlatformName() { return "Forge"; }
    @Override public boolean isDevelopmentEnvironment() { return !FMLEnvironment.production; }
    @Override public String getModVersion() {
        return ModList.getModContainerById(NeroEconomyCommon.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString()).orElse("unknown");
    }
    @Override public List<String> getLoadedModIds() {
        return ModList.getMods().stream().map(mod -> mod.getModId() + " " + mod.getVersion()).sorted().toList();
    }
}
