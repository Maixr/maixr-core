package com.maixr;

/**
 * 「破碎」碎裂场的<b>板块场</b>纯函数核心 —— 2D Voronoi + cell 哈希 + 碎屑子层。
 *
 * <p>被三个注册 DF 类型共用（{@link PlateFieldDensityFunction} 输出板块存活率、
 * {@link PlateDisplaceDensityFunction} 输出板块漂移量、{@link FractureDensityFunction}
 * 组合两者），逻辑集中在这里保证同一坐标三处判定永远一致（spec 硬要求）。</p>
 *
 * <h2>模型</h2>
 * <ul>
 *   <li><b>2D Voronoi</b>：cell 尺寸 {@value #CELL_SIZE} 格，每 cell 一个哈希抖动的
 *       板块中心；扫描 3×3 邻域取最近（f1）与次近（f2）中心。</li>
 *   <li><b>板块存活</b>：每 cell 按哈希死亡（死亡概率随四带距离升高，见
 *       {@link FractureDensityFunction#curve}）；存活率在板块边界附近按
 *       {@code f1/f2} 比值在死亡板块与存活邻居之间平滑过渡 ——
 *       死亡板块的"海岸线"沿抖动 Voronoi 边界延伸，<b>不是</b> cell 网格直线
 *       （旧渐变虚空方形洞的教训）。</li>
 *   <li><b>碎屑子层</b>：碎屑带里存活板块再乘一层 cell ~{@value #FINE_CELL} 格的
 *       小 Voronoi 碎裂，只留小碎块（spec「仅存小碎块与碎屑」）。</li>
 *   <li><b>漂移</b>：每板块一个哈希方向角 + 幅度（按带 {@code 0/5/15..25/30..40}，
 *       见 {@link FractureDensityFunction#DRIFT_AMP}）+ 垂直漂移；板块内部刚性、
 *       边界过渡带平滑混合 —— 过渡带即相邻断层的错位缝。</li>
 * </ul>
 *
 * <p>哈希仅做 cell 内抖动（无种子），整体形态随种子由带种子的裂缝噪声承担 ——
 * 见 design D3。所有函数都是纯坐标函数，同种子确定性由 spec 保证。</p>
 */
public final class PlateFieldLogic {

    /** 大板块 cell 尺寸（格）。 */
    public static final int CELL_SIZE = 64;
    /** 碎屑子层 cell 尺寸（格）。 */
    public static final int FINE_CELL = 8;
    /** 板块中心在 cell 内的抖动幅度（0.65 = 海岸线更不规则，模拟定值）。 */
    private static final double CENTER_JITTER = 0.65;
    /** f1/f2 超过此值开始向邻居过渡（板块边界过渡带内沿；收窄 = 海岸线悬崖化）。 */
    private static final double EDGE_SOFT_IN = 0.88;
    /** f1/f2 达到此值完全按邻居算（板块边界过渡带外沿）。 */
    private static final double EDGE_SOFT_OUT = 0.99;

    // ---- cell 哈希盐（不同用途绝不复用，避免相关性） ----
    private static final long SALT_CENTER_X = 0x51ED270B;
    private static final long SALT_CENTER_Z = 0x2545F491;
    private static final long SALT_DEATH = 0x9E3779B9;
    private static final long SALT_FINE_DEATH = 0x68BC21EB;
    /** 碎屑子层抖动中心（与死亡判定分开，避免相关）。 */
    private static final long SALT_FINE_CENTER_X = 0x1B873593;
    private static final long SALT_FINE_CENTER_Z = 0xCC9E2D51;
    private static final long SALT_DRIFT_DIR = 0xC2B2AE3D;
    private static final long SALT_DRIFT_MAG = 0x27D4EB2F;
    private static final long SALT_DRIFT_DY = 0x165667B1;
    private static final long SALT_TILT = 0x85EBCA77;

    private PlateFieldLogic() {}

