package za.co.neroland.neroeconomy.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import java.util.List;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;

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

    /** Classload-forcing no-op: touching this class runs the static registrations above. */
    public static void init() {
    }

    /** Every NeroEconomy item, in display order, for {@link ModCreativeTab}. Resolved when the tab is built. */
    public static List<ItemLike> creativeContents() {
        return List.of(BANK_TERMINAL.get(), PLAYER_SHOP.get(), TRADING_TERMINAL.get(), MARKET_BOARD.get(),
                AUTO_TRADER.get(), EXCHANGE_TERMINAL.get(), MERCHANT_STALL.get(), CREDIT_CHIP.get(),
                TRADERS_HANDBOOK.get());
    }
}
