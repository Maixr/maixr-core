package com.maixr;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 【陨石凝屑】—— 虚空里凝结出来的一小块陨石碎屑。
 *
 * <p>它自己没有任何玩法，专门给「朝上的 AE2 破坏面板」当食物：
 * {@link SkyDustCondenser} 会在破坏面板上方凭空凝结出这块东西，
 * 破坏面板立刻把它打碎，掉落<b>陨石粉</b>并吸进 ME 网络。
 *
 * <p>掉落走代码而不是战利品表：战利品表里写死 {@code ae2:sky_dust} 的话，
 * 一旦玩家没装 AE2，整张表会因为引用不存在的物品而报错。代码里查注册表更稳。
 */
public class SkyCondensateBlock extends Block {
    /** 1.20.1 里陨石粉的注册名 */
    private static final ResourceLocation SKY_DUST = ResourceLocation.fromNamespaceAndPath("ae2", "sky_dust");

    public SkyCondensateBlock(Properties properties) {
        super(properties);
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> out = new ArrayList<>(1);
        Item dust = BuiltInRegistries.ITEM.get(SKY_DUST);
        if (dust != null && dust != Items.AIR) {
            out.add(new ItemStack(dust));
        }
        return out;
    }

    /**
     * 【1.21 移植】新版的 Block 家族要求实现 {@code codec()}。
     * 1.20.1 没有这个抽象方法；1.21 用 {@code simpleCodec(构造引用)} 是最简写法，
     * 效果等同于"这个方块没有额外状态需要序列化"。
     */
    public static final com.mojang.serialization.MapCodec<SkyCondensateBlock> CODEC =
            simpleCodec(SkyCondensateBlock::new);

    @Override
    protected com.mojang.serialization.MapCodec<? extends Block> codec() {
        return CODEC;
    }
}
