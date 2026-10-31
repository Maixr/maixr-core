package com.maixr;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 「破碎」碎裂场主函数 {@code maixr:fracture} —— 输出<b>撕裂深度</b>（负值，
 * 直接 {@code add} 到原版 {@code final_density} 上）：
 * <ul>
 *   <li>{@code 0}：地形完好（平静区 / 存活板块内部）—— <b>精确等于原版</b>；</li>
 *   <li>{@code (survival-1) × TEAR_SCALE}：部分撕裂（裂缝边缘、板块海岸线的
 *       侵蚀斜坡）；</li>
 *   <li>{@code -TEAR_SCALE = -4}：完全撕裂（裂缝/死亡板块/震中中心）——
 *       任何原版密度都会被压到 0 以下 → 强制空气。</li>
 * </ul>
 *
 * <h2>★ 为什么是减法而不是乘法掩码（实现修正，runServer 实测教训）</h2>
 *
 * <p>最初版本输出 ∈ [-0.3, 1] 的乘法掩码，但 vanilla 实体判定是
 * {@code final_density &gt; 0.0} —— 在原版密度为<b>负</b>的区域（洞穴/空腔），
 * 负掩码 × 负密度 = <b>正密度 → 空气反转成实体</b>（实测：末地外岛被反转成
 * 29/32 实心、下界碎裂带 21/32 高于原版 11/32）。减法形式对任意原版密度符号
 * 都单调：{@code 原版密度 - 撕裂深度 &gt; 0} 才实体，完好处深度 0 精确还原原版。</p>
 *
 * <p>撕裂深度 = 1 -（四带静滞渐变下的存活率 = 脊状裂缝噪声 × 板块存活率
 * ± 震中加压），JSON 里 {@code add(原final, cache_2d(maixr:fracture))}。
 * 纯 2D（xz）场，{@code cache_2d} 逐列缓存。</p>
 *
 * <h2>四带模型（常量集中于此类顶部）</h2>
 * <pre>
 *   带进度 t：0=平静区 → 1=裂缝带末 → 2=碎块带末 → 3=碎屑带（渐近封顶）
 *   r ≤ CALM_RADIUS            平静区：无裂缝、无板块死亡、无漂移
 *   CALM_RADIUS..CRACK_END     裂缝带：窄裂缝（阈值 0.78），板块全活
 *   CRACK_END..FRAG_END        碎块带：宽裂缝（0.45）+ 板块死亡 0→0.6 + 漂移增强
 *   FRAG_END..                 碎屑带：裂缝 0.28 + 板块死亡→0.85 + 碎屑子层 + 强漂移
 * </pre>
 *
 * <p>裂缝 = 脊状噪声（{@code 1-|noise|} 阈值化）：各向同性、天然分叉、宽窄随第二倍频程
 * 变化（spec「各向同性裂缝」）；不均匀性由震中与板块死亡承担（spec「破碎的不均匀性」）。
 * 裂缝噪声带种子（{@link net.minecraft.world.level.levelgen.DensityFunction.NoiseHolder}
 * 经 RandomState 注入，见 design D3/D7 第 2 条）；板块哈希无种子只做抖动。</p>
 */
public final class FractureDensityFunction implements DensityFunction.SimpleFunction {

    // ==========================================================================
    //  ★ 四带常量（想调手感改这里；文档引用必须与这里一致 —— AGENTS.md 文档规范）
    // ==========================================================================

    /**
     * ★★ 全图统一碎裂水平（用户决定取消四带静滞渐变与平静区）：
     * 带进度恒定，<b>运行时由配置 {@code config/maixr-common.toml} 的
     * {@code uniformBand} 控制</b>（本常量只是默认值 2.0，见 {@link MaixrConfig#uniformBand()}；
     * 用户调参 2.0 = 原渐变设计碎块带末水平：宽裂缝阈值 0.45 + 板块死亡 0.60 +
     * 漂移 25 格，无碎屑子层）。改动只影响未生成区块。
     * 末地主岛核心的静滞由 JSON 里的 {@code calm_radius} 参数提供（~225 格）。
     */
    public static final double UNIFORM_BAND = 2.0D;

