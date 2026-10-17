package com.maixr;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 「抗下诅咒」的后果执行器。
 *
 * <p>诅咒内容：永远无法入睡、永远虚弱 X、永远挖掘疲劳 III、永远缓降、永远无法进入破碎世界维度。
 * 由于每 tick 重新施加，所以**死亡和喝牛奶都解不掉**。
 */
@EventBusSubscriber(modid = MaixrMod.MODID)
public final class MaixrRiftHandler {
    /** 破碎世界维度（见 {@link BrokenWorld}）。 */
    private static final ResourceKey<Level> BROKEN = BrokenWorld.BROKEN;

    /** 抉择窗口时长（秒） */
    public static final int CHOICE_SECONDS = 30;

    /** 抉择窗口状态 */
    private static long deadlineTick = 0L;
    private static boolean anyoneChose = false;
    private static ServerLevel riftLevel = null;
    private static net.minecraft.world.phys.Vec3 riftPos = null;

    private MaixrRiftHandler() {}

    /** Boss 死亡时开窗：弹出限时抉择 */
    public static void openChoiceWindow(ServerLevel level, net.minecraft.world.phys.Vec3 pos) {
        deadlineTick = level.getGameTime() + CHOICE_SECONDS * 20L;
        anyoneChose = false;
        riftLevel = level;
        riftPos = pos;
        for (ServerPlayer player : level.players()) {
            MaixrNetwork.sendRiftChoice(player, CHOICE_SECONDS);
        }
    }

    /** 有玩家做出了选择 */
    public static void markChosen() {
        anyoneChose = true;
    }

    /** 抉择窗口是否还开着（开着的时候入侵判定要暂停，别提前宣布"入侵已平息"） */
    public static boolean isChoicePending() {
        return deadlineTick > 0;
    }

    /**
     * 有人做出了选择 —— 立刻封印裂隙：关窗、收血条、把这场入侵判为结束。
     * （不然要等满 30 秒，而且入侵状态会一直挂着。）
     */
    public static void sealRift(ServerPlayer player) {
        deadlineTick = 0;
        anyoneChose = true;
        riftLevel = null;
        riftPos = null;
        MaixrNetwork.sendRiftBossBar(0F, 0, false);
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel broken = BrokenWorld.resolve(server);
        if (broken == null) return;
        EdenProgress progress = EdenProgress.get(broken);
        progress.invasionActive = false;
        progress.invasionMobs.clear();
        progress.invadedStages |= (1 << progress.stage);
        progress.markDirty();
    }