    /** 64 位坐标哈希 → [0,1)。确定性（同输入永远同输出）。 */
    public static double hash01(long a, long b, long salt) {
        long h = a * 0x9E3779B185EBCA87L ^ b * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 32;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** 板块 cell 的抖动中心 x（方块坐标）。 */
    // ==================================================================
    //  公开只读访问器：给「碎片连桥」用（FragmentBridges）
    // ==================================================================

    /** 碎片（Voronoi cell）中心 X（方块坐标）。 */
    public static double plateCenterX(long cellX, long cellZ) {
        return centerX(cellX, cellZ);
    }

    /** 碎片（Voronoi cell）中心 Z（方块坐标）。 */
    public static double plateCenterZ(long cellX, long cellZ) {
        return centerZ(cellX, cellZ);
    }

    /** 这个碎片是否存活（有地形）。 */
    public static boolean plateAlive(long cellX, long cellZ, double deathProb) {
        return alive(cellX, cellZ, deathProb);
    }

    private static double centerX(long cellX, long cellZ) {
        return (cellX + 0.5D + (hash01(cellX, cellZ, SALT_CENTER_X) - 0.5D) * CENTER_JITTER) * CELL_SIZE;
    }

    /** 板块 cell 的抖动中心 z（方块坐标）。 */
    private static double centerZ(long cellX, long cellZ) {
        return (cellZ + 0.5D + (hash01(cellX, cellZ, SALT_CENTER_Z) - 0.5D) * CENTER_JITTER) * CELL_SIZE;
    }

    /** 单个板块 cell 是否存活（deathProb ∈ [0,1]）。 */
    private static boolean alive(long cellX, long cellZ, double deathProb) {
        return deathProb <= 0.0D || hash01(cellX, cellZ, SALT_DEATH) >= deathProb;
    }

    /** 碎屑子层小 cell 是否存活。 */
    private static boolean fineAlive(int fx, int fz, double fineDeathProb) {
        return fineDeathProb <= 0.0D || hash01(fx, fz, SALT_FINE_DEATH) >= fineDeathProb;
    }

    /** 碎屑 cell 的抖动中心 x（方块坐标）。 */
    private static double fineCenterX(long fx, long fz) {
        return (fx + 0.5D + (hash01(fx, fz, SALT_FINE_CENTER_X) - 0.5D) * CENTER_JITTER) * FINE_CELL;
    }

    /** 碎屑 cell 的抖动中心 z（方块坐标）。 */
    private static double fineCenterZ(long fx, long fz) {
        return (fz + 0.5D + (hash01(fx, fz, SALT_FINE_CENTER_Z) - 0.5D) * CENTER_JITTER) * FINE_CELL;
    }

    /**
     * ★ 碎屑子层存活率 ∈ [0,1] —— <b>抖动 Voronoi</b>，不是 8×8 硬方格。
     *
     * <h2>为什么必须平滑（玩家实测 bug）</h2>
     *
     * <p>旧实现是 {@code fineAlive(cell) ? 1 : 0} —— 按 <b>8×8 格</b>整格判定，
     * 于是出现<b>轴对齐的正方形空洞</b>（8×8 正好是 1/4 区块，玩家截图里那种
     * “1/4 正方形空区块”）。默认带进度 2.0 下 {@code FINE_DEATH} 算得 0.10，
     * 即约 10% 的格子被整格挖空，观感非常“人工”。</p>
     *
     * <p>这里改成与大板块同款做法：cell 中心哈希抖动 + 3×3 邻域取 f1/f2，
     * 用 {@code f1/f2} 比值在“本格存活/邻格存活”之间平滑过渡 ——
     * 边界沿抖动 Voronoi 线走，<b>不再是方格</b>，且边缘是渐变侵蚀坡而非垂直断面。</p>
     */
    private static double fineSurvival(double x, double z, double fineDeathProb) {
        if (fineDeathProb <= 0.0D) {
            return 1.0D;
        }
        if (fineDeathProb >= 1.0D) {
            return 0.0D;
        }
        long baseX = Math.floorDiv((long) Math.floor(x), FINE_CELL);
        long baseZ = Math.floorDiv((long) Math.floor(z), FINE_CELL);

        double f1 = Double.MAX_VALUE, f2 = Double.MAX_VALUE;
        long c1x = 0, c1z = 0, c2x = 0, c2z = 0;
        for (long dz = -1; dz <= 1; dz++) {
            for (long dx = -1; dx <= 1; dx++) {
                long cx = baseX + dx, cz = baseZ + dz;
                double ddx = x - fineCenterX(cx, cz);
                double ddz = z - fineCenterZ(cx, cz);
                double d = ddx * ddx + ddz * ddz;
                if (d < f1) {
                    f2 = f1;
                    c2x = c1x;
                    c2z = c1z;
                    f1 = d;
                    c1x = cx;
                    c1z = cz;
                } else if (d < f2) {
                    f2 = d;
                    c2x = cx;
                    c2z = cz;
                }
            }
        }

        double edge = smoothstep(EDGE_SOFT_IN, EDGE_SOFT_OUT, Math.sqrt(f1) / Math.sqrt(f2));
        double own = fineAlive((int) c1x, (int) c1z, fineDeathProb) ? 1.0D : 0.0D;
        double other = fineAlive((int) c2x, (int) c2z, fineDeathProb) ? 1.0D : 0.0D;
        return own + (other - own) * edge;
    }

    /**
     * ★ survival 静态列缓存（design D2，2026-10-25 优化变更）：本函数是纯坐标函数，
     * 但噪声块、dressing、离线 getBaseHeight 各调用方对同一列重复求值（JFR
     * 2026-10-25：fineSurvival 5% + survival 3%）。静态 per-thread 直接映射列缓存
     * 把跨调用方重复收敛为每列一次；值恒等（纯函数，缓存值=重算值）。
     *
     * <p>键 = cfgCode(scale, bandFloor, calmRadius) &lt;&lt; 52 ‖ (x,26bit) ‖ (z,26bit)：
     * 参数三元组经 record 精确相等取恒等码（不同维度折算/静滞参数不串值）。
     * 直接映射槽位模式出处：{@link FractureDensityFunction} 的 256 槽列缓存
     * （已在线上验证；槽位错位只退化为重算，值仍正确）。</p>
     */
    private static final int SURVIVAL_CACHE_SLOTS = 2048;
    private static final ThreadLocal<long[]> SURVIVAL_CACHE_KEYS =
            ThreadLocal.withInitial(() -> fillNoCache(new long[SURVIVAL_CACHE_SLOTS]));
    private static final ThreadLocal<double[]> SURVIVAL_CACHE_VALS =
            ThreadLocal.withInitial(() -> new double[SURVIVAL_CACHE_SLOTS]);

    private static long[] fillNoCache(long[] slots) {
        java.util.Arrays.fill(slots, Long.MIN_VALUE);
        return slots;
    }

    /** 参数三元组恒等码（record 精确相等，无碰撞）；码序号原子分配。 */
    private record SurvivalCfg(double scale, double bandFloor, double calmRadius) {}

    private static final java.util.Map<SurvivalCfg, Integer> SURVIVAL_CFG_CODES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger SURVIVAL_CFG_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private static long survivalKey(double x, double z, double scale, double bandFloor, double calmRadius) {
        int code = SURVIVAL_CFG_CODES.computeIfAbsent(
                new SurvivalCfg(scale, bandFloor, calmRadius), k -> SURVIVAL_CFG_SEQ.incrementAndGet());
        return ((long) code << 52)
                | (((long) Math.floor(x) & 0x3FFFFFFL) << 26)
                | ((long) Math.floor(z) & 0x3FFFFFFL);
    }

    /**
     * 板块存活率 ∈ [0,1]：死亡板块内部 → 0；存活板块内部 → 1；
     * 板块边界过渡带在死亡/存活之间平滑（海岸线沿抖动 Voronoi 边界）。
     * 碎屑带额外乘碎屑子层。带静态列缓存，实际求值在 {@link #survivalUncached}。
     */
    public static double survival(double x, double z, double scale, double bandFloor, double calmRadius) {
        long key = survivalKey(x, z, scale, bandFloor, calmRadius);
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 53) & (SURVIVAL_CACHE_SLOTS - 1);
        long[] keys = SURVIVAL_CACHE_KEYS.get();
        if (keys[slot] == key) {
            return SURVIVAL_CACHE_VALS.get()[slot];
        }
        double value = survivalUncached(x, z, scale, bandFloor, calmRadius);
        keys[slot] = key;
        SURVIVAL_CACHE_VALS.get()[slot] = value;
        return value;
    }

