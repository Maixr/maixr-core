package com.maixr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 伊甸工程的「物品提交池」—— <b>静态配置</b>。
 *
 * <h2>设计原则：唯一数据源</h2>
 *
 * <p>池子<b>完全</b>由 {@code config/maixr/eden_pool.json} 决定，
 * <b>不再</b>从配方表动态推算。理由：</p>
 * <ul>
 *   <li>动态推算要递归遍历所有配方（{@code computeCost}），启动慢、偶尔算错</li>
 *   <li>整合包作者（你）需要精确控制"哪个阶段点名哪些物品、要几个"</li>
 *   <li>改了模组之后，动态结果会漂移；静态配置永远稳定</li>
 * </ul>
 *
 * <h2>配置文件格式</h2>
 *
 * <pre>{@code
 * {
 *   "defaultCount": 16,
 *   "stages": [
 *     { "name": "残响", "items": { "minecraft:stone": 16, "ae2:blank_pattern": 8 } },
 *     { "name": "篝火", "items": { ... } },
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * <p>阶段的<b>顺序</b>就是进度顺序：第 1 个阶段对应 0%~10%，最后一个对应 90%~100%。</p>
 *
 * <h2>热重载</h2>
 *
 * <p>服务器资源重载（{@code /reload}）时会重新读文件，改完 JSON 不用重启游戏。</p>
 */
public final class EdenPool {

    /** 配置文件在 jar 里的路径；同时也会尝试读游戏目录下的同名文件。 */
    private static final String RESOURCE = "/config/maixr/eden_pool.json";
    private static final String EXTERNAL = "config/maixr/eden_pool.json";

    /** 阶段名（按顺序）。 */
    private static List<String> stageNames = new ArrayList<>();
    /** 每个阶段 -> (物品 -> 数量)。 */
    private static List<Map<Item, Integer>> stageItems = new ArrayList<>();
    /** 是否已加载成功。 */
    private static boolean loaded = false;
    /** 加载失败时的原因（给日志/命令显示）。 */
    private static String lastError = null;

    /** 默认数量（配置里没写数量时用它）。 */
    public static int defaultCount = 16;

    // ==========================================================================
    //  加载
    // ==========================================================================

    /** 强制重新加载（改完 JSON 后 / reload 时调用）。 */
    public static synchronized void reload() {
        loaded = false;
        lastError = null;
        stageNames = new ArrayList<>();
        stageItems = new ArrayList<>();
        load();
    }

    private static synchronized void load() {
        if (loaded) return;

        String text = readExternal();
        if (text == null) {
            text = readResource();
        }
        if (text == null) {
            lastError = "找不到 " + EXTERNAL + "（jar 内也没有 " + RESOURCE + "）";
            MaixrMod.LOGGER.error("[伊甸] 读取提交池失败：{}", lastError);
            loaded = true;
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (root.has("defaultCount")) {
                defaultCount = root.get("defaultCount").getAsInt();
            }
            JsonArray stages = root.getAsJsonArray("stages");
            if (stages == null) {
                lastError = "配置里没有 stages 数组";
                MaixrMod.LOGGER.error("[伊甸] {}", lastError);
                loaded = true;
                return;
            }

            int skipped = 0;
            for (JsonElement se : stages) {
                JsonObject so = se.getAsJsonObject();
                String name = so.has("name") ? so.get("name").getAsString() : ("阶段" + (stageItems.size() + 1));
                Map<Item, Integer> map = new LinkedHashMap<>();

                JsonObject items = so.getAsJsonObject("items");
                if (items != null) {
                    for (Map.Entry<String, JsonElement> e : items.entrySet()) {
                        ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                        if (id == null) { skipped++; continue; }
                        Item item = BuiltInRegistries.ITEM.get(id);
                        if (item == null || item == net.minecraft.world.item.Items.AIR) {
                            skipped++;      // 该模组没装 —— 静默跳过，不刷屏
                            continue;
                        }
                        int count = defaultCount;
                        try {
                            count = e.getValue().getAsInt();
                        } catch (Throwable ignored) {
                            // 允许写成字符串或对象，出错就用默认值
                        }
                        if (count < 1) count = 1;
                        map.put(item, count);
                    }
                }
                stageNames.add(name);
                stageItems.add(map);
            }

            loaded = true;
            int total = stageItems.stream().mapToInt(Map::size).sum();
            MaixrMod.LOGGER.info("[伊甸] 提交池已加载：{} 个阶段，共 {} 种物品{}",
                    stageNames.size(), total,
                    skipped > 0 ? ("（跳过 " + skipped + " 个未安装模组的物品）") : "");
        } catch (Throwable t) {
            lastError = t.toString();
            MaixrMod.LOGGER.error("[伊甸] 解析提交池失败: {}", t.toString());
            loaded = true;
        }
    }

    /** 读游戏目录下的外部文件（优先，方便直接改）。 */
    private static String readExternal() {
        try {
            java.nio.file.Path p = java.nio.file.Path.of(EXTERNAL);
            if (java.nio.file.Files.isReadable(p)) {
                return java.nio.file.Files.readString(p, StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 读 jar 里的内置默认配置。 */
    private static String readResource() {
        try (InputStream in = EdenPool.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==========================================================================
    //  查询
    // ==========================================================================

    /** 池子是否可用（加载成功且至少有一个物品）。 */
    public static boolean isReady() {
        load();
        return isReadyRaw();
    }

    private static boolean isReadyRaw() {
        for (Map<Item, Integer> m : stageItems) {
            if (!m.isEmpty()) return true;
        }
        return false;
    }

    /** 加载失败原因；成功时为 null。 */
    public static String error() {
        load();
        return lastError;
    }

    /** 阶段数（= 配置里 stages 的长度）。 */
    public static int stageCount() {
        load();
        return stageItems.size();
    }

    /** 阶段名。 */
    public static String stageName(int stage) {
        load();
        if (stage < 0 || stage >= stageNames.size()) return "未知";
        return stageNames.get(stage);
    }

    /** 全部阶段名（只读）。 */
    public static List<String> stageNames() {
        load();
        return Collections.unmodifiableList(stageNames);
    }

    /** 某阶段的物品表。 */
    public static Map<Item, Integer> itemsOf(int stage) {
        load();
        if (stage < 0 || stage >= stageItems.size()) return Collections.emptyMap();
        return stageItems.get(stage);
    }

    /** 物品总数（所有阶段合计）。 */
    public static int totalItems() {
        load();
        int n = 0;
        for (Map<Item, Integer> m : stageItems) n += m.size();
        return n;
    }

    /** 各阶段物品数（给日志/命令展示）。 */
    public static int[] stageSizes() {
        load();
        int[] a = new int[stageItems.size()];
        for (int i = 0; i < a.length; i++) a[i] = stageItems.get(i).size();
        return a;
    }

    private EdenPool() {}
}
