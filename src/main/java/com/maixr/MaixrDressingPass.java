package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.Set;

/**
 * 「破碎」主世界的<b>确定性材质 dressing pass</b>（design D4）——
 * 在 {@code applyBiomeDecoration} 链尾（装饰完成后）逐列置换表层方块：
 *
 * <ul>
 *   <li><b>震中侵蚀</b>：震中加压半径内的存活表层按强度置换为幽匿块，
 *       贴地随机铺幽匿脉络 —— 替换密度随震中距离衰减（spec「侵蚀材质语言」）。</li>
 *   <li><b>碎屑带结晶</b>：碎屑带（带进度 ≥ {@link #CRYSTAL_BAND}）的存活浮板
 *       表层按概率结晶出天空石（与空域浮岩同款 {@code ae2:sky_stone_block}）。</li>
 * </ul>
 *
 * <h2>★★★ 潜声装置禁令（spec 硬要求）</h2>
 *
 * <p>本 pass 放置的方块<b>只允许</b>来自 {@link #ALLOWED}（幽匿块/幽匿脉络/天空石）——
 * {@link #FORBIDDEN}（潜声传感器/尖啸体/催化剂）在类加载时断言绝不出现在允许集合里，
 * 世界生成不会放置任何潜声装置。</p>
 *
 * <h2>与碎裂场的一致性</h2>
 *
 * <p>mask 采样用的 {@link FractureDensityFunction} 由区块所属维度当前
 * {@link RandomState} 的<b>同一个带种子 NormalNoise</b> 构造
 * （{@code RandomState.getOrCreateNoise(maixr:crack)} 有缓存 —— 与密度管线里的
 * 是同一实例），所以幽匿侵蚀与天空石结晶永远长在碎裂场实际算出的位置上。</p>
 */
public final class MaixrDressingPass {

    // ==========================================================================
    //  ★ 常量（文档 _docs/破碎世界.md 必须与这里一致）
    // ==========================================================================

    /** 震中侵蚀：置换概率 = 加压强度 × 此系数（强度随震中距离衰减）。 */
    public static final double SCULK_DENSITY = 0.35D;
    /** 震中侵蚀向下置换的最大层数。 */
    public static final int SCULK_DEPTH = 2;
    /** 碎屑带天空石结晶的起始带进度。 */
    public static final double CRYSTAL_BAND = 2.5D;
    /** 碎屑带浮板表层结晶天空石的概率。 */
    public static final double SKY_STONE_CHANCE = 0.08D;

