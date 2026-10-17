package com.maixr;

import net.minecraft.core.NonNullList;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 消耗版"创造猪咪"复制配方：
 * 合成台内 1 只创造猪咪 + 1 件任意物品 → 将该物品复制为一整组（64 个）。
 * 与 ae2lt 原版不同：合成后猪咪会被消耗（默认 remainingItems 全为空）。
 */
public class MaixrPigmeeDuplicationRecipe extends CustomRecipe {
    /** 创造猪咪（被消耗的那只）。 */
    public static final String PIGMEE_ID = "ae2lt:creative_pigmee_fumo";

    /** 超维猪咪：也能复制，因此同样禁止"把装着它的容器当复制目标"。 */
    public static final String HYPERDIMENSIONAL_PIGMEE_ID = "ae2lt:hyperdimensional_pigmee_fumo";

    /** 普通猪咪：它自己就是复制链的产物，一并拦掉。 */
    public static final String PLAIN_PIGMEE_ID = "ae2lt:pigmee_fumo";

    public static final RecipeSerializer<MaixrPigmeeDuplicationRecipe> SERIALIZER =
            new SimpleCraftingRecipeSerializer<>(MaixrPigmeeDuplicationRecipe::new);

    /**
     * 【1.21 移植】构造签名变了：
     * 1.20.1 是 {@code CustomRecipe(ResourceLocation id, CraftingBookCategory category)}，
     * 1.21 去掉了 id 参数（配方 id 不再存在于配方对象里）。
     * 对应的 {@code SimpleCraftingRecipeSerializer} 工厂也变成只收 category。
     */
    public MaixrPigmeeDuplicationRecipe(CraftingBookCategory category) {
        super(category);
    }

    private static net.minecraft.world.item.Item findPigmee() {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.parse(PIGMEE_ID));
    }

    /** 恰好一只创造猪咪 + 一件"目标"物品（且目标不是猪咪）。 */
    /**
     * 【1.21 移植】参数类型从 {@code CraftingContainer} 换成了 {@code CraftingInput}。
     * 1.21 把合成输入统一成不可变的 {@link CraftingInput} 记录，
     * 它同时带 {@code width()} / {@code height()}，所以 {@code size()} / {@code getItem(i)} 依然可用。
     */
    private static ItemStack findTarget(CraftingInput container) {
        net.minecraft.world.item.Item pigmee = findPigmee();
        ItemStack target = ItemStack.EMPTY;
        boolean pigmeeFound = false;
        int slots = container.size();
        for (int i = 0; i < slots; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;
            if (pigmee != null && stack.is(pigmee)) {
                if (pigmeeFound) return ItemStack.EMPTY;
                pigmeeFound = true;
                continue;
            }
            if (!target.isEmpty()) return ItemStack.EMPTY;
            target = stack;
        }
        return pigmeeFound && !target.isEmpty() ? target : ItemStack.EMPTY;
    }

    @Override
    public boolean matches(CraftingInput container, Level level) {
        return !findTarget(container).isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingInput container, HolderLookup.Provider access) {
        ItemStack target = findTarget(container);
        if (target.isEmpty()) return ItemStack.EMPTY;

        // 【机制保护 1】不能复制「里面装着创造猪咪的物品」。
        //
        // 漏洞：容器类物品（箱子 / 背包 / AE2 磁盘 / 抽屉 / Mek 箱柜…）
        // 的内容全在 NBT 里。而本配方用 copyWithCount(64) 只改数量、原样保留 NBT，
        // 于是：
        //     猪咪 + 装有猪咪的箱子  →  64 个装有猪咪的箱子  →  拆出 64 只猪咪
        // 无限猪咪，机制直接崩坏。
        //
        // 修法说明：Forge 1.20.1 没有"禁止某物品进入容器"的通用钩子
        // （Item#canFitInsideContainerItems 全包只有精致背包/存储两个模组遵守，
        //  AE2 磁盘、Functional Storage 抽屉、Mekanism 箱柜都不看它）。
        // 所以这里从「复制」这一环堵死 —— 不管容器怎么存数据，
        // 只要扫描物品 NBT 能发现猪咪的 id，就拒绝复制。
        if (containsPigmeeInNbt(target)) {
            return ItemStack.EMPTY;
        }

        // 【机制保护 2】三张带耐久的转化桌不能被 64 倍复制 ——
        // 否则「4 次 / 10 次 / 32 次」的消耗设定会瞬间失去意义。
        // 改为产出 2 个「奥术转化余质」（转化桌与猪咪一同消耗）。
        // 注：不看剩余耐久，一律给 2 个。
        if (target.getItem() instanceof MaixrTransmutationTabletItem) {
            return new ItemStack(MaixrMod.ARCANE_TRANSMUTATION_RESIDUE.get(), 2);
        }

        return target.copyWithCount(64);
    }

    /**
     * 判断这件物品的 NBT 里是否藏着「创造猪咪」。
     *
     * <p>做法是把整棵 NBT 序列化成字符串再查找物品 id —— 简单粗暴但<b>对所有模组都有效</b>：
     * 不管容器把内容放在 {@code Items} / {@code inventory} / {@code BlockEntityTag}
     * 还是自定义的键里，只要它存在于 NBT 文本中就一定能被找到。</p>
     *
     * <p>顺带也拦住了「超维猪咪」{@code ae2lt:hyperdimensional_pigmee_fumo}
     * 与普通猪咪 {@code ae2lt:pigmee_fumo} —— 凡是能被复制出来变成更多猪咪的，
     * 一律不允许作为复制目标。</p>
     */
    private static boolean containsPigmeeInNbt(ItemStack stack) {
        net.minecraft.nbt.CompoundTag tag = NbtCompat.get(stack);
        if (tag == null || tag.isEmpty()) {
            return false;
        }
        String nbt = tag.toString();
        return nbt.contains(PIGMEE_ID)
                || nbt.contains(HYPERDIMENSIONAL_PIGMEE_ID)
                || nbt.contains(PLAIN_PIGMEE_ID);
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return SERIALIZER;
    }
}
