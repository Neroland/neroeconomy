package za.co.neroland.neroeconomy.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import za.co.neroland.neroeconomy.client.AutoTraderScreen;
import za.co.neroland.neroeconomy.client.DirectTradeScreen;
import za.co.neroland.neroeconomy.client.EconomyHubScreen;
import za.co.neroland.neroeconomy.client.GalleryCaptureHarness;
import za.co.neroland.neroeconomy.client.PlayerShopScreen;
import za.co.neroland.neroeconomy.client.VaultScreen;
import za.co.neroland.neroeconomy.client.handbook.HandbookScreen;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;

final class ForgeClientSetup {
    private ForgeClientSetup() { }
    static void init(BusGroup bus) {
        FMLClientSetupEvent.getBus(bus).addListener(event -> event.enqueueWork(() -> {
            MenuScreens.register(ModMenuTypes.PLAYER_SHOP.get(), PlayerShopScreen::new);
            MenuScreens.register(ModMenuTypes.VAULT.get(), VaultScreen::new);
            MenuScreens.register(ModMenuTypes.DIRECT_TRADE.get(), DirectTradeScreen::new);
            MenuScreens.register(ModMenuTypes.AUTO_TRADER.get(), AutoTraderScreen::new);
            MenuScreens.register(ModMenuTypes.ECONOMY_HUB.get(), EconomyHubScreen::new);
        }));
        // The Trader's Handbook: the item opens it on this client, and the book goes to and from its
        // demos, through the way this game version shows a screen.
        HandbookScreen.install(screen -> {
            //? if >=26.2 {
            Minecraft.getInstance().gui.setScreen(screen);
            //?} else {
            /*Minecraft.getInstance().setScreen(screen);
            *///?}
        });
        // The capture tooling exists in a development environment only; a release jar wires up
        // neither the tick hook nor the command.
        if (!GalleryCaptureHarness.available()) {
            return;
        }
        // Screenshot harness state machine — game-bus client tick (counterpart to Fabric's END_CLIENT_TICK).
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> GalleryCaptureHarness.tick());
        // Client-side /necgallery capture command tree (drives the local camera; separate dispatcher
        // from the server-side /neroeconomy gallery builder — a shared root would shadow it).
        RegisterClientCommandsEvent.BUS.addListener(event ->
                GalleryCaptureHarness.registerClientCommands(event.getDispatcher()));
    }
}
