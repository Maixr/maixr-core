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
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 【空域浮岩】—— 破碎世界天空中的<b>天隙矿脉</b>。
 *
 * <h3>为什么要在模组里做，而不是改数据包</h3>
 * AE2 的陨石落点是它自己代码里算的（{@code MeteoriteStructure} 取
 * {@code Heightmap.Types.OCEAN_FLOOR_WG} 的高度），本包的空岛/破碎生成器在虚空区块上
 * 返回顶高，于是 AE2 陨石全部落在世界天花板（Y≈319）；数据包只能改结构密度，
 * 改不了这个高度。这条理由原本支撑「自己造天空陨石」；v7 起陨石团簇分支已删除，
 * 天空石改由本 Feature 的天隙矿脉宿主承担，但"为什么不用数据包搬 AE2 陨石"
 * 的结论仍然成立——所以本 Feature 仍然需要在代码里做。
 *
 * <h3>这个 Feature 做什么</h3>
 * <p>只生成<b>一类</b>浮岩：<b>天隙矿脉</b>。origin y 范围 <b>-50~300</b>
 * （{@link #VEIN_MIN_Y}~{@link #VEIN_MAX_Y}），每 4x4 区块单元一道
 * （约每 16 区块一道），细长裂隙石脉，长 96~224 格，宿主材质按 origin y 分带，
 * 脉内嵌矿石矿囊（原版式"石中藏矿"）。</p>
 *
 * <p><b>v7 变更</b>：旧的「陨石团簇」独立分支（椭球 + 外壳 {@code ae2:sky_stone_block}
 * + 内核 {@code ae2:smooth_sky_stone_block}）已整体删除；天空石改为矿脉的
 * <b>第三种宿主</b>，只在高空带出现。类名与注册 id {@code maixr:sky_stone_cluster}
 * 保留，属于历史命名，不再是"石头团簇"的意思。</p>
 *
 * <h3>天隙矿脉：跨区块协作生成（v6，移除单区块限制）</h3>
 * <p>旧架构（v4~v5.4）矿脉在单次 Feature 调用内生成，被 FEATURES 阶段的可写范围
 * （中心区块 ±1 区块，见下）物理钳死在 ~45-65 格。v6 改成<b>原版结构式</b>的
 * 跨区块协作：</p>
 * <ol>
 *   <li><b>单元种子确定性</b>：世界按 4x4 区块划单元（{@link #CELL_CHUNKS}），
 *       每单元以「世界种子 + 单元坐标」派生 RNG，确定性决定一道矿脉的<b>全部</b>
 *       几何：origin（单元中心 ±16）、走向、长度 96~224、半径呼吸、错台、挂屑、
 *       矿囊锚点、宿主（按 origin y 分带）与矿种——纯函数，与哪个区块求值无关</li>
 *   <li><b>每区块协作放置</b>：Feature 现在<b>每区块都跑</b>（placed_feature
 *       chance=1）：扫描周围 {@link #CELL_REACH} 圈单元（包围盒粗筛后回放几何），
 *       把落在<b>本区块可写范围</b>内的片段（截面扁椭球 / 挂屑 / 矿囊）放下。
 *       相邻区块各放各的片段——节点坐标是绝对坐标，片段天然无缝拼合，
 *       <b>没有任何断面或缺口</b></li>
 *   <li><b>幂等写入</b>：所有写入只进空气、状态恒定，区块求值顺序无关紧要；
 *       矿囊只替换本区块本次已放的宿主（{@code placed} 集），各片段归属明确</li>
 * </ol>
 *
 * <h3>天隙矿脉形态（四要素；v5.2 去支脉，v5.x 系列修订已并入）</h3>
 * <ol>
 *   <li><b>主脉笔直</b>：随机水平主导方向，每步方向扰动 ±5~15°（轻弯不折腾），
 *       长 96~224 步（v6 移除单区块限制后放开）</li>
 *   <li><b>厚薄呼吸</b>：截面为扁椭球——水平半径 2~4、垂直约其七成（1~3），
 *       沿程分段渐变（每 4~8 步一段）——中轴直、两壁参差，"走向直、壁面毛"，
 *       且脉有墙体厚度（v5.1 修纯平面 bug）</li>
 *   <li><b>两侧挂屑</b>：每 4~8 步在径旁（含上下）附着 1~3 格小碎片——
 *       上缘不塌平；<b>附着</b>不是剔除（v2 教训）</li>
 *   <li><b>偶发错台 + 端头尖灭</b>：~5%/步 ±1 格中轴横向错位（断层感）；
 *       首尾楔形收尖（楔长 = baseR+2~3 步，半径逐级 0,0,1,2,…,baseR 不跳级，
 *       末端单块须尖；v5.4 替换旧"3~5 步线性"——那会出 2,2 平台、末端停在
 *       r=1 十字块，看着是钝头）。
 *       （v4~v5 曾有 2~5 条斜出支脉，v5.2 按裁定移除——单一笔直主脉更干净）</li>
 * </ol>
 * <h3>宿主按 origin y 分带（v7）</h3>
 * <p>整脉统一一种宿主，MUST NOT 混拼。分带由矿脉 origin 的 y 决定——origin y 是
 * 单元种子直接产出的量，判定点唯一，跨区块回放必然一致：</p>
 *
 * <table border="1">
 *   <tr><th>origin y</th><th>宿主</th><th>常量</th></tr>
 *   <tr><td>{@code -50 ≤ y ≤ 8}</td>
 *       <td>{@code minecraft:deepslate} 100%</td>
 *       <td>{@link #DEEPSLATE_ZONE_MAX_Y}</td></tr>
 *   <tr><td>{@code 8 < y < 180}</td>
 *       <td>{@code minecraft:stone} 100%</td>
 *       <td>—</td></tr>
 *   <tr><td>{@code 180 ≤ y ≤ 300}</td>
 *       <td>{@code minecraft:stone} 85% / {@code ae2:sky_stone_block} 15%</td>
 *       <td>{@link #SKY_STONE_MIN_Y} / {@link #SKY_STONE_SHARE}</td></tr>
 * </table>
 *
 * <p>矿囊与宿主匹配：deepslate 脉刷深板岩矿石变体（缺变体回退常规矿石），
 * stone 脉与 sky_stone 脉刷常规矿石——天空石脉不引入专属矿种，保持
 * "矿脉 = 石头 + 矿石"的统一机制。</p>
 *
 * <p><b>AE2 缺失回退</b>：{@code ae2:sky_stone_block} 在注册表查不到（未装 AE2）时，
 * 该脉回退为 {@code minecraft:stone} —— 不整条消失，也不放空气。</p>
 *
 * <h3>区块写入边距（spike 实证；v6 起是"分工窗口"而非"长度上限"）</h3>
 * <p>1.21.1 FEATURES 阶段的可写范围是<b>中心区块 ±1 区块（3x3）</b>：
 * {@code ChunkPyramid.GENERATION_PYRAMID} 的 FEATURES 步声明
 * {@code blockStateWriteRadius(1)}（单位是<b>区块</b>——
 * {@code WorldGenRegion.ensureCanWrite} 用 {@code SectionPos.blockToSectionCoord}
 * 把写入坐标折算成区块坐标后与中心区块比较，|dx|≤1 且 |dz|≤1 才放行）。</p>
 * <p>越界后果是 {@code ensureCanWrite} 返回 false → {@code setBlock} <b>静默丢块</b>
 * （不抛异常、生产环境几乎无感）。v6 用 {@link #canPlace} 按精确范围
 * {@code [base-16, base+31]}（base = origin 所在区块世界坐标）逐点钳制：
 * 它现在只负责把每个片段路由到拥有该区块的生成调用上——矿脉总长不再受它限制。</p>
 *
 * <h3>矿池（粗矿块候选 → 矿石映射，权重）</h3>
 * <p>旧 {@code RAW_ORE_IDS} 16 项逐条映射为矿石方块（深板岩变体单独一列），
 * 权重按稀有度：铁/铜常见 → 金 → 模组常规 → 铀/钠夸达/钻石更稀。
 * <b>registry 过滤机制保留</b>：首次使用时按注册表过滤，未安装模组或查不到
 * 方块的候选直接不进池，不会放空气。逐条核实情况（v4，2.1 任务）：</p>
 *
 * <table border="1">
 *   <tr><th>粗矿块候选</th><th>常规矿石</th><th>深板岩变体</th><th>权重</th><th>备注</th></tr>
 *   <tr><td>{@code minecraft:raw_iron_block}</td><td>{@code iron_ore}</td><td>{@code deepslate_iron_ore}</td><td>20</td><td>原版</td></tr>
 *   <tr><td>{@code minecraft:raw_copper_block}</td><td>{@code copper_ore}</td><td>{@code deepslate_copper_ore}</td><td>20</td><td>原版</td></tr>
 *   <tr><td>{@code minecraft:raw_gold_block}</td><td>{@code gold_ore}</td><td>{@code deepslate_gold_ore}</td><td>8</td><td>原版</td></tr>
 *   <tr><td>（新增）</td><td>{@code diamond_ore}</td><td>{@code deepslate_diamond_ore}</td><td>3</td><td>原版，spec 要求"钻石更稀"</td></tr>
 *   <tr><td>{@code create:raw_zinc_block}</td><td>{@code create:zinc_ore}</td><td>{@code create:deepslate_zinc_ore}</td><td>8</td><td>Create 变体确定存在</td></tr>
 *   <tr><td>{@code mekanism:block_raw_osmium}</td><td>{@code mekanism:osmium_ore}</td><td>{@code mekanism:deepslate_osmium_ore}</td><td>8</td><td></td></tr>
 *   <tr><td>{@code mekanism:block_raw_lead}</td><td>{@code mekanism:lead_ore}</td><td>{@code mekanism:deepslate_lead_ore}</td><td>8</td><td></td></tr>
 *   <tr><td>{@code mekanism:block_raw_tin}</td><td>{@code mekanism:tin_ore}</td><td>{@code mekanism:deepslate_tin_ore}</td><td>8</td><td></td></tr>
 *   <tr><td>{@code mekanism:block_raw_uranium}</td><td>{@code mekanism:uranium_ore}</td><td>{@code mekanism:deepslate_uranium_ore}</td><td>4</td><td>稀有</td></tr>
 *   <tr><td>{@code mekanism_extras:block_raw_naquadah}</td><td>{@code mekanism_extras:naquadah_ore}</td><td>—</td><td>4</td><td>稀有；变体未核实，缺失回退常规</td></tr>
 *   <tr><td>{@code mekmm:block_raw_silver}</td><td>{@code mekmm:silver_ore}</td><td>—</td><td>8</td><td>id 未核实，registry 过滤兜底</td></tr>
 *   <tr><td>{@code neoecoae:raw_tungsten_block}</td><td>{@code neoecoae:tungsten_ore}</td><td>—</td><td>8</td><td>同上</td></tr>
 *   <tr><td>{@code neoecoae:raw_aluminum_block}</td><td>{@code neoecoae:aluminum_ore}</td><td>—</td><td>8</td><td>同上</td></tr>
 *   <tr><td>{@code pasterdream:raw_titanium_block}</td><td>{@code pasterdream:titanium_ore}</td><td>—</td><td>8</td><td>同上</td></tr>
 *   <tr><td>{@code anvilcraft:raw_titanium_block}</td><td>{@code anvilcraft:titanium_ore}</td><td>—</td><td>8</td><td>同上</td></tr>
 *   <tr><td>{@code anvilcraft:raw_tungsten_block}</td><td>{@code anvilcraft:tungsten_ore}</td><td>—</td><td>8</td><td>同上</td></tr>
 *   <tr><td>{@code enderio_evolution:crude_steel_block}</td><td>—</td><td>—</td><td>—</td><td><b>剔除</b>：粗钢是合金产物，不是天然矿，无对应矿石方块</td></tr>
 * </table>
 *
 * <p>本机 libs/ 只有 AE2/Curios/SkyblockBuilder/LibX，模组矿 id 无法离线逐一核实
 * ——预期 id 写进上表，装了模组但 id 对不上时 registry 过滤兜底（不进池）。</p>
 *
 * <h3>安全设计</h3>
 * <ul>
 *   <li><b>只在"脚下真的是空岛/虚空"的世界生成</b>（见 {@link #isVeinAllowed}）：
 *       破碎 / 空岛世界类型的主世界，以及 {@code maixr:overworld_broken} 维度。</li>
 *   <li>只往空气里放（{@code isEmptyBlock} 守卫），不覆盖已有地形（含天空结构方块）；
 *       矿囊只替换本区块本次已放的宿主方块。</li>
 *   <li>同种子确定性：矿脉几何、宿主分带与天空石抽取全部由「世界种子+单元坐标」
 *       派生，不再使用 {@code ctx.random()}——无全局/静态随机状态，也不依赖区块
 *       求值顺序。</li>
 * </ul>
 */
public class SkyStoneClusterFeature extends Feature<NoneFeatureConfiguration> {
    /** 天空石（v7：矿脉高空带宿主；旧陨石团簇外壳复用同一方块）。 */
    private static final ResourceLocation SKY_STONE = ResourceLocation.fromNamespaceAndPath("ae2", "sky_stone_block");

    /**
     * 深板岩带的上界（origin y ≤ 此值 → 整脉 deepslate）。
     *
     * <p>取值依据：破碎世界地表规则（{@code broken_overworld.json} 的
     * {@code minecraft:vertical_gradient}，{@code random_name=minecraft:deepslate}）
     * 在 y≤0 必为深板岩、y≥8 必不为深板岩，0~8 是噪声过渡带。取 8 让深板岩带
     * 与地表规则的上沿对齐——深板岩地形里长出的矿脉全部是深板岩脉。</p>
     */
    public static final int DEEPSLATE_ZONE_MAX_Y = 8;

    /**
     * 高空带下界（origin y ≥ 此值 → stone 85% / sky_stone 15%）。
     *
     * <p>v7 前陨石团簇锁在 110~170；改为矿脉宿主后按玩家裁定"只生成在更高的高空"，
     * 取 180 起，与旧陨石带明显拉开。</p>
     */
    public static final int SKY_STONE_MIN_Y = 180;

    /**
     * 高空带内天空石脉的占比（其余为石头）。单条矿脉数以千块计，而旧陨石单颗仅
     * 约百块——用 0.15 把天空石供给压回可比量级（≈ 1/7 的高空脉）。
     */
    public static final double SKY_STONE_SHARE = 0.15D;

    // ---- 天隙矿脉：单元网格（v6） ----
    /** 单元边长（区块）：4x4 区块 = 64 格，每单元一道矿脉 */
    public static final int CELL_CHUNKS = 4;
    public static final int CELL_BLOCKS = CELL_CHUNKS * 16;
    private static final int CELL_SHIFT = 6;             // log2(CELL_BLOCKS)
    /** 矿脉长度范围（格）—— v6 移除单区块限制后放开（单区块时代上限 ~65） */
    public static final int FISSURE_MIN_LEN = 96, FISSURE_MAX_LEN = 224;
    /** 邻单元扫描半径（单元数）：最长脉半长（origin 居中）+ 区域对角余量，向上取整 */
    private static final int CELL_REACH = (FISSURE_MAX_LEN / 2 + 48 + 44) / CELL_BLOCKS + 1;
    /** 包围盒粗筛半径：脉半长（origin 居中后 ≤ MAX_LEN/2）+ 截面/挂屑/摆动余量 */
    private static final int CELL_BBOX_HALF = FISSURE_MAX_LEN / 2 + 48;
    /** 主径方向摆动限幅（弧度，~20°）：v6.1 修复——每步 ±5~15° 独立扰动在 96~224 步下
     *  方向角随机游走累计 ~140°，脉蜷成团（实测净位移 avg 110 / min 7）。
     *  限幅后 locally 仍有 5~15° 的轻弯，整体保持笔直（实测净位移 avg 156 / min 94）。 */
    private static final double MAX_WANDER_RAD = 0.35;

    /**
     * 矿脉 origin 的 y 范围。下限 -50：世界 min_y=-64，留 14 格余量避免贴底。
     * 上限 300：世界顶部 y=319，留 19 格余量避免矿脉顶部（垂直半径 ≤3 + 挂屑）越界。
     * v7 起由 170 抬到 300，让矿脉能进入旧陨石的"更高空域"。
     */
    public static final int VEIN_MIN_Y = -50, VEIN_MAX_Y = 300;

    // ---- 裂隙形态参数（调参见 _docs/破碎世界.md 一点七） ----
    /** 每步方向扰动的最大幅度（度），实际取 5~15 之间随机带符号 —— "笔直一些" */
    private static final double YAW_JITTER_MIN_DEG = 5.0, YAW_JITTER_MAX_DEG = 15.0;
    /** 厚薄呼吸：每段 4~8 步，半径在 2~4 间 ±1 渐变 */
    private static final int BREATH_SEG_MIN = 4, BREATH_SEG_MAX = 8;
    /**
     * 首尾收尖楔长 = 呼吸基准半径 + 2~3 步（v5.4）。
     * 半径沿楔长逐级 0,0,1,2,…,baseR 不跳级，末端 r=0 由 placeDisc 落成单块须尖——
     * 旧版"3~5 步线性"在 baseR=4 时序列是 1,2,2,3,4（有平台、末端是 r=1 十字块），看着是钝头。
     */
    private static final int TAPER_EXTRA_MIN = 2, TAPER_EXTRA_MAX = 3;
    /** 偶发错台：每步概率 */
    private static final double KINK_CHANCE = 0.05;
    /** 挂屑：每 4~8 步一粒 */
    private static final int FLAKE_EVERY_MIN = 4, FLAKE_EVERY_MAX = 8;
    /** 矿囊：个数 / 半径（v6 长脉加量） */
    private static final int POCKET_MIN = 6, POCKET_MAX = 14;
    private static final int POCKET_R_MAX = 3;

    /**
     * 矿种条目：常规矿石 id + 深板岩变体 id（null = 无变体，deepslate 脉回退常规）
     * + 权重。映射依据见类 javadoc 矿池表。
     */
    private record OreEntry(ResourceLocation ore, ResourceLocation deepslateOre, int weight) {
    }

    private static ResourceLocation mc(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    private static ResourceLocation id(String ns, String path) {
        return ResourceLocation.fromNamespaceAndPath(ns, path);
    }

    /**
     * 矿种候选（见类 javadoc 矿池表）。顺序无意义（按权重随机挑）。
     */
    private static final OreEntry[] ORE_CANDIDATES = {
            new OreEntry(mc("iron_ore"), mc("deepslate_iron_ore"), 20),
            new OreEntry(mc("copper_ore"), mc("deepslate_copper_ore"), 20),
            new OreEntry(mc("gold_ore"), mc("deepslate_gold_ore"), 8),
            new OreEntry(mc("diamond_ore"), mc("deepslate_diamond_ore"), 3),
            new OreEntry(id("create", "zinc_ore"), id("create", "deepslate_zinc_ore"), 8),
            new OreEntry(id("mekanism", "osmium_ore"), id("mekanism", "deepslate_osmium_ore"), 8),
            new OreEntry(id("mekanism", "lead_ore"), id("mekanism", "deepslate_lead_ore"), 8),
            new OreEntry(id("mekanism", "tin_ore"), id("mekanism", "deepslate_tin_ore"), 8),
            new OreEntry(id("mekanism", "uranium_ore"), id("mekanism", "deepslate_uranium_ore"), 4),
            new OreEntry(id("mekanism_extras", "naquadah_ore"), null, 4),
            new OreEntry(id("mekmm", "silver_ore"), null, 8),
            new OreEntry(id("neoecoae", "tungsten_ore"), null, 8),
            new OreEntry(id("neoecoae", "aluminum_ore"), null, 8),
            new OreEntry(id("pasterdream", "titanium_ore"), null, 8),
            new OreEntry(id("anvilcraft", "titanium_ore"), null, 8),
            new OreEntry(id("anvilcraft", "tungsten_ore"), null, 8),
            // 剔除 enderio_evolution:crude_steel_block —— 合金产物，非天然矿，无对应矿石
    };

    /** 过滤后的矿池条目（首次使用时建好，之后就复用）。 */
    private record PooledOre(BlockState ore, BlockState deepslateOre, int weight) {
    }

    private static volatile List<PooledOre> orePool = null;

    /** 路径节点：位置 + 该点截面半径（呼吸与首尾楔形共同决定）。 */
    private record Node(BlockPos pos, int radius) {
    }

    /** 挂屑：锚点 + 已确定的碎粒偏移（单元 RNG 确定性生成）。 */
    private record FlakeSpec(BlockPos anchor, List<BlockPos> grains) {
    }

    /**
     * 矿囊：锚点 + 半径 + <b>本囊自己的矿种</b>（球内偏移在放置时确定性展开，
     * 只换本区块已放的宿主）。
     *
     * <p>v6.3：矿种从「整脉一种」改为<b>每囊独立抽</b>——同一条石脉上可以同时出现
     * 铁、铜、金、钻石等不同矿囊，更接近原版矿脉混生（用户需求）。</p>
     */
    private record PocketSpec(BlockPos anchor, int radius, BlockState ore) {
    }

    /** 一道矿脉的全部确定性几何（单元种子回放产物，与求值区块无关）。 */
    private record VeinSpec(List<Node> nodes, List<FlakeSpec> flakes, List<PocketSpec> pockets,
                            BlockState host, BlockPos origin) {
    }

    /** 实际产出统计（给 /maixr 指令或调试看"到底放了多少、比例对不对"）。 */
    private static final java.util.concurrent.atomic.AtomicInteger COUNT_ORE =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 打印实际产出统计。 */
    public static void dumpStats() {
        int o = COUNT_ORE.get();
        // 调试输出（已注释）：
        // MaixrMod.LOGGER.info("[空域浮岩] 已生成 矿脉片段 {} 区块次", o);
    }

    public SkyStoneClusterFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        WorldGenLevel level = ctx.level();
        ResourceKey<Level> dim = dimensionOf(level);
        if (dim == null) return false;
        if (!isVeinAllowed(level, dim)) return false;

        // v6：矿脉片段每区块协作放置（单元网格确定性，不依赖 ctx.random）
        return placeNearbyFissureSegments(level, origin(ctx));
    }

    private static BlockPos origin(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        return ctx.origin();
    }

    // ==================================================================
    //  天隙矿脉（v6：单元网格确定性几何 + 跨区块协作放置）
    // ==================================================================

    /**
     * 扫描周围单元，把每道近邻矿脉落在<b>本区块可写范围</b>内的片段放下。
     */
    private boolean placeNearbyFissureSegments(WorldGenLevel level, BlockPos origin) {
        if (orePool().isEmpty()) return false;               // 一个矿石模组都没装
        long seed = level.getSeed();
        int cellX = (origin.getX() >> 4) >> 2, cellZ = (origin.getZ() >> 4) >> 2;
        int baseX = (origin.getX() >> 4) << 4, baseZ = (origin.getZ() >> 4) << 4;
        int minX = baseX - 16, maxX = baseX + 31, minZ = baseZ - 16, maxZ = baseZ + 31;
        int half = CELL_BBOX_HALF;                           // 包围盒粗筛：半长 + 余量（v6.1）
        Set<BlockPos> placed = new HashSet<>();
        boolean any = false;
        for (int dx = -CELL_REACH; dx <= CELL_REACH; dx++) {
            for (int dz = -CELL_REACH; dz <= CELL_REACH; dz++) {
                int cx = cellX + dx, cz = cellZ + dz;
                BlockPos o = veinOrigin(seed, cx, cz);
                // 包围盒粗筛：直线脉的包络与本区块可写范围不相交 → 不回放几何
                if (o.getX() + half < minX || o.getX() - half > maxX
                        || o.getZ() + half < minZ || o.getZ() - half > maxZ) {
                    continue;
                }
                VeinSpec vein = buildVein(seed, cx, cz);
                if (vein != null) {
                    any |= placeVeinSegments(level, vein, origin, placed);
                }
            }
        }
        return any;
    }

    /** 单元矿脉 origin：独立于主几何序列（包围盒粗筛不必回放整条 RNG）。 */
    private static BlockPos veinOrigin(long seed, int cellX, int cellZ) {
        WorldgenRandom rnd = new WorldgenRandom(new LegacyRandomSource(
                seed ^ ((long) cellX << 32) ^ cellZ ^ 0x5DEECE66DL));
        int ox = (cellX << CELL_SHIFT) + (CELL_BLOCKS >> 1) + (rnd.nextInt(33) - 16);
        int oz = (cellZ << CELL_SHIFT) + (CELL_BLOCKS >> 1) + (rnd.nextInt(33) - 16);
        // origin y 是宿主分带的唯一判定点（见类 javadoc「宿主按 origin y 分带」）：
        //   -50~8 深板岩 / 8~180 石头 / 180~300 高空（石头 + 天空石）
        int oy = VEIN_MIN_Y + rnd.nextInt(VEIN_MAX_Y - VEIN_MIN_Y + 1);
        return new BlockPos(ox, oy, oz);
    }

    /**
     * 按 origin y 解析整脉宿主（v7：替换旧的 70/30 随机）。
     *
     * <ul>
     *   <li>{@code y ≤ DEEPSLATE_ZONE_MAX_Y(8)} → {@code minecraft:deepslate}</li>
     *   <li>{@code 8 < y < SKY_STONE_MIN_Y(180)} → {@code minecraft:stone}</li>
     *   <li>{@code y ≥ 180} → 按 {@link #SKY_STONE_SHARE} 抽
     *       {@code ae2:sky_stone_block}，否则 {@code minecraft:stone}</li>
     * </ul>
     *
     * <p><b>AE2 缺失回退</b>：高空带照常消耗一次 RNG（保证几何与是否装 AE2 无关），
     * 但查不到天空石方块时回退石头——不整条消失，也不放空气。</p>
     */
    private static BlockState resolveHost(int y, RandomSource rnd) {
        if (y <= DEEPSLATE_ZONE_MAX_Y) {
            return Blocks.DEEPSLATE.defaultBlockState();
        }
        if (y < SKY_STONE_MIN_Y) {
            return Blocks.STONE.defaultBlockState();
        }
        // 高空带：无论 AE2 是否安装都先消耗一次随机，保持几何序列一致。
        if (rnd.nextDouble() < SKY_STONE_SHARE) {
            BlockState sky = stateOf(SKY_STONE);
            if (sky != null) return sky;
        }
        return Blocks.STONE.defaultBlockState();
    }

    /**
     * 回放一道矿脉的<b>全部确定性几何</b>（不放置任何方块）：
     * 主径游走 → 楔形收尖半径 → 挂屑/矿囊规格。与世界种子+单元坐标一一对应，
     * 任何区块求值结果完全一致。
     */
    private static VeinSpec buildVein(long seed, int cellX, int cellZ) {
        WorldgenRandom rnd = new WorldgenRandom(new LegacyRandomSource(seed));
        rnd.setLargeFeatureSeed(seed, (cellX << 2) + (CELL_CHUNKS >> 1), (cellZ << 2) + (CELL_CHUNKS >> 1));
        int len = FISSURE_MIN_LEN + rnd.nextInt(FISSURE_MAX_LEN - FISSURE_MIN_LEN + 1);
        double yaw = rnd.nextDouble() * Math.PI * 2.0;
        int baseRadius = 2 + rnd.nextInt(3);
        int taper = baseRadius + TAPER_EXTRA_MIN + rnd.nextInt(TAPER_EXTRA_MAX - TAPER_EXTRA_MIN + 1);
        // v7：宿主不再随机 70/30，改按 origin y 分带（origin 由独立序列派生，先取）。
        BlockPos origin = veinOrigin(seed, cellX, cellZ);
        BlockState host = resolveHost(origin.getY(), rnd);
        boolean deepslate = host.is(Blocks.DEEPSLATE);

        // ---- 主径游走（纯几何：不受任何区块边界影响） ----
        // v6.1：origin 居中——起点沿主导方向回退半长，脉向两侧延伸各 ~len/2
        // （v6 曾从 origin 单向走满 len，包络半径=len 超出预检 half，远端被静默截短）
        List<Node> nodes = new ArrayList<>(len);
        List<Integer> breaths = new ArrayList<>(len);
        double baseYaw = yaw, wander = 0;
        double x = origin.getX() + 0.5 - Math.cos(baseYaw) * (len / 2.0);
        double z = origin.getZ() + 0.5 - Math.sin(baseYaw) * (len / 2.0);
        int y = origin.getY();
        int segLeft = 0, breathR = baseRadius;
        int flakeEvery = FLAKE_EVERY_MIN + rnd.nextInt(FLAKE_EVERY_MAX - FLAKE_EVERY_MIN + 1);
        for (int i = 0; i < len; i++) {
            double jitterDeg = YAW_JITTER_MIN_DEG + rnd.nextDouble() * (YAW_JITTER_MAX_DEG - YAW_JITTER_MIN_DEG);
            // v6.1：方向摆动限幅（±~20°），长脉不蜷线（详见 MAX_WANDER_RAD 注）
            wander = Math.max(-MAX_WANDER_RAD, Math.min(MAX_WANDER_RAD,
                    wander + Math.toRadians(jitterDeg) * (rnd.nextBoolean() ? 1 : -1)));
            yaw = baseYaw + wander;
            if (rnd.nextDouble() < KINK_CHANCE) {            // 偶发错台
                x += -Math.sin(yaw) * (rnd.nextBoolean() ? 1 : -1);
                z += Math.cos(yaw) * (rnd.nextBoolean() ? 1 : -1);
            }
            if (rnd.nextInt(10) < 3) {                       // 垂直轻摆（±3 内）
                int dy = rnd.nextBoolean() ? 1 : -1;
                if (Math.abs(y + dy - origin.getY()) <= 3) y += dy;
            }
            x += Math.cos(yaw);
            z += Math.sin(yaw);
            if (--segLeft <= 0) {                            // 厚薄呼吸
                segLeft = BREATH_SEG_MIN + rnd.nextInt(BREATH_SEG_MAX - BREATH_SEG_MIN + 1);
                breathR = Math.max(2, Math.min(4, breathR + (rnd.nextInt(3) - 1)));
            }
            nodes.add(new Node(BlockPos.containing(x, y, z), 0));
            breaths.add(breathR);
        }

        // ---- 半径：呼吸 × 首尾楔形收尖（v5.4：逐级不跳级，末端单块须尖） ----
        int n = nodes.size();
        for (int k = 0; k < n; k++) {
            int dH = k, dT = n - 1 - k;
            int wedgeH = dH < taper ? Math.min(baseRadius, dH * (baseRadius + 1) / taper) : baseRadius;
            int wedgeT = dT < taper ? Math.min(baseRadius, dT * (baseRadius + 1) / taper) : baseRadius;
            Node node = nodes.get(k);
            nodes.set(k, new Node(node.pos(),
                    Math.max(0, Math.min(breaths.get(k), Math.min(wedgeH, wedgeT)))));
        }

        // ---- 挂屑规格（半径定型后再定锚点偏移；确定性） ----
        List<FlakeSpec> flakes = new ArrayList<>();
        for (int k = taper; k < n - taper; k += flakeEvery) {
            if (rnd.nextInt(3) == 0) continue;               // 偶尔跳过，避免规律感
            Node node = nodes.get(k);
            double nx = -Math.sin(yawAt(nodes, k)), nz = Math.cos(yawAt(nodes, k));
            double off = node.radius() + 1;
            BlockPos anchor;
            switch (rnd.nextInt(4)) {                        // 上下两侧都有份
                case 0 -> anchor = node.pos().offset((int) Math.round(nx * off), 0, (int) Math.round(nz * off));
                case 1 -> anchor = node.pos().offset((int) Math.round(-nx * off), 0, (int) Math.round(-nz * off));
                case 2 -> anchor = node.pos().offset(0, (int) off, 0);
                default -> anchor = node.pos().offset(0, (int) -off, 0);
            }
            List<BlockPos> grains = new ArrayList<>(3);
            BlockPos p = anchor;
            int grainCount = 1 + rnd.nextInt(3);             // 1~3 格
            for (int g = 0; g < grainCount; g++) {
                grains.add(p);
                p = p.offset(rnd.nextInt(3) - 1, rnd.nextInt(3) - 1, rnd.nextInt(3) - 1);
            }
            flakes.add(new FlakeSpec(anchor, grains));
        }

        // ---- 矿囊规格：沿主径随机取锚点（球内偏移在放置时展开） ----
        // v6.3：每囊<b>独立</b>抽矿种——同一条石脉上可混生铁/铜/金/钻石等（用户需求），
        //   矿种仍按宿主族群取变体（deepslate 脉刷深板岩矿石，缺变体回退常规）。
        List<PooledOre> pool = orePool();
        int pockets = POCKET_MIN + rnd.nextInt(POCKET_MAX - POCKET_MIN + 1);
        List<PocketSpec> pocketSpecs = new ArrayList<>(pockets);
        for (int pk = 0; pk < pockets; pk++) {
            Node anchor = nodes.get(taper + rnd.nextInt(Math.max(1, n - 2 * taper)));
            PooledOre picked = weightedPick(pool, rnd);
            BlockState pocketOre = (deepslate && picked.deepslateOre() != null)
                    ? picked.deepslateOre() : picked.ore();
            pocketSpecs.add(new PocketSpec(anchor.pos(), 1 + rnd.nextInt(POCKET_R_MAX), pocketOre));
        }

        return new VeinSpec(nodes, flakes, pocketSpecs, host, origin);
    }

    /**
     * 把一道矿脉落在<b>本区块可写范围</b>内的片段放下（截面/挂屑/矿囊）。
     * {@code placed} 是本区块本次已放的宿主方块——矿囊只换这些，
     * 因此各区块对自己拥有的方块负责，跨区块幂等。
     */
    private boolean placeVeinSegments(WorldGenLevel level, VeinSpec vein, BlockPos origin,
                                      Set<BlockPos> placed) {
        boolean any = false;
        for (Node node : vein.nodes()) {
            any |= placeDisc(level, node.pos(), node.radius(), vein.host(), placed, origin);
        }
        for (FlakeSpec flake : vein.flakes()) {
            for (BlockPos g : flake.grains()) {
                if (canPlace(level, g, origin)) {
                    level.setBlock(g, vein.host(), 2);
                    placed.add(g);
                    any = true;
                }
            }
        }
        for (PocketSpec pocket : vein.pockets()) {
            int pr = pocket.radius();
            for (int dx = -pr; dx <= pr; dx++) {
                for (int dy = -pr; dy <= pr; dy++) {
                    for (int dz = -pr; dz <= pr; dz++) {
                        double d = dx * dx + dy * dy + dz * dz;
                        if (d > pr * pr + 0.5D) continue;
                        BlockPos p = pocket.anchor().offset(dx, dy, dz);
                        // ★ v6.3 修 bug：这里**不能**用 canPlace（内含 isEmptyBlock）——
                        //   p 是本区块本次刚铺好的宿主，isEmptyBlock 必然 false，
                        //   导致矿囊永远写不进去（"不生成矿石"）。矿囊只替换自己的宿主，
                        //   只需可写窗口检查。
                        if (placed.contains(p) && inWindow(p, origin)) {
                            level.setBlock(p, pocket.ore(), 2);
                            any = true;
                        }
                    }
                }
            }
        }
        if (any) COUNT_ORE.incrementAndGet();
        if (any && LOGGED_ORE.getAndIncrement() < 12) {
            // 调试输出（已注释）：
            // MaixrMod.LOGGER.info("[空域浮岩] 矿脉片段 @ ({}, {}, {}) —— 宿主 {}, 矿种 {}", ...);
        }
        return any;
    }

    /**
     * 取节点 k 处的走向（由相邻节点差分），用于挂屑法向。
     */
    private static double yawAt(List<Node> nodes, int k) {
        BlockPos a = nodes.get(Math.max(0, k - 1)).pos();
        BlockPos b = nodes.get(Math.min(nodes.size() - 1, k + 1)).pos();
        return Math.atan2(b.getZ() - a.getZ(), b.getX() - a.getX());
    }

    /**
     * 铺一个截面<b>扁椭球</b>：水平半径 {@code radius}（2~4），垂直半径
     * ry ≈ radius 的七成（1~3）——矿脉是"墙状"而不是"纸状"。
     * <p>v5.1 修 bug：旧版只铺 dy=0 的纯 XZ 圆盘，整条脉只有 1 格高
     * （加挂屑最多 2 格），玩家反馈"全是扁的"。</p>
     *
     * <p>判定 (dx²+dz²)/rr + dy²/rry ≤ 1（+0.25 补齐体素棱角）。
     * r=0 落成单块须尖；已放过的位置跳过，省重复 setBlock。</p>
     */
    private boolean placeDisc(WorldGenLevel level, BlockPos center, int radius,
                              BlockState host, Set<BlockPos> placed, BlockPos origin) {
        boolean placedAny = false;
        int ry = Math.max(0, Math.round(radius * 0.7F));
        double rr = radius * radius + 0.25D;
        double rry = ry * ry + 0.25D;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -ry; dy <= ry; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if ((dx * dx + dz * dz) / rr + (dy * dy) / rry > 1.0D) continue;
                    BlockPos p = center.offset(dx, dy, dz);
                    if (placed.contains(p) || !canPlace(level, p, origin)) continue;
                    level.setBlock(p, host, 2);
                    placed.add(p);
                    placedAny = true;
                }
            }
        }
        return placedAny;
    }

    /**
     * 统一写入闸门：只往空气放 + 精确可写范围钳制（见类 javadoc「区块写入边距」——
     * 越界是<b>静默丢块</b>，这里必须挡在 setBlock 之前）。
     *
     * <p>范围 = 中心区块 ±1 区块（FEATURES 阶段 {@code blockStateWriteRadius(1)}）：
     * {@code [base-16, base+31]}，base 为 origin 所在区块的世界坐标。
     * v6 起它是<b>片段路由窗口</b>：矿脉总长不受它限制（跨区块协作），
     * 它只保证本区块只写自己的份。</p>
     */
    private static boolean canPlace(WorldGenLevel level, BlockPos p, BlockPos origin) {
        // ★ 先做纯算术的范围检查，范围内才允许查世界——WorldGenRegion 对区域外的
        //   方块查询是直接抛 IllegalStateException（"Requested chunk unavailable"，
        //   不是返回空气），v6.2 之前 isEmptyBlock 跑在前面，远端节点一查就炸穿
        //   生成（v6.2 同步移除了生成器的越界读防线，异常不再被吞）。
        if (!inWindow(p, origin)) {
            return false;                        // 不属于本区块的可写窗口：由拥有它的区块负责
        }
        return level.isEmptyBlock(p);
    }

    /**
     * 纯算术的"本区块可写窗口"判定（<b>不</b>触碰世界，因此对区域外坐标安全）。
     * 用于矿囊这类"替换已放置宿主"的写入——它们不能用 {@link #canPlace}
     * （那里会查 {@code isEmptyBlock}，而宿主已非空气）。
     */
    private static boolean inWindow(BlockPos p, BlockPos origin) {
        int baseX = (origin.getX() >> 4) << 4;
        int baseZ = (origin.getZ() >> 4) << 4;
        return p.getX() >= baseX - 16 && p.getX() <= baseX + 31
                && p.getZ() >= baseZ - 16 && p.getZ() <= baseZ + 31;
    }

    /** 日志限流：矿脉片段只报前 12 次 */
    private static final java.util.concurrent.atomic.AtomicInteger LOGGED_ORE =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 取"实际可用"的矿池 —— 首次调用时按注册表过滤一次。
     *
     * <p>没装对应模组的矿石会被自动剔除，所以不会在世界里放空气；
     * 深板岩变体缺失的条目照常进池（deepslate 脉回退常规矿石）。</p>
     */
    private static List<PooledOre> orePool() {
        List<PooledOre> pool = orePool;
        if (pool != null) return pool;
        synchronized (SkyStoneClusterFeature.class) {
            if (orePool != null) return orePool;
            List<PooledOre> built = new ArrayList<>();
            for (OreEntry entry : ORE_CANDIDATES) {
                Block ore = BuiltInRegistries.BLOCK.get(entry.ore());
                if (ore == null || ore == Blocks.AIR) continue;
                Block deepslateOre = entry.deepslateOre() == null
                        ? null : BuiltInRegistries.BLOCK.get(entry.deepslateOre());
                if (deepslateOre == Blocks.AIR) deepslateOre = null;
                built.add(new PooledOre(ore.defaultBlockState(),
                        deepslateOre == null ? null : deepslateOre.defaultBlockState(),
                        entry.weight()));
            }
            orePool = built;
            MaixrMod.LOGGER.info("[空域浮岩] 矿脉矿池：{}/{} 种可用（其余模组未装或 id 未核实缺失）",
                    built.size(), ORE_CANDIDATES.length);
            return built;
        }
    }

    /** 按权重随机挑一个矿种。 */
    private static PooledOre weightedPick(List<PooledOre> pool, RandomSource rnd) {
        int total = 0;
        for (PooledOre e : pool) total += e.weight();
        int roll = rnd.nextInt(total);
        for (PooledOre e : pool) {
            roll -= e.weight();
            if (roll < 0) return e;
        }
        return pool.get(pool.size() - 1);
    }

    /**
     * 这个维度该不该长浮岩。
     *
     * <p><b>只在"脚下真的是空岛/虚空"的世界里长浮岩</b>——
     * 普通主世界的空域里飘石脉会显得很怪。</p>
     *
     * <table border="1">
     *   <tr><th>维度</th><th>生成器</th><th>长浮岩？</th></tr>
     *   <tr><td>破碎世界</td><td>我们的破碎生成器</td><td>✅ 是</td></tr>
     *   <tr><td>主世界（空岛世界类型）</td><td>SkyblockBuilder 的空岛生成器</td><td>✅ 是（空域合理）</td></tr>
     *   <tr><td>主世界（默认世界类型）</td><td>原版噪声</td><td>❌ 否（会有悬空石堆）</td></tr>
     *   <tr><td>其它维度（下界 / 末地等）</td><td>各自的生成器</td><td>❌ 否</td></tr>
     * </table>
     */
    private static boolean isVeinAllowed(WorldGenLevel level, ResourceKey<Level> dim) {
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
