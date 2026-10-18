package com.maixr.mixin;

import com.maixr.BrokenOverworldChunkGenerator;
import com.maixr.MaixrMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.GeodeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在<b>世界生成层面</b>阻止紫水晶洞在破碎世界生成。
 *
 * <h2>★ 为什么打 {@link Feature} 而不是 {@link GeodeFeature}（第二次修正）</h2>
 *
 * <p>第一版 {@code @Mixin(GeodeFeature.class)} + {@code method = "place"} 完全没生效：
 * {@code GeodeFeature} <b>没有覆写</b> {@code place}，那是父类 {@link Feature} 的方法，
 * 类 mixin 只能注入"该类自己声明的方法"，找不到就因 {@code require = 0} 静默跳过。
 * 于是晶洞照常生成，玩家看到"根本没删"。</p>
 *
 * <p>改为注入 {@link Feature#place}，再用 {@code instanceof GeodeFeature} 过滤 ——
 * 这样<b>一定命中</b>（每个特征放置都会经过这里，代价只是一次 instanceof）。</p>
 *
 * <p>只对 {@link BrokenOverworldChunkGenerator} 的维度返回 false（不生成），
 * 默认世界照常生成晶洞。</p>
 */
@Mixin(Feature.class)
public abstract class AmethystGeodeFeatureMixin {

    @Unique
    private static final AtomicBoolean maixr$logged = new AtomicBoolean(false);

    @Inject(method = "place", at = @At("HEAD"), cancellable = true, require = 0)
    private void maixr$skipGeodeInBrokenWorld(FeaturePlaceContext<?> context,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof GeodeFeature)) {
            return;
        }
        if (context.level().getLevel() instanceof ServerLevel sl
                && sl.getChunkSource().getGenerator() instanceof BrokenOverworldChunkGenerator) {
            if (maixr$logged.compareAndSet(false, true)) {
                MaixrMod.LOGGER.info("[破碎] 已在世界生成阶段拦截紫水晶洞（GeodeFeature）");
            }
            cir.setReturnValue(false);
        }
    }
}
