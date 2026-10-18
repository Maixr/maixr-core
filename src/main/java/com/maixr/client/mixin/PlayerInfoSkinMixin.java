package com.maixr.client.mixin;

import com.maixr.client.MemoryDollSkinManager;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 变身换肤钩子：{@code PlayerInfo#getSkin} 是客户端玩家皮肤查询单点
 * （AbstractClientPlayer#getSkin 直接委托它，第三人称/第一人称手臂/旁观渲染都走它）。
 *
 * <p>覆盖表命中 → 返回萨姆皮肤；未命中 → 原样放行（与 CustomSkinLoader 不冲突）。
 * 免 refmap：NeoForge 21.1 运行时即 mojmap，方法名在 dev 与生产一致。</p>
 */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoSkinMixin {

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void maixr$memoryDollSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin override = MemoryDollSkinManager.getOverride(
                ((PlayerInfo) (Object) this).getProfile().getId());
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
