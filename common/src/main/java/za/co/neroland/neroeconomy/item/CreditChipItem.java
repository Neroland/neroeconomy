package za.co.neroland.neroeconomy.item;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.block.BankTerminalBlock;
import za.co.neroland.neroeconomy.block.BankTerminalBlockEntity;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.TransactionType;

/** One-shot server-side credit redemption, valid only against a live Bank Terminal. */
public final class CreditChipItem extends Item {
    public CreditChipItem(Properties properties) { super(properties); }
    @Override public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof BankTerminalBlock))
            return InteractionResult.PASS;
        if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;
        // A terminal without its block entity is a half-placed or broken block; never mint against it.
        if (!(context.getLevel().getBlockEntity(context.getClickedPos()) instanceof BankTerminalBlockEntity terminal)
                || terminal.isRemoved()) return InteractionResult.PASS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.FAIL;
        ItemStack held = context.getItemInHand();
        if (held.isEmpty()) return InteractionResult.FAIL;
        long value = EconomyConfig.CREDIT_CHIP_VALUE.get();
        // Consume first: a chip that is spent before the deposit can never be redeemed twice, and a
        // rejected deposit gives it straight back.
        held.shrink(1);
        if (!NeroEconomyCommon.CURRENCY_PROVIDER.depositTyped(player.getUUID(), NeroEconomyCommon.CREDITS,
                value, TransactionType.CREDIT_CHIP_REDEMPTION)) {
            held.grow(1);
            return InteractionResult.FAIL;
        }
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "item.neroeconomy.credit_chip.redeemed", value));
        return InteractionResult.SUCCESS;
    }
}
