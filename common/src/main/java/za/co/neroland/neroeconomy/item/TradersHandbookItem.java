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
//? if >=1.21.5 {
import net.minecraft.world.item.component.TooltipDisplay;
//?}
import net.minecraft.world.level.Level;

import za.co.neroland.neroeconomy.handbook.HandbookOpener;
import za.co.neroland.neroeconomy.sibling.EconomyChannels;
import za.co.neroland.neroeconomy.sibling.MilestoneSection;

/**
 * The Trader's Handbook: used in hand it opens the guide on the client. The book has no menu and
 * no slots, and nothing about what is read is sent. The server records one thing when the book is
 * used: the player's {@code neroeconomy:handbook_read} milestone (one bit, erased with the player).
 */
public final class TradersHandbookItem extends Item {
    public static final String TOOLTIP = "item.neroeconomy.traders_handbook.tooltip";

    public TradersHandbookItem(Properties properties) { super(properties); }

    //? if >=1.21.2 {
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) HandbookOpener.open();
        else read(level, player);
        return InteractionResult.SUCCESS;
    }
    //?} else {
    /*@Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) HandbookOpener.open();
        else read(level, player);
        return net.minecraft.world.InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }
    *///?}

    /** Server side of opening the book: the reader's {@code handbook_read} milestone, nothing else. */
    private static void read(Level level, Player player) {
        if (player == null || player.isSpectator()) return;
        EconomyChannels.earn(level.getServer(), player.getUUID(), MilestoneSection.Milestone.HANDBOOK_READ);
    }

    //? if >=1.21.5 {
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        tooltip.accept(Component.translatable(TOOLTIP).withStyle(ChatFormatting.GRAY));
    }
    //?} elif >=1.20.5 {
    /*@Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, java.util.List<Component> tooltip,
            TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable(TOOLTIP).withStyle(ChatFormatting.GRAY));
    }
    *///?} else {
    /*@Override
    public void appendHoverText(ItemStack stack, Level level, java.util.List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable(TOOLTIP).withStyle(ChatFormatting.GRAY));
    }
    *///?}
}
