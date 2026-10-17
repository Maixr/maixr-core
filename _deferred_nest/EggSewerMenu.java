package com.maixr;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 「人造鸟巢端口」的容器菜单：9 个蛋槽 + 玩家背包。
 *
 * <p>模式切换（污水 / 巫水）用原版的按钮通道：客户端 Screen 调
 * {@code handleInventoryButtonClick(containerId, 0)}，服务端在 {@link #clickMenuButton} 里切模式 ——
 * 不需要自定义网络包。
 */
public class EggSewerMenu extends AbstractContainerMenu {
    /** 按钮 id：切换排出液体 */
    public static final int BUTTON_CYCLE_FLUID = 0;

    private final EggSewerBlockEntity port;
    private final ContainerData data;

    /** 客户端构造（数据由服务端同步过来） */
    public EggSewerMenu(int id, Inventory playerInv) {
        this(id, playerInv, null, new SimpleContainerData(8));
    }

    public EggSewerMenu(int id, Inventory playerInv, EggSewerBlockEntity port, ContainerData data) {
        super(MaixrMenus.EGG_SEWER.get(), id);
        this.port = port;
        this.data = data;

        IItemHandler inv = port != null ? port.inventory() : new net.minecraftforge.items.ItemStackHandler(EggSewerBlockEntity.SLOTS);
        // 9 个蛋槽（3×3），放在 UI 左侧
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                addSlot(new SlotItemHandler(inv, col + row * 3, 26 + col * 18, 17 + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return EggSewerBlockEntity.isEgg(stack);
                    }
                });
            }
        }
        // 玩家背包（标准原版位置）
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 142));
        }
        addDataSlots(data);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == BUTTON_CYCLE_FLUID && port != null) {
            port.setMode(port.mode() + 1);
            return true;
        }
        return false;
    }

    public ContainerData data() {
        return data;
    }

    public EggSewerBlockEntity blockEntity() {
        return port;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack copy = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            copy = stack.copy();
            if (index < 9) {
                if (!moveItemStackTo(stack, 9, slots.size(), true)) return ItemStack.EMPTY;
            } else {
                if (!moveItemStackTo(stack, 0, 9, false)) return ItemStack.EMPTY;
            }
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
