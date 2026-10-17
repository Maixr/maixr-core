package com.maixr;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;

/** Maixr 皮肤玩偶：可放置为方块，也可佩戴——优先 Curios 头饰槽，其次原版头盔槽。 */
public class MaixrDollItem extends BlockItem {
    public MaixrDollItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** 声明为头部装备（可放入原版头盔栏）。 */
    @Override
    @Nullable
    public EquipmentSlot getEquipmentSlot(ItemStack stack) {
        return EquipmentSlot.HEAD;
    }

    public static final String TAG_SPIN = "maixr_spin";

    public static boolean isSpinning(net.minecraft.world.item.ItemStack stack) {
        return NbtCompat.getOrCreate(stack).getBoolean(TAG_SPIN);
    }

    /** 佩戴时右键：切换旋转（true→转圈，false→停止）。 */
    public static boolean toggleSpin(net.minecraft.world.item.ItemStack stack) {
        boolean now = !isSpinning(stack);
        NbtCompat.getOrCreate(stack).putBoolean(TAG_SPIN, now);
        return now;
    }

    /** 对空气右键 = 佩戴（先 Curios 头饰槽，再原版头盔槽）；对方块右键仍为放置。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            if (MaixrCurios.isLoaded()) {
                if (MaixrCurios.equipHead(player, stack)) {
                    level.playSound((net.minecraft.world.entity.player.Player) null, player.getX(), player.getY(), player.getZ(),
                            SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.PLAYERS, 1.0F, 1.0F);
                    return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
                }
            }
            if (player.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && stack.getEquipmentSlot() == EquipmentSlot.HEAD) {
                ItemStack toEquip = stack.split(1);
                player.setItemSlot(EquipmentSlot.HEAD, toEquip);
                level.playSound((net.minecraft.world.entity.player.Player) null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.PLAYERS, 1.0F, 1.0F);
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
            }
        }
        return InteractionResultHolder.pass(stack);
    }
}
