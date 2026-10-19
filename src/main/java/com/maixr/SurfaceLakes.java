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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.Optional;

/**
 * 碎片伴生岛 —— 世界生成阶段、单区块内一次放好（不读邻区块）。
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

    /** 伴生岛的高度上限：y ≤ 100（玩家反馈高于此玩家登不上）。 */
    private static final int MAX_SURFACE_Y = 100;
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
        int minY = level.getMinBuildHeight();
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
        int need = (nbt != null) ? 12 : R * 2 + 2;

        for (int oz = 1; oz + need < 16; oz++) {
            for (int ox = 1; ox + need < 16; ox++) {
                int originX = minX + ox;
                int originZ = minZ + oz;
                boolean clear = true;
                for (int ax = -1; ax <= need && clear; ax++) {
                    for (int az = -1; az <= need && clear; az++) {
                        if (chunk.getHeight(Heightmap.Types.WORLD_SURFACE, originX + ax, originZ + az) > minY + 1) {
                            clear = false;
                        }
                    }
                }
                if (!clear) {
                    continue;
                }
                int baseY = -1;
                for (int az = 1; az < need - 1 && baseY < 0; az++) {
                    int h = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, originX - 2, originZ + az);
                    if (h > minY + 1 && h <= MAX_SURFACE_Y) {
                        baseY = h;
                    }
                }
                if (baseY < 0) {
                    continue;
                }
                if (nbt != null) {
                    placeNbt(level, server, nbt, originX, baseY, originZ, need);
                } else if (variant < 89) {
                    buildDesert(level, originX + need / 2, baseY, originZ + need / 2, rnd);
                } else {
                    buildAquarium(level, originX + need / 2, baseY, originZ + need / 2, rnd);
                }
                return;   // 每区块最多一座
            }
        }
    }

    private static void placeNbt(WorldGenLevel level, ServerLevel server, String name,
                                 int x, int y, int z, int need) {
        StructureTemplateManager manager = server.getStructureManager();
        Optional<StructureTemplate> opt = manager.get(ResourceLocation.fromNamespaceAndPath("maixr", name));
        if (opt.isEmpty()) {
            return;
        }
        StructureTemplate template = opt.get();
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
