package com.maixr.client;

import com.maixr.MaixrNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 「裂隙正在弥合」限时抉择窗。
 *
 * <p>只有 30 秒。窗口上读秒，时间越少整体越红。
 * 到点还没选 —— 服务端会让裂隙意志满血复活，并弹出一句「你辜负了弧光」。
 * Esc 无效：要么选，要么等祂回来。
 */
public class RiftChoiceScreen extends Screen {
    private static final Component TITLE = Component.literal("---裂隙正在弥合，你望着祂，期待着重生---");
    private static final String OPT_SELF = "【自我凋零填补裂隙】";
    private static final String OPT_BEAR = "【抗下诅咒填补裂隙】";

    private static final List<Component> TOOLTIP_SELF = List.of(
            Component.literal("§7把你自己的一切填进那道缝："),
            Component.literal("§c· 清空背包与快捷栏"),
            Component.literal("§c· 清空护甲与副手"),
            Component.literal("§c· 清空末影箱"),
            Component.literal("§a· 获得隐藏成就【以己身填之】"),
            Component.literal("§7——你不会失去任何永久的东西。")
    );
    private static final List<Component> TOOLTIP_BEAR = List.of(
            Component.literal("§7把裂隙担在自己身上，换取物品的保全："),
            Component.literal("§4· 永远无法入睡"),
            Component.literal("§4· 永远带有 虚弱 X"),
            Component.literal("§4· 永远带有 挖掘疲劳 III"),
            Component.literal("§4· 永远带有 缓降"),
            Component.literal("§4· 永远无法进入破碎世界维度"),
            Component.literal("§8死亡与牛奶都无法解除这些诅咒"),
            Component.literal("§a· 获得隐藏成就【以余生负之】")
    );

    private final int totalSeconds;
    private final long deadlineMs;
    private boolean resolved = false;

    private int boxW, boxH, leftX, rightX, boxY;

    public RiftChoiceScreen(int seconds) {
        super(Component.literal("裂隙"));
        this.totalSeconds = Math.max(1, seconds);
        this.deadlineMs = System.currentTimeMillis() + this.totalSeconds * 1000L;
    }

    /** 剩余秒数 */
    private int remaining() {
        long left = deadlineMs - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) Math.ceil(left / 1000.0);
    }

    /** 0 = 刚开始，1 = 即将归零 */
    private float danger() {
        float used = 1F - (float) remaining() / totalSeconds;
        return Math.max(0F, Math.min(1F, used));
    }

    @Override
    protected void init() {
        boxW = 190;
        boxH = 28;
        boxY = this.height / 2 + 10;
        leftX = this.width / 2 - boxW - 8;
        rightX = this.width / 2 + 8;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int secs = remaining();
        if (secs <= 0 && !resolved) {
            // 时间到：什么也不选，让服务端把祂叫回来
            resolved = true;
            this.onClose();
            return;
        }
        float danger = danger();

        this.renderBackground(g, 0, 0, 0F);
        // 越紧迫，整块屏幕越红
        if (danger > 0.01F) {
            int alpha = (int) (danger * 0.45F * 255) << 24;
            g.fill(0, 0, this.width, this.height, alpha | 0xFF0000);
        }

        Component title = com.maixr.ArcLightBoss.auroraText(TITLE.getString());
        int tw = this.font.width(title);
        g.drawString(this.font, title, (this.width - tw) / 2, this.height / 2 - 52, 0xFFFFFF, true);

        // 读秒：越少越红、越急
        String cd = "剩余 " + secs + " 秒";
        int cdColor = blend(0xFFE0FF, 0xFF2020, danger);
        int cdW = this.font.width(cd);
        float scale = 1.0F + danger * 0.35F;
        g.pose().pushPose();
        g.pose().translate((this.width - cdW * scale) / 2F, this.height / 2 - 30, 0);
        g.pose().scale(scale, scale, 1F);
        g.drawString(this.font, cd, 0, 0, cdColor, true);
        g.pose().popPose();

        drawOption(g, leftX, OPT_SELF, mouseX, mouseY, blend(0x66FFE0, 0xFF3030, danger), TOOLTIP_SELF);
        drawOption(g, rightX, OPT_BEAR, mouseX, mouseY, blend(0xFF99D0, 0xFF3030, danger), TOOLTIP_BEAR);
    }

    /** 两个颜色按 t 线性插值 */
    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int gg = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return (r << 16) | (gg << 8) | bl;
    }

    private void drawOption(GuiGraphics g, int x, String label, int mouseX, int mouseY,
                            int accent, List<Component> tooltip) {
        boolean hover = mouseX >= x && mouseX <= x + boxW && mouseY >= boxY && mouseY <= boxY + boxH;
        g.fill(x - 1, boxY - 1, x + boxW + 1, boxY + boxH + 1, hover ? accent : 0xFF3A3A4A);
        g.fill(x, boxY, x + boxW, boxY + boxH, hover ? 0xE0202030 : 0xC0101018);
        int color = hover ? 0xFFFFFF : accent;
        g.drawString(this.font, label, x + (boxW - this.font.width(label)) / 2, boxY + 10, color, true);

        if (hover) {
            int ty = boxY + boxH + 8;
            int maxW = 0;
            for (Component c : tooltip) maxW = Math.max(maxW, this.font.width(c));
            int tx = Math.max(4, Math.min(this.width - maxW - 8, x + boxW / 2 - maxW / 2));
            g.fill(tx - 4, ty - 4, tx + maxW + 4, ty + tooltip.size() * 10 + 4, 0xF0101018);
            for (Component c : tooltip) {
                g.drawString(this.font, c, tx, ty, 0xFFFFFF, false);
                ty += 10;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && remaining() > 0) {
            if (mx >= leftX && mx <= leftX + boxW && my >= boxY && my <= boxY + boxH) {
                resolved = true;
                MaixrNetwork.sendRiftChoiceResult(0);
                this.onClose();
                return true;
            }
            if (mx >= rightX && mx <= rightX + boxW && my >= boxY && my <= boxY + boxH) {
                resolved = true;
                MaixrNetwork.sendRiftChoiceResult(1);
                this.onClose();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return true;      // 吞掉所有按键（含 Esc），逼玩家做选择
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
