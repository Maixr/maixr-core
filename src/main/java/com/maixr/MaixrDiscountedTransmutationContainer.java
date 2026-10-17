package com.maixr;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;

/**
 * 「带贬值的转化桌」容器工厂 —— <b>零编译期依赖，全靠动态代理</b>。
 *
 * <h2>需求</h2>
 * <ul>
 *   <li>裂隙所遗弃：卖出 <b>4 折</b>　｜　艰难重生：<b>6 折</b>　｜　弧光孕育：<b>8 折</b></li>
 *   <li><b>买入一律原价</b>（用 EMC 换物品不受影响）</li>
 *   <li>原版便携式转化桌 / 转化桌：<b>完全不打折</b></li>
 * </ul>
 *
 * <h2>为什么用动态代理，而不是继承</h2>
 *
 * <p>早期实现是 {@code extends TransmutationContainer} 并 override {@code clickPostValidate}。
 * 问题：ProjectE <b>不是必需依赖</b> —— 玩家不装它时，
 * <b>光加载那个子类就会抛 {@code NoClassDefFoundError}</b>，整个模组崩掉。</p>
 *
 * <p>现在改成：</p>
 * <ol>
 *   <li>反射构造 ProjectE 的 {@code TransmutationContainer}</li>
 *   <li>用 {@link Proxy} 包一层，只在 {@code clickPostValidate} 这一个方法上插入折扣逻辑</li>
 *   <li>其余所有方法<b>原样转发</b>给真实容器</li>
 * </ol>
 *
 * <p>于是功能完全一致，而本类里<b>没有任何一个 ProjectE 类型</b>出现在签名或 import 里。</p>
 *
 * <h2>折扣原理</h2>
 *
 * <p>ProjectE 的卖出发生在 {@code SlotConsume.set()} 里：
 * <pre>
 *   inv.addEmc(BigInteger.valueOf(EMCHelper.getEmcSellValue(stack))
 *              .multiply(BigInteger.valueOf(stack.getCount())));
 * </pre>
 * 而"点击处理"的入口是 {@code clickPostValidate(int, int, ClickType, Player)}。</p>
 *
 * <p>所以我们在代理里量一次前后 EMC：</p>
 * <ol>
 *   <li>点击前记下 EMC</li>
 *   <li>转发给真实容器 —— 此时按<b>原价</b>卖出</li>
 *   <li>点击后再量：若 EMC <b>净增</b>，说明这次点击就是一次卖出，
 *       把多给的部分扣回去</li>
 * </ol>
 *
 * <h2>为什么不能"每 tick 比较 EMC 增量"</h2>
 * <p>本包里有 30+ 处会给玩家加 EMC 的东西（能量花、EMC 链接方块、炼金收集器、
 * 克莱因星、永恒密度宝石…）。用 tick 轮询会把它们全部误判成卖出并扣税。
 * 本方案的作用域被严格限制在 <b>「本容器内、本次点击」</b>。</p>
 */
public final class MaixrDiscountedTransmutationContainer {

    /** ProjectE 转化桌容器的类名。 */
    private static final String PE_CONTAINER = "moze_intel.projecte.gameObjs.container.TransmutationContainer";

    /**
     * 造一个「带贬值」的 ProjectE 转化桌容器。
     *
     * @param containerId     容器 id
     * @param playerInventory 玩家背包
     * @param hand            用哪只手打开（写进界面同步数据）
     * @param selected        打开时快捷栏选中的格（写进界面同步数据）
     * @param sellRate        本桌卖出折率（0~1，1.0 = 原价）
     * @return 菜单实例（动态代理）；
     *         <b>ProjectE 未装或构造失败时返回 {@code null}</b>（调用方需判空）
     */
    public static Object create(int containerId, Inventory playerInventory,
                                InteractionHand hand, int selected, double sellRate) {
        Object real = construct(containerId, playerInventory, hand, selected);
        if (real == null) return null;
        if (sellRate >= 1.0) return real;      // 原价桌：不用代理，省一层调用

        Class<?>[] ifaces = collectInterfaces(real.getClass());
        if (ifaces.length == 0) {
            MaixrMod.LOGGER.warn("[maixr] 转化桌容器没有可代理的接口，贬值功能不可用");
            return real;
        }

        return Proxy.newProxyInstance(
                MaixrDiscountedTransmutationContainer.class.getClassLoader(),
                ifaces,
                new DiscountHandler(real, sellRate));
    }