    /** 裂缝噪声（与 broken_overworld.json 里 maixr:fracture 的 crack_noise 同 key）。 */
    private static final ResourceKey<NormalNoise.NoiseParameters> CRACK_NOISE_KEY =
            ResourceKey.create(Registries.NOISE,
                    ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "crack"));

    /** 天空石（与空域浮岩外壳同款）。 */
    private static final ResourceLocation SKY_STONE_ID =
            ResourceLocation.fromNamespaceAndPath("ae2", "sky_stone_block");

    /**
     * ★★★ 本 pass 允许放置的方块全集。新加方块必须确认不是潜声装置
     * （FORBIDDEN 断言会在类加载时拦住）。
     */
    private static final Set<Block> ALLOWED = Set.of(
            Blocks.SCULK,           // 幽匿块（震中侵蚀）
            Blocks.SCULK_VEIN,      // 幽匿脉络（贴地）
            BuiltInRegistries.BLOCK.get(SKY_STONE_ID));   // 天空石（碎屑带结晶）

    /**
     * ★★★ 潜声装置禁令：这些方块绝不允许出现在 {@link #ALLOWED}。
     */
    private static final Set<Block> FORBIDDEN = Set.of(
            Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER, Blocks.SCULK_CATALYST);

    static {
        for (Block b : ALLOWED) {
            if (b == null) {
                throw new IllegalStateException("[破碎dressing] 允许集合里存在未注册方块（AE2 缺失？）");
            }
            if (FORBIDDEN.contains(b)) {
                throw new IllegalStateException("[破碎dressing] 潜声装置禁止由世界生成放置: " + b);
            }
        }
    }

    private MaixrDressingPass() {}

    /**
     * 对一个已装饰完成的区块跑 dressing。
     * 在 {@code BrokenOverworldChunkGenerator.applyBiomeDecoration} 链尾调用。
     */
    public static void run(WorldGenLevel level, ChunkAccess chunk) {
        // ★ 虚空残余清理：只删悬在虚空里的岩浆/紫水晶洞（本区块内，无跨区块读取）
        VoidDebrisCleanup.run(level, chunk);
        // 大碎片内的小湖泊（必带甘蔗）—— 碎裂场把地表水挖掉了，这里补取水点
        SurfaceLakes.run(level, chunk);
        // ★ 裂缝桥：纯本区块高度图、不排队、不读邻区块 —— 这是唯一不依赖加载时机的桥
        CrackBridges.run(level, chunk);
        ServerLevel serverLevel = (ServerLevel) level.getLevel();
        FractureDensityFunction fracture = buildFracture(serverLevel);
        if (fracture == null) {
            return;
        }
        long seed = level.getSeed();
        BlockPos min = chunk.getPos().getWorldPosition();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = min.getX() + lx;
                int z = min.getZ() + lz;
                // 撕裂深度（0=完好，-TEAR_SCALE=完全撕裂）；全撕裂列无地表可置换
                double mask = fracture.compute(new DensityFunction.SinglePointContext(x, 0, z));
                if (mask <= -0.95D * FractureDensityFunction.TEAR_SCALE) {
                    continue;
                }
                double band = FractureDensityFunction.bandProgress(x, z, 1.0D);
                // 每列一个确定性随机（同种子同坐标永远同结果）
                RandomSource rnd = RandomSource.create(
                        seed ^ (x * 0x9E3779B185EBCA87L) ^ (z * 0xC2B2AE3D27D4EB4FL));

                // ① 震中侵蚀：加压强度 → 概率置换表层（覆盖草皮，design D4）
                double press = fracture.epicenterPress(x, z, 1.0D);
                if (press > 0.0D && rnd.nextDouble() < press * SCULK_DENSITY) {
                    int depth = 1 + (rnd.nextDouble() < press ? 1 : 0);
                    if (replaceSurface(level, x, z, Math.min(depth, SCULK_DEPTH), Blocks.SCULK,
                            rnd, true)) {
                        continue;   // 该列已置换，不再结晶
                    }
                }

                // ② 碎屑带天空石结晶
                if (band >= CRYSTAL_BAND && rnd.nextDouble() < SKY_STONE_CHANCE) {
                    Block skyStone = BuiltInRegistries.BLOCK.get(SKY_STONE_ID);
                    if (skyStone != null && skyStone != Blocks.AIR) {
                        replaceSurface(level, x, z, 1, skyStone, rnd, false);
                    }
                }
            }
        }
    }

    /**
     * 从列顶往下找第一个可置换的地形方块，替换 {@code depth} 层为 {@code block}，
     * 可选贴地铺一条幽匿脉络。返回是否置换了至少一块。
     *
     * <p>跳过树叶/原木（不给树顶铺幽匿）；只置换"裸露地形表层"
     * （草方块/土/石/沙/砂砾等 —— 覆盖草皮，design D4）。</p>
     */
    private static boolean replaceSurface(WorldGenLevel level, int x, int z, int depth, Block block,
                                          RandomSource rnd, boolean vein) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        int minY = level.getMinBuildHeight();
        int replaced = 0;
        boolean veinPlaced = false;
        for (int y = top; y >= minY && replaced < depth; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            var state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
                return false;   // 树冠列不动
            }
            if (ALLOWED.contains(state.getBlock())) {
                return true;    // 已经置换过
            }
            boolean isTerrainSurface = state.is(BlockTags.DIRT)
                    || state.is(BlockTags.STONE_ORE_REPLACEABLES)
                    || state.is(Blocks.SAND) || state.is(Blocks.GRAVEL)
                    || state.is(Blocks.SANDSTONE) || state.is(Blocks.TERRACOTTA);
            if (!isTerrainSurface) {
                continue;       // 结构方块/花/雪等不动
            }
            level.setBlock(pos, block.defaultBlockState(), 3);
            replaced++;
            if (vein && !veinPlaced && rnd.nextDouble() < 0.5D) {
                BlockPos above = pos.above();
                if (level.getBlockState(above).isAir()) {
                    level.setBlock(above, Blocks.SCULK_VEIN.defaultBlockState(), 3);
                    veinPlaced = true;
                }
            }
        }
        return replaced > 0;
    }

    /**
     * 按 {@link RandomState} 身份复用的碎裂场实例（2026-10-25 优化变更 design D3）：
     * 实例不可变（NoiseHolder/参数均 final），同维度共享安全；每区块新建会让
     * {@link FractureDensityFunction} 的 ThreadLocal 列缓存每区块冷启动（256 列
     * 重复求值）。volatile 保证跨 worker 线程安全发布；竞态最坏 = 重复构造一次，
     * 值恒等无正确性影响。
     */
    private static volatile FractureDensityFunction cachedFracture;
    private static volatile RandomState cachedRandomState;

    /**
     * 用区块维度的当前 RandomState 构造与密度管线<b>同噪声实例</b>的碎裂场
     * （见类注释「与碎裂场的一致性」）；同 RandomState 直接复用缓存实例。
     */
    private static FractureDensityFunction buildFracture(ServerLevel serverLevel) {
        try {
            RandomState randomState = serverLevel.getChunkSource().randomState();
            if (cachedFracture != null && cachedRandomState == randomState) {
                return cachedFracture;
            }
            NormalNoise crackNoise = randomState.getOrCreateNoise(CRACK_NOISE_KEY);
            Holder<NormalNoise.NoiseParameters> holder = serverLevel.registryAccess()
                    .registryOrThrow(Registries.NOISE).getHolderOrThrow(CRACK_NOISE_KEY);
            FractureDensityFunction fracture = new FractureDensityFunction(
                    new DensityFunction.NoiseHolder(holder, crackNoise),
                    new PlateFieldDensityFunction(1.0D), 1.0D);
            cachedRandomState = randomState;
            cachedFracture = fracture;
            return fracture;
        } catch (Exception e) {
            MaixrMod.LOGGER.error("[破碎dressing] 构造碎裂场失败（跳过 dressing）: {}", e.toString());
            return null;
        }
    }
}
