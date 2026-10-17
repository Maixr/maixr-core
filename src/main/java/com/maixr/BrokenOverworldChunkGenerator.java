package com.maixr;

import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.longs.LongSet;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 「破碎」世界类型的<b>主世界生成器</b> —— 真实地形 + <b>渐变虚空</b>。
 *
 * <h2>两种"碎"的程度</h2>
 *
 * <p>虚空不是均匀的：<b>出生点附近几乎完好，越往外越碎</b>。
 * 判据是区块到原点的切比雪夫距离 {@code d = max(|chunkX|, |chunkZ|)}：</p>
 *
 * <table border="1">
 *   <tr><th>d（区块）</th><th>距离</th><th>虚空比例</th></tr>
 *   <tr><td>0（出生点）</td><td>0</td><td>{@value #NEAR_VOID_CHANCE} → 20%</td></tr>
 *   <tr><td>25</td><td>≈400 格</td><td>57.5%</td></tr>
 *   <tr><td>≈{@value #RAMP_CHUNKS}</td><td>≈800 格</td><td>{@value #FAR_VOID_CHANCE} → 95%</td></tr>
 *   <tr><td>&gt;{@value #RAMP_CHUNKS}</td><td>—</td><td>恒定 95%</td></tr>
 * </table>
 *
 * <p>中间线性过渡。过渡带收窄到 800 格，出岛走一小段就能明显看出"越来越碎"。</p>
 *
 * <h2>★★★ 关键设计：地形与结构【完全解耦】</h2>
 *
 * <p><b>这是走了 5 版弯路后的结论。</b> 前几版都试图"保护有结构的区块不被挖空"，
 * 全错了 —— 因为 {@code StructurePiece.postProcess()} 是<b>无条件 {@code setBlock}</b> 的，
 * <b>根本不关心脚下有没有地形</b>。结构能否完整落地，
 * 与"这个区块是不是虚空"<b>毫无关系</b>。</p>
 *
 * <table border="1">
 *   <tr><th>版本</th><th>做法</th><th>结果</th></tr>
 *   <tr><td>v2</td><td>挖虚空 + 虚空区块跳过 {@code applyBiomeDecoration}</td>
 *       <td>❌ <b>村庄真被截断</b> —— 结构恰恰是在那一步落地的</td></tr>
 *   <tr><td>v3</td><td>保护 {@code hasAnyStructureReferences()} 的区块</td>
 *       <td>❌ 废矿 bbox 横扫几十区块 → 虚空率 92.7% 掉到 <b>50.8%</b></td></tr>
 *   <tr><td>v4</td><td>保护"精确 piece bbox 相交"的区块</td>
 *       <td>❌ 仍有 44.8% 被保护</td></tr>
 *   <tr><td><b>v5</b></td><td><b>地形纯按公式挖；结构阶段一律照常</b></td>
 *       <td>✅ 虚空率 = 92.7%，村庄/要塞/传送门完整</td></tr>
 * </table>
 *
 * <p>所以：</p>
 * <ul>
 *   <li><b>地形阶段</b>（{@code fillFromNoise} / {@code buildSurface} / {@code applyCarvers}）
 *       —— 虚空区块<b>跳过</b>，这就是"破碎"</li>
 *   <li><b>结构阶段</b>（{@code createStructures} / {@code applyBiomeDecoration}）
 *       —— <b>绝不跳过，虚空区块也照常跑</b>，结构才会落地</li>
 * </ul>
 *
 * <p>结果：<b>地形是碎的，建筑是完整的、可抵达的</b> ——
 * 村庄有完整地基房屋、要塞与末地传送门一定拿得到，
 * 它们只是<b>悬在虚空里</b>，正是"残存于虚空的遗迹"该有的样子。</p>
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
     * {@code ClassCastException}，症状为「世界生成页面卡住」。
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

    // ==========================================================================
    //  ★ 渐变参数（想调手感就改这三个常量）
    // ==========================================================================

    /** 出生点附近的虚空比例（0.20 = 20% 虚空）。 */
    public static final double NEAR_VOID_CHANCE = 0.20D;

    /** 最远处的虚空比例（0.95 = 95% 虚空，只剩 5% 残陆）。 */
    public static final double FAR_VOID_CHANCE = 0.95D;

    /**
     * 从出生点走多远（区块）达到最碎的程度。
     *
     * <p>{@code 50} 区块 ≈ <b>800 格</b> —— 出岛后很快就明显变碎，观感更直观。</p>
     */
    public static final int RAMP_CHUNKS = 50;

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

    /** 与旧 KubeJS chunkRoll 完全一致的确定性 0..1 哈希（不依赖世界种子）。 */
    private static double roll01(int chunkX, int chunkZ) {
        int h = chunkX * 0x1638D74F ^ chunkZ * 0x9E3779B1;
        h = h ^ (h >>> 13);
        h = h * 0x85EBCA6B;
        h = h ^ (h >>> 16);
        return (h & 0xFFFFFFFFL) / 4294967296.0;
    }

    /**
     * 某个区块的虚空概率 —— 出生点 {@value #NEAR_VOID_CHANCE}，
     * 到 {@value #RAMP_CHUNKS} 区块处线性升到 {@value #FAR_VOID_CHANCE}，之后恒定。
     */
    public static double voidChance(int chunkX, int chunkZ) {
        int d = Math.max(Math.abs(chunkX), Math.abs(chunkZ));
        double t = Math.min(1.0D, d / (double) RAMP_CHUNKS);
        return NEAR_VOID_CHANCE + (FAR_VOID_CHANCE - NEAR_VOID_CHANCE) * t;
    }

    /** true = 这个区块是虚空。同一个坐标永远得到同一个结果。 */
    public static boolean isVoided(int chunkX, int chunkZ) {
        return roll01(chunkX, chunkZ) < voidChance(chunkX, chunkZ);
    }

    // ==========================================================================
    //  ★ 虚空判定（只看坐标，不掺结构信息）
    // ==========================================================================

    /**
     * 这个区块要不要挖成虚空。
     *
     * <h2>★★★ 关键设计：地形与结构【完全解耦】</h2>
     *
     * <p><b>第 5 版才修对，教训值得记下来。</b> 前几版都试图"保护有结构的区块不被挖空"，
     * 全错了 —— 因为 {@code StructurePiece.postProcess()} 是<b>无条件 {@code setBlock}</b> 的，
     * 它<b>根本不关心脚下有没有地形</b>。结构能不能完整落地，
     * 与"这个区块是不是虚空"<b>毫无关系</b>。</p>
     *
     * <table border="1">
     *   <tr><th>版本</th><th>做法</th><th>结果</th></tr>
     *   <tr><td>v2</td><td>无脑挖虚空 + 跳过 {@code applyBiomeDecoration}</td>
     *       <td><b>村庄真被截断</b> —— 因为结构恰恰是在
     *           {@code applyBiomeDecoration} 里落地的，跳过它 = 半个村庄从未放置</td></tr>
     *   <tr><td>v3</td><td>保护 {@code hasAnyStructureReferences()} 的区块</td>
     *       <td>误伤严重：废矿/试炼厅的 bbox 横扫几十区块，<b>450/1004 区块</b>被保留，
     *           虚空率从 92.7% 掉到 <b>50.8%</b>（玩家实测："离这么远区块还这么完整"）</td></tr>
     *   <tr><td>v4</td><td>保护"精确 piece bbox 相交"的区块</td>
     *       <td>仍是 44.8% 被保护 —— 大型结构本身覆盖就这么广</td></tr>
     *   <tr><td><b>v5（现在）</b></td><td><b>地形纯按公式挖；结构一律照常落地</b></td>
     *       <td>虚空率 = 公式值 <b>92.7%</b>；村庄/要塞/传送门<b>完整</b>，只是悬在虚空里</td></tr>
     * </table>
     *
     * <p>所以本方法<b>只看坐标</b>，不查任何结构信息 —— 这正是用户要的
     * "离得越远越碎"。</p>
     *
     * <h2>那结构怎么办</h2>
     *
     * <p>靠 {@code createStructures} 与 {@code applyBiomeDecoration}
     * <b>一律调 super、绝不跳过</b>：结构会在虚空里完整地放置自己，
     * 变成"悬空的残存建筑" —— 这正是破碎世界要的氛围，而且
     * <b>要塞/末地传送门一定拿得到</b>。</p>
     */
    private boolean isVoidedChunk(ChunkAccess chunk) {
        return isVoided(chunk.getPos().x, chunk.getPos().z);
    }

    // ==========================================================================
    //  ★ 破碎逻辑：只作用于"地形生成"，不碰"结构生成"
    // ==========================================================================

    /**
     * 虚空区块不生成噪声地形 —— 这是"破碎"的核心体现。
     *
     * <p>注意：<b>天空结构的"地基"不在这里处理</b>。Beardifier 在更早的
     * BIOMES 阶段就被创建并永久缓存进 {@code ChunkAccess.noiseChunk}，
     * 所以屏蔽必须发生在 {@link #createBiomes} —— 见那个方法。</p>
     */
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        if (isVoidedChunk(chunk)) {
            return CompletableFuture.completedFuture(chunk);
        }
        return super.fillFromNoise(blender, randomState, structureManager, chunk);
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

    /** 虚空区块不铺地表。 */
    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
        if (isVoidedChunk(chunk)) return;
        super.buildSurface(level, structureManager, randomState, chunk);
    }

    /** 虚空区块不雕刻洞穴（那里本来就没地形）。 */
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
     * <p>如果在这里对虚空区块 return，<b>整个破碎世界除了残陆就不会有任何遗迹</b>。
     * 悬在虚空中的残破建筑正是这个世界要的氛围。</p>
     */
    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState,
                                 StructureManager structureManager, ChunkAccess chunk, StructureTemplateManager structureTemplateManager) {
        super.createStructures(registryAccess, structureState, structureManager, chunk, structureTemplateManager);

        // ★「破碎结构」：天上随机生成任意结构（原版/模组/下界/末地都行）
        SkyStructurePlacer.tryPlace(chunk, registryAccess,
                structureState.randomState(), structureManager, structureTemplateManager,
                this, structureState.getLevelSeed());
    }

    /**
     * 生物群系装饰 —— <b>★ 绝不跳过，连虚空区块也不跳过！</b>
     *
     * <h2>为什么"不能跳过"（这是 v2 村庄被截断的真凶）</h2>
     *
     * <p>{@code ChunkGenerator.applyBiomeDecoration} 内部干两件事：</p>
     *
     * <pre>
     * 1. 放地形装饰（树、草、矿…）
     * 2. for (Structure structure : ...)
     *        startsForStructure(sectionpos, structure)
     *            .forEach(start -> start.placeInChunk(...));   // ← 结构真正写方块
     * </pre>
     *
     * <p>v2 在这个方法里对虚空区块 {@code return} 了 —— 结果<b>连结构一起跳过</b>，
     * 落在虚空区的半座村庄<b>从来没被放置过</b>。这就是"村庄被虚空截断"的真正原因：
     * 不是地形被挖走了，而是那半个村庄<b>压根没生成</b>。</p>
     *
     * <h2>为什么跳过也没省下什么</h2>
     *
     * <p>地形装饰（{@code Feature.place}）本来就只是往"已有的地表"上点缀；
     * 虚空区块里没有地表，绝大多数 feature 会自己找不到落点而放弃，
     * 开销极小。<b>跳过它换来的是结构消失，代价远大于收益。</b></p>
     *
     * <h2>结果</h2>
     *
     * <p>虚空区块里的结构会<b>完整地放置自己</b>（{@code StructurePiece.postProcess}
     * 是无条件 {@code setBlock}，不依赖脚下地形）——
     * 变成"悬在虚空中的残存遗迹"。村庄有完整的地基与房屋，
     * <b>要塞与末地传送门也一定拿得到</b>。</p>
     */
    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        super.applyBiomeDecoration(level, chunk, structureManager);
    }

}
