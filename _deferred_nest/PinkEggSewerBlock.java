package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;

/**
 * 【人造粉红鸟巢端口】—— 「人造粉红鸟巢」多方块机器的交互端口方块。
 *
 * <p>与 {@link EggSewerBlock 人造鸟巢端口} 完全同构：一台机器需要 <b>4 个端口</b>
 * （3×3×3 的中层四条边中点）。任意端口都能：
 * <ul>
 *   <li>右键打开机器 UI（四个端口看到同一台机器的数据）</li>
 *   <li>任意面接入 FE 电力</li>
 *   <li>任意面被流体管道抽走产出的粉红粘液</li>
 * </ul>
 *
 * <p>贴图偏粉色，便于与排污鸟巢区分。
 */
public class PinkEggSewerBlock extends BaseEntityBlock {

    public PinkEggSewerBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(3.5F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PinkEggSewerBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof PinkEggSewerBlockEntity port) {
            if (!port.isFormed()) {
                player.displayClientMessage(Component.translatable("message.maixr.pink_egg_sewer.not_formed"), true);
                return InteractionResult.CONSUME;
            }
            NetworkHooks.openScreen((net.minecraft.server.level.ServerPlayer) player, new MenuProvider() {
                @Override
                public Component getDisplayName() {
                    return Component.translatable("block.maixr.pink_egg_sewer");
                }

                @Override
                public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
                        int id, net.minecraft.world.entity.player.Inventory inv, Player p) {
                    return new PinkEggSewerMenu(id, inv, port, port.data());
                }
            }, pos);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof PinkEggSewerBlockEntity port) {
                for (int i = 0; i < port.inventory().getSlots(); i++) {
                    net.minecraft.world.item.ItemStack s = port.inventory().getStackInSlot(i);
                    if (!s.isEmpty()) Block.popResource(level, pos, s);
                }
            }
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, MaixrBlockEntities.PINK_EGG_SEWER.get(), PinkEggSewerBlockEntity::clientTick);
        }
        return createTickerHelper(type, MaixrBlockEntities.PINK_EGG_SEWER.get(), PinkEggSewerBlockEntity::serverTick);
    }
}
