package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * 「破碎」世界类型的<b>世界出生点接管</b> —— 保证首次出生一定落在
 * 「有真实地形的列」上，玩家进入不因出生位置而掉虚空死亡。
 *
 * <h2>★ 为什么必须接管（vanilla 兜底分支在碎裂世界会出生在虚空）</h2>
 *
 * <ul>
 *   <li>{@code PlayerRespawnLogic} 的 {@code isFaceFull(UP)} 在碎裂区大量失败，
 *       vanilla 搜索命中率低；</li>
 *   <li>更糟的是 11×11 螺旋区块全部失败时的<b>兜底分支不做任何合格性检查</b>，
 *       直接把出生点设到 {@code chunkPos + (8, spawnHeight, 8)} —— 该处可能是
 *       纯虚空，玩家进入即掉虚空死亡（用户实测）。</li>
 * </ul>
 *
 * <h2>★ 两段式挂点（实测教训：搜索不能放在 CreateSpawnPosition 回调里）</h2>
 *
 * <ol>
 *   <li><b>{@code LevelEvent.CreateSpawnPosition}</b>（{@code setInitialSpawn} 内，
 *       区块系统尚未热身）：只做生成器类型判定并<b>取消 vanilla 搜索</b> ——
 *       vanilla 的 121 区块螺旋同样可能撞上「生成泵死锁」（1.20.5+ 此阶段无
 *       ticket，个别区块的依赖链会调度饥饿，{@code getChunk} 同步等待永不完成，
 *       实测两种子复现，vanilla 对照/单区块 getChunk 均正常）；<b>绝不在这一步
 *       生成区块</b>。</li>
 *   <li><b>{@code ServerStartedEvent}</b>（{@code prepareLevels} 之后、玩家可连接
 *       之前）：此时区块调度系统已全速运转（ticket 已注册），与游戏运行时同环境，
 *       同步 {@code getChunk} 安全 —— 在这里跑两级搜索并写 {@code levelData.setSpawn}。
 *       玩家连接在网络 tick 里处理，回调阻塞期间不可能有人进入世界。</li>
 * </ol>
 *
 * <h2>合格口径（用户简化决定，design D5）</h2>
 *
 * <p>出生列存在表面方块（{@code WORLD_SURFACE} 首个非空气方块，<b>排除纯虚空列</b>），
 * 出生位（表面上方 1 格）与其上一格均为空气。<b>表面材质不限</b> ——
 * 泥土/草方块/树叶/石头断面均可。原先的「{@code BlockTags.DIRT} ∪ 树叶 +
 * 30 格内原木」口径已按用户决定移除：碎裂区存活碎块上树木稀疏，
 * 该口径 runServer 实测螺旋到 13 区块（290 个预筛命中）仍无合格列。</p>
 *
 * <h2>两级搜索（design D2）</h2>
 *
 * <ol>
 *   <li><b>掩码离线预筛</b>：碎裂场是纯坐标函数，不加载区块即可判定任意列
 *       「是否可能有地形」—— {@link FractureDensityFunction} 输出<b>撕裂深度</b>
 *       ∈ {@code [-TEAR_SCALE, 0]}（减法语义），列存活率 &gt; 0（即
 *       {@code compute > -TEAR_SCALE}）的列才可能有地形；完全撕裂列（死亡板块/
 *       宽裂缝中心，撕裂深度 = -4，任何原版密度都被压负）直接排除。
 *       <i>历史语义对账：文档早先写的「掩码 &gt; 0 即可能有地形」是初版乘法掩码
 *       （输出 ∈ [-0.3, 1]）的口径，减法重写后同一语义映射为
 *       {@code compute > -TEAR_SCALE}。</i></li>
 *   <li><b>真实区块校验</b>：预筛命中区块按螺旋序 {@code getChunk} 真实生成，
 *       逐列检查上述口径。</li>
 * </ol>
 *
 * <h2>与真实碎裂场逐位一致的原因</h2>
 *
 * <p>掩码实例的裂缝噪声取自 {@code ServerChunkCache.randomState()}（世界生成
 * 实际使用的 {@link RandomState}）：{@code RandomState} 构造时的
 * {@code NoiseWiringHelper.visitNoise}（非 legacy 路径，本项目
 * {@code legacy_random_source: false}）正是调 {@code getOrCreateNoise(key)}，
 * 同一 {@code computeIfAbsent} —— 离线掩码与真实碎裂场共用<b>同一个</b>
 * 种子化 {@link NormalNoise} 实例。板块场（{@link PlateFieldLogic}）是纯哈希
 * 无种子，直接按 JSON 参数构造（{@code scale=1.0}，无 band_floor/calm_radius）。</p>
 *
 * <h2>其它世界类型不受影响</h2>
 *
 * <p>两段挂点都只对主世界生成器为 {@link BrokenOverworldChunkGenerator} 的
 * 维度介入（vanilla normal / 空岛等类型照常走原版搜索）；vanilla 调试世界
 * （{@code flag} 分支）根本不会触发 CreateSpawnPosition。</p>
 */
