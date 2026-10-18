package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 悬浮取水小岛 —— 保证破碎世界里有水、有甘蔗。
 *
 * <h2>v3 修正（全部来自实机反馈）</h2>
 *
 * <table border="1">
 *   <tr><th>问题</th><th>原因</th><th>修法</th></tr>
 *   <tr><td>沙子浮空</td><td>沙环先铺、岛体后铺，环上落在岛体外的列就悬空</td>
 *       <td>先算出每列"岛体是否存在"，<b>只在该列有岛体时</b>铺沙</td></tr>
 *   <tr><td>甘蔗不在水边</td><td>沙环把池边水盖成了沙，甘蔗落在离水一格外的沙上</td>
 *       <td>甘蔗只种在<b>水平相邻列在 baseY 有水</b>且脚下是沙/土/草的列上</td></tr>
 *   <tr><td>千篇一律</td><td>正圆 + 固定参数</td>
 *       <td>半径用两路正弦扰动成有机轮廓；池心偏移、池径、岛径、装饰全部随机</td></tr>
 * </table>
 *
 * <p>整岛半径 + 2 &lt; 8，完全落在单个区块内，不跨区块写入。</p>
 */
public final class SurfaceLakes {

    public static final int CHANCE = 34;
    public static final int MIN_Y = 62;
    public static final int Y_SPREAD = 80;
    private static final int MIN_RADIUS = 4;
    private static final int MAX_RADIUS = 7;

    private SurfaceLakes() {
    }

