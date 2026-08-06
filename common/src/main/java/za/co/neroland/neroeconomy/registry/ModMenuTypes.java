package za.co.neroland.neroeconomy.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.menu.BankTerminalMenu;
import za.co.neroland.neroeconomy.menu.PlayerShopMenu;
import za.co.neroland.neroeconomy.menu.TradingTerminalMenu;

public final class ModMenuTypes {
    public static final RegistrationProvider<MenuType<?>> MENUS =
            RegistrationProvider.get(Registries.MENU, NeroEconomyCommon.MOD_ID);
    public static final RegistryEntry<MenuType<BankTerminalMenu>> BANK_TERMINAL = MENUS.register("bank_terminal",
            key -> new MenuType<>(BankTerminalMenu::new, FeatureFlags.VANILLA_SET));
    public static final RegistryEntry<MenuType<PlayerShopMenu>> PLAYER_SHOP = MENUS.register("player_shop",
            key -> new MenuType<>(PlayerShopMenu::new, FeatureFlags.VANILLA_SET));
    public static final RegistryEntry<MenuType<TradingTerminalMenu>> TRADING_TERMINAL = MENUS.register("trading_terminal",
            key -> new MenuType<>(TradingTerminalMenu::new, FeatureFlags.VANILLA_SET));

    private ModMenuTypes() {
    }

    public static void init() {
    }
}
