package com.maixr.client;

import com.maixr.PinkEggSewerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 「人造粉红鸟巢端口」的 UI —— <b>自绘原版风格</b>（粉色主题）。
 *
 * <p>版面与 {@link EggSewerScreen 人造鸟巢端口} 一致（176×166），只画四样东西：
 * <b>3×3 蛋槽 / 进度箭头 / 产物流体槽</b>。与排污鸟巢的差别：
 * <ul>
 *   <li><b>没有模式切换按钮</b>（粉红粘液是唯一产物）</li>
 *   <li>箭头与液槽用粉色系配色</li>
 * </ul>
 * 数值信息全部收进悬浮提示，版面保持干净。
 */
public class PinkEggSewerScreen extends AbstractContainerScreen<PinkEggSewerMenu> {

    // ---------------- 版面常量（与原版槽位网格对齐）----------------
    private static final int EGG_X = 26;
    private static final int EGG_Y = 17;
    private static final int ARROW_X = 88;
    private static final int ARROW_Y = 33;
    private static final int ARROW_W = 22;
    private static final int ARROW_H = 16;
    private static final int TANK_X = 146;
    private static final int TANK_Y = 17;
    private static final int TANK_W = 16;
    private static final int TANK_H = 52;

    // ---------------- 原版配色 ----------------
    private static final int C_BORDER = 0xFF373737;
    private static final int C_PANEL  = 0xFFC6C6C6;
    private static final int C_HILITE = 0xFFFFFFFF;
    private static final int C_SHADOW = 0xFF555555;
    private static final int C_SLOT   = 0xFF8B8B8B;
    private static final int C_TEXT   = 0xFF404040;
    private static final int C_GOOD   = 0xFF2E7D32;
    private static final int C_BAD    = 0xFFA02020;
    /** 粉色系强调色（进度箭头 / 液面高光） */
    private static final int C_PINK   = 0xFFFF77BB;

    public PinkEggSewerScreen(PinkEggSewerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = 72;
    }

    // ================= 背景（全部自绘）=================
    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos, y = this.topPos;

