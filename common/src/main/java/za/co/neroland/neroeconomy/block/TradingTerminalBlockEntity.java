package za.co.neroland.neroeconomy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import za.co.neroland.neroeconomy.menu.TradingTerminalMenu;
import za.co.neroland.neroeconomy.registry.ModBlockEntities;

public final class TradingTerminalBlockEntity extends BlockEntity implements MenuProvider {
    public TradingTerminalBlockEntity(BlockPos pos, BlockState state) { super(ModBlockEntities.TRADING_TERMINAL.get(), pos, state); }
    @Override public Component getDisplayName() { return Component.translatable("container.neroeconomy.trading_terminal"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new TradingTerminalMenu(id, inventory, this);
    }
}
