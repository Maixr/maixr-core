package com.maixr;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * 【空域浮岩】—— 天上飘着的<b>陨石团簇</b>与<b>粗矿块</b>。
 *
 * <h3>为什么要在模组里做，而不是改数据包</h3>
 * AE2 的陨石落点是它自己代码里算的：{@code MeteoriteStructure.findGenerationPoint()} 调用
 * {@code Structure.onTopOfChunkCenter(ctx, Heightmap.Types.OCEAN_FLOOR_WG, ...)}，
 * 也就是说 Y 来自<b>区块生成器的高度图</b>。本包的空岛/破碎生成器在虚空区块上返回的是顶高，
 * 于是陨石全部落在世界天花板（Y≈319）—— 数据包只能改结构密度，改不了这个高度。
 *
 * <h3>这个 Feature 做什么</h3>
 * <p>在空域（默认 Y=110~170，见 placed_feature）随机生成<b>一团浮岩</b>。
 * 每团按 {@link #METEOR_SHARE} 的概率二选一：</p>
 *
 * <table border="1">
 *   <tr><th>种类</th><th>外形</th><th>方块</th></tr>
 *   <tr><td><b>陨石团簇</b></td>
 *       <td>椭球，半径 2~4 × 1~3 × 2~4</td>
 *       <td>外壳 {@code ae2:sky_stone_block}（可粉碎成陨石粉），
 *           内核 {@code ae2:smooth_sky_stone_block}</td></tr>
 *   <tr><td><b>粗矿块</b></td>
 *       <td>椭球，半径 1~3（略小更像矿簇）</td>
 *       <td>从 {@link #RAW_ORES 候选清单}里随机一种<b>粗矿块</b>通体填充</td></tr>
 * </table>
 *
 * <p>玩家搭个小平台或者飞过去就能采 —— 陨石粉不必再爬 319 层，
 * 金属矿也能在天上直接挖到。</p>
 *
 * <h3>安全设计</h3>
 * <ul>
 *   <li><b>只在"脚下真的是空岛/虚空"的世界生成</b>（见 {@link #isMeteorAllowed}）：
 *       破碎 / 空岛世界类型的主世界，以及 {@code maixr:overworld_broken} 维度。
 *       普通主世界 y=110~170 绝大部分是空气，浮岩会显得很怪；其它维度一律不放。</li>
 *   <li>没装的模组的方块<b>自动跳过</b> —— 候选清单在首次使用时按注册表过滤一次，
 *       查不到的（比如没装 Mekanism）直接不进池子，不会放空气。</li>
 *   <li>没装 AE2 时陨石团簇自动跳过，只剩粗矿块。</li>
 * </ul>
 */
public class SkyStoneClusterFeature extends Feature<NoneFeatureConfiguration> {
    private static final ResourceLocation SKY_STONE = ResourceLocation.fromNamespaceAndPath("ae2", "sky_stone_block");
    private static final ResourceLocation SMOOTH_SKY_STONE = ResourceLocation.fromNamespaceAndPath("ae2", "smooth_sky_stone_block");

    /**
     * <b>陨石团簇</b>占的比例 —— 其余都变成粗矿块。
     *
     * <p>0.35 = 35% 陨石、65% 粗矿块。想调手感改这一个数就行。</p>
     *
     * <p>★ 之所以调低：早期版本 100% 都是灰色陨石，天上飘着太单调
     * （玩家反馈"陨石块太多了"）。现在混进各种颜色的粗矿块，视觉层次好得多。</p>
     */
    public static final double METEOR_SHARE = 0.35D;

    /**
     * 「粗矿块」候选池 —— 会按<b>实际装了的模组</b>自动过滤。
     *
     * <p>顺序无意义（每次随机挑）。加新矿种在这里加一行即可。</p>
     */
    private static final ResourceLocation[] RAW_ORE_IDS = {
            // 原版三兄弟
            ResourceLocation.fromNamespaceAndPath("minecraft", "raw_iron_block"),
            ResourceLocation.fromNamespaceAndPath("minecraft", "raw_copper_block"),
            ResourceLocation.fromNamespaceAndPath("minecraft", "raw_gold_block"),
            // 机械动力
            ResourceLocation.fromNamespaceAndPath("create", "raw_zinc_block"),
            // 通用机械
            ResourceLocation.fromNamespaceAndPath("mekanism", "block_raw_osmium"),
            ResourceLocation.fromNamespaceAndPath("mekanism", "block_raw_lead"),
            ResourceLocation.fromNamespaceAndPath("mekanism", "block_raw_tin"),
            ResourceLocation.fromNamespaceAndPath("mekanism", "block_raw_uranium"),
            ResourceLocation.fromNamespaceAndPath("mekanism_extras", "block_raw_naquadah"),
            ResourceLocation.fromNamespaceAndPath("mekmm", "block_raw_silver"),
            // 其它模组
            ResourceLocation.fromNamespaceAndPath("neoecoae", "raw_tungsten_block"),
            ResourceLocation.fromNamespaceAndPath("neoecoae", "raw_aluminum_block"),
            ResourceLocation.fromNamespaceAndPath("pasterdream", "raw_titanium_block"),
            ResourceLocation.fromNamespaceAndPath("anvilcraft", "raw_titanium_block"),
            ResourceLocation.fromNamespaceAndPath("anvilcraft", "raw_tungsten_block"),
            ResourceLocation.fromNamespaceAndPath("enderio_evolution", "crude_steel_block"),
    };

    /*
     * ★ 已移除：justdirethings:raw_ferricore_ore
     *
     * 它不是"地下能挖到的粗矿块"，而是 JDT 自己那套资源体系里的
     * 特殊方块（带专属指南书/工具/盔甲，是 raw_ferricore_ore 方块本身，
     * 不是 raw_xxx 物品的压缩块）。按需求"只要地下有的矿的粗矿块"剔除。
     */

    /** 过滤后的粗矿块池（首次使用时建好，之后就复用）。 */
    private static volatile List<BlockState> orePool = null;

    /** 实际产出统计（给 /maixr 指令或调试看"到底放了多少、比例对不对"）。 */
    private static final java.util.concurrent.atomic.AtomicInteger COUNT_METEOR =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger COUNT_ORE =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 打印实际产出统计。 */
    public static void dumpStats() {
        int m = COUNT_METEOR.get(), o = COUNT_ORE.get();
        // 调试输出（已注释）：
        // MaixrMod.LOGGER.info("[空域浮岩] 已生成 陨石 {} 团 / 粗矿块 {} 团（目标比例 {}/{}）",
        //         m, o, Math.round(METEOR_SHARE * 100), Math.round((1 - METEOR_SHARE) * 100));
    }

    public SkyStoneClusterFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        ResourceKey<Level> dim = dimensionOf(level);
        if (dim == null) return false;
        if (!isMeteorAllowed(level, dim)) return false;

        RandomSource rnd = ctx.random();
        BlockPos origin = ctx.origin();

        // ★ 二选一：陨石团簇 还是 粗矿块
        boolean makeMeteor = rnd.nextDouble() < METEOR_SHARE;

        if (makeMeteor) {
            BlockState shell = stateOf(SKY_STONE);
            if (shell == null) return false;                     // 没装 AE2 → 这团不生成
            BlockState core = stateOf(SMOOTH_SKY_STONE);
            return placeBlob(level, rnd, origin, shell, core);
        }

        // 粗矿块：从池子里随机挑一种
        List<BlockState> pool = orePool();
        if (pool.isEmpty()) return false;                        // 一个矿块模组都没装
        BlockState ore = pool.get(rnd.nextInt(pool.size()));
        return placeOreBlob(level, rnd, origin, ore);
    }

    /**
     * 放一团陨石（外壳 + 内核两色）。
     *
     * <p>椭球半径 2~4 × 1~3 × 2~4，扁一点更像"碎块"。</p>
     */
    private boolean placeBlob(WorldGenLevel level, RandomSource rnd, BlockPos origin,
                              BlockState shell, BlockState core) {
        // ★ 圆润椭球：三个轴接近等长（2~4），避免"扁片"感
        //   旧版 ry 只有 1~3 而 rx/rz 是 2~4 —— 出来是压扁的饼，很难看
        int r = 2 + rnd.nextInt(3);                      // 基准半径 2~4
        int rx = r, rz = r;
        int ry = Math.max(1, r - 1 + rnd.nextInt(2));    // r-1 ~ r，几乎球形
        boolean placed = false;
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dy = -ry; dy <= ry; dy++) {
                for (int dz = -rz; dz <= rz; dz++) {
                    double d = (dx * dx) / (double) (rx * rx)
                            + (dy * dy) / (double) (ry * ry)
                            + (dz * dz) / (double) (rz * rz);
                    // ★ 阈值 1.22：把椭球边缘"补齐"，表面更圆润。
                    //   数学椭球（1.0）体素化后棱角很明显；
                    //   实测 1.22 在 r=3 时 147 块（理想球 113），形状是漂亮的球，
                    //   而 1.35 会涨到 179 块、退化成"方块团"。
                    if (d > 1.22D) continue;
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (!level.isEmptyBlock(p)) continue;      // 别覆盖已有地形
                    BlockState st = (d < 0.35D && core != null) ? core : shell;
                    level.setBlock(p, st, 2);
                    placed = true;
                }
            }
        }
        if (placed) COUNT_METEOR.incrementAndGet();
        if (placed && LOGGED.getAndIncrement() < 12) {
            // 调试输出（已注释）：
            // MaixrMod.LOGGER.info("[空域浮岩] 陨石团簇 @ ({}, {}, {}) —— 外壳 {}（可粉碎成陨石粉）",
            //         origin.getX(), origin.getY(), origin.getZ(), SKY_STONE);
        }
        return placed;
    }

    /**
     * 放一团粗矿块。
     *
     * <p>比陨石<b>小一圈</b>（半径 1~3），而且做成<b>不规则的团块</b> ——
     * 用"椭球 + 边缘随机剔除"模拟矿石团簇的锯齿感，比规则球体自然。</p>
     */
    private boolean placeOreBlob(WorldGenLevel level, RandomSource rnd, BlockPos origin, BlockState ore) {
        // ★ 圆润球体：三轴等长（2~3），阈值放宽补齐体素棱角
        //   旧版用了"边缘随机剔除"做锯齿感 —— 玩家反馈"形状太难看"，去掉。
        int r = 2 + rnd.nextInt(2);                      // 半径 2~3
        int rx = r, ry = r, rz = r;
        boolean placed = false;
        for (int dx = -rx; dx <= rx; dx++) {
            for (int dy = -ry; dy <= ry; dy++) {
                for (int dz = -rz; dz <= rz; dz++) {
                    double d = (dx * dx) / (double) (rx * rx)
                            + (dy * dy) / (double) (ry * ry)
                            + (dz * dz) / (double) (rz * rz);
                    // ★ 阈值 1.25：半径 2 时体素化最严重（严格 1.0 只有 33 块，
                    //   是个带缺口的立方块）。1.25 给 57 块，是饱满的小球。
                    //   再往上（1.35+）就变成实心方块团了。
                    if (d > 1.25D) continue;
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (!level.isEmptyBlock(p)) continue;
                    level.setBlock(p, ore, 2);
                    placed = true;
                }
            }
        }
        if (placed) COUNT_ORE.incrementAndGet();
        if (placed && LOGGED_ORE.getAndIncrement() < 12) {
            // 调试输出（已注释）：
            // MaixrMod.LOGGER.info("[空域浮岩] 粗矿块 @ ({}, {}, {}) —— {}",
            //         origin.getX(), origin.getY(), origin.getZ(),
            //         BuiltInRegistries.BLOCK.getKey(ore.getBlock()));
        }
        return placed;
    }

    /** 日志限流：陨石 / 矿块各只报前 12 次 */
    private static final java.util.concurrent.atomic.AtomicInteger LOGGED =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger LOGGED_ORE =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 取"实际可用"的粗矿块池 —— 首次调用时按注册表过滤一次。
     *
     * <p>没装对应模组的方块会被自动剔除，所以不会在世界里放空气。</p>
     */
    private static List<BlockState> orePool() {
        List<BlockState> pool = orePool;
        if (pool != null) return pool;
        synchronized (SkyStoneClusterFeature.class) {
            if (orePool != null) return orePool;
            List<BlockState> built = new ArrayList<>();
            for (ResourceLocation id : RAW_ORE_IDS) {
                Block block = BuiltInRegistries.BLOCK.get(id);
                if (block == null || block == Blocks.AIR) continue;
                built.add(block.defaultBlockState());
            }
            orePool = built;
            // 调试输出（已注释）：
            // MaixrMod.LOGGER.info("[空域浮岩] 粗矿块池：{}/{} 种可用（其余模组未装）",
            //         built.size(), RAW_ORE_IDS.length);
            return built;
        }
    }

    /**
     * 这个维度该不该长浮岩。
     *
     * <h3>为什么不能只看维度名</h3>
     *
     * <p>旧写法是 {@code dim == OVERWORLD || dim == overworld_broken}。
     * 那在 1.20.1 没问题 —— 当时主世界本身就是空岛，Y=110~170 是空域，
     * 浮岩悬在那儿正合适。</p>
     *
     * <p>但现在主世界可能是<b>原版地形</b>（选了普通世界类型）：
     * 地面在 Y=64 左右、山最高 256，Y=110~170 绝大部分是空气
     * —— 浮岩就变成"天上飘着的一堆石头"，很怪。</p>
     *
     * <h3>判定规则</h3>
     *
     * <table border="1">
     *   <tr><th>维度</th><th>生成器</th><th>长浮岩？</th></tr>
     *   <tr><td>破碎世界</td><td>我们的破碎生成器</td><td>✅ 是</td></tr>
     *   <tr><td>主世界（空岛世界类型）</td><td>SkyblockBuilder 的空岛生成器</td><td>✅ 是（空域合理）</td></tr>
 *   <tr><td>主世界（默认世界类型）</td><td>原版噪声</td><td>❌ 否（会有悬空石堆）</td></tr>
 *   <tr><td>其它维度（下界 / 末地等）</td><td>各自的生成器</td><td>❌ 否</td></tr>
 * </table>
     *
     * <p>换句话说：<b>只在"脚下真的是空岛/虚空"的世界里长浮岩</b> ——
     * 因为陨石粉本来就是为了解决空岛玩家采不到陨石的问题。</p>
     */
    private static boolean isMeteorAllowed(WorldGenLevel level, ResourceKey<Level> dim) {
        // 1) 破碎维度：永远长
        if ("overworld_broken".equals(dim.location().getPath())
                && MaixrMod.MODID.equals(dim.location().getNamespace())) {
            return true;
        }
        // 2) 主世界：看它用的是哪个生成器
        if (dim.equals(Level.OVERWORLD)) {
            //   ★ 「破碎」世界类型：主世界就是破碎世界，空域里长浮岩正合适
            if (isBrokenGenerator(level)) return true;
            //   空岛世界类型：空域合理
            return isSkyblockDimension(level);
        }
        // 3) 其余维度（下界 / 末地等）一律不长
        return false;
    }

    /** 主世界用的是不是我们的破碎生成器（＝玩家选的是「破碎」世界类型）。 */
    private static boolean isBrokenGenerator(WorldGenLevel level) {
        try {
            return level.getLevel().getChunkSource().getGenerator()
                    instanceof BrokenOverworldChunkGenerator;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 这个维度用不用空岛生成器。
     *
     * <p>注意 <b>不</b> 把 {@link BrokenSkyblockChunkGenerator} 算进来 ——
     * 它虽然也继承 Skyblock 生成器，但只用在破碎维度（上面已经单独放行）。</p>
     */
    private static boolean isSkyblockDimension(WorldGenLevel level) {
        try {
            var gen = level.getLevel().getChunkSource().getGenerator();
            return gen instanceof de.melanx.skyblockbuilder.world.chunkgenerators.SkyblockNoiseBasedChunkGenerator;
        } catch (Throwable t) {
            return false;   // 拿不到生成器就当不是空岛世界（保守）
        }
    }

    /** 世界生成阶段拿不到 ServerLevel，只能从 WorldGenRegion 里取（调试用 /place 时则是 ServerLevel） */
    private static ResourceKey<Level> dimensionOf(WorldGenLevel level) {
        if (level instanceof WorldGenRegion region) return region.getLevel().dimension();
        if (level instanceof ServerLevel server) return server.dimension();
        return null;
    }

    private static BlockState stateOf(ResourceLocation id) {
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == null || block == Blocks.AIR) return null;
        return block.defaultBlockState();
    }
}
