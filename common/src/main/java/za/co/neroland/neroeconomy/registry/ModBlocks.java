package za.co.neroland.neroeconomy.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import za.co.neroland.nerolandcore.registry.RegistrationProvider;
import za.co.neroland.nerolandcore.registry.RegistrationProvider.RegistryEntry;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.block.AutoTraderBlock;
import za.co.neroland.neroeconomy.block.BankTerminalBlock;
import za.co.neroland.neroeconomy.block.PlayerShopBlock;
import za.co.neroland.neroeconomy.block.TradingTerminalBlock;
import za.co.neroland.neroeconomy.block.MarketBoardBlock;

public final class ModBlocks {
    public static final RegistrationProvider<Block> BLOCKS =
            RegistrationProvider.get(Registries.BLOCK, NeroEconomyCommon.MOD_ID);
    public static final RegistryEntry<BankTerminalBlock> BANK_TERMINAL = BLOCKS.register("bank_terminal",
            key -> new BankTerminalBlock(properties().setId(key)));
    public static final RegistryEntry<PlayerShopBlock> PLAYER_SHOP = BLOCKS.register("player_shop",
            key -> new PlayerShopBlock(properties().setId(key)));
    public static final RegistryEntry<TradingTerminalBlock> TRADING_TERMINAL = BLOCKS.register("trading_terminal",
            key -> new TradingTerminalBlock(properties().setId(key)));
    public static final RegistryEntry<MarketBoardBlock> MARKET_BOARD = BLOCKS.register("market_board",
            key -> new MarketBoardBlock(properties().setId(key)));
    public static final RegistryEntry<AutoTraderBlock> AUTO_TRADER = BLOCKS.register("auto_trader",
            key -> new AutoTraderBlock(properties().setId(key)));

    private ModBlocks() {
    }

    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5F, 6.0F)
                .requiresCorrectToolForDrops().sound(SoundType.METAL).noOcclusion();
    }

    public static void init() {
    }
}
