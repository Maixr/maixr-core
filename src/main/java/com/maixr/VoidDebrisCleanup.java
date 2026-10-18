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

/**
 * 虚空残余清理 —— <b>只清除整块悬在虚空里的</b>岩浆与紫水晶洞，只在「破碎」世界生效。
 *
 * <h2>为什么必须"整块"判定（实测教训）</h2>
 *
 * <p>第一版用"下方 4 格全空气"判悬空，结果浮空晶洞<b>底部</b>下方有晶洞自身方块，
 * 于是底部保留、上部删掉 —— 玩家看到"<b>一半有一半无、还缺个角</b>"。
 * 判据必须落在<b>整块</b>层面：只要这块东西<b>整列下方都撑着虚空</b>，就全删。</p>
 *
 * <h2>判据</h2>
 *
 * <ul>
 *   <li><b>岩浆</b>：正下方一格是空气 → 悬空岩浆带（正常岩浆湖下面是石头）。</li>
 *   <li><b>紫水晶族</b>：沿同列向下最多 {@value #VOID_DEPTH} 格，
 *       <b>找不到任何"非空气且非晶洞"的方块</b> → 整块悬空 → 删。</li>
 * </ul>
 *
 * <p>同列向下探测不会跨区块（x/z 不变），所以仍可安全地放在生成阶段。</p>
 */
public final class VoidDebrisCleanup {

    /** 判定晶洞悬空时向下探测的最大格数。 */
    public static final int VOID_DEPTH = 96;

    private static final TagKey<Block> AMETHYST_GEODE = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("maixr", "void_geode_blocks"));

    private VoidDebrisCleanup() {
    }

    /** 在 dressing 链尾调用（仅破碎世界主世界）。 */
    public static void run(WorldGenLevel level, ChunkAccess chunk) {
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
                        BlockState st = sec.getBlockState(lx, ly, lz);
                        boolean lava = st.is(Blocks.LAVA);
                        boolean geode = st.is(AMETHYST_GEODE);
                        if (!lava && !geode) {
                            continue;
                        }
                        int y = baseY + ly;
                        if (!isFloating(level, p, minX + lx, y, minZ + lz, lava)) {
                            continue;
                        }
                        level.setBlock(p.set(minX + lx, y, minZ + lz), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    /**
     * 是否"整列悬空"：向下找不到任何支撑（非空气、非晶洞族）。
     *
     * <p>岩浆只看 1 格；晶洞看 {@value #VOID_DEPTH} 格 —— 这样同一坨浮空晶洞的
     * <b>每一块</b>都会得到"悬空"结论，被整体删除，不会剩半坨。</p>
     */
    private static boolean isFloating(WorldGenLevel level, BlockPos.MutableBlockPos p,
                                      int x, int y, int z, boolean lava) {
        int depth = lava ? 1 : VOID_DEPTH;
        int minY = level.getMinBuildHeight();
        for (int d = 1; d <= depth; d++) {
            int yy = y - d;
            if (yy < minY) {
                return true;   // 到世界底了，下面什么都没有
            }
            BlockState below = level.getBlockState(p.set(x, yy, z));
            if (below.isAir()) {
                continue;
            }
            if (below.is(AMETHYST_GEODE)) {
                continue;      // 同族方块不算支撑（否则底部会被当成"有支撑"保留）
            }
            return false;      // 有真正的支撑 → 埋在地形里，保留
        }
        return true;
    }
}
