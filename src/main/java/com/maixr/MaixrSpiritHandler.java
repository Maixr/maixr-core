package com.maixr;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 三件「有故事的东西」的获取逻辑：
 *
 * <ol>
 *   <li><b>灵魂宝石</b>：玩家亲手杀死<b>自己的、好感度一级以上</b>的女仆时从尸体上掉落。</li>
 *   <li><b>封印的Ta</b>：玩家<b>双手各持一把寰宇支配之剑</b>击败另一名玩家时获得。</li>
 *   <li><b>原初背叛</b>：被上述方式击败的玩家护甲栏被清空，复活后该物品戴在头盔栏。</li>
 * </ol>
 *
 * 女仆相关代码全部走反射，这样 MaixrCore 不需要硬依赖东方小女仆。
 */
@EventBusSubscriber(modid = MaixrMod.MODID)
public final class MaixrSpiritHandler {
    /** 东方小女仆的女仆实体类名 */
    private static final String MAID_CLASS = "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid";

    /**
     * 好感度「一级」的门槛点数。
     * 取自 TLM FavorabilityManager：LEVEL_0_POINT=0 / LEVEL_1_POINT=64 /
     * LEVEL_2_POINT=192 / LEVEL_3_POINT=384。所以「一级及以上」= 点数 ≥ 64。
     */
    private static final int FAVORABILITY_LEVEL_1 = 64;

    private static final ResourceLocation INFINITY_SWORD = ResourceLocation.fromNamespaceAndPath("avaritia", "infinity_sword");

    private MaixrSpiritHandler() {}

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Entity attacker = event.getSource().getEntity();

        // ① 亲手杀死自己的、好感一级以上的女仆 -> 灵魂宝石
        if (isMaid(victim)) {
            trySoulGem(victim, killerOf(victim, event.getSource()), true);
        }

