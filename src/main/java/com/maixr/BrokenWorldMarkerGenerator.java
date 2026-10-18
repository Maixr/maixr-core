package com.maixr;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * 「破碎」世界类型的<b>标记生成器</b>（备用，当前未被任何 json 引用）。
 *
 * <h2>它做什么</h2>
 *
 * <p><b>什么都不做</b> —— 全部行为原样委托给父类 {@link NoiseBasedChunkGenerator}，
 * 也就是原版主世界的地形生成。主世界看起来、玩起来与原版一模一样。</p>
 *
 * <h2>那为什么需要它</h2>
 *
 * <p>它只是一个<b>可被检测的标记</b>：世界类型的选择不会直接存进 level.dat，
 * 但主世界用的是哪个生成器一定会被存下来 —— 让某个世界类型的主世界用
 * 一个专属生成器类型，之后就能靠 {@link BrokenWorld#isBrokenWorldType}
 * 反推玩家的选择。</p>
 *
 * <p>★ 现行方案里这个角色由 {@link BrokenOverworldChunkGenerator} 兼任
 * （「破碎」世界类型的主世界直接用它，破碎地形本身就是标记），
 * 所以本类目前<b>没有被任何维度 json 引用</b>，仅注册保留备用。</p>
 *
 * <h2>★ 与「破碎世界」维度无关</h2>
 *
 * <p>别搞混：</p>
 * <table border="1">
 *   <tr><th>名字</th><th>是什么</th></tr>
 *   <tr><td>{@code minecraft:overworld}（主世界）</td>
 *       <td>「破碎」世界类型下就是破碎世界本身（逐方块碎裂场），
 *           出生/复活走原版逻辑（平静区地表，开局空岛系统已移除）</td></tr>
 *   <tr><td>{@code maixr:overworld_broken}（破碎维度）</td>
 *       <td>老式空岛 + 95% 虚空维度（{@link BrokenSkyblockChunkGenerator}），
 *           仅兼容老存档保留，不再是出生地</td></tr>
 * </table>
 */
public class BrokenWorldMarkerGenerator extends NoiseBasedChunkGenerator {

    /**
     * 【1.21】必须是 {@link MapCodec}，不能声明成父类型 {@code Codec}。
     *
     * <p>{@code Registries.CHUNK_GENERATOR} 的值类型是 {@code MapCodec<? extends ChunkGenerator>}；
     * 声明成 {@code Codec} 会让注册表在反序列化维度 json 时抛
     * {@code ClassCastException}，症状是「创建世界页面卡住」。</p>
     */
    public static final MapCodec<BrokenWorldMarkerGenerator> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
                    NoiseGeneratorSettings.CODEC.fieldOf("settings")
                            .forGetter(BrokenWorldMarkerGenerator::settings)
            ).apply(instance, BrokenWorldMarkerGenerator::new));

    /** 让本类被识别为自身的 codec（否则会沿用父类的，反序列化时类型对不上）。 */
    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    /**
     * 自己存一份 settings。
     *
     * <p>父类 {@code NoiseBasedChunkGenerator} 的 {@code settings} 字段是
     * <b>private</b>，子类读不到，所以这里必须留一份自己的引用给 codec 用。</p>
     */
    private final Holder<NoiseGeneratorSettings> settings;

    public BrokenWorldMarkerGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings) {
        super(biomeSource, settings);
        this.settings = settings;
    }

    /** 给 codec 用的 getter。 */
    public Holder<NoiseGeneratorSettings> settings() {
        return this.settings;
    }
}
