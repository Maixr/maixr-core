package com.maixr.client;

import com.maixr.EdenTicker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/** 客户端侧保存服务端同步过来的世界状态。 */
public final class MaixrEdenClientState {
    public static int completeness = 0;
    public static int stage = 0;
    public static boolean invasion = false;
    public static int wave = 0;
    /** 伊甸核心机当前要求的物品 id（空串 = 没有） */
    public static String requiredItem = "";

    public static void accept(int completeness, int stage, boolean invasion, int wave, String requiredItem) {
        MaixrEdenClientState.completeness = completeness;
        MaixrEdenClientState.stage = stage;
        MaixrEdenClientState.invasion = invasion;
        MaixrEdenClientState.wave = wave;
        MaixrEdenClientState.requiredItem = requiredItem == null ? "" : requiredItem;
    }

    /** 玩家是不是在破碎世界里 */
    public static boolean inBroken() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.dimension().equals(EdenTicker.BROKEN);
    }

    private MaixrEdenClientState() {}
}
