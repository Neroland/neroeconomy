package za.co.neroland.neroeconomy.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

import org.jetbrains.annotations.Nullable;

import za.co.neroland.nerolandcore.registry.BlockCodecs;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.registry.ModBlockEntities;
import za.co.neroland.neroeconomy.trader.AutoTraders;

/**
 * The Auto-Trader block. Its placer owns it; only the owner opens it. Hoppers, ducts, drones and
 * pipes feed its input slots and empty its output slots from any face.
 */
public final class AutoTraderBlock extends BaseEntityBlock {
    public static final MapCodec<AutoTraderBlock> CODEC = BlockCodecs.simple(AutoTraderBlock::new);
    /** The order display faces the placer. Visual only: every face takes and gives items alike. */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public AutoTraderBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    /**
     * Who may break an Auto-Trader: its owner, a permission-level-2 operator, or anyone at all when
     * it has no owner on record (its owner's data was erased, or it was placed by a command) — the
     * same policy as a Player Shop. Breaking never loses anything the rule held.
     */
    public static boolean canBreak(Level level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof AutoTraderBlockEntity trader)) return true;
        boolean operator = Commands.LEVEL_GAMEMASTERS.check(player.permissions());
        // While the economy is frozen the owner records cannot be read, so "no owner on record"
        // proves nothing: only an operator may break one until the store is back.
        if (!level.isClientSide() && EconomyHealth.frozen()) return operator;
        return !trader.hasOwner() || trader.isOwner(player.getUUID()) || operator;
    }

    protected MapCodec<AutoTraderBlock> codec() { return CODEC; }
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
        return new AutoTraderBlockEntity(pos, state);
    }

    @Nullable @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null
                : createTickerHelper(type, ModBlockEntities.AUTO_TRADER.get(), AutoTraderBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel && placer instanceof ServerPlayer player
                && !AutoTraders.claim(serverLevel, pos, player.getUUID()))
            player.sendSystemMessage(Component.translatable("message.neroeconomy.auto_trader.not_claimed"));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof AutoTraderBlockEntity trader) {
            // Only the owner opens it. Nobody else is told who that is.
            if (trader.isOwner(serverPlayer.getUUID())) serverPlayer.openMenu(trader);
            else serverPlayer.sendSystemMessage(Component.translatable(trader.hasOwner()
                    ? "message.neroeconomy.auto_trader.not_owner" : "message.neroeconomy.auto_trader.no_owner"));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (level instanceof Level actual && !actual.isClientSide() && !canBreak(actual, pos, player)) return 0.0F;
        return super.getDestroyProgress(state, player, level, pos);
    }

    /**
     * A freshly placed Auto-Trader starts with no owner and no rule. Anything still recorded at
     * this position belongs to an earlier one whose removal was never reported, so it is settled
     * here before {@link #setPlacedBy} claims the new one for its placer.
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel serverLevel && !oldState.is(state.getBlock())) AutoTraders.removed(serverLevel, pos, null);
    }

    /**
     * Runs for every removal that updates neighbours, whoever or whatever caused it. The block
     * entity is already gone here and has returned what it held; this settles the records by
     * position, which is safe to do twice. Removals that suppress this hook are caught by the sweep.
     */
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos,
            boolean movedByPiston) {
        AutoTraders.removed(level, pos, null);
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
    }
}