    /**
     * 默认静滞核心半径（格）：主世界与下界 <b>0</b>（无平静区）；
     * 末地 JSON 覆盖为 300×0.75=225（主岛核心，保护龙战）。
     */
    public static final double CALM_RADIUS = 0.0D;
    /** 震中随机候选最远搜索半径基准（格）：候选最远 = 此值 × {@code RANDOM_MAX_R_FACTOR}。 */
    public static final double CRACK_END = 3072.0D;
    /** 历史常量（四带渐变的碎块带外沿）：现仅作文档对照保留，碎裂强度由 {@link #UNIFORM_BAND} 决定。 */
    public static final double FRAG_END = 6144.0D;

    /**
     * 撕裂深度满值（存活率 0 时的输出，负值 = 强制空气）：
     * 必须大于任何维度的原版 final_density 正峰（主世界 ~1.6 / 下界 ~2.5），
     * 取 4 保证完全撕裂区一定无地形。完好处输出 0（精确还原原版）。
     */
    public static final double TEAR_SCALE = 4.0D;

    /**
     * 裂缝阈值随带进度（t, thr）：ridge（=1-|n| 归一后）超过阈值处被撕开。
     * t=0 时阈值 2.0 = 平静区永无裂缝；0.10 处（≈577 格，用户调参：平静区外
     * 迅速达到原 3000 格处的破裂水平）收到 0.80（裂缝带窄缝，覆盖 ~14%），
     * 碎块带 0.45、碎屑带 0.28（缝宽可跨 → 宽 → 极宽）。
     * （阈值经 _tools/fracture_sim.py 体素模拟定值，任务 2.2）
     */
    public static final double[][] CRACK_THRESHOLD = {
            {0.00, 2.00}, {0.10, 0.80}, {1.00, 0.80}, {2.00, 0.45}, {3.00, 0.28}
    };

    /** 裂缝边缘软化宽窄（t, w）：窄值 = 接近阶跃、断面垂直锋利（用户调参：尖锐撕裂感）。 */
    public static final double[][] CRACK_WIDTH = {
            {0.00, 0.04}, {1.00, 0.03}, {2.00, 0.05}, {3.00, 0.06}
    };

    /** 板块死亡概率随带进度（t, p）：裂缝带 0，碎块带 0→0.60，碎屑带 0.70（用户调参：碎块密度优先于空旷感）。 */
    public static final double[][] PLATE_DEATH = {
            {0.00, 0.00}, {1.00, 0.00}, {2.00, 0.60}, {3.00, 0.62}
    };

    /** 碎屑子层死亡概率（t, p）：碎屑带内小 cell 碎裂，只留小碎块（用户调参：0.40 保留更多碎屑）。 */
    public static final double[][] FINE_DEATH = {
            {0.00, 0.00}, {1.50, 0.00}, {2.50, 0.20}, {3.00, 0.32}
    };

    /** 漂移幅度随带进度（t, 格）：平静 0 / 裂缝 ≤5 / 碎块 15~25 / 碎屑 30~40（spec）。 */
    public static final double[][] DRIFT_AMP = {
            {0.00, 0.0}, {1.00, 5.0}, {1.50, 15.0}, {2.00, 25.0}, {2.50, 32.0}, {3.00, 40.0}
    };

    /**
     * 末地专用：带进度地板（默认 0）。末地外岛要求直接按碎块带~碎屑带处理
     * （spec「末地碎裂应用」）—— 平静半径之外带进度从 2.0 起算。
     * 平静区（r ≤ calm）仍为 0（静滞优先，主岛完好）。
     */
    public static final double DEFAULT_BAND_FLOOR = 0.0D;

    /** 脊状噪声归一系数：|n|×此值 clamp 到 [0,1] 后取 1-|n|（四倍频程 + 域扭曲下模拟定值 3.2）。 */
    public static final double RIDGE_NORMALIZE = 3.2D;

