package com.maixr;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 给三件「有故事的东西」加 JEI 信息页（介绍 + 小故事）。
 *
 * <p>文案全部放在语言文件里，按 {@code maixr.jei.<物品id>.1 / .2 / ...} 的顺序逐条读取，
 * 遇到语言文件里没有的 key 就停止。所以想加长小故事，只要往 lang 里继续编号即可。
 */
@JeiPlugin
public class MaixrJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "jei_plugin");
    private static final int MAX_LINES = 24;

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    // 【1.21.1 移植·暂缓】两套鸟巢的 JEI 多方块信息类目（三层图纸页）。
    // 随鸟巢一起暂缓；原代码见 _deferred_nest/EggSewerJeiCategory.java。
    //
    // @Override
    // public void registerCategories(IRecipeCategoryRegistration registration) { ... }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        addInfo(registration, "soul_gem", MaixrMod.SOUL_GEM.get());
        addInfo(registration, "sealed_ta", MaixrMod.SEALED_TA.get());
        addInfo(registration, "primordial_betrayal", MaixrMod.PRIMORDIAL_BETRAYAL.get());
    }

    private static void addInfo(IRecipeRegistration registration, String id, Item item) {
        List<Component> lines = new ArrayList<>();
        for (int i = 1; i <= MAX_LINES; i++) {
            String key = "maixr.jei." + id + "." + i;
            Component line = Component.translatable(key);
            if (line.getString().equals(key)) {
                break;   // 语言文件里没有这一条 -> 结束
            }
            lines.add(line);
        }
        if (!lines.isEmpty()) {
            registration.addIngredientInfo(new ItemStack(item), VanillaTypes.ITEM_STACK,
                    lines.toArray(new Component[0]));
        }
    }
}
