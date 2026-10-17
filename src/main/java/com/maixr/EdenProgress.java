package com.maixr;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 伊甸工程 + 世界状态机的持久数据（存在 maixr:overworld_broken 维度）。
 *
 * <p>模型（改版后）：
 * <ul>
 *   <li><b>进度由提交驱动</b>：每提交一次 +1%（10‰），不再按区块数换算</li>
 *   <li><b>地形按圆心扩散</b>：以 (0,0) 为圆心一圈圈向外，环宽 {@link EdenRepair#RING_WIDTH} 格</li>
 *   <li><b>每提交 20 次开一次入侵</b>，没打完不能继续</li>
 *   <li><b>99%→100% 那一次必须交复苏草方块</b></li>
 * </ul>
 */
public class EdenProgress extends SavedData {
    private static final String DATA_NAME = "maixr_eden_progress";

    public static final int MAX = 1000;
    public static final int STAGE_SIZE = 100;
    public static final int STAGE_COUNT = 10;
    public static final int PER_SUBMIT = 10;

    public int submits = 0;
    public int decay = 0;
    public int budget = 0;

    public int ringRadius = 0;
    public int ringChunkIndex = 0;
    public long currentChunk = -1L;
    public int cursor = 0;
    public long filledColumns = 0L;

    /** 90% 的最终 Boss 是否已经刷过（一辈子只刷一次） */
    public boolean bossSpawned = false;
    public boolean invasionActive = false;
    public int invasionWave = 0;
    public int invasionMaxWave = 3;
    public long lastInvasionTick = 0L;
    public final Set<UUID> invasionMobs = new HashSet<>();

    public boolean finished = false;
    public boolean platformPlaced = false;
    /** 修复圆心的方块坐标（第一次加载时挑一个平原 + 非虚空的区块定下来） */
    public int centerX = 0;
    public int centerZ = 0;
    /** 圆心那块地真正的地面 y（平台就摆在这个高度上；-1 = 还没量过） */
    public int centerY = -1;
    public boolean centerChosen = false;
    /**
     * 圆心选取逻辑的版本号。
     * <p>v1 = 老逻辑（会在没加载的区块上读高度图，于是一路读到世界最底下 -64）。
     * v2 = 先真正加载区块再量高度、并且量到虚空就换地方。
     * <p>存档里存的版本号比当前低 → 下次开服自动重新锚定圆心。
     */
    public int centerVersion = 0;
    /** 上一次广播过的纪元（用来检测跨档） */
    public int stage = 0;
    /** 里程碑成就位掩码：bit0=10% bit1=50% bit2=90% */
    public int milestones = 0;
    /** 已经打完过入侵的纪元位掩码（衰败退回去再升上来不会重打） */
    public int invadedStages = 0;

    public final Map<UUID, Integer> contribution = new HashMap<>();
    public final Map<UUID, String> uuidNames = new HashMap<>();

    public String requiredItem = "";
    public int requiredCount = 16;
    public int requestRoll = 0;

    public UUID lastContributor = null;
    public String lastContributorName = "";

    public static EdenProgress get(ServerLevel brokenLevel) {
        return brokenLevel.getDataStorage()
                .computeIfAbsent(new net.minecraft.world.level.saveddata.SavedData.Factory<>(EdenProgress::new, EdenProgress::load), DATA_NAME);
    }

    public static EdenProgress load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        EdenProgress d = new EdenProgress();
        d.submits = tag.getInt("Submits");
        d.decay = tag.getInt("Decay");
        d.budget = tag.getInt("Budget");
        d.ringRadius = tag.getInt("RingRadius");
        d.ringChunkIndex = tag.getInt("RingChunkIndex");
        d.currentChunk = tag.contains("CurrentChunk") ? tag.getLong("CurrentChunk") : -1L;
        d.cursor = tag.getInt("Cursor");
        d.filledColumns = tag.getLong("FilledColumns");
        d.bossSpawned = tag.getBoolean("BossSpawned");
        d.invasionActive = tag.getBoolean("InvasionActive");
        d.invasionWave = tag.getInt("InvasionWave");
        d.invasionMaxWave = tag.contains("InvasionMaxWave") ? tag.getInt("InvasionMaxWave") : 3;
        d.lastInvasionTick = tag.getLong("LastInvasionTick");
        d.finished = tag.getBoolean("Finished");
        d.platformPlaced = tag.getBoolean("PlatformPlaced");
        d.centerX = tag.getInt("CenterX");
        d.centerZ = tag.getInt("CenterZ");
        d.centerY = tag.contains("CenterY") ? tag.getInt("CenterY") : -1;
        d.centerChosen = tag.getBoolean("CenterChosen");
        d.centerVersion = tag.getInt("CenterVersion");
        d.stage = tag.getInt("Stage");
        d.milestones = tag.getInt("Milestones");
        d.invadedStages = tag.getInt("InvadedStages");
        d.requiredItem = tag.getString("RequiredItem");
        d.requiredCount = tag.contains("RequiredCount") ? tag.getInt("RequiredCount") : 16;
        d.requestRoll = tag.getInt("RequestRoll");
        if (tag.contains("LastContributor")) {
            try { d.lastContributor = UUID.fromString(tag.getString("LastContributor")); } catch (Throwable ignored) {}
        }
        d.lastContributorName = tag.getString("LastContributorName");

        ListTag mobs = tag.getList("InvasionMobs", Tag.TAG_STRING);
        for (int i = 0; i < mobs.size(); i++) {
            try { d.invasionMobs.add(UUID.fromString(mobs.getString(i))); } catch (Throwable ignored) {}
        }
        ListTag contrib = tag.getList("Contribution", Tag.TAG_COMPOUND);
        for (int i = 0; i < contrib.size(); i++) {
            CompoundTag c = contrib.getCompound(i);
            try {
                UUID id = UUID.fromString(c.getString("Id"));
                d.contribution.put(id, c.getInt("Count"));
                d.uuidNames.put(id, c.getString("Name"));
            } catch (Throwable ignored) {}
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        tag.putInt("Submits", submits);
        tag.putInt("Decay", decay);
        tag.putInt("Budget", budget);
        tag.putInt("RingRadius", ringRadius);
        tag.putInt("RingChunkIndex", ringChunkIndex);
        tag.putLong("CurrentChunk", currentChunk);
        tag.putInt("Cursor", cursor);
        tag.putLong("FilledColumns", filledColumns);
        tag.putBoolean("BossSpawned", bossSpawned);
        tag.putBoolean("InvasionActive", invasionActive);
        tag.putInt("InvasionWave", invasionWave);
        tag.putInt("InvasionMaxWave", invasionMaxWave);
        tag.putLong("LastInvasionTick", lastInvasionTick);
        tag.putBoolean("Finished", finished);
        tag.putBoolean("PlatformPlaced", platformPlaced);
        tag.putInt("CenterX", centerX);
        tag.putInt("CenterZ", centerZ);
        tag.putInt("CenterY", centerY);
        tag.putBoolean("CenterChosen", centerChosen);
        tag.putInt("CenterVersion", centerVersion);
        tag.putInt("Stage", stage);
        tag.putInt("Milestones", milestones);
        tag.putInt("InvadedStages", invadedStages);
        tag.putString("RequiredItem", requiredItem == null ? "" : requiredItem);
        tag.putInt("RequiredCount", requiredCount);
        tag.putInt("RequestRoll", requestRoll);
        tag.putString("LastContributor", lastContributor == null ? "" : lastContributor.toString());
        tag.putString("LastContributorName", lastContributorName == null ? "" : lastContributorName);

        ListTag mobs = new ListTag();
        for (UUID id : invasionMobs) mobs.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        tag.put("InvasionMobs", mobs);

        ListTag contrib = new ListTag();
        contribution.forEach((id, count) -> {
            CompoundTag c = new CompoundTag();
            c.putString("Id", id.toString());
            c.putString("Name", uuidNames.getOrDefault(id, "?"));
            c.putInt("Count", count);
            contrib.add(c);
        });
        tag.put("Contribution", contrib);
        return tag;
    }

    /** 完整度千分比：提交换来的进度 - 衰败量，锁死不低于 0 */
    public int completeness() {
        return Math.min(MAX, Math.max(0, submits * PER_SUBMIT - decay));
    }

    public int stageOf(int completeness) {
        return Math.min(STAGE_COUNT - 1, Math.max(0, completeness / STAGE_SIZE));
    }

    /**
     * 目标半径：每提交一次向外 1 格，每跨一个纪元再加 3 格，封顶 {@link EdenRepair#MAX_RADIUS}。
     * 100 次提交 + 9 次跨档 = 127 格，正好是「毕业圆」的大小。
     */
    public int targetRadius() {
        int stage = stageOf(completeness());
        return Math.min(EdenRepair.MAX_RADIUS, submits * EdenRepair.RADIUS_PER_SUBMIT
                + stage * EdenRepair.RADIUS_PER_STAGE);
    }

    /**
     * 还有没有没填完的环。
     *
     * <p>★ 改版：以前这里还有一道 {@code !invasionActive} —— 入侵没打完就不许修。
     * 入侵整套删掉之后这个闸门也删了：现在只有 90% 那只 Boss，它不拦着修复。
     */
    public boolean canRepair() {
        return ringRadius < targetRadius();
    }

    /** 99% 之后那一次必须交复苏草方块 */
    public boolean needsRevivedGrass() {
        int c = completeness();
        return c >= MAX - PER_SUBMIT && c < MAX;
    }

    public void addContribution(UUID player, String name) {
        contribution.merge(player, 1, Integer::sum);
        if (name != null && !name.isEmpty()) uuidNames.put(player, name);
        setDirty();
    }

    public void decayBy(int amount) {
        decay = Math.min(submits * PER_SUBMIT, Math.max(0, decay + amount));
        setDirty();
    }

    public void markDirty() { setDirty(); }
}
