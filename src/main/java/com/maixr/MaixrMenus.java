package com.maixr;

import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.minecraft.core.registries.BuiltInRegistries;

/** 菜单类型注册。 */
public final class MaixrMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(BuiltInRegistries.MENU, MaixrMod.MODID);

    // 【1.21.1 移植·暂缓】两个鸟巢的菜单（随鸟巢一起暂缓）。
    // 注意：1.21.1 里 IForgeMenuType 改名 IMenuTypeExtension。

    private MaixrMenus() {}

    public static void register(IEventBus bus) {
        MENUS.register(bus);
    }
}
