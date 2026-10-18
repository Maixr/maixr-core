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

    // ==========================================================================
    //  ★ 旧版区块级虚空判定（自本类起私有保留）
    //
    //  主世界「破碎」类型已改用逐方块碎裂场（见 FractureDensityFunction），本判定
    //  只服务于老维度 maixr:overworld_broken（空岛 + 95% 虚空）—— 为兼容老存档
    //  已生成区块，其行为必须与旧版逐位一致，不得改动任何常量与哈希。
    // ==========================================================================

    /** 出生点附近的虚空比例（0.20 = 20% 虚空）。 */
    private static final double NEAR_VOID_CHANCE = 0.20D;

    /** 最远处的虚空比例（0.95 = 95% 虚空，只剩 5% 残陆）。 */
    private static final double FAR_VOID_CHANCE = 0.95D;

    /** 从出生点走多远（区块）达到最碎的程度（50 区块 ≈ 800 格）。 */
    private static final int RAMP_CHUNKS = 50;

    /** 与旧 KubeJS chunkRoll 完全一致的确定性 0..1 哈希（不依赖世界种子）。 */
    private static double roll01(int chunkX, int chunkZ) {
        int h = chunkX * 0x1638D74F ^ chunkZ * 0x9E3779B1;
        h = h ^ (h >>> 13);
        h = h * 0x85EBCA6B;
        h = h ^ (h >>> 16);
        return (h & 0xFFFFFFFFL) / 4294967296.0;
    }

    /** 某个区块的虚空概率 —— 出生点 {@value #NEAR_VOID_CHANCE}，到 {@value #RAMP_CHUNKS} 区块处线性升到 {@value #FAR_VOID_CHANCE}。 */
    private static double voidChance(int chunkX, int chunkZ) {
        int d = Math.max(Math.abs(chunkX), Math.abs(chunkZ));
        double t = Math.min(1.0D, d / (double) RAMP_CHUNKS);
        return NEAR_VOID_CHANCE + (FAR_VOID_CHANCE - NEAR_VOID_CHANCE) * t;
    }

    /** true = 这个区块是虚空。同一个坐标永远得到同一个结果。 */
    private static boolean isVoided(int chunkX, int chunkZ) {
        return roll01(chunkX, chunkZ) < voidChance(chunkX, chunkZ);
    }

    private static boolean isVoidedChunk(ChunkAccess chunk) {
        return isVoided(chunk.getPos().x, chunk.getPos().z);
    }

    public BrokenSkyblockChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings,
                                        ResourceKey<Level> dimension, FlatLayers layers) {
        super(biomeSource, settings, dimension, layers);
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
