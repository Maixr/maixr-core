package com.maixr;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 裂隙抉择的长期后果：谁选了「抗下诅咒」，谁选了「自我凋零」。 */
public class MaixrCurses extends SavedData {
    private static final String DATA_NAME = "maixr_rift_curses";

    /** 背负诅咒的玩家（永久） */
    public final Set<UUID> cursed = new HashSet<>();
    /** 自我凋零过的玩家 */
    public final Set<UUID> sacrificed = new HashSet<>();

    public static MaixrCurses get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new net.minecraft.world.level.saveddata.SavedData.Factory<>(MaixrCurses::new, MaixrCurses::load), DATA_NAME);
    }

    public static MaixrCurses load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        MaixrCurses d = new MaixrCurses();
        read(tag.getList("Cursed", Tag.TAG_STRING), d.cursed);
        read(tag.getList("Sacrificed", Tag.TAG_STRING), d.sacrificed);
        return d;
    }

    private static void read(ListTag list, Set<UUID> into) {
        for (int i = 0; i < list.size(); i++) {
            try { into.add(UUID.fromString(list.getString(i))); } catch (Throwable ignored) {}
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        tag.put("Cursed", write(cursed));
        tag.put("Sacrificed", write(sacrificed));
        return tag;
    }

    private static ListTag write(Set<UUID> set) {
        ListTag list = new ListTag();
        for (UUID id : set) list.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
        return list;
    }

    public boolean isCursed(UUID id) { return cursed.contains(id); }

    public void addCurse(UUID id) { cursed.add(id); setDirty(); }

    public void addSacrifice(UUID id) { sacrificed.add(id); setDirty(); }
}
