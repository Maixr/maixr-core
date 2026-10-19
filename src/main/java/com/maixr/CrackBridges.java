package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 裂缝桥 —— <b>只在当前区块内</b>完成、不读任何邻区块、不排队。
 *
 * <h2>为什么重写（前几版的死因）</h2>
 *
 * <p>之前按"板块中心连线"建桥，需要读线段经过的邻区块高度。玩家靠近时那些区块
 * 往往还没加载，于是每次都返回 UNLOADED、进待办队列反复重试同样的失败 ——
 * <b>结果一条桥都建不出来</b>。这不是算法问题，是"跨区块依赖"这个前提就不成立。</p>
 *
 * <p>本类改为<b>只用当前区块自己的高度图</b>：在区块内部找"实心 → 虚空 → 实心"
 * 的裂缝，直接在裂缝上架桥。因为整个世界就是被裂缝撕开的，这种裂缝遍地都是，
 * 所以桥一定能出现，而且一定对齐玩家看到的裂口。</p>
 *
 * <h2>规则</h2>
 * <ul>
 *   <li>只沿 +X 与 +Z 两个方向扫（避免同一座桥建两次）；</li>
 *   <li>裂缝宽度 {@value #MIN_GAP}~{@value #MAX_GAP} 格；跨出区块的不算（保证纯本地）；</li>
 *   <li>每区块最多 {@value #MAX_PER_CHUNK} 座，且 {@value #CHANCE} 分之一概率，避免刷屏；</li>
 *   <li>桥面沿两端高差线性插值，3 格宽，只填空气；桥面上方清 2 格。</li>
 * </ul>
 */
public final class CrackBridges {

    private static final int MIN_GAP = 2;
    private static final int MAX_GAP = 12;
    private static final int MAX_PER_CHUNK = 6;
    private static final int CHANCE = 2;      // 二分之一区块尝试
    private static final int MAX_Y = 100;     // 只连玩家够得着的高度

    private CrackBridges() {
    }

    public static void run(WorldGenLevel level, ChunkAccess chunk) {
        int minY = level.getMinBuildHeight();
        ChunkPos cp = chunk.getPos();
        int minX = cp.getMinBlockX();
        int minZ = cp.getMinBlockZ();
        RandomSource rnd = RandomSource.create(
                level.getSeed() * 31L + cp.x * 341873128712L + cp.z * 132897987541L + 0xB21DL);
        if (rnd.nextInt(CHANCE) != 0) {
            return;
        }

        // 本区块 16×16 的实心图与该列地表高度
        boolean[] solid = new boolean[256];
        int[] height = new int[256];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int h = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, minX + lx, minZ + lz);
                height[lx * 16 + lz] = h;
                solid[lx * 16 + lz] = h > minY + 1 && h <= MAX_Y;
            }
        }

        int built = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int lx = 0; lx < 16 && built < MAX_PER_CHUNK; lx++) {
            for (int lz = 0; lz < 16 && built < MAX_PER_CHUNK; lz++) {
                if (!solid[lx * 16 + lz]) {
                    continue;
                }
                // +X 方向
                built += tryBridge(level, chunk, solid, height, lx, lz, 1, 0, minX, minZ, p) ? 1 : 0;
                if (built >= MAX_PER_CHUNK) {
                    break;
                }
                // +Z 方向
                built += tryBridge(level, chunk, solid, height, lx, lz, 0, 1, minX, minZ, p) ? 1 : 0;
            }
        }
    }

    private static boolean tryBridge(WorldGenLevel level, ChunkAccess chunk, boolean[] solid, int[] height,
                                     int lx, int lz, int dx, int dz, int minX, int minZ,
                                     BlockPos.MutableBlockPos p) {
        int h1 = height[lx * 16 + lz];
        for (int d = 1; d <= MAX_GAP; d++) {
            int nx = lx + dx * d;
            int nz = lz + dz * d;
            if (nx < 0 || nz < 0 || nx > 15 || nz > 15) {
                return false;   // 出区块 → 不建（保证纯本地）
            }
            if (solid[nx * 16 + nz]) {
                if (d - 1 < MIN_GAP) {
                    return false;   // 缝隙太窄
                }
                int h2 = height[nx * 16 + nz];
                if (Math.abs(h2 - h1) > 8) {
                    return false;
                }
                buildDeck(level, p, minX + lx, minZ + lz, minX + nx, minZ + nz, h1, h2);
                return true;
            }
        }
        return false;   // 一路虚空或超出上限
    }

    private static void buildDeck(WorldGenLevel level, BlockPos.MutableBlockPos p,
                                  int x1, int z1, int x2, int z2, int y1, int y2) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        boolean alongX = Math.abs(x2 - x1) >= Math.abs(z2 - z1);
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : i / (double) steps;
            int x = (int) Math.round(x1 + (x2 - x1) * t);
            int z = (int) Math.round(z1 + (z2 - z1) * t);
            int y = (int) Math.round(y1 + (y2 - y1) * t);
            for (int w = -1; w <= 1; w++) {
                int bx = alongX ? x : x + w;
                int bz = alongX ? z + w : z;
                p.set(bx, y, bz);
                if (level.isOutsideBuildHeight(p)) {
                    continue;
                }
                if (level.getBlockState(p).isAir()) {
                    level.setBlock(p, deck(bx, bz), 2);
                }
                // 清桥面上方 2 格
                for (int up = 1; up <= 2; up++) {
                    BlockPos q = new BlockPos(bx, y + up, bz);
                    if (level.isOutsideBuildHeight(q)) {
                        continue;
                    }
                    BlockState st = level.getBlockState(q);
                    if (!st.isAir() && st.getFluidState().isEmpty()) {
                        level.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static BlockState deck(int x, int z) {
        int h = Math.floorMod(x * 31 + z * 17, 20);
        if (h < 3) {
            return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        }
        if (h < 5) {
            return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
        }
        return Blocks.STONE_BRICKS.defaultBlockState();
    }
}
