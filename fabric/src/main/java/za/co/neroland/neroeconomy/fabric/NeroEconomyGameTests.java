package za.co.neroland.neroeconomy.fabric;

//? if >=1.21.5 {
import net.fabricmc.fabric.api.gametest.v1.GameTest;
//?} else {
/*import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
*///?}

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

import za.co.neroland.neroeconomy.block.BankTerminalBlockEntity;
import za.co.neroland.neroeconomy.block.PlayerShopBlockEntity;
import za.co.neroland.neroeconomy.registry.ModBlocks;

/** Fabric GameTests for behavior that needs a real level and block-entity lifecycle. */
//? if >=1.21.5 {
public final class NeroEconomyGameTests {
//?} else {
/*// Before 1.21.5 Fabric's test entrypoint is a FabricGameTest and every test names its (empty) structure.
public final class NeroEconomyGameTests implements FabricGameTest {
*///?}
    //? if >=1.21.5 {
    @GameTest
    //?} else {
    /*@GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    *///?}
    public void bankTerminalCreatesItsBlockEntity(GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, ModBlocks.BANK_TERMINAL.get());
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(BlockPos.ZERO))
                instanceof BankTerminalBlockEntity)) {
            fail(helper, "Bank Terminal did not create its block entity");
            return;
        }
        helper.succeed();
    }

    //? if >=1.21.5 {
    @GameTest
    //?} else {
    /*@GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    *///?}
    public void playerShopHidesReservedStockFromAutomation(GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, ModBlocks.PLAYER_SHOP.get());
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(BlockPos.ZERO))
                instanceof PlayerShopBlockEntity shop)) {
            fail(helper, "Player Shop did not create its block entity");
            return;
        }
        if (shop.getSlotsForFace(net.minecraft.core.Direction.UP).length != 0) {
            fail(helper, "Player Shop exposed reserved stock to automation");
            return;
        }
        helper.succeed();
    }

    /** The offer slot must be a single write-through slot backed by the live escrow, never a copy. */
    //? if >=1.21.5 {
    @GameTest
    //?} else {
    /*@GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    *///?}
    public void playerShopOffersOneWriteThroughSlot(GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, ModBlocks.PLAYER_SHOP.get());
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(BlockPos.ZERO))
                instanceof PlayerShopBlockEntity shop)) {
            fail(helper, "Player Shop did not create its block entity");
            return;
        }
        if (shop.getContainerSize() != 1) {
            fail(helper, "Player Shop advertised slots it cannot store");
            return;
        }
        shop.claim(java.util.UUID.randomUUID());
        shop.setItem(PlayerShopBlockEntity.OFFER_SLOT,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 4));
        if (shop.stockCount() != 4) {
            fail(helper, "Offer slot did not write through to the escrow");
            shop.cancel();
            return;
        }
        shop.getItem(PlayerShopBlockEntity.OFFER_SLOT).shrink(1);
        shop.setChanged();
        shop.syncOffer();
        boolean live = shop.stockCount() == 3 && shop.getItem(PlayerShopBlockEntity.OFFER_SLOT).getCount() == 3;
        shop.cancel();
        if (!live) {
            fail(helper, "Offer slot handed out a detached copy of the escrow");
            return;
        }
        helper.succeed();
    }

    /** Fails the test; GameTestHelper.fail took a Component instead of a String on 1.21.5 - 1.21.8. */
    private static void fail(GameTestHelper helper, String message) {
        //? if >=1.21.5 && <1.21.9 {
        /*helper.fail(net.minecraft.network.chat.Component.literal(message));
        *///?} else {
        helper.fail(message);
        //?}
    }
}
