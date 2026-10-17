package com.maixr;

import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;

/**
 * ProjectE 桥接 —— <b>纯反射实现，零编译期依赖</b>。
 *
 * <h2>为什么必须是反射</h2>
 *
 * <p>本模组的「转化桌」系列物品复用 ProjectE 的转化桌界面。
 * 但 ProjectE <b>不是必需依赖</b> —— 玩家可以不装它。</p>
 *
 * <p>如果用 {@code import moze_intel.projecte...} 直接引用，
 * 那么 ProjectE 缺失时，<b>光加载本类就会抛 {@code NoClassDefFoundError}</b>，
 * 连带整个模组崩溃。所以这里全部走反射：</p>
 *
 * <ul>
 *   <li>类名以字符串形式出现 —— 没有类加载器会去解析它</li>
 *   <li>方法查找失败 = ProjectE 没装，静默返回 {@code null}</li>
 *   <li>调用方判空即可，不会崩</li>
 * </ul>
 *
 * <h2>ProjectE 的 API 位置（1.21.1 / PE1.1.0）</h2>
 *
 * <pre>
 * 能力常量：moze_intel.projecte.api.capabilities.PECapabilities.KNOWLEDGE_CAPABILITY
 * 返回类型：moze_intel.projecte.api.capabilities.IKnowledgeProvider
 * 取法：    player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY)   ← 直接返回实例
 * </pre>
 *
 * <p>注意 1.21 的 NeoForge capability 已经是"直接返回实例"（不再有 {@code LazyOptional} 包装），
 * 所以反射取到的方法返回值判空即可。</p>
 */
public final class ProjectEBridge {

    /** ProjectE 是否装了（只查一次）。 */
    private static Boolean loaded = null;

    /** ProjectE 是否已加载。 */
    public static boolean isLoaded() {
        if (loaded == null) {
            try {
                loaded = ModList.get() != null && ModList.get().isLoaded("projecte");
            } catch (Throwable t) {
                loaded = false;
            }
        }
        return loaded;
    }

    /**
     * 取玩家的 EMC 账户（ProjectE 的 {@code IKnowledgeProvider}）。
     *
     * @return 能力实例；ProjectE 未装 / 取不到时返回 {@code null}
     */
    public static Object knowledgeProvider(Player player) {
        if (!isLoaded() || player == null) return null;
        try {
            // ① 取 PECapabilities.KNOWLEDGE_CAPABILITY 这个静态字段
            Class<?> capsClass = Class.forName("moze_intel.projecte.api.capabilities.PECapabilities");
            Object capability = capsClass.getField("KNOWLEDGE_CAPABILITY").get(null);
            if (capability == null) return null;

            // ② player.getCapability(capability) —— 1.21 的 Entity#getCapability 返回能力实例本身
            for (Method m : player.getClass().getMethods()) {
                if (!m.getName().equals("getCapability")) continue;
                if (m.getParameterCount() != 1) continue;
                try {
                    Object result = m.invoke(player, capability);
                    if (result != null) return result;
                } catch (Throwable ignored) {
                    // 换下一个同名重载
                }
            }
            return null;
        } catch (ClassNotFoundException e) {
            return null;    // ProjectE 没装
        } catch (Throwable t) {
            MaixrMod.LOGGER.debug("[maixr] 取 ProjectE 知识提供者失败: {}", t.toString());
            return null;
        }
    }

    /**
     * 取玩家的 EMC 数值。
     *
     * @return EMC；拿不到时返回 0
     */
    public static long emc(Player player) {
        Object provider = knowledgeProvider(player);
        if (provider == null) return 0L;
        try {
            Method m = provider.getClass().getMethod("getEmc");
            Object r = m.invoke(provider);
            return r instanceof Number n ? n.longValue() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 设置玩家的 EMC 数值。
     *
     * @return 是否成功
     */
    public static boolean setEmc(Player player, long value) {
        Object provider = knowledgeProvider(player);
        if (provider == null) return false;
        try {
            Method m = provider.getClass().getMethod("setEmc", java.math.BigInteger.class);
            m.invoke(provider, java.math.BigInteger.valueOf(value));
            return true;
        } catch (Throwable t) {
            try {
                Method m = provider.getClass().getMethod("setEmc", long.class);
                m.invoke(provider, value);
                return true;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    private ProjectEBridge() {}
}
