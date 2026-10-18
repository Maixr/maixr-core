package com.maixr.client;

/**
 * ★ 只允许在客户端加载的包处理集中营（同 {@link MaixrClientPackets} 的存在理由）。
 * 变身换肤广播：维护本客户端的萨姆形态覆盖表。
 */
public final class MemoryDollClientPackets {
    private MemoryDollClientPackets() {}

    /** 某玩家变身开启/关闭（UUID 对应 PlayerInfo，实体尚未生成也要能记账）。 */
    public static void handleSkin(java.util.UUID playerId, boolean on) {
        MemoryDollSkinManager.setTransformed(playerId, on);
    }
}
