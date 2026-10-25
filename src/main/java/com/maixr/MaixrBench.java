package com.maixr;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 「生成性能测量基准」—— {@code /maixr bench} 随机传送基准
 * （spec: {@code maixr-bench-commands}，操作手册 {@code _docs/生成性能测量.md}）。
 *
 * <h2>测量口径</h2>
 * <ul>
 *   <li><b>墙钟</b>：{@code teleportTo} 调用瞬间 → 目的地区块 {@code getChunkNow != null}
 *       （{@code LevelChunk} 仅在 FULL 存在）的首个 Post tick。tick 粒度精度（~50ms），
 *       且<b>含网络往返</b>：同维度传送走 {@code connection.teleport}，目的地区块的玩家
 *       ticket 要等客户端确认传送后才迁移，所以 dedicated server 上测得值略偏高
 *       （误差方向：测得值 ≥ 纯生成成本）。</li>
 *   <li><b>MSPT 峰值</b>：窗口内每 tick 取 vanilla 环形缓冲最新值（见下）的最大值。</li>
 *   <li><b>恢复时长</b>：末次传送完成后，连续 {@value #SETTLE_OK_TICKS} tick
 *       MSPT ≤ 基线 × {@value #RECOVERY_FACTOR}（或超时 {@value #SETTLE_TIMEOUT_TICKS} tick）。</li>
 *   <li><b>过采样</b>：碎裂世界随机列大量落在死板块/裂缝上（首轮实测 4/4 全虚列），
 *       「次数」按<b>成功传送数</b>计，候选最多自动尝试 次数 × {@value #OVERSAMPLE_FACTOR}，
 *       全虚列/探高异常即跳过换下一候选（候选序号仍纯由种子+参数派生，确定性不变）。</li>
 *   <li>基线：会话开始后先静默采 {@value #BASELINE_TICKS} tick 取中位数。</li>
 *   <li><b>误差与陷阱</b>：region 文件缓存会让第二轮同坐标明显变快 —— 对比实验必须
 *       全新世界目录；坐标序列确定性 ≠ 耗时确定性（spec 只承诺前者）。探测/轮询开销
 *       不从测量中扣除（单列离线调用，毫秒级）。</li>
 * </ul>
 *
 * <h2>1.21.1 API 核实结论（任务 1.1，build/moddev 反编译源核实）</h2>
 *
 * <p>1.21.1 没有 {@code TickTimes} 类。tick 耗时存在 {@code MinecraftServer} 的
 * <b>private long[100] 环形缓冲 {@code tickTimesNanos}</b>，在 {@code tickServer}
 * 末尾（{@code fireServerTickPost} <b>之前</b>）按 {@code tickCount % 100} 写入
 * 「刚结束这个 tick」的总纳秒。公开读取：
 * {@code getTickTimesNanos()} / {@code getAverageTickTimeNanos()} /
 * {@code getCurrentSmoothedTickTime()}。所以 Post 监听里
 * {@code getTickTimesNanos()[getTickCount() % 100]} 就是刚结束 tick 的耗时 ——
 * <b>采用 vanilla 缓冲，D4 的自测降级路径不需要</b>。</p>
 *
 * <h2>实现约定</h2>
 * <ul>
 *   <li>目的地只由「世界种子 + 参数（次数/半径区间）+ 序号」派生
 *       （{@link #rngFor}），同种子同参数必出同坐标序列；</li>
 *   <li>落点 Y 用 {@code NoiseBasedChunkGenerator#getBaseHeight(WORLD_SURFACE)}
 *       离线求值（与 {@link BrokenSpawnSearch} 第三级同款 API，不接触区块系统），
 *       失败/全虚列跳过该序号继续；</li>
 *   <li>采样器（{@link Sampler}）会话开始注册、结束/stop/玩家退出/服务器停止注销，
 *       空闲零开销；全部状态只在服务端主线程读写（命令与 Post 事件都在主线程）；</li>
 *   <li>不写任何持久化数据，不放置/破坏方块（玩家正常落地除外）。</li>
 * </ul>
 */
public final class MaixrBench {

    /** 默认目的地区间下限（格，距原点）：远离出生点预生成区（文档口径与这里一致）。 */
    public static final int DEFAULT_MIN_R = 3000;
    /** 默认目的地区间上限（格）。 */
    public static final int DEFAULT_MAX_R = 20000;
    /** 单次会话最大传送次数（防手滑 10 万次拖垮主线程；超过按参数错误拒绝）。 */
    public static final int MAX_COUNT = 1000;
    /** 基线采样 tick 数。 */
    static final int BASELINE_TICKS = 10;
    /** 判定「MSPT 已回落」需要的连续 tick 数。 */
    static final int SETTLE_OK_TICKS = 20;
    /** 回落等待超时（tick）。 */
    static final int SETTLE_TIMEOUT_TICKS = 200;
    /** 回落判定：MSPT ≤ 基线 × 此系数（spec「基线 ±20% 内」）。 */
    static final double RECOVERY_FACTOR = 1.2;
    /**
     * 目的地过采样系数：碎裂世界随机列大量落在死板块/裂缝上（uniformBand=2.0 时
     * 首轮实测 4/4 全虚列），「次数」按<b>成功传送数</b>计，候选最多自动尝试
     * 次数 × 此系数，失败（全虚列/探高异常）即跳过换下一个候选。
     */
    public static final int OVERSAMPLE_FACTOR = 3;

    private static Session session;

    private MaixrBench() {}

    // ------------------------------------------------------------------
    //  命令入口（MaixrCommands 调用，均在服务端主线程）
    // ------------------------------------------------------------------

    /**
     * 启动基准会话。返回命令结果（1 成功 / 0 失败，失败已发提示）。
     * 校验顺序：会话互斥 → 参数合法 → 玩家执行 → 主世界。
     */
    public static int start(CommandSourceStack source, int count, int minR, int maxR) {
        if (!source.hasPermission(2)) {
            source.sendFailure(Component.literal("§c权限不足：/maixr bench 需要管理员（OP）权限"));
            return 0;
        }
        if (session != null) {
            source.sendFailure(Component.literal("§c已有基准会话运行中，可先 /maixr bench stop 中断"));
            return 0;
        }
        if (count < 1 || count > MAX_COUNT) {
            source.sendFailure(Component.literal("§c次数必须在 1~" + MAX_COUNT + " 之间"));
            return 0;
        }
        if (minR < 0 || minR > maxR) {
            source.sendFailure(Component.literal("§c最近距离不能大于最远距离，且距离不能为负"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§c/maixr bench tp 需要由玩家执行（控制台/命令方块不可用）"));
            return 0;
        }
        if (!(player.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            source.sendFailure(Component.literal("§c基准仅支持主世界（下界/末地非本模组生成，测量无意义）"));
            return 0;
        }
        session = new Session(player, level, count, minR, maxR);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(session.sampler);
        MaixrMod.LOGGER.info("[maixr 基准] 会话启动：{} 次，半径 {}~{} 格", count, minR, maxR);
        return 1;
    }

    /** 中断当前会话并输出已完成部分。返回命令结果。 */
    public static int stop(CommandSourceStack source) {
        if (!source.hasPermission(2)) {
            source.sendFailure(Component.literal("§c权限不足：/maixr bench 需要管理员（OP）权限"));
            return 0;
        }
        Session s = session;
        if (s == null) {
            source.sendSuccess(() -> Component.literal("§7当前没有进行中的基准会话"), false);
            return 0;
        }
        s.finish("手动 /maixr bench stop");
        return 1;
    }

    // ------------------------------------------------------------------
    //  会话
    // ------------------------------------------------------------------

    /** 单次传送的测量行；{@code skipReason != null} 表示该序号被跳过。 */
    private record Row(int index, int x, int y, int z, long wallNanos, long peakNanos, String skipReason) {}

    private enum Phase { BASELINE, TRAVEL, SETTLE }

    private static final class Session {
        final ServerPlayer player;
        final ServerLevel level;
        final int count;
        final int minR;
        final int maxR;
        final long seedBase;
        final Sampler sampler = new Sampler();
        final List<Row> rows = new ArrayList<>();
        /** 全程（所有传送窗口）的 MSPT 峰值，纳秒。 */
        long overallPeakNanos;
        double baselineMs;
        Phase phase = Phase.BASELINE;
        final long[] baselineSamples = new long[BASELINE_TICKS];
        int baselineLeft = BASELINE_TICKS;
        /** 已派生的目的地候选数（含跳过）。 */
        int attempts;
        /** 已成功落地的传送数。 */
        int successCount;
        /** 候选尝试上限 = count × OVERSAMPLE_FACTOR。 */
        final int maxAttempts;
        // 当前目的地
        int destX, destY, destZ;
        ChunkPos destChunk;
        long t0;
        long windowPeakNanos;
        // SETTLE
        int settleOkTicks;
        int settleTicks;
        long settleStartNanos;

        Session(ServerPlayer player, ServerLevel level, int count, int minR, int maxR) {
            this.player = player;
            this.level = level;
            this.count = count;
            this.minR = minR;
            this.maxR = maxR;
            this.maxAttempts = count * OVERSAMPLE_FACTOR;
            // 目的地派生基：只含世界种子与参数（spec「确定性派生」，不含任何运行时随机源）
            this.seedBase = level.getSeed() * 31L + (long) minR * 100003L + (long) maxR * 1000003L;
        }

        void tick(MinecraftServer server) {
            // Post 事件时，环形缓冲 [tickCount % 100] 已是刚结束 tick 的总纳秒（见类注释）
            long lastTickNanos = server.getTickTimesNanos()[server.getTickCount() % 100];
            switch (this.phase) {
                case BASELINE -> this.tickBaseline(lastTickNanos);
                case TRAVEL -> this.tickTravel(lastTickNanos);
                case SETTLE -> this.tickSettle(lastTickNanos);
            }
        }

        private void tickBaseline(long lastTickNanos) {
            this.baselineSamples[BASELINE_TICKS - this.baselineLeft] = lastTickNanos;
            if (--this.baselineLeft > 0) {
                return;
            }
            long[] sorted = this.baselineSamples.clone();
            Arrays.sort(sorted);
            long median = sorted[sorted.length / 2];
            this.baselineMs = median / 1_000_000.0D;
            this.echo(String.format("§e[maixr 基准] §f开始：%d 次，半径 %d~%d 格，基线 MSPT %.1fms",
                    this.count, this.minR, this.maxR, this.baselineMs));
            this.teleportNext();
        }

        private void tickTravel(long lastTickNanos) {
            if (this.player.isRemoved() || this.player.level() != this.level) {
                this.finish("玩家退出或离开主世界");
                return;
            }
            this.windowPeakNanos = Math.max(this.windowPeakNanos, lastTickNanos);
            this.overallPeakNanos = Math.max(this.overallPeakNanos, lastTickNanos);
            if (this.level.getChunkSource().getChunkNow(this.destChunk.x, this.destChunk.z) == null) {
                return;   // 目的地区块还没到 FULL，继续等
            }
            long wall = System.nanoTime() - this.t0;
            this.successCount++;
            this.rows.add(new Row(this.attempts - 1, this.destX, this.destY, this.destZ, wall, this.windowPeakNanos, null));
            this.echo(String.format("§e[maixr 基准] §f#%d (%d, %d) y=%d —— 墙钟 %.2fs，窗口 MSPT 峰值 %.0fms",
                    this.attempts, this.destX, this.destZ, this.destY,
                    wall / 1_000_000_000.0D, this.windowPeakNanos / 1_000_000.0D));
            this.advance();
        }

        /** 成功/跳过后的推进：达标或候选用尽 → 结束传送段进入回落判定；否则尝试下一候选。 */
        private void advance() {
            if (this.successCount >= this.count || this.attempts >= this.maxAttempts) {
                this.beginSettle();
            } else {
                this.teleportNext();
            }
        }

        private void beginSettle() {
            long settleNanos = this.settleBaseline();
            if (settleNanos < 0) {
                this.finish(null);   // 没有成功传送，恢复时长无意义
                return;
            }
            this.phase = Phase.SETTLE;
            this.settleStartNanos = System.nanoTime();
        }

        /** 若至少一次成功传送，返回基线纳秒；否则 -1。 */
        private long settleBaseline() {
            for (Row r : this.rows) {
                if (r.skipReason() == null) {
                    return (long) (this.baselineMs * 1_000_000.0D);
                }
            }
            return -1L;
        }

        private void tickSettle(long lastTickNanos) {
            if (lastTickNanos <= (long) (this.baselineMs * 1_000_000.0D * RECOVERY_FACTOR)) {
                if (++this.settleOkTicks >= SETTLE_OK_TICKS) {
                    this.finish(null);
                    return;
                }
            } else {
                this.settleOkTicks = 0;
            }
            if (++this.settleTicks >= SETTLE_TIMEOUT_TICKS) {
                this.finish("等待 MSPT 回落超时");
            }
        }

        /** 派生下一个目的地候选、离线探高并传送；失败则记跳过行并推进。 */
        private void teleportNext() {
            int i = this.attempts++;
            RandomSource rng = rngFor(this.seedBase, i);
            double angle = rng.nextDouble() * Math.PI * 2.0D;
            double r = this.minR + rng.nextDouble() * (this.maxR - this.minR);
            int x = (int) Math.floor(Math.cos(angle) * r);
            int z = (int) Math.floor(Math.sin(angle) * r);

            String skip = probe(x, z);
            if (skip != null) {
                this.rows.add(new Row(i, x, 0, z, 0L, 0L, skip));
                this.echo(String.format("§e[maixr 基准] §7#%d (%d, %d) 跳过：%s", i + 1, x, z, skip));
                this.advance();   // 跳过零成本，同 tick 推进
                return;
            }

            // ★ 必须在传送前切相位：否则下一 Post 仍走 tickBaseline（实测越界崩溃根因）
            this.phase = Phase.TRAVEL;
            this.destX = x;
            this.destZ = z;
            this.destChunk = new ChunkPos(x >> 4, z >> 4);
            this.t0 = System.nanoTime();
            this.windowPeakNanos = 0;
            this.player.teleportTo(this.level, x + 0.5D, this.destY, z + 0.5D,
                    this.player.getYRot(), this.player.getXRot());
        }

        /** 离线高度场探测：返回 null 表示可行（destY 已写），否则为跳过原因。 */
        private String probe(int x, int z) {
            try {
                ChunkGenerator gen = this.level.getChunkSource().getGenerator();
                if (!(gen instanceof NoiseBasedChunkGenerator noiseGen)) {
                    return "非噪声生成器";
                }
                int top = noiseGen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE,
                        this.level, this.level.getChunkSource().randomState());
                if (top <= this.level.getMinBuildHeight()) {
                    return "全虚列（离线高度场返回 minY）";
                }
                this.destY = Math.min(top + 2, this.level.getMaxBuildHeight() - 1);
                return null;
            } catch (Throwable t) {
                return "高度探测异常: " + t.getClass().getSimpleName();
            }
        }

        /** 结束会话：摘采样器、清全局引用、双通道输出汇总。 */
        void finish(String reason) {
            session = null;
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(this.sampler);
            List<String> lines = new ArrayList<>();
            if (reason != null) {
                lines.add("§c[maixr 基准] 会话结束（" + reason + "）");
            }
            long ok = this.rows.stream().filter(r -> r.skipReason() == null).count();
            long totalWall = 0L;
            long maxPeak = 0L;
            for (Row r : this.rows) {
                if (r.skipReason() == null) {
                    totalWall += r.wallNanos();
                    maxPeak = Math.max(maxPeak, r.peakNanos());
                }
            }
            lines.add(String.format("§e[maixr 基准] §f===== 汇总 ====="));
            lines.add(String.format("§f成功 %d / 跳过 %d（候选尝试 %d / 上限 %d）；总墙钟 %.2fs；单次均值 %.2fs",
                    ok, this.rows.size() - ok, this.attempts, this.maxAttempts,
                    totalWall / 1_000_000_000.0D,
                    ok == 0 ? 0.0D : totalWall / (double) ok / 1_000_000_000.0D));
            String recovery;
            if (this.phase == Phase.SETTLE) {
                double rec = (System.nanoTime() - this.settleStartNanos) / 1_000_000_000.0D;
                recovery = this.settleTicks >= SETTLE_TIMEOUT_TICKS && this.settleOkTicks < SETTLE_OK_TICKS
                        ? String.format("超时未回落（等待 %.1fs）", rec)
                        : String.format("%.1fs", rec);
            } else {
                recovery = "无（无成功传送或会话提前结束）";
            }
            lines.add(String.format("§f全程 MSPT 峰值 %.0fms；末次传送后回落至基线±20%% 用时 %s",
                    this.overallPeakNanos / 1_000_000.0D, recovery));
            for (Row r : this.rows) {
                if (r.skipReason() != null) {
                    lines.add(String.format("§7跳过 #%d (%d, %d)：%s", r.index() + 1, r.x(), r.z(), r.skipReason()));
                }
            }
            for (String line : lines) {
                this.echo(line);   // echo 内部已双通道（聊天 + 日志），不要重复打日志
            }
        }

        /** 聊天栏输出（玩家在线时）+ 日志（始终）。 */
        private void echo(String line) {
            if (!this.player.isRemoved()) {
                this.player.sendSystemMessage(Component.literal(line));
            }
            MaixrMod.LOGGER.info(stripCodes(line));
        }

        private static String stripCodes(String s) {
            return s.replace("§0", "").replace("§1", "").replace("§2", "").replace("§3", "")
                    .replace("§4", "").replace("§5", "").replace("§6", "").replace("§7", "")
                    .replace("§8", "").replace("§9", "").replace("§a", "").replace("§b", "")
                    .replace("§c", "").replace("§d", "").replace("§e", "").replace("§f", "");
        }
    }

    /**
     * 目的地序号 RNG：只含「种子基 + 序号」（种子基已含世界种子与参数），
     * 保证同种子同参数同坐标序列（spec「确定性派生」）。
     */
    private static RandomSource rngFor(long seedBase, int index) {
        return RandomSource.create(seedBase + (long) index * 1000000007L);
    }

    // ------------------------------------------------------------------
    //  采样器：会话期间注册，结束注销
    // ------------------------------------------------------------------

    private static final class Sampler {
        @SubscribeEvent
        public void onPostTick(ServerTickEvent.Post event) {
            Session s = session;
            if (s != null) {
                try {
                    s.tick(event.getServer());
                } catch (Throwable t) {
                    MaixrMod.LOGGER.error("[maixr 基准] 内部异常，会话终止", t);
                    s.finish("内部异常: " + t.getClass().getSimpleName());
                }
            }
        }

        @SubscribeEvent
        public void onServerStopped(ServerStoppedEvent event) {
            if (session != null) {
                // 服务器正在关闭：只清引用，不再输出报告
                session = null;
                MaixrMod.LOGGER.info("[maixr 基准] 服务器停止，会话已清理（无残留状态）");
            }
        }
    }
}
