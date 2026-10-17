package com.maixr.client;

import com.maixr.ArcLightBoss;
import com.maixr.MaixrMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 弧光·裂隙意志的专属血条（特殊形状版）。
 *
 * <h3>形状</h3>
 * 不是原版那种方块条，而是一枚<b>两端收尖的透镜/梭形</b>：
 * 每一列的高度按"离端点多远"收缩，两端收成一个尖，整体像一道被拉长的裂隙。
 *
 * <h3>层</h3>
 * · 极光三色渐变填充 + 顶部高光 + 流动扫描线<br>
 * · 掉血时留一条暗红滞后残影慢慢追上来<br>
 * · 10% 刻度 + 33%/66% 相位分界<br>
 * · 当前血量处一颗菱形指示珠，条上方右侧显示 {@code 1234 / 4096}<br>
 * · 血量低于 33% 时整个边框随心跳脉冲发红<br>
 * · 条上方三颗相位宝珠，名字用极光逐字渐变
 *
 * <h3>★ 三个渲染入口</h3>
 * 只挂一个事件曾经导致"血条根本不显示"（某些 HUD 类模组会改/替换原版 overlay 事件）。
 * 现在同时挂：{@code RegisterGuiOverlaysEvent} 注册的自定义 overlay、{@code RenderGuiEvent.Post}、
 * {@code RenderGuiLayerEvent.Post(HOTBAR)}，用"同帧只画一次"的守卫合并，
 * 并且第一次成功绘制会写日志，方便定位到底哪条路通了。
 */
@EventBusSubscriber(modid = MaixrMod.MODID, value = Dist.CLIENT)
public final class MaixrRiftBossBar {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 16;
    /** 两端收尖的长度（像素） */
    private static final int TIP = 16;

    private static float percent = 0F;
    private static float ghost = 0F;          // 滞后残影
    private static int phase = 0;
    private static boolean active = false;
    private static float hp = 0F;             // 当前血量（条上显示 x / y）
    private static float maxHp = 0F;
    private static long lastUpdateMs = 0L;

    /** 同一帧内只画一次（三个 hook 都会触发） */
    private static long lastDrawNanos = 0L;
    /** 主入口（RenderGuiEvent.Post）最后一次绘制的时间：其他入口只在它"没动静"时才兜底 */
    private static long lastPrimaryMs = 0L;
    /** 血条的纵向位置：**必须在屏幕内留出上方文字的空间**。
     *  以前是 18，而名字画在 y-28 = -10 —— 直接画到屏幕外面去了，所以"看不见 Boss 名字"。
     *  现在 76：名字 48、宝珠/血量 62、条 76~92，全都在屏幕内，也躲开了顶部 Jade/HUD 区域。 */
    private static final int BAR_Y = 76;
    /** 诊断：每次激活只打一条日志，避免刷屏 */
    private static boolean loggedAccept = false;
    private static boolean loggedDraw = false;
    private static boolean loggedCancel = false;

    public static void accept(float pct, int ph, boolean act, float curHp, float max) {
        float clamped = Mth.clamp(pct, 0F, 1F);
        if (clamped > percent) ghost = clamped;      // 回血直接贴上去
        percent = clamped;
        phase = ph;
        hp = curHp;
        maxHp = max;
        lastUpdateMs = System.currentTimeMillis();
        if (!act) {
            active = false;
            loggedAccept = false;
            loggedDraw = false;
            return;
        }
        active = true;
        if (!loggedAccept) {
            loggedAccept = true;
            MaixrMod.LOGGER.info("[弧光] 客户端收到血条同步：{}% （{} / {}），相位 {}",
                    (int) (clamped * 100F), (int) Math.ceil(curHp), (int) max, ph);
        }
    }

    private MaixrRiftBossBar() {}

    /**
     * ★ 主入口：RenderGuiEvent.Post —— 它在一帧里**所有 HUD/overlay 之后**才触发，
     * 所以画出来的血条天然盖在 Jade 之类 mod 的 HUD 之上，不会被遮住。
     * （之前三个入口同时画、谁先谁后不确定 → 被 Jade 压住 / 闪烁，就是这个原因。）
     */
    public static void renderPrimary(GuiGraphics g) {
        lastPrimaryMs = System.currentTimeMillis();
        renderOverlay(g, "RenderGuiEvent");
    }

