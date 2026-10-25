import com.maixr.FractureDensityFunction;
import com.maixr.PlateDisplaceDensityFunction;
import com.maixr.PlateFieldDensityFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 完全撕裂面积占比离线采样（变更 2026-10-25-fracture-perf-leaps 任务 1.3，design D3-1）。
 *
 * 离线 RandomState + fracture 采样：加载数据包注册表（vanilla pack + maixr resources），
 * 解析 maixr:broken_overworld noise settings，RandomState.create 注入种子 12345，
 * 从 final_density 树反射下钻找 FractureDensityFunction 实例，逐点 compute。
 *
 * 口径：
 *  - 主窗口 64×64 区块（1024×1024 格，x,z ∈ [-512,512)），步长 4（design 要求）；
 *  - 9 个基准区域窗口（与 _tools/forceload_bench.py 的区域表一致，中心 = from+64，
 *    窗口 16×16 区块 = ±128 格），直接校准 L2 在基准坐标下的收益上限；
 *  - 分类：完全撕裂精确 = -TEAR_SCALE(-4.0)；range_choice 短路带 = [-4.0, -3.999)
 *    （含精确 -4，即 L2 实际命中口径）；完好 = 0.0；其余为部分撕裂过渡带。
 *
 * 用法（项目根目录）：
 *   javac -encoding UTF-8 -cp "$(cat run/devcp.txt)" -d _tools/classes _tools/FractureAreaProbe.java
 *   java -cp "_tools/classes;$(cat run/devcp.txt)" FractureAreaProbe
 */
public final class FractureAreaProbe {
    static final long SEED = 12345L;
    static final double TEAR_SCALE = FractureDensityFunction.TEAR_SCALE;
    static final double EPS = 0.001D; // range_choice 短路带宽 [-4, -3.999)
    static FractureDensityFunction FRACTURE;

    /** 基准区域窗口（名称, 中心 x, 中心 z）—— 中心 = forceload from + 64。 */
    static final Object[][] WINDOWS = {
            {"R1", 6208, 6464}, {"R2", 9536, 5440}, {"R3", 5440, 10560},
            {"R4", 12352, 7488}, {"R5", 7744, 13888}, {"R6", 15424, 10048},
            {"R7", 11072, 16448}, {"R8", 17984, 12864}, {"R9", 14144, 18752},
    };

    public static void main(String[] args) throws Exception {
        // 脱离 FML 的 main 进程：NeoForge 的 Bootstrap 会查 LoadingModList（FeatureFlag modded
        // flags），伪造一个空单例（无 mod 文件 → 任何 modded flag 遍历都为空）。
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        if (net.minecraft.SharedConstants.getCurrentVersion() == null) {
            throw new IllegalStateException("version.json 探测失败（client-extra jar 不在 classpath？）");
        }
        Bootstrap.bootStrap();
        // DensityFunction.<clinit> 依赖 bootstrap 后的注册表，而 codec 注册须赶在冻结前；
        // 先 bootstrap 再解冻 codec 注册表，是离线进程的唯一顺序。
        ((net.minecraft.core.MappedRegistry<?>) BuiltInRegistries.DENSITY_FUNCTION_TYPE).unfreeze();
        registerTypes();

        RegistryAccess.Frozen access;
        try (var vanilla = ServerPacksSource.createVanillaPackSource()) {
            var maixr = new PathPackResources(new PackLocationInfo(
                    "maixr", Component.literal("maixr"), PackSource.BUILT_IN, Optional.empty()),
                    Path.of("build/resources/main"));
            ResourceManager rm = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(vanilla, maixr));
            // 只加载 fracture 采样需要的三张注册表：noise → density_function → noise_settings。
            // 全量 WORLDGEN_REGISTRIES 会连 enchantment/biome 一起解析（tag/Block 引用脱离 FML 跑不动）。
            var registries = List.of(
                    new RegistryDataLoader.RegistryData<>(Registries.NOISE,
                            net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters.DIRECT_CODEC, false),
                    new RegistryDataLoader.RegistryData<>(Registries.DENSITY_FUNCTION,
                            DensityFunction.DIRECT_CODEC, false),
                    new RegistryDataLoader.RegistryData<>(Registries.NOISE_SETTINGS,
                            NoiseGeneratorSettings.DIRECT_CODEC, false));
            access = RegistryDataLoader.load(rm, RegistryAccess.EMPTY, registries);
        }

        ResourceKey<NoiseGeneratorSettings> key = ResourceKey.create(Registries.NOISE_SETTINGS,
                ResourceLocation.fromNamespaceAndPath("maixr", "broken_overworld"));
        NoiseGeneratorSettings settings = access.registryOrThrow(Registries.NOISE_SETTINGS)
                .get(key);
        if (settings == null) {
            throw new IllegalStateException("maixr:broken_overworld noise settings 未加载");
        }
        RandomState rs = RandomState.create(settings, access.lookupOrThrow(Registries.NOISE), SEED);
        DensityFunction fd = rs.router().finalDensity();
        FRACTURE = findFracture(fd);
        if (FRACTURE == null) {
            StringBuilder sb = new StringBuilder("final_density 树中未找到 FractureDensityFunction，结构：\n");
            dumpTree(fd, 0, 5, sb);
            throw new IllegalStateException(sb.toString());
        }

