package za.co.neroland.neroeconomy.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.MenuScreens;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.client.AutoTraderScreen;
import za.co.neroland.neroeconomy.client.DirectTradeScreen;
import za.co.neroland.neroeconomy.client.EconomyHubScreen;
import za.co.neroland.neroeconomy.client.GalleryCaptureHarness;
import za.co.neroland.neroeconomy.client.PlayerShopScreen;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;
import za.co.neroland.neroeconomy.platform.Services;
import za.co.neroland.neroeconomy.client.VaultScreen;

/** Fabric client entry point for NeroEconomy. */
public final class NeroEconomyFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Services.NETWORK.registerClient();
        NeroEconomyCommon.LOGGER.info("[NeroEconomy] Fabric client bootstrap");
        MenuScreens.register(ModMenuTypes.PLAYER_SHOP.get(), PlayerShopScreen::new);
        MenuScreens.register(ModMenuTypes.VAULT.get(), VaultScreen::new);
        MenuScreens.register(ModMenuTypes.DIRECT_TRADE.get(), DirectTradeScreen::new);
        MenuScreens.register(ModMenuTypes.AUTO_TRADER.get(), AutoTraderScreen::new);
        MenuScreens.register(ModMenuTypes.ECONOMY_HUB.get(), EconomyHubScreen::new);
        // The capture tooling exists in a development environment only; a release jar wires up
        // neither the tick hook nor the command.
        if (!GalleryCaptureHarness.available()) {
            return;
        }
        // Screenshot harness state machine — counterpart to NeoForge's ClientTickEvent.Post.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> GalleryCaptureHarness.tick());
        // Client-side /necgallery capture command tree (drives the local camera; separate dispatcher
        // from the server-side /neroeconomy gallery builder — a shared root would shadow it).
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                GalleryCaptureHarness.registerClientCommands(dispatcher));
    }
}
