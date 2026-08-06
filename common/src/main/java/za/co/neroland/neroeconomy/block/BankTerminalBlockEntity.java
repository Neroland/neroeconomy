package za.co.neroland.neroeconomy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import za.co.neroland.neroeconomy.menu.BankTerminalMenu;
import za.co.neroland.neroeconomy.registry.ModBlockEntities;

public final class BankTerminalBlockEntity extends BlockEntity implements MenuProvider {
    public BankTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BANK_TERMINAL.get(), pos, state);
    }

    @Override public Component getDisplayName() { return Component.translatable("container.neroeconomy.bank_terminal"); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new BankTerminalMenu(id, inventory, player, this);
    }
}
