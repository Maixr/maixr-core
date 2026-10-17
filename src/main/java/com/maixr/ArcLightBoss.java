package com.maixr;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 弧光·裂隙意志 —— 弧光纪元（90~100%）入侵中唯一的敌人。
 *
 * <p>它「不是生物，是世界裂缝本身的意志」：没有腿、没有脸，一颗悬浮的发光核心
 * 外面套着八块逆向旋转的黑色碎片。
 *
 * <h3>三相位</h3>
 * <ul>
 *   <li><b>一 · 凝视</b>：近战，每 40 秒召唤一只凋灵</li>
 *   <li><b>二 · 撕裂</b>：加速 + 冲击波（击飞 + 反胃）</li>
 *   <li><b>三 · 坍缩</b>：裂隙轻拉（已削弱，按距离 0.035~0.09/tick）；<b>在玩家周围 50 格内随机瞬移</b>，
 *       并不断朝玩家<b>丢 TNT</b>；被拽进裂隙的玩家会被丢回主世界</li>
 * </ul>
 *
 * <p><b>所有伤害都是百分比伤害</b>（按目标最大生命计算），所以再厚的血也扛不住几刀。
 * <p><b>免疫贪婪类武器</b>：用无尽贪婪 / 等价交换 / 创造物品打它，会被极光色的文字嘲讽。
 */
public class ArcLightBoss extends Monster {
    /** 被拉到这个距离以内 = 掉进裂隙 */
    private static final double DEVOUR_RANGE = 2.0D;      // 被裂隙"吐出"的判定半径（2 格）

    private int summonCooldown = 100;      // 二阶段起：约 5 秒孕育一只
    private int shockCooldown = 160;
    private int lightningCooldown = 60;    // 普通攻击：每 3 秒朝目标劈一道闪电
    private int teleportCooldown = 120;    // 相位三：在玩家周围随机瞬移
    private int tntCooldown = 60;          // 相位三：朝玩家丢 TNT
    private int lastPhase = -1;

    /** 相位通过实体数据同步，客户端模型与血条都读它 */
    private static final EntityDataAccessor<Integer> DATA_PHASE =
            SynchedEntityData.defineId(ArcLightBoss.class, EntityDataSerializers.INT);

