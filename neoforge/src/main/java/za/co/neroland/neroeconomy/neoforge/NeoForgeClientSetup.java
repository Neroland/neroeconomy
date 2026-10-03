package za.co.neroland.neroeconomy.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

import za.co.neroland.neroeconomy.client.AutoTraderScreen;
import za.co.neroland.neroeconomy.client.BankTerminalScreen;
import za.co.neroland.neroeconomy.client.DirectTradeScreen;
import za.co.neroland.neroeconomy.client.GalleryCaptureHarness;
import za.co.neroland.neroeconomy.client.PlayerShopScreen;
import za.co.neroland.neroeconomy.client.TradingTerminalScreen;
import za.co.neroland.neroeconomy.client.VaultScreen;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;

final class NeoForgeClientSetup {
    private NeoForgeClientSetup() { }
    static void init(IEventBus bus) {
        bus.addListener(NeoForgeClientSetup::screens);
        // The capture tooling exists in a development environment only; a release jar wires up
        // neither the tick hook nor the command.
        if (!GalleryCaptureHarness.available()) {
            return;
        }
        // Screenshot harness state machine — game-bus client tick (counterpart to Fabric's END_CLIENT_TICK).
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> GalleryCaptureHarness.tick());
        // Client-side /necgallery capture command tree (drives the local camera; separate dispatcher
        // from the server-side /neroeconomy gallery builder — a shared root would shadow it).
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) ->
                GalleryCaptureHarness.registerClientCommands(event.getDispatcher()));
    }
    private static void screens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.BANK_TERMINAL.get(), BankTerminalScreen::new);
        event.register(ModMenuTypes.PLAYER_SHOP.get(), PlayerShopScreen::new);
        event.register(ModMenuTypes.TRADING_TERMINAL.get(), TradingTerminalScreen::new);
        event.register(ModMenuTypes.VAULT.get(), VaultScreen::new);
        event.register(ModMenuTypes.DIRECT_TRADE.get(), DirectTradeScreen::new);
        event.register(ModMenuTypes.AUTO_TRADER.get(), AutoTraderScreen::new);
    }
}
