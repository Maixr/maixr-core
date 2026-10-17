package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 修复引擎：把 maixr:overworld_reborn 里「本该存在」的地形，搬进 maixr:overworld_broken。
 *
 * <p>两个维度用的是完全相同的 noise settings / biome source / 世界种子，
 * 所以 reborn 同坐标的地形 == broken 那个位置本该有的地形。
 *
 * <p><b>圆心扩散</b>：以伊甸圆心为准，按半径一圈圈往外填（环宽 {@link #RING_WIDTH} 格）。
 * 每个区块内再按「到圆心的距离」排序列，所以外缘是圆的，不是一个方块一个方块地跳。
 *
 * <p><b>同步</b>：用 {@code level.setBlock(pos, state, 2)} 而不是 {@code chunk.setBlockState}，
 * 因为后者不发包给客户端 —— 那正是之前"服务端填了但玩家还看到虚空"的原因。
 */
public final class EdenRepair {
    /**
     * 最大修复半径（毕业圆的半径）。
     *
     * <p>★ 95% 虚空之后，圆里几乎每个区块都要真填一遍，成本按半径平方涨：
     * 半径 130 → 约 5.3 万个区块 ≈ 200 MB 存档；半径 260 → 约 21 万个 ≈ 800 MB。
     * 所以毕业圆定在 130 格（直径 4 公里，玩家一辈子走不完）。
     */
    public static final int MAX_RADIUS = 130;
    /** 每次向外推一圈的宽度 */
    public static final int RING_WIDTH = 2;
    /** 每提交一次，圆向外长多少格 */
    public static final int RADIUS_PER_SUBMIT = 1;
    /** 每跨一个纪元，圆额外向外长多少格 */
    public static final int RADIUS_PER_STAGE = 3;
    public static final int Y_MIN = 50;
    public static final int Y_MAX = 256;      // 一直修到世界顶：山顶、树冠、雪线都能还原
    public static final int LAYERS = Y_MAX - Y_MIN + 1;
    /** 每 tick 处理多少个方块（含空气/相同方块）—— 这是<b>上限</b>，实际会按服务器负载动态下调 */
    public static final int BLOCKS_PER_TICK = 512;
    /** 下限：服务器再卡也至少推进这么多，保证修复不会完全停摆 */
    public static final int BLOCKS_PER_TICK_MIN = 96;

    /**
     * ★ 按服务器当前 tick 耗时自适应配额（服务端优化）。
     *
     * <p>MSPT 取最近 100 tick 的平均值：健康（&lt;22ms）时跑满 512，
     * 一卡就逐级降到 384 / 256 / 128，最差 96 —— 修复不会把服务器压垮，
     * 但也不会完全停下（玩家提交的物品总得慢慢长出来）。
     */
    public static int adaptiveQuota(net.minecraft.server.MinecraftServer server) {
        if (server == null) return BLOCKS_PER_TICK;
        double mspt = server.getCurrentSmoothedTickTime();
        if (mspt >= 45.0D) return BLOCKS_PER_TICK_MIN;
        if (mspt >= 35.0D) return 128;
        if (mspt >= 28.0D) return 256;
        if (mspt >= 22.0D) return 384;
        return BLOCKS_PER_TICK;
    }
    /** 旧的额度常量（保留仅为兼容，不再使用） */
    @Deprecated
    public static final int BUDGET_PER_SUBMIT = 0;

    /** 半径 512 的圆里一共有多少根柱子 */
    public static long totalColumns() {
        long n = 0;
        for (int x = -MAX_RADIUS; x <= MAX_RADIUS; x++) {
            int rem = MAX_RADIUS * MAX_RADIUS - x * x;
            if (rem < 0) continue;
            n += (long) (Math.floor(Math.sqrt(rem))) * 2 + 1;
        }
        return n;
    }

    /** 半径 512、y 40~100 一共要扫多少个格子 */
    public static long totalCells() {
        return totalColumns() * LAYERS;
    }

    private static int ringStart(int ringIndex) { return ringIndex * RING_WIDTH; }

    private static boolean columnInRing(int wx, int wz, EdenProgress p, int ringIndex) {
        long dx = wx - p.centerX;
        long dz = wz - p.centerZ;
        long d2 = dx * dx + dz * dz;
        int dist = (int) Math.floor(Math.sqrt((double) d2));
        int start = ringStart(ringIndex);
        return dist >= start && dist < start + RING_WIDTH;
    }

    /** 区块的最近/最远距离区间，是否和当前环有交集 */
    private static boolean chunkTouchesRing(int cx, int cz, EdenProgress p, int ringIndex) {
        int minX = cx * 16 - p.centerX, maxX = minX + 15;
        int minZ = cz * 16 - p.centerZ, maxZ = minZ + 15;
        int nearX = clampNear(minX, maxX), nearZ = clampNear(minZ, maxZ);
        double minDist = Math.sqrt((double) nearX * nearX + (double) nearZ * nearZ);
        int farX = Math.max(Math.abs(minX), Math.abs(maxX));
        int farZ = Math.max(Math.abs(minZ), Math.abs(maxZ));
        double maxDist = Math.sqrt((double) farX * farX + (double) farZ * farZ);
        int start = ringStart(ringIndex);
        return maxDist >= start && minDist < start + RING_WIDTH;
    }

    private static int clampNear(int min, int max) {
        if (min > 0) return min;
        if (max < 0) return max;
        return 0;
    }

    /** 在半径范围内取下一个还有活干的区块；返回 false 表示这一环扫完了 */
    private static boolean pickNextChunk(EdenProgress p) {
        int span = MAX_RADIUS / 16 + 1;
        int side = span * 2 + 1;
        int ccx = p.centerX >> 4;
        int ccz = p.centerZ >> 4;
        for (int i = p.ringChunkIndex; i < side * side; i++) {
            int cx = ccx + (i % side - span);
            int cz = ccz + (i / side - span);
            if (p.ringRadius == 0 && cx == ccx && cz == ccz) continue;   // 中心区块本来就是陆地
            if (!chunkTouchesRing(cx, cz, p, p.ringRadius / RING_WIDTH)) continue;
            p.ringChunkIndex = i + 1;
            p.currentChunk = ChunkPos.asLong(cx, cz);
            p.cursor = 0;
            return true;
        }
        return false;
    }

    /**
     * 推进一步。返回 true 表示刚刚填完一个区块（调用方用来记功）。
     */
    public static boolean step(ServerLevel broken, ServerLevel reborn, EdenProgress p) {
        if (!p.canRepair()) return false;

        int ringIndex = p.ringRadius / RING_WIDTH;
        if (ringStart(ringIndex) > MAX_RADIUS) return false;

        int quota = adaptiveQuota(broken.getServer());
        int consumed = 0;
        boolean finishedChunk = false;
        int guard = 0;
        int radiusTarget = p.targetRadius();

        while (consumed < quota && guard++ < 512) {
            if (p.currentChunk == -1L) {
                if (!pickNextChunk(p)) {
                    // 这一环绕完了，向外推一圈
                    //
                    // ★ 这里以前写的是「next > radiusTarget 就停下」，于是 RING_WIDTH=2 碰上奇数目标半径
                    //   （比如刚提交 1 次：目标半径 1）时会永远停在半径 0 ——
                    //   而半径 0~7 整段都落在<b>圆心所在的那个区块</b>里，pickNextChunk 又特意跳过圆心区块，
                    //   结果就是"修复进度条卡在 半径 0/1 永远不动"。
                    //   改成「已经到过目标半径才停」，允许环游标最多越过目标 1 格。
                    if (p.ringRadius >= radiusTarget) {
                        p.currentChunk = -1L;
                        p.cursor = 0;
                        return finishedChunk;      // 等玩家再提交
                    }
                    p.ringRadius = p.ringRadius + RING_WIDTH;
                    p.ringChunkIndex = 0;
                    p.markDirty();
                    ringIndex = p.ringRadius / RING_WIDTH;
                    continue;
                }
            }

            ChunkPos pos = new ChunkPos(p.currentChunk);
            LevelChunk source = reborn.getChunk(pos.x, pos.z);
            LevelChunk target = broken.getChunk(pos.x, pos.z);

            while (consumed < quota && p.cursor < 256) {
                int col = p.cursor++;
                int wx = pos.getMinBlockX() + (col & 15);
                int wz = pos.getMinBlockZ() + (col >> 4);
                if (!columnInRing(wx, wz, p, ringIndex)) continue;    // 这根柱子不在当前环里
                boolean any = false;
                for (int y = Y_MIN; y <= Y_MAX && consumed < quota; y++) {
                    BlockPos bp = new BlockPos(wx, y, wz);
                    consumed++;
                    BlockState from = source.getBlockState(bp);
                    if (from.isAir()) continue;
                    if (target.getBlockState(bp) == from) continue;
                    // 液体用 flag 3（带邻居更新），否则水不会"安顿"下来，看起来像海底没水
                    boolean fluid = !from.getFluidState().isEmpty();
                    broken.setBlock(bp, from, fluid ? 3 : 2);
                    any = true;
                }
                if (any) p.filledColumns++;
            }

            if (p.cursor >= 256) {
                p.currentChunk = -1L;
                p.cursor = 0;
                finishedChunk = true;
            }
        }

        p.budget = 0;                 // 额度机制已废弃，改由目标半径驱动
        p.markDirty();
        return finishedChunk;
    }

    private EdenRepair() {}
}
