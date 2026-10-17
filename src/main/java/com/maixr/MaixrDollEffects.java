package com.maixr;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

/** 佩戴 Maixr 玩偶时持续获得夜视；空手右键切换头上玩偶（Maixr/真理均可）的旋转。 */
@EventBusSubscriber(modid = MaixrMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MaixrDollEffects {
    /** 夜视刷新时长（刻）。 */
    private static final int NIGHT_VISION_DURATION = 420;
    /** 剩余低于该时长时重新施加。 */
    private static final int REFRESH_AT = 220;

    private MaixrDollEffects() {}

    private static boolean isDollWorn(Player player) {
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (!head.isEmpty() && head.getItem() == MaixrMod.SKIN_DOLL_ITEM.get()) return true;
        return MaixrCurios.isLoaded() && MaixrCurios.isDollOnHead(player);
    }

    /** 取当前佩戴的玩偶（头盔槽或 curios 头部槽，涵盖所有玩偶种类）。 */
    private static ItemStack getWornDoll(Player player) {
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (!head.isEmpty() && MaixrMod.isDollItem(head.getItem())) return head;
        if (MaixrCurios.isLoaded()) {
            ItemStack cur = MaixrCurios.getHeadStack(player);
            if (!cur.isEmpty() && MaixrMod.isDollItem(cur.getItem())) return cur;
        }
        return ItemStack.EMPTY;
    }

    /**
     * 按需补效果（等级不足或即将到期时刷新）。amplifier = 等级 - 1。
     *
     * <p><b>1.21 移植注意：</b>参数类型从 {@code MobEffect} 改成 {@code Holder<MobEffect>}。
     * 1.21 里 {@code MobEffects.XXX} 这些常量已经是 {@code Holder<MobEffect>}（不再是 MobEffect 本身），
     * 而 {@code player.getEffect()} / {@code MobEffectInstance} 也都收 Holder，
     * 所以把签名一起换掉即可 —— 调用点（{@code MobEffects.DAMAGE_RESISTANCE} 这些）不用改。</p>
     */
    private static void applyIfNeeded(Player player, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect, int amplifier) {
        MobEffectInstance cur = player.getEffect(effect);
        if (cur == null || cur.getAmplifier() < amplifier || cur.getDuration() < REFRESH_AT) {
            player.addEffect(new MobEffectInstance(effect, NIGHT_VISION_DURATION, amplifier, false, false, false));
        }
    }

    /** 佩戴着玩偶时空手右键 → 切换头上玩偶的旋转。 */
    @SubscribeEvent
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        net.minecraft.world.entity.player.Player player = event.getEntity();
        if (player.level().isClientSide || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!player.getMainHandItem().isEmpty()) return; // 手里有东西时交给正常使用

        // 原版头盔槽（Maixr 或真理）
        ItemStack worn = player.getItemBySlot(EquipmentSlot.HEAD);
        if (!worn.isEmpty() && MaixrMod.isDollItem(worn.getItem())) {
            if (MaixrDollItem.toggleSpin(worn)) {
                player.setItemSlot(EquipmentSlot.HEAD, worn); // 触发客户端同步
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("\u00a7a玩偶开始旋转"), true);
            } else {
                player.displayClientMessage(net.minecraft.network.chat.Component.literal("\u00a77玩偶停止旋转"), true);
            }
        } else if (MaixrCurios.isLoaded()) {
            ItemStack curiosStack = MaixrCurios.getHeadStack(player);
            if (!curiosStack.isEmpty() && MaixrMod.isDollItem(curiosStack.getItem())) {
                boolean now = MaixrDollItem.toggleSpin(curiosStack);
                try {
                    var handler = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).orElse(null);
                    if (handler != null) handler.setEquippedCurio("head", 0, curiosStack);
                } catch (Throwable ignored) {}
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(now ? "\u00a7a玩偶开始旋转" : "\u00a77玩偶停止旋转"), true);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;

        ItemStack worn = getWornDoll(player);
        if (worn.isEmpty()) return;

        // ★【裂隙逆转者】毕业玩偶：抗性提升 VI + 力量 114 + 速度 II（不再提供夜视）
        if (worn.getItem() == MaixrMod.RIFT_REVERSER_DOLL_ITEM.get()) {
            applyIfNeeded(player, MobEffects.DAMAGE_RESISTANCE, 5);    // 抗性提升 6 级
            applyIfNeeded(player, MobEffects.DAMAGE_BOOST, 113);       // 力量 114 级
            applyIfNeeded(player, MobEffects.MOVEMENT_SPEED, 1);       // 速度 2 级
            return;
        }

        // 普通玩偶（Maixr / 真理）：夜视
        if (!isDollWorn(player)) return;
        MobEffectInstance current = player.getEffect(MobEffects.NIGHT_VISION);
        if (current == null || current.getDuration() < REFRESH_AT) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,
                    NIGHT_VISION_DURATION, 0, false, false, false));
        }
    }
}
