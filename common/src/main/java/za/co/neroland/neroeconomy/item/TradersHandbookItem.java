package za.co.neroland.neroeconomy.item;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import za.co.neroland.neroeconomy.handbook.HandbookOpener;

/**
 * The Trader's Handbook: used in hand it opens the guide on the client. The book has no server
 * side at all: no menu, no slots, nothing stored per player and nothing sent when it is read.
 */
public final class TradersHandbookItem extends Item {
    public static final String TOOLTIP = "item.neroeconomy.traders_handbook.tooltip";

    public TradersHandbookItem(Properties properties) { super(properties); }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) HandbookOpener.open();
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        tooltip.accept(Component.translatable(TOOLTIP).withStyle(ChatFormatting.GRAY));
    }
}
