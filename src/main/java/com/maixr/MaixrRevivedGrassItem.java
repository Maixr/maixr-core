package com.maixr;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

/** 复苏草方块物品：客户端通过 initializeClient 挂上自定义渲染器（3D 草方块 + 九色卫星拖尾环绕）。 */
public class MaixrRevivedGrassItem extends Item {
    public MaixrRevivedGrassItem(Properties properties) {
        super(properties);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private RevivedGrassItemRenderer renderer;

            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new RevivedGrassItemRenderer();
                }
                return renderer;
            }
        });
    }
}
