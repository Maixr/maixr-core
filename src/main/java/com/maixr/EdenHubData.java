package com.maixr;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** 伊甸枢纽总站的维度级持久数据（保证整座建筑只生成一次）。 */
public class EdenHubData extends SavedData {
    private static final String DATA_NAME = "maixr_eden_hub";

    public boolean hubPlaced = false;
    /** 已经看过"欢迎回家"运镜的玩家（服务端统一记录，换号/清数据都不受影响） */
    public final java.util.Set<java.util.UUID> cinePlayed = new java.util.HashSet<>();

    public static EdenHubData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new net.minecraft.world.level.saveddata.SavedData.Factory<>(EdenHubData::new, EdenHubData::load), DATA_NAME);
    }

    public static EdenHubData load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        EdenHubData data = new EdenHubData();
        data.hubPlaced = tag.getBoolean("HubPlaced");
        net.minecraft.nbt.ListTag list = tag.getList("CinePlayed", 8);   // 8 = String
        for (int i = 0; i < list.size(); i++) {
            try { data.cinePlayed.add(java.util.UUID.fromString(list.getString(i))); } catch (Throwable ignored) {}
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        tag.putBoolean("HubPlaced", this.hubPlaced);
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (java.util.UUID id : this.cinePlayed) list.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        tag.put("CinePlayed", list);
        return tag;
    }
}
