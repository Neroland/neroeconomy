package za.co.neroland.neroeconomy.registry;

import java.util.Set;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.block.AutoTraderBlockEntity;
import za.co.neroland.neroeconomy.block.BankTerminalBlockEntity;
import za.co.neroland.neroeconomy.block.PlayerShopBlockEntity;
import za.co.neroland.neroeconomy.block.TradingTerminalBlockEntity;
import za.co.neroland.neroeconomy.block.MarketBoardBlockEntity;

public final class ModBlockEntities {
    public static final RegistrationProvider<BlockEntityType<?>> TYPES =
            RegistrationProvider.get(Registries.BLOCK_ENTITY_TYPE, NeroEconomyCommon.MOD_ID);
    public static final RegistryEntry<BlockEntityType<BankTerminalBlockEntity>> BANK_TERMINAL =
            TYPES.register("bank_terminal", key -> new BlockEntityType<>(BankTerminalBlockEntity::new,
                    Set.of(ModBlocks.BANK_TERMINAL.get())));
    public static final RegistryEntry<BlockEntityType<PlayerShopBlockEntity>> PLAYER_SHOP =
            TYPES.register("player_shop", key -> new BlockEntityType<>(PlayerShopBlockEntity::new,
                    Set.of(ModBlocks.PLAYER_SHOP.get())));
    public static final RegistryEntry<BlockEntityType<TradingTerminalBlockEntity>> TRADING_TERMINAL =
            TYPES.register("trading_terminal", key -> new BlockEntityType<>(TradingTerminalBlockEntity::new,
                    Set.of(ModBlocks.TRADING_TERMINAL.get())));
    public static final RegistryEntry<BlockEntityType<MarketBoardBlockEntity>> MARKET_BOARD =
            TYPES.register("market_board", key -> new BlockEntityType<>(MarketBoardBlockEntity::new,
                    Set.of(ModBlocks.MARKET_BOARD.get())));

    public static final RegistryEntry<BlockEntityType<AutoTraderBlockEntity>> AUTO_TRADER =
            TYPES.register("auto_trader", key -> new BlockEntityType<>(AutoTraderBlockEntity::new,
                    Set.of(ModBlocks.AUTO_TRADER.get())));

    private ModBlockEntities() {
    }

    public static void init() {
    }
}
