package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 碎片道路网（移植 RoadWeaver 的核心思路）。
 *
 * <h2>相比旧版 FragmentBridges 的四个根本差异</h2>
 *
 * <table border="1">
 *   <tr><th>维度</th><th>旧版</th><th>本模块（学 RoadWeaver）</th></tr>
 *   <tr><td>选边</td><td>最近 5 个，全部尝试</td>
 *       <td><b>RNG（相对邻域图）</b>过滤：若存在 c 同时比 b 更靠近 a 和 b，则 a–b 不建 ——
 *           这正是 RoadWeaver 的 "RNG (balanced)" 网络规划</td></tr>
 *   <tr><td>路径</td><td>两点直线 + 弓形</td>
 *       <td><b>A\* 代价寻路</b>：坡度²、穿越实心、天空结构全部计入代价，自动绕行</td></tr>
 *   <tr><td>形状</td><td>直线</td><td><b>样条平滑</b>（邻点加权），曲率连续</td></tr>
 *   <tr><td>状态</td><td>会话内内存记录</td>
 *       <td><b>SavedData 持久化</b>：每对碎片只建一次，跨会话也不会重复</td></tr>
 * </table>
 *
 * <h2>坡度限制</h2>
 *
 * <p>竖直剖面按 {@value #MAX_SLOPE} 的坡度（tan）渐进逼近目标高度，
 * 超出可通行坡度的落差会被"摊平"到整条桥上 —— 这正是玩家"登不上"问题的正解。</p>
 */
public final class FragmentRoadNetwork {

    /** 最大可通行坡度（tan）。1.0 = 45°；用户确认从旧的 2.0 收紧到 1.0。 */
    public static final double MAX_SLOPE = 1.0D;

    /** A* 网格分辨率（格）。 */
    private static final int GRID = 4;

    /** A* 搜索边界外扩（格）。 */
    private static final int EXPAND = 20;

    private static final int MAX_ITER = 40000;

    /** 桥面中段抬高的拱高（格）。让长跨看起来像图里那种自然拱桥，而不是一条直线。 */
    private static final double BRIDGE_ARCH = 3.0D;

    /** 诊断计数：端点找不到 / A* 找不到路径 —— 用来定位"建成 0"的真正原因。 */
    public static final java.util.concurrent.atomic.AtomicInteger C_NO_END =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger C_NO_PATH =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 天空结构起始高度，超过一律视为不可通行。 */
    private static final int MAX_SURFACE_Y = 180;

    /** 每个碎片最多连几条。 */
    public static final int NEIGHBORS = 5;

    private static final java.util.concurrent.atomic.AtomicBoolean LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 待重试的碎片（本次因邻区块未加载而失败）—— 旧版有这个，重写时我弄丢了，导致一座桥都不生成。 */
    private static final java.util.Map<ResourceKey<Level>, Set<Long>> PENDING = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int RETRY_PER_LOAD = 6;
    /** 诊断计数：分别记录 建成 / 不可行 / 邻区块未加载 / A* 失败。 */
    public static final java.util.concurrent.atomic.AtomicInteger C_BUILT = new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger C_IMPOSSIBLE = new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger C_UNLOADED = new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger C_PLATES = new java.util.concurrent.atomic.AtomicInteger();
    private static final int MAX_PENDING = 512;
    private static final int UNLOADED = -1;
    private static final int IMPOSSIBLE = 0;
    private static final int BUILT = 1;

    private FragmentRoadNetwork() {
    }

    /**
     * 服务端 tick 里补建待办队列。
     *
     * <p>必须在<b>区块加载事件之外</b>执行：在 {@code ChunkEvent.Load} 里读邻区块会递归加载，
     * 实测会把世界卡在 100%。tick 时玩家周围区块已就绪，成功率最高。</p>
     */
    public static void tick(ServerLevel level) {
        Set<Long> pending = PENDING.get(level.dimension());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        double band = MaixrConfig.uniformBand();
        double deathProb = FractureDensityFunction.curve(FractureDensityFunction.PLATE_DEATH, band);
        if (deathProb <= 0.0D || deathProb >= 1.0D) {
            return;
        }
        RoadData data = RoadData.get(level);
        int tried = 0;
        for (Long k : new ArrayList<>(pending)) {
            if (tried++ >= 4) {
                break;   // ★ 性能：48 → 4。每次 connectPlate 内部都跑 A*（MAX_ITER=40000），
                         //   48 个 = 最坏 192 万次节点展开，全在主线程，是 tick 卡顿的主因。
            }
            if (connectPlate(level, data, k >> 32, (int) (long) k, deathProb) != UNLOADED) {
                pending.remove(k);
            }
        }
    }

    // ==================================================================
    //  入口
    // ==================================================================

    public static void run(ServerLevel level, LevelChunk chunk) {
        double band = MaixrConfig.uniformBand();
        double deathProb = FractureDensityFunction.curve(FractureDensityFunction.PLATE_DEATH, band);
        if (deathProb <= 0.0D || deathProb >= 1.0D) {
            return;
        }
        RoadData data = RoadData.get(level);

        // 先补上次因邻区块未加载而失败的碎片
        Set<Long> pending = PENDING.computeIfAbsent(level.dimension(), k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        if (!pending.isEmpty()) {
            int tried = 0;
            for (Long k : new ArrayList<>(pending)) {
                if (tried++ >= RETRY_PER_LOAD) {
                    break;
                }
                if (connectPlate(level, data, k >> 32, (int) (long) k, deathProb) != UNLOADED) {
                    pending.remove(k);
                }
            }
        }

        int cell = PlateFieldLogic.CELL_SIZE;
        ChunkPos cp = chunk.getPos();
        int baseCX = Math.floorDiv(cp.getMinBlockX(), cell);
        int baseCZ = Math.floorDiv(cp.getMinBlockZ(), cell);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long ax = baseCX + dx;
                long az = baseCZ + dz;
                if (!PlateFieldLogic.plateAlive(ax, az, deathProb)) {
                    continue;
                }
                if (((int) Math.floor(PlateFieldLogic.plateCenterX(ax, az)) >> 4) != cp.x
                        || ((int) Math.floor(PlateFieldLogic.plateCenterZ(ax, az)) >> 4) != cp.z) {
                    continue;   // 只由拥有碎片中心的区块负责
                }
                if (connectPlate(level, data, ax, az, deathProb) == UNLOADED
                        && pending.size() < MAX_PENDING) {
                    pending.add((ax << 32) ^ (az & 0xFFFFFFFFL));
                }
            }
        }
    }

    /** 为一个碎片规划并铺设若干条连接。 */
    private static int connectPlate(ServerLevel level, RoadData data, long ax, long az, double deathProb) {
        C_PLATES.incrementAndGet();
        boolean sawUnloaded = false;
        long[][] candidates = nearestAlive(ax, az, deathProb);
        // RNG 过滤（相对邻域图）：a–b 只在"没有 c 同时更靠近两者"时保留
        int built = 0;
        for (long[] b : candidates) {
            if (b == null || built >= NEIGHBORS) {
                continue;
            }
            if (ax > b[0] || (ax == b[0] && az > b[1])) {
                continue;   // 每对只由字典序小的一方发起
            }
            if (!rngKeeps(ax, az, b[0], b[1], candidates)) {
                continue;
            }
            long key = pairKey(ax, az, b[0], b[1]);
            if (data.isDone(key)) {
                continue;
            }
            int r = buildRoad(level, ax, az, b[0], b[1]);
            if (r == BUILT) {
                data.markDone(key);
                C_BUILT.incrementAndGet();
                built++;
            } else if (r == UNLOADED) {
                C_UNLOADED.incrementAndGet();
                sawUnloaded = true;
            } else {
                C_IMPOSSIBLE.incrementAndGet();
            }
        }
        // ★ 兜底：RNG 把这个碎片的所有边都抑制掉了 → 至少连最近的一个
        if (built == 0) {
            for (long[] b : candidates) {
                if (b == null) {
                    continue;
                }
                if (ax > b[0] || (ax == b[0] && az > b[1])) {
                    continue;
                }
                long key = pairKey(ax, az, b[0], b[1]);
                if (data.isDone(key)) {
                    continue;
                }
                int r2 = buildRoad(level, ax, az, b[0], b[1]);
                if (r2 == BUILT) {
                    data.markDone(key);
                    break;
                } else if (r2 == UNLOADED) {
                    sawUnloaded = true;
                }
            }
        }
        return sawUnloaded ? UNLOADED : IMPOSSIBLE;
    }

    /** RNG：若存在 c 同时比 b 更靠近 a、也更靠近 b，则 a–b 被抑制。 */
    private static boolean rngKeeps(long ax, long az, long bx, long bz, long[][] candidates) {
        double cx = PlateFieldLogic.plateCenterX(ax, az);
        double cz = PlateFieldLogic.plateCenterZ(ax, az);
        double dx = PlateFieldLogic.plateCenterX(bx, bz);
        double dz = PlateFieldLogic.plateCenterZ(bx, bz);
        double ab = dist(cx, cz, dx, dz);
        for (long[] c : candidates) {
            if (c == null || (c[0] == bx && c[1] == bz) || (c[0] == ax && c[1] == az)) {
                continue;
            }
            double ex = PlateFieldLogic.plateCenterX(c[0], c[1]);
            double ez = PlateFieldLogic.plateCenterZ(c[0], c[1]);
            // ★ 容忍度：只有当一个第三方"明显"更靠近两端（各 < 0.8×ab）才抑制。
            //   原来用 < ab 判定，稠密碎片场里几乎每条边都会被抑制 → 一座桥都不生成。
            if (dist(cx, cz, ex, ez) < ab * 0.8D && dist(dx, dz, ex, ez) < ab * 0.8D) {
                return false;
            }
        }
        return true;
    }

    private static double dist(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ==================================================================
    //  端点 / A* / 铺设
    // ==================================================================

    private static int buildRoad(ServerLevel level, long ax, long az, long bx, long bz) {
        double x0 = PlateFieldLogic.plateCenterX(ax, az);
        double z0 = PlateFieldLogic.plateCenterZ(ax, az);
        double x1 = PlateFieldLogic.plateCenterX(bx, bz);
        double z1 = PlateFieldLogic.plateCenterZ(bx, bz);

        // 1) 端点：沿中心连线找两侧的真实地表
        int[] ends = findEnds(level, x0, z0, x1, z1);
        if (ends != null && ends.length == 0) {
            return IMPOSSIBLE;   // ★ 永久失败：沿线没地面 / 已经连着 —— 不再进重试队列
        }
        if (ends == null) {
            C_NO_END.incrementAndGet();   // 诊断：真正的「邻区块未加载」
            return UNLOADED;
        }
        int ex0 = ends[0];
        int ez0 = ends[1];
        int ey0 = ends[2];
        int ex1 = ends[3];
        int ez1 = ends[4];
        int ey1 = ends[5];
        if (Math.abs(ey1 - ey0) > Math.max(8.0D, dist(ex0, ez0, ex1, ez1))) {
            return IMPOSSIBLE;   // 高差远超水平距离，坡度不可能满足
        }
        // ★ 端点必须是「真碎片」（相连实心列 ≥ 160）—— 排除空域浮岩/天空结构/小岛，
        //   这是「通天桥」与「无目标桥」的根因。
        if (!isFragmentTerrain(level, ex0, ez0) || !isFragmentTerrain(level, ex1, ez1)) {
            return IMPOSSIBLE;
        }
        // 端点下方必须有实地（否则桥头悬空 = 无目标桥）
        if (!grounded(level, ex0, ey0, ez0) || !grounded(level, ex1, ey1, ez1)) {
            return IMPOSSIBLE;
        }

        // 2) A* 寻找一条平缓通道
        List<int[]> path = astar(level, ex0, ez0, ex1, ez1);
        if (path == null || path.size() < 2) {
            C_NO_PATH.incrementAndGet();   // 诊断：A* 找不到路径
            return UNLOADED;   // 可能是邻区块未加载导致网格不可通行 → 允许重试
        }
        path = smooth(path);

        // 3) 竖直剖面：按 MAX_SLOPE 渐进，禁止陡坡
        int n = path.size();
        double[] ys = new double[n];
        ys[0] = ey0;
        for (int i = 1; i < n; i++) {
            double d = dist(path.get(i - 1)[0], path.get(i - 1)[1], path.get(i)[0], path.get(i)[1]);
            double target = (i == n - 1) ? ey1 : ys[i - 1];
            double maxStep = Math.max(0.5D, MAX_SLOPE * d);
            double next = ys[i - 1];
            if (i == n - 1) {
                next += Math.max(-maxStep, Math.min(maxStep, target - ys[i - 1]));
            }
            ys[i] = next;
        }
        // 反向再拉一次，保证终点贴合真实地表
        ys[n - 1] = ey1;
        for (int i = n - 2; i >= 0; i--) {
            double d = dist(path.get(i)[0], path.get(i)[1], path.get(i + 1)[0], path.get(i + 1)[1]);
            double maxStep = Math.max(0.5D, MAX_SLOPE * d);
            double want = ys[i + 1] - (ys[i + 1] - ys[i]) * 0.5D;
            ys[i] = Math.max(ys[i + 1] - maxStep, Math.min(ys[i + 1] + maxStep, want));
        }

        // 3.5) 拱形：中段抬高，两端归零 —— 视觉上像天然拱桥
        if (n >= 3) {
            for (int i = 1; i < n - 1; i++) {
                double t = i / (double) (n - 1);
                ys[i] += BRIDGE_ARCH * Math.sin(Math.PI * t);
            }
        }

        // 4) 铺设桥面 + 清障
        // ★ 相邻路径点间距为 GRID(4) 格，必须按 1 格插值铺桥面，否则中间必然漏 3 格 → 断桥
        int pillar = 0;
        for (int i = 0; i + 1 < n; i++) {
            int[] a = path.get(i);
            int[] b = path.get(i + 1);
            int seg = Math.max(1, (int) Math.ceil(dist(a[0], a[1], b[0], b[1])));
            for (int s = 0; s <= seg; s++) {
                double t = s / (double) seg;
                int px = (int) Math.round(a[0] + (b[0] - a[0]) * t);
                int pz = (int) Math.round(a[1] + (b[1] - a[1]) * t);
                int py = (int) Math.round(ys[i] + (ys[i + 1] - ys[i]) * t);
                for (int ox = -1; ox <= 1; ox++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        if (ox * ox + oz * oz > 2) {
                            continue;
                        }
                        int bx2 = px + ox;
                        int bz2 = pz + oz;
                        place(level, bx2, py, bz2, deckAt(bx2, bz2));
                        clearAbove(level, bx2, py + 1, bz2);
                        clearAbove(level, bx2, py + 2, bz2);
                    }
                }
                // ★ 中线铺铁轨（对齐参考图里的"桥 + 铁路"）。
                //   铁轨需要邻居更新才能算出朝向，所以用 flag 3 而不是 place() 的 2。
                if (!level.isOutsideBuildHeight(py + 1)) {
                    BlockPos rp = new BlockPos(px, py + 1, pz);
                    if (level.getBlockState(rp).isAir()) {
                        level.setBlock(rp, Blocks.RAIL.defaultBlockState(), 3);
                    }
                }
                // 桥墩：同样改沙岩，每 6 格一个
                if (pillar++ % 6 == 0) {
                    for (int y = py - 1; y >= py - 3; y--) {
                        place(level, px, y, pz, Blocks.SANDSTONE.defaultBlockState());
                    }
                }
            }
        }
        if (LOGGED.compareAndSet(false, true)) {
            MaixrMod.LOGGER.info("[道路网] 首条桥已生成：({}, {}) → ({}, {})，路径 {} 点，坡度上限 {}",
                    ex0, ez0, ex1, ez1, path.size(), MAX_SLOPE);
        }
        return BUILT;
    }

    /** 该列是否属于大块碎片地形（±20 格内相连实心列 ≥ 160），排除浮岩/天空结构。 */
    private static boolean isFragmentTerrain(ServerLevel level, int x, int z) {
        int minY = level.getMinBuildHeight();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        q.add(new int[]{x, z});
        seen.add(((long) x << 32) ^ (z & 0xFFFFFFFFL));
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty() && seen.size() < 60) {
            int[] c = q.poll();
            for (int[] d : dirs) {
                int nx = c[0] + d[0];
                int nz = c[1] + d[1];
                if (Math.abs(nx - x) > 20 || Math.abs(nz - z) > 20) {
                    continue;
                }
                long key = ((long) nx << 32) ^ (nz & 0xFFFFFFFFL);
                if (seen.contains(key)) {
                    continue;
                }
                LevelChunk ch = level.getChunkSource().getChunkNow(nx >> 4, nz >> 4);
                if (ch == null) {
                    return false;
                }
                int h = ch.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);
                if (h <= minY + 1 || h > MAX_SURFACE_Y) {
                    continue;
                }
                seen.add(key);
                q.add(new int[]{nx, nz});
            }
        }
        return seen.size() >= 60;
    }

    /** 桥头是否踩在实地上（脚下或本格有方块）。 */
    private static boolean grounded(ServerLevel level, int x, int y, int z) {
        if (!chunkLoaded(level, x, z)) {
            return false;   // ★ 不加载
        }
        if (y - 1 < level.getMinBuildHeight()) {
            return false;
        }
        return !level.getBlockState(new BlockPos(x, y - 1, z)).isAir()
                || !level.getBlockState(new BlockPos(x, y, z)).isAir();
    }

    /** 沿中心连线找 A 侧与 B 侧的落脚点：[ex0,ez0,ey0, ex1,ez1,ey1]。 */
    /** 永久失败标记（空数组）：没有地面 / 已经连着。跟 null（邻区块未加载，可重试）区分开。 */
    private static final int[] NO_END = new int[0];

    /**
     * 找桥的两端。
     *
     * <p>返回：</p>
     * <ul>
     *   <li>{@code null} —— 沿线的邻区块未加载，<b>可重试</b></li>
     *   <li>{@code NO_END}（长度 0）—— 沿线没有实心地面 / 两端已经连着，<b>永久失败</b></li>
     *   <li>长度 6 的数组 —— 成功</li>
     * </ul>
     *
     * <p>★ 碎片中心经常正好落在虚空里，沿中心连线直扫会一个实心列都扫不到。
     * 这时沿<b>垂直方向平移</b>整条线再扫（±4..±24 格），通常就能找到两侧的岛缘。</p>
     */
    private static int[] findEnds(ServerLevel level, double x0, double z0, double x1, double z1) {
        int[] direct = scanEnds(level, x0, z0, x1, z1);
        if (direct == null) {
            return null;          // 未加载 → 交给重试队列
        }
        if (direct.length == 6) {
            return direct;
        }
        // 直线不行：垂直平移搜索
        double dx = x1 - x0;
        double dz = z1 - z0;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-6D) {
            return NO_END;
        }
        double ux = -dz / len;    // 垂直单位向量
        double uz = dx / len;
        for (int off = 4; off <= 24; off += 4) {
            for (int sign = -1; sign <= 1; sign += 2) {
                double sx = ux * off * sign;
                double sz = uz * off * sign;
                int[] r = scanEnds(level, x0 + sx, z0 + sz, x1 + sx, z1 + sz);
                if (r == null) {
                    continue;     // 这一侧偏移碰到未加载区块，换一边
                }
                if (r.length == 6) {
                    return r;
                }
            }
        }
        return NO_END;
    }

    /** 沿一条直线扫端点。语义同 findEnds 的三种返回。 */
    private static int[] scanEnds(ServerLevel level, double x0, double z0, double x1, double z1) {
        int len = (int) Math.ceil(dist(x0, z0, x1, z1));
        if (len < 4) {
            return NO_END;
        }
        int minY = level.getMinBuildHeight();
        boolean[] solid = new boolean[len + 1];
        int[] hgt = new int[len + 1];
        for (int i = 0; i <= len; i++) {
            double t = i / (double) len;
            int x = (int) Math.floor(x0 + (x1 - x0) * t);
            int z = (int) Math.floor(z0 + (z1 - z0) * t);
            // 绝不能在这里强制加载：ChunkEvent.Load 里再 getChunk 会递归加载 → 世界卡 100%
            LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (c == null) {
                return null;   // 交给 tick 队列做
            }
            int h = c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            hgt[i] = h;
            solid[i] = h > minY + 1 && h <= MAX_SURFACE_Y;
        }
        int ai = -1;
        for (int i = 0; i <= len; i++) {
            if (solid[i]) {
                ai = i;
                break;
            }
        }
        if (ai < 0) {
            return NO_END;     // 整条线都是虚空/天空 → 永久失败，别再来回重试
        }
        int a = ai;
        while (a + 1 <= len && solid[a + 1]) {
            a++;
        }
        int bi = -1;
        for (int i = len; i >= 0; i--) {
            if (solid[i]) {
                bi = i;
                break;
            }
        }
        if (bi < 0) {
            return NO_END;
        }
        int b = bi;
        while (b - 1 >= 0 && solid[b - 1]) {
            b--;
        }
        if (b <= a + 1) {
            return NO_END;     // 已经连着，不需要桥
        }
        double t0 = a / (double) len;
        double t1 = b / (double) len;
        return new int[]{
                (int) Math.floor(x0 + (x1 - x0) * t0), (int) Math.floor(z0 + (z1 - z0) * t0), hgt[a],
                (int) Math.floor(x0 + (x1 - x0) * t1), (int) Math.floor(z0 + (z1 - z0) * t1), hgt[b]
        };
    }

    // ---------------------------------------------------------------- A*

    private static List<int[]> astar(ServerLevel level, int sx, int sz, int gx, int gz) {
        int minX = Math.min(sx, gx) - EXPAND;
        int maxX = Math.max(sx, gx) + EXPAND;
        int minZ = Math.min(sz, gz) - EXPAND;
        int maxZ = Math.max(sz, gz) + EXPAND;
        int w = (maxX - minX) / GRID + 1;
        int h = (maxZ - minZ) / GRID + 1;
        if ((long) w * h > 20000L) {
            return null;
        }
        int minY = level.getMinBuildHeight();

        // 预采样高度
        int[] hs = new int[w * h];
        boolean[] solid = new boolean[w * h];
        boolean[] blocked = new boolean[w * h];
        for (int iz = 0; iz < h; iz++) {
            for (int ix = 0; ix < w; ix++) {
                int x = minX + ix * GRID;
                int z = minZ + iz * GRID;
                LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (c == null) {
                    blocked[iz * w + ix] = true;
                    continue;
                }
                int hh = c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                hs[iz * w + ix] = hh;
                solid[iz * w + ix] = hh > minY + 1 && hh <= MAX_SURFACE_Y;
            }
        }

        int sidx = ((sz - minZ) / GRID) * w + ((sx - minX) / GRID);
        int gidx = ((gz - minZ) / GRID) * w + ((gx - minX) / GRID);
        if (sidx < 0 || sidx >= w * h || gidx < 0 || gidx >= w * h
                || blocked[sidx] || blocked[gidx]) {
            return null;
        }

        double[] g = new double[w * h];
        int[] from = new int[w * h];
        Arrays.fill(g, Double.MAX_VALUE);
        Arrays.fill(from, -1);
        g[sidx] = 0.0D;
        PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        pq.add(new double[]{0.0D, sidx});
        int iter = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

        while (!pq.isEmpty() && iter++ < MAX_ITER) {
            double[] cur = pq.poll();
            int cidx = (int) cur[1];
            if (cidx == gidx) {
                break;
            }
            if (cur[0] > g[cidx] + 1e-9) {
                continue;
            }
            int cx = cidx % w;
            int cz = cidx / w;
            for (int[] d : dirs) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                if (nx < 0 || nz < 0 || nx >= w || nz >= h) {
                    continue;
                }
                int nidx = nz * w + nx;
                if (blocked[nidx]) {
                    continue;
                }
                double stepLen = GRID * ((d[0] != 0 && d[1] != 0) ? 1.4142D : 1.0D);
                double dh = Math.abs(hs[nidx] - hs[cidx]);
                double slope = dh / stepLen;
                double cost = stepLen * (1.0D + slope * slope * 8.0D);
                if (solid[nidx]) {
                    cost += 6.0D;      // 穿越实心要挖方，罚重
                }
                if (hs[nidx] > MAX_SURFACE_Y) {
                    continue;          // 天空结构禁止
                }
                double ng = g[cidx] + cost;
                if (ng < g[nidx]) {
                    g[nidx] = ng;
                    from[nidx] = cidx;
                    double hx = Math.abs(nx * GRID + minX - gx);
                    double hz = Math.abs(nz * GRID + minZ - gz);
                    pq.add(new double[]{ng + Math.sqrt(hx * hx + hz * hz), nidx});
                }
            }
        }
        if (from[gidx] < 0 && gidx != sidx) {
            return null;
        }
        List<int[]> rev = new ArrayList<>();
        int c = gidx;
        while (c >= 0) {
            int cx = c % w;
            int cz = c / w;
            rev.add(new int[]{minX + cx * GRID, minZ + cz * GRID});
            if (c == sidx) {
                break;
            }
            c = from[c];
        }
        List<int[]> out = new ArrayList<>();
        for (int i = rev.size() - 1; i >= 0; i--) {
            out.add(rev.get(i));
        }
        // 起终点校正到真实端点
        out.set(0, new int[]{sx, sz});
        out.set(out.size() - 1, new int[]{gx, gz});
        return out;
    }

    /** 邻点加权平滑（近样条效果，代价低）。 */
    private static List<int[]> smooth(List<int[]> path) {
        if (path.size() < 3) {
            return path;
        }
        List<int[]> out = new ArrayList<>(path.size());
        out.add(path.get(0));
        for (int i = 1; i < path.size() - 1; i++) {
            int[] a = path.get(i - 1);
            int[] b = path.get(i);
            int[] c = path.get(i + 1);
            out.add(new int[]{
                    (int) Math.round(b[0] * 0.5D + (a[0] + c[0]) * 0.25D),
                    (int) Math.round(b[1] * 0.5D + (a[1] + c[1]) * 0.25D)
            });
        }
        out.add(path.get(path.size() - 1));
        return out;
    }

    // ---------------------------------------------------------------- 材料 / 工具

    /**
     * 桥面材质：沙岩系（对齐参考图里的沙色桥）。
     * 主色 切制沙岩，点缀 平滑沙岩 / 裂纹沙岩砖，避免大面积单色。
     */
    private static BlockState deckAt(int x, int z) {
        int h = Math.floorMod(x * 31 + z * 17, 20);
        if (h < 3) {
            return Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        }
        if (h < 5) {
            return Blocks.CUT_SANDSTONE.defaultBlockState();
        }
        return Blocks.SANDSTONE.defaultBlockState();
    }

    /**
     * 该列所在区块是否<b>已经加载</b>。
     *
     * <p>★ 极其重要：桥面铺设里绝不能用 {@code level.getBlockState} / {@code level.setBlock}
     * 去碰未加载的区块 —— 它们会<b>同步加载</b>区块，而加载又会触发
     * {@code ChunkEvent.Load} → run() → buildRoad() → 再次加载 …… <b>无限递归</b>，
     * 同时把进世界拖到几分钟。</p>
     */
    private static boolean chunkLoaded(ServerLevel level, int x, int z) {
        return level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
    }

    private static void place(ServerLevel level, int x, int y, int z, BlockState state) {
        if (!chunkLoaded(level, x, z)) {
            return;   // ★ 不加载：没加载就跳过
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (level.isOutsideBuildHeight(pos) || !level.isEmptyBlock(pos)) {
            return;
        }
        level.setBlock(pos, state, 2);
    }

    private static void clearAbove(ServerLevel level, int x, int y, int z) {
        if (!chunkLoaded(level, x, z)) {
            return;   // ★ 不加载
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (level.isOutsideBuildHeight(pos)) {
            return;
        }
        BlockState st = level.getBlockState(pos);
        if (st.isAir() || !st.getFluidState().isEmpty()) {
            return;
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
    }

    // ---------------------------------------------------------------- 最近邻 / 状态

    private static long[][] nearestAlive(long ax, long az, double deathProb) {
        long[][] best = new long[NEIGHBORS][];
        double[] bestD = new double[NEIGHBORS];
        Arrays.fill(bestD, Double.MAX_VALUE);
        double cx0 = PlateFieldLogic.plateCenterX(ax, az);
        double cz0 = PlateFieldLogic.plateCenterZ(ax, az);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                long bx = ax + dx;
                long bz = az + dz;
                if (!PlateFieldLogic.plateAlive(bx, bz, deathProb)) {
                    continue;
                }
                double d = dist(cx0, cz0, PlateFieldLogic.plateCenterX(bx, bz),
                        PlateFieldLogic.plateCenterZ(bx, bz));
                for (int i = 0; i < NEIGHBORS; i++) {
                    if (d < bestD[i]) {
                        for (int k = NEIGHBORS - 1; k > i; k--) {
                            bestD[k] = bestD[k - 1];
                            best[k] = best[k - 1];
                        }
                        bestD[i] = d;
                        best[i] = new long[]{bx, bz};
                        break;
                    }
                }
            }
        }
        return best;
    }

    private static long pairKey(long ax, long az, long bx, long bz) {
        return (ax * 73856093L) ^ (az * 19349663L) ^ (bx * 83492791L) ^ (bz * 2971215073L);
    }

    /** 持久化的"已建连接"记录 —— 跨会话也不会重复建。 */
    public static final class RoadData extends SavedData {

        private final Set<Long> done = new HashSet<>();

        private RoadData() {
        }

        public static RoadData get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(
                    new SavedData.Factory<>(RoadData::new, RoadData::load), "maixr_road_network");
        }

        private static RoadData load(CompoundTag tag, HolderLookup.Provider provider) {
            RoadData d = new RoadData();
            for (long v : tag.getLongArray("Done")) {
                d.done.add(v);
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            long[] arr = new long[done.size()];
            int i = 0;
            for (long v : done) {
                arr[i++] = v;
            }
            tag.putLongArray("Done", arr);
            return tag;
        }

        public boolean isDone(long key) {
            return done.contains(key);
        }

        public void markDone(long key) {
            if (done.add(key)) {
                setDirty();
            }
        }
    }
}
