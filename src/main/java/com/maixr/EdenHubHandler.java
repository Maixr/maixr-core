package com.maixr;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 【弧光重生 · 伊甸枢纽总站】维度逻辑（原生 mod 实现，不依赖 KubeJS）：
 *  1. 首个玩家进入 maixr:overworld_reborn 时，自动放置整座枢纽总站，
 *     使结构最高点恰好落在 (0, 256, 0)（放置原点 = -130, 54, -130）。
 *  2. 每次进入该维度：传送到建筑中心最高点 (0.5, 257, 0.5)。
 *  3. 显示 30 秒极光渐变大标题 + 【管理员：玩家名】。
 *  4. 每个玩家首次进入时播放 30 秒电影式运镜（旁观者视角环绕总站 + 俯瞰重生世界），
 *     全程路径位于建筑外部，不会穿入建筑内部。
 */
public final class EdenHubHandler {
    public static final ResourceLocation DIM_ID = ResourceLocation.fromNamespaceAndPath("maixr", "overworld_reborn");
    public static final ResourceKey<Level> DIM_KEY = ResourceKey.create(Registries.DIMENSION, DIM_ID);
    public static final ResourceLocation HUB_ID = ResourceLocation.fromNamespaceAndPath("maixr", "eden-hub");

    /** 放置原点：让结构内最高点（130,202,130）落到世界 (0,256,0) */
    public static final BlockPos ORIGIN = new BlockPos(-130, 54, -130);

    private static final double SPAWN_X = 0.5D, SPAWN_Y = 257.0D, SPAWN_Z = 0.5D;
    private static final float SPAWN_YAW = 0.0F, SPAWN_PITCH = 8.0F;

    /** 电影运镜时长（tick）：600 = 30 秒 */
    private static final int CINE_TICKS = 200;
    /** 标题停留时长（tick）：600 = 30 秒 */
    private static final int TITLE_STAY = 600;

    /** 每 tick 放置的方块数（60 万方块约 150 tick / 7.5 秒完成，避免卡死主线程） */
    private static final int BLOCKS_PER_TICK = 4000;
    /** 分帧放置队列（每个维度一份，放置完成后释放） */
    private static final java.util.Map<ResourceKey<Level>, long[]> QUEUE_POS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<ResourceKey<Level>, net.minecraft.world.level.block.state.BlockState[]> QUEUE_STATE = new java.util.concurrent.ConcurrentHashMap<>();
    /** 按区块分组的索引：区块 key -> 该区块内的方块索引数组 */
    private static final java.util.Map<ResourceKey<Level>, java.util.Map<Long, int[]>> QUEUE_CHUNKS = new java.util.concurrent.ConcurrentHashMap<>();
    /** 区块处理顺序 */
    private static final java.util.Map<ResourceKey<Level>, java.util.List<Long>> QUEUE_ORDER = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<ResourceKey<Level>, Integer> QUEUE_CHUNK_AT = new java.util.concurrent.ConcurrentHashMap<>();
    /** 每 tick 处理的区块数（整体 289 个区块，约 96 tick / 5 秒完成） */
    private static final int CHUNKS_PER_TICK = 3;
    /** 等待枢纽总站生成完成的玩家（生成完毕后统一传送，避免被埋进方块里） */
    private static final java.util.Set<java.util.UUID> WAITING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final String TAG_ROOT = "MaixrEden";
    private static final String TAG_CINE_TICK = "CineTick";
    private static final String TAG_CINE_DONE = "CineDone";
    private static final String TAG_LAST_DIM = "LastDim";
    private static final String TAG_BACK_MODE = "BackMode";

    /** 极光渐变色（青 → 蓝 → 紫 → 粉 → 金 → 绿） */
    private static final int[] AURORA = {
            0x00E5FF, 0x00B8D4, 0x2979FF, 0x536DFE, 0x7C4DFF, 0xB388FF,
            0xFF80AB, 0xFFD54F, 0x69F0AE, 0x00E5FF
    };

