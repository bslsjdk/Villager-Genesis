package dev.villagergenesis.ai;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.util.datafix.DataFixTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent ownership ledger for structures placed by Villager Genesis.
 *
 * This is deliberately not an inventory: it stores only ownership metadata. Every registered
 * position must correspond to a real block in the world. The ledger is dimension-local SavedData,
 * survives world saves/restarts, and is the safety boundary future demolition/rebuild jobs must use.
 */
public final class VillageStructureRegistry extends SavedData {
    private static final String DATA_NAME = "villager_genesis_structures";
    private static final String ENTRIES = "structures";
    private static final Factory<VillageStructureRegistry> FACTORY =
            new Factory<>(VillageStructureRegistry::new, VillageStructureRegistry::load, DataFixTypes.LEVEL);

    public record OwnedBlock(BlockPos pos, String structureType, UUID builder, long placedAt) {
        public OwnedBlock {
            pos = pos.immutable();
            if (structureType == null || structureType.isBlank()) {
                throw new IllegalArgumentException("Structure type must not be blank.");
            }
        }
    }

    private final Map<Long, OwnedBlock> ownedBlocks = new HashMap<>();

    public VillageStructureRegistry() {}

    public static VillageStructureRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * Registers a block only after its placement succeeded. A position already owned by another
     * structure is never silently overwritten.
     */
    public boolean registerPlacedBlock(BlockPos pos, String structureType, UUID builder) {
        long key = pos.asLong();
        OwnedBlock existing = ownedBlocks.get(key);
        if (existing != null) {
            return existing.structureType().equals(structureType)
                    && existing.builder().equals(builder);
        }
        ownedBlocks.put(key, new OwnedBlock(pos, structureType, builder, 0L));
        setDirty();
        return true;
    }

    public boolean isOwned(BlockPos pos) {
        return ownedBlocks.containsKey(pos.asLong());
    }

    public boolean isOwnedBy(BlockPos pos, String structureType) {
        OwnedBlock entry = ownedBlocks.get(pos.asLong());
        return entry != null && entry.structureType().equals(structureType);
    }

    public boolean unregister(BlockPos pos, String expectedStructureType) {
        OwnedBlock existing = ownedBlocks.get(pos.asLong());
        if (existing == null || !existing.structureType().equals(expectedStructureType)) return false;
        ownedBlocks.remove(pos.asLong());
        setDirty();
        return true;
    }

    public List<OwnedBlock> blocksOfType(String structureType) {
        List<OwnedBlock> result = new ArrayList<>();
        for (OwnedBlock block : ownedBlocks.values()) {
            if (block.structureType().equals(structureType)) result.add(block);
        }
        return Collections.unmodifiableList(result);
    }

    public static VillageStructureRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        VillageStructureRegistry data = new VillageStructureRegistry();
        ListTag list = tag.getList(ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("builder") || !entry.contains("pos", Tag.TAG_LONG)
                    || !entry.contains("type", Tag.TAG_STRING)) continue;
            BlockPos pos = BlockPos.of(entry.getLong("pos"));
            String type = entry.getString("type");
            if (type.isBlank()) continue;
            UUID builder = entry.getUUID("builder");
            long placedAt = entry.getLong("placed_at");
            data.ownedBlocks.put(pos.asLong(), new OwnedBlock(pos, type, builder, placedAt));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (OwnedBlock block : ownedBlocks.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("pos", block.pos().asLong());
            entry.putString("type", block.structureType());
            entry.putUUID("builder", block.builder());
            entry.putLong("placed_at", block.placedAt());
            list.add(entry);
        }
        tag.put(ENTRIES, list);
        return tag;
    }
}
