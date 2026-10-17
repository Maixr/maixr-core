package com.maixr.client;

import com.maixr.EggSewerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 「人造排污鸟巢端口」的 UI —— <b>自绘原版风格</b>。
 *
 * <p>不套用任何原版容器贴图（那会画出多余的 9 列空网格），全部用原版配色自绘：
 * 只画该有的四样东西 —— <b>3×3 蛋槽 / 进度箭头 / 产物流体槽 / 切换模式按钮</b>，
 * 外加标准玩家背包。数值信息全部收进悬浮提示，版面保持干净。
 *
 * <p>版面（176×166，与原版容器同尺寸）：
 * <pre>
 *   [标题]                    [结构成立]
 *   ┌───┐
 *   │蛋蛋蛋│      ▶      ║液║
 *   │蛋蛋蛋│   [污水]     ║体║
 *   │蛋蛋蛋│             ╚══╝
 *   ────────── 物品栏 ──────────
 * </pre>
 */
public class EggSewerScreen extends AbstractContainerScreen<EggSewerMenu> {

    // ---------------- 版面常量 ----------------
    /** 蛋槽 3×3（与原版槽位网格对齐：26,44,62 / 17,35,53） */
    private static final int EGG_X = 26;
    private static final int EGG_Y = 17;
    /** 进度箭头 22×16 */
    private static final int ARROW_X = 88;
    private static final int ARROW_Y = 33;
    private static final int ARROW_W = 22;
    private static final int ARROW_H = 16;
    /** 产物流体槽 16×52 */
    private static final int TANK_X = 146;
    private static final int TANK_Y = 17;
    private static final int TANK_W = 16;
    private static final int TANK_H = 52;
    /** 模式切换按钮 */
    private static final int BTN_X = 88;
    private static final int BTN_Y = 54;
    private static final int BTN_W = 52;
    private static final int BTN_H = 16;

    // ---------------- 原版配色 ----------------
    private static final int C_BORDER = 0xFF373737;   // 外框/阴影
    private static final int C_PANEL  = 0xFFC6C6C6;   // 面板底色
    private static final int C_HILITE = 0xFFFFFFFF;   // 斜面高光
    private static final int C_SHADOW = 0xFF555555;   // 斜面暗边
    private static final int C_SLOT   = 0xFF8B8B8B;   // 槽位内底
    private static final int C_TEXT   = 0xFF404040;   // 正文
    private static final int C_GOOD   = 0xFF2E7D32;   // 结构成立
    private static final int C_BAD    = 0xFFA02020;   // 结构不完整

    private Button modeButton;
    private int lastMode = -1;

    public EggSewerScreen(EggSewerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = 72;
    }

