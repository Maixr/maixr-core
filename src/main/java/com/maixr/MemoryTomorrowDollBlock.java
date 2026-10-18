package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 致回忆中的明日：流萤/萨姆双形态玩偶方块。
 *
 * <p>放置态交互：右键（不蹲下）切换旋转动画（继承 {@link SkinDollBlock}）；
 * <b>蹲下右键切换流萤/萨姆外观</b>（带物品时也一样 —— 覆写 useItemOn 保证不被"蹲下放置"抢走）。</p>
 *
 * <p>外观状态只放在 BlockState（{@link #SAM}）上，不放方块实体：
 * 客户端自动同步、{@link MaixrDollBlockRenderer} 按方块状态选模型零改动；
 * 挖掘掉落走默认战利品表，永远是流萤物品（外观仅视觉）。</p>
 */
public class MemoryTomorrowDollBlock extends SkinDollBlock {
    /** 萨姆形态（true = 萨姆，false = 流萤）。 */
    public static final BooleanProperty SAM = BooleanProperty.create("sam");

    public MemoryTomorrowDollBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SAM);
    }

    /** 放置时读手持物品的萨姆标记：变身中放下的玩偶以萨姆形态落地。 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state.setValue(SAM, MemoryTomorrowDollItem.isSam(context.getItemInHand()));
    }

    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                                  Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (player.isCrouching()) {
            toggleSkin(state, level, pos);
            return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (player.isCrouching()) {
            toggleSkin(state, level, pos);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    /** 蹲下右键：流萤 ↔ 萨姆（两处调用路径共用，返回类型各自包装）。 */
    private static void toggleSkin(BlockState state, Level level, BlockPos pos) {
        if (!level.isClientSide) {
            level.setBlock(pos, state.cycle(SAM), Block.UPDATE_ALL);
            level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.6F, 1.2F);
        }
    }

    // ---- 方块实体 ----

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MemoryTomorrowDollBlockEntity(pos, state);
    }

    /** 【1.21 移植】Block 家族必须实现 codec()（simpleCodec 最简）。 */
    public static final com.mojang.serialization.MapCodec<MemoryTomorrowDollBlock> CODEC =
            simpleCodec(MemoryTomorrowDollBlock::new);

    @Override
    protected com.mojang.serialization.MapCodec<? extends SkinDollBlock> codec() {
        return CODEC;
    }
}
