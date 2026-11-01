package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.Optional;

/**
 * 碎片伴生岛 —— 世界生成阶段、单区块内一次放好（不读邻区块）。
 *
 * <p><b>v2：全部搬到高空</b>。旧版贴碎片地表生成（岛面 ≤ y100），玩家在地面
 * 走就能撞见，没有"悬空岛"的观感。现在基准高度固定在
 * {@link #ISLAND_MIN_Y}(250) ~ {@link #ISLAND_MAX_Y}(300) 区间内按区块种子抽取，
 * 岛屿完全悬空。</p>
 *
 * <p>放置条件也从"该区域没有地形"改为<b>高空净空</b>：岛屿所占方块盒内必须
 * 全为空气——这样天然避开已落地的天空结构（`createStructures` 阶段）与
 * 天隙矿脉（FEATURES 阶段）。净空判定只读本区块。</p>
 *
 * <p><b>频率</b>：每 {@link #ISLAND_CHUNK_DENOMINATOR}(4) 个区块约 1 座
 * （v2 第一版无限量，用户裁定降到原来的 1/4）。</p>
 *
 * <table border="1">
 *   <tr><th>概率</th><th>类型</th><th>内容</th></tr>
 *   <tr><td>40%</td><td>甘蔗水岛</td><td>{@code maixr:skyblock_water}（玩家提供）</td></tr>
 *   <tr><td>15%</td><td>梦晶岛</td><td>{@code maixr:skyblock_dream}（玩家提供）</td></tr>
 *   <tr><td>12%</td><td>末地小岛</td><td>{@code maixr:skyblock_end}（玩家提供）</td></tr>
 *   <tr><td>11%</td><td>下界小岛</td><td>{@code maixr:skyblock_nether}（玩家提供）</td></tr>
 *   <tr><td>11%</td><td>沙漠小岛</td><td>砂岩 + 仙人掌（程序生成）</td></tr>
 *   <tr><td>11%</td><td>水族馆</td><td><b>球形玻璃罩</b>，内部充满水 + 海带 + 多种珊瑚</td></tr>
 * </table>
 */
public final class SurfaceLakes {

    /** 伴生岛基准高度的下限（v2：全部悬空，不再贴地表）。 */
    public static final int ISLAND_MIN_Y = 250;
    /** 伴生岛基准高度的上限。世界顶部 y=319，留 19 格余量给岛体本身。 */
    public static final int ISLAND_MAX_Y = 300;
    /**
     * 频率门控分母：每 N 个区块约 1 座。
     *
     * <p>v2 第一版（无限量）实际密度约每区块 1 座，偏高；用户裁定降到原来的
     * 1/4，故取 4。门控用区块坐标的确定性哈希，与类型分派
     * （{@code floorMod(x*7+z*11, 100)}）互相独立——被跳过的区块不改变类型归属。</p>
     */
    public static final int ISLAND_CHUNK_DENOMINATOR = 4;
    /**
     * 程序生成岛（沙漠/水族馆）的净空盒高度。
     * 实际最高点：沙漠沙丘 y+3；水族馆球心 y+2r(r=5)=y+10 + 海带最高 y+12。
     * 取 14 = 再多留 2 格，避免"顶上一格被占"的边界漏判。
     */
    private static final int PROGRAM_ISLAND_HEIGHT = 14;
    /**
     * 程序岛向下的净空深度：沙漠岛的砂岩底盘写到 {@code y-3}
     * （逐层收窄），这部分同样要检查净空，否则可能盖掉下方的矿石矿囊。
     * NBT 模板从 y 起向上，不需要这一段。
     */
    private static final int PROGRAM_ISLAND_DEPTH = 4;
    /** 程序生成岛（沙漠/水族馆）的半径。 */
    private static final int R = 4;

    private SurfaceLakes() {
    }

