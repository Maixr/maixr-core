package com.maixr;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 注册自定义密度函数类型（供 noise_settings JSON 里的
 * {@code final_density} 等字段以 {@code "type": "maixr:xxx"} 引用）。
 *
 * <p>【1.21.1 硬规则】{@code Registries.DENSITY_FUNCTION_TYPE} 的值类型是
 * {@code MapCodec<? extends DensityFunction>}（与 {@code CHUNK_GENERATOR} 同款），
 * <b>不能</b>声明成父类型 {@code Codec}，否则反序列化 noise_settings 时抛
 * {@code ClassCastException}（症状：创建世界页面卡住 / Failed to parse worldgen JSON）。</p>
 */
public final class MaixrDensityFunctions {

    public static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY_FUNCTION_TYPES =
            DeferredRegister.create(
                    (net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<MapCodec<? extends DensityFunction>>>)
                            (Object) Registries.DENSITY_FUNCTION_TYPE,
                    MaixrMod.MODID);

    private MaixrDensityFunctions() {}

    public static void register(IEventBus modBus) {
        // 碎裂场三件套（noise_settings JSON 以 "type": "maixr:xxx" 引用）：
        //   fracture  —— 四带渐变 × 脊状裂缝 × 板块存活（±震中加压）的符号掩码
        //   plate_field —— 2D Voronoi 板块存活率（fracture 的 plates 子函数）
        //   plate_displace —— 板块漂移坐标偏移包装器（包住原版地形子树）
        DENSITY_FUNCTION_TYPES.register("fracture", () -> FractureDensityFunction.MAP_CODEC);
        DENSITY_FUNCTION_TYPES.register("plate_field", () -> PlateFieldDensityFunction.MAP_CODEC);
        DENSITY_FUNCTION_TYPES.register("plate_displace", () -> PlateDisplaceDensityFunction.MAP_CODEC);
        DENSITY_FUNCTION_TYPES.register(modBus);
    }
}
