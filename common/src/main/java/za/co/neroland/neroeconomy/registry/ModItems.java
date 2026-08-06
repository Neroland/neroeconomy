package za.co.neroland.neroeconomy.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import za.co.neroland.nerolandcore.registry.CoreCreativeTab;
import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.item.CreditChipItem;

public final class ModItems {
    public static final RegistrationProvider<Item> ITEMS =
            RegistrationProvider.get(Registries.ITEM, NeroEconomyCommon.MOD_ID);
    public static final RegistryEntry<BlockItem> BANK_TERMINAL = ITEMS.register("bank_terminal",
            key -> new BlockItem(ModBlocks.BANK_TERMINAL.get(), new Item.Properties().setId(key)));
    public static final RegistryEntry<BlockItem> PLAYER_SHOP = ITEMS.register("player_shop",
            key -> new BlockItem(ModBlocks.PLAYER_SHOP.get(), new Item.Properties().setId(key)));
    public static final RegistryEntry<BlockItem> TRADING_TERMINAL = ITEMS.register("trading_terminal",
            key -> new BlockItem(ModBlocks.TRADING_TERMINAL.get(), new Item.Properties().setId(key)));
    public static final RegistryEntry<BlockItem> MARKET_BOARD = ITEMS.register("market_board",
            key -> new BlockItem(ModBlocks.MARKET_BOARD.get(), new Item.Properties().setId(key)));
    public static final RegistryEntry<CreditChipItem> CREDIT_CHIP = ITEMS.register("credit_chip",
            key -> new CreditChipItem(new Item.Properties().setId(key).stacksTo(16)));

    private ModItems() {
    }

    public static void init() {
        CoreCreativeTab.add(BANK_TERMINAL);
        CoreCreativeTab.add(PLAYER_SHOP);
        CoreCreativeTab.add(TRADING_TERMINAL);
        CoreCreativeTab.add(MARKET_BOARD);
        CoreCreativeTab.add(CREDIT_CHIP);
    }
}
