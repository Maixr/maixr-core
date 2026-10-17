package com.maixr;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

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
 * <p>目前只有一条包：真理玩偶触发不死保护时通知客户端播放"玩偶激活"动画。
 * 客户端逻辑全部收在 {@code com.maixr.client.MaixrClientPackets}（专用服务端上
 * 不能出现 {@code net.minecraft.client.*} 类引用）。</p>
 *
 * <p>注册入口在 {@link #register(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent)}
 * 里，由 NeoForge 在 mod 加载阶段调用（1.21 不允许运行期注册）。</p>
 */
public final class MaixrNetwork {

    /** 协议版本：NeoForge 用它做握手校验，客户端/服务端不一致会拒连。 */
    private static final String PROTOCOL = "1";

    // ==========================================================================
    //  真理玩偶：服务端 -> 客户端（播放"玩偶激活"动画）
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

    /**
     * 注册全部网络包。
     *
     * <p>在 {@code MaixrMod} 构造函数里挂到 mod 事件总线上：
     * {@code modBus.addListener(MaixrNetwork::register)}</p>
     */
    public static void register(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);

        registrar.playToClient(TruthTotemMsg.TYPE, TruthTotemMsg.CODEC,
                (msg, ctx) -> ctx.enqueueWork(() -> com.maixr.client.MaixrClientPackets.truthTotem()));
    }

    /** 通知某玩家：真理玩偶触发了不死保护 */
    public static void sendTruthTotemTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new TruthTotemMsg());
    }

    private MaixrNetwork() {}
}
