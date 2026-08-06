package za.co.neroland.neroeconomy.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import za.co.neroland.neroeconomy.client.BankTerminalScreen;
import za.co.neroland.neroeconomy.client.PlayerShopScreen;
import za.co.neroland.neroeconomy.client.TradingTerminalScreen;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;

final class NeoForgeClientSetup {
    private NeoForgeClientSetup() { }
    static void init(IEventBus bus) { bus.addListener(NeoForgeClientSetup::screens); }
    private static void screens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.BANK_TERMINAL.get(), BankTerminalScreen::new);
        event.register(ModMenuTypes.PLAYER_SHOP.get(), PlayerShopScreen::new);
        event.register(ModMenuTypes.TRADING_TERMINAL.get(), TradingTerminalScreen::new);
    }
}
