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
 * 「人造粉红鸟巢端口」的容器菜单：9 个蛋槽 + 玩家背包。
 *
 * <p>与 {@link EggSewerMenu} 的差别：粉红鸟巢只有一种产物（粉红粘液），
 * 所以<b>没有模式切换按钮</b>，{@code clickMenuButton} 直接返回 false。
 */
public class PinkEggSewerMenu extends AbstractContainerMenu {

    private final PinkEggSewerBlockEntity port;
    private final ContainerData data;

    /** 客户端构造（数据由服务端同步过来） */
    public PinkEggSewerMenu(int id, Inventory playerInv) {
        this(id, playerInv, null, new SimpleContainerData(8));
    }

    public PinkEggSewerMenu(int id, Inventory playerInv, PinkEggSewerBlockEntity port, ContainerData data) {
        super(MaixrMenus.PINK_EGG_SEWER.get(), id);
        this.port = port;
        this.data = data;

        IItemHandler inv = port != null ? port.inventory() : new net.minecraftforge.items.ItemStackHandler(PinkEggSewerBlockEntity.SLOTS);
        // 9 个蛋槽（3×3），放在 UI 左侧
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                addSlot(new SlotItemHandler(inv, col + row * 3, 26 + col * 18, 17 + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return PinkEggSewerBlockEntity.isEgg(stack);
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

    public ContainerData data() {
        return data;
    }

    public PinkEggSewerBlockEntity blockEntity() {
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
