package za.co.neroland.neroeconomy.item;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.block.BankTerminalBlock;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.TransactionType;

/** One-shot server-side credit redemption, valid only against a Bank Terminal. */
public final class CreditChipItem extends Item {
    public CreditChipItem(Properties properties) { super(properties); }
    @Override public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof BankTerminalBlock))
            return InteractionResult.PASS;
        if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
        long value = EconomyConfig.CREDIT_CHIP_VALUE.get();
        if (!NeroEconomyCommon.CURRENCY_PROVIDER.depositTyped(player.getUUID(), NeroEconomyCommon.CREDITS,
                value, TransactionType.CREDIT_CHIP_REDEMPTION)) return InteractionResult.FAIL;
        context.getItemInHand().shrink(1);
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "item.neroeconomy.credit_chip.redeemed", value));
        return InteractionResult.SUCCESS;
    }
}