    @Override
    protected void init() {
        super.init();
        this.modeButton = Button.builder(fluidLabel(), b -> {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId,
                        EggSewerMenu.BUTTON_CYCLE_FLUID);
            }
        }).bounds(this.leftPos + BTN_X, this.topPos + BTN_Y, BTN_W, BTN_H).build();
        this.addRenderableWidget(this.modeButton);
        refreshButtonTip();
    }

    /** 按钮只显示液体名（短），完整说明放悬浮提示里 */
    private Component fluidLabel() {
        return Component.literal(menu.data().get(5) == 1 ? "巫水" : "污水");
    }

    private void refreshButtonTip() {
        if (modeButton != null) {
            modeButton.setMessage(fluidLabel());
            modeButton.setTooltip(Tooltip.create(
                    Component.translatable("gui.maixr.egg_sewer.mode", fluidLabel())));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int mode = menu.data().get(5);
        if (mode != lastMode) {
            lastMode = mode;
            refreshButtonTip();
        }
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
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

    /** 右向箭头：箭杆按"电力充能比例"填充，箭头发光表示正在产出 */
    private void drawArrow(GuiGraphics g, int ax, int ay) {
        int kfe = menu.data().get(6);
        int kfeMax = Math.max(1, menu.data().get(7));
        float frac = Math.max(0f, Math.min(1f, (float) kfe / kfeMax));
        boolean producing = menu.data().get(2) == 1 && menu.data().get(4) > 0;

        int shaftY0 = ay + 5, shaftY1 = ay + 11;
        int shaftW = 14;
        // 箭杆底
        g.fill(ax, shaftY0, ax + shaftW, shaftY1, C_SHADOW);
        g.fill(ax, shaftY0, ax + 1, shaftY1, C_BORDER);
        // 箭杆进度
        int fillW = (int) (shaftW * frac);
        if (fillW > 0) {
            g.fill(ax, shaftY0, ax + fillW, shaftY1, producing ? 0xFFFFD24A : C_HILITE);
        }
        // 箭头（用逐行 fill 拼三角形）
        int head = producing ? 0xFFFFD24A : C_HILITE;
        for (int i = 0; i <= 8; i++) {
            int half = 8 - i;
            g.fill(ax + 14 + i, ay + 8 - half, ax + 15 + i, ay + 8 + half + 1, head);
        }
        // 箭头描边（底）
        g.fill(ax + 14, ay + 8, ax + 15, ay + 9, C_BORDER);
    }

    /** 产物流体槽：原版桶式深凹槽 + 液面 */
    private void drawTank(GuiGraphics g, int tx, int ty) {
        g.fill(tx - 1, ty - 1, tx + TANK_W + 1, ty + TANK_H + 1, C_BORDER);
        g.fill(tx, ty, tx + TANK_W, ty + TANK_H, C_SLOT);

        int amount = menu.data().get(0);
        int capacity = Math.max(1, menu.data().get(1));
        int fill = (int) ((long) TANK_H * Math.min(amount, capacity) / capacity);
        if (fill > 0) {
            int color = fluidColor();
            g.fill(tx, ty + TANK_H - fill, tx + TANK_W, ty + TANK_H, color);
            // 液面高光
            g.fill(tx, ty + TANK_H - fill, tx + TANK_W, ty + TANK_H - fill + 1, 0x40FFFFFF);
        }
    }

    /** 液体颜色：1.20.1 的 FluidType 没有 getColor(FluidStack)，直接用两种液体的代表色 */
    private int fluidColor() {
        return menu.data().get(5) == 1 ? 0xFF5B3A7A : 0xFF6B5A33;   // 巫水=暗紫，污水=棕黄
    }

    // ================= 文字 =================
    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 标题（左上）
        g.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, C_TEXT, false);

        // 结构状态：右对齐在标题同一行（不占额外空间、不压槽位）
        boolean formed = menu.data().get(2) == 1;
        Component status = Component.translatable(formed ? "gui.maixr.egg_sewer.formed"
                : "gui.maixr.egg_sewer.not_formed");
        int sw = this.font.width(status);
        g.drawString(this.font, status, this.imageWidth - 8 - sw, 6, formed ? C_GOOD : C_BAD, false);

        // 玩家背包标题
        g.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, C_TEXT, false);
    }

    // ================= 悬浮提示 =================
    @Override
    protected void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        super.renderTooltip(g, mouseX, mouseY);

        // 蛋槽区域 → 蛋数 / 产出速率
        if (inBox(mouseX, mouseY, EGG_X, EGG_Y, 3 * 18, 3 * 18)) {
            g.renderTooltip(this.font, java.util.List.of(
                    Component.translatable("gui.maixr.egg_sewer.eggs", menu.data().get(3)),
                    Component.translatable("gui.maixr.egg_sewer.rate", menu.data().get(4))), java.util.Optional.empty(), mouseX, mouseY);
            return;
        }
        // 箭头 → 电力
        if (inBox(mouseX, mouseY, ARROW_X, ARROW_Y, ARROW_W, ARROW_H)) {
            g.renderTooltip(this.font, Component.translatable("gui.maixr.egg_sewer.energy",
                    menu.data().get(6), menu.data().get(7)), mouseX, mouseY);
            return;
        }
        // 液体槽 → 液体量
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
