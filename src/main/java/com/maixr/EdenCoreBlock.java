package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 弧光-伊甸核心机（多方块控制器）。
 *
 * <p>结构：核心本体 + 同一层 3x3 的 8 个「弧光-伊甸框架」。
 * <p>玩法：机器随机点名一种可合成物品，玩家手持它右键提交 → <b>进度 +1%</b>，并获得修复额度。
 * <ul>
 *   <li>每提交 20 次触发一次入侵，没打完不能继续</li>
 *   <li>99% → 100% 那一次必须是「复苏的草方块」</li>
 * </ul>
 */
public class EdenCoreBlock extends Block {

    public EdenCoreBlock(Properties properties) {
        super(properties);
    }

    // ==========================================================================
    //  物品提交池 —— 【静态】，完全由 config/maixr/eden_pool.json 决定
    // ==========================================================================
    //
    //  历史：早期版本是"从配方表递归推算复杂度、按分位数分十段"的动态池。
    //  现改为静态配置，理由：
    //    · 动态推算要遍历全部配方（递归 computeCost），启动慢、偶尔算错
    //    · 整合包作者需要精确控制"哪个阶段点名哪些物品、要几个"
    //    · 换了模组之后动态结果会漂移，静态配置永远稳定
    //
    //  改池子请直接编辑 config/maixr/eden_pool.json，然后 /reload 或重启。

    /** 池子版本号：配置重载时用它判断要不要刷新缓存 */
    private static int poolStamp = -1;

    /**
     * 抽需求物品：按「当前进度所处阶段」加权。
     *
     * <p>权重 —— 正好是本阶段的 ×8；相邻阶段 ×3；其余 ×1。
     * 所以越贴近玩家当前进度的物品越容易被点名，不会在前期就撞上终局产物。</p>
     *
     * <p><b>数量来自配置</b>：{@code eden_pool.json} 里每种物品可以单独设数量
     * （没写的用 {@code defaultCount}，默认 16）。</p>
     */
    private static void rollRequirement(ServerLevel level, EdenProgress progress) {
        if (!EdenPool.isReady()) {
            // 配置缺失时的兜底：别让玩家卡死
            progress.requiredItem = "minecraft:stone";
            progress.requiredCount = EdenPool.defaultCount;
            progress.markDirty();
            return;
        }

        int stageCount = EdenPool.stageCount();
        // 进度 0~1000 映射到 0 ~ stageCount-1
        int stage = Math.min(stageCount - 1,
                Math.max(0, progress.completeness() * stageCount / EdenProgress.MAX));

        // 收集候选（本阶段 ×8 / 相邻 ×3 / 其余 ×1）
        List<Item> candidates = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        long total = 0;
        for (int s = 0; s < stageCount; s++) {
            int w = 1;
            int diff = Math.abs(s - stage);
            if (diff == 0) w = 8;
            else if (diff == 1) w = 3;
            for (java.util.Map.Entry<Item, Integer> e : EdenPool.itemsOf(s).entrySet()) {
                candidates.add(e.getKey());
                counts.add(e.getValue());
                weights.add(w);
                total += w;
            }
        }
        if (candidates.isEmpty()) {
            progress.requiredItem = "minecraft:stone";
            progress.requiredCount = EdenPool.defaultCount;
            progress.markDirty();
            return;
        }

        int roll = level.random.nextInt((int) Math.min(Integer.MAX_VALUE, Math.max(1, total)));
        int pick = candidates.size() - 1;
        for (int i = 0; i < candidates.size(); i++) {
            roll -= weights.get(i);
            if (roll < 0) { pick = i; break; }
        }

        Item pickedItem = candidates.get(pick);
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(pickedItem);
        progress.requiredItem = id == null ? "minecraft:stone" : id.toString();
        progress.requiredCount = counts.get(pick);
        progress.markDirty();
    }


    /**
     * 把当前池子导出到 {@code logs/maixr_eden_pool.txt}（只读展示，不会改配置）。
     *
     * <p>格式与 {@code config/maixr/eden_pool.json} 一一对应，
     * 方便你对照着检查"配置读进来了没有"。</p>
     */
    public static String dumpPool(ServerLevel level) {
        if (!EdenPool.isReady()) {
            return "§c[伊甸] 提交池未加载：" + EdenPool.error();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("MaixrCore 伊甸工程 - 提交池清单（静态配置）").append(System.lineSeparator());
        sb.append("来源：config/maixr/eden_pool.json").append(System.lineSeparator());
        sb.append("规则：数量按配置逐项设定；权重 = 本阶段 x8 / 相邻阶段 x3 / 其余 x1")
                .append(System.lineSeparator()).append(System.lineSeparator());

        int stageCount = EdenPool.stageCount();
        for (int stage = 0; stage < stageCount; stage++) {
            java.util.Map<Item, Integer> items = EdenPool.itemsOf(stage);
            sb.append("=========== 阶段 ").append(stage + 1).append(" · ").append(EdenPool.stageName(stage))
                    .append("（").append(items.size()).append(" 种）===========")
                    .append(System.lineSeparator());
            List<String> linesOut = new ArrayList<>();
            for (java.util.Map.Entry<Item, Integer> e : items.entrySet()) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(e.getKey());
                if (id != null) linesOut.add("  " + id + "  x" + e.getValue());
            }
            java.util.Collections.sort(linesOut);
            for (String line : linesOut) sb.append(line).append(System.lineSeparator());
            sb.append(System.lineSeparator());
        }

        try {
            java.io.File dir = new java.io.File(level.getServer().getServerDirectory().toFile(), "logs");
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, "maixr_eden_pool.txt");
            java.nio.file.Files.writeString(file.toPath(), sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder brief = new StringBuilder("§b[伊甸] 提交池已导出：§f" + file.getAbsolutePath() + "\n§7各阶段物品数 ");
            int[] sizes = EdenPool.stageSizes();
            for (int i = 0; i < sizes.length; i++) {
                brief.append(i + 1).append(":").append(sizes[i]).append("  ");
            }
            return brief.toString();
        } catch (Throwable t) {
            MaixrMod.LOGGER.warn("[伊甸] 导出提交池失败: {}", t.toString());
            return "§c[伊甸] 导出失败，池中共 " + EdenPool.totalItems() + " 种";
        }
    }

    /**
     * 【1.21 移植】新版的 Block 家族要求实现 {@code codec()}。
     * 1.20.1 没有这个抽象方法；1.21 用 {@code simpleCodec(构造引用)} 是最简写法，
     * 效果等同于"这个方块没有额外状态需要序列化"。
     */
    public static final com.mojang.serialization.MapCodec<EdenCoreBlock> CODEC =
            simpleCodec(EdenCoreBlock::new);

    @Override
    protected com.mojang.serialization.MapCodec<? extends Block> codec() {
        return CODEC;
    }
}