package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 碎片连桥 v6 —— 以碎片（板块）为单位，每碎片最多 {@value #NEIGHBORS} 座。
 *
 * <h2>v6 相对 v5 的修正（全部来自玩家实机反馈）</h2>
 * <ul>
 *   <li><b>去掉栏杆</b>（玩家：桥不需要围栏）。</li>
 *   <li><b>桥面换下半砖</b>（玩家建议）：{@code stone_brick_slab} 底面 —
 *       台阶状桥面观感连续，且不会像整块那样"鼓"出来。</li>
 *   <li><b>补洞</b>：相邻采样点 Y 跳变 > 1 时<b>逐格补柱</b>，并把横向刷子扩到
 *       3 格 —— 原来斜向桥面会漏出 1 格宽的洞。</li>
 *   <li><b>不连到树上</b>：高度图改用 {@code MOTION_BLOCKING_NO_LEAVES}
 *       （原版 {@code WORLD_SURFACE} 会把树叶算进"地表"，于是桥落在树冠上）。</li>
 *   <li><b>取消长度上限</b>（玩家要求）：只保留一个防爆上限 {@value #HARD_MAX_SPAN}，
 *       正常距离内的碎片全部尝试连接。</li>
 *   <li><b>提高连接成功率</b>：A/B 两侧允许在连线前 70% / 后 70% 内找到实心段，
 *       并且允许"中间夹着非实心"的多段情况取最外侧两端。</li>
 * </ul>
 *
 * <p>仍由 {@code ChunkEvent.Load} 触发（生成阶段跨区块读高度会崩服），
 * 失败于"邻区块未加载"时登记待重试，后续区块加载补建。</p>
 */
public final class FragmentBridges {

    public static final int NEIGHBORS = 5;

    private static final int SEARCH_CELLS = 2;
    private static final int DECK_HALF_WIDTH = 1;
    /** ★ 拱高已取消（ARCH = 0）：正弦拱在 t→0/1 处导数 = ARCH·π ≈ 9.4，
     *  桥头会翘得很陡，和 tan 约束冲突。改成纯直线桥面。 */
    private static final double ARCH = 0.0D;
    private static final int MIN_SPAN = 4;

    /** 防爆上限（不是"长度上限"，只是避免一次铺几千格）。 */
    private static final int HARD_MAX_SPAN = 512;

    private static final int MAX_DY = 48;

    /** ★ 端点最高 Y：天空结构从 y=201 起，超过这个高度的"地表"是浮空结构，不能当桥端点。 */
    private static final int MAX_SURFACE_Y = 180;

    /** ★ 端点最低 Y：两端都必须 y > 60（玩家要求）。 */
    private static final int MIN_ENDPOINT_Y = 60;

    /** ★ 桥面最大坡度 tan：|落差| / 水平跨距 必须 < 1.1（严格 / relaxed 都套）。 */
    private static final double MAX_TAN = 1.1D;

    /** 端点不合格时，沿连线往里最多再找几格。 */
    private static final int ENDPOINT_SEARCH = 16;

    /**
     * ★ 端点必须是「只在地表出现」的方块 —— 用来排掉裸露岩层和被撕开的洞穴顶。
     * 配合 {@link #validEndpoint} 里「上方 8 格全空气」的露天判定。
     */
    private static final java.util.Set<Block> SURFACE_BLOCKS = java.util.Set.of(
            Blocks.GRASS_BLOCK,
            Blocks.SAND,
            Blocks.RED_SAND,
            Blocks.DIRT,
            Blocks.COARSE_DIRT,
            Blocks.PODZOL,
            Blocks.GRAVEL);

    /** 桥面<b>主体</b>：石砖（玩家要求：主体仍石砖）。 */
    private static final BlockState DECK = Blocks.STONE_BRICKS.defaultBlockState();
    /** 桥面<b>缺口修补</b>：下半砖（只有采样点之间的竖直缝隙用它，不是整条桥都换半砖）。 */
    private static final BlockState PATCH = Blocks.STONE_BRICK_SLAB.defaultBlockState();

    private static final Map<ResourceKey<Level>, Set<Long>> PENDING = new ConcurrentHashMap<>();

    private FragmentBridges() {
    }

    public static void run(ServerLevel level, LevelChunk chunk) {
        // 诊断：每 500 次尝试打一行，定位"一座桥都没有"卡在哪条闸
        int tries = C_TRY.get();
        if (tries > 0 && tries % 500 == 0) {
            MaixrMod.LOGGER.info("[连桥] 尝试 {} / 建成 {} / 端点不合格 {} / 坡度超限 {}"
                            + " / 未加载 {} / 无地面线 {} / 中间有碎片 {} / 同碎片 {} / 跨距越界 {}",
                    tries, C_BUILT.get(), C_NO_ENDPOINT.get(), C_TAN.get(),
                    C_UNLOADED.get(), C_NO_LINE.get(), C_MID_FRAG.get(),
                    C_SAME_FRAG.get(), C_SPAN.get());
        }
        double band = MaixrConfig.uniformBand();
        double deathProb = FractureDensityFunction.curve(FractureDensityFunction.PLATE_DEATH, band);
        if (deathProb <= 0.0D || deathProb >= 1.0D) {
            return;
        }
        Set<Long> pending = PENDING.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet());
        if (!pending.isEmpty()) {
            int n = 0;
            for (Long k : new ArrayList<>(pending)) {
                if (n++ >= 8) {
                    break;
                }
                if (buildPlate(level, k >> 32, (int) (long) k, deathProb)) {
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
                    continue;
                }
                if (!buildPlate(level, ax, az, deathProb)) {
                    pending.add((ax << 32) ^ (az & 0xFFFFFFFFL));
                }
            }
        }
    }

    private static boolean buildPlate(ServerLevel level, long ax, long az, double deathProb) {
        boolean resolved = true;
        int built = 0;
        long[][] near = nearestAlive(ax, az, deathProb);
        for (long[] b : near) {
            if (b == null || built >= NEIGHBORS) {
                continue;
            }
            if (ax > b[0] || (ax == b[0] && az > b[1])) {
                continue;
            }
            int r = tryBuild(level, ax, az, b[0], b[1], false);
            if (r == BUILT) {
                built++;
            } else if (r == UNLOADED) {
                resolved = false;
            }
        }
        // ★★ 保证连接：严格规则下一座都没建成 → 放宽（忽略去重、允许线段穿过地形，
        //    只在空隙处铺桥面），连到最近的存活碎片。这样不会再出现孤儿碎片。
        if (built == 0) {
            for (long[] b : near) {
                if (b == null) {
                    continue;
                }
                int r = tryBuild(level, ax, az, b[0], b[1], true);
                if (r == BUILT) {
                    built++;
                    break;
                }
                if (r == UNLOADED) {
                    resolved = false;
                    break;
                }
            }
        }
        return resolved;
    }

    /** 诊断计数：分别记录各条拒绝原因，定位"一座桥都没有"到底卡在哪。 */
    public static final java.util.concurrent.atomic.AtomicInteger C_TRY =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger C_NO_ENDPOINT =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger C_TAN =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger C_BUILT =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger C_UNLOADED =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger C_MID_FRAG =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger C_SAME_FRAG =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger C_SPAN =
            new java.util.concurrent.atomic.AtomicInteger();
    public static final java.util.concurrent.atomic.AtomicInteger C_NO_LINE =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final int NO_BRIDGE = 0;
    private static final int BUILT = 1;
    private static final int UNLOADED = -1;

    private static long[][] nearestAlive(long ax, long az, double deathProb) {
        long[][] best = new long[NEIGHBORS][];
        double[] bestD = new double[NEIGHBORS];
        java.util.Arrays.fill(bestD, Double.MAX_VALUE);
        double cx0 = PlateFieldLogic.plateCenterX(ax, az);
        double cz0 = PlateFieldLogic.plateCenterZ(ax, az);
        for (int dx = -SEARCH_CELLS; dx <= SEARCH_CELLS; dx++) {
            for (int dz = -SEARCH_CELLS; dz <= SEARCH_CELLS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                long bx = ax + dx;
                long bz = az + dz;
                if (!PlateFieldLogic.plateAlive(bx, bz, deathProb)) {
                    continue;
                }
                double ddx = PlateFieldLogic.plateCenterX(bx, bz) - cx0;
                double ddz = PlateFieldLogic.plateCenterZ(bx, bz) - cz0;
                double d = ddx * ddx + ddz * ddz;
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

    private static int tryBuild(ServerLevel level, long ax, long az, long bx, long bz, boolean relaxed) {
        double x0 = PlateFieldLogic.plateCenterX(ax, az);
        double z0 = PlateFieldLogic.plateCenterZ(ax, az);
        double x1 = PlateFieldLogic.plateCenterX(bx, bz);
        double z1 = PlateFieldLogic.plateCenterZ(bx, bz);
        double len = Math.sqrt((x1 - x0) * (x1 - x0) + (z1 - z0) * (z1 - z0));
        if (len < 1.0D || len > HARD_MAX_SPAN * 1.5D) {
            return NO_BRIDGE;
        }
        C_TRY.incrementAndGet();
        int steps = (int) Math.ceil(len);
        boolean[] solid = new boolean[steps + 1];
        int[] hgt = new int[steps + 1];
        int minY = level.getMinBuildHeight();

        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.floor(x0 + (x1 - x0) * t);
            int z = (int) Math.floor(z0 + (z1 - z0) * t);
            LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (c == null) {
                C_UNLOADED.incrementAndGet();
                return UNLOADED;
            }
            // ★ NO_LEAVES：树叶不算地表，避免桥连到树冠上
            int h = c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            hgt[i] = h;
            // ★ 天空结构/浮空岛不算地表：只认 MAX_SURFACE_Y 以下的实心列
            solid[i] = h > minY + 1 && h <= MAX_SURFACE_Y;
        }

        // A 侧：前 70% 内第一段连续实心的末端
        int ai = -1;
        for (int i = 0; i <= (int) (steps * 0.7); i++) {
            if (solid[i]) {
                ai = i;
                break;
            }
        }
        if (ai < 0) {
            C_NO_LINE.incrementAndGet();
            return NO_BRIDGE;
        }
        int a = ai;
        while (a + 1 <= steps && solid[a + 1]) {
            a++;
        }
        // B 侧：后 70% 内最后一段连续实心的开端
        int bi = -1;
        for (int i = steps; i >= (int) (steps * 0.3); i--) {
            if (solid[i]) {
                bi = i;
                break;
            }
        }
        if (bi < 0) {
            C_NO_LINE.incrementAndGet();
            return NO_BRIDGE;
        }
        int b = bi;
        while (b - 1 >= 0 && solid[b - 1]) {
            b--;
        }
        if (b <= a + 1) {
            return NO_BRIDGE;   // 已经连着
        }

        // ★ 端点合格性：y>60 + 地表白名单 + 上方 8 格露天。
        //   不合格就沿连线往里退，最多 ENDPOINT_SEARCH 格（玩家选定：退而不是直接放弃）。
        int na = -1;
        for (int i = a; i <= Math.min(b - 2, a + ENDPOINT_SEARCH); i++) {
            int xa = (int) Math.floor(x0 + (x1 - x0) * (i / (double) steps));
            int za = (int) Math.floor(z0 + (z1 - z0) * (i / (double) steps));
            if (validEndpoint(level, xa, za)) {
                na = i;
                break;
            }
        }
        if (na < 0) {
            C_NO_ENDPOINT.incrementAndGet();
            return NO_BRIDGE;
        }
        int nb = -1;
        for (int i = b; i >= Math.max(na + 2, b - ENDPOINT_SEARCH); i--) {
            int xb = (int) Math.floor(x0 + (x1 - x0) * (i / (double) steps));
            int zb = (int) Math.floor(z0 + (z1 - z0) * (i / (double) steps));
            if (validEndpoint(level, xb, zb)) {
                nb = i;
                break;
            }
        }
        if (nb < 0) {
            C_NO_ENDPOINT.incrementAndGet();
            return NO_BRIDGE;
        }
        a = na;
        b = nb;
        if (!relaxed) {
            for (int i = a + 1; i < b; i++) {
                if (solid[i]) {
                    C_MID_FRAG.incrementAndGet();
                    return NO_BRIDGE;   // 中间有别的碎片 → 不跨碎片连
                }
            }
        }

        // ★ 同一碎片自连检测：两端若在实心地形的同一连通块里 → 属于同一碎片 → 不建
        int ex0 = (int) Math.floor(x0 + (x1 - x0) * (a / (double) steps));
        int ez0 = (int) Math.floor(z0 + (z1 - z0) * (a / (double) steps));
        int ex1 = (int) Math.floor(x0 + (x1 - x0) * (b / (double) steps));
        int ez1 = (int) Math.floor(z0 + (z1 - z0) * (b / (double) steps));
        if (sameFragment(level, ex0, ez0, ex1, ez1)) {
            C_SAME_FRAG.incrementAndGet();
            return NO_BRIDGE;
        }

        double span = (b - a) * (len / steps);
        if (span < MIN_SPAN || span > HARD_MAX_SPAN) {
            C_SPAN.incrementAndGet();
            return NO_BRIDGE;
        }
        int y0 = hgt[a];
        int y1 = hgt[b];
        // ★ 坡度约束：tan = |落差| / 水平跨距，必须 < MAX_TAN(1.1)。
        //   原来只比 MAX_DY(48) 绝对值，短跨也能很陡；且 relaxed 放宽到 4 倍。
        //   现在严格 / relaxed 用同一个门槛。
        if (span < 1.0D || Math.abs(y1 - y0) / span >= MAX_TAN) {
            C_TAN.incrementAndGet();
            return NO_BRIDGE;
        }

        double px0 = x0 + (x1 - x0) * (a / (double) steps);
        double pz0 = z0 + (z1 - z0) * (a / (double) steps);
        double px1 = x0 + (x1 - x0) * (b / (double) steps);
        double pz1 = z0 + (z1 - z0) * (b / (double) steps);
        double dx = px1 - px0;
        double dz = pz1 - pz0;
        double plen = Math.sqrt(dx * dx + dz * dz);
        if (plen < 1.0D) {
            return NO_BRIDGE;
        }
        double perpX = -dz / plen;
        double perpZ = dx / plen;

        int n = Math.max(2, (int) Math.ceil(plen));
        int prevY = Integer.MIN_VALUE;
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            double px = px0 + dx * t;
            double pz = pz0 + dz * t;
            int py = (int) Math.round(y0 + (y1 - y0) * t + ARCH * Math.sin(Math.PI * t));

            for (int w = -DECK_HALF_WIDTH; w <= DECK_HALF_WIDTH; w++) {
                int bx2 = (int) Math.floor(px + perpX * w);
                int bz2 = (int) Math.floor(pz + perpZ * w);
                // ★ 补柱：相邻采样 Y 跳变时把中间格子补齐，杜绝桥面破洞
                if (prevY != Integer.MIN_VALUE && Math.abs(py - prevY) > 1) {
                    int lo = Math.min(py, prevY);
                    int hi = Math.max(py, prevY);
                    for (int yy = lo + 1; yy < hi; yy++) {
                        place(level, bx2, yy, bz2, PATCH);
                    }
                }
                // relaxed 模式下只铺在空隙上：穿过地形的那几段不写，避免在实心里挖洞
                if (!relaxed || (i >= 0 && i <= steps && !solid[i])) {
                    place(level, bx2, py, bz2, DECK);
                }
            }
            prevY = py;
        }
        C_BUILT.incrementAndGet();
        return BUILT;
    }

    /** 两个地表列是否属于同一片实心地形（4 邻域 BFS，上限 3000 列）。 */
    private static boolean sameFragment(ServerLevel level, int ax, int az, int bx, int bz) {
        int minY = level.getMinBuildHeight();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<long[]> q = new java.util.ArrayDeque<>();
        q.add(new long[]{ax, az});
        seen.add(((long) ax << 32) ^ (az & 0xFFFFFFFFL));
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty() && seen.size() < 3000) {
            long[] c = q.poll();
            int x = (int) c[0];
            int z = (int) c[1];
            if (x == bx && z == bz) {
                return true;
            }
            for (int[] d : dirs) {
                int nx = x + d[0];
                int nz = z + d[1];
                long key = ((long) nx << 32) ^ (nz & 0xFFFFFFFFL);
                if (seen.contains(key)) {
                    continue;
                }
                LevelChunk ch = level.getChunkSource().getChunkNow(nx >> 4, nz >> 4);
                if (ch == null) {
                    return false;   // 读不到，保守认为不同碎片
                }
                int nh = ch.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz);
                if (nh <= minY + 1 || nh > MAX_SURFACE_Y) {
                    continue;       // 虚空列或天空结构
                }
                seen.add(key);
                q.add(new long[]{nx, nz});
            }
        }
        return false;
    }

    /**
     * 该列能否作为桥端点。三个条件缺一不可：
     * <ol>
     *   <li>地表高度 {@code h} 满足 {@code MIN_ENDPOINT_Y < h <= MAX_SURFACE_Y}
     *       （两端都必须 y > 60）</li>
     *   <li>{@code h-1} 的方块属于 {@link #SURFACE_BLOCKS} —— 只认草/沙/红沙/泥土/粗土/灰化土/砂砾，
     *       排除裸露岩层与洞穴</li>
     *   <li>{@code h .. h+7} 全空气 —— 端点必须是露天的，不能是洞穴里/悬崖下</li>
     * </ol>
     */
    private static boolean validEndpoint(ServerLevel level, int x, int z) {
        LevelChunk c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (c == null) {
            return false;   // 不触发加载
        }
        int h = c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (h <= MIN_ENDPOINT_Y || h > MAX_SURFACE_Y) {
            return false;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        if (!SURFACE_BLOCKS.contains(level.getBlockState(p.set(x, h - 1, z)).getBlock())) {
            return false;
        }
        for (int d = 0; d < 8; d++) {
            if (!level.getBlockState(p.set(x, h + d, z)).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static void place(ServerLevel level, int x, int y, int z, BlockState state) {
        BlockPos pos = new BlockPos(x, y, z);
        if (level.isOutsideBuildHeight(pos) || !level.isEmptyBlock(pos)) {
            return;
        }
        level.setBlock(pos, state, 2);
    }
}
