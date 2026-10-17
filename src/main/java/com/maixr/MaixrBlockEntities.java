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

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RiftReverserDollBlockEntity>> RIFT_REVERSER_DOLL =
            BLOCK_ENTITIES.register("rift_reverser_doll",
                    () -> BlockEntityType.Builder.of(RiftReverserDollBlockEntity::new, MaixrMod.RIFT_REVERSER_DOLL.get()).build(null));

    // 【1.21.1 移植·暂缓】两个人造鸟巢端口（排污鸟巢 / 粉红鸟巢）：
    //   它们的方块实体用了 Forge 1.20.1 的能力系统（LazyOptional + Capability + ForgeCapabilities），
    //   而 NeoForge 已移除 LazyOptional、能力注册方式也变了，需要整套重写。
    //   暂缓后这两台多方块机器在新版不可用，其余功能不受影响。
    //   原代码见 _deferred_nest/EggSewerBlockEntity.java 与 PinkEggSewerBlockEntity.java。
    //
    // public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EggSewerBlockEntity>> EGG_SEWER = ...
    // public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PinkEggSewerBlockEntity>> PINK_EGG_SEWER = ...

    private MaixrBlockEntities() {}

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }
}