        // --- 面板 + 原版斜面 ---
        g.fill(x, y, x + imageWidth, y + imageHeight, C_BORDER);
        g.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, C_PANEL);
        g.fill(x + 1, y + 1, x + imageWidth - 1, y + 2, C_HILITE);
        g.fill(x + 1, y + 1, x + 2, y + imageHeight - 1, C_HILITE);
        g.fill(x + 1, y + imageHeight - 2, x + imageWidth - 1, y + imageHeight - 1, C_SHADOW);
        g.fill(x + imageWidth - 2, y + 1, x + imageWidth - 1, y + imageHeight - 1, C_SHADOW);

        // --- 3×3 蛋槽 ---
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                drawSlot(g, x + EGG_X + c * 18 - 1, y + EGG_Y + r * 18 - 1);
            }
        }
        // --- 玩家背包 3×9 + 快捷栏 ---
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                drawSlot(g, x + 8 + c * 18 - 1, y + 84 + r * 18 - 1);
            }
        }
        for (int c = 0; c < 9; c++) {
            drawSlot(g, x + 8 + c * 18 - 1, y + 142 - 1);
        }

        // --- 进度箭头 ---
        drawArrow(g, x + ARROW_X, y + ARROW_Y);
        // --- 产物流体槽 ---
        drawTank(g, x + TANK_X, y + TANK_Y);
    }

    /** 原版槽位样式：18×18，左上深边、右下亮边、内底灰 */
    private void drawSlot(GuiGraphics g, int sx, int sy) {
        g.fill(sx, sy, sx + 18, sy + 18, C_SLOT);
        g.fill(sx, sy, sx + 18, sy + 1, C_BORDER);
        g.fill(sx, sy, sx + 1, sy + 18, C_BORDER);
        g.fill(sx, sy + 17, sx + 18, sy + 18, C_HILITE);
        g.fill(sx + 17, sy, sx + 18, sy + 18, C_HILITE);
    }

    /** 右向箭头：箭杆按"电力充能比例"填充（粉色），箭头发光表示正在产出 */
    private void drawArrow(GuiGraphics g, int ax, int ay) {
        int kfe = menu.data().get(6);
        int kfeMax = Math.max(1, menu.data().get(7));
        float frac = Math.max(0f, Math.min(1f, (float) kfe / kfeMax));
        boolean producing = menu.data().get(2) == 1 && menu.data().get(4) > 0;

        int shaftY0 = ay + 5, shaftY1 = ay + 11;
        int shaftW = 14;
        g.fill(ax, shaftY0, ax + shaftW, shaftY1, C_SHADOW);
        g.fill(ax, shaftY0, ax + 1, shaftY1, C_BORDER);
        int fillW = (int) (shaftW * frac);
        if (fillW > 0) {
            g.fill(ax, shaftY0, ax + fillW, shaftY1, producing ? C_PINK : C_HILITE);
        }
        int head = producing ? C_PINK : C_HILITE;
        for (int i = 0; i <= 8; i++) {
            int half = 8 - i;
            g.fill(ax + 14 + i, ay + 8 - half, ax + 15 + i, ay + 8 + half + 1, head);
        }
        g.fill(ax + 14, ay + 8, ax + 15, ay + 9, C_BORDER);
    }

    /** 产物流体槽：深凹槽 + 粉色液面 */
    private void drawTank(GuiGraphics g, int tx, int ty) {
        g.fill(tx - 1, ty - 1, tx + TANK_W + 1, ty + TANK_H + 1, C_BORDER);
        g.fill(tx, ty, tx + TANK_W, ty + TANK_H, C_SLOT);

        int amount = menu.data().get(0);
        int capacity = Math.max(1, menu.data().get(1));
        int fill = (int) ((long) TANK_H * Math.min(amount, capacity) / capacity);
        if (fill > 0) {
            // 粉红粘液的代表色（粉紫）
            g.fill(tx, ty + TANK_H - fill, tx + TANK_W, ty + TANK_H, 0xFFE86FB4);
            g.fill(tx, ty + TANK_H - fill, tx + TANK_W, ty + TANK_H - fill + 1, 0x60FFFFFF);
        }
    }

    // ================= 文字 =================
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, C_TEXT, false);

        // 结构状态：右对齐在标题同一行
        boolean formed = menu.data().get(2) == 1;
        Component status = Component.translatable(formed ? "gui.maixr.egg_sewer.formed"
                : "gui.maixr.egg_sewer.not_formed");
        int sw = this.font.width(status);
        g.drawString(this.font, status, this.imageWidth - 8 - sw, 6, formed ? C_GOOD : C_BAD, false);

        g.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, C_TEXT, false);
    }

    // ================= 悬浮提示 =================
    @Override
    protected void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        super.renderTooltip(g, mouseX, mouseY);

        if (inBox(mouseX, mouseY, EGG_X, EGG_Y, 3 * 18, 3 * 18)) {
            g.renderTooltip(this.font, java.util.List.of(
                    Component.translatable("gui.maixr.egg_sewer.eggs", menu.data().get(3)),
                    Component.translatable("gui.maixr.egg_sewer.rate", menu.data().get(4))),
                    java.util.Optional.empty(), mouseX, mouseY);
            return;
        }
        if (inBox(mouseX, mouseY, ARROW_X, ARROW_Y, ARROW_W, ARROW_H)) {
            g.renderTooltip(this.font, Component.translatable("gui.maixr.egg_sewer.energy",
                    menu.data().get(6), menu.data().get(7)), mouseX, mouseY);
            return;
        }
        if (inBox(mouseX, mouseY, TANK_X, TANK_Y, TANK_W, TANK_H)) {
            g.renderTooltip(this.font, Component.translatable("gui.maixr.egg_sewer.tank_tip",
                    menu.data().get(0), menu.data().get(1)), mouseX, mouseY);
        }
    }

    private boolean inBox(int mx, int my, int bx, int by, int bw, int bh) {
        int x = this.leftPos + bx, y = this.topPos + by;
        return mx >= x && mx < x + bw && my >= y && my < y + bh;
    }
}
