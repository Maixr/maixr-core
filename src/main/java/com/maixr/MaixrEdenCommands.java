package com.maixr;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 伊甸工程相关命令：/maixr eden [top|kit] */
public final class MaixrEdenCommands {
    private MaixrEdenCommands() {}

    // 命令注册统一在 MaixrCommands.registerCommands 里挂到 /maixr 下面，
    // 自己再注册一遍 /maixr 会被 Brigadier 去重（可能静默失效），所以这里不注册。

    private static EdenProgress progressOf(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerLevel broken = server.getLevel(EdenTicker.BROKEN);
        return broken == null ? null : EdenProgress.get(broken);
    }

    public static int status(CommandContext<CommandSourceStack> ctx) {
        EdenProgress p = progressOf(ctx.getSource());
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal("破碎世界维度未加载"));
            return 0;
        }
        ServerLevel broken = ctx.getSource().getServer().getLevel(EdenTicker.BROKEN);
        long now = broken == null ? 0 : broken.getGameTime();
        StringBuilder sb = new StringBuilder();
        sb.append("§b§l===== 伊甸工程 =====\n§r");
        sb.append("§7世界完整度：§f").append(p.completeness() / 10).append("%  §7(千分比 ").append(p.completeness()).append(")\n");
        sb.append("§7当前纪元：§f").append(EdenStage.name(p.stageOf(p.completeness())))
                .append(" §7(第 ").append(p.stageOf(p.completeness()) + 1).append("/10 档)\n");
        sb.append("§7提交次数：§f").append(p.submits).append("  §7(每次 +1%)\n");
        sb.append("§7已填柱子：§f").append(p.filledColumns).append(" §7/ ").append(EdenRepair.totalColumns()).append("\n");
        sb.append("§7扩散半径：§f").append(p.ringRadius).append(" §7/ ").append(EdenRepair.MAX_RADIUS).append(" 格\n");
        sb.append("§7衰败量：§c").append(p.decay).append("\n");
        sb.append("§7修复额度：§f").append(p.budget).append(" 方块\n");
        sb.append("§7当前需求：§f").append(p.requiredItem == null || p.requiredItem.isEmpty() ? "未抽取" : p.requiredItem)
                .append(" §7× ").append(p.requiredCount).append("\n");
        if (p.invasionActive) {
            sb.append("§5§l裂隙 Boss 战中：§r§5剩余 ").append(p.invasionMobs.size())
                    .append(" 只 —— 它不拦着修复，随时可以继续提交\n");
        } else if (!p.bossSpawned) {
            sb.append("§7最终 Boss：§f还没出现（完整度到 90% 时出现一次）\n");
        } else {
            sb.append("§7最终 Boss：§f已经打过了\n");
        }
        sb.append("§7修复圆心：§f(").append(p.centerX).append(", ").append(p.centerY).append(", ").append(p.centerZ)
                .append(") §7锚定版本 v").append(p.centerVersion).append("\n");
        sb.append("§7破碎程度：§f出生点附近虚空 §b")
                .append((int) Math.round(BrokenOverworldChunkGenerator.NEAR_VOID_CHANCE * 100))
                .append("%§7，到 §b").append(BrokenOverworldChunkGenerator.RAMP_CHUNKS)
                .append(" §7区块外恒为 §b")
                .append((int) Math.round(BrokenOverworldChunkGenerator.FAR_VOID_CHANCE * 100))
                .append("%§7（只影响还没生成过的区块）\n");
        sb.append("§7修复高度：§fY ").append(EdenRepair.Y_MIN).append("~").append(EdenRepair.Y_MAX);
        if (p.needsRevivedGrass()) sb.append("\n§d§l最后一程：§r§d必须提交「复苏的草方块」× 1");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /** /maixr eden center —— 圆心在哪、平台在不在、虚空比例多少（排查"平台怎么没了"用） */
    public static int center(CommandContext<CommandSourceStack> ctx) {
        EdenProgress p = progressOf(ctx.getSource());
        ServerLevel broken = ctx.getSource().getServer().getLevel(EdenTicker.BROKEN);
        if (p == null || broken == null) {
            ctx.getSource().sendFailure(Component.literal("破碎世界维度未加载"));
            return 0;
        }
        net.minecraft.core.BlockPos core = new net.minecraft.core.BlockPos(p.centerX, p.centerY, p.centerZ);
        boolean hasCore = broken.getBlockState(core).is(MaixrMod.EDEN_CORE.get());
        int ground = broken.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                p.centerX, p.centerZ);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§b[伊甸] 圆心 §f(" + p.centerX + ", " + p.centerY + ", " + p.centerZ + ")"
                + " §7锚定版本 §fv" + p.centerVersion
                + " §7已选=" + p.centerChosen + " 已摆平台=" + p.platformPlaced + "\n"
                + "§7核心机就在那儿？§f" + hasCore + " §7；该列真实地面 y=§f" + ground
                + " §7(世界底 y=" + broken.getMinBuildHeight() + ")\n"
                + "§7该处虚空比例：§f"
                + (int) Math.round(BrokenOverworldChunkGenerator.voidChance(p.centerX >> 4, p.centerZ >> 4) * 100) + "%"
                + "（出生点 " + (int) Math.round(BrokenOverworldChunkGenerator.NEAR_VOID_CHANCE * 100)
                + "% → 最远 " + (int) Math.round(BrokenOverworldChunkGenerator.FAR_VOID_CHANCE * 100)
                + "%，只影响还没生成过的区块）"), false);
        return 1;
    }

    /** /maixr eden reanchor —— 强制重新挑一次圆心（改过选取逻辑 / 平台丢了的时候用） */
    public static int reanchor(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ServerLevel broken = server.getLevel(EdenTicker.BROKEN);
        if (broken == null) {
            ctx.getSource().sendFailure(Component.literal("破碎世界维度未加载"));
            return 0;
        }
        EdenTicker.reanchor(server, broken);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a已重新锚定圆心 —— 看日志里的「圆心定为 …」那一行，或者用 §f/maixr eden center§a。"), true);
        return 1;
    }

    public static int top(CommandContext<CommandSourceStack> ctx) {
        EdenProgress p = progressOf(ctx.getSource());
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal("破碎世界维度未加载"));
            return 0;
        }
        List<Map.Entry<UUID, Integer>> list = new ArrayList<>(p.contribution.entrySet());
        list.sort(Comparator.comparingInt((Map.Entry<UUID, Integer> e) -> e.getValue()).reversed());
        StringBuilder sb = new StringBuilder("§b§l===== 伊甸贡献榜 =====\n§r");
        if (list.isEmpty()) {
            sb.append("§7还没有人提交过物品。");
        } else {
            int rank = 1;
            for (Map.Entry<UUID, Integer> e : list) {
                if (rank > 15) break;
                sb.append("§7").append(rank).append(". §f")
                        .append(p.uuidNames.getOrDefault(e.getKey(), e.getKey().toString().substring(0, 8)))
                        .append(" §7- §b").append(e.getValue()).append(" 次提交\n");
                rank++;
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    public static int kit(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("只能由玩家执行"));
            return 0;
        }
        player.getInventory().add(new ItemStack(MaixrMod.EDEN_CORE_ITEM.get()));
        for (int i = 0; i < 8; i++) {
            player.getInventory().add(new ItemStack(MaixrMod.EDEN_FRAME_ITEM.get()));
        }
        player.displayClientMessage(Component.literal("§b[伊甸] 已发放：伊甸核心机 ×1，伊甸框架 ×8"), false);
        return 1;
    }

    /** 手动把世界完整度设成指定百分比（0~100） */
    public static int setPercent(CommandContext<CommandSourceStack> ctx, int percent) {
        return applyPercent(ctx, percent, true);
    }

    /** 在当前基础上增减百分比 */
    public static int addPercent(CommandContext<CommandSourceStack> ctx, int percent) {
        return applyPercent(ctx, percent, false);
    }

    private static int applyPercent(CommandContext<CommandSourceStack> ctx, int percent, boolean absolute) {
        ServerLevel broken = ctx.getSource().getServer().getLevel(EdenTicker.BROKEN);
        if (broken == null) {
            ctx.getSource().sendFailure(Component.literal("破碎世界维度未加载"));
            return 0;
        }
        EdenProgress progress = EdenProgress.get(broken);
        int target = absolute
                ? percent * 10
                : progress.completeness() + percent * 10;
        target = Math.max(0, Math.min(EdenProgress.MAX, target));
        // completeness = submits*10 - decay  ->  反解 submits
        progress.submits = (int) Math.ceil((target + progress.decay) / (double) EdenProgress.PER_SUBMIT);
        progress.decay = Math.min(progress.decay, progress.submits * EdenProgress.PER_SUBMIT);
        // ★ 游标归零：让世界真的从圆心往外修到新的目标半径。
        //   不归零的话 ringRadius 会停在旧值上（>= 新目标），表现就是"完整度涨了、世界一动不动"。
        EdenTicker.resetRepairCursor(progress);
        progress.markDirty();

        int now = progress.completeness();
        String msg = "§b[伊甸] 世界完整度已" + (absolute ? "设为" : "调整为") + " §f" + (now / 10) + "%"
                + " §7(纪元 " + EdenStage.name(progress.stageOf(now)) + ") §7—— 世界会从圆心往外补修到半径 "
                + progress.targetRadius() + " 格";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
        broken.getServer().getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
        return 1;
    }

    /**
     * /maixr eden refill —— 把修复游标推回圆心，让世界从中心往外重修一遍。
     *
     * <p>什么时候用：改过修复逻辑 / 修过修复源之后，存档里的 ringRadius 已经跑到前面去了，
     * 世界却还是空的 —— 这时候跑一次这个，世界会按照当前完整度对应的半径真补回来。
     */
    public static int refill(CommandContext<CommandSourceStack> ctx) {
        ServerLevel broken = ctx.getSource().getServer().getLevel(EdenTicker.BROKEN);
        if (broken == null) {
            ctx.getSource().sendFailure(Component.literal("破碎世界维度未加载"));
            return 0;
        }
        EdenProgress p = EdenProgress.get(broken);
        EdenTicker.resetRepairCursor(p);
        p.markDirty();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a修复游标已归零 —— 世界会从圆心 ("
                        + p.centerX + ", " + p.centerZ + ") 往外重修到半径 " + p.targetRadius() + " 格。"), true);
        return 1;
    }

    /**
     * /maixr eden cleanhub —— 把被"当地形搬进来"的伊甸总站从破碎世界里抹掉。
     *
     * <p>起因：修复引擎按坐标搬地形，如果取样源里有伊甸总站（它建在 maixr:overworld_reborn 里），
     * 修复圈扫到那儿就会把整座总站搬进破碎世界 —— 玩家抬头看见一座站台飘在天上。
     */
    public static int cleanhub(CommandContext<CommandSourceStack> ctx) {
        ServerLevel broken = ctx.getSource().getServer().getLevel(EdenTicker.BROKEN);
        if (broken == null) {
            ctx.getSource().sendFailure(Component.literal("破碎世界维度未加载"));
            return 0;
        }
        if (EdenTicker.isCleanupRunning()) {
            ctx.getSource().sendFailure(Component.literal("§e清理已经在进行中了。"));
            return 0;
        }
        int n = EdenTicker.startHubCleanup(broken);
        if (n == -1) {
            ctx.getSource().sendFailure(Component.literal("§c读不到总站结构文件 maixr:eden-hub，清理中止。"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a开始清理总站残骸：§f" + n + " §a个方块位置会逐个对照纯净主世界复原"
                        + "（约十几秒，进度看动作栏）。"), true);
        return 1;
    }

    /** 直接在眼睛里刷一只裂隙意志（排查"召唤不出来"用） */
    public static int spawnBoss(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        double x = src.getPosition().x;
        double y = src.getPosition().y + 4;
        double z = src.getPosition().z;
        int cleaned = ArcLightBoss.clearExisting(level);      // ★ 只能有一只，否则血条会叠一堆
        net.minecraft.world.entity.Entity entity = MaixrMod.ARC_LIGHT_BOSS.get().create(level);
        if (entity == null) {
            src.sendFailure(Component.literal("§c[伊甸] 实体创建失败 —— 注册有问题，请看日志"));
            MaixrMod.LOGGER.error("[伊甸] ARC_LIGHT_BOSS.create() 返回 null！");
            return 0;
        }
        entity.moveTo(x, y, z, 0F, 0F);
        boolean ok = level.addFreshEntity(entity);
        MaixrMod.LOGGER.info("[伊甸] 手动召唤裂隙意志 -> addFreshEntity={} @ {} {} {}", ok, x, y, z);
        src.sendSuccess(() -> Component.literal("§b[伊甸] 已召唤裂隙意志（成功=" + ok + "），位置 "
                + (int) x + " " + (int) y + " " + (int) z
                + (cleaned > 0 ? "§7（已先清理旧的 " + cleaned + " 只）" : "")), true);
        return 1;
    }

    /** 导出当前抽取池到 logs/maixr_eden_pool.txt */
    public static int pool(CommandContext<CommandSourceStack> ctx) {
        ServerLevel broken = ctx.getSource().getServer().getLevel(EdenTicker.BROKEN);
        if (broken == null) {
            ctx.getSource().sendFailure(Component.literal("破碎世界维度未加载"));
            return 0;
        }
        String summary = EdenCoreBlock.dumpPool(broken);
        for (String line : summary.split("\n")) {
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /** 100% 时授予隐藏成就《我的世界》 */
    public static void grantMyWorld(ServerPlayer player) {
        grantAdvancement(player, "my_world");
    }

    /** 通用：给某个玩家授予 maixr:<path> 成就 */
    public static void grantAdvancement(ServerPlayer player, String path) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        AdvancementHolder advancement = server.getAdvancements()
                .get(ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, path));
        if (advancement != null) {
            player.getAdvancements().award(advancement, "granted");
        } else {
            MaixrMod.LOGGER.warn("[伊甸] 找不到成就 maixr:{}", path);
        }
    }
}
