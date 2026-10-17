package com.maixr;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.mojang.serialization.MapCodec;

/** 注册自定义区块生成器 codec（供数据包维度 json 引用 maixr:broken_overworld）。 */
public final class MaixrChunkGenerators {
    /**
     * 【1.21.1 修正】注册表的值类型必须是 {@code MapCodec<? extends ChunkGenerator>}，
     * <b>不能</b>写成 {@code Codec<...>}。
     *
     * <p>1.21.1 的 {@code Registries.CHUNK_GENERATOR} 实际是
     * {@code Registry<MapCodec<? extends ChunkGenerator>>}。若注册成父类型 {@code Codec}，
     * 反序列化时会把 {@code MapCodec.MapCodecCodec} 当作 {@code MapCodec} 强转，
     * 直接抛 {@code ClassCastException: MapCodec$MapCodecCodec cannot be cast to MapCodec}，
     * 表现为「世界生成页面卡住 / Failed to parse maixr:dimension/overworld_broken.json」。
     */
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(
                    (net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<MapCodec<? extends ChunkGenerator>>>)
                            (Object) Registries.CHUNK_GENERATOR,
                    MaixrMod.MODID);

    private MaixrChunkGenerators() {}

    public static void register(IEventBus modBus) {
        CHUNK_GENERATORS.register("broken_overworld", () -> BrokenOverworldChunkGenerator.CODEC);
        // 破碎世界（maixr:overworld_broken）专用：空岛平台 + 95% 虚空，但建筑遗迹照常生成
        CHUNK_GENERATORS.register("broken_skyblock", () -> BrokenSkyblockChunkGenerator.CODEC);
        // 「破碎」世界类型的标记生成器：行为与原版主世界完全一致，只用来标记"玩家选了破碎"
        CHUNK_GENERATORS.register("broken_world_marker", () -> BrokenWorldMarkerGenerator.CODEC);
        CHUNK_GENERATORS.register(modBus);
    }
}
