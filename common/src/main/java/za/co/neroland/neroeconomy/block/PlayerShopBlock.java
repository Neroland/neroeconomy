package za.co.neroland.neroeconomy.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.registry.BlockCodecs;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.shop.ShopOwnershipState;

public final class PlayerShopBlock extends BaseEntityBlock {
    public static final MapCodec<PlayerShopBlock> CODEC = BlockCodecs.simple(PlayerShopBlock::new);
    /** The stall counter faces the placer. Visual only. */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public PlayerShopBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }
    /**
     * Who may break a shop: its owner, a permission-level-2 operator, or anyone at all when the shop
     * has no owner on record (its owner's data was erased, or it was placed by a command). Breaking
     * never loses stock: whatever is in escrow goes to the listed owner's vault.
     */
    public static boolean canBreak(Level level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof PlayerShopBlockEntity shop)) return true;
        boolean operator = Commands.LEVEL_GAMEMASTERS.check(player.permissions());
        // While the economy is frozen the owner records cannot be read, so "no owner on record"
        // proves nothing: only an operator may break a shop until the store is back.
        if (!level.isClientSide() && EconomyHealth.frozen()) return operator;
        return !shop.hasOwner() || shop.isOwner(player.getUUID()) || operator;
    }

    /**
     * Forgets the shop that stood at {@code pos}: drops its owner row and releases any listing still
     * recorded there. Safe to call when nothing is recorded, so every removal path may call it.
     */
    private static void forgetShop(ServerLevel level, BlockPos pos) {
        ShopOwnershipState.get(level.getServer()).remove(level, pos);
        MarketSavedData.get(level.getServer()).releaseShopsAt(ShopOwnershipState.key(level, pos));
    }
    protected MapCodec<PlayerShopBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

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

    /**
     * A freshly placed shop starts with no owner and no listing. Anything still recorded at this
     * position belongs to an earlier shop whose removal was never reported, so it is settled here
     * before {@link #setPlacedBy} claims the new one for its placer.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !oldState.is(state.getBlock())) forgetShop(serverLevel, pos);
    }

    /**
     * Runs for every removal that updates neighbours — mining, explosions, pistons, {@code destroy} —
     * whoever or whatever caused it. The block entity is already gone here, so the records are
     * settled by position. Removals that suppress this hook are caught by {@code ShopSweep}.
     */
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos,
            boolean movedByPiston) {
        forgetShop(level, pos);
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
    }
}
