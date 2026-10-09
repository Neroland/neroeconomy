package za.co.neroland.neroeconomy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import za.co.neroland.neroeconomy.hub.HubAnchor;
import za.co.neroland.neroeconomy.hub.HubOpener;
import za.co.neroland.neroeconomy.hub.HubTab;
import za.co.neroland.neroeconomy.menu.EconomyHubMenu;
import za.co.neroland.neroeconomy.registry.ModBlockEntities;

/** The Market Board: opens the Economy Hub at My orders. */
public final class MarketBoardBlockEntity extends BlockEntity implements MenuProvider, HubAnchor {
    public MarketBoardBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MARKET_BOARD.get(), pos, state);
    }

    @Override public HubTab hubTab() { return HubTab.ORDERS; }
    @Override public Component getDisplayName() { return Component.translatable(HubOpener.TITLE); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new EconomyHubMenu(id, inventory, this, hubTab());
    }
}
