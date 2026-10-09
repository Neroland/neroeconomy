package za.co.neroland.neroeconomy.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.menu.AutoTraderMenu;
import za.co.neroland.neroeconomy.menu.DirectTradeMenu;
import za.co.neroland.neroeconomy.menu.EconomyHubMenu;
import za.co.neroland.neroeconomy.menu.PlayerShopMenu;
import za.co.neroland.neroeconomy.menu.VaultMenu;

public final class ModMenuTypes {
    public static final RegistrationProvider<MenuType<?>> MENUS =
            RegistrationProvider.get(Registries.MENU, NeroEconomyCommon.MOD_ID);
    public static final RegistryEntry<MenuType<PlayerShopMenu>> PLAYER_SHOP = MENUS.register("player_shop",
            key -> new MenuType<>(PlayerShopMenu::new, FeatureFlags.VANILLA_SET));
    public static final RegistryEntry<MenuType<VaultMenu>> VAULT = MENUS.register("vault",
            key -> new MenuType<>(VaultMenu::new, FeatureFlags.VANILLA_SET));
    public static final RegistryEntry<MenuType<DirectTradeMenu>> DIRECT_TRADE = MENUS.register("direct_trade",
            key -> new MenuType<>(DirectTradeMenu::new, FeatureFlags.VANILLA_SET));

    public static final RegistryEntry<MenuType<AutoTraderMenu>> AUTO_TRADER = MENUS.register("auto_trader",
            key -> new MenuType<>(AutoTraderMenu::new, FeatureFlags.VANILLA_SET));

    public static final RegistryEntry<MenuType<EconomyHubMenu>> ECONOMY_HUB = MENUS.register("economy_hub",
            key -> new MenuType<>(EconomyHubMenu::new, FeatureFlags.VANILLA_SET));

    private ModMenuTypes() {
    }

    public static void init() {
    }
}
