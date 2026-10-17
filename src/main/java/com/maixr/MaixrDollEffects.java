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

        // ★ 夜视只对 Maixr 玩偶生效 —— isDollWorn 只认 SKIN_DOLL_ITEM；真理玩偶不给夜视
        if (!isDollWorn(player)) return;
        MobEffectInstance current = player.getEffect(MobEffects.NIGHT_VISION);
        if (current == null || current.getDuration() < REFRESH_AT) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,
                    NIGHT_VISION_DURATION, 0, false, false, false));
        }
    }
}
