package com.maixr;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端/服务端网络 —— NeoForge 1.21.1 版。
 *
 * <p><b>移植说明（Forge 1.20.1 → NeoForge 1.21.1 的最大断裂点）：</b></p>
 *
 * <table border="1">
 *   <tr><th>Forge 1.20.1</th><th>NeoForge 1.21.1</th></tr>
 *   <tr><td>{@code SimpleChannel} + {@code NetworkRegistry.newSimpleChannel}</td>
 *       <td>{@code CustomPacketPayload} + {@code RegisterPayloadHandlersEvent}</td></tr>
 *   <tr><td>{@code registerMessage(id, class, enc, dec, handler)}</td>
 *       <td>{@code registrar.playToClient/playToServer(type, codec, handler)}</td></tr>
 *   <tr><td>{@code Supplier<NetworkEvent.Context>} + {@code setPacketHandled}</td>
 *       <td>{@code IPayloadContext}（无需手动标记已处理）</td></tr>
 *   <tr><td>{@code DistExecutor.unsafeRunWhenOn(CLIENT, ...)}</td>
 *       <td>不再需要 —— 用 {@code playToClient} 就只会发给客户端</td></tr>
 * </table>
 *
 * <p>注册入口在 {@link #register(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent)}
 * 里，由 NeoForge 在 mod 加载阶段调用（1.21 不允许运行期注册）。</p>
 */
public final class MaixrNetwork {

    /** 协议版本：NeoForge 用它做握手校验，客户端/服务端不一致会拒连。 */
    private static final String PROTOCOL = "1";

    // ==========================================================================
    //  ① 真理玩偶：服务端 -> 客户端（播放"玩偶激活"动画）
    // ==========================================================================

    public record TruthTotemMsg() implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<TruthTotemMsg> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "truth_totem"));

        public static final StreamCodec<FriendlyByteBuf, TruthTotemMsg> CODEC =
                StreamCodec.unit(new TruthTotemMsg());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ==========================================================================
    //  ② 伊甸世界状态同步：服务端 -> 所有客户端（动态天空要用）
    // ==========================================================================

    public record EdenSyncMsg(int completeness, int stage, boolean invasion, int wave, String requiredItem)
            implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<EdenSyncMsg> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "eden_sync"));

        public static final StreamCodec<FriendlyByteBuf, EdenSyncMsg> CODEC =
                StreamCodec.of(
                        (buf, msg) -> {
                            buf.writeVarInt(msg.completeness());
                            buf.writeVarInt(msg.stage());
                            buf.writeBoolean(msg.invasion());
                            buf.writeVarInt(msg.wave());
                            buf.writeUtf(msg.requiredItem() == null ? "" : msg.requiredItem(), 128);
                        },
                        buf -> new EdenSyncMsg(buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                                buf.readVarInt(), buf.readUtf(128)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ==========================================================================
    //  ③ 裂隙抉择窗：服务端 -> 单个客户端
    // ==========================================================================

    public record RiftChoiceOpenMsg(int seconds) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RiftChoiceOpenMsg> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "rift_choice_open"));

        public static final StreamCodec<FriendlyByteBuf, RiftChoiceOpenMsg> CODEC =
                StreamCodec.of(
                        (buf, msg) -> buf.writeVarInt(msg.seconds()),
                        buf -> new RiftChoiceOpenMsg(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ==========================================================================
    //  ④ 裂隙抉择结果：客户端 -> 服务端
    // ==========================================================================

    public record RiftChoiceMsg(int choice) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RiftChoiceMsg> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "rift_choice"));

        public static final StreamCodec<FriendlyByteBuf, RiftChoiceMsg> CODEC =
                StreamCodec.of(
                        (buf, msg) -> buf.writeVarInt(msg.choice()),
                        buf -> new RiftChoiceMsg(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ==========================================================================
    //  ⑤ 自定义 Boss 血条：服务端 -> 所有客户端
    // ==========================================================================

    public record RiftBossBarMsg(float percent, int phase, boolean active, float hp, float maxHp)
            implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RiftBossBarMsg> TYPE =
                new CustomPacketPayload.Type<>(
                        ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "rift_boss_bar"));

        public static final StreamCodec<FriendlyByteBuf, RiftBossBarMsg> CODEC =
                StreamCodec.of(
                        (buf, msg) -> {
                            buf.writeFloat(msg.percent());
                            buf.writeVarInt(msg.phase());
                            buf.writeBoolean(msg.active());
                            buf.writeFloat(msg.hp());
                            buf.writeFloat(msg.maxHp());
                        },
                        buf -> new RiftBossBarMsg(buf.readFloat(), buf.readVarInt(), buf.readBoolean(),
                                buf.readFloat(), buf.readFloat()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ==========================================================================
    //  注册（由 NeoForge 在 mod 加载阶段调用）
    // ==========================================================================

    /**
     * 注册全部网络包。
     *
     * <p>在 {@code MaixrMod} 构造函数里挂到 mod 事件总线上：
     * {@code modBus.addListener(MaixrNetwork::register)}</p>
     */
    public static void register(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);

        // 服务端 -> 客户端
        registrar.playToClient(TruthTotemMsg.TYPE, TruthTotemMsg.CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> com.maixr.client.MaixrClientPackets.truthTotem()));

        registrar.playToClient(EdenSyncMsg.TYPE, EdenSyncMsg.CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> com.maixr.client.MaixrClientPackets.edenSync(
                        msg.completeness(), msg.stage(), msg.invasion(), msg.wave(), msg.requiredItem())));

        registrar.playToClient(RiftChoiceOpenMsg.TYPE, RiftChoiceOpenMsg.CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> com.maixr.client.MaixrClientPackets.riftChoiceOpen(
                        msg.seconds())));

        registrar.playToClient(RiftBossBarMsg.TYPE, RiftBossBarMsg.CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> com.maixr.client.MaixrClientPackets.riftBossBar(
                        msg.percent(), msg.phase(), msg.active(), msg.hp(), msg.maxHp())));

        // 客户端 -> 服务端
        registrar.playToServer(RiftChoiceMsg.TYPE, RiftChoiceMsg.CODEC,
                MaixrNetwork::handleRiftChoice);
    }

    /** 处理客户端的抉择结果。{@code ctx.player()} 在 playToServer 里就是发消息的玩家。 */
    private static void handleRiftChoice(RiftChoiceMsg msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (msg.choice() == 0) MaixrRiftHandler.selfSacrifice(player);
            else MaixrRiftHandler.bearCurse(player);
        });
    }

    // ==========================================================================
    //  发送辅助
    // ==========================================================================

    /** 服务端 -> 客户端：弹出「裂隙弥合」抉择窗 */
    public static void sendRiftChoice(ServerPlayer player, int seconds) {
        PacketDistributor.sendToPlayer(player, new RiftChoiceOpenMsg(seconds));
    }

    /** 客户端 -> 服务端：玩家选了哪一边（0 = 自我凋零，1 = 抗下诅咒） */
    public static void sendRiftChoiceResult(int choice) {
        PacketDistributor.sendToServer(new RiftChoiceMsg(choice));
    }

    /** 服务端 -> 客户端：自定义 Boss 血条（只有百分比） */
    public static void sendRiftBossBar(float percent, int phase, boolean active) {
        sendRiftBossBar(percent, phase, active, 0F, 0F);
    }

    /** 服务端 -> 客户端：自定义 Boss 血条（带血量数字，血条上要显示 x / 4096） */
    public static void sendRiftBossBar(float percent, int phase, boolean active, float hp, float maxHp) {
        PacketDistributor.sendToAllPlayers(new RiftBossBarMsg(percent, phase, active, hp, maxHp));
    }

    /** 把世界完整度/纪元同步给所有客户端（客户端动态天空要用） */
    public static void sendEdenSync(int completeness, int stage, boolean invasion, int wave, String requiredItem) {
        PacketDistributor.sendToAllPlayers(
                new EdenSyncMsg(completeness, stage, invasion, wave, requiredItem == null ? "" : requiredItem));
    }

    /** 通知某玩家：真理玩偶触发了不死保护 */
    public static void sendTruthTotemTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new TruthTotemMsg());
    }

    /** 兼容旧调用点（原先是"触发类静态初始化"用）。 */
    public static void init() {
        // 1.21 的注册发生在 RegisterPayloadHandlersEvent，不再需要手动触发
    }

    private MaixrNetwork() {}
}
