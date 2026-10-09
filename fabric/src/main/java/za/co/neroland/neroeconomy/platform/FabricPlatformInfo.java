package za.co.neroland.neroeconomy.platform;

import java.util.List;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import za.co.neroland.neroeconomy.block.AutoTraderBlock;
import za.co.neroland.neroeconomy.block.PlayerShopBlock;
import za.co.neroland.neroeconomy.registry.ModBlockEntities;
import za.co.neroland.neroeconomy.obligation.BountyHooks;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Direction;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.command.EconomyCommands;
import za.co.neroland.neroeconomy.commodity.CommodityData;
import za.co.neroland.neroeconomy.direct.DirectTrades;
import za.co.neroland.neroeconomy.exchange.StockData;
import za.co.neroland.neroeconomy.handbook.HandbookGift;
import za.co.neroland.neroeconomy.network.IntentGuard;

public final class FabricPlatformInfo implements PlatformInfo {
    @Override public void register(Object ignored) {
        ServerLifecycleEvents.SERVER_STARTED.register(NeroEconomyCommon.CURRENCY_PROVIDER::bind);
        ServerLifecycleEvents.SERVER_STOPPED.register(NeroEconomyCommon.CURRENCY_PROVIDER::unbind);
        ServerTickEvents.END_SERVER_TICK.register(NeroEconomyCommon.CURRENCY_PROVIDER::tick);
        // Data packs were reloaded: the listed companies and the commodities are read again.
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                StockData.reload(server);
                CommodityData.reload(server);
                za.co.neroland.neroeconomy.house.HouseData.reload(server);
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register(BountyHooks::entityKilled);
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
                (!(state.getBlock() instanceof PlayerShopBlock) || PlayerShopBlock.canBreak(level, pos, player))
                        && (!(state.getBlock() instanceof AutoTraderBlock) || AutoTraderBlock.canBreak(level, pos, player)));
        // The Auto-Trader's slots on the Fabric Transfer API, so any mod's pipes can feed and empty
        // it. Every face behaves alike, and a caller that names no face gets the same view as one
        // that does: inputs take, outputs give, and nothing else.
        ItemStorage.SIDED.registerForBlockEntity((trader, side) ->
                ContainerStorage.of(trader, side != null ? side : Direction.DOWN), ModBlockEntities.AUTO_TRADER.get());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                EconomyCommands.register(dispatcher));
        // Sneak-use on another player with an empty hand asks them for a trade. The answer is
        // always PASS: the interaction is never consumed, so the game and every other mod see it.
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            DirectTrades.useEntity(player, entity, hand);
            return net.minecraft.world.InteractionResult.PASS;
        });
        // First join: the Trader's Handbook, once per account (see HandbookGift for the rule).
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> HandbookGift.joined(handler.player));
        // The packet limiter keys on the player id in memory; the entry leaves with the player,
        // and so do their trade requests and a trade they had open.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DirectTrades.playerLeft(handler.player);
            IntentGuard.forget(handler.player.getUUID());
        });
    }
    @Override public String getPlatformName() { return "Fabric"; }
    @Override public boolean isDevelopmentEnvironment() { return FabricLoader.getInstance().isDevelopmentEnvironment(); }
    @Override public String getModVersion() {
        return FabricLoader.getInstance().getModContainer(NeroEconomyCommon.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }
    @Override public List<String> getLoadedModIds() {
        return FabricLoader.getInstance().getAllMods().stream()
                .map(mod -> mod.getMetadata().getId() + " " + mod.getMetadata().getVersion().getFriendlyString())
                .sorted().toList();
    }
}
