package com.maixr.mixin;

import com.maixr.BrokenOverworldChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.GeodeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 紫水晶洞：<b>在生成入口直接不生成</b>（不是生成后再删）。
 *
 * <h2>判定</h2>
 * <p>紫水晶洞是一个多方块 feature。它放置前，如果生成原点所在列<b>下方 8 格全为空气</b>，
 * 说明这片地形已被碎裂场撕掉 —— 长出来必然是悬浮晶洞，于是<b>直接取消生成</b>；
 * 只要下方有任何实心方块（正常地下），完全放行。</p>
 *
 * <h2>★ 本类不允许出现任何字段（关键）</h2>
 * <p>Mixin 有已知 bug（SpongePowered/Mixin #576）：{@code MixinPreProcessorStandard}
 * 对 {@code @Unique} 字段的 STATIC 标志检查有误，处理 {@code @Inject} 时会抛
 * {@code Unexpecteded NullPointerException}，<b>整个注入被跳过</b> —— 之前晶洞一直生成
 * 就是这个原因（日志里有 {@code Mixin apply for mod maixr failed}）。</p>
 *
 * <p>所以这里<b>一个字段都没有</b>，也不打日志，全部用字面量。</p>
 *
 * <h2>为什么打 {@link Feature#place}</h2>
 * <p>{@code GeodeFeature} 没有覆写 {@code place}（那是父类 {@link Feature} 的方法），
 * 打 {@code GeodeFeature} 会因找不到方法而静默跳过。这里打父类 + {@code instanceof} 过滤。</p>
 */
@Mixin(Feature.class)
public abstract class AmethystGeodeFeatureMixin {

    @Inject(method = "place", at = @At("HEAD"), cancellable = true, require = 0)
    private void maixr$skipVoidGeode(FeaturePlaceContext<?> context,
                                     CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof GeodeFeature)) {
            return;
        }
        if (!(context.level().getLevel() instanceof ServerLevel sl)
                || !(sl.getChunkSource().getGenerator() instanceof BrokenOverworldChunkGenerator)) {
            return;
        }
        BlockPos origin = context.origin();
        for (int d = 0; d <= 8; d++) {
            if (!context.level().getBlockState(origin.below(d)).isAir()) {
                return;   // 有支撑 → 正常地下晶洞，放行
            }
        }
        cir.setReturnValue(false);   // 整列空气 → 根本不生成（不是事后删除）
    }
}
