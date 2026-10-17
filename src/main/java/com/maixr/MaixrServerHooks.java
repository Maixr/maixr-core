package com.maixr;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * 服务端钩子。
 *
 * <h2>⚠️ 已删除的功能：破碎维度生成器覆盖</h2>
 *
 * <p>旧版这里会在 {@code ServerAboutToStart} 时，把 {@code maixr:overworld_broken} 的
 * 生成器<b>强行替换</b>成 {@link BrokenOverworldChunkGenerator}（纯虚空版），
 * 目的是"让旧存档也能用上破碎生成器"。</p>
 *
 * <p><b>这个逻辑有害，已彻底删除</b>，原因：</p>
 * <ul>
 *   <li>它的判据是 {@code gen instanceof NoiseBasedChunkGenerator} ——
 *       而 {@link BrokenSkyblockChunkGenerator}（空岛 + 虚空）<b>也是</b>它的子类，
 *       所以会被误判成"旧存档的原版生成器"而<b>被覆盖掉</b></li>
 *   <li>后果：日志里会看到
 *       "实际生成器 = BrokenOverworldChunkGenerator" —— 空岛平台没了，
 *       陨石等植被装饰也没了（纯虚空版会跳过 {@code applyBiomeDecoration}）</li>
 *   <li>数据包里的维度定义已经能正确指定生成器，不需要再运行时覆盖</li>
 * </ul>
 *
 * <p>现在本类只负责：提交池加载（启动时 + {@code /reload} 时）。</p>
 */
public final class MaixrServerHooks {
    private MaixrServerHooks() {}

    /**
     * 数据包重载（{@code /reload}）时重新读提交池配置。
     *
     * <p>这样改完 {@code config/maixr/eden_pool.json} 不用重启服务器，
     * 游戏里敲一次 {@code /reload} 就生效。</p>
     */
    public static void onDatapackSync(net.neoforged.neoforge.event.OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return;   // 只在全量同步（/reload）时重载，避免每个玩家登录都读盘
        EdenPool.reload();
        MaixrMod.LOGGER.info("[伊甸] 提交池已随数据包重载：{} 个阶段 / {} 种物品",
                EdenPool.stageCount(), EdenPool.totalItems());
    }

    public static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        // 启动时先加载一次提交池，日志里能看到成功与否
        EdenPool.reload();
        ServerLevel broken = server.getLevel(MaixrCommands.BROKEN_DIMENSION);
        if (broken != null) {
            MaixrMod.LOGGER.info("[MaixrCore] ServerStarting: broken dim 实际生成器 = {}",
                    broken.getChunkSource().getGenerator().getClass().getName());
        } else {
            MaixrMod.LOGGER.warn("[MaixrCore] ServerStarting: 未找到 broken dim 的 ServerLevel");
        }
    }
}
