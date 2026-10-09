package za.co.neroland.neroeconomy.block;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import za.co.neroland.neroeconomy.hub.HubAnchor;
import za.co.neroland.neroeconomy.hub.HubOpener;
import za.co.neroland.neroeconomy.hub.HubTab;
import za.co.neroland.neroeconomy.menu.EconomyHubMenu;
import za.co.neroland.neroeconomy.registry.ModBlockEntities;

/**
 * The Merchant Stall: opens the Economy Hub at the Market, showing only what one Merchant House
 * sells. Which House is the one thing it stores; it stores nothing about any player. A stall
 * placed before a House was chosen shows the general House.
 */
public final class MerchantStallBlockEntity extends BlockEntity implements MenuProvider, HubAnchor {
    /** The House every world has, shown until another is chosen. */
    public static final String DEFAULT_HOUSE = "general";
    private static final int MAX_HOUSE_ID = 64;
    private String house = DEFAULT_HOUSE;

    public MerchantStallBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MERCHANT_STALL.get(), pos, state);
    }

    @Override public HubTab hubTab() { return HubTab.MARKET; }
    @Override public String houseFilter() { return house; }
    @Override public Component getDisplayName() { return Component.translatable(HubOpener.TITLE); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new EconomyHubMenu(id, inventory, this, hubTab());
    }

    /** Moves on to the House after the current one in {@code houses} (in order), wrapping round. */
    public void cycleHouse(List<String> houses) {
        if (houses == null || houses.isEmpty()) return;
        int at = houses.indexOf(house);
        house = houses.get((at + 1) % houses.size());
        setChanged();
    }

    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("House", house);
    }

    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        String stored = input.getStringOr("House", DEFAULT_HOUSE);
        house = stored.isBlank() || stored.length() > MAX_HOUSE_ID ? DEFAULT_HOUSE : stored;
    }
}