    public static void run(WorldGenLevel level, ChunkAccess chunk) {
        ServerLevel server = level.getLevel() instanceof ServerLevel sl ? sl : null;
        if (server == null) {
            return;
        }
        ChunkPos cp = chunk.getPos();
        int minX = cp.getMinBlockX();
        int minZ = cp.getMinBlockZ();

        // ★ 频率门控：每 ISLAND_CHUNK_DENOMINATOR 个区块约 1 座。
        //   用区块坐标的确定性哈希（与下面类型分派的判据互相独立）——
        //   被跳过的区块不改变自己的类型归属，同种子同坐标结果仍一致。
        long gateHash = cp.x * 0x9E3779B97F4A7C15L ^ cp.z * 0xC2B2AE3D27D4EB4FL ^ 0x5EEDL;
        gateHash ^= gateHash >>> 33;
        gateHash *= 0xFF51AFD7ED558CCDL;
        gateHash ^= gateHash >>> 33;
        if (Math.floorMod(gateHash, ISLAND_CHUNK_DENOMINATOR) != 0) {
            return;
        }

        RandomSource rnd = RandomSource.create(
                level.getSeed() * 31L + cp.x * 341873128712L + cp.z * 132897987541L + 0x5EEDL);

        int variant = Math.floorMod(cp.x * 7L + cp.z * 11L, 100);
        String nbt = null;
        if (variant < 40) {
            nbt = "skyblock_water";
        } else if (variant < 55) {
            nbt = "skyblock_dream";
        } else if (variant < 67) {
            nbt = "skyblock_end";
        } else if (variant < 78) {
            nbt = "skyblock_nether";
        }
        // NBT 岛用 12×12 候选位（模板最大 10×10，留边）；程序岛要覆盖水族馆
        // 球壳的完整水平跨度：球心 cx±(R+1) = 2R+3 格宽（10 格装不下）。
        int need = (nbt != null) ? 12 : R * 2 + 3;

        // ★ v2：基准高度不再读邻列地表 —— 250 以上没有地形，高度图恒为顶部值。
        //   按区块种子在 [ISLAND_MIN_Y, ISLAND_MAX_Y] 内抽一个基准。
        int baseY = ISLAND_MIN_Y + rnd.nextInt(ISLAND_MAX_Y - ISLAND_MIN_Y + 1);

        // NBT 岛的净空盒高度取模板真实高度；程序岛用固定常量。
        int clearH = PROGRAM_ISLAND_HEIGHT;
        int clearDepth = PROGRAM_ISLAND_DEPTH;      // 程序岛底盘向下延伸的格数
        StructureTemplate template = null;
        if (nbt != null) {
            StructureTemplateManager manager = server.getStructureManager();
            Optional<StructureTemplate> opt = manager.get(ResourceLocation.fromNamespaceAndPath("maixr", nbt));
            if (opt.isEmpty()) {
                return;                     // 模板缺失：静默跳过（数据包异常不炸生成）
            }
            template = opt.get();
            clearH = template.getSize().getY();
            clearDepth = 0;                 // NBT 模板从 y 起向上，不向下占格
        }
        // ★ 顶部越界保护：岛体最高点不能超出世界可建高度
        if (baseY + clearH >= level.getMaxBuildHeight()) {
            baseY = level.getMaxBuildHeight() - clearH - 1;
            if (baseY < ISLAND_MIN_Y) {
                return;
            }
        }

        for (int oz = 1; oz + need < 16; oz++) {
            for (int ox = 1; ox + need < 16; ox++) {
                int originX = minX + ox;
                int originZ = minZ + oz;
                // ★ v2：净空 = 岛屿实际占据的方块盒全为空气（避开天空结构/矿脉/地形）
                if (!isClearVolume(chunk, originX, baseY - clearDepth, originZ,
                        need, need, clearDepth + clearH)) {
                    continue;
                }
                if (template != null) {
                    placeNbt(level, template, originX, baseY, originZ, need);
                } else if (variant < 89) {
                    buildDesert(level, originX + need / 2, baseY, originZ + need / 2, rnd);
                } else {
                    buildAquarium(level, originX + need / 2, baseY, originZ + need / 2, rnd);
                }
                return;   // 每区块最多一座
            }
        }
    }

