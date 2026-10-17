package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 【裂隙逆转者】毕业玩偶：
 * 手持【命令方块】右键它 → 传送到 maixr:overworld_reborn 的伊甸枢纽总站中心最高点。
 * 命令方块不会被消耗；空手右键仍沿用父类的旋转切换。
 */
public class RiftReverserDollBlock extends SkinDollBlock {
    /** 目标维度与落点（建筑中心最高点上方） */
    public static final ResourceLocation TARGET_DIM =
            ResourceLocation.fromNamespaceAndPath("maixr", "overworld_reborn");
    public static final double SPAWN_X = 0.5D, SPAWN_Y = 257.0D, SPAWN_Z = 0.5D;
    public static final float SPAWN_YAW = 0.0F, SPAWN_PITCH = 8.0F;

    public RiftReverserDollBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RiftReverserDollBlockEntity(pos, state);
    }

    /**
     * 【1.21 移植·方法拆分】Block#use 被拆成两个方法：
     * <ul>
     *   <li>{@code useItemOn(stack, level, player, hand, hit)} —— <b>手持物品</b>右键时调用，
     *       返回 {@code ItemInteractionResult}</li>
     *   <li>{@code useWithoutItem(level, player, hit)} —— <b>空手</b>右键时调用，
     *       返回 {@code InteractionResult}，且不再接收 state / pos</li>
     * </ul>
     *
     * <p>本玩偶要求玩家<b>手持命令方块</b>才能触发，所以必须实现
     * {@code useItemOn} —— 如果写在 {@code useWithoutItem} 里，持物时根本不会被调用。</p>
     */
    @Override
    public ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                           Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && hand == InteractionHand.MAIN_HAND && player instanceof ServerPlayer sp) {
            ItemStack held = stack;
            // —— 手持命令方块：触发维度逆转 ——
            if (held.is(Items.COMMAND_BLOCK)) {
                ServerLevel target = sp.server.getLevel(
                        ResourceKey.create(Registries.DIMENSION, TARGET_DIM));
                if (target != null) {
                    level.playSound((net.minecraft.world.entity.player.Player) null, pos, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 1.0F, 1.2F);
                    if (level instanceof ServerLevel sl) {
                        sl.sendParticles(ParticleTypes.REVERSE_PORTAL,
                                pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D,
                                60, 0.6D, 0.9D, 0.6D, 0.25D);
                    }
                    sp.teleportTo(target, SPAWN_X, SPAWN_Y, SPAWN_Z, SPAWN_YAW, SPAWN_PITCH);
                    target.playSound((net.minecraft.world.entity.player.Player) null, SPAWN_X, SPAWN_Y, SPAWN_Z,
                            SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 0.8F, 0.9F);
                    sp.displayClientMessage(Component.literal("§b[弧光重生] §f维度逆转 —— 伊甸枢纽总站"), true);
                } else {
                    sp.displayClientMessage(Component.literal("§c[弧光重生] 目标维度未加载，请稍后再试"), true);
                }
                return ItemInteractionResult.SUCCESS;
            }
            // —— 未持命令方块时的提示 ——
            if (held.isEmpty()) {
                sp.displayClientMessage(Component.literal("§7手持 §e命令方块 §7右键此玩偶，即可逆转至重生世界"), true);
            }
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    /**
     * 【1.21 移植】新版的 Block 家族要求实现 {@code codec()}。
     * 1.20.1 没有这个抽象方法；1.21 用 {@code simpleCodec(构造引用)} 是最简写法，
     * 效果等同于"这个方块没有额外状态需要序列化"。
     */
    public static final com.mojang.serialization.MapCodec<RiftReverserDollBlock> CODEC =
            simpleCodec(RiftReverserDollBlock::new);

    @Override
    protected com.mojang.serialization.MapCodec<? extends SkinDollBlock> codec() {
        return CODEC;
    }
}
