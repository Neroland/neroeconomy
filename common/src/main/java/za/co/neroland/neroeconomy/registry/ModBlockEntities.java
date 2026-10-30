package za.co.neroland.neroeconomy.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import za.co.neroland.nerolandcore.registry.BlockEntityTypeCompat;
import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.block.AutoTraderBlockEntity;
import za.co.neroland.neroeconomy.block.BankTerminalBlockEntity;
import za.co.neroland.neroeconomy.block.ExchangeTerminalBlockEntity;
import za.co.neroland.neroeconomy.block.MerchantStallBlockEntity;
import za.co.neroland.neroeconomy.block.PlayerShopBlockEntity;
import za.co.neroland.neroeconomy.block.TradingTerminalBlockEntity;
import za.co.neroland.neroeconomy.block.MarketBoardBlockEntity;

public final class ModBlockEntities {
    public static final RegistrationProvider<BlockEntityType<?>> TYPES =
            RegistrationProvider.get(Registries.BLOCK_ENTITY_TYPE, NeroEconomyCommon.MOD_ID);
    public static final RegistryEntry<BlockEntityType<BankTerminalBlockEntity>> BANK_TERMINAL =
            TYPES.register("bank_terminal", key -> BlockEntityTypeCompat.create(BankTerminalBlockEntity::new,
                    ModBlocks.BANK_TERMINAL.get()));
    public static final RegistryEntry<BlockEntityType<PlayerShopBlockEntity>> PLAYER_SHOP =
            TYPES.register("player_shop", key -> BlockEntityTypeCompat.create(PlayerShopBlockEntity::new,
                    ModBlocks.PLAYER_SHOP.get()));
    public static final RegistryEntry<BlockEntityType<TradingTerminalBlockEntity>> TRADING_TERMINAL =
            TYPES.register("trading_terminal", key -> BlockEntityTypeCompat.create(TradingTerminalBlockEntity::new,
                    ModBlocks.TRADING_TERMINAL.get()));
    public static final RegistryEntry<BlockEntityType<MarketBoardBlockEntity>> MARKET_BOARD =
            TYPES.register("market_board", key -> BlockEntityTypeCompat.create(MarketBoardBlockEntity::new,
                    ModBlocks.MARKET_BOARD.get()));

    public static final RegistryEntry<BlockEntityType<AutoTraderBlockEntity>> AUTO_TRADER =
            TYPES.register("auto_trader", key -> BlockEntityTypeCompat.create(AutoTraderBlockEntity::new,
                    ModBlocks.AUTO_TRADER.get()));

    public static final RegistryEntry<BlockEntityType<ExchangeTerminalBlockEntity>> EXCHANGE_TERMINAL =
            TYPES.register("exchange_terminal", key -> BlockEntityTypeCompat.create(ExchangeTerminalBlockEntity::new,
                    ModBlocks.EXCHANGE_TERMINAL.get()));
    public static final RegistryEntry<BlockEntityType<MerchantStallBlockEntity>> MERCHANT_STALL =
            TYPES.register("merchant_stall", key -> BlockEntityTypeCompat.create(MerchantStallBlockEntity::new,
                    ModBlocks.MERCHANT_STALL.get()));

    private ModBlockEntities() {
    }

    public static void init() {
    }
}
