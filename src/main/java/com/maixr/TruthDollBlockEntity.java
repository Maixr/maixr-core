package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

/** 真理玩偶方块实体：复用 Maixr 玩偶的整套逻辑，只把"产出物"换成真理玩偶。 */
public class TruthDollBlockEntity extends MaixrDollBlockEntity {
    public TruthDollBlockEntity(BlockPos pos, BlockState state) {
        super(MaixrBlockEntities.TRUTH_DOLL.get(), pos, state);
    }

    @Override
    protected Item getProducedItem() {
        return MaixrMod.TRUTH_DOLL_ITEM.get();
    }
}
