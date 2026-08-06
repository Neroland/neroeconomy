package za.co.neroland.neroeconomy.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.client.BankTerminalScreen;
import za.co.neroland.neroeconomy.client.PlayerShopScreen;
import za.co.neroland.neroeconomy.registry.ModMenuTypes;
import za.co.neroland.neroeconomy.platform.Services;
import za.co.neroland.neroeconomy.client.TradingTerminalScreen;

/** Fabric client entry point for NeroEconomy. */
public final class NeroEconomyFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Services.NETWORK.registerClient();
        NeroEconomyCommon.LOGGER.info("[NeroEconomy] Fabric client bootstrap");
        MenuScreens.register(ModMenuTypes.BANK_TERMINAL.get(), BankTerminalScreen::new);
        MenuScreens.register(ModMenuTypes.PLAYER_SHOP.get(), PlayerShopScreen::new);
        MenuScreens.register(ModMenuTypes.TRADING_TERMINAL.get(), TradingTerminalScreen::new);
    }
}
