package com.maixr.client;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;

/**
 * 变身换肤覆盖表（客户端）：记录哪些玩家处于萨姆形态。
 *
 * <p>由 {@code PlayerInfoSkinMixin} 在 {@code PlayerInfo#getSkin} 的 HEAD 查询：
 * 命中即返回萨姆 {@link PlayerSkin}，未命中零干预 —— 与 CustomSkinLoader 等皮肤管线
 * 互不冲突（它们改的是更底层的 SkinManager 内部，见 openspec design.md D4）。</p>
 *
 * <p>还原 = 移除表项，不污染皮肤缓存（披风/鞘翅贴图无损）。</p>
 */
public final class MemoryDollSkinManager {
    /** 萨姆皮肤：两张皮肤经像素判定均为 CLASSIC 宽臂 → Model.WIDE；变身的 10 秒内披风/鞘翅不可见属预期。 */
    private static final PlayerSkin SAM_SKIN = new PlayerSkin(
            ResourceLocation.fromNamespaceAndPath(com.maixr.MaixrMod.MODID, "textures/entity/memory_doll_sam.png"),
            null, null, null, PlayerSkin.Model.WIDE, false);

    private static final Set<UUID> TRANSFORMED = ConcurrentHashMap.newKeySet();

    /** mixin 查询入口：处于萨姆形态返回萨姆皮肤，否则返回 null（零干预）。 */
    public static PlayerSkin getOverride(UUID playerId) {
        return TRANSFORMED.contains(playerId) ? SAM_SKIN : null;
    }

    public static void setTransformed(UUID playerId, boolean on) {
        if (on) {
            TRANSFORMED.add(playerId);
        } else {
            TRANSFORMED.remove(playerId);
        }
        // PlayerRenderer 每帧都调 getSkin，无需手动触发重绘
    }

    /** 断线/退出存档时清空（覆盖表是会话态，重进后由服务端重新对齐）。 */
    public static void clearAll() {
        TRANSFORMED.clear();
    }

    private MemoryDollSkinManager() {}
}
