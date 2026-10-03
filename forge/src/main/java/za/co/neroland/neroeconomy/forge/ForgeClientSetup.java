package za.co.neroland.neroeconomy.forge;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import za.co.neroland.neroeconomy.client.AutoTraderScreen;
import za.co.neroland.neroeconomy.client.BankTerminalScreen;
import za.co.neroland.neroeconomy.client.DirectTradeScreen;
import za.co.neroland.neroeconomy.client.GalleryCaptureHarness;
import za.co.neroland.neroeconomy.client.PlayerShopScreen;
import za.co.neroland.neroeconomy.client.TradingTerminalScreen;
import za.co.neroland.neroeconomy.client.VaultScreen;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;

final class ForgeClientSetup {
    private ForgeClientSetup() { }
    static void init(BusGroup bus) {
        FMLClientSetupEvent.getBus(bus).addListener(event -> event.enqueueWork(() -> {
            MenuScreens.register(ModMenuTypes.BANK_TERMINAL.get(), BankTerminalScreen::new);
            MenuScreens.register(ModMenuTypes.PLAYER_SHOP.get(), PlayerShopScreen::new);
            MenuScreens.register(ModMenuTypes.TRADING_TERMINAL.get(), TradingTerminalScreen::new);
            MenuScreens.register(ModMenuTypes.VAULT.get(), VaultScreen::new);
            MenuScreens.register(ModMenuTypes.DIRECT_TRADE.get(), DirectTradeScreen::new);
            MenuScreens.register(ModMenuTypes.AUTO_TRADER.get(), AutoTraderScreen::new);
        }));
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
