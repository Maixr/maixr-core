package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 真理玩偶方块：外观/行为与 Maixr 玩偶一致，仅使用独立方块实体类型。 */
public class TruthSkinDollBlock extends SkinDollBlock {
    public TruthSkinDollBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TruthDollBlockEntity(pos, state);
    }

    /**
     * 【1.21 移植】新版的 Block 家族要求实现 {@code codec()}。
     * 1.20.1 没有这个抽象方法；1.21 用 {@code simpleCodec(构造引用)} 是最简写法，
     * 效果等同于"这个方块没有额外状态需要序列化"。
     */
    public static final com.mojang.serialization.MapCodec<TruthSkinDollBlock> CODEC =
            simpleCodec(TruthSkinDollBlock::new);

    @Override
    protected com.mojang.serialization.MapCodec<? extends SkinDollBlock> codec() {
        return CODEC;
    }
}
