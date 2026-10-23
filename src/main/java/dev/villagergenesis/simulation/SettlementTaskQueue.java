package dev.villagergenesis.simulation;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Persistent, data-only queue for settlement lifecycle work.
 *
 * This queue records planning intent and progress only. It never loads chunks, reads terrain,
 * consumes inventories, places blocks, or creates entities. Real construction must be implemented
 * separately after terrain-query safety has been validated.
 */
public final class SettlementTaskQueue extends SavedData {
    private static final String DATA_NAME = "villager_genesis_settlement_tasks";
    private static final Factory<SettlementTaskQueue> FACTORY =
            new Factory<>(SettlementTaskQueue::new, SettlementTaskQueue::load, DataFixTypes.LEVEL);

    public enum Type { PLAN_SETTLEMENT }
    public enum Status { PENDING, IN_PROGRESS, COMPLETE, FAILED }

    public static final class Task {
        private final UUID id;
        private final UUID nodeId;
        private final Type type;
        private Status status;
        private int progress;
        private int attempts;
        private long createdAt;
        private long updatedAt;

        private Task(UUID id, UUID nodeId, Type type, Status status, int progress,
                     int attempts, long createdAt, long updatedAt) {
            this.id = id;
            this.nodeId = nodeId;
            this.type = type;
            this.status = status;
            this.progress = clamp(progress, 0, 100);
            this.attempts = Math.max(0, attempts);
            this.createdAt = Math.max(0, createdAt);
            this.updatedAt = Math.max(this.createdAt, updatedAt);
        }

        public UUID id() { return id; }
        public UUID nodeId() { return nodeId; }
        public Type type() { return type; }
        public Status status() { return status; }
        public int progress() { return progress; }
        public int attempts() { return attempts; }
        public long createdAt() { return createdAt; }
        public long updatedAt() { return updatedAt; }
    }

    private final List<Task> tasks = new ArrayList<>();

    public static SettlementTaskQueue get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Adds one pending planning task unless the same node already has an active task of this type. */
    public Task enqueueUnique(UUID nodeId, Type type, long gameTime) {
        for (Task task : tasks) {
            if (task.nodeId.equals(nodeId) && task.type == type
                    && (task.status == Status.PENDING || task.status == Status.IN_PROGRESS)) {
                return task;
            }
        }
        Task task = new Task(UUID.randomUUID(), nodeId, type, Status.PENDING, 0, 0,
                gameTime, gameTime);
        tasks.add(task);
        setDirty();
        return task;
    }

    /** Returns the oldest pending task without changing its state. */
    public Task peekPending() {
        return tasks.stream()
                .filter(task -> task.status == Status.PENDING)
                .min(Comparator.comparingLong(Task::createdAt).thenComparing(task -> task.id.toString()))
                .orElse(null);
    }

    /** Updates persisted progress. Callers must explicitly choose the new status. */
    public boolean update(UUID taskId, Status status, int progress, long gameTime) {
        for (Task task : tasks) {
            if (!task.id.equals(taskId)) continue;
            task.status = status;
            task.progress = clamp(progress, 0, 100);
            if (status == Status.IN_PROGRESS) task.attempts++;
            task.updatedAt = Math.max(task.updatedAt, gameTime);
            setDirty();
            return true;
        }
        return false;
    }

    public List<Task> tasks() {
        return Collections.unmodifiableList(new ArrayList<>(tasks));
    }

    public static SettlementTaskQueue load(CompoundTag tag, HolderLookup.Provider registries) {
        SettlementTaskQueue queue = new SettlementTaskQueue();
        ListTag list = tag.getList("tasks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("id") || !entry.hasUUID("node")) continue;
            Type type = enumValue(Type.values(), entry.getInt("type"), Type.PLAN_SETTLEMENT);
            Status status = enumValue(Status.values(), entry.getInt("status"), Status.PENDING);
            queue.tasks.add(new Task(entry.getUUID("id"), entry.getUUID("node"), type, status,
                    entry.getInt("progress"), entry.getInt("attempts"),
                    entry.getLong("created"), entry.getLong("updated")));
        }
        return queue;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Task task : tasks) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", task.id);
            entry.putUUID("node", task.nodeId);
            entry.putInt("type", task.type.ordinal());
            entry.putInt("status", task.status.ordinal());
            entry.putInt("progress", task.progress);
            entry.putInt("attempts", task.attempts);
            entry.putLong("created", task.createdAt);
            entry.putLong("updated", task.updatedAt);
            list.add(entry);
        }
        tag.put("tasks", list);
        return tag;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static <T> T enumValue(T[] values, int ordinal, T fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }
}
