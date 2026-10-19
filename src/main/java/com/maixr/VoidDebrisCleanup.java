package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayDeque;

/**
 * 虚空残余清理 —— 只清除<b>整块</b>悬在虚空里的岩浆与紫水晶洞。
 *
 * <h2>★★ 为什么必须按「整块」删（两次实机翻车记录）</h2>
 *
 * <p>v1：按列判"下方 4 格全空气"。浮空晶洞的<b>底部</b>下方是它自己的方块，
 * 于是底部保留、上部删掉 → 玩家看到"<b>一半有一半无、缺个角</b>"。</p>
 *
 * <p>v2：按列判"向下 96 格找不到非晶洞支撑"。理论上每块都会判成悬空，
 * 但晶洞是<b>球</b>：贴着岩壁的那一半列有支撑、悬空的那一半列没支撑，
 * 结果仍然被<b>拦腰切一半</b>（就是玩家截图里那种"纸杯蛋糕"）。</p>
 *
 * <p>v3（本版）：<b>先 BFS 出整坨连通块，再整坨决定</b>——
 * 只要其中<b>任意一块</b>有非晶洞支撑，整坨保留；否则整坨全删。
 * 另外，<b>碰到区块边界的团块一律不动</b>（无法确认另一半是否有支撑，
 * 宁可留着也绝不切一半）。</p>
 */
public final class VoidDebrisCleanup {

    /** 判定晶洞悬空时向下探测的最大格数。 */
    public static final int VOID_DEPTH = 96;

    /** 单个团块最多 BFS 多少块，防御性上限。 */
    private static final int MAX_BLOB = 4000;

    private static final TagKey<Block> AMETHYST_GEODE = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("maixr", "void_geode_blocks"));

    /** 调色板快路径用的谓词：section 里只要有岩浆或晶洞族就值得扫。 */
    private static final java.util.function.Predicate<BlockState> MIXED_CHECK =
            st -> st.is(Blocks.LAVA) || st.is(AMETHYST_GEODE);

    private VoidDebrisCleanup() {
    }

    /** 在 dressing 链尾 与 区块加载时调用（仅破碎世界主世界）。 */
    public static void run(WorldGenLevel level, ChunkAccess chunk) {
        int minY = level.getMinBuildHeight();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        // 已处理过的坐标（避免同一团块被多处重复 BFS）
        java.util.Set<Long> done = new java.util.HashSet<>();

        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection sec = sections[si];
            if (sec == null || sec.hasOnlyAir()) {
                continue;
            }
            // ★ 调色板快路径：该 section 不含岩浆/晶洞族就整段跳过（只查 palette，不遍历 4096 格）
            if (!sec.maybeHas(MIXED_CHECK)) {
                continue;
            }
            int baseY = minY + (si << 4);
            for (int ly = 0; ly < 16; ly++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        BlockState st = sec.getBlockState(lx, ly, lz);
                        boolean lava = st.is(Blocks.LAVA);
                        boolean geode = st.is(AMETHYST_GEODE);
                        if (!lava && !geode) {
                            continue;
                        }
                        int x = minX + lx;
                        int y = baseY + ly;
                        int z = minZ + lz;
                        long key = packKey(x, y, z);
                        if (done.contains(key)) {
                            continue;
                        }
                        if (lava) {
                            // 岩浆照旧单块判定：正下方一格是空气 → 悬空岩浆带
                            done.add(key);
                            if (y - 1 < minY || level.getBlockState(p.set(x, y - 1, z)).isAir()) {
                                level.setBlock(p.set(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                            }
                            continue;
                        }
                        collectAndCleanBlob(level, chunk, minX, minZ, x, y, z, p, done, minY);
                    }
                }
            }
        }
    }

    /** BFS 一整坨晶洞族方块，整坨决定删/留。 */
    private static void collectAndCleanBlob(WorldGenLevel level, ChunkAccess chunk,
                                            int minX, int minZ, int sx, int sy, int sz,
                                            BlockPos.MutableBlockPos p,
                                            java.util.Set<Long> done, int minY) {
        ArrayDeque<int[]> q = new ArrayDeque<>();
        java.util.List<int[]> blob = new java.util.ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        q.add(new int[]{sx, sy, sz});
        seen.add(packKey(sx, sy, sz));

        boolean touchesBorder = false;

        while (!q.isEmpty() && blob.size() < MAX_BLOB) {
            int[] c = q.poll();
            int x = c[0], y = c[1], z = c[2];
            blob.add(c);
            // 碰区块边界就不动（无法确认另一半）
            int lx = x - minX;
            int lz = z - minZ;
            if (lx <= 0 || lx >= 15 || lz <= 0 || lz >= 15) {
                touchesBorder = true;
            }
            for (int[] d : SIX) {
                int nx = x + d[0];
                int ny = y + d[1];
                int nz = z + d[2];
                if (ny < minY || ny >= level.getMaxBuildHeight()) {
                    continue;
                }
                long k = packKey(nx, ny, nz);
                if (seen.contains(k)) {
                    continue;
                }
                if (!level.getBlockState(p.set(nx, ny, nz)).is(AMETHYST_GEODE)) {
                    continue;
                }
                seen.add(k);
                q.add(new int[]{nx, ny, nz});
            }
        }

        // 标记整坨已处理（无论删留）
        for (int[] b : blob) {
            done.add(packKey(b[0], b[1], b[2]));
        }

        if (touchesBorder) {
            return;   // ★ 跨区块团块：不动，绝不切一半
        }

        // 整坨里只要有一块找到「非晶洞、非空气」的支撑 → 整坨保留
        for (int[] b : blob) {
            if (hasSupport(level, p, b[0], b[1], b[2], minY)) {
                return;
            }
        }
        // 整坨悬空 → 全部删掉
        for (int[] b : blob) {
            level.setBlock(p.set(b[0], b[1], b[2]), Blocks.AIR.defaultBlockState(), 2);
        }
    }

    /** 该块正下方 {@value #VOID_DEPTH} 格内，有没有「非空气且非晶洞」的方块。 */
    private static boolean hasSupport(WorldGenLevel level, BlockPos.MutableBlockPos p,
                                      int x, int y, int z, int minY) {
        for (int d = 1; d <= VOID_DEPTH; d++) {
            int yy = y - d;
            if (yy < minY) {
                return false;
            }
            BlockState below = level.getBlockState(p.set(x, yy, z));
            if (below.isAir()) {
                continue;
            }
            if (below.is(AMETHYST_GEODE)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static final int[][] SIX = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private static long packKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
