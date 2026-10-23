package dev.villagergenesis.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
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
 * Compact persistent state for Villager Genesis' future off-screen settlement simulation.
 *
 * Important: these records are planning state, not an inventory and not proof that blocks/items
 * exist. Resource values may only be committed after reconciling them with real world containers.
 * This class performs no chunk loading and never creates entities or blocks.
 */
public final class VillageSimulationData extends SavedData {
    private static final String DATA_NAME = "villager_genesis_simulation";
    private static final String SETTLEMENTS = "settlements";
    private static final Factory<VillageSimulationData> FACTORY =
            new Factory<>(VillageSimulationData::new, VillageSimulationData::load, DataFixTypes.LEVEL);

    public enum Mode {
        ABSTRACT, MATERIALIZING, LIVE, SETTLING
    }

    public static final class Settlement {
        private final UUID id;
        private final String dimension;
        private BlockPos anchor;
        private long lastSimulatedGameTime;
        private long nextWorkGameTime;
        private int populationEstimate;
        private int foodEstimate;
        private int storedMaterialsEstimate;
        private int knowledgeFlags;
        private int pendingWork;
        private Mode mode;

        private Settlement(UUID id, String dimension, BlockPos anchor, long gameTime) {
            this.id = id;
            this.dimension = dimension;
            this.anchor = anchor.immutable();
            this.lastSimulatedGameTime = gameTime;
            this.nextWorkGameTime = gameTime;
            this.mode = Mode.ABSTRACT;
        }

        public UUID id() { return id; }
        public String dimension() { return dimension; }
        public BlockPos anchor() { return anchor; }
        public long lastSimulatedGameTime() { return lastSimulatedGameTime; }
        public long nextWorkGameTime() { return nextWorkGameTime; }
        public int populationEstimate() { return populationEstimate; }
        public int foodEstimate() { return foodEstimate; }
        public int storedMaterialsEstimate() { return storedMaterialsEstimate; }
        public int knowledgeFlags() { return knowledgeFlags; }
        public int pendingWork() { return pendingWork; }
        public Mode mode() { return mode; }

        public void refreshAnchor(BlockPos newAnchor) {
            anchor = newAnchor.immutable();
        }

        public void setMode(Mode newMode) {
            mode = newMode;
        }

        /**
         * Updates only simulation metadata. Callers must not interpret estimates as real items.
         * Simulation steps are intentionally explicit and bounded by the scheduler.
         */
        public void advanceClock(long gameTime, long nextWorkTime) {
            if (gameTime > lastSimulatedGameTime) lastSimulatedGameTime = gameTime;
            nextWorkGameTime = Math.max(gameTime, nextWorkTime);
        }

        public void updateEstimates(int population, int food, int materials, int knowledge, int work) {
            populationEstimate = Math.max(0, population);
            foodEstimate = Math.max(0, food);
            storedMaterialsEstimate = Math.max(0, materials);
            knowledgeFlags = Math.max(0, knowledge);
            pendingWork = Math.max(0, work);
        }
    }

    private final Map<UUID, Settlement> settlements = new HashMap<>();

    public static VillageSimulationData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Settlement getOrCreate(UUID id, String dimension, BlockPos anchor, long gameTime) {
        Settlement existing = settlements.get(id);
        if (existing != null) {
            if (!existing.dimension.equals(dimension)) {
                throw new IllegalArgumentException("Settlement dimension cannot change.");
            }
            existing.refreshAnchor(anchor);
            return existing;
        }
        Settlement created = new Settlement(id, dimension, anchor, gameTime);
        settlements.put(id, created);
        setDirty();
        return created;
    }

    public Settlement find(UUID id) {
        return settlements.get(id);
    }

    public List<Settlement> settlements() {
        return Collections.unmodifiableList(new ArrayList<>(settlements.values()));
    }

    public void markChanged() {
        setDirty();
    }

    public static VillageSimulationData load(CompoundTag tag, HolderLookup.Provider registries) {
        VillageSimulationData data = new VillageSimulationData();
        ListTag list = tag.getList(SETTLEMENTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("id") || !entry.contains("dimension", Tag.TAG_STRING)
                    || !entry.contains("anchor", Tag.TAG_LONG)) continue;
            UUID id = entry.getUUID("id");
            String dimension = entry.getString("dimension");
            if (dimension.isBlank()) continue;
            Settlement settlement = new Settlement(id, dimension,
                    BlockPos.of(entry.getLong("anchor")), entry.getLong("last_simulated"));
            settlement.nextWorkGameTime = Math.max(settlement.lastSimulatedGameTime,
                    entry.getLong("next_work"));
            settlement.populationEstimate = Math.max(0, entry.getInt("population"));
            settlement.foodEstimate = Math.max(0, entry.getInt("food"));
            settlement.storedMaterialsEstimate = Math.max(0, entry.getInt("materials"));
            settlement.knowledgeFlags = Math.max(0, entry.getInt("knowledge"));
            settlement.pendingWork = Math.max(0, entry.getInt("pending_work"));
            int mode = entry.getInt("mode");
            settlement.mode = mode >= 0 && mode < Mode.values().length
                    ? Mode.values()[mode] : Mode.ABSTRACT;
            data.settlements.put(id, settlement);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Settlement settlement : settlements.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", settlement.id);
            entry.putString("dimension", settlement.dimension);
            entry.putLong("anchor", settlement.anchor.asLong());
            entry.putLong("last_simulated", settlement.lastSimulatedGameTime);
            entry.putLong("next_work", settlement.nextWorkGameTime);
            entry.putInt("population", settlement.populationEstimate);
            entry.putInt("food", settlement.foodEstimate);
            entry.putInt("materials", settlement.storedMaterialsEstimate);
            entry.putInt("knowledge", settlement.knowledgeFlags);
            entry.putInt("pending_work", settlement.pendingWork);
            entry.putInt("mode", settlement.mode.ordinal());
            list.add(entry);
        }
        tag.put(SETTLEMENTS, list);
        return tag;
    }
}
