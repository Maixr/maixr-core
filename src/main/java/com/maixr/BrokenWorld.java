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
 * {@link BrokenOverworldChunkGenerator} 生成 —— 真实地形 + 渐变虚空
 * （出生点 10% 虚空，250 区块外 95%），主世界就是破碎世界本身。
 *
 * <table border="1">
 *   <tr><th>世界类型</th><th>主世界生成器</th><th>出生</th></tr>
 *   <tr><td><b>默认</b></td><td>{@code minecraft:noise}（原版）</td>
 *       <td>主世界地表（原版）</td></tr>
 *   <tr><td><b>空岛</b></td><td>SkyblockBuilder 的空岛生成器</td>
 *       <td>主世界空岛（SkyblockBuilder 自己管）</td></tr>
 *   <tr><td><b>破碎</b></td><td>{@link BrokenOverworldChunkGenerator}</td>
 *       <td>主世界里 (0,160,0) 的开局空岛（{@link BrokenIslandHandler} 放）</td></tr>
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
 * <b>现在不再作为出生地</b> —— 保留是为了兼容老存档里已经生成过的区块。
 * 玩家如果想要，可以用 {@code /maixr broken} 之类的指令过去。</p>
 *
 * <h2>伊甸修复的三个维度</h2>
 *
 * <table border="1">
 *   <tr><th>维度</th><th>角色</th></tr>
 *   <tr><td>{@link #BROKEN}</td><td><b>修复目标</b>：虚空一块块被填回地形</td></tr>
 *   <tr><td>{@link #PRISTINE}</td><td><b>取样源</b>：干净的原版主世界地形（无建筑、无改动）</td></tr>
 *   <tr><td>{@link #REBORN}</td><td>重生世界（含伊甸枢纽总站），pristine 拿不到时的备选源</td></tr>
 * </table>
 *
 * <p><b>★ 绝不能用主世界当取样源</b> —— 主世界是空岛或者破碎地形，
 * 从它搬地形等于搬空气或搬碎片。这个坑踩过一次，
 * {@code EdenTicker} 里有自检报错专门盯着它。</p>
 */
public final class BrokenWorld {

    /** 破碎维度（空岛 + 95% 虚空的老式破碎世界）。不再是出生地，但保留可用。 */
    public static final ResourceKey<Level> BROKEN =
            ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "overworld_broken"));

    /** 取样源：干净的原版主世界地形。 */
    public static final ResourceKey<Level> PRISTINE =
            ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "overworld_pristine"));

    /** 重生世界（含伊甸枢纽总站）。 */
    public static final ResourceKey<Level> REBORN =
            ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "overworld_reborn"));

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

    /** 取取样源（优先 pristine，退回 reborn）；拿不到返回 {@code null}。 */
    public static ServerLevel resolveSource(MinecraftServer server) {
        if (server == null) return null;
        ServerLevel pristine = server.getLevel(PRISTINE);
        if (pristine != null) return pristine;
        return server.getLevel(REBORN);
    }

    /** 某个维度是不是破碎维度。 */
    public static boolean isBroken(ResourceKey<Level> dim) {
        return dim != null && dim.equals(BROKEN);
    }

    private BrokenWorld() {}
}