    /**
     * 兜底入口（注册式 overlay / 原版快捷栏之后）：
     * 只有当主入口在最近 250ms 内**一次都没画过**时才启用 —— 例如某个 HUD 模组
     * 把 RenderGuiEvent 也吞了，那至少还有一条路能把血条画出来。
     */
    public static void renderFallback(GuiGraphics g, String hook) {
        if (System.currentTimeMillis() - lastPrimaryMs < 250L) return;
        renderOverlay(g, hook);
    }

    /** 共用绘制：同帧去重 + 首次绘制打日志 */
    public static void renderOverlay(GuiGraphics g, String hook) {
        if (!active) return;
        if (System.currentTimeMillis() - lastUpdateMs > 3000L) {
            active = false;
            return;
        }
        long now = System.nanoTime();
        if (now - lastDrawNanos < 3_000_000L) return;      // 同一帧画过了
        lastDrawNanos = now;
        if (!loggedDraw) {
            loggedDraw = true;
            MaixrMod.LOGGER.info("[弧光] 自定义血条开始绘制（入口：{}）", hook);
        }
        draw(g);
    }

    /**
     * 把原版那条顶掉。
     *
     * <p>★ 这里是<b>永远</b>取消，而不是"我们这条活着的时候才取消"：
     * 服务端已经彻底不用 {@code ServerBossEvent} 了，凡是名字里带"弧光"的原版血条
     * 都属于历史残留（旧版本实体重建时留下的残影条），必须一条都不许画上去。
     */
    @SubscribeEvent
    public static void onBossBar(CustomizeGuiOverlayEvent.BossEventProgress event) {
        String name = event.getBossEvent().getName().getString();
        if (active || name.contains("弧光") || name.contains("裂隙意志")) {
            if (!loggedCancel) {
                loggedCancel = true;
                MaixrMod.LOGGER.info("[弧光] 已拦截原版血条：{}", name);
            }
            event.setCanceled(true);
        }
    }

