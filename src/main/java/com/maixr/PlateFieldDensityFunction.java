package com.maixr;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 「破碎」板块场 {@code maixr:plate_field} —— 输出板块存活率 ∈ [0,1]
 * （2D Voronoi + cell 哈希死亡 + 碎屑子层，核心逻辑见 {@link PlateFieldLogic}）。
 *
 * <p>作为 {@code maixr:fracture} 的 {@code plates} 子函数组合使用
 * （JSON：{@code {"type":"maixr:plate_field","scale":1.0}}）。
 * {@code scale} 为维度折算系数：下界 1/8（板块/碎屑 cell 与四带半径同步折算）。</p>
 */
public final class PlateFieldDensityFunction implements DensityFunction.SimpleFunction {

    public static final MapCodec<PlateFieldDensityFunction> MAP_CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    Codec.doubleRange(0.03125D, 1.0D).optionalFieldOf("scale", 1.0D).forGetter(f -> f.scale),
                    Codec.doubleRange(0.0D, 3.0D).optionalFieldOf("band_floor", 0.0D).forGetter(f -> f.bandFloor),
                    Codec.doubleRange(0.0D, 4096.0D).optionalFieldOf("calm_radius", FractureDensityFunction.CALM_RADIUS).forGetter(f -> f.calmRadius)
            ).apply(instance, PlateFieldDensityFunction::new));

    public static final KeyDispatchDataCodec<PlateFieldDensityFunction> CODEC =
            KeyDispatchDataCodec.of(MAP_CODEC);

    private final double scale;
    private final double bandFloor;
    private final double calmRadius;

    public PlateFieldDensityFunction(double scale) {
        this(scale, 0.0D);
    }

    public PlateFieldDensityFunction(double scale, double bandFloor) {
        this(scale, bandFloor, FractureDensityFunction.CALM_RADIUS);
    }

    public PlateFieldDensityFunction(double scale, double bandFloor, double calmRadius) {
        this.scale = scale;
        this.bandFloor = bandFloor;
        this.calmRadius = calmRadius;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        return PlateFieldLogic.survival(context.blockX(), context.blockZ(), this.scale, this.bandFloor, this.calmRadius);
    }

    @Override
    public double minValue() {
        return 0.0D;
    }

    @Override
    public double maxValue() {
        return 1.0D;
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(this);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