    private static double survivalUncached(double x, double z, double scale, double bandFloor, double calmRadius) {
        double band = FractureDensityFunction.bandProgress(x, z, scale, bandFloor, calmRadius);
        double deathProb = FractureDensityFunction.curve(FractureDensityFunction.PLATE_DEATH, band);
        if (deathProb >= 1.0D) {
            return 0.0D;
        }
        if (deathProb <= 0.0D) {
            return 1.0D;
        }
        long baseX = Math.floorDiv((long) Math.floor(x), CELL_SIZE);
        long baseZ = Math.floorDiv((long) Math.floor(z), CELL_SIZE);

        double f1 = Double.MAX_VALUE, f2 = Double.MAX_VALUE;
        long c1x = 0, c1z = 0, c2x = 0, c2z = 0;
        for (long dz = -1; dz <= 1; dz++) {
            for (long dx = -1; dx <= 1; dx++) {
                long cx = baseX + dx, cz = baseZ + dz;
                double ddx = x - centerX(cx, cz);
                double ddz = z - centerZ(cx, cz);
                double d = ddx * ddx + ddz * ddz;
                if (d < f1) {
                    f2 = f1;
                    c2x = c1x;
                    c2z = c1z;
                    f1 = d;
                    c1x = cx;
                    c1z = cz;
                } else if (d < f2) {
                    f2 = d;
                    c2x = cx;
                    c2z = cz;
                }
            }
        }

        double ratio = Math.sqrt(f1) / Math.sqrt(f2);
        double edge = smoothstep(EDGE_SOFT_IN, EDGE_SOFT_OUT, ratio);

        // ★ 碎屑子层：抖动 Voronoi 平滑（旧版 8×8 整格判定会挖出 1/4 区块的方洞）
        double fineDeath = FractureDensityFunction.curve(FractureDensityFunction.FINE_DEATH, band);
        double fine = fineSurvival(x, z, fineDeath);

        double own = alive(c1x, c1z, deathProb) ? fine : 0.0D;
        double other = alive(c2x, c2z, deathProb) ? fine : 0.0D;
        return own + (other - own) * edge;
    }

