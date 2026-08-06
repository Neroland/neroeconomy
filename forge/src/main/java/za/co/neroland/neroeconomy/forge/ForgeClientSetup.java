package za.co.neroland.neroeconomy.forge;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import za.co.neroland.neroeconomy.client.BankTerminalScreen;
import za.co.neroland.neroeconomy.client.PlayerShopScreen;
import za.co.neroland.neroeconomy.client.TradingTerminalScreen;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;

final class ForgeClientSetup {
    private ForgeClientSetup() { }
    static void init(BusGroup bus) {
        FMLClientSetupEvent.getBus(bus).addListener(event -> event.enqueueWork(() -> {
            MenuScreens.register(ModMenuTypes.BANK_TERMINAL.get(), BankTerminalScreen::new);
            MenuScreens.register(ModMenuTypes.PLAYER_SHOP.get(), PlayerShopScreen::new);
            MenuScreens.register(ModMenuTypes.TRADING_TERMINAL.get(), TradingTerminalScreen::new);
        }));
    }
}
