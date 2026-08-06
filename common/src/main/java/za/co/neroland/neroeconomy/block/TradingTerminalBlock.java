package za.co.neroland.neroeconomy.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public final class TradingTerminalBlock extends BaseEntityBlock {
    public static final MapCodec<TradingTerminalBlock> CODEC = simpleCodec(TradingTerminalBlock::new);
    public TradingTerminalBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<TradingTerminalBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TradingTerminalBlockEntity(pos, state);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof TradingTerminalBlockEntity terminal) serverPlayer.openMenu(terminal);
        return InteractionResult.SUCCESS;
    }
}