    public ArcLightBoss(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setNoGravity(true);                       // 像凋灵一样飞
        this.moveControl = new FlyingMoveControl(this, 20, true);
        this.xpReward = 3000;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 4096.0D)       // 4096 血（按需求）+ 自带生命回复 II
                .add(Attributes.ARMOR, 32.0D)              // 32 防御
                .add(Attributes.ATTACK_DAMAGE, 22.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.34D)
                .add(Attributes.FOLLOW_RANGE, 64.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0D, true));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 32.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // 【1.21 移植】MobType 枚举已被移除（连同 getMobType()）。
    // 1.21 改用实体类型标签来决定"亡灵/节肢"这类归类：
    //   原版通过数据包给实体类型打 #minecraft:undead 标签，
    //   代码里判断用 entity.getType().is(EntityTypeTags.UNDEAD)。
    // 本 Boss 的"亡灵"特性（受亡灵杀手加成、治疗药水伤害它等）不再由方法 override 提供。
    // 若需要保留该特性，应改为在数据包里给 maixr:arc_light_boss 实体类型打标签。

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)
                || source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)
                || super.isInvulnerableTo(source);
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void checkDespawn() {
        // 入侵期间永不自然消失
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        // 【1.21 移植】1.21 把同步数据注册挪到了 Builder 上：
        //   1.20.1: super.defineSynchedData(); this.entityData.define(ID, v);
        //   1.21  : super.defineSynchedData(builder); builder.define(ID, v);
        super.defineSynchedData(builder);
        builder.define(DATA_PHASE, 0);
    }

    /** 当前相位 0/1/2（服务端算完写进同步数据，客户端直接读） */
    public int phase() {
        if (this.level().isClientSide) return this.entityData.get(DATA_PHASE);
        float ratio = this.getHealth() / Math.max(1.0F, this.getMaxHealth());
        int p = ratio > 0.66F ? 0 : (ratio > 0.33F ? 1 : 2);
        this.entityData.set(DATA_PHASE, p);
        return p;
    }

    // ------------------------------------------------------------------ 免疫情节

    /** 贪婪类武器：无尽贪婪 / 等价交换红物质 / 创造物品 */
    public static boolean isGreedyWeapon(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return false;
        String path = id.getPath();
        return (id.getNamespace().equals("avaritia") && path.startsWith("infinity"))
                || (id.getNamespace().equals("projecte") && path.startsWith("rm_"))
                || path.contains("creative");
    }

    /** 每个玩家上一次看到嘲讽的时间（按玩家冷却，而不是按游戏刻取模 —— 那样 40 次里只响 1 次） */
    private static final java.util.Map<java.util.UUID, Long> TAUNT_AT = new java.util.HashMap<>();

    /**
     * 我们自己走<b>正常伤害流程</b>时把这个开关打开 —— 只有这时候才允许血量掉到 0。
     *
     * <p>★ 为什么需要它：寰宇支配之剑（Re-Avaritia 的 InfinitySwordItem）在「无尽攻击」模式下
     * <b>自己复刻了一遍 {@code LivingEntity.hurt} 的方法体</b>，直接
     * {@code victim.setHealth(victim.getHealth() - Float.MAX_VALUE)}，
     * 既不调用 {@code Entity.hurt()}，也不触发 {@code LivingIncomingDamageEvent}，更不会走 {@code die()}。
     * 所以拦 {@code hurt()} 是拦不住它的 —— 唯一的 choke point 就是 {@code setHealth}。
     */
    private boolean allowLethalHealth = false;

    /**
     * 拦「绕过伤害流程的直接改血」。
     *
     * <p>只有一种情况会被拒：<b>当前还活着，却被要求把血量写到 1 以下</b>。
     * 正常战斗里把 boss 打死是走 {@code hurt()} → 这里带着 {@link #allowLethalHealth} 放行；
     * 治疗、读档、命令都改不到 0 以下，不受影响。
     */
    @Override
    public void setHealth(float health) {
        if (!this.allowLethalHealth && health < 1.0F && this.getHealth() > 0.0F) {
            this.onBypassAttempt();
            return;
        }
        super.setHealth(health);
    }

    /** 有人在对它用"不打伤害流程"的秒杀 —— 播报 + 记日志（全局 1 秒最多一次，免得刷屏） */
    private void onBypassAttempt() {
        if (!(this.level() instanceof ServerLevel serverLevel)) return;
        long now = serverLevel.getGameTime();
        if (now - BYPASS_LAST < 20) return;
        BYPASS_LAST = now;
        MaixrMod.LOGGER.warn("[弧光] 检测到绕过伤害流程的秒杀（血量被直接改写），已拒绝。血量仍是 {}", this.getHealth());
        serverLevel.playSound((net.minecraft.world.entity.player.Player) null, this.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, 1.5F, 0.5F);
        for (ServerPlayer p : serverLevel.getEntitiesOfClass(ServerPlayer.class, this.getBoundingBox().inflate(48.0))) {
            p.displayClientMessage(auroraText("裂隙不吃这一套 —— 那把剑不可能刺穿它！！！"), true);
        }
    }

    private static long BYPASS_LAST = -1000L;

    // ------------------------------------------------------- 免疫：枪械 / 电磁炮

    /** TaCZ（永恒枪械工坊）的子弹伤害全部带这个伤害类型标签（data/tacz/tags/damage_type/bullets.json） */
    private static final TagKey<DamageType> TACZ_BULLETS =
            TagKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath("tacz", "bullets"));
    /** 闪电科技（ae2lt）电磁炮自己的伤害类型（data/ae2lt/damage_type/electromagnetic.json） */
    private static final ResourceKey<DamageType> AE2LT_ELECTROMAGNETIC =
            ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath("ae2lt", "electromagnetic"));

    /** 这一下是不是 TaCZ 的枪械子弹（标签 → 弹丸类名 → 手上武器，三层兜底） */
    private static boolean isTaczBullet(DamageSource source) {
        try {
            if (source.is(TACZ_BULLETS)) return true;
        } catch (Throwable ignored) {
            // 标签没加载（数据包异常）就退回下面两层
        }
        Entity direct = source.getDirectEntity();
        if (direct != null && direct.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("tacz")) {
            return true;
        }
        Entity attacker = source.getEntity();
        if (attacker instanceof LivingEntity living) {
            return isTaczItem(living.getMainHandItem()) || isTaczItem(living.getOffhandItem());
        }
        return false;
    }

    private static boolean isTaczItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && id.getNamespace().equals("tacz");
    }

    /** 这一下是不是闪电科技的电磁炮（伤害类型 → 弹丸类名 → 手上武器 → 死亡信息） */
    private static boolean isAe2ltRailgun(DamageSource source) {
        try {
            if (source.is(AE2LT_ELECTROMAGNETIC)) return true;
        } catch (Throwable ignored) {
        }
        Entity direct = source.getDirectEntity();
        if (direct != null && direct.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("ae2lt")) {
            return true;
        }
        Entity attacker = source.getEntity();
        if (attacker instanceof LivingEntity living) {
            return isRailgunItem(living.getMainHandItem()) || isRailgunItem(living.getOffhandItem());
        }
        String msg = source.getMsgId();
        return msg != null && msg.contains("ae2lt");
    }

    private static boolean isRailgunItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && id.getNamespace().equals("ae2lt") && id.getPath().contains("railgun");
    }

    /**
     * 傲慢风嘲讽：同一个玩家 1 秒最多一次（与贪婪武器共用冷却表，避免刷屏）。
     * 伤害不是玩家造成时（自动炮台之类），播给附近 48 格内的玩家。
     */
    private void arrogantTaunt(DamageSource source, String text) {
        if (!(this.level() instanceof ServerLevel serverLevel)) return;
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            for (ServerPlayer p : serverLevel.getEntitiesOfClass(ServerPlayer.class,
                    this.getBoundingBox().inflate(48.0D))) {
                p.displayClientMessage(auroraText(text), true);
            }
            return;
        }
        long now = serverLevel.getGameTime();
        Long last = TAUNT_AT.get(player.getUUID());
        if (last != null && now - last < 20) return;
        TAUNT_AT.put(player.getUUID(), now);
        player.displayClientMessage(auroraText(
                player.getGameProfile().getName() + "，" + text), true);
        serverLevel.playSound((net.minecraft.world.entity.player.Player) null, player.blockPosition(), SoundEvents.SHIELD_BLOCK,
                SoundSource.HOSTILE, 1.2F, 0.6F);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof ServerPlayer player
                && (isGreedyWeapon(player.getMainHandItem()) || isGreedyWeapon(player.getOffhandItem()))) {
            if (this.level() instanceof ServerLevel serverLevel) {
                long now = serverLevel.getGameTime();
                Long last = TAUNT_AT.get(player.getUUID());
                if (last == null || now - last >= 20) {           // 同一玩家 1 秒一次
                    TAUNT_AT.put(player.getUUID(), now);
                    player.displayClientMessage(auroraText(
                            player.getGameProfile().getName() + "，那把象征贪婪的武器不可能刺穿裂隙！！！"), true);
                    serverLevel.playSound((net.minecraft.world.entity.player.Player) null, player.blockPosition(), SoundEvents.SHIELD_BLOCK,
                            SoundSource.HOSTILE, 1.2F, 0.6F);
                }
            }
            return false;
        }
        // ★ 全阶段免疫：闪电科技（ae2lt）的电磁炮
        if (isAe2ltRailgun(source)) {
            arrogantTaunt(source, "电磁炮？你把线圈推到过载，也推不穿一道世界裂缝。");
            return false;
        }
        // ★ 相位三（坍缩）起免疫：TaCZ 枪械子弹
        if (phase() >= 2 && isTaczBullet(source)) {
            arrogantTaunt(source, "子弹？那点铁屑连我脚下的虚空都惊不动 —— 这里不是枪说了算的地方。");
            return false;
        }
        this.allowLethalHealth = true;                 // 正常伤害流程：允许它真的把血打到 0
        try {
            return super.hurt(source, amount);
        } finally {
            this.allowLethalHealth = false;
        }
    }

    /** 极光色渐变的文字：青 → 绿 → 紫 → 粉 → 青 */
    public static Component auroraText(String text) {
        final int[] stops = {0x66FFE0, 0x66FF99, 0x99CCFF, 0xCC99FF, 0xFF99D0, 0x66FFE0};
        MutableComponent out = Component.empty();
        int len = Math.max(1, text.length());
        for (int i = 0; i < text.length(); i++) {
            float t = (float) i / len * (stops.length - 1);
            int seg = Math.min(stops.length - 2, (int) t);
            float f = t - seg;
            int a = stops[seg], b = stops[seg + 1];
            int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * f);
            int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * f);
            int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * f);
            out.append(Component.literal(String.valueOf(text.charAt(i)))
                    .withStyle(Style.EMPTY.withColor((r << 16) | (g << 8) | bl)));
        }
        return out;
    }

    // ------------------------------------------------------------------ 百分比伤害

    /** 所有伤害都是「目标最大生命的百分比」，随相位提高 */
    @Override
    public boolean doHurtTarget(Entity target) {
        if (!(target instanceof LivingEntity living)) return false;
        float pct = phase() >= 2 ? 0.30F : (phase() >= 1 ? 0.24F : 0.18F);
        float damage = Math.max(6.0F, living.getMaxHealth() * pct);
        boolean hurt = living.hurt(this.damageSources().indirectMagic(this, this), damage);
        if (hurt) {
            // 【1.21 移植】LivingEntity#doEnchantDamageEffects 已被移除。
            // 它的作用是把主手武器的附魔额外效果（火焰附加、亡灵杀手等）应用到目标上，
            // 1.21 里这一步由 hurt() 内部的附魔结算链自动完成，无需（也无法）手动调用。
            this.setLastHurtMob(living);
        }
        return hurt;
    }

    // ------------------------------------------------------------------ 每 tick

    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide) return;
        // ★ 常驻发光：别的模组/效果有可能把 glowing 洗掉，这里每秒补一次，
        //   保证玩家永远能隔着地形看见祂在哪（找 Boss 体验的关键）。
        if (this.tickCount % 20 == 0 && !this.hasGlowingTag()) {
            this.setGlowingTag(true);
        }
        // ★ 主动追击。
        //   原来这里只调整了 Y（悬停），水平方向完全不动 ——
        //   因为它是 noGravity + FlyingMoveControl 的飞行生物，MeleeAttackGoal 那套地面寻路根本不驱动它，
        //   于是"BOSS 不主动追玩家"。现在直接在 aiStep 里按相位速度朝目标飞。
        LivingEntity target = this.getTarget();
        Vec3 dm = this.getDeltaMovement();
        if (target == null || !target.isAlive()) {
            this.setDeltaMovement(dm.x * 0.9D, dm.y * 0.9D, dm.z * 0.9D);
            return;
        }
        double wantY = target.getY() + 3.0D;
        double dy = wantY - this.getY();
        Vec3 to = target.position().subtract(this.position());
        double horiz = Math.sqrt(to.x * to.x + to.z * to.z);
        int ph = phase();
        double speed = ph >= 2 ? 0.46D : (ph >= 1 ? 0.36D : 0.28D);   // 相位越高追得越凶
        double vx = 0.0D, vz = 0.0D;
        if (horiz > 1.0E-4) {
            vx = to.x / horiz * speed;
            vz = to.z / horiz * speed;
            if (horiz < 3.0D) {          // 贴脸了就别继续怼，留出近战距离
                vx *= 0.15D;
                vz *= 0.15D;
            }
        }
        this.setDeltaMovement(vx, dm.y + (dy * 0.02D - dm.y) * 0.12D, vz);
        this.getLookControl().setLookAt(target, 40.0F, 40.0F);
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(this.level() instanceof ServerLevel level)) return;

        int phase = phase();
        if (phase != lastPhase) {
            lastPhase = phase;
            onPhaseChange(level, phase);
        }
        var speed = this.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(phase >= 1 ? 0.48D : 0.34D);

        // 相位越高，粒子越凶
        if (level.getGameTime() % 4 == 0) {
            net.minecraft.core.particles.ParticleOptions particle = switch (phase) {
                case 0 -> ParticleTypes.END_ROD;
                case 1 -> ParticleTypes.SOUL_FIRE_FLAME;
                default -> ParticleTypes.SCULK_SOUL;
            };
            level.sendParticles(particle, this.getX(), this.getY() + 1.2, this.getZ(),
                    3 + phase * 3, 0.8, 0.8, 0.8, 0.02);
        }
        // ★ 心音：整个战场都在跳 —— 压迫感的主要来源
        if (level.getGameTime() % 40 == 0) {
            level.playSound((net.minecraft.world.entity.player.Player) null, this.blockPosition(), SoundEvents.WARDEN_HEARTBEAT,
                    SoundSource.HOSTILE, 0.9F + phase * 0.35F, 0.8F - phase * 0.12F);
        }
        // ★ 自带生命回复 II（amplifier 1 = 二级）：每 5 秒续一次，永远挂着 —— 打它得比它回得快
        if (this.tickCount % 100 == 0) {
            this.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 220, 1, false, false));
        }

        // ★ 自定义血条（现在这是唯一的血条：原版 ServerBossEvent 已彻底移除，
        //   之前它是实体字段、每次实体重建就换一个 UUID，客户端会攒出一堆收不掉的残影条）
        if (level.getGameTime() % 10 == 0) {
            MaixrNetwork.sendRiftBossBar(this.getHealth() / this.getMaxHealth(), phase, true,
                    this.getHealth(), this.getMaxHealth());
        }

        // —— 普通攻击：每 3 秒朝目标劈一道闪电 ——
        if (--lightningCooldown <= 0) {
            lightningCooldown = 60;
            strikeLightning(level);
        }

        // —— 二阶段起：裂隙不断孕育怪物 ——
        if (phase >= 1 && --summonCooldown <= 0) {
            summonCooldown = 120 + this.random.nextInt(60);      // 6~9 秒一只
            summonMinion(level);
        }

        // —— 相位二起：冲击波 ——
        if (phase >= 1 && --shockCooldown <= 0) {
            shockCooldown = phase >= 2 ? 120 : 160;
            shockwave(level);
        }

        // —— 相位三：裂隙一直在吸（没有间隔）+ 随机瞬移 + 朝玩家丢 TNT ——
        if (phase >= 2) {
            pull(level);
            if (--teleportCooldown <= 0) {
                teleportCooldown = 100 + this.random.nextInt(80);      // 5~9 秒一次
                blinkNear(level);
            }
            if (--tntCooldown <= 0) {
                tntCooldown = 50 + this.random.nextInt(40);            // 2.5~4.5 秒一颗
                throwTnt(level);
            }
        }

        // —— 被拽进裂隙的玩家送回主世界 ——
        devour(level);
    }

    private void onPhaseChange(ServerLevel level, int phase) {
        String name = switch (phase) {
            case 1 -> "§5相位二 · 撕裂";
            case 2 -> "§4相位三 · 坍缩";
            default -> "§b相位一 · 凝视";
        };
        for (ServerPlayer player : level.players()) {
            player.displayClientMessage(Component.literal("§d[裂隙意志] " + name), true);
            // ★ 每进入一个新相位，裂隙吞噬一次玩家身上的护甲
            devourArmor(level, player);
        }
        level.playSound((net.minecraft.world.entity.player.Player) null, this.blockPosition(), SoundEvents.WARDEN_ROAR,
                SoundSource.HOSTILE, 2.0F, 0.7F);
        level.sendParticles(ParticleTypes.FLASH, this.getX(), this.getY() + 1, this.getZ(), 1, 0, 0, 0, 0);
    }

    /**
     * ★ 裂隙吞噬护甲：把玩家四个护甲槽里的装备一次性卷走（清零），并给出提示与视听反馈。
     *
     * <p>只在<b>相位切换</b>时触发（由 {@link #onPhaseChange} 调用），所以一场战斗最多吞噬 2 次
     * （相位一→二、二→三）。
     *
     * @return 本次是否真的吞掉了至少一件护甲
     */
    private boolean devourArmor(ServerLevel level, ServerPlayer player) {
        EquipmentSlot[] slots = {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        };
        boolean devoured = false;
        for (EquipmentSlot slot : slots) {
            ItemStack worn = player.getItemBySlot(slot);
            if (worn.isEmpty()) continue;
            devoured = true;
            // 先给一份副本，用于让装备以物品形式掉在原地（玩家可以捡回来找回场子）
            ItemStack drop = worn.copy();
            player.setItemSlot(slot, ItemStack.EMPTY);
            player.drop(drop, false, false);   // 不随机偏移、不只在死亡时掉
        }
        if (!devoured) return false;

        // —— 提示与反馈 ——
        player.displayClientMessage(auroraText("你的护甲被裂隙吞噬了"), true);
        player.displayClientMessage(
                Component.literal("§5你的护甲被裂隙吞噬了 §7（捡回来还能再穿，但它会再吞一次）"), false);
        level.playSound((net.minecraft.world.entity.player.Player) null, player.blockPosition(), SoundEvents.WARDEN_HURT,
                SoundSource.PLAYERS, 1.4F, 0.6F);
        level.playSound((net.minecraft.world.entity.player.Player) null, player.blockPosition(), SoundEvents.ITEM_BREAK,
                SoundSource.PLAYERS, 1.0F, 0.5F);
        level.sendParticles(ParticleTypes.SCULK_SOUL,
                player.getX(), player.getY() + 1.0D, player.getZ(), 30, 0.4D, 0.7D, 0.4D, 0.04D);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                player.getX(), player.getY() + 1.0D, player.getZ(), 40, 0.5D, 0.8D, 0.5D, 0.15D);
        return true;
    }


    /** 冲击波：6 格内玩家被击飞 + 反胃 + 百分比伤害 */
    private void shockwave(ServerLevel level) {
        AABB box = this.getBoundingBox().inflate(6.0D);
        for (Player player : level.getEntitiesOfClass(Player.class, box)) {
            Vec3 push = player.position().subtract(this.position()).normalize().scale(1.6D).add(0, 0.9D, 0);
            player.push(push.x, push.y, push.z);
            player.hurtMarked = true;
            player.hurt(this.damageSources().indirectMagic(this, this),
                    Math.max(4.0F, player.getMaxHealth() * 0.12F));
            if (player instanceof ServerPlayer sp) {
                sp.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 0));
            }
        }
        level.sendParticles(ParticleTypes.EXPLOSION, this.getX(), this.getY() + 0.5, this.getZ(),
                24, 3.0, 0.5, 3.0, 0.0);
        // 【1.21 移植】playSound(Player, BlockPos, ...) 的首参类型收窄为 @Nullable Player，
        // 1.20.1 允许传 null 表示"所有玩家"，1.21 请改用 Entity 重载（this 即本 Boss）。
        // 【1.21 移植】SoundEvents 常量在 1.21 是 Holder<SoundEvent>，
        // 需要用 .value() 取出真正的 SoundEvent 再传给 playSound。
        level.playSound(this, this.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.5F, 0.8F);
    }

    /**
     * 裂隙拉扯：24 格内玩家被拉向自己。
     *
     * <p>★ 现在是<b>每 tick 都拉</b>（相位三没有间隔），所以单次力度调小，
     * 改成持续牵引 —— 合计约 3.6 格/秒，跑得掉前提是一直往反方向跑。
     */
    private void pull(ServerLevel level) {
        AABB box = this.getBoundingBox().inflate(24.0D);
        for (Player player : level.getEntitiesOfClass(Player.class, box)) {
            Vec3 dir = this.position().subtract(player.position());
            if (dir.lengthSqr() < 1.0E-4) continue;
            double d = dir.length();
            // ★ 吸引力已削弱：原来是固定 0.18/tick（≈3.6 格/秒），现在按距离衰减到 0.035~0.09
            //   （贴脸约 1.8 格/秒，拉到 24 格外只剩 0.8 格/秒），跑得掉、也不再一被吸就没法还手。
            double strength = Mth.clamp(0.09D - (d - 4.0D) * 0.0025D, 0.035D, 0.09D);
            Vec3 pull = dir.normalize().scale(strength).add(0, 0.015D, 0);
            player.push(pull.x, pull.y, pull.z);
            player.hurtMarked = true;
        }
        if (level.getGameTime() % 4 == 0) {
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, this.getX(), this.getY() + 1, this.getZ(),
                    40, 6.0, 3.0, 6.0, 0.4);
        }
    }

    // ------------------------------------------------------------------ 相位三：瞬移 / 丢 TNT

    /** 我们丢出去的 TNT 都带这个标签：爆炸时只打人不炸地形 */
    public static final String RIFT_TNT_TAG = "maixr_rift_tnt";

    /**
     * 在<b>随机一名玩家周围 50 格内</b>随机瞬移（12~50 格），落点挑"脚下实心 + 头顶两格空气"，
     * 免得卡进方块里或者掉进虚空。
     */
    private void blinkNear(ServerLevel level) {
        List<ServerPlayer> players = level.getEntitiesOfClass(ServerPlayer.class,
                this.getBoundingBox().inflate(64.0D));
        if (players.isEmpty()) return;
        ServerPlayer target = players.get(this.random.nextInt(players.size()));

        double angle = this.random.nextDouble() * Math.PI * 2.0D;
        double dist = 12.0D + this.random.nextDouble() * 38.0D;      // 12 ~ 50 格
        double x = target.getX() + Math.cos(angle) * dist;
        double z = target.getZ() + Math.sin(angle) * dist;

        int startY = Mth.floor(target.getY()) + 5;
        int y = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 20; i++) {
            int cy = startY - i;
            if (cy <= level.getMinBuildHeight() + 1) break;
            pos.set(x, cy, z);
            if (!level.getBlockState(pos).isAir()
                    && level.getBlockState(pos.above()).isAir()
                    && level.getBlockState(pos.above(2)).isAir()) {
                y = cy + 1;
                break;
            }
        }
        if (y == Integer.MIN_VALUE) y = Mth.floor(target.getY()) + 3;   // 找不到落脚点就悬在玩家上头

        level.sendParticles(ParticleTypes.REVERSE_PORTAL, this.getX(), this.getY() + 1.0D, this.getZ(),
                60, 0.7D, 0.7D, 0.7D, 0.35D);
        level.playSound((net.minecraft.world.entity.player.Player) null, this.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.HOSTILE, 1.8F, 0.5F);
        this.teleportTo(x, y, z);
        level.sendParticles(ParticleTypes.FLASH, x, y + 1.0D, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.SCULK_SOUL, x, y + 1.0D, z, 40, 0.6D, 0.6D, 0.6D, 0.06D);
        level.playSound((net.minecraft.world.entity.player.Player) null, BlockPos.containing(x, y, z), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.HOSTILE, 1.8F, 0.7F);
    }

    /** 朝目标抛物线丢一颗 TNT（带 {@link #RIFT_TNT_TAG}，爆炸只伤人不炸地形） */
    private void throwTnt(ServerLevel level) {
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) return;
        Vec3 from = this.position().add(0.0D, 1.2D, 0.0D);
        Vec3 to = target.position().add(0.0D, 1.0D, 0.0D);
        Vec3 dir = to.subtract(from);
        double d = dir.length();
        if (d < 0.5D) return;
        Vec3 v = dir.normalize().scale(Math.min(2.0D, 0.55D + d * 0.05D))
                .add(0.0D, 0.18D + d * 0.018D, 0.0D);

        PrimedTnt tnt = new PrimedTnt(level, from.x, from.y, from.z, null);
        tnt.setFuse(60);
        tnt.setDeltaMovement(v);
        tnt.addTag(RIFT_TNT_TAG);
        level.addFreshEntity(tnt);
        level.playSound((net.minecraft.world.entity.player.Player) null, this.blockPosition(), SoundEvents.TNT_PRIMED,
                SoundSource.HOSTILE, 1.3F, 1.0F);
        if (target instanceof ServerPlayer sp) {
            sp.displayClientMessage(Component.literal("§5[裂隙] 朝你扔来一颗裂隙爆裂物！"), true);
        }
    }

    /** 普通攻击：朝当前目标脚下劈一道闪电 */
    private void strikeLightning(ServerLevel level) {
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) return;
        net.minecraft.world.entity.LightningBolt bolt =
                EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) return;
        bolt.moveTo(Vec3.atBottomCenterOf(target.blockPosition()));
        if (target instanceof ServerPlayer sp) {
            bolt.setCause(sp);
        }
        level.addFreshEntity(bolt);
        level.playSound((net.minecraft.world.entity.player.Player) null, target.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER,
                SoundSource.HOSTILE, 1.4F, 1.0F);
    }

    /** 二阶段起：裂隙孕育一只怪物，并弹出提示 */
    private static final String[] MINIONS = {
            "minecraft:wither", "minecraft:ravager", "minecraft:evoker",
            "minecraft:vindicator", "minecraft:blaze", "minecraft:wither_skeleton",
            "minecraft:enderman", "minecraft:phantom"
    };

    private void summonMinion(ServerLevel level) {
        String id = MINIONS[this.random.nextInt(MINIONS.length)];
        EntityType<?> type = EntityType.byString(id).orElse(null);
        if (type == null) return;
        Entity created = type.create(level);
        if (!(created instanceof Mob mob)) return;
        double angle = this.random.nextDouble() * Math.PI * 2;
        mob.moveTo(this.getX() + Math.cos(angle) * 6.0D, this.getY() + 1.0D,
                this.getZ() + Math.sin(angle) * 6.0D, this.random.nextFloat() * 360F, 0F);
        mob.setPersistenceRequired();
        level.addFreshEntity(mob);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, mob.getX(), mob.getY() + 1, mob.getZ(),
                50, 1.2, 1.2, 1.2, 0.05);
        level.playSound((net.minecraft.world.entity.player.Player) null, mob.blockPosition(), SoundEvents.WITHER_SPAWN,
                SoundSource.HOSTILE, 0.8F, 1.4F);
        String name = mob.getDisplayName().getString();
        for (ServerPlayer player : level.players()) {
            player.displayClientMessage(Component.literal("§5§l[裂隙] §r§5裂隙孕育了 §d" + name), false);
        }
        MaixrMod.LOGGER.info("[裂隙意志] 裂隙孕育了 {} -> 成功", name);
    }

    // ------------------------------------------------------------------ 血条与"唯一一只 Boss"

    /**
     * ★ 为什么这里<b>没有</b>原版 {@code ServerBossEvent} 了：
     *
     * <p>它以前是本类的一个字段，问题有两个：
     * <ol>
     *   <li><b>残影血条</b>：{@code ServerBossEvent} 的 UUID 是实例级的，实体一旦被重新构造
     *       （区块卸载重载、维度重载、抉择超时后重生），就会用一个<b>新 UUID</b> 再发一次"加血条"，
     *       而旧那条（旧 UUID）没有任何人发"移除" —— 客户端就把它们一条条叠起来，
     *       实战里出现过屏幕上同时挂 6 条一模一样血条。</li>
     *   <li>它就是玩家看到的"原版风格紫色血条"。现在血条只用我们自己的极光条。</li>
     * </ol>
     *
     * <p>所以：血条完全交给 {@code MaixrNetwork.sendRiftBossBar}，并在
     * {@link #remove(RemovalReason)} 与 {@link #die} 里明确收掉。
     */

    /** 覆盖整个世界的搜索盒（只用来找同一维度里的同类实体） */
    private static final AABB EVERYWHERE =
            new AABB(-3.0E7D, -512.0D, -3.0E7D, 3.0E7D, 512.0D, 3.0E7D);

    /** 这个维度里还有别的活着的弧光 Boss 吗 */
    private static boolean otherBossAlive(ServerLevel level, ArcLightBoss self) {
        for (ArcLightBoss other : level.getEntitiesOfClass(ArcLightBoss.class, EVERYWHERE)) {
            if (other != self && other.isAlive()) return true;
        }
        return false;
    }

    /**
     * 清掉这个维度里所有已存在的弧光 Boss —— <b>每次召唤新 Boss 之前都要先调用</b>，
     * 保证世界上永远只有一只、也就永远只有一条血条。
     *
     * @return 被清掉的数量
     */
    public static int clearExisting(ServerLevel level) {
        int n = 0;
        for (ArcLightBoss old : level.getEntitiesOfClass(ArcLightBoss.class, EVERYWHERE)) {
            old.discard();
            n++;
        }
        if (n > 0) MaixrMod.LOGGER.info("[弧光] 召唤前清理旧 Boss {} 只（保证只有一个血条）", n);
        return n;
    }

    /** 实体被移除（卸载 / discard / 换维度）时，如果它是最后一只，就把血条收掉 */
    @Override
    public void remove(RemovalReason reason) {
        boolean hideBar = false;
        if (this.level() instanceof ServerLevel serverLevel) {
            hideBar = !otherBossAlive(serverLevel, this);
        }
        super.remove(reason);
        if (hideBar) {
            MaixrNetwork.sendRiftBossBar(0F, 0, false, 0F, 0F);
        }
    }

    /**
     * 被拽进裂隙 = 被丢回主世界。
     *
     * <p>★ 只有<b>相位三</b>（坍缩）在吸人时才会触发 —— 否则它一追上来玩家就被送走，根本没法打。
     */
    private void devour(ServerLevel level) {
        if (phase() < 2) return;
        AABB box = this.getBoundingBox().inflate(DEVOUR_RANGE);
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, box)) {
            ServerLevel overworld = level.getServer().getLevel(Level.OVERWORLD);
            if (overworld == null) continue;
            net.minecraft.core.BlockPos spawn = overworld.getSharedSpawnPos();
            player.displayClientMessage(Component.literal("§5§o裂隙把你吐了出来……"), false);
            player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5,
                    player.getYRot(), player.getXRot());
            level.playSound((net.minecraft.world.entity.player.Player) null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                    SoundSource.PLAYERS, 1.5F, 0.5F);
        }
    }

    // ------------------------------------------------------------------ 死亡

    @Override
    public void die(DamageSource source) {
        if (this.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, this.getX(), this.getY() + 1, this.getZ(),
                    8, 2, 2, 2, 0);
            level.playSound((net.minecraft.world.entity.player.Player) null, this.blockPosition(), SoundEvents.ENDER_DRAGON_DEATH,
                    SoundSource.HOSTILE, 2.0F, 0.7F);
            MaixrNetwork.sendRiftBossBar(0F, 2, false, 0F, 0F);   // 收掉血条
            // 开一个 30 秒的限时抉择窗口；期间没人选，祂会满血回来
            MaixrRiftHandler.openChoiceWindow(level, this.position());
        }
        super.die(source);
    }
}