    /**
     * 高空净空判定：水平 {@code [x, x+w) × [z, z+d)}、竖直 {@code [y, y+h)}
     * 的方块盒内必须全为空气。
     *
     * <p>只读本区块（调用方保证 {@code x/z} 落在区块内、{@code x+w ≤ 区块右边界}），
     * 因此不会触发 {@code WorldGenRegion} 的越界读异常。</p>
     *
     * <p>v2 换代理由：旧判定用 {@code getHeight(WORLD_SURFACE) > minY + 1} 问
     * "这些列有没有地形"，在 y≥250 恒为真（上方永远没有地形），所以必须换成
     * 真正的方块盒检查——它同时兜住了天空结构与天隙矿脉。</p>
     */
    private static boolean isClearVolume(ChunkAccess chunk, int x, int y, int z, int w, int d, int h) {
        for (int lx = x; lx < x + w; lx++) {
            for (int lz = z; lz < z + d; lz++) {
                for (int ly = y; ly < y + h; ly++) {
                    if (!chunk.getBlockState(new BlockPos(lx, ly, lz)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void placeNbt(WorldGenLevel level, StructureTemplate template,
                                 int x, int y, int z, int need) {
        Vec3i size = template.getSize();
        BlockPos origin = new BlockPos(x + need / 2 - size.getX() / 2, y, z + need / 2 - size.getZ() / 2);
        template.placeInWorld(level, origin, origin, new StructurePlaceSettings(), level.getRandom(), 2);
    }

    /**
     * 沙漠小岛：<b>加厚成沙丘</b>（原来只有 2 层，太扁），并大幅增加仙人掌。
     *
     * <p>结构：砂岩底盘逐层收窄 4 层 → 沙层 2 层 → 中心小丘抬高 1~2 格；
     * 仙人掌 7~10 根、高 2~4 格，另加枯木点缀。</p>
     */
    private static void buildDesert(WorldGenLevel level, int cx, int y, int cz, RandomSource rnd) {
        // 砂岩底盘（向下逐层收窄，做出厚度）
        disc(level, cx, y, cz, R, Blocks.SANDSTONE.defaultBlockState());
        disc(level, cx, y - 1, cz, R, Blocks.SANDSTONE.defaultBlockState());
        disc(level, cx, y - 2, cz, R - 1, Blocks.SANDSTONE.defaultBlockState());
        disc(level, cx, y - 3, cz, R - 2, Blocks.SANDSTONE.defaultBlockState());
        // 沙层（顶面），中心抬高成沙丘
        disc(level, cx, y + 1, cz, R - 1, Blocks.SAND.defaultBlockState());
        int duneR = Math.max(1, R - 3);
        disc(level, cx, y + 2, cz, duneR, Blocks.SAND.defaultBlockState());
        if (rnd.nextBoolean()) {
            disc(level, cx, y + 3, cz, Math.max(1, duneR - 1), Blocks.SAND.defaultBlockState());
        }
        // ★ 仙人掌加量：7~10 根，高 2~4，避开中心沙丘
        int cactus = 7 + rnd.nextInt(4);
        java.util.Set<Long> used = new java.util.HashSet<>();
        for (int i = 0; i < cactus * 3 && used.size() < cactus; i++) {
            int dx = rnd.nextInt(R * 2 + 1) - R;
            int dz = rnd.nextInt(R * 2 + 1) - R;
            if (dx * dx + dz * dz > R * R || dx * dx + dz * dz < 2) {
                continue;
            }
            long key = ((long) dx << 32) ^ (dz & 0xFFFFFFFFL);
            if (!used.add(key)) {
                continue;
            }
            int top = y + 1;
            // 若该列在沙丘上，仙人掌从沙丘顶起
            while (!level.getBlockState(new BlockPos(cx + dx, top, cz + dz)).isAir()) {
                top++;
                if (top > y + 5) {
                    break;
                }
            }
            int h = 2 + rnd.nextInt(3);
            for (int k = 0; k < h; k++) {
                level.setBlock(new BlockPos(cx + dx, top + k, cz + dz),
                        Blocks.CACTUS.defaultBlockState(), 2);
            }
        }
        // 枯木点缀
        for (int i = 0; i < 3; i++) {
            int dx = rnd.nextInt(R * 2 + 1) - R;
            int dz = rnd.nextInt(R * 2 + 1) - R;
            if (dx * dx + dz * dz > R * R) {
                continue;
            }
            level.setBlock(new BlockPos(cx + dx, y + 2, cz + dz),
                    Blocks.DEAD_BUSH.defaultBlockState(), 2);
        }
    }

    /** ★ 球形水族馆：玻璃球壳 + 内部全充满水 + 海带柱 + 4 种珊瑚。 */
    private static void buildAquarium(WorldGenLevel level, int cx, int y, int cz, RandomSource rnd) {
        int r = R + 1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > r + 0.15D) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(cx + dx, y + dy + r, cz + dz);
                    if (d >= r - 0.7D) {
                        level.setBlock(pos, Blocks.GLASS.defaultBlockState(), 2);   // 球壳
                    } else {
                        level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2);   // 内部充满水
                    }
                }
            }
        }
        // ★ 底面改为青金石块（原来铺沙，但沙在水里悬空会掉落，堆不住）
        for (int dx = -r + 1; dx <= r - 1; dx++) {
            for (int dz = -r + 1; dz <= r - 1; dz++) {
                if (dx * dx + dz * dz <= (r - 1) * (r - 1)) {
                    level.setBlock(new BlockPos(cx + dx, y + 1, cz + dz),
                            Blocks.LAPIS_BLOCK.defaultBlockState(), 2);
                }
            }
        }
        // 海带柱（很多）
        for (int i = 0; i < 8; i++) {
            int dx = rnd.nextInt(2 * r - 3) - (r - 2);
            int dz = rnd.nextInt(2 * r - 3) - (r - 2);
            if (dx * dx + dz * dz > (r - 2) * (r - 2)) {
                continue;
            }
            int h = 2 + rnd.nextInt(r);
            for (int k = 0; k < h; k++) {
                level.setBlock(new BlockPos(cx + dx, y + 2 + k, cz + dz),
                        Blocks.KELP_PLANT.defaultBlockState(), 2);
            }
            level.setBlock(new BlockPos(cx + dx, y + 2 + h, cz + dz),
                    Blocks.KELP.defaultBlockState(), 2);
        }
        // 多种珊瑚（随机分布在底部）
        BlockState[] corals = {
                Blocks.TUBE_CORAL_BLOCK.defaultBlockState(),
                Blocks.BRAIN_CORAL_BLOCK.defaultBlockState(),
                Blocks.BUBBLE_CORAL_BLOCK.defaultBlockState(),
                Blocks.FIRE_CORAL_BLOCK.defaultBlockState(),
                Blocks.HORN_CORAL_BLOCK.defaultBlockState(),
                Blocks.TUBE_CORAL.defaultBlockState(),
                Blocks.BRAIN_CORAL.defaultBlockState()
        };
        for (int i = 0; i < 12; i++) {
            int dx = rnd.nextInt(2 * r - 3) - (r - 2);
            int dz = rnd.nextInt(2 * r - 3) - (r - 2);
            if (dx * dx + dz * dz > (r - 2) * (r - 2)) {
                continue;
            }
            level.setBlock(new BlockPos(cx + dx, y + 2, cz + dz), corals[rnd.nextInt(corals.length)], 2);
        }
    }

    private static void disc(WorldGenLevel level, int cx, int y, int cz, int r, BlockState state) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= r * r) {
                    level.setBlock(new BlockPos(cx + dx, y, cz + dz), state, 2);
                }
            }
        }
    }
}
