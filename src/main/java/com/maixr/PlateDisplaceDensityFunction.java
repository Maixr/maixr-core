package com.maixr;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * 「破碎」板块形变包装器 {@code maixr:plate_displace} ——
 * 按板块漂移量偏移采样坐标后转发子 DF（垂直漂移 / 相邻断层错位都在这里，spec「垂直形变」）。
 *
 * <h2>为什么偏移采样是安全的（design D7 第 3 条，spike 实测结论）</h2>
 *
 * <p>本类在 JSON 里放在 {@code minecraft:interpolated} <b>内层</b>包住原版地形子树：</p>
 * <ul>
 *   <li>填充循环（角点）阶段 ctx == NoiseChunk → 漂移非零时用
 *       {@link DensityFunction.SinglePointContext} 偏移坐标直算 ——
 *       子树内没有 {@code interpolated} 标记（已核实 vanilla 被引用的
 *       density_function JSON 全部无 interpolated），不会踩
 *       NoiseInterpolator 的状态缓存；</li>
 *   <li>被引用函数里的 {@code flat_cache}/{@code cache_2d} 对任意坐标都正确
 *       （越界回退直算）；</li>
 *   <li>逐方块阶段走插值器 lerp，不经过本类。</li>
 * </ul>
 *
 * <p>漂移幅度按四带 {@code 0/5/15..25/30..40}（× 维度折算 {@code scale}），
 * {@code tilt} 为倾斜剪切参数，<b>默认 0 = 关闭</b>（spec「倾斜剪切默认关闭」）。
 * 同坐标重复采样确定性由 {@link PlateFieldLogic} 纯函数保证；板块边界过渡带
 * 平滑混合 → 邻区块接缝处连续。</p>
 */
public final class PlateDisplaceDensityFunction implements DensityFunction {

    public static final MapCodec<PlateDisplaceDensityFunction> MAP_CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    DensityFunction.HOLDER_HELPER_CODEC.fieldOf("input").forGetter(f -> f.input),
                    Codec.doubleRange(0.03125D, 1.0D).optionalFieldOf("scale", 1.0D).forGetter(f -> f.scale),
                    Codec.doubleRange(-16.0D, 16.0D).optionalFieldOf("tilt", 0.0D).forGetter(f -> f.tilt),
                    Codec.doubleRange(0.0D, 3.0D).optionalFieldOf("band_floor", 0.0D).forGetter(f -> f.bandFloor),
                    Codec.doubleRange(0.0D, 4096.0D).optionalFieldOf("calm_radius", FractureDensityFunction.CALM_RADIUS).forGetter(f -> f.calmRadius)
            ).apply(instance, PlateDisplaceDensityFunction::new));

    public static final KeyDispatchDataCodec<PlateDisplaceDensityFunction> CODEC =
            KeyDispatchDataCodec.of(MAP_CODEC);

    private final DensityFunction input;
    private final double scale;
    private final double tilt;
    private final double bandFloor;
    private final double calmRadius;

    public PlateDisplaceDensityFunction(DensityFunction input, double scale, double tilt) {
        this(input, scale, tilt, 0.0D, FractureDensityFunction.CALM_RADIUS);
    }

    public PlateDisplaceDensityFunction(DensityFunction input, double scale, double tilt, double bandFloor) {
        this(input, scale, tilt, bandFloor, FractureDensityFunction.CALM_RADIUS);
    }

    public PlateDisplaceDensityFunction(DensityFunction input, double scale, double tilt, double bandFloor, double calmRadius) {
        this.input = input;
        this.scale = scale;
        this.tilt = tilt;
        this.bandFloor = bandFloor;
        this.calmRadius = calmRadius;
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        PlateFieldLogic.Drift drift =
                PlateFieldLogic.drift(context.blockX(), context.blockZ(), this.scale, this.tilt, this.bandFloor, this.calmRadius);
        if (drift.dx() == 0.0D && drift.dy() == 0.0D && drift.dz() == 0.0D) {
            // 平静区零漂移：透传原 context（保持 vanilla 原生路径，含插值/缓存）
            return this.input.compute(context);
        }
        return this.input.compute(new DensityFunction.SinglePointContext(
                context.blockX() + (int) Math.round(drift.dx()),
                context.blockY() + (int) Math.round(drift.dy()),
                context.blockZ() + (int) Math.round(drift.dz())));
    }

    @Override
    public void fillArray(double[] array, DensityFunction.ContextProvider provider) {
        // 逐点走 compute（漂移坐标逐点不同），不能透传 input.fillArray
        provider.fillAllDirectly(array, this);
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return new PlateDisplaceDensityFunction(this.input.mapAll(visitor), this.scale, this.tilt, this.bandFloor, this.calmRadius);
    }

    @Override
    public double minValue() {
        return this.input.minValue();
    }

    @Override
    public double maxValue() {
        return this.input.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
