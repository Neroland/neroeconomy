package za.co.neroland.neroeconomy.platform;

import java.util.List;

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import za.co.neroland.neroeconomy.block.PlayerShopBlock;
import za.co.neroland.neroeconomy.obligation.BountyHooks;
import net.fabricmc.loader.api.FabricLoader;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.command.EconomyCommands;

public final class FabricPlatformInfo implements PlatformInfo {
    @Override public void register(Object ignored) {
        ServerLifecycleEvents.SERVER_STARTED.register(NeroEconomyCommon.CURRENCY_PROVIDER::bind);
        ServerLifecycleEvents.SERVER_STOPPED.register(NeroEconomyCommon.CURRENCY_PROVIDER::unbind);
        ServerTickEvents.END_SERVER_TICK.register(NeroEconomyCommon.CURRENCY_PROVIDER::tick);
        ServerLivingEntityEvents.AFTER_DEATH.register(BountyHooks::entityKilled);
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
                !(state.getBlock() instanceof PlayerShopBlock) || PlayerShopBlock.canBreak(level, pos, player));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                EconomyCommands.register(dispatcher));
    }
    @Override public String getPlatformName() { return "Fabric"; }
    @Override public boolean isDevelopmentEnvironment() { return FabricLoader.getInstance().isDevelopmentEnvironment(); }
    @Override public boolean isClient() { return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT; }
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
