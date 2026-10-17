package com.maixr;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * JEI 里的「鸟巢 · 多方块信息」页（参照 ECO 的多方块信息页）。
 *
 * <h3>共 5 页（JEI 左右翻页）</h3>
 * <ol>
 *   <li><b>整机说明</b>：蛋 → 机器 → 液体 的流程 + 玩法文字</li>
 *   <li><b>整机总览（3D）</b>：<b>27 格一次性全部画出来</b>的立体爆炸图 + 整机材料总表</li>
 *   <li><b>第 1 层</b>、4. <b>第 2 层</b>、5. <b>第 3 层</b>：分层立体图 + 该层材料</li>
 * </ol>
 *
 * <p>★ 本类被两套鸟巢复用，靠 {@link Blueprint} 区分：
 * <ul>
 *   <li>{@link #SEWAGE} —— 人造排污鸟巢（端口 {@code maixr:egg_sewer}，产污水/巫水）</li>
 *   <li>{@link #PINK}   —— 人造粉红鸟巢（端口 {@code maixr:pink_egg_sewer}，产粉红粘液）</li>
 * </ul>
 * 图纸唯一来源是各自的 {@code LAYERS} / {@code KINDS}，机器结构改了这里自动跟着变。
 *
 * <p>★ 3D 画法：PoseStack 旋转成等轴视角（X 30°、Y 225°），再用
 * {@code BlockRenderDispatcher.renderSingleBlock} 逐块渲染，按「先远后近」排序
 * （按 {@link #isoDepth} 升序），所以前面的方块自然压住后面的。
 */
public class EggSewerJeiCategory implements IRecipeCategory<EggSewerJeiCategory.Page> {

    // ==================================================================== 图纸描述

    /**
     * 一套鸟巢图纸。两套鸟巢共用同一套 JEI 渲染逻辑，只在这里分叉。
     *
     * @param recipeType   JEI 配方类型（每个 blueprint 一个，互不干扰）
     * @param langPrefix   语言键前缀，如 {@code jei.maixr.egg_sewer}
     * @param layers       三层图纸（每层 3 行 × 3 列）
     * @param kinds        字符 → 方块 id
     * @param portChar     图纸里表示「端口」的字符
     * @param portItem     端口物品（懒解析，避免类加载时序问题）
     * @param productItem  产物代表物（说明页里画在箭头右边）
     * @param productLabel 产物在图下方的文字
     */
    public record Blueprint(
            RecipeType<Page> recipeType,
            String langPrefix,
            String[][] layers,
            Map<Character, String> kinds,
            char portChar,
            Supplier<ItemStack> portItem,
            Supplier<ItemStack> productItem,
            String productLabel) {

        /** 材料/标题等语言键 */
        public String key(String suffix) {
            return langPrefix + "." + suffix;
        }
    }

    /** ★ 人造排污鸟巢 */
    public static final Blueprint SEWAGE = new Blueprint(
            RecipeType.create(MaixrMod.MODID, "egg_sewer_multiblock", Page.class),
            "jei.maixr.egg_sewer",
            EggSewerBlockEntity.LAYERS,
            EggSewerBlockEntity.KINDS,
            'M',
            () -> new ItemStack(MaixrMod.EGG_SEWER_ITEM.get()),
            () -> firstNonEmpty("industrialforegoing:sewage_bucket", "minecraft:water_bucket"),
            "污水 / 巫水");

    /** ★ 人造粉红鸟巢 */
    public static final Blueprint PINK = new Blueprint(
            RecipeType.create(MaixrMod.MODID, "pink_egg_sewer_multiblock", Page.class),
            "jei.maixr.pink_egg_sewer",
            PinkEggSewerBlockEntity.LAYERS,
            PinkEggSewerBlockEntity.KINDS,
            'P',
            () -> new ItemStack(MaixrMod.PINK_EGG_SEWER_ITEM.get()),
            () -> firstNonEmpty("industrialforegoing:pink_slime_bucket", "minecraft:water_bucket"),
            "粉红粘液");

    // ==================================================================== 页面类型

    /** 页面类型 */
    public enum Kind {
        /** 整机文字说明 */
        INFO,
        /** 整机 3D 总览（27 格一起画） */
        FULL,
        /** 单层 3D 预览 */
        LAYER
    }

    /**
     * 一页数据。
     *
     * @param titleKey 标题语言键
     * @param kind     页面类型
     * @param cells    仅 {@link Kind#LAYER} 用：该层 9 格（null = 端口格）
     * @param notes    底部文字行（语言键）
     */
    public record Page(String titleKey, Kind kind, String[] cells, List<String> notes) {}

    private static final int W = 156;
    private static final int H = 136;
    /** 单层预览：一个方块多少像素 */
    private static final float ISO_SCALE_LAYER = 22.0F;
    /** 整机预览：方块小一点才放得下三层 */
    private static final float ISO_SCALE_FULL = 15.0F;

    private final Blueprint bp;
    private final IDrawable background;
    private final IDrawable icon;

    public EggSewerJeiCategory(IGuiHelper helper, Blueprint bp) {
        this.bp = bp;
        this.background = helper.createBlankDrawable(W, H);
        this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, bp.portItem().get());
    }

    @Override
    public RecipeType<Page> getRecipeType() {
        return bp.recipeType();
    }

    @Override
    public Component getTitle() {
        return Component.translatable(bp.key("title"));
    }

    @Override
    public IDrawable getBackground() {
        return background;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public int getWidth() {
        return W;
    }

    @Override
    public int getHeight() {
        return H;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, Page page, IFocusGroup focuses) {
        List<ItemStack> list;
        if (page.kind() == Kind.INFO) {
            list = overviewItems();
        } else if (page.kind() == Kind.FULL) {
            list = fullMaterialList();
        } else {
            list = layerMaterialList(page);
        }
        for (int i = 0; i < list.size() && i < 8; i++) {
            builder.addSlot(RecipeIngredientRole.INPUT, 12 + i * 18, H - 24)
                    .addItemStack(list.get(i));
        }
    }

    @Override
    public void draw(Page page, IRecipeSlotsView slots, GuiGraphics g, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        g.drawString(font, Component.translatable(page.titleKey()).getString(), 4, 4, 0xFF303030, false);

        switch (page.kind()) {
            case INFO -> drawOverview(g);
            case FULL -> drawIsoFull(g);
            case LAYER -> drawIsoLayer(g, page.cells());
        }

        int y = page.kind() == Kind.INFO ? 78 : H - 40;
        for (String key : page.notes()) {
            g.drawString(font, Component.translatable(key).getString(), 4, y, 0xFF606060, false);
            y += 10;
        }
    }

    // ------------------------------------------------------------------ 3D 渲染

    private void setupIso(GuiGraphics g, float scale, float translateY) {
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(W / 2.0F, translateY, 200.0F);
        pose.scale(scale, -scale, scale);
        pose.mulPose(Axis.XP.rotationDegrees(30.0F));
        pose.mulPose(Axis.YP.rotationDegrees(225.0F));
    }

    /** 单层 3D 预览 */
    private void drawIsoLayer(GuiGraphics g, String[] cells) {
        Minecraft mc = Minecraft.getInstance();
        var dispatcher = mc.getBlockRenderer();
        var buffers = g.bufferSource();
        PoseStack pose = g.pose();

        setupIso(g, ISO_SCALE_LAYER, 52.0F);
        pose.translate(-0.5F, -0.5F, -0.5F);

        List<int[]> order = new ArrayList<>();
        for (int z = 0; z < 3; z++) {
            for (int x = 0; x < 3; x++) order.add(new int[]{x, z});
        }
        // ★ 深度公式（X 转 30°、Y 转 225° 的等轴视角下，视线方向投影到 z'' = 0.6124x - 0.6124z）：
        //   越大越靠近镜头 → 从小到大画 = 由远到近，前面的方块自然压住后面的。
        order.sort(Comparator.comparingDouble(a -> isoDepth(a[0], 0, a[1])));

        for (int[] xz : order) {
            int x = xz[0], z = xz[1];
            String id = cells[z * 3 + x];
            BlockState state = (id == null) ? portState() : blockState(id);
            if (state == null) continue;
            pose.pushPose();
            pose.translate(x - 1.0F, 0.0F, z - 1.0F);
            dispatcher.renderSingleBlock(state, pose, buffers, 0xF000F0, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        pose.popPose();
        buffers.endBatch();
    }

    /** ★ 整机 3D 总览：27 格一次性全部画出来 */
    private void drawIsoFull(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        var dispatcher = mc.getBlockRenderer();
        var buffers = g.bufferSource();
        PoseStack pose = g.pose();

        setupIso(g, ISO_SCALE_FULL, 56.0F);
        pose.translate(-0.5F, -0.5F, -0.5F);

        List<int[]> order = new ArrayList<>();
        for (int dy = 0; dy < 3; dy++) {
            for (int z = 0; z < 3; z++) {
                for (int x = 0; x < 3; x++) {
                    if (charAt(dy, x, z) == ' ') continue;
                    order.add(new int[]{x, dy, z});
                }
            }
        }
        // ★ 由远到近：用统一推导出的等轴深度（见 isoDepth），y 也参与（高处的方块更靠前）
        order.sort(Comparator.comparingDouble(a -> isoDepth(a[0], a[1], a[2])));

        for (int[] p : order) {
            int x = p[0], dy = p[1], z = p[2];
            BlockState state = stateAt(dy, x, z);
            if (state == null) continue;
            pose.pushPose();
            pose.translate(x - 1.0F, dy - 1.0F, z - 1.0F);
            dispatcher.renderSingleBlock(state, pose, buffers, 0xF000F0, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        pose.popPose();
        buffers.endBatch();
    }

    /**
     * 等轴视角下的屏幕深度（越大 = 越靠近镜头）。
     *
     * <p>推导：顶点先绕 Y 转 225°、再绕 X 转 30°，投影后化简得
     * {@code 0.6124*x - 0.6124*z + 0.5*y}。按它升序画 = 由远到近。
     */
    private static double isoDepth(double x, double y, double z) {
        return 0.6124D * x - 0.6124D * z + 0.5D * y;
    }

    private char charAt(int dy, int x, int z) {
        String[][] raw = bp.layers();
        if (dy < 0 || dy >= raw.length || z < 0 || z >= raw[dy].length) return ' ';
        String row = raw[dy][z];
        if (x < 0 || x >= row.length()) return ' ';
        return row.charAt(x);
    }

    private BlockState stateAt(int dy, int x, int z) {
        char c = charAt(dy, x, z);
        if (c == ' ') return null;
        if (c == bp.portChar()) return portState();
        String id = bp.kinds().get(c);
        return id == null ? null : blockState(id);
    }

    private BlockState portState() {
        var block = bp.portItem().get().getItem();
        if (block instanceof net.minecraft.world.item.BlockItem bi) return bi.getBlock().defaultBlockState();
        return null;
    }

    private static BlockState blockState(String id) {
        var b = BuiltInRegistries.BLOCK.getValue(new ResourceLocation(id));
        if (b == null || b == Blocks.AIR) return null;
        return b.defaultBlockState();
    }

    /** 说明页：蛋 → 机器 → 液体 */
    private void drawOverview(GuiGraphics g) {
        int row = 30;
        g.renderItem(new ItemStack(net.minecraft.world.item.Items.EGG), 12, row);
        arrow(g, 34, row + 4);
        g.renderItem(bp.portItem().get(), 52, row);
        arrow(g, 74, row + 4);
        g.renderItem(bp.productItem().get(), 92, row);
        var font = Minecraft.getInstance().font;
        g.drawString(font, "蛋", 14, row + 18, 0xFF606060, false);
        g.drawString(font, "端口", 50, row + 18, 0xFF606060, false);
        g.drawString(font, bp.productLabel(), 88, row + 18, 0xFF606060, false);
    }

    private static void arrow(GuiGraphics g, int x, int y) {
        g.fill(x, y + 3, x + 12, y + 5, 0xFF707070);
        g.fill(x + 10, y + 1, x + 12, y + 7, 0xFF707070);
        g.fill(x + 8, y + 2, x + 10, y + 6, 0xFF707070);
    }

    // ------------------------------------------------------------------ 页面数据

    /** 生成某个 blueprint 的全部页面：说明 → 整机 3D → 三层 */
    public static List<Page> pages(Blueprint bp) {
        List<Page> out = new ArrayList<>();
        out.add(new Page(bp.key("page.info"), Kind.INFO, null, List.of(
                bp.key("info.1"), bp.key("info.2"), bp.key("info.3"),
                bp.key("info.4"), bp.key("info.5"))));
        out.add(new Page(bp.key("page.full"), Kind.FULL, null, List.of(bp.key("hint"))));

        String[] titleKeys = {bp.key("layer.bottom"), bp.key("layer.middle"), bp.key("layer.top")};
        String[][] raw = bp.layers();
        for (int i = 0; i < raw.length && i < titleKeys.length; i++) {
            String[] flat = new String[9];
            for (int z = 0; z < 3; z++) {
                for (int x = 0; x < 3; x++) {
                    char c = raw[i][z].charAt(x);
                    flat[z * 3 + x] = (c == bp.portChar()) ? null : bp.kinds().get(c);
                }
            }
            out.add(new Page(titleKeys[i], Kind.LAYER, flat, List.of(bp.key("hint"))));
        }
        return out;
    }

    // ------------------------------------------------------------------ 材料清单

    private List<ItemStack> overviewItems() {
        List<ItemStack> out = new ArrayList<>();
        out.add(new ItemStack(net.minecraft.world.item.Items.EGG));
        ItemStack exotic = itemOf("exoticbirds:flamingo_egg");
        if (!exotic.isEmpty()) out.add(exotic);
        ItemStack prod = bp.productItem().get();
        if (!prod.isEmpty()) out.add(prod);
        return out;
    }

    /** 整机材料总表（27 格合计） */
    private List<ItemStack> fullMaterialList() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        String[][] raw = bp.layers();
        for (int dy = 0; dy < raw.length; dy++) {
            for (int z = 0; z < 3; z++) {
                for (int x = 0; x < 3; x++) {
                    char c = charAt(dy, x, z);
                    if (c == ' ') continue;
                    counts.merge(c == bp.portChar() ? "@port" : bp.kinds().get(c), 1, Integer::sum);
                }
            }
        }
        return toStacks(counts);
    }

    private List<ItemStack> layerMaterialList(Page page) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String id : page.cells()) {
            counts.merge(id == null ? "@port" : id, 1, Integer::sum);
        }
        return toStacks(counts);
    }

    private List<ItemStack> toStacks(Map<String, Integer> counts) {
        List<ItemStack> out = new ArrayList<>();
        counts.forEach((id, n) -> {
            if ("@port".equals(id)) {
                ItemStack port = bp.portItem().get();
                if (!port.isEmpty()) out.add(new ItemStack(port.getItem(), n));
            } else if (id != null) {
                var b = BuiltInRegistries.BLOCK.getValue(new ResourceLocation(id));
                if (b != null && b != Blocks.AIR) out.add(new ItemStack(b, n));
            }
        });
        return out;
    }

    private static ItemStack itemOf(String id) {
        var item = BuiltInRegistries.ITEM.getValue(new ResourceLocation(id));
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    /** 按顺序取第一个存在的物品（用于「有就用真货、没有就用兜底」） */
    private static ItemStack firstNonEmpty(String... ids) {
        for (String id : ids) {
            ItemStack s = itemOf(id);
            if (!s.isEmpty()) return s;
        }
        return ItemStack.EMPTY;
    }
}
