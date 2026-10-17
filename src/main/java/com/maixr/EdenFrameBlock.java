package com.maixr;

import net.minecraft.world.level.block.Block;

/** 伊甸框架：围着伊甸核心机摆 3x3 外圈，构成多方块结构。 */
public class EdenFrameBlock extends Block {
    public EdenFrameBlock(Properties properties) {
        super(properties);
    }

    /**
     * 【1.21 移植】新版的 Block 家族要求实现 {@code codec()}。
     * 1.20.1 没有这个抽象方法；1.21 用 {@code simpleCodec(构造引用)} 是最简写法，
     * 效果等同于"这个方块没有额外状态需要序列化"。
     */
    public static final com.mojang.serialization.MapCodec<EdenFrameBlock> CODEC =
            simpleCodec(EdenFrameBlock::new);

    @Override
    protected com.mojang.serialization.MapCodec<? extends Block> codec() {
        return CODEC;
    }
}