    /** 漂移量（方块）：dx/dz 水平漂移，dy 垂直漂移（含倾斜剪切，默认 0）。 */
    public record Drift(double dx, double dy, double dz) {
        static final Drift ZERO = new Drift(0, 0, 0);
    }

    /**
     * (x,z) 处的板块漂移量。
     *
     * <p>幅度按四带取 {@code 0/5/15..25/30..40}（{@link FractureDensityFunction#DRIFT_AMP}），
     * 再乘维度折算 {@code scale}（下界 1/8）。板块内部刚性、边界过渡带与邻居平滑混合 ——
     * 相邻板块漂移不同时，过渡带就是断层错位缝（缝内本就无地形）。</p>
     *
     * @param tilt 倾斜剪切幅度（方块/plate 宽度）；0 = 关闭（spec：默认关闭）
     */
    public static Drift drift(double x, double z, double scale, double tilt, double bandFloor, double calmRadius) {
        double band = FractureDensityFunction.bandProgress(x, z, scale, bandFloor, calmRadius);
        double amp = FractureDensityFunction.curve(FractureDensityFunction.DRIFT_AMP, band) * scale;
        if (amp <= 0.0D) {
            return Drift.ZERO;
        }
        long baseX = Math.floorDiv((long) Math.floor(x), CELL_SIZE);
        long baseZ = Math.floorDiv((long) Math.floor(z), CELL_SIZE);

        double f1 = Double.MAX_VALUE, f2 = Double.MAX_VALUE;
        long c1x = 0, c1z = 0, c2x = 0, c2z = 0;
        for (long dz = -1; dz <= 1; dz++) {
            for (long dx = -1; dx <= 1; dx++) {
                long cx = baseX + dx, cz = baseZ + dz;
                double ddx = x - centerX(cx, cz);
                double ddz = z - centerZ(cx, cz);
                double d = ddx * ddx + ddz * ddz;
                if (d < f1) {
                    f2 = f1;
                    c2x = c1x;
                    c2z = c1z;
                    f1 = d;
                    c1x = cx;
                    c1z = cz;
                } else if (d < f2) {
                    f2 = d;
                    c2x = cx;
                    c2z = cz;
                }
            }
        }
        double edge = smoothstep(EDGE_SOFT_IN, EDGE_SOFT_OUT, Math.sqrt(f1) / Math.sqrt(f2));
        Drift a = driftOf(c1x, c1z, x, z, amp, tilt);
        if (edge <= 0.0D) {
            return a;
        }
        Drift b = driftOf(c2x, c2z, x, z, amp, tilt);
        return new Drift(
                a.dx() + (b.dx() - a.dx()) * edge,
                a.dy() + (b.dy() - a.dy()) * edge,
                a.dz() + (b.dz() - a.dz()) * edge);
    }

    /** 单板块的漂移向量：哈希方向角 + 幅度抖动 + 垂直漂移 + 倾斜剪切。 */
    private static Drift driftOf(long cx, long cz, double x, double z, double amp, double tilt) {
        double angle = hash01(cx, cz, SALT_DRIFT_DIR) * Math.PI * 2.0D;
        double mag = amp * (0.8D + 0.4D * hash01(cx, cz, SALT_DRIFT_MAG));
        double dx = Math.cos(angle) * mag;
        double dz = Math.sin(angle) * mag;
        double dy = (hash01(cx, cz, SALT_DRIFT_DY) * 2.0D - 1.0D) * amp * 0.8D;
        if (tilt != 0.0D) {
            // 倾斜剪切：每板块一条过板块中心的斜线，沿板块自身方向下倾/上翘
            double pcx = centerX(cx, cz);
            double pcz = centerZ(cx, cz);
            double dirX = Math.cos(angle + Math.PI / 2.0D);
            double dirZ = Math.sin(angle + Math.PI / 2.0D);
            double t = ((x - pcx) * dirX + (z - pcz) * dirZ) / CELL_SIZE;
            dy += tilt * (hash01(cx, cz, SALT_TILT) * 2.0D - 1.0D) * t;
        }
        return new Drift(dx, dy, dz);
    }

    /** Hermite 平滑阶跃：0（x≤lo）→ 1（x≥hi）。 */
    public static double smoothstep(double lo, double hi, double x) {
        if (x <= lo) return 0.0D;
        if (x >= hi) return 1.0D;
        double t = (x - lo) / (hi - lo);
        return t * t * (3.0D - 2.0D * t);
    }
}
