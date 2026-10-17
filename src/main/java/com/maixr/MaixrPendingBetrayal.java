package com.maixr;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 待发放名单：被「双持寰宇支配之剑」击杀的玩家，复活后要把【原初背叛】戴到头盔栏。
 * 死亡那一刻记下 UUID，复活时消费掉。
 */
public class MaixrPendingBetrayal extends SavedData {
    private static final String DATA_NAME = "maixr_pending_betrayal";

    private final Set<UUID> pending = new HashSet<>();

    public static MaixrPendingBetrayal get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new net.minecraft.world.level.saveddata.SavedData.Factory<>(MaixrPendingBetrayal::new, MaixrPendingBetrayal::load), DATA_NAME);
    }

    private static MaixrPendingBetrayal load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        MaixrPendingBetrayal data = new MaixrPendingBetrayal();
        ListTag list = tag.getList("Pending", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            try {
                data.pending.add(UUID.fromString(list.getString(i)));
            } catch (IllegalArgumentException ignored) {
                // 脏数据跳过
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (UUID id : pending) {
            list.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        }
        tag.put("Pending", list);
        return tag;
    }

    public void mark(UUID player) {
        if (pending.add(player)) {
            setDirty();
        }
    }

    /** @return 该玩家是否在名单里；在的话顺手移除 */
    public boolean consume(UUID player) {
        if (pending.remove(player)) {
            setDirty();
            return true;
        }
        return false;
    }
}
