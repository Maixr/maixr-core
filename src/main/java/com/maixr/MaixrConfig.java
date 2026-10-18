package com.maixr;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Maixr Core 配置（{@code config/maixr-common.toml}，COMMON —— 单人与服务器共用）。
 *
 * <ul>
 *   <li>{@code dollSoundEnabled}：玩偶的<b>变身语音</b>（"我将点燃星海"）是否启用 ——
 *       <b>默认 false（禁用）</b>；其它玩偶音效（佩戴音/结束音/图腾音等）不受此配置影响；</li>
 *   <li>{@code uniformBand}：破碎世界全图统一碎裂水平的带进度（原为
 *       {@code FractureDensityFunction.UNIFORM_BAND} 静态常量，现由配置控制 ——
 *       改动只影响未生成区块，已生成区块不变）。</li>
 * </ul>
 */
public final class MaixrConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DOLL_SOUND_ENABLED =
            BUILDER.comment("玩偶的变身语音（我将点燃星海）是否启用；其它玩偶音效不受影响")
                    .define("dollSoundEnabled", false);

    public static final ModConfigSpec.DoubleValue UNIFORM_BAND =
            BUILDER.comment("破碎世界全图统一碎裂水平的带进度（越大越碎；改动只影响未生成区块）")
                    .defineInRange("uniformBand", FractureDensityFunction.UNIFORM_BAND, 0.0D, 3.0D);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** 玩偶使用音效是否启用（配置未加载时回退默认 false）。 */
    public static boolean dollSoundEnabled() {
        return SPEC.isLoaded() && DOLL_SOUND_ENABLED.get();
    }

    /** 统一碎裂带进度（配置未加载时回退默认值）。 */
    public static double uniformBand() {
        return SPEC.isLoaded() ? UNIFORM_BAND.get() : FractureDensityFunction.UNIFORM_BAND;
    }

    private MaixrConfig() {}
}