    /** 倒计时到点：没人选就让 Boss 满血复活 */
    private static void tickChoiceWindow(MinecraftServer server) {
        if (deadlineTick <= 0 || riftLevel == null || riftPos == null) return;
        if (riftLevel.getGameTime() < deadlineTick) return;

        long tick = deadlineTick;
        deadlineTick = 0;
        if (anyoneChose) {
            riftLevel = null;
            riftPos = null;
            return;
        }

        // 复活：满血重开（先把可能残留的旧 Boss 清干净）
        ArcLightBoss.clearExisting(riftLevel);
        net.minecraft.world.entity.EntityType<?> type = MaixrMod.ARC_LIGHT_BOSS.get();
        net.minecraft.world.entity.Entity boss = type.create(riftLevel);
        if (boss instanceof ArcLightBoss arc) {
            arc.moveTo(riftPos.x, riftPos.y, riftPos.z, arc.getYRot(), arc.getXRot());
            arc.setHealth(arc.getMaxHealth());
            arc.setPersistenceRequired();
            riftLevel.addFreshEntity(arc);
            EdenProgress progress = EdenProgress.get(riftLevel);
            progress.invasionActive = true;
            progress.invasionWave = 1;
            progress.invasionMaxWave = 1;
            progress.invadedStages &= ~(1 << progress.stage);   // 这一纪元重新变成"没打完"
            progress.invasionMobs.clear();
            progress.invasionMobs.add(arc.getUUID());
            progress.markDirty();
        }
        server.getPlayerList().broadcastSystemMessage(
                ArcLightBoss.auroraText("你辜负了弧光"), false);
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("§5[裂隙] 裂隙重新张开，祂回来了。"), false);
        riftLevel = null;
        riftPos = null;
        MaixrNetwork.sendRiftBossBar(1F, 0, true);
    }

    /**
     * 祂丢出来的 TNT：<b>只打人不炸地形</b>。
     *
     * <p>不带这个拦截的话，相位三的连环 TNT 会把玩家辛苦修好的伊甸平台炸出一堆坑。
     * （实体伤害在 Detonate 事件之前就已经结算了，所以清空方块列表只影响地形。）
     */
    @SubscribeEvent
    public static void onExplosionDetonate(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        net.minecraft.world.entity.Entity source = event.getExplosion().getDirectSourceEntity();
        if (source instanceof net.minecraft.world.entity.item.PrimedTnt tnt
                && tnt.getTags().contains(ArcLightBoss.RIFT_TNT_TAG)) {
            event.getAffectedBlocks().clear();
        }
    }

    /** 每 20 tick 重新施加一遍诅咒（覆盖式，死亡/牛奶都冲不掉） */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        tickChoiceWindow(server);

        // ★ 血条看门狗：破碎世界里一只活的 Boss 都没有时，每秒广播一次"收血条"。
        //   这样无论实体是怎么消失的（卸载 / 换维度 / 被指令清掉），屏幕上都不会留下残影条。
        if (server.getTickCount() % 20 == 0) {
            ServerLevel brokenLevel = BrokenWorld.resolve(server);
            if (brokenLevel != null && brokenLevel.getEntitiesOfClass(ArcLightBoss.class,
                    new net.minecraft.world.phys.AABB(-3.0E7D, -512.0D, -3.0E7D, 3.0E7D, 512.0D, 3.0E7D)).isEmpty()) {
                MaixrNetwork.sendRiftBossBar(0F, 0, false, 0F, 0F);
            }
        }

        if (server.getTickCount() % 20 != 0) return;
        MaixrCurses data = MaixrCurses.get(server);
        if (data.cursed.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!data.isCursed(player.getUUID())) continue;
            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 400, 9, false, false));
            player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 400, 2, false, false));
            player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 400, 0, false, false));
        }
    }

    /** 诅咒者无法入睡 */
    @SubscribeEvent
    public static void onSleep(CanPlayerSleepEvent event) {
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer sp)) return;
        MinecraftServer server = sp.getServer();
        if (server == null) return;
        if (MaixrCurses.get(server).isCursed(sp.getUUID())) {
            // 【1.21 移植】CanPlayerSleepEvent 不再用 setResult(null) 表示"能睡"。
            // 1.21 该事件的"能不能睡"由 problem 字段决定，用 setProblem 设置睡不着的理由。
            event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);
            sp.displayClientMessage(Component.literal("§5§o裂隙还在你体内翻涌，你睡不着。"), true);
        }
    }

    /** 诅咒者永远无法进入破碎世界 */
    @SubscribeEvent
    public static void onTravel(EntityTravelToDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!BrokenWorld.isBroken(event.getDimension())) return;
        MinecraftServer server = sp.getServer();
        if (server == null) return;
        if (MaixrCurses.get(server).isCursed(sp.getUUID())) {
            // 【1.21 移植】EntityTravelToDimensionEvent 是可取消事件，用 setCanceled 阻止跨维度。
            event.setCanceled(true);
            sp.displayClientMessage(Component.literal("§5§o裂隙拒绝了你 —— 你已经把灵魂押给它了。"), true);
        }
    }

    /** 抉择 1：自我凋零 —— 清空所有物品 */
    public static void selfSacrifice(ServerPlayer player) {
        markChosen();
        sealRift(player);
        player.getInventory().clearContent();                      // 背包 + 快捷栏
        player.getInventory().armor.clear();                       // 护甲
        player.getInventory().offhand.clear();                     // 副手
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastFullState();
        player.getEnderChestInventory().clearContent();            // 末影箱
        MinecraftServer server = player.getServer();
        if (server != null) {
            MaixrCurses.get(server).addSacrifice(player.getUUID());
            MaixrEdenCommands.grantAdvancement(player, "rift_self");
        }
        net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel) player.level();
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.SOUL,
                player.getX(), player.getY() + 1, player.getZ(), 120, 1.0, 1.5, 1.0, 0.05);
        level.playSound((net.minecraft.world.entity.player.Player) null, player.blockPosition(), net.minecraft.sounds.SoundEvents.WITHER_DEATH,
                net.minecraft.sounds.SoundSource.PLAYERS, 1.5F, 1.4F);
        net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList()
                .broadcastSystemMessage(Component.literal("§d§l[裂隙] §r§d" + player.getGameProfile().getName()
                        + " 把自己交了出去 —— 他身上的一切都填进了那道缝。"), false);
    }

    /** 抉择 2：抗下诅咒 —— 永久负面 */
    public static void bearCurse(ServerPlayer player) {
        markChosen();
        sealRift(player);
        MinecraftServer server = player.getServer();
        if (server != null) {
            MaixrCurses.get(server).addCurse(player.getUUID());
            MaixrEdenCommands.grantAdvancement(player, "rift_bear");
        }
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 400, 9, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 400, 2, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 400, 0, false, false));
        server.getPlayerList().broadcastSystemMessage(Component.literal("§5§l[裂隙] §r§5"
                + player.getGameProfile().getName() + " 把裂隙担在了自己身上 —— 他再也睡不着了。"), false);
    }
}