        StringBuilder out = new StringBuilder();
        out.append("完全撕裂面积占比采样 seed=").append(SEED)
                .append("（maixr:broken_overworld，uniformBand=")
                .append(net.minecraft.util.Mth.clamp(
                        com.maixr.MaixrConfig.uniformBand(), 0.0D, 3.0D))
                .append("，分类：torn_exact = 精确 -4.0；torn_band = [-4.0,-3.999) 即 L2 短路命中；")
                .append("intact = 0.0；partial = 其余过渡带）\n");

        window(out, "主窗口 64x64 区块 [-512,512)", -512, -512, 1024);
        for (Object[] w : WINDOWS) {
            window(out, w[0] + " 16x16 区块", (Integer) w[1] - 128, (Integer) w[2] - 128, 256);
        }
        System.out.println(out);
        Files.createDirectories(Path.of("_bench-data"));
        Files.writeString(Path.of("_bench-data/fracture-area.txt"), out.toString());
    }

    /** 单窗口采样：origin = 窗口左上角（格），size = 边长（格），步长 4。 */
    private static void window(StringBuilder out, String label, int ox, int oz, int size) {
        long tornExact = 0, tornBand = 0, intact = 0, partial = 0, total = 0;
        for (int x = ox; x < ox + size; x += 4) {
            for (int z = oz; z < oz + size; z += 4) {
                double v = FRACTURE.compute(new DensityFunction.SinglePointContext(x, 0, z));
                if (v == -TEAR_SCALE) tornExact++;
                if (v >= -TEAR_SCALE && v < -TEAR_SCALE + EPS) tornBand++;
                else if (v == 0.0D) intact++;
                else partial++;
                total++;
            }
        }
        out.append(String.format("%-34s  torn_exact=%6.2f%%  torn_band(L2)=%6.2f%%  intact=%6.2f%%  partial=%6.2f%%  (n=%d)%n",
                label, pct(tornExact, total), pct(tornBand, total), pct(intact, total), pct(partial, total), total));
    }

    /** 同一实例直接 compute（列缓存按 (x,z) 命中，y 无关）。 */
    private static double fractureValue(FractureDensityFunction f, int x, int z,
                                        DensityFunction.SinglePointContext ctx) {
        return f.compute(new DensityFunction.SinglePointContext(x, 0, z));
    }

    private static double pct(long n, long total) {
        return total == 0 ? 0.0D : 100.0D * n / total;
    }

    /** 从 final_density 树反射下钻找 FractureDensityFunction（vanilla wrapper 均为字段持有子函数；
     *  noise_router 解析经 holder-helper codec，内联表达式会被包成 HolderHolder —— 需穿透 Holder）。 */
    private static FractureDensityFunction findFracture(Object node) throws Exception {
        if (node instanceof Holder<?> h) {
            return findFracture(h.value());
        }
        if (node instanceof FractureDensityFunction f) {
            return f;
        }
        if (node == null || node instanceof Number || node instanceof String || node instanceof Enum) {
            return null;
        }
        for (Field field : allFields(node.getClass())) {
            field.setAccessible(true);
            Object child = field.get(node);
            if (child instanceof DensityFunction || child instanceof Holder<?>) {
                FractureDensityFunction r = findFracture(child);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 调试：打印 wrapper 树前 depth 层。 */
    private static void dumpTree(Object node, int depth, int maxDepth, StringBuilder sb) throws Exception {
        sb.append("  ".repeat(depth)).append(node == null ? "null"
                : node.getClass().getName()).append('\n');
        if (node == null || depth >= maxDepth || node instanceof Number || node instanceof String
                || node instanceof Enum) {
            return;
        }
        try {
            for (Field field : allFields(node.getClass())) {
                field.setAccessible(true);
                Object child = field.get(node);
                sb.append("  ".repeat(depth + 1)).append(field.getName()).append(": ");
                if (child instanceof DensityFunction) {
                    sb.append('\n');
                    dumpTree(child, depth + 2, maxDepth, sb);
                } else {
                    sb.append(child).append('\n');
                }
            }
        } catch (Exception e) {
            sb.append("  <dump error: ").append(e).append(">\n");
        }
    }

    private static List<Field> allFields(Class<?> c) {
        List<Field> all = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() == DensityFunction.class
                        || DensityFunction.class.isAssignableFrom(f.getType())
                        || f.getType() == Holder.class) {
                    all.add(f);
                }
            }
        }
        return all;
    }

    /** MaixrDensityFunctions 走 DeferredRegister（mod bus），离线 probe 直接内置注册表手动注册等价项。 */
    private static void registerTypes() {
        registerType("fracture", FractureDensityFunction.MAP_CODEC);
        registerType("plate_field", PlateFieldDensityFunction.MAP_CODEC);
        registerType("plate_displace", PlateDisplaceDensityFunction.MAP_CODEC);
    }

    private static void registerType(String name, com.mojang.serialization.MapCodec<? extends DensityFunction> codec) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("maixr", name);
        register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, id, codec);
    }

    /** 捕获转换辅助：通配符注册表的实例 register 不可直接调用，走静态 Registry.register。 */
    private static <V> void register(net.minecraft.core.Registry<V> reg, ResourceLocation id, V value) {
        if (reg.get(id) == null) {
            net.minecraft.core.Registry.register(reg, id, value);
        }
    }

    private FractureAreaProbe() {
    }
}
