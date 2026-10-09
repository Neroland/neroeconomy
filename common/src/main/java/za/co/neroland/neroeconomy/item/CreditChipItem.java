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
import za.co.neroland.neroeconomy.economy.CreditChipRules;
import za.co.neroland.neroeconomy.economy.CreditChipSchedule;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.LedgerResult;
import za.co.neroland.neroeconomy.economy.TransactionType;

/**
 * One-shot server-side credit redemption, valid only against a live Bank Terminal. The credits are
 * paid out of the Reserve, and each chip an account redeems in one economy day is worth less than
 * the one before (see {@link CreditChipSchedule}).
 */
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
        // The chip is a faucet, so it is rate limited per account and economy day. Every refusal
        // happens before the chip is touched, so the player simply keeps it.
        int dailyLimit = EconomyConfig.CREDIT_CHIP_DAILY_LIMIT.get();
        String refusal = CreditChipRules.refusal(EconomyHealth.frozen(), player.isCreative(), player.isSpectator(),
                player.hasInfiniteMaterials(), dailyLimit);
        if (refusal != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(refusal));
            return InteractionResult.FAIL;
        }
        // What this chip is worth depends on how many the account has already redeemed today.
        int redeemedToday = NeroEconomyCommon.CURRENCY_PROVIDER.chipRedemptionsToday(player.getUUID());
        long value = CreditChipSchedule.value(EconomyConfig.CREDIT_CHIP_VALUE.get(), redeemedToday);
        if (!NeroEconomyCommon.CURRENCY_PROVIDER.reserveChipRedemption(player.getUUID(), dailyLimit)) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "item.neroeconomy.credit_chip.limit_reached", dailyLimit));
            return InteractionResult.FAIL;
        }
        // Consume first: a chip that is spent before the payment can never be redeemed twice, and a
        // refused payment gives it straight back along with the reservation it took.
        held.shrink(1);
        LedgerResult paid = NeroEconomyCommon.CURRENCY_PROVIDER.payFromReserve(CreditChipRules.BUDGET,
                player.getUUID(), value, TransactionType.CREDIT_CHIP_REDEMPTION);
        if (!paid.ok()) {
            held.grow(1);
            NeroEconomyCommon.CURRENCY_PROVIDER.releaseChipRedemption(player.getUUID());
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    paid == LedgerResult.FROZEN ? EconomyHealth.FROZEN_KEY
                            : paid == LedgerResult.RESERVE_SHORT ? "item.neroeconomy.credit_chip.reserve_empty"
                            : paid == LedgerResult.BUDGET_SPENT ? "item.neroeconomy.credit_chip.budget_spent"
                            : "item.neroeconomy.credit_chip.refused"));
            return InteractionResult.FAIL;
        }
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "item.neroeconomy.credit_chip.redeemed", value, CreditChipSchedule.percent(redeemedToday)));
        return InteractionResult.SUCCESS;
    }
}
