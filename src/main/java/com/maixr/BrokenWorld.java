package com.maixr;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 「破碎」体系的维度中心 —— 整合包里只有这一个地方定义这几个维度 key
 * 与「玩家选没选破碎世界类型」的判定。
 *
 * <h2>★ 「破碎」世界类型：主世界本身就是破碎世界</h2>
 *
 * <p>选了「破碎」世界类型时，<b>主世界</b>（{@code minecraft:overworld}）直接用
 * {@link BrokenOverworldChunkGenerator} 生成 —— 逐方块碎裂场
 * （平静区 1024 格内完好，越远越碎），主世界就是破碎世界本身。
 *
 * <table border="1">
 *   <tr><th>世界类型</th><th>主世界生成器</th><th>出生</th></tr>
 *   <tr><td><b>默认</b></td><td>{@code minecraft:noise}（原版）</td>
 *       <td>主世界地表（原版）</td></tr>
 *   <tr><td><b>空岛</b></td><td>SkyblockBuilder 的空岛生成器</td>
 *       <td>主世界空岛（SkyblockBuilder 自己管）</td></tr>
 *   <tr><td><b>破碎</b></td><td>{@link BrokenOverworldChunkGenerator}</td>
 *       <td>平静区地表（原版出生点逻辑；开局空岛系统已移除）</td></tr>
 * </table>
 *
 * <h2>为什么要靠"生成器类型"来判定</h2>
 *
 * <p>世界类型的选择<b>不会直接写进 level.dat</b>，存档里查不到"玩家当初选了什么"。
 * 但主世界用的是哪个 chunk generator 一定会被存下来。
 * 所以让「破碎」世界类型的主世界用 {@link BrokenOverworldChunkGenerator}，
 * 之后就能靠 {@link #isBrokenWorldType} 反推出玩家的选择。</p>
 *
 * <h2>独立维度 {@link #BROKEN} 的现状</h2>
 *
 * <p>它是「空岛 + 95% 虚空」的老式破碎维度（用 {@link BrokenSkyblockChunkGenerator}），
 * <b>现在不再作为出生地</b> —— 保留是为了兼容老存档里已经生成过的区块。</p>
 *
 * <p>（伊甸修复体系 —— 取样源 pristine / 重生世界 reborn 及其状态机 —— 已整体移除。）</p>
 */
public final class BrokenWorld {

    /** 破碎维度（空岛 + 95% 虚空的老式破碎世界）。不再是出生地，但保留可用。 */
    public static final ResourceKey<Level> BROKEN =
            ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "overworld_broken"));

    /**
     * 本存档是不是「破碎」世界类型。
     *
     * <p>判据：主世界的 chunk generator 是不是 {@link BrokenOverworldChunkGenerator}。
     * 那个生成器真的会生成破碎地形，所以这个判定既是"类型标记"也是"事实"。</p>
     */
    public static boolean isBrokenWorldType(MinecraftServer server) {
        if (server == null) return false;
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return false;
        return overworld.getChunkSource().getGenerator() instanceof BrokenOverworldChunkGenerator;
    }

    /** 取破碎维度；未加载时返回 {@code null}。 */
    public static ServerLevel resolve(MinecraftServer server) {
        if (server == null) return null;
        return server.getLevel(BROKEN);
    }

    /** 某个维度是不是破碎维度。 */
    public static boolean isBroken(ResourceKey<Level> dim) {
        return dim != null && dim.equals(BROKEN);
    }

    private BrokenWorld() {}
}
