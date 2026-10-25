import com.maixr.FractureDensityFunction;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryDataLoader;
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
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 差异区块的撕裂边界过渡带核验（变更 2026-10-25-fracture-perf-leaps 任务 3.1/3.3，
 * design D4 L2 豁免判据）：对差异区块坐标离线采样 fracture（每 4 格，16 点/区块），
 * 分类：TRANSITION（区块内同时存在完全撕裂点与存活点 = 撕裂边界过渡带，允许差异）/
 * TORN-INTERIOR（全撕裂腹地，差异 = 豁免失败）/ ALIVE-INTERIOR（完好腹地，同上）。
 *
 * 输入（stdin）：每行 `<文件名> <cx> <cz>`（文件名 = r.<rx>.<rz>.mca，cx/cz = region 内坐标），
 * 即 mca_diff.py 输出的差异区块坐标。输出分类统计与明细。
 */
public final class FractureBoundaryCheck {
    static final double TEAR_SCALE = FractureDensityFunction.TEAR_SCALE;
    static final double EPS = 0.001D;
    static FractureDensityFunction FRACTURE;

    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.LoadingModList.of(
                List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ((MappedRegistry<?>) BuiltInRegistries.DENSITY_FUNCTION_TYPE).unfreeze();
        registerType("fracture", FractureDensityFunction.MAP_CODEC);
        registerType("plate_field", com.maixr.PlateFieldDensityFunction.MAP_CODEC);
        registerType("plate_displace", com.maixr.PlateDisplaceDensityFunction.MAP_CODEC);

        RegistryAccess.Frozen access;
        try (var vanilla = ServerPacksSource.createVanillaPackSource()) {
            // 注意：读 src/main/resources（原装配）。fracture 子树与本变更装配无关，
            // 离线 RegistryOps 上下文对 spike 版顶层 range_choice 的 either 解码不友好（FML 路径正常）。
            var maixr = new PathPackResources(new PackLocationInfo(
                    "maixr", Component.literal("maixr"), PackSource.BUILT_IN, Optional.empty()),
                    Path.of("src/main/resources"));
            ResourceManager rm = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(vanilla, maixr));
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
        RandomState rs = RandomState.create(access.registryOrThrow(Registries.NOISE_SETTINGS).get(key),
                access.lookupOrThrow(Registries.NOISE), 12345L);
        FRACTURE = findFracture(rs.router().finalDensity());

        Map<String, List<int[]>> byFile = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            String[] p = line.trim().split("\\s+");
            if (p.length != 3) {
                continue;
            }
            byFile.computeIfAbsent(p[0], k -> new ArrayList<>()).add(
                    new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2])});
        }

        int transition = 0, tornInterior = 0, aliveInterior = 0;
        StringBuilder out = new StringBuilder();
        for (var e : byFile.entrySet()) {
            String fname = e.getKey();
            String[] parts = fname.replace("r.", "").replace(".mca", "").replace("\\.", ".").split("\\.");
            int rx = Integer.parseInt(parts[0]), rz = Integer.parseInt(parts[1]);
            for (int[] cc : e.getValue()) {
                int wx = rx * 32 + cc[0], wz = rz * 32 + cc[1];
                int torn = 0, alive = 0;
                for (int lx = 0; lx < 16; lx += 4) {
                    for (int lz = 0; lz < 16; lz += 4) {
                        double v = FRACTURE.compute(new DensityFunction.SinglePointContext(wx * 16 + lx, 0, wz * 16 + lz));
                        if (v >= -TEAR_SCALE && v < -TEAR_SCALE + EPS) {
                            torn++;
                        } else {
                            alive++;
                        }
                    }
                }
                String cls = torn > 0 && alive > 0 ? "TRANSITION"
                        : torn == 16 ? "TORN-INTERIOR"
                        : alive == 16 ? "ALIVE-INTERIOR"
                        : "MIXED-EDGE(" + torn + "/16)";
                if (cls.equals("TRANSITION")) {
                    transition++;
                } else if (cls.startsWith("TORN")) {
                    tornInterior++;
                } else {
                    aliveInterior++;
                }
                out.append(String.format("%s (%d,%d) 区块世界坐标[%d,%d] torn=%d/16 -> %s%n",
                        fname, cc[0], cc[1], wx, wz, torn, cls));
            }
        }
        System.out.printf("核验：TRANSITION=%d  TORN-INTERIOR=%d  ALIVE-INTERIOR=%d  总=%d%n",
                transition, tornInterior, aliveInterior, transition + tornInterior + aliveInterior);
        System.out.print(out);
    }

    private static void registerType(String name, com.mojang.serialization.MapCodec<? extends DensityFunction> codec) {
        net.minecraft.core.Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                ResourceLocation.fromNamespaceAndPath("maixr", name), codec);
    }

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
        for (Field field : node.getClass().getDeclaredFields()) {
            if (field.getType() == DensityFunction.class
                    || DensityFunction.class.isAssignableFrom(field.getType())
                    || field.getType() == Holder.class) {
                field.setAccessible(true);
                FractureDensityFunction r = findFracture(field.get(node));
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private FractureBoundaryCheck() {
    }
}