    // ==========================================================================
    //  ★ 纸撕裂边缘（域扭曲 + 宽窄抖动，让裂缝线条呈撕纸毛边而非平滑圆弧）
    // ==========================================================================

    /** 域扭曲幅度（格）：裂缝脊线的采样坐标被高频噪声拉扯的最大距离 —— 撕扯弯折感。 */
    public static final double TEAR_WARP_AMPLITUDE = 10.0D;
    /** 宽窄抖动采样频率：裂缝宽度的豁口/收窄变化尺度（波长 ≈ 64/3.1 ≈ 20 格）。 */
    public static final double TEAR_WIDTH_JITTER_FREQ = 3.1D;

    // ==========================================================================
    //  ★ 震中常量（任务 4.1/4.2；文档 _docs/破碎世界.md 必须与这里一致）
    // ==========================================================================

    /**
     * 「被炸毁的倒数第二基地」叙事地标震中 —— <b>确定性</b>（不随种子）：
     * 位于静滞锚点（坐标原点）固定方位 {@value #LANDMARK_AZIMUTH_DEG}&deg;
     * （东北）、距离 {@value #LANDMARK_DISTANCE} 格（spec：2048~4096）。
     */
    public static final double LANDMARK_AZIMUTH_DEG = 45.0D;
    public static final double LANDMARK_DISTANCE = 2896.0D;

    /** 随机震中候选网格间距（格）：候选天然互距 ≥3072，满足 spec 间距 ≥1024。 */
    public static final double RANDOM_GRID = 3072.0D;
    /** 随机震中激活阈值：候选格中心的裂缝噪声采样超过它才激活（种子决定 → 异种子异分布；实测定值 0.15 ≈ 12 候选中 4 个激活）。 */
    public static final double RANDOM_ACTIVATE = 0.15D;
    /** 随机震中候选距锚点最近距离（× calm 半径），保证不落入平静区（spec）。 */
    public static final double RANDOM_MIN_R_FACTOR = 1.15D;
    /** 随机震中候选最远搜索半径系数（× 碎块带外沿），之外不再找。 */
    public static final double RANDOM_MAX_R_FACTOR = 2.0D;

    /** 震中加压半径（格）：中心向外渐变到 0。 */
    public static final double PRESSURE_RADIUS = 512.0D;
    /** 震中中心的存活率地板：碎裂显著加重但留少量残块（供幽匿侵蚀附着与攀爬）。 */
    public static final double SURVIVAL_FLOOR = 0.12D;

    // ==========================================================================
    //  Codec
    // ==========================================================================