    /** 反射构造 ProjectE 的 TransmutationContainer。 */
    private static Object construct(int containerId, Inventory inv, InteractionHand hand, int selected) {
        if (!ProjectEBridge.isLoaded()) return null;
        try {
            Class<?> cls = Class.forName(PE_CONTAINER);
            var ctor = cls.getConstructor(int.class, Inventory.class, InteractionHand.class, int.class);
            return ctor.newInstance(containerId, inv, hand, selected);
        } catch (Throwable t) {
            MaixrMod.LOGGER.warn("[maixr] 构造 ProjectE 转化桌容器失败: {}", t.toString());
            return null;
        }
    }

    /** 收集一个类实现的全部接口（含父类继承来的）。 */
    private static Class<?>[] collectInterfaces(Class<?> cls) {
        java.util.LinkedHashSet<Class<?>> set = new java.util.LinkedHashSet<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                collectInto(set, i);
            }
        }
        return set.toArray(new Class<?>[0]);
    }

    private static void collectInto(java.util.Set<Class<?>> set, Class<?> i) {
        if (set.add(i)) {
            for (Class<?> parent : i.getInterfaces()) {
                collectInto(set, parent);
            }
        }
    }

    // ==========================================================================
    //  代理处理器：只拦 clickPostValidate，其余原样转发
    // ==========================================================================

    private static final class DiscountHandler implements InvocationHandler {
        private final Object real;
        private final double sellRate;

        DiscountHandler(Object real, double sellRate) {
            this.real = real;
            this.sellRate = sellRate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            if ("clickPostValidate".equals(name) && args != null && args.length == 4
                    && args[3] instanceof Player player) {
                return handleClick(method, args, player);
            }

            // getEmc / setEmc 等我们自己读写 EMC 的调用，直接走桥接
            try {
                return method.invoke(real, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }

        /** 点击前后量 EMC，把多给的扣回去。 */
        private Object handleClick(Method method, Object[] args, Player player) throws Throwable {
            final BigInteger before = readEmc(player);

            Object result;
            try {
                result = method.invoke(real, args);     // 真正执行点击（按原价卖出）
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }

            if (before == null) return result;

            BigInteger after = readEmc(player);
            if (after == null) return result;

            BigInteger gained = after.subtract(before);
            if (gained.signum() <= 0) return result;    // 没赚 = 不是卖出（买入/无效点击）

            BigInteger shouldGive = scaled(gained, sellRate);
            BigInteger overPaid = gained.subtract(shouldGive);
            if (overPaid.signum() > 0) {
                writeEmc(player, after.subtract(overPaid));
            }
            return result;
        }
    }

    // ------------------------------------------------------------ EMC 读写

    private static BigInteger readEmc(Player player) {
        try {
            Object provider = ProjectEBridge.knowledgeProvider(player);
            if (provider == null) return null;
            Method m = provider.getClass().getMethod("getEmc");
            Object r = m.invoke(provider);
            return r instanceof BigInteger b ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeEmc(Player player, BigInteger value) {
        try {
            Object provider = ProjectEBridge.knowledgeProvider(player);
            if (provider == null) return;
            Method set = provider.getClass().getMethod("setEmc", BigInteger.class);
            set.invoke(provider, value);
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                try {
                    Method sync = provider.getClass().getMethod("syncEmc",
                            net.minecraft.server.level.ServerPlayer.class);
                    sync.invoke(provider, sp);
                } catch (Throwable ignored) {
                    // 没有 syncEmc 就算了，下次同步自然修正
                }
            }
        } catch (Throwable t) {
            MaixrMod.LOGGER.warn("[maixr] 转化桌贬值：写入 EMC 失败", t);
        }
    }

    /**
     * 计算 {@code amount × rate}，用<b>精确整数</b>运算。
     *
     * <p>折率以万分比表示（4 折 = 4000）：先乘整数再除 10000。
     * 本包含无尽贪婪，EMC 很容易超过 2^53（{@code double} 的尾数位数），
     * 用 double 会丢精度导致折扣算错。</p>
     */
    private static BigInteger scaled(BigInteger amount, double rate) {
        long perMyriad = Math.round(rate * 10000.0);
        return amount.multiply(BigInteger.valueOf(perMyriad))
                     .divide(BigInteger.valueOf(10000L));
    }

    private MaixrDiscountedTransmutationContainer() {}
}
