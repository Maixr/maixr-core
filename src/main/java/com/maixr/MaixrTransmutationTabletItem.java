package com.maixr;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带耐久的「转化桌」——右键打开 ProjectE 的转化桌界面，每次使用消耗 1 点耐久。
 *
 * <p>耐久耗尽时物品消失，并在原地留下一颗「奥术转化余质」。</p>
 *
 * <h2>为什么用反射</h2>
 * <p>本项目不把 ProjectE 作为编译期依赖，因此通过反射取得
 * {@code moze_intel.projecte.gameObjs.container.TransmutationContainer}。
 * 好处：ProjectE 缺失时本物品不会导致游戏崩溃，只会在右键时报一条警告并原地返回。</p>
 *
 * <h2>为什么能复用原版界面</h2>
 * <p>{@code TransmutationContainer} 的构造函数会把 ProjectE 自己注册的
 * {@code PEContainerTypes.TRANSMUTATION_CONTAINER} 交给父类，
 * 因此 {@code menu.getType()} 返回的仍是 ProjectE 的菜单类型；
 * 客户端随后用该类型的 {@code fromNetwork} 重建界面——
 * 只要我们在缓冲区里按同样顺序写入
 * {@code boolean(true) / InteractionHand / byte(快捷栏槽位)} 即可。</p>
 *
 * <h2>耐久何时扣</h2>
 * <p>不是「打开就扣」，而是「关闭界面时扣」，见
 * {@link MaixrTabletDurabilityHandler}。</p>
 */
public class MaixrTransmutationTabletItem extends Item {

    /** 记录「谁用什么手打开了我们的转化桌」，供关闭事件结算耐久。 */
    private static final Map<UUID, InteractionHand> OPENED = new ConcurrentHashMap<>();

    /** 该物品能使用几次。 */
    private final int maxUses;

    /** 耐久耗尽后产生的物品（通常是「奥术转化余质」）。 */
    private final java.util.function.Supplier<Item> residue;

    /** 语言键用的标识（{@code item.maixr.<langId>.tooltip}）。 */
    private final String langId;

    /**
     * 卖出折率（0~1）。用这张桌子把物品换成 EMC 时，只按该比例折算。
     * <ul>
     *   <li>裂隙所遗弃：{@code 0.4}（4 折）</li>
     *   <li>艰难重生：{@code 0.6}（6 折）</li>
     *   <li>弧光孕育：{@code 0.8}（8 折）</li>
     * </ul>
     * <b>买入不受影响</b> —— 用 EMC 换物品仍按原价。
     */
    private final double sellRate;

    public MaixrTransmutationTabletItem(Properties properties, int maxUses,
                                        java.util.function.Supplier<Item> residue, String langId,
                                        double sellRate) {
        super(properties.durability(maxUses));
        this.maxUses = maxUses;
        this.residue = residue;
        this.langId = langId;
        this.sellRate = sellRate;
    }

    public int getMaxUses() {
        return maxUses;
    }

    public Item getResidue() {
        return residue.get();
    }

    /** 卖出折率（0~1）。1.0 = 原价。 */
    public double getSellRate() {
        return sellRate;
    }

    /** 折扣百分数，用于显示（如 40 表示 4 折）。 */
    public int getSellPercent() {
        return (int) Math.round(sellRate * 100.0);
    }

