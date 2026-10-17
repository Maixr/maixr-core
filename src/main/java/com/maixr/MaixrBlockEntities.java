package com.maixr;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.minecraft.core.registries.BuiltInRegistries;

public final class MaixrBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, MaixrMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MaixrDollBlockEntity>> SKIN_DOLL =
            BLOCK_ENTITIES.register("maixr_skin_doll",
                    () -> BlockEntityType.Builder.of(MaixrDollBlockEntity::new, MaixrMod.SKIN_DOLL.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TruthDollBlockEntity>> TRUTH_DOLL =
            BLOCK_ENTITIES.register("truth_doll",
                    () -> BlockEntityType.Builder.of(TruthDollBlockEntity::new, MaixrMod.TRUTH_DOLL.get()).build(null));

    // 【已删除】人造排污鸟巢 / 粉红鸟巢（EggSewer / PinkEggSewer）：
    //   旧实现基于 Forge 1.20.1 能力系统（LazyOptional + ForgeCapabilities），
    //   NeoForge 1.21.1 已移除 LazyOptional、能力注册方式也变了。
    //   旧代码原放在 _deferred_nest/，现已整体删除（git 历史可查）。
    //   若将来重做这两台多方块机器，需按 NeoForge 的能力注册方式整套重写。

    private MaixrBlockEntities() {}

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }
}
