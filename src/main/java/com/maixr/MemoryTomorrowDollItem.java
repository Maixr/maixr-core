package com.maixr;

import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 致回忆中的明日物品：
 * <ul>
 *   <li><b>手持右键（不蹲下）= 变身</b>（{@link MemoryDollTransforms#tryTransform}）</li>
 *   <li><b>蹲下 + 右键 = 佩戴</b>（复用 {@link MaixrDollItem} 的 Curios 头槽 / 头盔槽逻辑）</li>
 *   <li>萨姆外观标记存 NbtCompat 自定义数据（{@link #TAG_SAM}），驱动客户端物品模型谓词切换</li>
 * </ul>
 */
public class MemoryTomorrowDollItem extends MaixrDollItem {
    /** 萨姆外观标记（物品 NBT，仅视觉）。 */
    public static final String TAG_SAM = "maixr_sam";

    public MemoryTomorrowDollItem(Block block, Properties properties) {
        super(block, properties);
    }

    public static boolean isSam(ItemStack stack) {
        return NbtCompat.get(stack).getBoolean(TAG_SAM);
    }

    /**
     * 写入/清除萨姆标记。
     *
     * <p>★ NbtCompat 的 {@code getOrCreate} 返回副本，改完必须 {@code set} 写回（项目硬规则）。
     * 若该物品正被佩戴（头盔槽 / Curios 头槽），主动触发一次槽位写回保证客户端同步。</p>
     */
    public static void setSam(ItemStack stack, boolean sam, @Nullable ServerPlayer owner) {
        CompoundTag tag = NbtCompat.getOrCreate(stack);
        if (sam) {
            tag.putBoolean(TAG_SAM, true);
        } else {
            tag.remove(TAG_SAM);
        }
        NbtCompat.set(stack, tag);
        if (owner != null) {
            syncIfWorn(stack, owner);
        }
    }

    private static void syncIfWorn(ItemStack stack, ServerPlayer owner) {
        if (stack == owner.getItemBySlot(EquipmentSlot.HEAD)) {
            owner.setItemSlot(EquipmentSlot.HEAD, stack);
        } else if (MaixrCurios.isLoaded() && stack == MaixrCurios.getHeadStack(owner)) {
            try {
                var handler = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(owner).orElse(null);
                if (handler != null) {
                    handler.setEquippedCurio("head", 0, stack);
                }
            } catch (Throwable ignored) {
                // Curios 异常时不再重试；物品本体组件已写回
            }
        }
    }

    /** 手持右键：不蹲下 = 变身；蹲下 = 佩戴（基类逻辑）。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isCrouching()) {
            return super.use(level, player, hand);
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            MemoryDollTransforms.tryTransform(serverPlayer, stack);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
