package com.maixr;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 带「简介」的物品：tooltip 由语言文件驱动。
 *
 * <ul>
 *   <li>{@code item.maixr.<id>.tooltip} —— 一句话简介（可选）</li>
 *   <li>{@code maixr.lore.<id>.1 / .2 / ...} —— 小故事逐行，遇到语言里没有的编号就停</li>
 * </ul>
 */
public class MaixrLoreItem extends Item {
    private static final int MAX_LINES = 24;

    private final String loreId;

    public MaixrLoreItem(Properties properties, String loreId) {
        super(properties);
        this.loreId = loreId;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext level, List<Component> tooltip, TooltipFlag flag) {
        String titleKey = "item.maixr." + loreId + ".tooltip";
        Component title = Component.translatable(titleKey);
        if (!title.getString().equals(titleKey)) {
            tooltip.add(title.copy().withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
        for (int i = 1; i <= MAX_LINES; i++) {
            String key = "maixr.lore." + loreId + "." + i;
            Component line = Component.translatable(key);
            if (line.getString().equals(key)) break;
            tooltip.add(line.copy().withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
    }
}
