package com.maixr.client;

/**
 * ★ 只允许在客户端加载的包处理集中营。
 *
 * <p>为什么要有这个类：专用服务端上没有 {@code net.minecraft.client.*} 这些类。
 * 如果网络包的 handle 方法里直接写 {@code Minecraft.getInstance()} / {@code LocalPlayer}，
 * 那么 {@code MaixrNetwork} 的静态初始化（注册取方法引用时）就会在服务端
 * 触发客户端类加载 —— 实测报错：
 * <pre>
 * BootstrapMethodError: Attempted to load class net/minecraft/client/player/LocalPlayer
 *                        for invalid dist DEDICATED_SERVER
 *   at com.maixr.MaixrNetwork.&lt;clinit&gt;
 * </pre>
 * 所以所有客户端逻辑都收在这里，保证 {@code MaixrNetwork} 的字节码里
 * 一个客户端类都不出现。</p>
 */
public final class MaixrClientPackets {
    private MaixrClientPackets() {}

    /** 真理玩偶触发不死保护：屏幕中央播放玩偶图标动画 + 不死图腾音效 */
    public static void truthTotem() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;
        mc.gameRenderer.displayItemActivation(
                new net.minecraft.world.item.ItemStack(com.maixr.MaixrMod.TRUTH_DOLL_ITEM.get()));
        mc.player.level().playSound(mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                net.minecraft.sounds.SoundEvents.TOTEM_USE, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F);
    }
}