    public static final MapCodec<FractureDensityFunction> MAP_CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    DensityFunction.NoiseHolder.CODEC.fieldOf("crack_noise").forGetter(f -> f.crackNoise),
                    DensityFunction.HOLDER_HELPER_CODEC.fieldOf("plates").forGetter(f -> f.plates),
                    Codec.doubleRange(0.03125D, 1.0D).optionalFieldOf("scale", 1.0D).forGetter(f -> f.scale),
                    Codec.doubleRange(0.0D, 3.0D).optionalFieldOf("band_floor", 0.0D).forGetter(f -> f.bandFloor),
                    Codec.doubleRange(0.0D, 4096.0D).optionalFieldOf("calm_radius", CALM_RADIUS).forGetter(f -> f.calmRadius)
            ).apply(instance, FractureDensityFunction::new));

    public static final KeyDispatchDataCodec<FractureDensityFunction> CODEC =
            KeyDispatchDataCodec.of(MAP_CODEC);

    private final DensityFunction.NoiseHolder crackNoise;
    private final DensityFunction plates;
    private final double scale;
    private final double bandFloor;
    private final double calmRadius;

    public FractureDensityFunction(DensityFunction.NoiseHolder crackNoise, DensityFunction plates, double scale) {
        this(crackNoise, plates, scale, DEFAULT_BAND_FLOOR, CALM_RADIUS);
    }

    public FractureDensityFunction(DensityFunction.NoiseHolder crackNoise, DensityFunction plates,
                                   double scale, double bandFloor) {
        this(crackNoise, plates, scale, bandFloor, CALM_RADIUS);
    }

    public FractureDensityFunction(DensityFunction.NoiseHolder crackNoise, DensityFunction plates,
                                   double scale, double bandFloor, double calmRadius) {
        this.crackNoise = crackNoise;
        this.plates = plates;
        this.scale = scale;
        this.bandFloor = bandFloor;
        this.calmRadius = calmRadius;
    }

    // ==========================================================================
    //  四带 / 曲线（PlateFieldLogic 等兄弟类共用）
    // ==========================================================================

    /**
     * 带进度：静滞核心（{@code calmRadius × scale}）内为 0（末地主岛保护），
     * 其余全图恒为 {@link #UNIFORM_BAND}（用户决定取消四带静滞渐变与平静区）。
     */
    public static double bandProgress(double x, double z, double scale) {
        return bandProgress(x, z, scale, DEFAULT_BAND_FLOOR, CALM_RADIUS);
    }

    /** 带进度（带地板/静滞半径版）。 */
    public static double bandProgress(double x, double z, double scale, double bandFloor, double calmRadius) {
        double r = Math.sqrt(x * x + z * z);
        if (r <= calmRadius * scale) {
            return 0.0D;
        }
        return Math.max(MaixrConfig.uniformBand(), bandFloor);
    }

    /** 分段线性曲线查值（pts 按 t 升序）。 */
    public static double curve(double[][] pts, double t) {
        if (t <= pts[0][0]) {
            return pts[0][1];
        }
        for (int i = 1; i < pts.length; i++) {
            if (t <= pts[i][0]) {
                double t0 = pts[i - 1][0], v0 = pts[i - 1][1];
                double t1 = pts[i][0], v1 = pts[i][1];
                return v0 + (v1 - v0) * (t - t0) / (t1 - t0);
            }
        }
        return pts[pts.length - 1][1];
    }

    // ==========================================================================
    //  震中（任务 4.1：3~5 随机种子点 + 1 确定性方位地标，碎裂局部乘压）
    // ==========================================================================

    /** 地标震中坐标（相对静滞锚点；方块坐标取整便于文档对账）。 */
    public static double landmarkX() {
        return Math.cos(Math.toRadians(LANDMARK_AZIMUTH_DEG)) * LANDMARK_DISTANCE;
    }

    public static double landmarkZ() {
        return Math.sin(Math.toRadians(LANDMARK_AZIMUTH_DEG)) * LANDMARK_DISTANCE;
    }

    /**
     * 震中<b>加压强度</b> ∈ [0,1]：0 = 不加压；1 = 震中中心（碎裂压到存活率地板）。
     *
     * <h2>分布</h2>
     * <ul>
     *   <li><b>1 个确定性叙事地标</b>：固定方位/距离（见常量），任何种子必存在
     *       （spec「地标震中确定存在」）；</li>
     *   <li><b>3~5 个随机震中</b>：{@value #RANDOM_GRID} 格网格候选，候选格中心用
     *       <b>带种子的裂缝噪声</b>低频采样判定激活（异种子异分布，spec「种子决定」）；
     *       距锚点 {@code 1.15×calm} 内的候选被拒绝（spec「不落入任何静滞区」）。</li>
     * </ul>
     *
     * <p>多点取最强加压；激活判定用极低频（网格间距 × 极小系数）保证相邻候选
     * 去相关。纯坐标函数，同种子确定性由 spec 保证。</p>
     *
     * <p>公开给 {@link MaixrDressingPass} 复用（幽匿侵蚀按同一加压强度衰减）。</p>
     */
    public double epicenterPress(double x, double z, double scale) {
        // ① 确定性方位地标（scale 折算以适配下界/末地维度）
        double lx = landmarkX() * scale;
        double lz = landmarkZ() * scale;
        double press = 1.0D - PlateFieldLogic.smoothstep(0.0D, PRESSURE_RADIUS * scale,
                Math.hypot(x - lx, z - lz));

        // ② 随机震中：邻域 3×3 候选格
        double grid = RANDOM_GRID * scale;
        long gx = Math.floorDiv((long) Math.floor(x), (long) grid);
        long gz = Math.floorDiv((long) Math.floor(z), (long) grid);
        double calm = this.calmRadius * scale * RANDOM_MIN_R_FACTOR;
        double maxR = CRACK_END * scale * RANDOM_MAX_R_FACTOR;
        for (long dz = -1; dz <= 1; dz++) {
            for (long dx = -1; dx <= 1; dx++) {
                long cx = gx + dx, cz = gz + dz;
                double ex = (cx + 0.5D) * grid, ez = (cz + 0.5D) * grid;
                double r = Math.hypot(ex, ez);
                if (r < calm || r > maxR) {
                    continue;   // 不落入静滞区 / 范围外
                }
                // 激活：候选格中心低频噪声（种子决定）；采样坐标与格心绑定保证同候选同结果
                double n = this.crackNoise.getValue(ex * 0.02D, 0.0D, ez * 0.02D);
                if (n <= RANDOM_ACTIVATE) {
                    continue;
                }
                double p = 1.0D - PlateFieldLogic.smoothstep(0.0D, PRESSURE_RADIUS * scale,
                        Math.hypot(x - ex, z - ez));
                press = Math.max(press, p);
            }
        }
        return press;
    }

    // ==========================================================================
    //  DensityFunction
    // ==========================================================================

    /**
     * ★ 列缓存（memoize）：本函数纯 2D（y 无关），但 {@code NoiseChunk} 的
     * {@code fillArray} 批量路径会<b>绕过 JSON 里 {@code cache_2d} 包装器的
     * 单条目缓存</b>直接透传到这里 —— {@code fillAllDirectly} 以 y 为外层、
     * xz 为内层逐点遍历，实测单区块生成管线 fracture 被调 ~300 万次
     * （64 槽时 miss 64%）。直接映射槽位按碰撞对期望定档：
     * 逐方块填充阶段每批次 ~256 列，槽位数 N 下两列共享槽位的期望对数
     * ≈ C(256,2)/N —— 256 槽 ≈ 94 对（~37% 列两两互踢，JFR 实测逐行风暴
     * ~3.6 万次重复求值/区块），4096 槽 ≈ 8 对（风暴量级回落 ~12 倍），
     * 内存 4096 × (8+8) B = 64KB/线程/实例；16384 槽 ≈ 2 对（192KB）作为
     * 保留升档（见变更 2026-10-25-fracture-perf-leaps design D1）。
     *
     * <p>线程安全：worker 池并发调用 —— 用 <b>per-instance ThreadLocal</b>
     * 保证「位置↔值」原子配对（静态缓存会跨实例污染：下界/末地/离线掩码的
     * 同列输出不同）。mapAll 重建的副本自带冷缓存，无正确性影响。</p>
     */
    private static final int CACHE_SLOTS = 4096;
    private final ThreadLocal<long[]> columnCachePos =
            ThreadLocal.withInitial(() -> fillNO_CACHE(new long[CACHE_SLOTS]));
    private final ThreadLocal<double[]> columnCacheValue =
            ThreadLocal.withInitial(() -> new double[CACHE_SLOTS]);
    /** 「空槽」哨兵键：用 (x=0,z=0) 永不生成的组合避免真实键碰撞。 */
    private static long[] fillNO_CACHE(long[] slots) {
        java.util.Arrays.fill(slots, Long.MIN_VALUE);
        return slots;
    }

    /** 列缓存键：把 (x,z) 打包成一个 64 位键。 */
    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /**
     * 槽位索引位移：{@code 64 - log2(CACHE_SLOTS)}。
     *
     * <p>★ 历史 bug（2026-10-31 修正）：原实现写死 {@code >>> 58}，只保留乘积累加的
     * <b>最高 6 位</b>，再与 {@code CACHE_SLOTS - 1} 相与后<b>实际只有 64 个槽位生效</b>
     * —— 4096 槽的容量与 64KB 内存开销都白付了，等价于一直跑在 64 槽档位。
     * 这也解释了「L1 扩容后墙钟无可测变化」：容量从未真正扩大。</p>
     */
    private static final int SLOT_SHIFT = 64 - Integer.numberOfTrailingZeros(CACHE_SLOTS);

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        int x = context.blockX();
        int z = context.blockZ();
        long key = columnKey(x, z);
        int slot = (int) (key * 0x9E3779B97F4A7C15L >>> SLOT_SHIFT) & (CACHE_SLOTS - 1);
        long[] keys = this.columnCachePos.get();
        if (keys[slot] == key) {
            return this.columnCacheValue.get()[slot];
        }
        double result = this.computeUncached(context);
        keys[slot] = key;
        this.columnCacheValue.get()[slot] = result;
        return result;
    }

    /** 实际求值（原 compute 逻辑，纯 2D：撕裂深度只由 (x,z) 决定）。 */
    private double computeUncached(DensityFunction.FunctionContext context) {
        double x = context.blockX();
        double z = context.blockZ();

        double band = bandProgress(x, z, this.scale, this.bandFloor, this.calmRadius);
        if (band <= 0.0D) {
            return 0.0D;   // 平静区：撕裂深度 0 = 精确还原原版
        }

        // ① 脊状裂缝噪声 + 纸撕裂边缘：脊线坐标经高频域扭曲（撕扯弯折），
        //    阈值宽度再叠一层宽窄抖动（豁口与收窄突变）—— 线条呈撕纸毛边
        double warpX = this.crackNoise.getValue(x, 40.7D, z);
        double warpZ = this.crackNoise.getValue(x, -33.3D, z);
        double n = this.crackNoise.getValue(
                x + warpX * TEAR_WARP_AMPLITUDE, 0.0D, z + warpZ * TEAR_WARP_AMPLITUDE);
        double ridge = 1.0D - Math.min(1.0D, Math.abs(n) * RIDGE_NORMALIZE);
        double thr = curve(CRACK_THRESHOLD, band);
        double w = curve(CRACK_WIDTH, band);
        double jitter = this.crackNoise.getValue(
                x * TEAR_WIDTH_JITTER_FREQ, 77.7D, z * TEAR_WIDTH_JITTER_FREQ);
        double wj = w * (0.45D + 0.45D * (jitter + 1.0D));
        double crackMask = 1.0D - PlateFieldLogic.smoothstep(thr - wj, thr + wj, ridge);

        // ② 板块存活率（碎块带起有死亡；碎屑带乘碎屑子层）
        double plate = this.plates.compute(context);

        // ③ 震中加压：震中中心存活率压到 SURVIVAL_FLOOR（显著加重但不归零，
        //    留少量残块供幽匿侵蚀附着与攀爬），向外渐变回原碎裂水平
        double base = crackMask * plate;
        double press = epicenterPress(x, z, this.scale);
        double survival = net.minecraft.util.Mth.lerp(press, base, SURVIVAL_FLOOR);

        // 撕裂深度：存活率 1 → 0（精确还原原版），0 → -TEAR_SCALE（强制空气）
        return (survival - 1.0D) * TEAR_SCALE;
    }

    @Override
    public double minValue() {
        return -TEAR_SCALE;
    }

    @Override
    public double maxValue() {
        return 0.0D;
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        // ★ 种子注入：NoiseWiringHelper 靠 mapAll 下钻到 NoiseHolder 换成带种子实例
        //   （design D7 第 2 条）—— 不重建自身的话裂缝噪声恒 0
        return new FractureDensityFunction(
                visitor.visitNoise(this.crackNoise),
                this.plates.mapAll(visitor),
                this.scale, this.bandFloor, this.calmRadius);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
