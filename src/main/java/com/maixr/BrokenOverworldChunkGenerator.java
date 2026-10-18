package com.maixr;

import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.longs.LongSet;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 「破碎」世界类型的<b>主世界生成器</b> —— 薄标记子类，地形全部交给碎裂场。
 *
 * <h2>★ 碎裂场（v2.0 重写）</h2>
 *
 * <p>旧版的「区块级渐变虚空」（{@code isVoided}/{@code roll01}/三常量渐变 +
 * 三阶段跳过）已整体删除。地形破碎现在完全由 vanilla 密度管线内的
 * <b>逐方块碎裂场</b>承担：</p>
 *
 * <ul>
 *   <li>{@code maixr:fracture} —— 四带静滞渐变 × 脊状裂缝噪声 × 板块存活
 *       （±震中加压）的符号掩码（见 {@link FractureDensityFunction}）；</li>
 *   <li>{@code maixr:plate_field} / {@code maixr:plate_displace} —— 板块存活与
 *       垂直漂移（见 {@link PlateFieldLogic}）；</li>
 *   <li>接线在 {@code data/maixr/worldgen/noise_settings/broken_overworld.json}：
 *       {@code final_density = min(mul(cache_2d(fracture), 原版地形链), 原floor)}。</li>
 * </ul>
 *
 * <h2>★★★ 关键设计：地形与结构【完全解耦】</h2>
 *
 * <p>密度为 0 的地方自动无地形，而 {@code StructurePiece.postProcess()} 是
 * <b>无条件 {@code setBlock}</b> 的 —— 结构落地与脚下地形无关。
 * 所以旧版"虚空区块跳过地形阶段"的跳过逻辑全部不再需要（v5 教训自动成立）：
 * 本类<b>不覆写</b> {@code fillFromNoise}/{@code buildSurface}/{@code applyCarvers}，
 * 结构与装饰一律照常跑，悬空的残存建筑就是这个世界要的氛围。</p>
 *
 * <h2>本类还剩什么</h2>
 *
 * <ul>
 *   <li><b>类型标记</b>：{@link BrokenWorld#isBrokenWorldType} 靠
 *       {@code instanceof BrokenOverworldChunkGenerator} 判定世界类型；</li>
 *   <li><b>天空结构地基屏蔽</b>：{@link #createBiomes} 里调
 *       {@link SkyStructurePlacer#maskSkyReferences}（Beardifier 在 BIOMES 阶段
 *       就被读走并缓存，必须在那时摘掉天空结构）；</li>
 *   <li><b>天空结构挂钩</b>：{@link #createStructures} 里调
 *       {@link SkyStructurePlacer#tryPlace}；</li>
 *   <li><b>材质 dressing</b>：{@link #applyBiomeDecoration} 链尾的确定性
 *       逐方块置换（震中幽匿侵蚀 / 碎屑带天空石结晶，见 5.1）。</li>
 * </ul>
 *
 * <p>★ 只对<b>还没生成过</b>的区块生效；老存档里已经落盘的区块保持原样。</p>
 */
public class BrokenOverworldChunkGenerator extends NoiseBasedChunkGenerator {
    /**
     * 【1.21.1 修正】必须是 {@link MapCodec}，不能声明成父类型 {@code Codec}。
     *
     * <p>{@code Registries.CHUNK_GENERATOR} 的值类型是 {@code MapCodec<? extends ChunkGenerator>}；
     * 若声明成 {@code Codec}，区块生成器注册表在反序列化维度 json 时会把
     * {@code MapCodec.MapCodecCodec} 强转成 {@code MapCodec} 并抛
     * {@code ClassCastException}，症状为「世界生成页面卡住」。</p>
     */
    public static final MapCodec<BrokenOverworldChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
            NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(generator -> generator.settings)
    ).apply(instance, BrokenOverworldChunkGenerator::new));

    /** 让本类被正确识别为自身的 codec（否则会沿用父类 NoiseBasedChunkGenerator 的）。 */
    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    /**
     * 父类 {@code NoiseBasedChunkGenerator} 的 {@code settings} 字段是
     * <b>private</b>，子类读不到，所以这里必须留一份自己的引用给 codec 用。
     */
    private final Holder<NoiseGeneratorSettings> settings;

    public BrokenOverworldChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings) {
        super(biomeSource, settings);
        this.settings = settings;
    }

    /** 给 codec 用的 getter。 */
    public Holder<NoiseGeneratorSettings> settings() {
        return this.settings;
    }

    /**
     * ★★ 生物群系阶段 —— <b>在这里摘掉天空结构，防止它们在天上"长出地基"</b>。
     *
     * <h2>为什么必须是这里（不是 fillFromNoise）</h2>
     *
     * <p>{@code NoiseBasedChunkGenerator.createBiomes} → {@code doCreateBiomes}
     * 会调 {@code chunk.getOrCreateNoiseChunk(...)}，而 NoiseChunk 的构造参数里
     * 就带着 {@code Beardifier.forStructuresInChunk(...)}。也就是说
     * <b>Beardifier 在 BIOMES 阶段就被读走、并永久缓存</b>
     * （{@code ChunkAccess.noiseChunk} 一旦创建，全 jar 没有任何地方重置它）。</p>
     *
     * <p>所以想影响 Beardifier，只能在 {@code super.createBiomes(...)} <b>之前</b>
     * 把天空结构从本区块的 references 里摘掉；本区块 BIOMES 跑完再还原
     * （FEATURES 的 {@code applyBiomeDecoration} 还要靠 references 放结构方块）。</p>
     *
     * <p>摘除/还原的完整推理见 {@link SkyStructurePlacer#maskSkyReferences}。</p>
     */
    @Override
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender,
                                                       StructureManager structureManager, ChunkAccess chunk) {
        Map<Structure, LongSet> original = SkyStructurePlacer.maskSkyReferences(chunk, structureManager);
        if (original == null) {
            return super.createBiomes(randomState, blender, structureManager, chunk);
        }
        // ★ super.createBiomes 是异步的（supplyAsync），所以还原挂在 whenComplete 上，
        //   不能用 try/finally —— 那会在 future 刚创建时就还回去。
        return super.createBiomes(randomState, blender, structureManager, chunk)
                .whenComplete((result, error) -> chunk.setAllReferences(original));
    }

    /**
     * 结构生成 —— 照常 + ★「破碎结构」：天上随机生成任意结构（原版/模组/下界/末地都行）。
     */
    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState,
                                 StructureManager structureManager, ChunkAccess chunk, StructureTemplateManager structureTemplateManager) {
        super.createStructures(registryAccess, structureState, structureManager, chunk, structureTemplateManager);

        SkyStructurePlacer.tryPlace(chunk, registryAccess,
                structureState.randomState(), structureManager, structureTemplateManager,
                this, structureState.getLevelSeed());
    }

    /**
     * 生物群系装饰 —— <b>照常跑（结构在这里落地，绝不能跳过）</b>，
     * 链尾追加确定性材质 dressing（震中幽匿侵蚀 / 碎屑带天空石结晶）。
     *
     * <p>历史教训（v2）：在这里对"虚空区块" return 会连结构一起跳过 ——
     * {@code applyBiomeDecoration} 正是结构真正写方块的地方，跳过它 =
     * 半座村庄从未被放置。</p>
     */
    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        // ★ 兜底防崩：天空结构会把任意模组/原版结构注册到任意区块，个别超大 jigsaw
        //   结构（piece 跨度超出区块写半径）在模板 processor 阶段读生成区域外的方块
        //   会抛 IllegalStateException —— 宁可该区块少几座结构，也不让整个世界生成崩掉
        //   （天空结构入口已用 WRITE_RADIUS_CHUNKS 校验拦截大头，这里是最后防线）
        try {
            super.applyBiomeDecoration(level, chunk, structureManager);
        } catch (RuntimeException e) {
            MaixrMod.LOGGER.warn("[破碎] 区块 ({}, {}) 结构放置异常，已跳过（天空结构越界读防线）: {}",
                    chunk.getPos().x, chunk.getPos().z, e.toString());
        }
        // ★ 材质 dressing（design D4）：装饰完成后的链尾确定性逐方块置换
        //   （震中幽匿侵蚀 / 碎屑带天空石结晶；潜声装置禁令见 MaixrDressingPass）
        MaixrDressingPass.run(level, chunk);
    }
}
