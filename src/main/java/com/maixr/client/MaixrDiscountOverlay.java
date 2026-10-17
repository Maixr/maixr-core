package com.maixr.client;

import com.maixr.MaixrMod;
import com.maixr.MaixrTransmutationTabletItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 在 ProjectE 的转化桌界面里，画一行「本桌卖出折率」提示。
 *
 * <h2>为什么不用聊天栏</h2>
 * <p>界面打开时聊天框被挡住，玩家根本看不到 —— 必须画在界面里。</p>
 *
 * <h2>怎么判断该不该画</h2>
 * <ol>
 *   <li>当前打开的必须是 ProjectE 的转化桌界面（按类名判断，不吃编译期依赖）</li>
 *   <li>玩家手里得拿着我们的三张桌子之一（主手或副手）</li>
 *   <li>只有 {@code sellRate < 1.0} 才画（原版桌子不显示）</li>
 * </ol>
 *
 * <p>提示画在界面顶部居中，位置随窗口尺寸自适应。</p>
 */
@EventBusSubscriber(modid = MaixrMod.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class MaixrDiscountOverlay {

    private MaixrDiscountOverlay() {
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        // ① 必须是我们关心的界面（ProjectE 转化桌）
        String screenName = event.getScreen().getClass().getName();
        if (!screenName.endsWith("GUITransmutation")) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        // ② 玩家手里拿着哪张桌子
        ItemStack tablet = findTablet(mc);
        if (tablet.isEmpty()) {
            return;
        }

        MaixrTransmutationTabletItem item = (MaixrTransmutationTabletItem) tablet.getItem();
        int percent = item.getSellPercent();
        if (percent >= 100) {
            return;
        }

        // ③ 画两行：卖出折率（金色）+ 买入原价（绿色）
        GuiGraphics g = event.getGuiGraphics();
        int cx = event.getScreen().width / 2;
        int top = 6;

        Component sell = Component.translatable(
                "screen.maixr.transmutation.sell_rate", percent);
        Component buy = Component.translatable(
                "screen.maixr.transmutation.buy_rate");

        g.drawCenteredString(mc.font,
                sell.copy().withStyle(ChatFormatting.GOLD), cx, top, 0xFFFFFF);
        g.drawCenteredString(mc.font,
                buy.copy().withStyle(ChatFormatting.GREEN), cx, top + 11, 0xFFFFFF);
    }

    /** 在双手里找我们的转化桌。 */
    private static ItemStack findTablet(Minecraft mc) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack s = mc.player.getItemInHand(hand);
            if (s.getItem() instanceof MaixrTransmutationTabletItem) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }
}
