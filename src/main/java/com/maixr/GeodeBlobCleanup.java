package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * 虚空晶洞清理（v2，<b>整坨判定</b>）—— 只删"与任何地形都没有接触"的紫水晶洞。
 *
 * <h2>为什么按"坨"而不是按"块"</h2>
 *
 * <p>按块判"下方有没有支撑"会把一坨浮空晶洞<b>切成两半</b>：底部下方是晶洞自身，
 * 被当成"有支撑"保留，上半部分删掉 —— 玩家看到的"一半有一半无、缺个角"。
 * 晶洞是一个整体，判定必须在<b>整体</b>层面做。</p>
 *
 * <h2>判据</h2>
 *
 * <p>把相邻的晶洞族方块 BFS 连成一坨；若这一坨的<b>任何一个方块</b>都与
 * "非空气且非晶洞"的方块相邻，说明它嵌在地形里 → <b>整坨保留</b>；
 * 若四周全是空气 → 悬空 → <b>整坨删除</b>。</p>
 *
 * <p>在 {@code ChunkEvent.Load} 调用（区块已完整生成）。若 BFS 途中遇到未加载区块，
 * 本次跳过不删（保守），等那块加载后重来 —— 绝不强制加载。</p>
 */
public final class GeodeBlobCleanup {

    private static final TagKey<Block> GEODE = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("maixr", "void_geode_blocks"));

    /** 单坨上限。1.21 大型晶洞外壳可达上万块 —— 原值 6000 会把"大晶洞"整个跳过，
     *  这正是玩家看到"雷霆大晶洞还在"的直接原因。 */
    private static final int MAX_BLOB = 60000;

    private static final int[][] DIRS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    private GeodeBlobCleanup() {
    }

    public static void run(ServerLevel level, LevelChunk chunk) {
        int minY = level.getMinBuildHeight();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection sec = sections[si];
            if (sec == null || sec.hasOnlyAir()) {
                continue;
            }
            int baseY = minY + (si << 4);
            for (int ly = 0; ly < 16; ly++) {
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        if (!sec.getBlockState(lx, ly, lz).is(GEODE)) {
                            continue;
                        }
                        int x = minX + lx;
                        int y = baseY + ly;
                        int z = minZ + lz;
                        if (level.getBlockState(p.set(x, y, z)).isAir()) {
                            continue;   // 已被上一轮删掉
                        }
                        clearBlobIfFloating(level, p, x, y, z);
                    }
                }
            }
        }
    }

    /** 从 (x,y,z) 出发 BFS 整坨；若判定悬空则整坨删除。 */
    private static void clearBlobIfFloating(ServerLevel level, BlockPos.MutableBlockPos p,
                                            int sx, int sy, int sz) {
        Set<Long> blob = new HashSet<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(new BlockPos(sx, sy, sz));
        seen.add(BlockPos.asLong(sx, sy, sz));
        boolean touchesTerrain = false;

        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            if (blob.size() >= MAX_BLOB) {
                return;   // 异常巨大，保守不动
            }
            blob.add(cur.asLong());
            for (int[] d : DIRS) {
                int nx = cur.getX() + d[0];
                int ny = cur.getY() + d[1];
                int nz = cur.getZ() + d[2];
                if (ny < level.getMinBuildHeight() || ny >= level.getMaxBuildHeight()) {
                    continue;
                }
                long key = BlockPos.asLong(nx, ny, nz);
                if (seen.contains(key)) {
                    continue;
                }
                LevelChunk nc = level.getChunkSource().getChunkNow(nx >> 4, nz >> 4);
                if (nc == null) {
                    // 邻区块未加载：不能判断该侧有没有接触地形 → 记一笔并放弃本次删除
                    return;
                }
                BlockState st = level.getBlockState(p.set(nx, ny, nz));
                if (st.is(GEODE)) {
                    seen.add(key);
                    queue.add(new BlockPos(nx, ny, nz));
                } else if (!st.isAir()) {
                    touchesTerrain = true;   // 与真实地形/其它方块接触 → 不是孤儿
                }
            }
        }

        if (touchesTerrain) {
            return;   // 嵌在地形里 → 保留
        }
        for (long key : blob) {
            level.setBlock(BlockPos.of(key), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        }
    }
}
