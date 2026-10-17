package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 世界状态机的心脏：
 * <ul>
 *   <li>驱动修复引擎（有额度、没入侵时才推进）</li>
 *   <li>完整度跨过 100‰ 就升纪元：全服播报 + 开启入侵</li>
 *   <li>入侵打完才允许继续修（E5）</li>
 *   <li>按纪元压制刷怪</li>
 *   <li>双向刻度：用无尽/红物质/创造级物品攻击或挖掘会让世界衰败（点名播报）</li>
 * </ul>
 */
@EventBusSubscriber(modid = MaixrMod.MODID)
public final class EdenTicker {
    public static final ResourceKey<Level> BROKEN = BrokenWorld.BROKEN;
    public static final ResourceKey<Level> REBORN =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "overworld_reborn"));

    /**
     * ★ 纯净主世界：<b>只</b>用来当"这个世界原本该有的地形"的取样源。
     *
     * <p>为什么不能直接用 maixr:overworld_reborn：那个维度里建了<b>伊甸枢纽总站</b>
     * （601374 个方块、占地 ±130 格）。修复引擎是"按坐标搬地形"，
     * 于是圆心一旦进入总站的覆盖范围，整座总站就会被当成地形搬进破碎世界
     * —— 玩家抬头看见一座站台飘在天上，就是这么来的。
     *
     * <p>这个维度没有任何建筑、也没有玩家改动，是干净的原版主世界地形。
     */
    public static final ResourceKey<Level> PRISTINE =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "overworld_pristine"));

    /**
     * 最终 Boss 出现的纪元档位（0 基）：9 = 弧光（90%~100%）。
     *
     * <p>★ 按需求改版：以前 5 个偶数纪元各有一次“入侵”（刷一堆原版怪，打完才能继续修），
     * 现在整套删除 —— 只在 90% 这一档刷一次自定义 Boss「弧光·裂隙意志」，一辈子只刷一次。
     */
    private static final int BOSS_STAGE = 9;

    /** 衰败触发冷却（tick）。同一玩家 5 秒内只计一次，避免一秒砍十刀掉十个百分点 */
    private static final long DECAY_COOLDOWN = 100L;
    /** 每次衰败扣多少完整度（千分比）。10‰ = 1%，这样界面上真的看得见 */
    private static final int DECAY_AMOUNT = 10;

    private static int tickCounter = 0;
    private static boolean spawnInitialized = false;
    /** 重生世界枢纽区域的预加载队列（区块 key） */
    private static java.util.List<Long> preloadQueue = null;
    private static int preloadAt = 0;
    /** 修复源只播报一次 */
    private static boolean sourceLogged = false;

    private EdenTicker() {}

    // ------------------------------------------------------------------ 每 tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel broken = BrokenWorld.resolve(server);
        if (broken == null) return;

        EdenProgress progress = EdenProgress.get(broken);

        // ★★ 修复源：优先 maixr:overworld_reborn（原生噪声世界，和破碎世界同种子、同 minecraft:overworld 预设）。
        //
        //   血的教训：这里千万不能"想当然地用主世界"。本整合包主世界是 SkyblockBuilder 空岛，
        //   主世界除了玩家出生那一小块以外<b>全是空气</b>。于是"从主世界同坐标搬地形"搬过来的全是空气：
        //   环游标一路推进（RingRadius 涨到 26），filledColumns 却永远是 0 ——
        //   表现就是"提交了物品，世界根本没修"。存档里的 RingRadius=26 / FilledColumns=0 就是这个。
        //   另外还踩过一次：用 maixr:overworld_reborn 当源时，源里有伊甸枢纽总站，
        //   修复圈扫到那儿就把整座总站搬进了破碎世界 —— 所以专门做了 maixr:overworld_pristine。
        ServerLevel source = server.getLevel(PRISTINE);      // 首选：纯净主世界（没有总站、没有任何改动）
        if (source == null) {
            source = server.getLevel(REBORN);                // 退而求其次：重生世界（注意里面有伊甸总站）
        }
        if (source == null) {
            source = server.getLevel(Level.OVERWORLD);       // 最后才用主世界（可能压根是空岛）
        }
        if (source != null && !sourceLogged) {
            sourceLogged = true;
            MaixrMod.LOGGER.info("[伊甸] 修复源 = {}（同种子同预设，用它当「这个世界原本该有的地形」）",
                    source.dimension().location());
            // ★ 自检：源维度在圆心附近到底有没有地形？
            //   本包主世界是 SkyblockBuilder 空岛 —— 如果谁又把源换回主世界，
            //   或者在空岛世界里用主世界当源，这里会立刻吼出来，而不是让玩家对着"修不动的世界"发呆。
            int probes = 0;
            int empty = 0;
            for (int off : new int[]{0, 96, -96}) {
                int px = progress.centerX + off;
                int pz = progress.centerZ;
                LevelChunk probe = source.getChunk(px >> 4, pz >> 4);
                boolean anyBlock = false;
                for (int y = EdenRepair.Y_MIN; y <= 120 && !anyBlock; y++) {
                    if (!probe.getBlockState(new BlockPos(px, y, pz)).isAir()) anyBlock = true;
                }
                probes++;
                if (!anyBlock) empty++;
            }
            if (empty == probes) {
                MaixrMod.LOGGER.error("[伊甸] ★★★ 修复源 {} 在圆心附近【完全没有方块】——"
                                + " 这个维度多半是空岛/虚空世界（例如 SkyblockBuilder）。"
                                + " 从它搬地形等于搬空气，修复不会有任何效果！"
                                + " 请把修复源指回 maixr:overworld_reborn。",
                        source.dimension().location());
            }
        }

        // 总站残骸清理（如果正在跑）
        tickCleanup(broken);

        if (source != null && progress.canRepair()) {
            EdenRepair.step(broken, source, progress);
            // ★ 修复要花时间，给玩家一个看得见的进度条
            if (broken.getGameTime() % 10 == 0) {
                int target = Math.max(1, progress.targetRadius());
                int done = Math.min(progress.ringRadius, target);
                int pct = done * 100 / target;
                String bar = progressBar(pct);
                for (ServerPlayer p : broken.players()) {
                    p.displayClientMessage(Component.literal(
                            "§b[伊甸] 修复中 " + bar + " §f" + pct + "%§7  半径 " + progress.ringRadius
                                    + "/" + progress.targetRadius()), true);
                }
            }
        }

        // ★ 预加载重生世界（伊甸枢纽）的区块：枢纽占地约 17x17 区块，
        //   如果等玩家进去才现生成，会明显卡顿甚至丢区块。这里从开服起每 tick 生成 1 个。
        if (preloadQueue == null) {
            java.util.List<Long> list = new java.util.ArrayList<>();
            for (int cx = -9; cx <= 8; cx++) {
                for (int cz = -9; cz <= 8; cz++) {
                    list.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz));
                }
            }
            preloadQueue = list;
        }
        ServerLevel rebornLevel = server.getLevel(REBORN);
        if (rebornLevel != null && preloadAt < preloadQueue.size()) {
            for (int i = 0; i < 3 && preloadAt < preloadQueue.size(); i++) {     // 每 tick 3 个，约 5.4 秒铺完
                long key = preloadQueue.get(preloadAt++);
                rebornLevel.getChunk(net.minecraft.world.level.ChunkPos.getX(key),
                        net.minecraft.world.level.ChunkPos.getZ(key));
            }
            if (preloadAt == preloadQueue.size()) {
                MaixrMod.LOGGER.info("[伊甸] 重生世界枢纽区块预加载完成（{} 个），开始静默建造总站",
                        preloadQueue.size());
            }
        }

        if (++tickCounter < 20) return;
        tickCounter = 0;

        // 圆心 + 平台就位（没定过 / 逻辑升级了 / 定下来的地其实是虚空 → 重新锚定）
        ensureCenterAndPlatform(server, broken, progress);

        // 把世界状态同步给所有客户端（动态天空用）
        MaixrNetwork.sendEdenSync(progress.completeness(),
                progress.stageOf(progress.completeness()),
                progress.invasionActive, progress.invasionWave, progress.requiredItem);

        // ★ 入侵（一波波刷怪）已按需求整套删除：现在只有 90% 那一只最终 Boss

        // 纪元判定
        int completeness = progress.completeness();
        int newStage = progress.stageOf(completeness);
        if (newStage > progress.stage) {
            progress.stage = newStage;
            progress.markDirty();
            broadcast(server, "§b§l[弧光] §r§b世界完整度 " + (completeness / 10) + "% —— 进入纪元 · §e"
                    + EdenStage.name(newStage));
            growPlatform(broken, progress, newStage);
            broadcast(server, "§b[伊甸] 平台向外扩张了一圈。");
            thankTopContributor(server, progress);
            notifyOtherSide(server);
            celebrate(broken, new BlockPos(progress.centerX,
                    Math.max(progress.centerY, broken.getMinBuildHeight() + 1) + 3, progress.centerZ), newStage);
            maybeSpawnFinalBoss(server, broken, progress, newStage);
        } else if (newStage < progress.stage) {
            progress.stage = newStage;
            progress.markDirty();
            broadcast(server, "§4§l[弧光] §r§4世界衰败，退回纪元 · §c" + EdenStage.name(newStage));
        }

        // ★ 兜底：完整度已经在 90% 以上，但 Boss 从来没出现过 —— 补刷一次。
        //   为什么需要：Boss 原本只在"跨进 90% 那一瞬间"触发。老存档跨版本升上来、
        //   或者管理员用 /maixr eden set 直接跳上去，那一瞬间早就过去了，Boss 就永远不会来。
        //   bossSpawned 标志保证这里只会真的执行一次。
        if (!progress.bossSpawned && newStage >= BOSS_STAGE) {
            maybeSpawnFinalBoss(server, broken, progress, newStage);
        }

        // 里程碑成就（10% / 50% / 90%）
        grantMilestone(server, progress, completeness, 100, 0, "eden_10");
        grantMilestone(server, progress, completeness, 500, 1, "eden_50");
        grantMilestone(server, progress, completeness, 900, 2, "eden_90");

        // 100% 终局
        if (!progress.finished && completeness >= EdenProgress.MAX) {
            progress.finished = true;
            progress.markDirty();
            finish(server, broken);
        }
    }

    // ------------------------------------------------------------------ 总站残骸清理

    /** 待清理的位置队列（伊甸总站的方块坐标） */
    private static long[] cleanQueue = null;
    private static int cleanAt = 0;
    private static int cleanChanged = 0;
    private static int cleanProbe = 0;
    private static final int CLEAN_PER_TICK = 4000;

    /** 清理是否正在进行（给命令用） */
    public static boolean isCleanupRunning() {
        return cleanQueue != null;
    }

    /**
     * 把被"当地形搬进来"的伊甸总站从破碎世界里抹掉。
     *
     * <p>做法：拿总站结构里那 60 万个方块坐标，逐个对照纯净主世界 ——
     * 该是空气的补回空气、该是地形的补回地形。只碰这些坐标，不碰玩家的建筑。
     *
     * @return 需要清理的方块数；-1 = 读不到结构文件；0 = 已经在跑
     */
    public static int startHubCleanup(ServerLevel broken) {
        if (cleanQueue != null) return 0;
        long[] queue = loadHubPositions();
        if (queue == null || queue.length == 0) return -1;
        cleanQueue = queue;
        cleanAt = 0;
        cleanChanged = 0;
        cleanProbe = 0;
        MaixrMod.LOGGER.info("[伊甸] 开始清理总站残骸：{} 个方块位置", queue.length);
        return queue.length;
    }

    private static long[] loadHubPositions() {
        try (java.io.InputStream in = EdenHubHandler.openHubNbt()) {
            if (in == null) return null;
            net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.NbtIo.readCompressed(in, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            net.minecraft.nbt.ListTag blocks = tag.getList("blocks", 10);
            long[] out = new long[blocks.size()];
            for (int i = 0; i < blocks.size(); i++) {
                net.minecraft.nbt.CompoundTag b = blocks.getCompound(i);
                net.minecraft.nbt.ListTag pl = b.getList("pos", 3);
                out[i] = BlockPos.asLong(EdenHubHandler.ORIGIN.getX() + pl.getInt(0),
                        EdenHubHandler.ORIGIN.getY() + pl.getInt(1),
                        EdenHubHandler.ORIGIN.getZ() + pl.getInt(2));
            }
            return out;
        } catch (Throwable e) {
            MaixrMod.LOGGER.error("[伊甸] 读取总站结构失败：{}", e.toString());
            return null;
        }
    }

    private static void tickCleanup(ServerLevel broken) {
        if (cleanQueue == null) return;
        MinecraftServer server = broken.getServer();
        if (server == null) return;
        ServerLevel source = server.getLevel(PRISTINE);
        if (source == null) {
            MaixrMod.LOGGER.error("[伊甸] 没有 maixr:overworld_pristine 维度，清理中止"
                    + "（拿总站所在的维度当参考只会把总站又搬回来）");
            cleanQueue = null;
            return;
        }
        int done = 0;
        while (done < CLEAN_PER_TICK && cleanAt < cleanQueue.length) {
            BlockPos pos = BlockPos.of(cleanQueue[cleanAt++]);
            done++;
            cleanProbe++;
            net.minecraft.world.level.block.state.BlockState want = source.getBlockState(pos);
            if (broken.getBlockState(pos) == want) continue;
            broken.setBlock(pos, want, 2);
            cleanChanged++;
        }
        long gameTime = broken.getGameTime();
        if (gameTime % 20 == 0) {
            int pct = cleanAt * 100 / cleanQueue.length;
            for (ServerPlayer p : broken.players()) {
                p.displayClientMessage(Component.literal(
                        "§b[伊甸] 清理总站残骸 " + pct + "%§7  已复原 " + cleanChanged + " 个方块"), true);
            }
        }
        if (cleanAt >= cleanQueue.length) {
            broadcast(server, "§b[伊甸] 总站残骸清理完成：检查 " + cleanProbe + " 个位置，复原 " + cleanChanged + " 个方块。");
            MaixrMod.LOGGER.info("[伊甸] 总站残骸清理完成：{} / {}", cleanChanged, cleanProbe);
            cleanQueue = null;
            cleanAt = 0;
        }
    }

    // ------------------------------------------------------------------ 最终 Boss

    // ------------------------------------------------------------------ 终局

    private static void finish(MinecraftServer server, ServerLevel broken) {
        broadcast(server, "§d§l[弧光] §r§d世界完整度 100%。破碎的世界重新完整了。");
        broadcast(server, "§d有一些东西，从虚空的中心浮了上来。");

        // 命令方块的掉落物漂浮在 (0, 100, 0)
        net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(
                broken, 0.5D, 100.0D, 0.5D, new ItemStack(net.minecraft.world.item.Items.COMMAND_BLOCK));
        drop.setNoGravity(true);          // 漂浮
        drop.setInvulnerable(true);       // 不会被破坏
        drop.setGlowingTag(true);         // 发光，好找
        drop.setUnlimitedLifetime();      // 永不消失，等着玩家来捡
        broken.addFreshEntity(drop);

        for (ServerPlayer player : broken.players()) {
            player.displayClientMessage(Component.literal("§d[弧光] §f去 (0, 100, 0) 看看。"), false);
            MaixrEdenCommands.grantMyWorld(player);
        }
    }

    private static void broadcast(MinecraftServer server, String text) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
    }

    // ------------------------------------------------------------------ 刷怪压制

    /**
     * 每跨一个纪元，伊甸平台向外长一圈。
     *
     * <p>★ 修正：以前这里写的是 {@code new BlockPos(dx, 130, dz)} —— 用的是<b>绝对坐标</b>，
     * 所以"平台扩张"其实一直长在原点 (0,130,0) 的高空，跟圆心平台毫无关系。
     * 现在改成以圆心为准，高度也用圆心那块地的真实地面高度。
     */
    private static void growPlatform(ServerLevel broken, EdenProgress progress, int stage) {
        int y = Math.max(progress.centerY, broken.getMinBuildHeight() + 1);
        int r = 2 + stage;                       // 纪元 0 是 5x5(半径2)，每升一档 +1
        net.minecraft.world.level.block.Block frame = MaixrMod.EDEN_FRAME.get();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                broken.setBlockAndUpdate(new BlockPos(progress.centerX + dx, y, progress.centerZ + dz),
                        frame.defaultBlockState());
            }
        }
    }

    /** 点名感谢本纪元提交最多的玩家 */
    private static void thankTopContributor(MinecraftServer server, EdenProgress progress) {
        UUID top = null;
        int best = 0;
        for (java.util.Map.Entry<UUID, Integer> e : progress.contribution.entrySet()) {
            if (e.getValue() > best) { best = e.getValue(); top = e.getKey(); }
        }
        if (top == null) return;
        String name = progress.uuidNames.getOrDefault(top, "某人");
        broadcast(server, "§e§l[伊甸] §r§e感谢 §f" + name + " §e——他已为这个世界提交了 §f" + best + " §e次。");
    }

    /** 让不在破碎世界的玩家也感到"有什么在回应" */
    private static void notifyOtherSide(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension().equals(BROKEN)) continue;
            player.displayClientMessage(Component.literal("§b§o彼岸有什么在回应我们……"), true);
        }
    }

    /** 达到阈值就授予里程碑成就 */
    private static void grantMilestone(MinecraftServer server, EdenProgress progress,
                                        int completeness, int threshold, int bit, String advancementPath) {
        if (completeness < threshold) return;
        if ((progress.milestones & (1 << bit)) != 0) return;
        progress.milestones |= (1 << bit);
        progress.markDirty();
        net.minecraft.advancements.AdvancementHolder adv = server.getAdvancements()
                .get(ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, advancementPath));
        if (adv == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.getAdvancements().award(adv, "granted");
        }
    }

    /**
     * 90% 那一档召唤最终 Boss —— 只召唤一次，不刷任何小怪。
     *
     * <p>以前这里是「偶数纪元开入侵」：怪表 + 一波波刷怪 + 打完才允许继续修。
     * 入侵整套删掉后，只剩这一只自定义 Boss。
     */
    private static void maybeSpawnFinalBoss(MinecraftServer server, ServerLevel broken,
                                            EdenProgress progress, int newStage) {
        if (newStage < BOSS_STAGE) return;
        if (progress.bossSpawned) return;                 // 一辈子只打一次
        progress.bossSpawned = true;
        progress.invasionActive = true;                   // 复用这个标志：告诉客户端"祂在场"（血条/天空）
        progress.invasionWave = 1;
        progress.invasionMaxWave = 1;
        progress.markDirty();

        // ★ 刷在伊甸核心机正上方（不是 y=150 空投）：玩家抬头就看见祂从核心机里升起来。
        //   往上找一处"连着两格空气"的位置，免得刚好卡在方块/平台框架里。
        int baseY = Math.max(progress.centerY, broken.getMinBuildHeight() + 1) + 2;
        int y = baseY;
        for (int i = 0; i < 16; i++) {
            BlockPos a = new BlockPos(progress.centerX, baseY + i, progress.centerZ);
            if (broken.getBlockState(a).isAir() && broken.getBlockState(a.above()).isAir()) {
                y = baseY + i;
                break;
            }
        }

        ArcLightBoss.clearExisting(broken);        // ★ 世界里只允许存在一只（也就只有一条血条）
        ArcLightBoss boss = MaixrMod.ARC_LIGHT_BOSS.get().create(broken);
        if (boss == null) {
            MaixrMod.LOGGER.error("[弧光] Boss 实体创建失败，最终 Boss 没能出现");
            return;
        }
        boss.moveTo(progress.centerX + 0.5D, y, progress.centerZ + 0.5D,
                broken.random.nextFloat() * 360F, 0F);
        boss.setPersistenceRequired();
        // ★ 常驻发光：隔着方块也能看见轮廓，玩家不用满世界找祂在哪
        boss.setGlowingTag(true);
        boss.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.GLOWING, Integer.MAX_VALUE, 0, false, false));
        spawningInvasion = true;
        boolean ok = broken.addFreshEntity(boss);
        spawningInvasion = false;
        if (!ok) {
            MaixrMod.LOGGER.error("[弧光] Boss 生成被拒（addFreshEntity 返回 false）");
            return;
        }
        progress.invasionMobs.clear();
        progress.invasionMobs.add(boss.getUUID());
        progress.markDirty();

        broadcast(server, "§5§l[弧光] §r§5裂隙的中心睁开了眼睛 —— 祂从伊甸核心机里升起来了（发光的那一团）。");
        MaixrMod.LOGGER.info("[弧光] 最终 Boss 已在伊甸核心机上方 ({}, {}, {}) 生成（完整度 {}‰，已发光）",
                progress.centerX, y, progress.centerZ, progress.completeness());
    }


    /** 进入新纪元放一串烟花庆祝 */
    private static void celebrate(ServerLevel level, BlockPos center, int stage) {
        for (int i = 0; i < 6; i++) {
            // 【1.21 移植】烟花改用数据组件：Fireworks 是 record，爆炸是 FireworkExplosion record。
            // 1.20.1 那种 "往 NBT 里塞 Fireworks/Explosions/Colors" 的写法已失效。
            ItemStack rocket = new ItemStack(net.minecraft.world.item.Items.FIREWORK_ROCKET);
            var explosion = new net.minecraft.world.item.component.FireworkExplosion(
                    net.minecraft.world.item.component.FireworkExplosion.Shape.LARGE_BALL,
                    it.unimi.dsi.fastutil.ints.IntList.of(
                            EdenStage.sky(stage), EdenStage.fog(stage), 0xFFFFFF, 0xFF99D0),
                    it.unimi.dsi.fastutil.ints.IntList.of(),
                    true,    // hasTrail
                    true);   // hasTwinkle（原来是 Flicker）
            rocket.set(net.minecraft.core.component.DataComponents.FIREWORKS,
                    new net.minecraft.world.item.component.Fireworks(
                            1 + level.random.nextInt(2), java.util.List.of(explosion)));

            double x = center.getX() + 0.5D + (level.random.nextDouble() - 0.5D) * 14.0D;
            double z = center.getZ() + 0.5D + (level.random.nextDouble() - 0.5D) * 14.0D;
            net.minecraft.world.entity.projectile.FireworkRocketEntity fw =
                    new net.minecraft.world.entity.projectile.FireworkRocketEntity(
                            level, x, center.getY(), z, rocket);
            level.addFreshEntity(fw);
        }
    }

    /** 重生世界枢纽区块是否已经预加载完（总站的建造要等这个） */
    public static boolean isHubPreloadDone() {
        return preloadQueue != null && preloadAt >= preloadQueue.size();
    }

    /** 画一条十格的进度条 */
    private static String progressBar(int percent) {
        int filled = Math.max(0, Math.min(10, percent / 10));
        StringBuilder sb = new StringBuilder("§a");
        for (int i = 0; i < 10; i++) {
            if (i == filled) sb.append("§8");
            sb.append(i < filled ? "■" : "□");
        }
        return sb.toString();
    }


    /** 圆心选取逻辑的版本号：改了选取规则就 +1，存档里的版本更低时下次开服自动重新锚定 */
    private static final int CENTER_VERSION = 3;

    /** 把修复环游标推回圆心（给 /maixr eden set|add|refill 用） */
    public static void resetRepairCursor(EdenProgress progress) {
        progress.ringRadius = 0;
        progress.ringChunkIndex = 0;
        progress.currentChunk = -1L;
        progress.cursor = 0;
    }

    /** 强制重新锚定圆心（给 /maixr eden reanchor 用） */
    public static void reanchor(MinecraftServer server, ServerLevel broken) {
        EdenProgress progress = EdenProgress.get(broken);
        progress.centerVersion = 0;
        progress.centerChosen = false;
        progress.platformPlaced = false;
        progress.markDirty();
        centerCheckedThisSession = true;
        chooseCenterAndPlatform(server, broken, progress);
    }

    /**
     * 保证「圆心 + 平台」已经就位。
     *
     * <p>下面任何一种情况都会重新锚定圆心：
     * <ul>
     *   <li>从来没定过（新世界）</li>
     *   <li>存档里的选取版本号 &lt; {@link #CENTER_VERSION}（逻辑升级了）</li>
     *   <li>定下来的那块地量出来是空的 —— 旧比例时代生成并落盘的虚空块，
     *       哈希判定和磁盘上的地形对不上，平台会被摆到世界最底下，谁也看不见</li>
     * </ul>
     *
     * <p>★ 一个开服周期只验一次：验证要把圆心那个区块加载出来，要是每秒都验，
     * 玩家一走远这个区块就会被反复加载 / 卸载（TPS 杀手 + 存档反复变脏）。
     */
    private static boolean centerCheckedThisSession = false;

    private static void ensureCenterAndPlatform(MinecraftServer server, ServerLevel broken, EdenProgress progress) {
        if (centerCheckedThisSession) return;
        centerCheckedThisSession = true;
        boolean stale = progress.centerVersion < CENTER_VERSION;
        if (progress.centerChosen && progress.platformPlaced && !stale
                && groundAt(broken, progress.centerX, progress.centerZ) > broken.getMinBuildHeight()) {
            return;
        }
        MaixrMod.LOGGER.info("[伊甸] Boss 实体注册检查: key={} 说明={}",
                BuiltInRegistries.ENTITY_TYPE.getKey(MaixrMod.ARC_LIGHT_BOSS.get()),
                MaixrMod.ARC_LIGHT_BOSS.get().getDescription().getString());
        if (stale && progress.centerChosen) {
            MaixrMod.LOGGER.info("[伊甸] 圆心选取逻辑升级到 v{}（旧锚点 {} / {}，地面 y={}），重新锚定",
                    CENTER_VERSION, progress.centerX, progress.centerZ, progress.centerY);
        }
        chooseCenterAndPlatform(server, broken, progress);
    }

    /**
     * 挑圆心。
     *
     * <p>分两档找：
     * <ol>
     *   <li>「活着的陆地 + 平原」—— 最理想：玩家和核心机站在真实的平原地面上</li>
     *   <li>「任何活着的陆地」—— 95% 都是虚空的比例下，平原不一定就在近处</li>
     * </ol>
     * 两档都不成，才退回原点 (0, 0)。
     *
     * <p>高度不跟地形走，见 {@link #pickPlatformY}：128 → 196 → 256。
     */
    private static void chooseCenterAndPlatform(MinecraftServer server, ServerLevel broken, EdenProgress progress) {
        int[] anchor = findAnchor(broken, true, 96);
        String how = "平原";
        if (anchor == null) {
            anchor = findAnchor(broken, false, 160);
            how = "陆地";
        }

        int cx, cz;
        if (anchor != null) {
            cx = anchor[0];
            cz = anchor[1];
        } else {
            cx = 0;
            cz = 0;
            how = "兜底虚空";
        }
        // ★ 平台高度是固定三档，不跟地形走：128 → 196 → 256，取第一个整片空着的那一档
        int y = pickPlatformY(broken, cx, cz);

        // 圆心搬了就把旧平台拆干净 —— 不然会留下第二个核心机（旧框架会一直挂在那儿）
        if (progress.centerChosen) {
            eraseOldPlatform(broken, progress);
        }

        net.minecraft.world.level.block.Block frame = MaixrMod.EDEN_FRAME.get();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                broken.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), frame.defaultBlockState());
            }
        }
        broken.setBlockAndUpdate(new BlockPos(cx, y, cz), MaixrMod.EDEN_CORE.get().defaultBlockState());
        broken.setDefaultSpawnPos(new BlockPos(cx, y + 1, cz), 0F);

        // 圆心换了 → 旧的环游标作废，从新圆心重新向外修
        progress.centerX = cx;
        progress.centerY = y;
        progress.centerZ = cz;
        progress.centerChosen = true;
        progress.platformPlaced = true;
        progress.centerVersion = CENTER_VERSION;
        progress.ringRadius = 0;
        progress.ringChunkIndex = 0;
        progress.currentChunk = -1L;
        progress.cursor = 0;
        progress.markDirty();

        MaixrMod.LOGGER.info("[伊甸] 圆心定为 ({}, {}), 平台 y={}, 来源={}, 该处虚空比例={}%（出生点 {}% → 最远 {}%）",
                cx, cz, y, how,
                (int) Math.round(BrokenOverworldChunkGenerator.voidChance(cx >> 4, cz >> 4) * 100),
                (int) Math.round(BrokenOverworldChunkGenerator.NEAR_VOID_CHANCE * 100),
                (int) Math.round(BrokenOverworldChunkGenerator.FAR_VOID_CHANCE * 100));
        broadcast(server, "§b[伊甸] 世界中心已锚定在 §f(" + cx + ", " + y + ", " + cz + ")"
                + "§b，伊甸核心机已就位。所有修复都从这个圆心向外扩散。");
    }

    /**
     * 拆掉旧圆心上的平台（框架 + 核心机）。
     *
     * <p>平台每跨一个纪元向外长一圈，最大半径 2 + 9 = 11，所以按 12 格清。
     * 只清「伊甸框架 / 伊甸核心」这两种方块，别的都不碰。
     */
    private static void eraseOldPlatform(ServerLevel broken, EdenProgress progress) {
        if (progress.centerY <= broken.getMinBuildHeight()) return;
        net.minecraft.world.level.block.Block frame = MaixrMod.EDEN_FRAME.get();
        net.minecraft.world.level.block.Block core = MaixrMod.EDEN_CORE.get();
        int removed = 0;
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                BlockPos pos = new BlockPos(progress.centerX + dx, progress.centerY, progress.centerZ + dz);
                net.minecraft.world.level.block.state.BlockState state = broken.getBlockState(pos);
                if (state.is(frame) || state.is(core)) {
                    broken.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    removed++;
                }
            }
        }
        if (removed > 0) {
            MaixrMod.LOGGER.info("[伊甸] 已拆掉旧平台（{} / {} / {}）上的 {} 个方块",
                    progress.centerX, progress.centerY, progress.centerZ, removed);
        }
    }

    /** 平台高度的三档：从上往下取第一个「5×5 全是空气」的 */
    private static final int[] PLATFORM_Y = {128, 196, 256};

    /**
     * 挑平台高度：128 → 196 → 256。
     *
     * <p>规则：优先 128；那一档有方块就抬到 196；还占着就用 256；三档都占着也是 256。
     * 95% 虚空的世界里绝大多数位置 128 就是空气，所以平台基本都落在 y=128。
     */
    private static int pickPlatformY(ServerLevel level, int cx, int cz) {
        level.getChunk(cx >> 4, cz >> 4);        // 先真正加载/生成，否则读到的是没加载的空壳
        for (int y : PLATFORM_Y) {
            boolean free = true;
            for (int dx = -2; dx <= 2 && free; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (!level.getBlockState(new BlockPos(cx + dx, y, cz + dz)).isAir()) {
                        free = false;
                        break;
                    }
                }
            }
            if (free) return y;
        }
        return PLATFORM_Y[PLATFORM_Y.length - 1];
    }

    /**
     * 从原点向外找一个能当圆心的区块，返回 {@code {x, z, 地面y}}，找不到返回 null。
     *
     * <p>★ 两步走的顺序很重要：
     * <ol>
     *   <li>先用确定性哈希 {@code isVoided} 排掉虚空区块 —— 这一步<b>不生成任何区块</b></li>
     *   <li>真的看上某一块了，才把它加载出来量地面高度（这一步要生成区块，所以尽量少做）</li>
     * </ol>
     * 量出来是空的（{@code <= getMinBuildHeight()}）说明磁盘上那块其实是旧比例时代生成的虚空块，
     * 跟哈希判定对不上 —— 直接换下一块。
     *
     * @param requirePlains 是否要求平原生物群系
     * @param limit         搜索半径（单位：区块）
     */
    private static int[] findAnchor(ServerLevel broken, boolean requirePlains, int limit) {
        int probed = 0;
        for (int r = 0; r <= limit; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    if (BrokenOverworldChunkGenerator.isVoided(dx, dz)) continue;   // 必须是活下来的陆地
                    int bx = dx * 16 + 8;
                    int bz = dz * 16 + 8;
                    if (requirePlains && !isPlains(broken, bx, bz)) continue;
                    if (++probed > 32) return null;         // 量了 32 块都不成，别再往地图上撒区块了
                    int y = groundAt(broken, bx, bz);
                    if (y <= broken.getMinBuildHeight()) continue;
                    return new int[]{bx, bz, y};
                }
            }
        }
        return null;
    }

    /**
     * 某处方块的真实地面高度 —— 必须<b>先把区块真正加载/生成出来</b>再读高度图。
     *
     * <p>★ 这就是"伊甸平台不会生成"的元凶：{@code LevelReader.getHeight} 在区块还没加载时
     * 既不生成也不报错，直接返回 {@code getMinBuildHeight()}（这个世界是 -64）。
     * 于是平台被放到世界最底下，玩家当然什么都看不到 —— 日志里的「地面 y=-64」就是它。
     */
    private static int groundAt(ServerLevel level, int bx, int bz) {
        level.getChunk(bx >> 4, bz >> 4);      // Level.getChunk(x,z) = 按 FULL 状态加载/生成
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
    }

    /**
     * 该位置是不是"平原类"生物群系。
     *
     * <p>★ 用生物群系采样器直接算，<b>不生成任何区块</b>。
     * 如果图省事写成 {@code level.getBiome(pos)}，外围搜索会把周围一大片区块全部生成出来，
     * 首次进世界直接卡死。
     */
    private static boolean isPlains(ServerLevel level, int blockX, int blockZ) {
        try {
            var source = level.getChunkSource().getGenerator().getBiomeSource();
            var sampler = level.getChunkSource().randomState().sampler();
            var holder = source.getNoiseBiome(blockX >> 2, 64 >> 2, blockZ >> 2, sampler);
            return holder.unwrapKey()
                    .map(key -> key.location().getPath().contains("plains"))
                    .orElse(false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ★ 玩家进入破碎世界 —— 不管他从哪个口进来（重生核心、/maixr broken、别的模组的门、被裂隙吐出去又走回来），
     * 一律落在伊甸核心机的正上方。
     *
     * <p>以前重生核心把人硬编码扔在 (0, 280, 0)，而圆心早就不是原点了
     * （挑圆心会去挑最近的平原陆地），于是玩家一进来就在一片虚空里往下掉。
     * 这里做一层总兜底：任何进入该维度的方式都会被拉回核心机。
     */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!event.getTo().location().equals(BROKEN.location())) return;
        ServerLevel broken = BrokenWorld.resolve(player.server);
        if (broken == null) return;
        EdenProgress progress = EdenProgress.get(broken);
        if (!progress.centerChosen) return;                 // 还没锚定就先别动他
        double x = progress.centerX + 0.5D;
        double z = progress.centerZ + 0.5D;
        double y = Math.max(progress.centerY, broken.getMinBuildHeight() + 1) + 1.0D;
        player.teleportTo(broken, x, y, z, player.getYRot(), player.getXRot());
        player.displayClientMessage(Component.literal("§b[伊甸] 你落在了核心机的正上方。"), true);
    }

    /** 我们自己刷入侵怪的时候不要被压制逻辑拦掉 */
    private static boolean spawningInvasion = false;

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (spawningInvasion) return;
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(BROKEN)) return;
        if (!(event.getEntity() instanceof Enemy)) return;
        EdenProgress progress = EdenProgress.get(level);
        double suppress = EdenStage.suppress(progress.stageOf(progress.completeness()));
        if (suppress > 0 && level.random.nextDouble() < suppress) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ 双向刻度：衰败

    @SubscribeEvent
    public static void onLivingHurt(LivingIncomingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (isDoomItem(player.getMainHandItem())) decay(player);
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (isDoomItem(player.getMainHandItem())) decay(player);
    }

    /** 会加速世界衰败的东西：无尽贪婪系 / 红物质系 / 创造级物品（C1） */
    private static boolean isDoomItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return false;
        String path = id.getPath();
        return id.getNamespace().equals("avaritia") && path.startsWith("infinity")
                || id.getNamespace().equals("projecte") && path.startsWith("rm_")
                || path.contains("creative");
    }

    private static final java.util.Map<UUID, Long> DECAY_LAST = new java.util.HashMap<>();

    private static void decay(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel broken = BrokenWorld.resolve(server);
        if (broken == null) return;

        long now = broken.getGameTime();
        Long last = DECAY_LAST.get(player.getUUID());
        if (last != null && now - last < DECAY_COOLDOWN) return;
        DECAY_LAST.put(player.getUUID(), now);

        EdenProgress progress = EdenProgress.get(broken);
        if (progress.decay >= progress.submits * EdenProgress.PER_SUBMIT) return;   // 已经锁死在 0
        progress.decayBy(DECAY_AMOUNT);
        progress.markDirty();

        // C6：点名
        broadcast(server, "§4§l[弧光] §r§c" + player.getGameProfile().getName()
                + " §4使用了不该用的力量，世界完整度 §c-" + (DECAY_AMOUNT / 10) + "% §7（当前 "
                + (progress.completeness() / 10) + "%，5 秒内只计一次）");
    }
}
