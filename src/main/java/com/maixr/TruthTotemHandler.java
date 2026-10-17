package com.maixr;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

/** 真理玩偶：佩戴在头（头盔槽 / Curios 头饰槽）或握在主/副手时，死亡触发不死图腾保护并消耗 1 个。 */
@EventBusSubscriber(modid = MaixrMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TruthTotemHandler {
    /** 与不死图腾完全一致：再生 II 45 秒（900 刻）。 */
    private static final int REGEN_DURATION = 900;
    /** 吸收 II 5 秒（100 刻）。 */
    private static final int ABSORPTION_DURATION = 100;

    private TruthTotemHandler() {}

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;

        if (!consumeOneTruthDoll(entity)) return;

        event.setCanceled(true);
        // —— 复刻 vanilla 不死图腾（TotemItem 保护逻辑）——
        entity.setHealth(1.0F);
        entity.removeAllEffects();
        entity.addEffect(new MobEffectInstance(MobEffects.REGENERATION, REGEN_DURATION, 1));
        entity.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, ABSORPTION_DURATION, 1));
        if (entity instanceof ServerPlayer sp) {
            sp.awardStat(Stats.ITEM_USED.get(MaixrMod.TRUTH_DOLL_ITEM.get()));
            MaixrNetwork.sendTruthTotemTo(sp); // 客户端播放玩偶图标动画
        }
    }

    /** 依次检查：头盔槽 → Curios 头饰槽 → 副手 → 主手，找到真理玩偶并消耗 1 个。 */
    private static boolean consumeOneTruthDoll(LivingEntity entity) {
        // 头盔槽
        ItemStack head = entity.getItemBySlot(EquipmentSlot.HEAD);
        if (isTruthDoll(head)) {
            head.shrink(1);
            return true;
        }
        // Curios 头饰槽（仅玩家且 curios 存在）
        if (entity instanceof Player player && MaixrCurios.isLoaded()) {
            try {
                var handler = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).orElse(null);
                if (handler != null) {
                    var stacks = handler.getStacksHandler("head").orElse(null);
                    if (stacks != null) {
                        ItemStack curiosStack = stacks.getStacks().getStackInSlot(0);
                        if (isTruthDoll(curiosStack)) {
                            curiosStack.shrink(1);
                            handler.setEquippedCurio("head", 0, curiosStack);
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // curios 异常时继续检查手部
            }
        }
        // 副手
        ItemStack off = entity.getOffhandItem();
        if (isTruthDoll(off)) {
            off.shrink(1);
            return true;
        }
        // 主手
        ItemStack main = entity.getMainHandItem();
        if (isTruthDoll(main)) {
            main.shrink(1);
            return true;
        }
        return false;
    }

    private static boolean isTruthDoll(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == MaixrMod.TRUTH_DOLL_ITEM.get();
    }
}
