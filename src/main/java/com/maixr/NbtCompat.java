package com.maixr;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * 1.21 的 NBT 访问兼容层。
 *
 * <p><b>为什么要这个类：</b>Minecraft 1.20.5 起，{@code ItemStack} 的
 * {@code getTag()} / {@code hasTag()} / {@code getOrCreateTag()} 被<b>彻底移除</b>，
 * 改用「数据组件」（Data Components）体系。
 * 物品的自定义 NBT 现在统一放在 {@link DataComponents#CUSTOM_DATA} 组件里，
 * 值类型是 {@link CustomData}（内部包一个 {@link CompoundTag}）。</p>
 *
 * <table border="1">
 *   <tr><th>1.20.1 写法</th><th>1.21.1 写法</th></tr>
 *   <tr><td>{@code stack.getTag()}</td><td>{@code NbtCompat.get(stack)}</td></tr>
 *   <tr><td>{@code stack.hasTag()}</td><td>{@code NbtCompat.has(stack)}</td></tr>
 *   <tr><td>{@code stack.getOrCreateTag()}</td><td>{@code NbtCompat.getOrCreate(stack)}</td></tr>
 * </table>
 *
 * <p><b>注意：</b>{@code getOrCreate} 返回的是内部 {@code CompoundTag} 的<b>副本引用</b>，
 * 直接改它<b>不会</b>写回物品。要写回必须调用 {@code NbtCompat.set(stack, tag)}。
 * 这一点和 1.20.1 的行为不同，是本移植里最容易踩的坑。</p>
 */
public final class NbtCompat {

    /** 取自定义 NBT；没有就返回一个空 CompoundTag（不写回物品）。 */
    public static CompoundTag get(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    /** 是否带自定义 NBT。 */
    public static boolean has(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && !data.isEmpty();
    }

    /**
     * 取自定义 NBT 用于修改。
     *
     * <p><b>改完必须调 {@link #set(ItemStack, CompoundTag)} 写回！</b>
     * 因为 {@code CustomData} 是不可变的值对象。</p>
     */
    public static CompoundTag getOrCreate(ItemStack stack) {
        return get(stack);
    }

    /** 把修改后的 NBT 写回物品。 */
    public static void set(ItemStack stack, CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    /**
     * 取（或创建）一个子 CompoundTag，用于往物品 NBT 里塞结构。
     *
     * <p>对应 1.20.1 的 {@code stack.getOrCreateTagElement(name)}。
     * 返回的是副本 —— 改完要 {@link #set(ItemStack, CompoundTag)} 写回。</p>
     */
    public static CompoundTag getOrCreateSub(ItemStack stack, String name) {
        CompoundTag root = get(stack);
        CompoundTag sub = root.getCompound(name);
        root.put(name, sub);
        set(stack, root);
        return sub;
    }

    private NbtCompat() {}
}
