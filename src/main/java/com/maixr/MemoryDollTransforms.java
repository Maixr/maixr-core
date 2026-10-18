package com.maixr;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * 变身的服务端权威逻辑（手持右键与佩戴快捷键共用 {@link #tryTransform}）。
 *
 * <h2>状态存储</h2>
 * 玩家数据附件存 {@code (到期 gameTime, 飞行是否本能力授予)}：跨重登持久；
 * 死亡默认不复制附件 → 状态自然清除（{@link #onDeath} 在追踪者还在时提前广播还原）。
 *
 * <h2>到期回收</h2>
 * 服务端 tick 检查到期：仅当"飞行由本能力授予且当前非创造（instabuild）"才收回
 * {@code mayfly/flying}（避免误收创造玩家的原生飞行），随后清物品标记、广播还原包。
 */
@EventBusSubscriber(modid = MaixrMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MemoryDollTransforms {
    /** 变身时长：200 tick = 10 秒。 */
    public static final int TRANSFORM_DURATION = 200;
    /** 冷却：600 tick = 30 秒，从触发瞬间起算（含变身期）。 */
    public static final int COOLDOWN_TICKS = 600;
    /**
     * 音效音量：原版机制下 volume &gt; 1 按倍数扩大可闻半径（基线 16 格）——
     * 3.0 即传播距离 3 倍（约 48 格），近距离响度不变。声音以玩家实体为声源（随移动）。
     */
    public static final float SOUND_VOLUME = 3.0F;

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MaixrMod.MODID);

    /**
     * 变身状态：到期 gameTime + 飞行是否由本能力授予。
     *
     * <p>★ 可变类而非 record：NeoForge 21.1.234 的附件序列化走 {@link INBTSerializable}
     * （反序列化时对默认实例逐字段回填，不可变类型无法做到）。本机 21.1.234 没有
     * {@code serializable(Codec)} 重载。</p>
     */
    public static final class SamTransformData implements INBTSerializable<CompoundTag> {
        private long expiryGameTime;
        private boolean flightGranted;

        public SamTransformData() {}

        public SamTransformData(long expiryGameTime, boolean flightGranted) {
            this.expiryGameTime = expiryGameTime;
            this.flightGranted = flightGranted;
        }

        public long expiryGameTime() {
            return this.expiryGameTime;
        }

        public boolean flightGranted() {
            return this.flightGranted;
        }

        @Override
        public CompoundTag serializeNBT(HolderLookup.Provider provider) {
            CompoundTag tag = new CompoundTag();
            tag.putLong("expiry_game_time", this.expiryGameTime);
            tag.putBoolean("flight_granted", this.flightGranted);
            return tag;
        }

        @Override
        public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
            this.expiryGameTime = tag.getLong("expiry_game_time");
            this.flightGranted = tag.getBoolean("flight_granted");
        }
    }

    public static final Supplier<AttachmentType<SamTransformData>> SAM_TRANSFORM =
            ATTACHMENT_TYPES.register("sam_transform",
                    () -> AttachmentType.serializable(SamTransformData::new).build());

    public static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
    }

    private MemoryDollTransforms() {}

    // ==================================================================
    //  变身入口
    // ==================================================================

    /** 统一变身入口：冷却检查 → 附件 + 飞行 + 速度 II + 物品标记 + 冷却 + 语音 + 换肤广播。 */
    public static void tryTransform(ServerPlayer player, ItemStack source) {
        if (player.getCooldowns().isOnCooldown(MaixrMod.MEMORY_TOMORROW_DOLL_ITEM.get())) {
            return; // 冷却中（原版冷却条同时会阻止手持右键路径）
        }
        long now = player.level().getGameTime();
        player.setData(SAM_TRANSFORM.get(), new SamTransformData(now + TRANSFORM_DURATION, true));

        // 创造飞行：立即起飞（onUpdateAbilities 会把能力同步到客户端）
        Abilities abilities = player.getAbilities();
        abilities.mayfly = true;
        abilities.flying = true;
        player.onUpdateAbilities();

        // 速度 II（200 tick，展示粒子与图标）
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, TRANSFORM_DURATION, 1, false, true, true));

        MemoryTomorrowDollItem.setSam(source, true, player);
        player.getCooldowns().addCooldown(MaixrMod.MEMORY_TOMORROW_DOLL_ITEM.get(), COOLDOWN_TICKS);

        // 语音：以玩家【实体】为声源广播（ClientboundSoundEntityPacket —— 客户端把声音挂在玩家身上，
        // 玩家飞行移动时声源跟随）；volume=SOUND_VOLUME 把可闻距离扩到约 48 格，随距离衰减
        player.level().playSound(null, player,
                MaixrMod.MEMORY_DOLL_TRANSFORM_SOUND.get(), SoundSource.PLAYERS, SOUND_VOLUME, 1.0F);
        player.displayClientMessage(Component.literal("\u00a7e我将点燃星海！"), true);

        MaixrNetwork.sendSkinToTrackersAndSelf(player, true);
    }

    /** 佩戴快捷键入口：未佩戴本玩偶（头盔槽 / Curios 头槽）时无效。 */
    public static void onHotkey(ServerPlayer player) {
        ItemStack worn = getWornMemoryDoll(player);
        if (!worn.isEmpty()) {
            tryTransform(player, worn);
        }
    }

    private static ItemStack getWornMemoryDoll(ServerPlayer player) {
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (!head.isEmpty() && head.getItem() == MaixrMod.MEMORY_TOMORROW_DOLL_ITEM.get()) {
            return head;
        }
        if (MaixrCurios.isLoaded()) {
            ItemStack curios = MaixrCurios.getHeadStack(player);
            if (!curios.isEmpty() && curios.getItem() == MaixrMod.MEMORY_TOMORROW_DOLL_ITEM.get()) {
                return curios;
            }
        }
        return ItemStack.EMPTY;
    }

    // ==================================================================
    //  到期回收 / 死亡清理 / 登录补发
    // ==================================================================

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.hasData(SAM_TRANSFORM.get())) return;
        SamTransformData data = player.getData(SAM_TRANSFORM.get());
        if (data.expiryGameTime() > player.level().getGameTime()) return;
        revert(player, data);
    }

    /** 变身期死亡：先在追踪者还在时广播还原，再让死亡流程继续（附件随死亡不复制，等价清除）。 */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.hasData(SAM_TRANSFORM.get())) return;
        revert(player, player.getData(SAM_TRANSFORM.get()));
    }

    /** 重登补发：变身中（未到期）重新登录的玩家本人拿回萨姆皮肤（周围玩家的覆盖表从未清除，无需补发）。 */
    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.hasData(SAM_TRANSFORM.get())) {
            MaixrNetwork.sendSkinToPlayer(player, true);
        }
    }

    /** 到期还原：收回飞行（仅本能力授予且非创造）、清物品标记、播放结束音效、广播还原。 */
    private static void revert(ServerPlayer player, SamTransformData data) {
        if (data.flightGranted() && !player.getAbilities().instabuild) {
            Abilities abilities = player.getAbilities();
            abilities.mayfly = false;
            abilities.flying = false;
            player.onUpdateAbilities();
        }
        player.removeData(SAM_TRANSFORM.get());
        clearSamFlags(player);
        // 结束音效：暂用原版"末地传送门框架镶嵌末影之眼"音效代替流萤大招结束音效（用户指定，拿到素材后可换成注册音效）；
        // 同样以玩家实体为声源、可闻距离 3 倍
        player.level().playSound(null, player,
                net.minecraft.sounds.SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.PLAYERS, SOUND_VOLUME, 1.0F);
        MaixrNetwork.sendSkinToTrackersAndSelf(player, false);
        player.displayClientMessage(Component.literal("\u00a77玩偶恢复了流萤的样子"), true);
    }

    private static void clearSamFlags(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            clearFlag(stack, player);
        }
        for (ItemStack stack : player.getInventory().armor) {
            clearFlag(stack, player);
        }
        clearFlag(player.getInventory().offhand.get(0), player);
    }

    private static void clearFlag(ItemStack stack, ServerPlayer player) {
        if (!stack.isEmpty() && MemoryTomorrowDollItem.isSam(stack)) {
            MemoryTomorrowDollItem.setSam(stack, false, player);
        }
    }
}