    /** ★ 主入口：整块 HUD 渲染结束（在所有 overlay 之后 → 盖在 Jade 之上，不会被遮） */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        renderPrimary(event.getGuiGraphics());
    }

    /** 兜底入口：原版快捷栏 overlay 之后（主入口正常工作时不会重复画） */
    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiLayerEvent.Post event) {
        // 【1.21】RenderGuiLayerEvent#getName() 返回的就是 ResourceLocation（1.20.1 是 overlay 对象）
        if (!event.getName().equals(net.neoforged.neoforge.client.gui.VanillaGuiLayers.HOTBAR)) return;
        renderFallback(event.getGuiGraphics(), "RenderGuiLayerEvent(hotbar)");
    }

    // ------------------------------------------------------------------ 形状

    /** 第 i 列在梭形里上下各收进多少像素（两端收尖） */
    private static int tip(int i, int width, int halfHeight) {
        int edge = Math.min(i, width - 1 - i);
        if (edge >= TIP) return 0;
        return (TIP - edge) * halfHeight / TIP;
    }

    private static void draw(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int x = (screenW - WIDTH) / 2;
        int y = BAR_Y;
        long now = System.currentTimeMillis();
        int half = HEIGHT / 2;

        // 残影慢慢追上来
        if (ghost > percent) ghost = Math.max(percent, ghost - 0.004F);
        else ghost = percent;

        boolean critical = percent <= 0.33F;
        float pulse = critical ? (0.55F + 0.45F * Mth.sin(now / 110F)) : 1F;

        // ---- 外发光（梭形，比本体上下各多 2px）----
        int glowCol = ((int) (0x55 * pulse) << 24) | (critical ? 0xFF3030 : 0x8A6AB0);
        for (int i = 0; i < WIDTH; i++) {
            int in = Math.max(0, tip(i, WIDTH, half) - 2);
            g.fill(x + i, y + in - 2, x + i + 1, y + HEIGHT - in + 2, glowCol);
        }
        // ---- 底槽 ----
        for (int i = 0; i < WIDTH; i++) {
            int in = tip(i, WIDTH, half);
            g.fill(x + i, y + in, x + i + 1, y + HEIGHT - in, 0xFF120C1A);
        }
        // ---- 残影（暗红）----
        int ghostW = (int) (WIDTH * ghost);
        for (int i = 0; i < ghostW; i++) {
            int in = tip(i, WIDTH, half);
            g.fill(x + i, y + in, x + i + 1, y + HEIGHT - in, 0xFF5A1020);
        }
        // ---- 主填充：极光渐变（按列上色，列高跟着梭形走）----
        int filled = (int) (WIDTH * percent);
        int[] cA = colorAt(0F, phase);
        int[] cB = colorAt(1F, phase);
        for (int i = 0; i < filled; i++) {
            float t = (float) i / Math.max(1, WIDTH);
            int r = (int) (cA[0] + (cB[0] - cA[0]) * t);
            int gg = (int) (cA[1] + (cB[1] - cA[1]) * t);
            int b = (int) (cA[2] + (cB[2] - cA[2]) * t);
            int in = tip(i, WIDTH, half);
            g.fill(x + i, y + in, x + i + 1, y + HEIGHT - in, 0xFF000000 | (r << 16) | (gg << 8) | b);
            // 顶部高光 / 底部压暗
            if (HEIGHT - in - in > 3) {
                g.fill(x + i, y + in, x + i + 1, y + in + 2, 0x66FFFFFF);
                g.fill(x + i, y + HEIGHT - in - 2, x + i + 1, y + HEIGHT - in, 0x33000000);
            }
        }
        // ---- 流动扫描线 ----
        if (filled > 4) {
            int scan = x + (int) ((now / 5) % filled);
            int in = tip(scan - x, WIDTH, half);
            g.fill(scan, y + in, scan + 2, y + HEIGHT - in, 0x99FFFFFF);
        }
        // ---- 10% 刻度 + 33/66 相位分界 ----
        for (int m = 1; m < 10; m++) {
            int mx = x + WIDTH * m / 10;
            int in = tip(WIDTH * m / 10, WIDTH, half);
            boolean major = (m == 3 || m == 6);
            g.fill(mx, y + in, mx + 1, y + HEIGHT - in, major ? 0xAAFFFFFF : 0x44FFFFFF);
        }
        // ---- 当前血量菱形指示珠 ----
        if (filled > 2 && filled < WIDTH - 2) {
            int mx = x + filled;
            int in = tip(filled, WIDTH, half);
            int cy = y + HEIGHT / 2;
            int col = critical ? 0xFFFF5555 : 0xFFFFFFFF;
            g.fill(mx - 1, cy - 1, mx + 2, cy + 2, col);
            g.fill(mx - 3, cy, mx + 4, cy + 1, col);
            g.fill(mx, cy - 3, mx + 1, cy + 4, col);
        }

        // ---- 相位宝珠（条上方左侧）----
        for (int i = 0; i < 3; i++) {
            int px = x + 12 + i * 14;
            int py = y - 13;
            boolean lit = i <= phase;
            boolean current = i == phase;
            int[] c = colorAt(0.5F + i * 0.25F, phase);
            int col = lit ? (0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2]) : 0xFF3A3A4A;
            int sz = current ? (int) (4 + 1.5F * Mth.sin(now / 130F)) : 3;
            g.fill(px - sz, py - sz, px + sz, py + sz, col);
        }

        // ---- 名字（极光逐字）+ 相位名 ----
        Component name = ArcLightBoss.auroraText("弧光 · 裂隙意志");
        g.drawString(mc.font, name, x, y - 28, 0xFFFFFF, true);
        String[] phaseName = {"相位一 · 凝视", "相位二 · 撕裂", "相位三 · 坍缩"};
        String right = phaseName[Mth.clamp(phase, 0, 2)];
        g.drawString(mc.font, right, x + WIDTH - mc.font.width(right), y - 28, 0xE0C0FF, true);

        // ---- 血量数字（右侧）----
        if (maxHp > 0F) {
            String hpText = (int) Math.ceil(hp) + " / " + (int) maxHp;
            g.drawString(mc.font, hpText, x + WIDTH - mc.font.width(hpText), y - 13,
                    critical ? 0xFF9090 : 0xD8C8FF, true);
        }
        // ---- 条内居中百分比 ----
        String pctText = (int) (percent * 100) + "%";
        g.drawString(mc.font, pctText, x + (WIDTH - mc.font.width(pctText)) / 2,
                y + 4, 0xFFFFFFFF, true);
    }

    /** 相位决定基色，t 在同相位内做明暗过渡 */
    private static int[] colorAt(float t, int p) {
        return switch (p) {
            case 0 -> new int[]{(int) (0x50 + 0x40 * t), (int) (0xE0 + 0x1F * t), (int) (0xC0 + 0x30 * t)};
            case 1 -> new int[]{(int) (0xA0 + 0x50 * t), (int) (0x50 + 0x50 * t), (int) (0xE0 + 0x1F * t)};
            default -> new int[]{(int) (0xE0 + 0x1F * t), (int) (0x30 + 0x40 * t), (int) (0x70 + 0x60 * t)};
        };
    }
}
