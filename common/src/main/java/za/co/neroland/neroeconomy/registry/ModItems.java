package za.co.neroland.neroeconomy.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import za.co.neroland.nerolandcore.registry.CoreCreativeTab;
import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.item.CreditChipItem;
import za.co.neroland.neroeconomy.item.TradersHandbookItem;

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
    public static final RegistryEntry<BlockItem> AUTO_TRADER = ITEMS.register("auto_trader",
            key -> new BlockItem(ModBlocks.AUTO_TRADER.get(), new Item.Properties().setId(key)));
    public static final RegistryEntry<BlockItem> EXCHANGE_TERMINAL = ITEMS.register("exchange_terminal",
            key -> new BlockItem(ModBlocks.EXCHANGE_TERMINAL.get(), new Item.Properties().setId(key)));
    public static final RegistryEntry<BlockItem> MERCHANT_STALL = ITEMS.register("merchant_stall",
            key -> new BlockItem(ModBlocks.MERCHANT_STALL.get(), new Item.Properties().setId(key)));
    public static final RegistryEntry<CreditChipItem> CREDIT_CHIP = ITEMS.register("credit_chip",
            key -> new CreditChipItem(new Item.Properties().setId(key).stacksTo(16)));
    public static final RegistryEntry<TradersHandbookItem> TRADERS_HANDBOOK = ITEMS.register("traders_handbook",
            key -> new TradersHandbookItem(new Item.Properties().setId(key).stacksTo(1)));

    private ModItems() {
    }

    public static void init() {
        CoreCreativeTab.add(BANK_TERMINAL);
        CoreCreativeTab.add(PLAYER_SHOP);
        CoreCreativeTab.add(TRADING_TERMINAL);
        CoreCreativeTab.add(MARKET_BOARD);
        CoreCreativeTab.add(AUTO_TRADER);
        CoreCreativeTab.add(EXCHANGE_TERMINAL);
        CoreCreativeTab.add(MERCHANT_STALL);
        CoreCreativeTab.add(CREDIT_CHIP);
        CoreCreativeTab.add(TRADERS_HANDBOOK);
    }
}
