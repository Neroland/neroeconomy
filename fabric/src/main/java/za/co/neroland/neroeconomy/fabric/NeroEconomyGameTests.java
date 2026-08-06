package za.co.neroland.neroeconomy.fabric;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

import za.co.neroland.neroeconomy.block.BankTerminalBlockEntity;
import za.co.neroland.neroeconomy.block.PlayerShopBlockEntity;
import za.co.neroland.neroeconomy.registry.ModBlocks;

/** Fabric GameTests for behavior that needs a real level and block-entity lifecycle. */
public final class NeroEconomyGameTests {
    @GameTest
    public void bankTerminalCreatesItsBlockEntity(GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, ModBlocks.BANK_TERMINAL.get());
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(BlockPos.ZERO))
                instanceof BankTerminalBlockEntity)) {
            helper.fail("Bank Terminal did not create its block entity");
            return;
        }
        helper.succeed();
    }

    @GameTest
    public void playerShopHidesReservedStockFromAutomation(GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, ModBlocks.PLAYER_SHOP.get());
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(BlockPos.ZERO))
                instanceof PlayerShopBlockEntity shop)) {
            helper.fail("Player Shop did not create its block entity");
            return;
        }
        if (shop.getSlotsForFace(net.minecraft.core.Direction.UP).length != 0) {
            helper.fail("Player Shop exposed reserved stock to automation");
            return;
        }
        helper.succeed();
    }

    /** The offer slot must be a single write-through slot backed by the live escrow, never a copy. */
    @GameTest
    public void playerShopOffersOneWriteThroughSlot(GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, ModBlocks.PLAYER_SHOP.get());
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(BlockPos.ZERO))
                instanceof PlayerShopBlockEntity shop)) {
            helper.fail("Player Shop did not create its block entity");
            return;
        }
        if (shop.getContainerSize() != 1) {
            helper.fail("Player Shop advertised slots it cannot store");
            return;
        }
        shop.claim(java.util.UUID.randomUUID());
        shop.setItem(PlayerShopBlockEntity.OFFER_SLOT,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 4));
        if (shop.stockCount() != 4) {
            helper.fail("Offer slot did not write through to the escrow");
            shop.cancel();
            return;
        }
        shop.getItem(PlayerShopBlockEntity.OFFER_SLOT).shrink(1);
        shop.setChanged();
        shop.syncOffer();
        boolean live = shop.stockCount() == 3 && shop.getItem(PlayerShopBlockEntity.OFFER_SLOT).getCount() == 3;
        shop.cancel();
        if (!live) {
            helper.fail("Offer slot handed out a detached copy of the escrow");
            return;
        }
        helper.succeed();
    }
}
