package com.maixr;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * 碎片连桥的触发点 —— 区块<b>加载完成</b>后（主线程）建桥。
 *
 * <p>不能放在区块<b>生成</b>阶段：桥端点常在当前区块读写范围之外，
 * {@code getHeight} 会抛 {@code Requested chunk unavailable during world generation}
 * （实测把世界创建卡死在 0%）。放在 Load 阶段后，端点区块可由
 * {@code getChunkNow} 安全探测，未加载就跳过、等它自己加载时再处理。</p>
 */
public final class MaixrBridgeEvents {

    private MaixrBridgeEvents() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        // 只对「破碎」主世界生效（判据 = 我们自己那个生成器）
        if (!(level.getChunkSource().getGenerator() instanceof BrokenOverworldChunkGenerator)) {
            return;
        }
        // ★ 全部改为可配置（默认：连桥关闭、晶洞清理开启）
        if (MaixrConfig.geodeCleanupEnabled()) {
            // 老区块补跑：世界生成阶段的清理只覆盖新区块
            VoidDebrisCleanup.run(level, chunk);
            GeodeBlobCleanup.run(level, chunk);
        }
        if (MaixrConfig.bridgesEnabled()) {
            FragmentBridges.run(level, chunk);
        }
    }
}
