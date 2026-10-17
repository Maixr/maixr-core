package com.maixr;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「破碎结构」—— 「破碎」世界类型的独有特性：
 * <b>任何结构（原版 / 模组 / 主世界 / 下界 / 末地）都可能出现在主世界的天空里</b>。
 *
 * <h2>玩法</h2>
 *
 * <ul>
 *   <li>只在<b>「破碎」世界类型</b>生效（判据 {@link BrokenWorld#isBrokenWorldType}）</li>
 *   <li>每个区块 {@value #CHANCE_DENOMINATOR} 分之一概率 —— 即<b>平均每 100 区块一座</b></li>
 *   <li>结构被整体<b>抬到 y ≥ {@value #MIN_Y}</b> 的天空里，在可用高度内随机</li>
 *   <li>池子是<b>服务器上所有已注册的结构</b>（自动包含其它模组的结构，
 *       不需要硬编码清单）</li>
 * </ul>
 *
 * <h2>★★ 为什么走"原生注册 StructureStart"这条路</h2>
 *
 * <p>有两种做法：</p>
 *
 * <table border="1">
 *   <tr><th>做法</th><th>问题</th></tr>
 *   <tr><td>自己在某个区块里把整座结构一次性放完</td>
 *       <td>结构横跨多个区块时会<b>越界写入</b>（{@code WorldGenRegion} 有写半径限制），
 *           结果只画出一小块</td></tr>
 *   <tr><td><b>本类的做法</b>：在 {@code createStructures} 阶段把结构登记进区块，
 *       再让原版的<b>引用机制</b>驱动每个相邻区块放自己那一片</td>
 *       <td>✅ 与原生结构完全同一条代码路径；跨区块、局部放置、性能都由原版保证</td></tr>
 * </table>
 *
 * <h2>★ 安全检测（为什么必须做）</h2>
 *
 * <p>把结构"抬上天"靠 {@link StructurePiece#move}。但它的语义是
 * <b>只移动碰撞箱</b>，真正的方块坐标由各子类自己维护：</p>
 *
 * <ul>
 *   <li>{@code TemplateStructurePiece} ✅ 同步 {@code templatePosition}</li>
 *   <li>{@code PoolElementStructurePiece} ✅ 同步 {@code position}</li>
 *   <li>{@code MineshaftPieces} ❌ 覆写了但<b>没有</b>同步走廊起点坐标</li>
 *   <li>未知的模组 piece ❓ 可能根本不覆写</li>
 * </ul>
 *
 * <p>所以搬移前做一次 <b>dry-run 校验</b>：逐个 piece {@code move} 后检查包围盒
 * 是否真的跟着动。<b>任何一个 piece 不听话，整座结构就放弃</b> ——
 * 宁可不生成，也不要在地面留一堆搬不动的残骸。</p>
 */
public final class SkyStructurePlacer {

    /** 出生概率分母：100 表示平均每 100 个区块出 1 座。 */
    public static final int CHANCE_DENOMINATOR = 100;

    /** 结构底部的最低高度 —— 用户要求 y &gt; 200。 */
    public static final int MIN_Y = 201;

    /** 结构顶部与建造上限之间留的余量（免得顶到 319 被截断）。 */
    public static final int TOP_MARGIN = 2;

    /** 每次最多试几种结构（有些结构在当前条件不允许生成）。 */
    private static final int MAX_ATTEMPTS = 24;

    /** 结构池大小只报一次 */
    private static final java.util.concurrent.atomic.AtomicBoolean POOL_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 日志限流 */
    private static final java.util.concurrent.atomic.AtomicInteger LOGGED_OK =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger LOGGED_SKIP =
            new java.util.concurrent.atomic.AtomicInteger();

    private SkyStructurePlacer() {}

    /**
     * 主入口：在这个区块里尝试登记一座"天空破碎结构"。
     *
     * <p>在 {@code createStructures} 阶段调用 —— 此时区块的 StructureStart 表可写，
     * 而登记进去的位置信息会被原版引用机制正常传播到相邻区块。</p>
     *
     * @return 是否真的登记了结构
     */
    public static boolean tryPlace(ChunkAccess chunk,
                                   RegistryAccess registryAccess, RandomState randomState,
                                   StructureManager structureManager,
                                   StructureTemplateManager templateManager,
                                   ChunkGenerator generator, long seed) {
        ChunkPos pos = chunk.getPos();

        // 概率判定：坐标派生的确定性随机 —— 同一区块永远同样结果
        RandomSource rnd = RandomSource.create(
                seed * 31L + pos.x * 341873128712L + pos.z * 132897987541L + 0x5DEECE66DL);
        if (rnd.nextInt(CHANCE_DENOMINATOR) != 0) return false;

        // 池子 = 服务器上所有已注册结构（原版 + 全部模组，自动收集，不硬编码）。
        //   ★ 带地形适配（村庄/前哨站/要塞…）的也【照收】——
        //     它们的天上地形由 maskSkyReferences 在 BIOMES 阶段摘掉，见那个方法的注释。
        List<Structure> pool = new ArrayList<>();
        registryAccess.registryOrThrow(Registries.STRUCTURE).forEach(pool::add);
        if (pool.isEmpty()) return false;
        if (POOL_LOGGED.compareAndSet(false, true)) {
            MaixrMod.LOGGER.info("[破碎结构] 结构池：{} 个（全量；带地形适配的会在 BIOMES 阶段被屏蔽地基）", pool.size());
        }

        int n = pool.size();
        int startIdx = rnd.nextInt(n);
        // ★ 最多试 MAX_ATTEMPTS 种。绝大多数结构在"生物群系恒真"下第一次就成功，
        //   所以这个循环通常只跑 1 轮；只有碰到"自身条件不满足"的结构才会继续试。
        for (int attempt = 0; attempt < Math.min(n, MAX_ATTEMPTS); attempt++) {
            Structure structure = pool.get((startIdx + attempt) % n);
            StructureStart start = buildSkyStart(structure, chunk, registryAccess, randomState,
                    generator, templateManager, seed, rnd);
            if (start != null && start.isValid()) {
                structureManager.setStartForStructure(SectionPos.bottomOf(chunk),
                        structure, start, chunk);
                if (LOGGED_OK.getAndIncrement() < 40) {
                    MaixrMod.LOGGER.info("[破碎结构] 区块 ({}, {}) 天上生成 {} —— 底部 y={} 顶部 y={}",
                            pos.x, pos.z, describe(structure),
                            bottomY(start), topY(start));
                }
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    //  ★★ 屏蔽"地形适配"（Beardifier）—— 让带地基的结构也能上天
    // ==================================================================

    /**
     * 把本区块里<b>属于天空结构</b>的 structure references 临时摘掉，
     * 好让 Beardifier 看不到它们；返回原表用于还原。
     *
     * <h2>为什么必须这么做</h2>
     *
     * <p>{@code Beardifier}（"胡须器"）是原版的地形适配机制：</p>
     *
     * <pre>
     * // NoiseBasedChunkGenerator.createNoiseChunk 内部：
     * NoiseChunk.forChunk(chunk, randomState,
     *         Beardifier.forStructuresInChunk(structureManager, chunk.getPos()), ...)
     *
     * // Beardifier.forStructuresInChunk 内部：
     * startsForStructure(chunkPos, s -&gt; s.terrainAdaptation() != NONE)
     *     .forEach(start -&gt; { ... piece 12 格内就 add(Rigid) ... });
     * </pre>
     *
     * <p>村庄/前哨站/要塞这些带 {@code terrain_adaptation} 的结构被抬到 y≥201 后，
     * Beardifier 仍会在它们周围 12 格内"长地形"，在虚空里算不出合理高度，
     * 就地摊成<b>两三格厚的浮空薄板</b>（玩家实测的 bug）。</p>
     *
     * <h2>★ 为什么在 BIOMES 阶段摘，而不是 NOISE</h2>
     *
     * <p>坑在这里：{@code ChunkAccess.noiseChunk} 是<b>永久缓存</b>的
     * （{@code if (this.noiseChunk == null)} 之后全 jar 没有任何地方重置它），
     * 而它在 <b>BIOMES 阶段</b>（{@code ChunkGenerator.doCreateBiomes}）就创建了，
     * 比 NOISE 还早。等到 {@code fillFromNoise} 再摘，Beardifier 早读完了 —— 无效。
     * 所以调用点在 {@code BrokenOverworldChunkGenerator.createBiomes}。</p>
     *
     * <h2>为什么摘 references 是安全的（无竞态）</h2>
     *
     * <p>Beardifier 读的是<b>本区块自己的</b> references
     * （{@code level.getChunk(chunkPos, STRUCTURE_REFERENCES).getAllReferences()}）。
     * references 只被两处读：本区块的 Beardifier、本区块的
     * {@code applyBiomeDecoration}（FEATURES，晚于 BIOMES，那时已经还原）。
     * 邻居区块的 {@code createReferences} 读的是我们的 <b>starts</b>，不是 references ——
     * 所以摘 references 不影响任何邻居。</p>
     *
     * @return 还原用的原表；本区块没有天空结构时返回 {@code null}（零开销）
     */
    public static Map<Structure, LongSet> maskSkyReferences(ChunkAccess chunk, StructureManager structureManager) {
        Map<Structure, LongSet> refs = chunk.getAllReferences();
        if (refs.isEmpty()) {
            return null;
        }

        List<StructureStart> starts;
        try {
            starts = structureManager.startsForStructure(chunk.getPos(), s -> true);
        } catch (Throwable t) {
            return null;
        }
        if (starts.isEmpty()) {
            return null;
        }

        // 同一结构类型可能既有地面实例又有天空实例 ——
        // 只在该类型"这份引用解析出来的 start 清一色是天空结构"时才摘，避免误伤地面那座。
        Map<Structure, Boolean> allSky = new HashMap<>();
        for (StructureStart start : starts) {
            allSky.merge(start.getStructure(), isSkyStart(start), (a, b) -> a && b);
        }

        Map<Structure, LongSet> original = new HashMap<>(refs);
        Map<Structure, LongSet> filtered = new HashMap<>(refs);
        boolean any = false;
        for (Map.Entry<Structure, Boolean> e : allSky.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue()) && filtered.remove(e.getKey()) != null) {
                any = true;
            }
        }
        if (!any) {
            return null;
        }

        chunk.setAllReferences(filtered);
        return original;
    }

    /** 这个 start 是不是我们抬上天的（按 pieces 现算底面，别用带缓存的 getBoundingBox）。 */
    private static boolean isSkyStart(StructureStart start) {
        try {
            if (start == null || !start.isValid()) {
                return false;
            }
            BoundingBox box = null;
            for (StructurePiece piece : start.getPieces()) {
                BoundingBox b = piece.getBoundingBox();
                box = (box == null) ? b : box.encapsulate(b);
            }
            return box != null && box.minY() >= MIN_Y - 4;
        } catch (Throwable t) {
            return false;
        }
    }

    /*
     * ★ 关于"只对「破碎」世界类型生效"
     *
     * 这里不需要再判一次维度或世界类型 —— 因为
     * maixr:broken_overworld 这个生成器只被
     * data/maixr/worldgen/world_preset/broken.json 里的「主世界」引用，
     * 而那个 preset 就是「破碎」世界类型。
     *
     * 所以"本方法被调用"这件事本身就等价于
     * "当前是破碎世界类型的主世界"：
     *   - 「默认」世界类型的主世界用 minecraft:noise      -> 走不到这里
     *   - 「空岛」世界类型用 SkyblockBuilder 的生成器     -> 走不到这里
     *   - 取样源 pristine / 重生世界 reborn 用 noise      -> 走不到这里
     *
     * 这比运行时查 server.getLevel(OVERWORLD) 更可靠 ——
     * createStructures 阶段拿不到 ServerLevel，而且那种查法在
     * 生成期可能有重入/时序问题。
     */

    /**
     * 生成一座"悬浮在天上"的 StructureStart。
     *
     * <p>原生生成 → dry-run 校验可搬移 → 搬到目标高度。</p>
     */
    private static StructureStart buildSkyStart(Structure structure, ChunkAccess chunk,
                                                RegistryAccess registryAccess, RandomState randomState,
                                                ChunkGenerator generator,
                                                StructureTemplateManager templateManager,
                                                long seed, RandomSource rnd) {
        ChunkPos pos = chunk.getPos();
        var heightAccessor = chunk.getHeightAccessorForGeneration();
        try {
            // ① 先按原生方式生成（Y 由结构自己决定），拿到完整的 StructureStart
            //    生物群系谓词传"恒真" —— 我们要的就是"哪里都能出现"
            StructureStart start = structure.generate(registryAccess, generator,
                    generator.getBiomeSource(), randomState, templateManager, seed, pos,
                    0, heightAccessor, (Holder<Biome> b) -> true);
            if (start == null || !start.isValid()) {
                SKIP_REASONS.merge("生成无效(生物群系/条件不符)", 1, Integer::sum);
                return null;
            }

            List<StructurePiece> pieces = start.getPieces();
            if (pieces.isEmpty()) {
                SKIP_REASONS.merge("无 pieces", 1, Integer::sum);
                return null;
            }

            // ② 算目标高度（结构底部）
            //   ★ 不要用 start.getBoundingBox() —— 它会【缓存】首次计算结果，
            //     搬移 piece 之后那个缓存就过期了。这里直接按 piece 现算。
            BoundingBox bb = piecesBBox(pieces);
            int height = bb.maxY() - bb.minY();
            int maxBottom = heightAccessor.getMaxBuildHeight() - TOP_MARGIN - height;
            if (maxBottom < MIN_Y) {
                SKIP_REASONS.merge("结构太高放不下(y=" + bb.minY() + ".." + bb.maxY() + ")", 1, Integer::sum);
                return null;
            }
            int targetBottom = MIN_Y + rnd.nextInt(maxBottom - MIN_Y + 1);
            int dy = targetBottom - bb.minY();
            if (dy == 0) return null;

            // ③ ★ dry-run 校验：每个 piece 必须真的跟着动
            for (StructurePiece piece : pieces) {
                int before = piece.getBoundingBox().minY();
                try {
                    piece.move(0, dy, 0);
                } catch (Throwable t) {
                    return null;
                }
                int after = piece.getBoundingBox().minY();
                if (after != before + dy) {
                    SKIP_REASONS.merge("不可搬移:" + piece.getClass().getSimpleName(), 1, Integer::sum);
                    piece.move(0, -dy, 0);                      // 回滚
                    if (LOGGED_SKIP.getAndIncrement() < 20) {
                        MaixrMod.LOGGER.info("[破碎结构] 放弃 {} —— {} 无法搬移（会留残骸）",
                                describe(structure), piece.getClass().getSimpleName());
                    }
                    return null;
                }
            }
            return start;
        } catch (Throwable t) {
            SKIP_REASONS.merge("异常:" + t.getClass().getSimpleName(), 1, Integer::sum);
            if (LOGGED_SKIP.getAndIncrement() < 20) {
                MaixrMod.LOGGER.info("[破碎结构] 生成 {} 失败: {}", describe(structure), t.toString());
            }
            return null;
        }
    }

    /** 各类放弃原因的计数（启动一段时间后由指令/日志汇总，方便排查哪些结构不可用）。 */
    public static final java.util.Map<String, Integer> SKIP_REASONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 打印一次统计（给 /maixr 指令或调试用）。 */
    public static void dumpStats() {
        if (SKIP_REASONS.isEmpty()) {
            MaixrMod.LOGGER.info("[破碎结构] 暂无放弃记录");
            return;
        }
        MaixrMod.LOGGER.info("[破碎结构] 放弃原因统计:");
        SKIP_REASONS.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(20)
                .forEach(e -> MaixrMod.LOGGER.info("    {} x{}", e.getKey(), e.getValue()));
    }

    /**
     * 按 pieces 现算包围盒。
     *
     * <p>★ 为什么不用 {@code StructureStart.getBoundingBox()}：
     * 那个方法会把结果缓存在 {@code cachedBoundingBox} 字段里，
     * 一旦我们 {@code move} 了 piece，缓存就<b>过期</b>了 ——
     * 拿它算高度会得到搬移前的旧值。</p>
     */
    private static BoundingBox piecesBBox(List<StructurePiece> pieces) {
        BoundingBox box = null;
        for (StructurePiece piece : pieces) {
            BoundingBox b = piece.getBoundingBox();
            // encapsulate 是【实例方法】，返回一个包含两者的新 box
            box = (box == null) ? b : box.encapsulate(b);
        }
        return box != null ? box : new BoundingBox(0, MIN_Y, 0, 0, MIN_Y, 0);
    }

    /** 结构顶部的 Y（搬移后现算）。 */
    private static int topY(StructureStart start) {
        return piecesBBox(start.getPieces()).maxY();
    }

    /** 结构底部的 Y（搬移后现算）。 */
    private static int bottomY(StructureStart start) {
        return piecesBBox(start.getPieces()).minY();
    }

    private static String describe(Structure s) {
        try {
            return s.getClass().getSimpleName();
        } catch (Throwable t) {
            return "结构";
        }
    }
}
