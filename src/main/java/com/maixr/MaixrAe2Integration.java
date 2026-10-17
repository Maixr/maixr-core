package com.maixr;

import appeng.api.AECapabilities;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * 把两个玩偶方块实体接进 AE2 的网络能力体系。
 *
 * <h2>★ 为什么必须自己注册（少这一步线缆永远连不上）</h2>
 *
 * <p>AE2 的 {@code InitCapabilityProviders.register} 只遍历<b>它自己</b>
 * （{@code AEBlockEntities.getImplementorsOf(IInWorldGridNodeHost.class)}）
 * 的方块实体类型来注册 {@code AECapabilities.IN_WORLD_GRID_NODE_HOST}。
 * 我们模组里的类型不在那张表里 —— 哪怕类实现了
 * {@code IGridConnectedBlockEntity}，线缆在旁边也拿不到节点宿主能力，
 * 表现为「方块贴上线缆但完全没反应、网络里看不到它」。</p>
 *
 * <p>所以这里必须显式给 {@code SKIN_DOLL} / {@code TRUTH_DOLL} 两个
 * 方块实体类型注册该能力。</p>
 */
public final class MaixrAe2Integration {

    private MaixrAe2Integration() {
    }

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // 两个玩偶都实现了 IGridConnectedBlockEntity（经 AENetworkedBlockEntity），可直接当宿主返回
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST,
                MaixrBlockEntities.SKIN_DOLL.get(), (blockEntity, context) -> blockEntity);
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST,
                MaixrBlockEntities.TRUTH_DOLL.get(), (blockEntity, context) -> blockEntity);
    }
}
