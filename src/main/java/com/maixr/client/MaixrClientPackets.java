package com.maixr.client;

/**
 * ★ 只允许在客户端加载的包处理集中营。
 *
 * <p>为什么要有这个类：专用服务端上没有 {@code net.minecraft.client.*} 这些类。
 * 如果网络包的 handle 方法里直接写 {@code Minecraft.getInstance()} / {@code LocalPlayer}，
 * 那么 {@code MaixrNetwork} 的静态初始化（registerMessage 取方法引用时）就会在服务端
 * 触发客户端类加载 —— 实测报错：
 * <pre>
 * BootstrapMethodError: Attempted to load class net/minecraft/client/player/LocalPlayer
 *                        for invalid dist DEDICATED_SERVER
 *   at com.maixr.MaixrNetwork.&lt;clinit&gt;
 * </pre>
 * 所以所有客户端逻辑都收在这里，服务端只用 DistExecutor 转发过来，
 * 保证 {@code MaixrNetwork} 的字节码里一个客户端类都不出现。
 */
public final class MaixrClientPackets {
    private MaixrClientPackets() {}

    /** 弹出「裂隙弥合」抉择窗 */
    public static void riftChoiceOpen(int seconds) {
        net.minecraft.client.Minecraft.getInstance().setScreen(new RiftChoiceScreen(seconds));
    }

    /** 自定义 Boss 血条（带血量数字） */
    public static void riftBossBar(float percent, int phase, boolean active, float hp, float maxHp) {
        MaixrRiftBossBar.accept(percent, phase, active, hp, maxHp);
    }

    /** 世界状态（完整度 / 纪元 / 需求物品），客户端动态天空用 */
    public static void edenSync(int completeness, int stage, boolean invasion, int wave, String requiredItem) {
        MaixrEdenClientState.accept(completeness, stage, invasion, wave, requiredItem);
    }

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
