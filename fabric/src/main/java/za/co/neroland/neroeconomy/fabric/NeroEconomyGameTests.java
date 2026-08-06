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
}
