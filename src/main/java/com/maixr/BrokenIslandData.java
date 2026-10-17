package com.maixr;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** 破碎世界开局空岛的维度级持久数据（保证岛只放一次，重启也不会重复放）。 */
public class BrokenIslandData extends SavedData {
    private static final String DATA_NAME = "maixr_broken_island";

    public boolean islandPlaced = false;

    public static BrokenIslandData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(BrokenIslandData::new, BrokenIslandData::load), DATA_NAME);
    }

    public static BrokenIslandData load(CompoundTag tag, HolderLookup.Provider registries) {
        BrokenIslandData data = new BrokenIslandData();
        data.islandPlaced = tag.getBoolean("IslandPlaced");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("IslandPlaced", this.islandPlaced);
        return tag;
    }
}
