package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 世界重生核心：右键草方块——普通世界→破碎世界；破碎世界→回重生点并返还复苏草方块。 */
public class MaixrWorldRebirthCoreItem extends Item {
    public MaixrWorldRebirthCoreItem(Properties properties) {
        super(properties);
    }

    private static boolean isGrass(UseOnContext ctx) {
        return ctx.getLevel().getBlockState(ctx.getClickedPos()).is(Blocks.GRASS_BLOCK);
    }

    private static void consumeOne(UseOnContext ctx, Player player) {
        if (player.getAbilities().instabuild) return;
        ItemStack stack = ctx.getItemInHand();
        stack.shrink(1);
        if (stack.isEmpty()) {
            player.getInventory().setItem(player.getInventory().selected, ItemStack.EMPTY);
        }
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
    }

    private static void giveItem(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null || !isGrass(context)) return InteractionResult.PASS;
        // 客户端放行（PASS），让操作发给服务器执行
        if (level.isClientSide) {
            return InteractionResult.PASS;
        }
        ServerPlayer serverPlayer = (ServerPlayer) player;
        boolean inBroken = player.level().dimension().equals(MaixrCommands.BROKEN_DIMENSION);
        if (inBroken) {
            // 破碎世界 → 回家：传送到重生点（或主世界出生点）
            consumeOne(context, player);
            ServerLevel home = serverPlayer.getServer().overworld();
            Vec3 target = null;
            ResourceKey<Level> respawnDim = serverPlayer.getRespawnDimension();
            BlockPos respawnPos = serverPlayer.getRespawnPosition();
            if (respawnPos != null) {
                ServerLevel resp = serverPlayer.getServer().getLevel(respawnDim);
                if (resp != null) { home = resp; target = Vec3.atCenterOf(respawnPos); }
            }
            if (target == null) {
                BlockPos spawn = home.getSharedSpawnPos();
                target = Vec3.atCenterOf(spawn).add(0, 0.2, 0);
            }
            serverPlayer.teleportTo(home, target.x, target.y, target.z, serverPlayer.getYRot(), serverPlayer.getXRot());
            giveItem(player, new ItemStack(MaixrMod.REVIVED_GRASS.get()));
            player.displayClientMessage(Component.literal("\u00a7b回家？世界重生核心已破碎……"), false);
            level.playSound((net.minecraft.world.entity.player.Player) null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
            return InteractionResult.CONSUME;
        }
        // 普通世界 → 破碎世界
        ServerLevel broken = serverPlayer.getServer().getLevel(MaixrCommands.BROKEN_DIMENSION);
        if (broken == null) {
            player.displayClientMessage(Component.literal("\u00a7c破碎世界维度未加载"), true);
            return InteractionResult.FAIL;
        }
        ChunkPos found = null;
        outer:
        for (int r = 0; r <= 200 && found == null; r++) {
            for (int dx = -r; dx <= r && found == null; dx++) {
                for (int dz = -r; dz <= r && found == null; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    if (!BrokenOverworldChunkGenerator.isVoided(dx, dz)) {
                        found = new ChunkPos(dx, dz);
                        break outer;
                    }
                }
            }
        }
        if (found == null) {
            player.displayClientMessage(Component.literal("\u00a7c找不到可用的落脚区块"), true);
            return InteractionResult.FAIL;
        }
        consumeOne(context, player);
        // ★ 落点 = 伊甸核心机正上方。圆心是按「最近的平原陆地」挑的，早就不在原点上了，
        //   以前这里硬编码 (0, 280, 0)，玩家会被扔在一片虚空里。
        EdenProgress eden = EdenProgress.get(broken);
        double x = eden.centerChosen ? eden.centerX + 0.5D : 0.5D;
        double z = eden.centerChosen ? eden.centerZ + 0.5D : 0.5D;
        double y = eden.centerChosen
                ? Math.max(eden.centerY, broken.getMinBuildHeight() + 1) + 1.0D : 280.0D;
        serverPlayer.teleportTo(broken, x, y, z, serverPlayer.getYRot(), serverPlayer.getXRot());
        serverPlayer.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 20, 1, false, false));
        level.playSound((net.minecraft.world.entity.player.Player) null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
        player.displayClientMessage(Component.literal("\u00a7a世界开始崩解——你坠入了支离破碎的彼端……"), false);
        return InteractionResult.CONSUME;
    }
}