    // ------------------------------------------------------------------ 使用

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide) {
            // 客户端只负责抬手动画；界面由服务端下发。
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        if (player instanceof ServerPlayer serverPlayer) {
            // 【选项 B】缺 ProjectE 时给一句明确提示，而不是静默失败或崩溃
            if (!ProjectEBridge.isLoaded()) {
                serverPlayer.displayClientMessage(Component.literal(
                        "§c[转化桌] 需要安装 §e等价交换（ProjectE）§c 才能使用。"), true);
                return InteractionResultHolder.fail(stack);
            }
            if (!openTransmutation(serverPlayer, hand)) {
                serverPlayer.displayClientMessage(Component.literal(
                        "§c[转化桌] 打开界面失败，请检查日志。"), true);
                return InteractionResultHolder.fail(stack);
            }
            // 记下来，等界面关闭时再结算耐久（创造模式不扣）。
            if (!player.getAbilities().instabuild) {
                OPENED.put(player.getUUID(), hand);
            }
            // 贬值由 MaixrDiscountedTransmutationContainer 在容器内处理；
            // 折率提示由客户端 MaixrDiscountOverlay 画在界面里
            // （聊天框在界面打开时看不见，不能用聊天提示）。
        }
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    /**
     * 用 ProjectE 的容器打开转化桌界面。
     *
     * <p><b>ProjectE 不是必需依赖</b> —— 没装时本方法返回 {@code false}，
     * 调用方会给玩家一句提示，不会崩。</p>
     *
     * @return 是否成功打开
     */
    private boolean openTransmutation(ServerPlayer player, InteractionHand hand) {
        // ① 先确认 ProjectE 在不在（缺了就提示，见调用方）
        if (!ProjectEBridge.isLoaded()) {
            return false;
        }
        try {
            final int selected = player.getInventory().selected;
            final double rate = this.sellRate;

            MenuProvider provider = new MenuProvider() {
                @Override
                public Component getDisplayName() {
                    // ProjectE 自己的标题键（= 转化桌）
                    return Component.translatable("transmutation.projecte.transmute");
                }

                @Override
                public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                    // 【零编译期依赖】动态代理造的「带贬值」容器：卖出按 rate 打折，买入原价。
                    // ProjectE 未装时 create() 返回 null，此时退回 ProjectE 自己的容器（也不会发生，
                    // 因为上面已经拦了 isLoaded）。
                    Object menu = MaixrDiscountedTransmutationContainer.create(id, inv, hand, selected, rate);
                    if (menu instanceof AbstractContainerMenu acm) return acm;
                    return null;
                }
            };

            // 【1.21】NetworkHooks 已移除，直接用 Player#openMenu(provider, extraData)
            player.openMenu(provider, buf -> {
                // 与 TransmutationContainer.fromNetwork 的读取顺序严格一致：
                // readBoolean -> readEnum(InteractionHand) -> readByte
                buf.writeBoolean(true);
                buf.writeEnum(hand);
                buf.writeByte(selected);
            });
            return true;
        } catch (Throwable t) {
            MaixrMod.LOGGER.error("[maixr] 打开转化桌失败", t);
            return false;
        }
    }

    // -------------------------------------------------------------- 耐久结算

    /**
     * 界面关闭时调用：扣 1 点耐久；耐久耗尽则消耗物品并掉落「奥术转化余质」。
     *
     * @param player 打开界面的玩家
     * @param hand   当初用来打开界面的那只手
     */
    public static void consumeOnClose(ServerPlayer player, InteractionHand hand) {
        if (player.getAbilities().instabuild) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof MaixrTransmutationTabletItem tablet)) {
            return;
        }

        int next = stack.getDamageValue() + 1;
        if (next >= tablet.maxUses) {
            // 用完了：物品消失，原地留下余质。
            stack.shrink(1);
            ItemStack residueStack = new ItemStack(tablet.getResidue());
            if (!player.getInventory().add(residueStack)) {
                player.drop(residueStack, false);
            }
            player.displayClientMessage(
                    Component.translatable("message.maixr.transmutation_tablet_consumed")
                            .withStyle(ChatFormatting.GRAY), true);
        } else {
            stack.setDamageValue(next);
        }
    }

    static InteractionHand pollOpened(UUID uuid) {
        return OPENED.remove(uuid);
    }

    // ---------------------------------------------------------------- tooltip

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext level,
                                List<Component> tooltip, TooltipFlag flag) {
        // 风味文字（与 MaixrLoreItem 同样的约定：语言里没有就不显示）
        String titleKey = "item.maixr." + langId + ".tooltip";
        Component title = Component.translatable(titleKey);
        if (!title.getString().equals(titleKey)) {
            tooltip.add(title.copy().withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }

        // 剩余次数
        int left = maxUses - stack.getDamageValue();
        tooltip.add(Component.translatable("item.maixr.transmutation_tablet.uses", left, maxUses)
                .withStyle(left <= 1 ? ChatFormatting.RED : ChatFormatting.AQUA));

        // 卖出折率（C：物品 tooltip）
        tooltip.add(Component.translatable("item.maixr.transmutation_tablet.sell_rate", getSellPercent())
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.maixr.transmutation_tablet.buy_rate")
                .withStyle(ChatFormatting.GREEN));

        tooltip.add(Component.translatable("item.maixr.transmutation_tablet.hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        // 弧光孕育的转化桌（32 次）自带附魔光效。
        return maxUses >= 32;
    }
}
