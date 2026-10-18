package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

/** 致回忆中的明日方块实体：完整继承 Maixr 玩偶的 ME 网络接入（占 1 频道 / 每分钟产出 / 满仓重试），只换产出物。 */
public class MemoryTomorrowDollBlockEntity extends MaixrDollBlockEntity {
    public MemoryTomorrowDollBlockEntity(BlockPos pos, BlockState state) {
        super(MaixrBlockEntities.MEMORY_TOMORROW_DOLL.get(), pos, state);
    }

    @Override
    protected Item getProducedItem() {
        return MaixrMod.MEMORY_TOMORROW_DOLL_ITEM.get();
    }
}