public final class BrokenSpawnSearch {

    /**
     * 新世界初始化标记：{@code CreateSpawnPosition} 只在「主世界首次初始化」时触发
     * （老存档重启不触发）——用它区分「新世界需要搜索」与「老存档重启 spawn 不动」。
     * 单服务器生命周期内只此一用，静态即可。
     */
    private static boolean pendingSearch;

    private final ServerLevel level;
    private final ServerLevelData levelData;
    /** 离线碎裂场掩码（与真实碎裂场同一噪声实例，见类注释）。 */
    private final FractureDensityFunction fractureMask;
    private final int minY;

    // 搜索统计（一次性日志用）
    private int maskCandidateChunks;
    private int generatedChunks;
    private long maskColumnEvals;
    private long startedNano;

    private BrokenSpawnSearch(ServerLevel level, ServerLevelData levelData) {
        this.level = level;
        this.levelData = levelData;
        this.fractureMask = buildOfflineMask(level);
        this.minY = level.getMinBuildHeight();
    }

    // ==========================================================================
    //  事件入口（MaixrMod 构造器注册到 NeoForge.EVENT_BUS）
    // ==========================================================================

    /**
     * 第一段：{@code LevelEvent.CreateSpawnPosition}（区块系统未热身）——
     * 只判定破碎世界并取消 vanilla 搜索，<b>不生成任何区块</b>（实测该阶段
     * 同步 {@code getChunk} 会概率性死锁，见类注释「两段式挂点」）。
     */
    public static void onCreateSpawnPosition(LevelEvent.CreateSpawnPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(level.getChunkSource().getGenerator() instanceof BrokenOverworldChunkGenerator)) {
            return;   // vanilla normal / 空岛等其它世界类型照常走原版搜索（spec 场景）
        }
        pendingSearch = true;
        event.setCanceled(true);
        // 真正的搜索延后到 ServerStartedEvent（onServerStarted）
    }

    /**
     * 第二段：{@code ServerStartedEvent}（prepareLevels 之后、玩家可连接之前）——
     * 区块调度系统已全速运转，在这里跑两级搜索并写入出生点。
     * 仅当第一段标记了「新世界初始化」才执行（老存档重启 spawn 不动）。
     */
    public static void onServerStarted(ServerStartedEvent event) {
        if (!pendingSearch) {
            return;   // 老存档重启 / 非破碎世界：level.dat 里的 spawn 保持不变
        }
        pendingSearch = false;
        ServerLevel level = event.getServer().overworld();
        if (level == null
                || !(level.getChunkSource().getGenerator() instanceof BrokenOverworldChunkGenerator)) {
            return;   // 只管破碎世界类型
        }
        new BrokenSpawnSearch(level, (ServerLevelData) level.getLevelData()).run();
    }

    // ==========================================================================
    //  两级搜索主循环：区块粒度无限螺旋（半径逐轮扩大，不退化 —— 用户决定）
    // ==========================================================================

    /**
     * 无限搜索直到写入合格出生点（{@code levelData.setSpawn}）。
     * int 螺旋上限（±21 亿区块 ≈ ±340 亿格）远超任何现实可达范围 ——
     * 物理上等价「无限」，溢出即终止（保留 levelData 默认 spawn）。
     */
    private void run() {
        this.startedNano = System.nanoTime();
        for (int r = 0; r >= 0; r++) {
            if (r == 0) {
                if (tryChunk(0, 0)) {
                    return;
                }
                continue;
            }
            // 环遍历：从 (-r,-r) 顺时针一圈，保证「近处优先」的确定性顺序
            for (int dx = -r; dx <= r; dx++) {
                if (tryChunk(dx, -r) || tryChunk(dx, r)) {
                    return;
                }
            }
            for (int dz = -r + 1; dz <= r - 1; dz++) {
                if (tryChunk(-r, dz) || tryChunk(r, dz)) {
                    return;
                }
            }
        }
        MaixrMod.LOGGER.error("[破碎] 出生点搜索耗尽 int 螺旋范围仍未命中（理论上不可能）");
    }

    /** 预筛命中 → 真实生成 → 列校验；任一环节失败则螺旋继续。 */
    private boolean tryChunk(int cx, int cz) {
        if (!chunkMayHaveTerrain(cx, cz)) {
            return false;   // 掩码预筛排除（全撕列区块，不浪费生成）
        }
        this.maskCandidateChunks++;
        // 限流诊断日志：观察螺旋推进与命中分布（一次性搜索，量可控）
        if (this.maskCandidateChunks <= 200) {
            MaixrMod.LOGGER.info("[破碎] 出生点搜索：预筛命中 #{}，区块 ({}, {}) 距原点 {} 区块",
                    this.maskCandidateChunks, cx, cz, Math.max(Math.abs(cx), Math.abs(cz)));
        }
        LevelChunk chunk = this.level.getChunk(cx, cz);   // 同步真实生成
        this.generatedChunks++;
        BlockPos spawn = findSpawnColumnInChunk(chunk);
        if (spawn == null) {
            return false;   // 预筛误报（全断面虚空等）—— 螺旋继续，不退化
        }
        this.levelData.setSpawn(spawn, 0.0F);
        BlockState surface = this.level.getBlockState(spawn.below());
        MaixrMod.LOGGER.info(
                "[破碎] 出生点选定：{}（表面方块 {}，距原点 {} 格；预筛命中区块 {} / 实际生成区块 {} / 掩码求值 {} 列，耗时 {}ms）",
                spawn.toShortString(), surface.getBlock().getName().getString(),
                Math.round(Math.hypot(spawn.getX(), spawn.getZ())),
                this.maskCandidateChunks, this.generatedChunks, this.maskColumnEvals,
                (System.nanoTime() - this.startedNano) / 1_000_000L);
        return true;
    }

    // ==========================================================================
    //  掩码离线预筛
    // ==========================================================================

    /**
     * 构造离线碎裂场掩码 —— 与真实碎裂场<b>共用同一个种子化裂缝噪声实例</b>
     * （见类注释「与真实碎裂场逐位一致的原因」）。
     */
    private static FractureDensityFunction buildOfflineMask(ServerLevel level) {
        RandomState randomState = level.getChunkSource().randomState();
        ResourceKey<NormalNoise.NoiseParameters> crackKey = ResourceKey.create(Registries.NOISE,
                ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "crack"));
        // 噪声参数是数据包注册表（worldgen/noise/*.json），必须从 level 的 registryAccess 取
        Holder<NormalNoise.NoiseParameters> crackHolder =
                level.registryAccess().lookupOrThrow(Registries.NOISE).getOrThrow(crackKey);
        // plates/scale 与 broken_overworld.json 的 maixr:fracture 参数一致：
        // {"crack_noise":"maixr:crack","plates":{"type":"maixr:plate_field","scale":1.0},"scale":1.0}
        // （无 band_floor / calm_radius 字段 → 默认值 0）
        return new FractureDensityFunction(
                new DensityFunction.NoiseHolder(crackHolder, randomState.getOrCreateNoise(crackKey)),
                new PlateFieldDensityFunction(1.0D), 1.0D);
    }

    /**
     * 区块内 16×16 列逐一求值撕裂深度，任一列存活率 &gt; 0（{@code compute > -TEAR_SCALE}）
     * 即「可能存在地形」。掩码 = {@code -TEAR_SCALE} 的列（死亡板块/宽裂缝中心，
     * 完全撕裂）直接排除。纯函数求值不加载区块，每列 ~1μs、每区块 &lt;1ms。
     */
    private boolean chunkMayHaveTerrain(int cx, int cz) {
        int baseX = cx << 4;
        int baseZ = cz << 4;
        double fullTear = -FractureDensityFunction.TEAR_SCALE;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                double tear = this.fractureMask.compute(
                        new DensityFunction.SinglePointContext(baseX + lx, 0, baseZ + lz));
                this.maskColumnEvals++;
                if (tear > fullTear) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==========================================================================
    //  真实区块列校验
    // ==========================================================================

    /**
     * 逐列检查（行序遍历，vanilla 螺旋同款）：
     * <ol>
     *   <li>{@code WORLD_SURFACE} 顶（首个非空气方块，{@code h-1}）存在 ——
     *       纯虚空列排除（vanilla 兜底虚空 bug 的修复本体）；</li>
     *   <li>出生位（表面上方 1 格）与其上一格均为空气 —— 头顶两格空间。</li>
     * </ol>
     * 表面材质不限（用户简化口径，design D5）。全部合格 → 返回出生位
     * （表面上方 1 格），由调用方写 {@code levelData.setSpawn}。
     */
    private BlockPos findSpawnColumnInChunk(LevelChunk chunk) {
        ChunkPos chunkPos = chunk.getPos();
        int maxHeight = this.level.getMaxBuildHeight();
        // 临时诊断：失败原因统计（MAIXR_SPAWN_DIAG 开启时打印）
        boolean diag = System.getenv("MAIXR_SPAWN_DIAG") != null;
        int voidCols = 0, topFail = 0, airFail = 0;
        BlockPos firstVoid = null;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int wx = chunkPos.getMinBlockX() + lx;
                int wz = chunkPos.getMinBlockZ() + lz;
                int h = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                if (h <= this.minY) {
                    voidCols++;
                    if (firstVoid == null) {
                        firstVoid = new BlockPos(wx, h, wz);
                    }
                    continue;   // 全空列（虚空）
                }
                // ★ 高度图可能比真实顶方块低：surface rule（如草方块）写在
                //   WORLD_SURFACE 高度图更新之后（generation 内部 setBlockState
                //   isMoved=true 不更新高度图），实测 grass_block 会落在 h 处。
                //   先从 h 向下找首个非空方块（h 本身可能是方块），再从其上向上
                //   找首个「连续两格空气」——对树叶覆写同样成立（出生在树冠顶）。
                int top = h;
                while (top > this.minY && chunk.getBlockState(new BlockPos(wx, top, wz)).isAir()) {
                    top--;
                }
                if (top <= this.minY) {
                    voidCols++;
                    continue;   // 实际全空
                }
                int limit = Math.min(top + 64, maxHeight - 2);
                int y = top + 1;
                while (y < limit && (!chunk.getBlockState(new BlockPos(wx, y, wz)).isAir()
                        || !chunk.getBlockState(new BlockPos(wx, y + 1, wz)).isAir())) {
                    y++;
                }
                if (y >= limit) {
                    airFail++;
                    continue;   // 表面上方 64 格内无双空气（被浮岩覆盖等，罕见）
                }
                BlockPos spawn = new BlockPos(wx, y, wz);
                if (diag) {
                    MaixrMod.LOGGER.info("[破碎] 校验合格：列 ({}, {}) 表面 y={} {}，出生位 y={}",
                            wx, wz, top,
                            chunk.getBlockState(new BlockPos(wx, top, wz)).getBlock().getName().getString(), y);
                }
                return spawn;
            }
        }
        if (diag) {
            MaixrMod.LOGGER.info("[破碎] 区块 ({}, {}) 校验失败统计：虚空列 {}（首列 h={}）、顶格 {}、空气不足 {}",
                    chunkPos.x, chunkPos.z, voidCols,
                    firstVoid == null ? -999 : firstVoid.getY(), topFail, airFail);
        }
        return null;
    }
}