    public static void run(WorldGenLevel level, ChunkAccess chunk) {
        int minY = level.getMinBuildHeight();
        ChunkPos cp = chunk.getPos();
        int minX = cp.getMinBlockX();
        int minZ = cp.getMinBlockZ();

        RandomSource rnd = RandomSource.create(
                level.getSeed() * 31L + cp.x * 341873118712L + cp.z * 132897987541L + 0x77AC1L);
        if (rnd.nextInt(CHANCE) != 0) {
            return;
        }

        int radius = MIN_RADIUS + rnd.nextInt(MAX_RADIUS - MIN_RADIUS + 1);
        int margin = radius + 2;
        if (margin * 2 >= 16) {
            return;
        }
        int cx = minX + margin + rnd.nextInt(16 - margin * 2);
        int cz = minZ + margin + rnd.nextInt(16 - margin * 2);
        int baseY = MIN_Y + rnd.nextInt(Y_SPREAD);

        // 空域检查（含边缘一圈）
        for (int dx = -margin - 1; dx <= margin + 1; dx++) {
            for (int dz = -margin - 1; dz <= margin + 1; dz++) {
                if (chunk.getHeight(Heightmap.Types.WORLD_SURFACE, cx + dx, cz + dz) > minY + 1) {
                    return;
                }
            }
        }

        // ★ 有机轮廓：两路正弦扰动，每个岛形状都不同
        double phase1 = rnd.nextDouble() * Math.PI * 2.0D;
        double phase2 = rnd.nextDouble() * Math.PI * 2.0D;
        double amp1 = 0.8D + rnd.nextDouble() * 1.2D;
        double amp2 = 0.4D + rnd.nextDouble() * 0.9D;

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int lim = radius + 2;

        // 每列的岛体半径（有机形状）
        double[][] rmax = new double[lim * 2 + 1][lim * 2 + 1];
        for (int dx = -lim; dx <= lim; dx++) {
            for (int dz = -lim; dz <= lim; dz++) {
                double theta = Math.atan2(dz, dx);
                rmax[dx + lim][dz + lim] = radius
                        + amp1 * Math.sin(3.0D * theta + phase1)
                        + amp2 * Math.sin(5.0D * theta + phase2);
            }
        }
        java.util.function.BiPredicate<Integer, Integer> inIsland = (dx, dz) -> {
            if (dx < -lim || dx > lim || dz < -lim || dz > lim) {
                return false;
            }
            double r = rmax[dx + lim][dz + lim];
            return dx * dx + dz * dz <= r * r;
        };

        // 1) 岛体：草/土/石，逐层收窄
        for (int dx = -lim; dx <= lim; dx++) {
            for (int dz = -lim; dz <= lim; dz++) {
                if (!inIsland.test(dx, dz)) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                level.setBlock(p.set(x, baseY, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int d = 1; d <= 4; d++) {
                    if (!inIsland.test(dx, dz) || dx * dx + dz * dz > (rmax[dx + lim][dz + lim] - d) * (rmax[dx + lim][dz + lim] - d)) {
                        break;
                    }
                    level.setBlock(p.set(x, baseY - d, z),
                            d <= 1 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }

        // 2) 水池：池心随机偏移，只挖在岛体内部
        int ox = rnd.nextInt(3) - 1;
        int oz = rnd.nextInt(3) - 1;
        int poolR = 1 + rnd.nextInt(2) + Math.max(0, (radius - 5) / 2);
        for (int dx = -poolR - 1; dx <= poolR + 1; dx++) {
            for (int dz = -poolR - 1; dz <= poolR + 1; dz++) {
                int lx = dx + ox;
                int lz = dz + oz;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > poolR + 0.4D || !inIsland.test(lx, lz)) {
                    continue;   // ★ 池边必须仍在岛体内，避免把水挖到岛外
                }
                int x = cx + lx;
                int z = cz + lz;
                if (d > poolR - 0.4D) {
                    // 池沿：沙（此时该列一定有岛体，不会是浮空沙）
                    level.setBlock(p.set(x, baseY, z), Blocks.SAND.defaultBlockState(), 2);
                } else {
                    level.setBlock(p.set(x, baseY, z), Blocks.WATER.defaultBlockState(), 2);
                    level.setBlock(p.set(x, baseY - 1, z), Blocks.WATER.defaultBlockState(), 2);
                    level.setBlock(p.set(x, baseY - 2, z), Blocks.SAND.defaultBlockState(), 2);
                }
            }
        }

        // 3) 甘蔗：只种在「水平相邻列 baseY 有水」且脚下是沙/土/草的列
        for (int dx = -lim; dx <= lim; dx++) {
            for (int dz = -lim; dz <= lim; dz++) {
                if (!inIsland.test(dx, dz)) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                if (!level.getBlockState(p.set(x, baseY, z)).is(Blocks.SAND)
                        && !level.getBlockState(p.set(x, baseY, z)).is(Blocks.GRASS_BLOCK)) {
                    continue;
                }
                if (!hasWaterAround(level, p, x, baseY, z)) {
                    continue;
                }
                int h = 1 + rnd.nextInt(3);   // 1~3 格
                for (int i = 0; i < h; i++) {
                    level.setBlock(p.set(x, baseY + 1 + i, z), Blocks.SUGAR_CANE.defaultBlockState(), 2);
                }
            }
        }

        // 4) 点缀：草/花，增加辨识度
        for (int i = 0; i < 6; i++) {
            int dx = rnd.nextInt(lim * 2 + 1) - lim;
            int dz = rnd.nextInt(lim * 2 + 1) - lim;
            if (!inIsland.test(dx, dz)) {
                continue;
            }
            int x = cx + dx;
            int z = cz + dz;
            if (!level.getBlockState(p.set(x, baseY, z)).is(Blocks.GRASS_BLOCK)) {
                continue;
            }
            if (!level.getBlockState(p.set(x, baseY + 1, z)).isAir()) {
                continue;
            }
            level.setBlock(p.set(x, baseY + 1, z),
                    rnd.nextBoolean() ? Blocks.SHORT_GRASS.defaultBlockState() : Blocks.DANDELION.defaultBlockState(), 2);
        }
    }

    /** 水平 4 邻域在<b>同一层</b>是否有水（甘蔗的硬性条件）。 */
    private static boolean hasWaterAround(WorldGenLevel level, BlockPos.MutableBlockPos p, int x, int y, int z) {
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (level.getBlockState(p.set(x + d[0], y, z + d[1])).is(Blocks.WATER)) {
                return true;
            }
        }
        return false;
    }
}
