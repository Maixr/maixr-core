package com.maixr;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.minecraft.core.registries.BuiltInRegistries;

/** Curios 集成（仅在 curios 存在时调用，避免类加载失败）。 */
public final class MaixrCurios {
    private MaixrCurios() {}

    public static boolean isLoaded() {
        return ModList.get() != null && ModList.get().isLoaded("curios");
    }

    /** 尝试戴到 curios 头饰槽（槽 0）。成功返回 true。 */
    public static boolean equipHead(Player player, ItemStack stack) {
        try {
            var handler = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).orElse(null);
            if (handler == null) return false;
            var head = handler.getStacksHandler("head").orElse(null);
            if (head == null || head.getStacks().getStackInSlot(0).isEmpty()) {
                handler.setEquippedCurio("head", 0, stack.split(1));
                return true;
            }
        } catch (Throwable ignored) {
            // curios 异常时回退到原版头盔槽
        }
        return false;
    }

    /** 读取玩家 curios 头饰槽(0) 的物品。 */
    public static net.minecraft.world.item.ItemStack getHeadStack(net.minecraft.world.entity.LivingEntity entity) {
        try {
            var handler = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(entity).orElse(null);
            if (handler == null) return net.minecraft.world.item.ItemStack.EMPTY;
            var head = handler.getStacksHandler("head").orElse(null);
            if (head == null) return net.minecraft.world.item.ItemStack.EMPTY;
            return head.getStacks().getStackInSlot(0);
        } catch (Throwable ignored) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
    }

    /** 遍历玩家全部 Curios 槽，返回 [槽标签(如 curios:head:0), 物品注册ID]。 */
    public static java.util.List<String[]> scanCurios(net.minecraft.world.entity.LivingEntity entity) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            var handler = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(entity).orElse(null);
            if (handler == null) return out;
            for (var entry : handler.getCurios().entrySet()) {
                String type = entry.getKey();
                var stacks = entry.getValue().getStacks();
                for (int i = 0; i < stacks.getSlots(); i++) {
                    ItemStack s = stacks.getStackInSlot(i);
                    if (!s.isEmpty()) {
                        out.add(new String[]{ "curios:" + type + ":" + i,
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString() });
                    }
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** 玩家头上（curios 头饰槽）是否戴着玩偶。 */
    public static boolean isDollOnHead(net.minecraft.world.entity.LivingEntity entity) {
        try {
            var handler = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(entity).orElse(null);
            if (handler == null) return false;
            return handler.isEquipped(MaixrMod.SKIN_DOLL_ITEM.get());
        } catch (Throwable ignored) {
            return false;
        }
    }
}
