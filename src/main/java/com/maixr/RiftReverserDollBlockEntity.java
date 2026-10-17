package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** 裂隙逆转者玩偶方块实体：复用玩偶旋转逻辑（独立 BE 类型）。 */
public class RiftReverserDollBlockEntity extends MaixrDollBlockEntity {
    public RiftReverserDollBlockEntity(BlockPos pos, BlockState state) {
        super(MaixrBlockEntities.RIFT_REVERSER_DOLL.get(), pos, state);
    }
}
