package za.co.neroland.neroeconomy.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.jetbrains.annotations.Nullable;

public final class PlayerShopBlock extends BaseEntityBlock {
    public static final MapCodec<PlayerShopBlock> CODEC = simpleCodec(PlayerShopBlock::new);

    public PlayerShopBlock(Properties properties) { super(properties); }
    public static boolean canBreak(Level level, BlockPos pos, Player player) {
        return !(level.getBlockEntity(pos) instanceof PlayerShopBlockEntity shop)
                || shop.isOwner(player.getUUID());
    }
    @Override protected MapCodec<PlayerShopBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PlayerShopBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide() && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof PlayerShopBlockEntity shop) {
            shop.claim(player.getUUID());
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof PlayerShopBlockEntity shop) {
            serverPlayer.openMenu(shop);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (level instanceof Level actual && !actual.isClientSide()
                && !canBreak(actual, pos, player)) {
            return 0.0F;
        }
        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            if (level.getBlockEntity(pos) instanceof PlayerShopBlockEntity shop) shop.cancel();
            za.co.neroland.neroeconomy.shop.ShopOwnershipState.get(serverLevel.getServer()).remove(serverLevel, pos);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }
}