    /** 运镜关键帧：[进度, 相机 x,y,z, 注视 x,y,z]（全部位于建筑外部） */
    private static final double[][] CINE_PATH = {
            {0.00, 0, 302, 132, 0, 250, 0},          // 起点：总站正前方上方
            {0.28, 96, 296, 58, 0, 248, 0},          // 缓慢侧移
            {0.56, 82, 292, -62, 0, 246, 0},         // 绕到另一侧
            {0.80, -74, 290, -54, 0, 245, 0},        // 回绕
            {1.00, SPAWN_X, SPAWN_Y, SPAWN_Z, 0, 250, 0},   // 落到中央柱顶
    };

    private EdenHubHandler() {}

    // ============================ 玩家进入维度 ============================
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!event.getTo().location().equals(DIM_ID)) return;
        ServerLevel level = player.server.getLevel(DIM_KEY);
        if (level == null) return;

        EdenHubData data = EdenHubData.get(level);
        MaixrMod.LOGGER.info("[伊甸枢纽总站] 玩家 {} 进入维度：建筑已就绪={} 生成中={}",
                player.getName().getString(), data.hubPlaced, QUEUE_POS.containsKey(level.dimension()));
        if (!data.hubPlaced || QUEUE_POS.containsKey(level.dimension())) {
            // 建筑还在生成：先让玩家在安全位置等待，生成完成后自动传送
            WAITING.add(player.getUUID());
            player.teleportTo(level, SPAWN_X, SPAWN_Y + 48, SPAWN_Z, SPAWN_YAW, 68.0F);
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.SLOW_FALLING, 1200, 0, false, false));
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 1200, 4, false, false));
            player.displayClientMessage(Component.literal("§b[弧光重生] §f伊甸枢纽总站正在凝聚……").withStyle(ChatFormatting.AQUA), true);
            return;
        }
        onHubReady(player, level);
    }

    // ============================ 服务器 tick ============================
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().getLevel(DIM_KEY);
        if (level == null) return;

        // ★★★ 建造时机改了：以前是「重生世界里有玩家才干活」，于是所有重活
        //     （加载解析 1.9MB 结构 nbt + 60 万方块分帧放置 + 289 个区块现生成）
        //     全挤在玩家第一次进门那一秒，直接卡到不能动。
        //     现在改成：开服后等 EdenTicker 把枢纽区块预加载完，就<b>静默施工</b>，
        //     玩家来的时候总站早就站在那儿了。
        EdenHubData data = EdenHubData.get(level);
        if (!data.hubPlaced && EdenTicker.isHubPreloadDone()) {
            if (!QUEUE_POS.containsKey(level.dimension())) {
                MaixrMod.LOGGER.info("[伊甸枢纽总站] 预加载完成，开始静默建造……");
            }
            ensureHubPlaced(level);
            tickPlacement(level, data);
        }

        java.util.List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        for (ServerPlayer player : players) {
            tickCinematic(player);        // 推进身处该维度玩家的运镜

            // ★ 兜底：建筑已就绪、玩家没看过动画、且当前不在运镜中 → 补播（不管他是传送进来还是登录就在这）
            var root = player.getPersistentData().getCompound(TAG_ROOT);
            if (data.hubPlaced && !QUEUE_POS.containsKey(level.dimension())
                    && !data.cinePlayed.contains(player.getUUID()) && !root.contains(TAG_CINE_TICK)) {
                MaixrMod.LOGGER.info("[伊甸枢纽总站] 兜底触发运镜：玩家 {}", player.getName().getString());
                onHubReady(player, level);
            }
            // 兜底：长时间等待生成仍未就绪 → 直接落地，避免玩家永远悬在空中
            if (WAITING.contains(player.getUUID()) && !data.hubPlaced) {
                int waited = root.getInt("WaitTicks") + 1;
                root.putInt("WaitTicks", waited);
                player.getPersistentData().put(TAG_ROOT, root);
                if (waited > 600) {                       // 30 秒
                    WAITING.remove(player.getUUID());
                    root.remove("WaitTicks");
                    player.getPersistentData().put(TAG_ROOT, root);
                    MaixrMod.LOGGER.warn("[伊甸枢纽总站] 等待超时，直接把玩家落地");
                    onHubReady(player, level);
                }
            }
        }
    }

    // ============================ 建筑放置 ============================

    /**
     * 打开总站结构文件。
     *
     * <p>★ 1.21 起数据包目录改为<b>单数</b>（{@code structure/}）。旧代码写的是复数
     * {@code structures/}，导致 {@code getResourceAsStream} 永远返回 null ——
     * 表现为「伊甸枢纽结构怎么也放不出来」并且每 tick 刷一条
     * 「mod 内没有找到结构文件」错误（实测一次启动刷出 6000+ 条）。
     *
     * <p>这里同时尝试单数（正确）与复数（兼容旧资源布局），返回先命中的那个。
     */
    public static java.io.InputStream openHubNbt() {
        for (String p : new String[]{
                "/data/maixr/structure/eden-hub.nbt",
                "/data/maixr/structures/eden-hub.nbt"}) {
            java.io.InputStream in = MaixrMod.class.getResourceAsStream(p);
            if (in != null) return in;
        }
        return null;
    }

    private static void ensureHubPlaced(ServerLevel level) {
        EdenHubData data = EdenHubData.get(level);
        if (data.hubPlaced) return;
        if (QUEUE_POS.containsKey(level.dimension())) return;          // 已在分帧放置中
        StructureTemplate template = level.getStructureManager().get(HUB_ID).orElse(null);
        if (template == null) {
            MaixrMod.LOGGER.warn("[伊甸枢纽总站] 未找到结构模板 maixr:eden-hub（应位于 data/maixr/structure/eden-hub.nbt）");
            return;
        }
        // ★ 直接读取并解析结构 nbt（不依赖 StructureTemplate.filterBlocks，其语义会把方块全部过滤掉）
        long[] pos;
        net.minecraft.world.level.block.state.BlockState[] st;
        java.util.Map<Long, java.util.List<Integer>> byChunk = new java.util.HashMap<>();
        try (java.io.InputStream in = openHubNbt()) {
            if (in == null) {
                MaixrMod.LOGGER.error("[伊甸枢纽总站] mod 内没有找到结构文件 data/maixr/structure/eden-hub.nbt");
                return;
            }
            net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.NbtIo.readCompressed(in, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            net.minecraft.nbt.ListTag palette = tag.getList("palette", 10);
            net.minecraft.nbt.ListTag blocks = tag.getList("blocks", 10);
            net.minecraft.core.HolderGetter<net.minecraft.world.level.block.Block> lookup =
                    level.holderLookup(net.minecraft.core.registries.Registries.BLOCK);
            net.minecraft.world.level.block.state.BlockState[] paletteStates =
                    new net.minecraft.world.level.block.state.BlockState[palette.size()];
            for (int i = 0; i < palette.size(); i++) {
                paletteStates[i] = net.minecraft.nbt.NbtUtils.readBlockState(lookup, palette.getCompound(i));
            }
            int n = blocks.size();
            pos = new long[n];
            st = new net.minecraft.world.level.block.state.BlockState[n];
            int kept = 0;
            for (int i = 0; i < n; i++) {
                net.minecraft.nbt.CompoundTag b = blocks.getCompound(i);
                net.minecraft.nbt.ListTag pl = b.getList("pos", 3);
                int bx = pl.getInt(0), by = pl.getInt(1), bz = pl.getInt(2);
                int si = b.getInt("state");
                if (si < 0 || si >= paletteStates.length) continue;
                net.minecraft.world.level.block.state.BlockState state = paletteStates[si];
                if (state.isAir()) continue;
                int wx = ORIGIN.getX() + bx, wy = ORIGIN.getY() + by, wz = ORIGIN.getZ() + bz;
                pos[kept] = net.minecraft.core.BlockPos.asLong(wx, wy, wz);
                st[kept] = state;
                long ck = net.minecraft.world.level.ChunkPos.asLong(wx >> 4, wz >> 4);
                byChunk.computeIfAbsent(ck, k -> new java.util.ArrayList<>()).add(kept);
                kept++;
            }
            pos = java.util.Arrays.copyOf(pos, kept);
            st = java.util.Arrays.copyOf(st, kept);
        } catch (Throwable t) {
            MaixrMod.LOGGER.error("[伊甸枢纽总站] 读取结构 nbt 失败: {}", t.toString());
            return;
        }
        java.util.Map<Long, int[]> packed = new java.util.HashMap<>();
        java.util.List<Long> order = new java.util.ArrayList<>(byChunk.keySet());
        order.sort(java.util.Comparator.comparingLong(a -> a));
        for (var e : byChunk.entrySet()) {
            int[] arr = new int[e.getValue().size()];
            for (int i = 0; i < arr.length; i++) arr[i] = e.getValue().get(i);
            packed.put(e.getKey(), arr);
        }
        QUEUE_POS.put(level.dimension(), pos);
        QUEUE_STATE.put(level.dimension(), st);
        QUEUE_CHUNKS.put(level.dimension(), packed);
        QUEUE_ORDER.put(level.dimension(), order);
        QUEUE_CHUNK_AT.put(level.dimension(), 0);
        MaixrMod.LOGGER.info("[伊甸枢纽总站] 已解析结构：{} 个方块 / {} 个区块，每 tick {} 个区块（约 {} 秒）",
                pos.length, order.size(), CHUNKS_PER_TICK, order.size() / CHUNKS_PER_TICK / 20);
    }

    /** 按区块分帧放置：每 tick 处理若干个区块（确保区块已加载，否则方块会被丢弃） */
    private static void tickPlacement(ServerLevel level, EdenHubData data) {
        var key = level.dimension();
        long[] pos = QUEUE_POS.get(key);
        if (pos == null) return;
        net.minecraft.world.level.block.state.BlockState[] st = QUEUE_STATE.get(key);
        java.util.Map<Long, int[]> chunks = QUEUE_CHUNKS.get(key);
        java.util.List<Long> order = QUEUE_ORDER.get(key);
        int at = QUEUE_CHUNK_AT.getOrDefault(key, 0);
        if (chunks == null || order == null) return;

        // ★ 服务端优化：按服务器负载自适应放慢（总站建造是突发重活，别跟玩家的 tick 抢时间）
        int perTick = CHUNKS_PER_TICK;
        net.minecraft.server.MinecraftServer srv = level.getServer();
        if (srv != null) {
            double mspt = srv.getCurrentSmoothedTickTime();
            if (mspt >= 40.0D) perTick = Math.max(1, CHUNKS_PER_TICK / 4);
            else if (mspt >= 28.0D) perTick = Math.max(1, CHUNKS_PER_TICK / 2);
        }
        int end = Math.min(at + perTick, order.size());
        for (int ci = at; ci < end; ci++) {
            long ck = order.get(ci);
            int cx = net.minecraft.world.level.ChunkPos.getX(ck);
            int cz = net.minecraft.world.level.ChunkPos.getZ(ck);
            // ★ 关键：强制加载该区块（否则 setBlock 落在未加载区块上会被丢弃）
            net.minecraft.world.level.chunk.LevelChunk chunk = null;
            try {
                net.minecraft.world.level.chunk.ChunkAccess access =
                        level.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
                if (access instanceof net.minecraft.world.level.chunk.LevelChunk lc) chunk = lc;
            } catch (Throwable t) {
                MaixrMod.LOGGER.warn("[伊甸枢纽总站] 区块 ({},{}) 加载失败: {}", cx, cz, t.toString());
                continue;
            }
            if (chunk == null) continue;
            int[] idxs = chunks.get(ck);
            if (idxs == null) continue;
            for (int idx : idxs) {
                net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.of(pos[idx]);
                chunk.setBlockState(bp, st[idx], false);     // 直接写区块（快，且不触发额外逻辑）
            }
            chunk.setUnsaved(true);                            // 标记为待保存
        }
        QUEUE_CHUNK_AT.put(key, end);
        if (end >= order.size() && !data.hubPlaced) {
            MaixrMod.LOGGER.info("[伊甸枢纽总站] 静默建造完成（{} 个区块）", order.size());
        }
        if (end >= order.size()) {
            QUEUE_POS.remove(key); QUEUE_STATE.remove(key);
            QUEUE_CHUNKS.remove(key); QUEUE_ORDER.remove(key); QUEUE_CHUNK_AT.remove(key);
            data.hubPlaced = true;
            data.setDirty();
            for (java.util.UUID id : new java.util.ArrayList<>(WAITING)) {
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
                if (p != null) onHubReady(p, level);
                WAITING.remove(id);
            }
            MaixrMod.LOGGER.info("[伊甸枢纽总站] 生成完毕（最高点 (0,256,0)，共 {} 个方块）", pos.length);
        }
    }

    /** 建筑就绪后：传送 + 首次运镜 / 落地 */
    private static void onHubReady(ServerPlayer player, ServerLevel level) {
        // ★ 首次判定改为"服务端统一记录"（不再依赖玩家 NBT，避免换号/脏数据误判）
        EdenHubData data = EdenHubData.get(level);
        boolean first = !data.cinePlayed.contains(player.getUUID());
        MaixrMod.LOGGER.info("[伊甸枢纽总站] 玩家 {} 就位流程：首次={}（服务端记录 {} 人已观看）",
                player.getName().getString(), first, data.cinePlayed.size());
        if (first) startCinematic(player, level);
        else player.teleportTo(level, SPAWN_X, SPAWN_Y, SPAWN_Z, SPAWN_YAW, SPAWN_PITCH);
        showEntryTitle(player, first);
    }

    // ============================ 电影运镜 ============================
    private static void startCinematic(ServerPlayer player, ServerLevel level) {
        GameType back = player.gameMode.getGameModeForPlayer();
        var root = player.getPersistentData().getCompound(TAG_ROOT);
        root.putString(TAG_BACK_MODE, back.getName());
        root.putInt(TAG_CINE_TICK, 0);
        root.putBoolean(TAG_CINE_DONE, true);
        player.getPersistentData().put(TAG_ROOT, root);

        // ★ 世界转为黄昏（该维度独立时间）
        level.setDayTime(12500L);
        // ★ 记入服务端"已观看"名单（从此这个账号不再重播）
        EdenHubData hd = EdenHubData.get(level);
        hd.cinePlayed.add(player.getUUID());
        hd.setDirty();
        // ★ 启动烟花庆典（剩余时长记在玩家 NBT，纯运行时状态）
        var root0 = player.getPersistentData().getCompound(TAG_ROOT);
        root0.putInt("FireworkTicks", CINE_TICKS + 240);
        player.getPersistentData().put(TAG_ROOT, root0);

        player.setGameMode(GameType.SPECTATOR);
        player.teleportTo(level, CINE_PATH[0][1], CINE_PATH[0][2], CINE_PATH[0][3], SPAWN_YAW, SPAWN_PITCH);
        MaixrMod.LOGGER.info("[伊甸枢纽总站] 运镜开始：玩家 {}（{} tick = {} 秒），世界时间设为黄昏，烟花庆典启动",
                player.getName().getString(), CINE_TICKS, CINE_TICKS / 20);
    }

    private static void tickCinematic(ServerPlayer player) {
        var root = player.getPersistentData().getCompound(TAG_ROOT);
        if (!root.contains(TAG_CINE_TICK)) return;
        int tick = root.getInt(TAG_CINE_TICK);
        ServerLevel level = player.server.getLevel(DIM_KEY);
        if (level == null) { root.remove(TAG_CINE_TICK); player.getPersistentData().put(TAG_ROOT, root); return; }

        if (tick >= CINE_TICKS) {
            // 结束：恢复游戏模式并落到出生点
            GameType back = GameType.byName(root.getString(TAG_BACK_MODE), GameType.SURVIVAL);
            root.remove(TAG_CINE_TICK);
            player.getPersistentData().put(TAG_ROOT, root);
            player.setGameMode(back);
            player.teleportTo(level, SPAWN_X, SPAWN_Y, SPAWN_Z, SPAWN_YAW, SPAWN_PITCH);
            showEntryTitle(player, false);
            return;
        }

        double progress = (double) tick / CINE_TICKS;
        int i = 0;
        while (i < CINE_PATH.length - 2 && progress > CINE_PATH[i + 1][0]) i++;
        double[] a = CINE_PATH[i], b = CINE_PATH[i + 1];
        double seg = (progress - a[0]) / Math.max(0.0001D, b[0] - a[0]);
        double e = ease(Math.min(1.0D, Math.max(0.0D, seg)));

        double px = lerp(a[1], b[1], e), py = lerp(a[2], b[2], e), pz = lerp(a[3], b[3], e);
        double tx = lerp(a[4], b[4], e), ty = lerp(a[5], b[5], e), tz = lerp(a[6], b[6], e);
        double dx = tx - px, dy = ty - py, dz = tz - pz;
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        player.teleportTo(level, px, py, pz, yaw, pitch);

        root.putInt(TAG_CINE_TICK, tick + 1);

        // ★ 烟花庆典：四周弥散绽放（每 10 tick 一波，每波 3 处）
        int fw = root.contains("FireworkTicks") ? root.getInt("FireworkTicks") : 0;
        if (fw > 0) {
            if (fw % 5 == 0) spawnFireworks(level, player, 6);       // ★ 每 5 tick 一波、每波 6 发
            root.putInt("FireworkTicks", fw - 1);
            // 落地瞬间：来一波大型收尾烟花
            if (fw == 1) spawnFireworks(level, player, 14);
        }
        player.getPersistentData().put(TAG_ROOT, root);
    }

    /** 在玩家四周弥散点燃绚丽烟花（青 / 紫 / 粉 / 金 / 绿 五套配色）。 */
    private static void spawnFireworks(ServerLevel level, ServerPlayer player, int count) {
        final int[][] PALETTE = {
                {0x00E5FF, 0x7C4DFF}, {0xFF80AB, 0xFFD54F}, {0x69F0AE, 0x00E5FF},
                {0xFFD54F, 0xFF80AB}, {0xB388FF, 0x69F0AE}, {0x7C4DFF, 0xFFD54F}
        };
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < count; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2.0D;
            double dist = 12.0D + rnd.nextDouble() * 30.0D;
            double x = player.getX() + Math.cos(ang) * dist;
            double z = player.getZ() + Math.sin(ang) * dist;
            double y = player.getY() + 2.0D + rnd.nextDouble() * 20.0D;

            net.minecraft.world.item.ItemStack rocket =
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FIREWORK_ROCKET);
            net.minecraft.nbt.CompoundTag fw = NbtCompat.getOrCreateSub(rocket, "Fireworks");
            fw.putByte("Flight", (byte) (1 + rnd.nextInt(2)));
            net.minecraft.nbt.ListTag exps = new net.minecraft.nbt.ListTag();
            net.minecraft.nbt.CompoundTag exp = new net.minecraft.nbt.CompoundTag();
            exp.putByte("Type", (byte) rnd.nextInt(5));
            exp.putBoolean("Trail", true);
            exp.putBoolean("Flicker", rnd.nextBoolean());
            int[] pal = PALETTE[rnd.nextInt(PALETTE.length)];
            exp.putIntArray("Colors", new int[]{pal[0], pal[1]});
            exp.putIntArray("FadeColors", new int[]{pal[1], 0xFFFFFF});
            exps.add(exp);
            fw.put("Explosions", exps);

            net.minecraft.world.entity.projectile.FireworkRocketEntity firework =
                    new net.minecraft.world.entity.projectile.FireworkRocketEntity(level, x, y, z, rocket);
            level.addFreshEntity(firework);
        }
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double ease(double t) { return t < 0.5D ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2; }

    // ============================ 极光标题 ============================
    private static void showEntryTitle(ServerPlayer player, boolean first) {
        // 首次进入：欢迎回家 + 弧光裂隙--伊甸枢纽总站；之后：弧光重生 - 伊甸枢纽总站 + 管理员
        String main = first ? ("欢迎回家，" + player.getName().getString()) : "弧光重生 - 伊甸枢纽总站";
        MutableComponent title = Component.empty();
        for (int i = 0; i < main.length(); i++) {
            final int color = AURORA[i % AURORA.length];
            title.append(Component.literal(String.valueOf(main.charAt(i)))
                    .withStyle(style -> style.withColor(color).withBold(true)));
        }
        MutableComponent subtitle = Component.literal(
                        first ? "弧光裂隙--伊甸枢纽总站" : ("【管理员：" + player.getName().getString() + "】"))
                .withStyle(first ? ChatFormatting.AQUA : ChatFormatting.GRAY);
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, TITLE_STAY, 20));
        player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        player.connection.send(new ClientboundSetTitleTextPacket(title));
    }
}
