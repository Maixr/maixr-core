package com.maixr;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.melanx.skyblockbuilder.world.chunkgenerators.SkyblockNoiseBasedChunkGenerator;
import de.melanx.skyblockbuilder.world.flat.FlatLayers;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.concurrent.CompletableFuture;

/**
 * 破碎空岛生成器 —— 破碎世界（maixr:overworld_broken）的核心。
 *
 * <p><b>做法</b>：继承 {@link SkyblockNoiseBasedChunkGenerator}（空岛平台铺层），
 * 叠加 {@link BrokenOverworldChunkGenerator} 的虚空判定（95% 区块不生成地形）。</p>
 *
 * <h2>★ 关键设计：结构照常生成</h2>
 *
 * <p>普通的"破碎"实现会在虚空区块里连 {@code createStructures} 一起跳过，
 * 结果整个世界只剩空岛、没有任何遗迹。本类<b>刻意不跳过结构</b>：</p>
 *
 * <ul>
 *   <li>{@code createStructures} / {@code applyBiomeDecoration} —— <b>不跳过</b>，
 *       由父类（Skyblock 生成器）正常处理，所以村庄、遗迹、要塞都会照常出现</li>
 *   <li>{@code fillFromNoise} —— 父类本来就只返回空 chunk（地形在 buildSurface 里铺），
 *       无需额外处理</li>
 *   <li>{@code buildSurface} —— <b>虚空区块跳过</b>：这就是"破碎"的体现，
 *       这些区块不会铺空岛平台，掉下去就是虚空</li>
 * </ul>
 *
 * <p>换句话说：<b>地形是碎的，建筑是完整的</b>。</p>
 */
public class BrokenSkyblockChunkGenerator extends SkyblockNoiseBasedChunkGenerator {

    /**
     * 【1.21 修正】必须是 {@link MapCodec}，不能声明成父类型 {@code Codec}。
     *
     * <p>{@code Registries.CHUNK_GENERATOR} 的值类型是 {@code MapCodec<? extends ChunkGenerator>}；
     * 声明成 {@code Codec} 会让区块生成器注册表在反序列化维度 json 时抛
     * {@code ClassCastException}，症状是「创建世界页面卡住」。</p>
     */
    public static final MapCodec<BrokenSkyblockChunkGenerator> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
                    NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(g -> g.generatorSettings),
                    ResourceKey.codec(net.minecraft.core.registries.Registries.DIMENSION)
                            .fieldOf("dimension").forGetter(BrokenSkyblockChunkGenerator::getDimension),
                    FlatLayers.CODEC.optionalFieldOf("layers", FlatLayers.EMPTY)
                            .forGetter(BrokenSkyblockChunkGenerator::getFlatLayers)
            ).apply(instance, BrokenSkyblockChunkGenerator::new));

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    /** 废墟比例：有多少区块会保留"完整地形"（其余是虚空）。 */
    public static final double GENERATED_CHANCE = 0.05;

    public BrokenSkyblockChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings,
                                        ResourceKey<Level> dimension, FlatLayers layers) {
        super(biomeSource, settings, dimension, layers);
    }

    /** 与 {@link BrokenOverworldChunkGenerator} 完全一致的确定性哈希（同坐标同结果）。 */
    public static boolean isVoided(int chunkX, int chunkZ) {
        return BrokenOverworldChunkGenerator.isVoided(chunkX, chunkZ);
    }

    private static boolean isVoidedChunk(ChunkAccess chunk) {
        return isVoided(chunk.getPos().x, chunk.getPos().z);
    }

    // ==========================================================================
    //  ★ 破碎逻辑：只作用于"地形生成"，不碰"结构生成"
    // ==========================================================================

    /**
     * 虚空区块不铺空岛平台 —— 这是"破碎"的核心体现。
     *
     * <p>注意：<b>结构生成不走这里</b>，所以遗迹照常出现（可能悬在空中，
     * 这正是"破碎世界里残留的建筑"该有的样子）。</p>
     */
    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState randomState,
                             ChunkAccess chunk) {
        if (isVoidedChunk(chunk)) return;
        super.buildSurface(level, structureManager, randomState, chunk);
    }

    /** 虚空区块不雕刻洞穴 —— 否则会给破碎的岛掏洞，看着很怪。 */
    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState randomState, BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunk, GenerationStep.Carving carving) {
        if (isVoidedChunk(chunk)) return;
        super.applyCarvers(level, seed, randomState, biomeManager, structureManager, chunk, carving);
    }

    // ==========================================================================
    //  ★ 以下是"不跳过"的部分 —— 保证建筑遗迹自然生成
    // ==========================================================================

    /**
     * 结构生成 <b>不跳过</b>。
     *
     * <p>原版 & 我们的破碎生成器都会在这里对虚空区块 return，
     * 那样<b>村子里不会有任何遗迹</b>。本类刻意调 super，
     * 让结构在世界生成期正常落位 —— 悬在虚空中的残破建筑正是这个世界要的氛围。</p>
     */
    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState,
                                 StructureManager structureManager, ChunkAccess chunk,
                                 StructureTemplateManager structureTemplateManager) {
        super.createStructures(registryAccess, structureState, structureManager, chunk, structureTemplateManager);
    }

    /**
     * 生物群系装饰（树木、花草、矿石…）<b>不跳过</b>。
     *
     * <p>同理：让植被和矿物照常生成，只是它们脚下往往是虚空。</p>
     */
    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        super.applyBiomeDecoration(level, chunk, structureManager);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                        StructureManager structureManager, ChunkAccess chunk) {
        return super.fillFromNoise(blender, randomState, structureManager, chunk);
    }
}