        // ② 双持寰宇支配之剑击败其他玩家
        if (victim instanceof ServerPlayer dead && attacker instanceof ServerPlayer killer
                && !dead.getUUID().equals(killer.getUUID()) && wieldsInfinitySword(killer)) {
            giveOrDrop(killer, new ItemStack(MaixrMod.SEALED_TA.get()));
            grantAdvancement(killer, "sealed_ta");

            clearArmor(dead);
            MinecraftServer server = serverLevel.getServer();
            if (server != null) {
                MaixrPendingBetrayal.get(server).mark(dead.getUUID());
            }
            grantAdvancement(dead, "primordial_betrayal");
        }
    }

    /**
     * ★ 兜底：有些武器会<b>绕过 {@code LivingDeathEvent}</b>。
     *
     * <p>实测：Re-Avaritia 的寰宇支配之剑在「无尽攻击」模式下自己复刻了一份
     * {@code LivingEntity.die()} 的方法体，里面调的是 {@code dropAllDeathLoot(source)} 而不是
     * {@code die(source)} —— 于是 {@code LivingDeathEvent} 根本不发，玩家用那把剑杀女仆永远不掉宝石。
     * 但 {@code dropAllDeathLoot} 末尾一定会发 {@code LivingDropsEvent}，所以在这儿再兜一次。
     */
    @SubscribeEvent
    public static void onLivingDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event) {
        LivingEntity victim = event.getEntity();
        if (!isMaid(victim)) {
            return;
        }
        trySoulGem(victim, killerOf(victim, event.getSource()), false);
    }

    /**
     * 掉落判定。两条路径都会走到这里，用 UUID 去重，保证只会掉一颗。
     *
     * @param intoDrops true = 由 {@code LivingDeathEvent} 触发（自己 spawn）；
     *                  false = 由 {@code LivingDropsEvent} 触发（塞进掉落列表，由 Forge 生成）
     */
    private static void trySoulGem(LivingEntity victim, ServerPlayer owner, boolean intoDrops) {
        UUID ownerId = ownerOf(victim);
        int fav = favorabilityOf(victim);
        MaixrMod.LOGGER.info("[灵魂宝石] 女仆死亡: 击杀者={} 主人={} 好感={} 入口={}",
                owner == null ? "无" : owner.getGameProfile().getName(), ownerId, fav,
                intoDrops ? "死亡事件" : "掉落事件");
        if (owner == null || !owner.getUUID().equals(ownerId) || fav < FAVORABILITY_LEVEL_1) {
            return;
        }
        if (!claimDrop(victim.getUUID())) {
            return;                                   // 另一个入口已经发过了
        }
        victim.spawnAtLocation(new ItemStack(MaixrMod.SOUL_GEM.get()));
        grantAdvancement(owner, "soul_gem");
        MaixrMod.LOGGER.info("[灵魂宝石] 已掉落给 {}", owner.getGameProfile().getName());
    }

    /** 击杀者：先看伤害来源的实体，再退回战斗记录（有些武器不设 lastHurtByMob） */
    private static ServerPlayer killerOf(LivingEntity victim, net.minecraft.world.damagesource.DamageSource source) {
        if (source != null && source.getEntity() instanceof ServerPlayer p) {
            return p;
        }
        if (victim.getKillCredit() instanceof ServerPlayer p) {
            return p;
        }
        return null;
    }

    /** 同一次死亡只发一颗宝石（死亡事件 / 掉落事件都会进来，顺序不固定） */
    private static final java.util.Map<UUID, Long> GEM_GIVEN = new java.util.HashMap<>();

    private static boolean claimDrop(UUID victimId) {
        long now = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer() == null
                ? 0L : net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer().getTickCount();
        if (GEM_GIVEN.size() > 512) {
            GEM_GIVEN.entrySet().removeIf(e -> now - e.getValue() > 200L);
        }
        Long last = GEM_GIVEN.get(victimId);
        if (last != null && now - last <= 200L) {
            return false;
        }
        GEM_GIVEN.put(victimId, now);
        return true;
    }

    /** 复活后把【原初背叛】戴进头盔栏。 */
    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null || !MaixrPendingBetrayal.get(server).consume(player.getUUID())) {
            return;
        }
        // PlayerInventory.armor: 0=靴 1=腿 2=胸 3=头
        player.getInventory().armor.set(3, new ItemStack(MaixrMod.PRIMORDIAL_BETRAYAL.get()));
        player.inventoryMenu.broadcastFullState();
    }

    // ---------------- 工具方法 ----------------

    /**
     * 判断这是不是东方小女仆的女仆。
     *
     * <p>不再用「类名字符串完全相等」——那样只要 TLM 换了包路径、或者有子类/包装类就会全部失效。
     * 改成：沿着继承链找 TLM 的女仆类，或者「TLM 包下 + 有 getFavorability 方法」也算。
     */
    private static boolean isMaid(LivingEntity entity) {
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (MAID_CLASS.equals(c.getName())) return true;
        }
        if (entity.getClass().getName().startsWith("com.github.tartaricacid.touhoulittlemaid.")) {
            try {
                entity.getClass().getMethod("getFavorability");
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    /** 女仆的主人 UUID（拿不到返回 null） */
    private static UUID ownerOf(LivingEntity maid) {
        if (maid instanceof TamableAnimal tamable) {
            return tamable.getOwnerUUID();
        }
        return null;
    }

    /** 反射读 TLM 的好感度点数；读不到返回 -1（当作不满足条件） */
    private static int favorabilityOf(LivingEntity maid) {
        try {
            Object value = maid.getClass().getMethod("getFavorability").invoke(maid);
            return value instanceof Integer i ? i : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean wieldsInfinitySword(ServerPlayer player) {
        Item sword = BuiltInRegistries.ITEM.get(INFINITY_SWORD);
        if (sword == null) {
            return false;
        }
        return player.getMainHandItem().is(sword) && player.getOffhandItem().is(sword);
    }

    private static void clearArmor(ServerPlayer player) {
        NonNullList<ItemStack> armor = player.getInventory().armor;
        for (int i = 0; i < armor.size(); i++) {
            armor.set(i, ItemStack.EMPTY);
        }
        player.inventoryMenu.broadcastFullState();
    }

    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static void grantAdvancement(ServerPlayer player, String path) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        AdvancementHolder advancement = server.getAdvancements()
                .get(ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, path));
        if (advancement == null) {
            MaixrMod.LOGGER.warn("[MaixrCore] 找不到成就 maixr:{}，请检查 data/maixr/advancements/", path);
            return;
        }
        player.getAdvancements().award(advancement, "granted");
    }

    /** 仅用于给玩家看的一句提示（保留给后续扩展） */
    @SuppressWarnings("unused")
    private static void tell(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), false);
    }
}
