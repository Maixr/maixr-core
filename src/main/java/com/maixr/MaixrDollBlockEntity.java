package com.maixr;

import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.me.helpers.MachineSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 放置的玩偶方块实体 —— <b>可接入 AE2 的 ME 网络</b>。
 *
 * <h2>接入网络后做什么</h2>
 *
 * <p>每 <b>1 分钟</b>（{@value #PRODUCE_INTERVAL} tick）往网络存储里塞
 * <b>1 个自己</b>（Maixr 玩偶 → Maixr 玩偶；真理玩偶 → 真理玩偶）。
 * 存储满了（插入返回 0）就每 {@value #RETRY_INTERVAL} tick 重试一次，
 * 不会丢产出也不会刷屏。</p>
 *
 * <h2>「占一个频道」「显示在线」是怎么来的</h2>
 *
 * <ul>
 *   <li>{@code GridFlags.REQUIRE_CHANNEL} —— 节点必须真拿到 1 个频道才算数；
 *       AE2 里一个这样的节点默认就是 <b>1 频道</b>。</li>
 *   <li>有了频道后 {@code getMainNode().isOnline()} 为真 —— 网络里就把它当作
 *       <b>在线设备</b>显示（与驱动器/接口同一套逻辑）。</li>
 * </ul>
 *
 * <h2>★ 为什么继承 AENetworkedBlockEntity 而不是自己 new 节点</h2>
 *
 * <p>{@code AENetworkedBlockEntity} 已经把节点生命周期全接好了：
 * {@code clearRemoved() -> scheduleInit() -> onReady() -> mainNode.create(...)}，
 * 以及 {@code setRemoved()/onChunkUnloaded() -> mainNode.destroy()}、
 * NBT 存读、朝向变化时重新暴露侧面。自己写容易漏 {@code create()} 或漏
 * {@code destroy()}（后者会让网络里残留幽灵节点）。</p>
 *
 * <p>但它<b>不会</b>自动暴露给线缆 —— 还需要在 {@link MaixrAe2Integration} 里
 * 给这两个方块实体类型注册 {@code AECapabilities.IN_WORLD_GRID_NODE_HOST}。</p>
 */
public class MaixrDollBlockEntity extends AENetworkedBlockEntity implements IGridTickable {

    /** 旋转速度（度/秒）。 */
    public static final float SPIN_SPEED = 60.0F;

    /** 产出一个玩偶的间隔：1200 tick = 1 分钟（20 tps）。 */
    public static final int PRODUCE_INTERVAL = 1200;

    /** 存储满时的重试间隔（tick）。5 秒试一次，别每 tick 都去戳存储。 */
    private static final int RETRY_INTERVAL = 100;

    /** AE2 的 tick 粒度：1 秒被叫一次，够精确且省性能。 */
    private static final int TICK_RATE = 20;

    private boolean spinning = false;

    /** 距离下次产出的剩余 tick。 */
    private int cooldown = PRODUCE_INTERVAL;

    /** 以「这台机器」身份向网络插入物品。 */
    private final IActionSource actionSource = new MachineSource(this);

    public MaixrDollBlockEntity(BlockPos pos, BlockState state) {
        this(MaixrBlockEntities.SKIN_DOLL.get(), pos, state);
    }

    protected MaixrDollBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.getMainNode()
                // 必须拿到 1 个频道才算"接入"（同时这也让它显示为在线设备）
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                // 注册进 AE2 的 tick 服务，网络每 TICK_RATE tick 回调一次
                .addService(IGridTickable.class, this);
    }

    /**
     * 这台玩偶"产出"的物品 —— 真理玩偶覆写成真理玩偶。
     *
     * <p>★ 注意只在 {@link #onReady()} / {@link #produceOne()} 里调用，
     * <b>不</b>在构造器里调 —— 构造期子类还没初始化完。</p>
     */
    protected Item getProducedItem() {
        return MaixrMod.SKIN_DOLL_ITEM.get();
    }

    // ==================================================================
    //  旋转（原有功能，保留）
    // ==================================================================

    public boolean isSpinning() {
        return spinning;
    }

    /** 右键切换旋转。 */
    public void toggleSpinning() {
        this.spinning = !this.spinning;
        this.saveChanges();
        this.markForClientUpdate();
    }

    // ==================================================================
    //  AE2 tick：每分钟产出 1 个自己
    // ==================================================================

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        // isSleeping = false：保持持续被 tick。（AE2 的自动休眠默认会睡，睡了我们就不产出了）
        return new TickingRequest(TICK_RATE, TICK_RATE, false, TICK_RATE);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        // ★ 「接入网络」才工作：没频道 / 网络没电时 isOnline() 为假，计时原地暂停
        if (!this.getMainNode().isOnline()) {
            return TickRateModulation.SAME;
        }

        this.cooldown -= ticksSinceLastCall;
        if (this.cooldown > 0) {
            return TickRateModulation.SAME;
        }

        if (this.produceOne()) {
            this.cooldown = PRODUCE_INTERVAL;
        } else {
            // 存储满了 —— 不重置成 1 分钟，而是 5 秒后重试，免得满了以后要等很久才恢复
            this.cooldown = RETRY_INTERVAL;
        }
        return TickRateModulation.SAME;
    }

    /**
     * 尝试往网络里塞 1 个自己的玩偶。
     *
     * @return 真的塞进去了（存储有空位）才返回 true
     */
    private boolean produceOne() {
        IGrid grid = this.getMainNode().getGrid();
        if (grid == null) {
            return false;
        }
        MEStorage storage = grid.getStorageService().getInventory();
        AEItemKey key = AEItemKey.of(new ItemStack(this.getProducedItem()));
        if (key == null) {
            return false;
        }
        long inserted = storage.insert(key, 1L, Actionable.MODULATE, this.actionSource);
        return inserted > 0;
    }

    // ==================================================================
    //  生命周期
    // ==================================================================

    @Override
    public void onReady() {
        // 网络状态面板里用玩偶本体当图标
        this.getMainNode().setVisualRepresentation(this.getProducedItem());
        super.onReady();
    }

    // ==================================================================
    //  存档 / 同步
    // ==================================================================

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        data.putBoolean("Spinning", this.spinning);
        data.putInt("Cooldown", this.cooldown);
    }

    /** ★ AE2 的 AEBaseBlockEntity.loadAdditional 是 final，会转调这里。 */
    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        this.spinning = data.getBoolean("Spinning");
        this.cooldown = data.contains("Cooldown") ? data.getInt("Cooldown") : PRODUCE_INTERVAL;
    }

    /**
     * 客户端同步走 AE2 的流式通道（{@code #upd} 字节数组）。
     *
     * <p>★ 别自己覆写 {@code getUpdateTag()} 往里面塞字段 ——
     * {@code AEBaseBlockEntity.loadAdditional} 只有在 tag 里<b>仅有</b>
     * {@code #upd} 一个键时才走流式解析，多塞键会让同步整条失效。</p>
     */
    @Override
    protected void writeToStream(RegistryFriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeBoolean(this.spinning);
    }

    @Override
    protected boolean readFromStream(RegistryFriendlyByteBuf data) {
        boolean changed = super.readFromStream(data);
        boolean now = data.readBoolean();
        if (now != this.spinning) {
            this.spinning = now;
            changed = true;
        }
        return changed;
    }
}
