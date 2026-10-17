package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 【虚空凝尘】—— 破坏面板朝上对着空气，就源源不断地产陨石粉，位置越高越快。
 *
 * <h3>工作方式</h3>
 * <ol>
 *   <li><b>发现</b>：每 {@link #DISCOVER_INTERVAL} tick 扫一遍玩家周围的方块实体，
 *       找 {@code ae2:cable_bus}，反射查它朝上的部件；是破坏面板就记进缓存。</li>
 *   <li><b>催熟</b>：之后<b>每 tick</b>只处理缓存里的面板（一次方块实体查询，很便宜），
 *       倒计时到点就在面板上方放一块 {@link SkyCondensateBlock}。</li>
 *   <li>AE2 自己把这块打碎，掉落陨石粉并吸进 ME 网络。</li>
 * </ol>
 *
 * <h3>速率（Y 越高越快）</h3>
 * <pre>
 *   Y ≤ 80   →  600 tick（30 秒一个）
 *   Y ≥ 319  →   20 tick（1 秒一个，世界顶部）
 *   中间线性插值（越往上越快，每升高一格约快 2.4 tick）
 * </pre>
 *
 * <h3>自诊断（重要）</h3>
 * 这套东西最怕"静默不干活"。所以：
 * <ul>
 *   <li>扫到线缆总线但没认出破坏面板 → 把部件的类名打进日志（一次性），一眼就知道 AE2 里叫什么</li>
 *   <li>放下去的凝屑 {@link #STUCK_LIMIT} tick 还没被吃掉 → 打 WARN（说明面板没供电 / 不是破坏面板）</li>
 *   <li>{@code /maixr skydebug} 手动扫一遍并报告看到的一切</li>
 * </ul>
 */
@EventBusSubscriber(modid = MaixrMod.MODID)
public final class SkyDustCondenser {
    private static final String CABLE_BUS = "ae2:cable_bus";
    /** 最慢段上界：Y ≤ 80 一律按最慢速率产 */
    private static final int SLOW_Y = 80;
    /** 最快段下界：Y ≥ 319（世界顶部）一律按最快速率产 */
    private static final int FAST_Y = 319;
    /** Y ≤ 80 的间隔：600 tick = 30 秒一个 */
    private static final int SLOWEST_TICKS = 600;
    /** Y ≥ 319（世界顶）的间隔：20 tick = 1 秒一个 */
    private static final int FASTEST_TICKS = 20;
    /** 多久重新找一遍新面板 */
    private static final int DISCOVER_INTERVAL = 20;
    /**
     * 每次发现时扫几个区块。
     *
     * <p>★ 服务端优化：原来是 81（每个玩家每秒把 9×9 区块全部遍历一遍方块实体）。
     * 现在降到 27 —— 同样一圈扫描分摊到 3 秒完成，玩家看不出区别，服务器少 2/3 的活。
     */
    private static final int CHUNKS_PER_DISCOVER = 27;
    private static final int SCAN_RADIUS = 4;
    /** 凝屑放下去多久没被吃掉就报警 */
    private static final int STUCK_LIMIT = 60;

    private static int discoverCounter = 0;
    private static int chunkCursor = 0;

    private static final class Plane {
        final ServerLevel level;
        final BlockPos bus;
        int cooldown;
        int stuck;
        boolean warned;

        Plane(ServerLevel level, BlockPos bus, int cooldown) {
            this.level = level;
            this.bus = bus;
            this.cooldown = cooldown;
        }
    }

    /** 维度 + 总线坐标 → 面板记录 */
    private static final Map<String, Plane> PLANES = new HashMap<>();
    /** 诊断用：见过的部件类名（只记一次） */
    private static final Set<String> SEEN_PART_CLASSES = new LinkedHashSet<>();
    private static boolean reportedNoPlane = false;
    private static int loggedPlacements = 0;

    private SkyDustCondenser() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++discoverCounter >= DISCOVER_INTERVAL) {
            discoverCounter = 0;
            for (ServerLevel level : event.getServer().getAllLevels()) {
                if (level.players().isEmpty()) continue;
                for (ServerPlayer player : level.players()) discoverAround(level, player);
            }
        }
        for (Iterator<Map.Entry<String, Plane>> it = PLANES.entrySet().iterator(); it.hasNext(); ) {
            Plane p = it.next().getValue();
            if (!tickPlane(p)) it.remove();
        }
    }

    /**
     * 高度决定速度：Y ≤ 80 最慢（30 秒一个），Y ≥ 319 世界顶部最快（1 秒一个），中间线性过渡。
     *
     * <p>例：y=80 → 600 tick；y=160 → 406 tick（20.3 秒）；y=256 → 173 tick（8.7 秒）；y=319 → 20 tick。
     */
    public static int intervalFor(int y) {
        if (y <= SLOW_Y) return SLOWEST_TICKS;
        if (y >= FAST_Y) return FASTEST_TICKS;
        int span = SLOWEST_TICKS - FASTEST_TICKS;
        return Math.max(FASTEST_TICKS, SLOWEST_TICKS - span * (y - SLOW_Y) / (FAST_Y - SLOW_Y));
    }

    /** 把 tick 间隔转成"约几秒"，只用于提示文字 */
    public static String secondsText(int ticks) {
        return String.format(java.util.Locale.ROOT, "%.1f", ticks / 20.0D);
    }

    // ------------------------------------------------------------------ 催熟

    /**
     * 催熟一个面板。
     *
     * <p>★ 做法：直接在面板正前方<b>生成一颗陨石粉的物品实体</b>，交给面板自己吸进网络。
     * 反编译确认过：AE2 的破坏面板（{@code ItemPickupStrategy}）除了打碎方块，
     * 还实现了 {@code canPickUpEntity / pickUpEntity / storeEntityItem} ——
     * 落在它面前的物品会被直接塞进 ME 网络。
     * 这比"让它打碎我放的方块"稳得多：不依赖任何"方块能不能被采集"的判断。
     */
    private static boolean tickPlane(Plane p) {
        if (p.level.getBlockEntity(p.bus) == null) return false;      // 面板被拆了
        BlockPos target = p.bus.above();

        // 上一颗还没被吸走（方块挡着 / 地上还躺着陨石粉）就先不发，免得堆成一座山
        boolean blocked = !p.level.isEmptyBlock(target) || dustNearby(p.level, target);
        if (blocked) {
            if (++p.stuck > STUCK_LIMIT && !p.warned) {
                p.warned = true;
                MaixrMod.LOGGER.warn("[陨石] 面板 ({}, {}, {}, {}) 前方的东西一直没被吸走 —— "
                                + "检查它是不是 AE2 破坏面板、有没有通电、前方是不是空的",
                        p.bus.getX(), p.bus.getY(), p.bus.getZ(), p.level.dimension().location());
            }
            return true;
        }
        p.stuck = 0;
        if (--p.cooldown > 0) return true;

        net.minecraft.world.item.Item dust = BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath("ae2", "sky_dust"));
        if (dust == null || dust == net.minecraft.world.item.Items.AIR) return true;

        // ★★ 关键：AE2 面板是靠 onEntityCollision 触发的 —— 实体必须"撞到"面板的那块薄碰撞箱。
        //    所以这里绝对不能让物品悬空（之前设了 setNoGravity(true)，它飘在半空永远碰不到面板，
        //    表现就是"一堆掉落物悬在空中谁也不吸"）。现在让它从面板正上方一点点往下落。
        net.minecraft.world.entity.item.ItemEntity item = new net.minecraft.world.entity.item.ItemEntity(
                p.level, target.getX() + 0.5D, target.getY() + 0.1D, target.getZ() + 0.5D,
                new net.minecraft.world.item.ItemStack(dust, 1));
        item.setDeltaMovement(0.0D, -0.02D, 0.0D);   // 轻轻下坠，穿过面板的碰撞箱
        item.setPickUpDelay(0);
        p.level.addFreshEntity(item);

        int iv = intervalFor(target.getY());
        p.cooldown = iv;
        if (loggedPlacements < 6 || loggedPlacements % 500 == 0) {
            MaixrMod.LOGGER.info("[陨石] 面板 ({}, {}, {}) 前方生成陨石粉（间隔 {} tick ≈ {} 秒）",
                    target.getX(), target.getY(), target.getZ(), iv, secondsText(iv));
        }
        loggedPlacements++;
        if (loggedPlacements % 10 == 0) {
            p.level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                    target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D,
                    3, 0.25D, 0.25D, 0.25D, 0.01D);
        }
        return true;
    }

    /**
     * 面板前方是不是还有"卡住"的陨石粉。
     *
     * <p>只统计活了超过 20 tick 的 —— 正常下坠的陨石粉 1~2 tick 就被吸走了，
     * 不能因为"刚好路过"就把生成节流掉（不然顶层 1 tick/个根本跑不起来）。
     */
    private static boolean dustNearby(ServerLevel level, BlockPos target) {
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                target.getX() - 1.0D, target.getY() - 2.0D, target.getZ() - 1.0D,
                target.getX() + 2.0D, target.getY() + 2.0D, target.getZ() + 2.0D);
        for (net.minecraft.world.entity.item.ItemEntity it
                : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) {
            if (it.tickCount > 20) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 发现

    private static void discoverAround(ServerLevel level, ServerPlayer player) {
        int side = SCAN_RADIUS * 2 + 1;
        int total = side * side;
        int cx = player.chunkPosition().x;
        int cz = player.chunkPosition().z;
        for (int i = 0; i < CHUNKS_PER_DISCOVER; i++) {
            int idx = Math.floorMod(chunkCursor++, total);
            int ox = idx % side - SCAN_RADIUS;
            int oz = idx / side - SCAN_RADIUS;
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx + ox, cz + oz);
            if (chunk == null) continue;
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                inspect(level, be);
            }
        }
    }

    private static void inspect(ServerLevel level, BlockEntity be) {
        ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        if (id == null || !id.toString().equals(CABLE_BUS)) return;
        Object part = partOn(be, Direction.UP);
        if (part == null) return;
        String cls = part.getClass().getName();
        if (!isAnnihilationPlane(part)) {
            if (SEEN_PART_CLASSES.add(cls) && SEEN_PART_CLASSES.size() <= 8) {
                MaixrMod.LOGGER.info("[陨石] 线缆总线 ({}, {}, {}) 朝上是 {}（不是破坏面板，跳过）",
                        be.getBlockPos().getX(), be.getBlockPos().getY(), be.getBlockPos().getZ(), cls);
            }
            return;
        }
        String key = level.dimension().location() + "@" + be.getBlockPos().asLong();
        if (!PLANES.containsKey(key)) {
            BlockPos target = be.getBlockPos().above();
            PLANES.put(key, new Plane(level, be.getBlockPos(), intervalFor(target.getY())));
            int iv = intervalFor(target.getY());
            MaixrMod.LOGGER.info("[陨石] 发现朝上的破坏面板 ({}, {}, {}) —— 间隔 {} tick ≈ {} 秒",
                    target.getX(), target.getY(), target.getZ(), iv, secondsText(iv));
        }
    }

    /** 反射取某个面上的部件；拿不到返回 null */
    private static Object partOn(BlockEntity be, Direction dir) {
        try {
            Method getPart = null;
            for (Class<?> c = be.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    getPart = c.getMethod("getPart", Direction.class);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // 继续往上找
                }
            }
            if (getPart == null) return null;
            return getPart.invoke(be, dir);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 部件是不是"破坏面板"：类名 / 父类 / 接口 里出现 AnnihilationPlane 都算 */
    private static boolean isAnnihilationPlane(Object part) {
        for (Class<?> c = part.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().contains("AnnihilationPlane")) return true;
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().contains("AnnihilationPlane")) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 调试

    /** /maixr skydebug：把玩家周围看到的东西全部报出来 */
    public static String debugReport(ServerLevel level, ServerPlayer player) {
        StringBuilder sb = new StringBuilder();
        int side = SCAN_RADIUS * 2 + 1;
        int buses = 0;
        sb.append("半径 ").append(SCAN_RADIUS).append(" 区块内：");
        for (int ox = -SCAN_RADIUS; ox <= SCAN_RADIUS; ox++) {
            for (int oz = -SCAN_RADIUS; oz <= SCAN_RADIUS; oz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(
                        player.chunkPosition().x + ox, player.chunkPosition().z + oz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
                    if (id == null || !id.toString().equals(CABLE_BUS)) continue;
                    buses++;
                    if (buses > 12) continue;
                    sb.append("\n  §7总线 (").append(be.getBlockPos().getX()).append(", ")
                      .append(be.getBlockPos().getY()).append(", ").append(be.getBlockPos().getZ()).append(")§r 各面: ");
                    for (Direction d : Direction.values()) {
                        Object part = partOn(be, d);
                        if (part == null) continue;
                        boolean plane = isAnnihilationPlane(part);
                        sb.append("\n    ").append(d.getName()).append(" = ")
                          .append(part.getClass().getSimpleName())
                          .append(plane ? " §a← 破坏面板§r" : "");
                    }
                }
            }
        }
        sb.append("\n§7共找到线缆总线 §f").append(buses).append(" §7个；已锁定面板 §f").append(PLANES.size()).append(" §7个");
        int iv = intervalFor(player.blockPosition().getY());
        sb.append("\n§7当前高度 y=").append(player.blockPosition().getY())
          .append(" → 间隔 §f").append(iv).append(" tick§7（约 §f").append(secondsText(iv))
          .append(" §7秒一个）");
        sb.append("\n§7速率曲线：§fy≤80 §7→ 30.0 秒；§fy=160 §7→ ").append(secondsText(intervalFor(160)))
          .append(" 秒；§fy=256 §7→ ").append(secondsText(intervalFor(256)))
          .append(" 秒；§fy≥319 §7→ 1.0 秒");
        return sb.toString();
    }
}
