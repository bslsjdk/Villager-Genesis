package dev.villagergenesis.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server-thread task leases shared by settlement work goals.
 *
 * Claims are deliberately runtime-only: losing them on restart is safe because a claim is not
 * a resource reservation and every goal revalidates the real world before changing it. A worker
 * renews its lease while ticking; if it unloads or stops ticking, another worker can reclaim the
 * target after the lease expires. No chunk is loaded by this manager.
 */
public final class SettlementTaskManager {
    private static final long LEASE_TICKS = 100L;
    private static final Map<ServerLevel, SettlementTaskManager> LEVEL_MANAGERS = new WeakHashMap<>();

    private final Map<TaskKey, SettlementTask> tasks = new HashMap<>();
    private final Map<UUID, TaskKey> ownerTasks = new HashMap<>();

    private SettlementTaskManager() {}

    public enum TaskType {
        LOGGING,
        MINING
    }

    public enum TaskStatus {
        PENDING,
        CLAIMED,
        COMPLETED,
        FAILED
    }

    /** Immutable task identity plus mutable lease lifecycle, owned by the server thread. */
    private static final class SettlementTask {
        private final UUID taskId = UUID.randomUUID();
        private final TaskKey key;
        private TaskStatus status = TaskStatus.PENDING;
        private UUID claimedBy;
        private long expiresAt;

        private SettlementTask(TaskKey key) {
            this.key = key;
        }
    }

    record TaskKey(TaskType type, long packedTarget) {}

    private static SettlementTaskManager forLevel(ServerLevel level) {
        synchronized (LEVEL_MANAGERS) {
            return LEVEL_MANAGERS.computeIfAbsent(level, ignored -> new SettlementTaskManager());
        }
    }

    /**
     * Atomically claims a target for one villager. Since callers run on the logical server thread,
     * this check-and-set cannot interleave with another goal tick.
     */
    public static boolean tryClaim(ServerLevel level, UUID villagerId, TaskType type,
                                   BlockPos target, long gameTime) {
        if (level == null || villagerId == null || type == null || target == null) return false;
        return forLevel(level).tryClaim(villagerId, type, target, gameTime);
    }

    private boolean tryClaim(UUID villagerId, TaskType type, BlockPos target, long now) {
        pruneExpired(now);
        TaskKey key = new TaskKey(type, target.asLong());
        TaskKey currentOwnerTask = ownerTasks.get(villagerId);
        if (currentOwnerTask != null && !currentOwnerTask.equals(key)) return false;

        SettlementTask task = tasks.computeIfAbsent(key, SettlementTask::new);
        if (task.status == TaskStatus.CLAIMED && !villagerId.equals(task.claimedBy)) return false;
        task.status = TaskStatus.CLAIMED;
        task.claimedBy = villagerId;
        task.expiresAt = now + LEASE_TICKS;
        ownerTasks.put(villagerId, key);
        return true;
    }

    /** Renews a live lease; false means the caller no longer owns the target. */
    public static boolean renew(ServerLevel level, UUID villagerId, TaskType type,
                                BlockPos target, long gameTime) {
        if (level == null || villagerId == null || type == null || target == null) return false;
        return forLevel(level).renew(villagerId, type, target, gameTime);
    }

    private boolean renew(UUID villagerId, TaskType type, BlockPos target, long now) {
        pruneExpired(now);
        TaskKey key = new TaskKey(type, target.asLong());
        SettlementTask task = tasks.get(key);
        if (task == null || task.status != TaskStatus.CLAIMED
                || !villagerId.equals(task.claimedBy)) return false;
        task.expiresAt = now + LEASE_TICKS;
        return true;
    }

    /** Releases only the matching owner's lease, never another villager's claim. */
    public static void release(ServerLevel level, UUID villagerId, TaskType type, BlockPos target) {
        if (level == null || villagerId == null || type == null || target == null) return;
        forLevel(level).release(villagerId, new TaskKey(type, target.asLong()));
    }

    private void release(UUID villagerId, TaskKey key) {
        SettlementTask task = tasks.get(key);
        if (task == null || !villagerId.equals(task.claimedBy)) return;
        task.status = TaskStatus.PENDING;
        task.claimedBy = null;
        task.expiresAt = 0L;
        tasks.remove(key);
        ownerTasks.remove(villagerId, key);
    }

    /** Death cleanup is immediate; unloads are handled by the lease timeout. */
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        UUID owner = entity.getUUID();
        synchronized (LEVEL_MANAGERS) {
            for (SettlementTaskManager manager : LEVEL_MANAGERS.values()) {
                manager.releaseOwner(owner);
            }
        }
    }

    private void releaseOwner(UUID owner) {
        TaskKey key = ownerTasks.remove(owner);
        if (key == null) return;
        SettlementTask task = tasks.get(key);
        if (task != null && owner.equals(task.claimedBy)) {
            task.status = TaskStatus.PENDING;
            task.claimedBy = null;
            task.expiresAt = 0L;
            tasks.remove(key);
        }
    }

    private void pruneExpired(long now) {
        Iterator<Map.Entry<TaskKey, SettlementTask>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<TaskKey, SettlementTask> entry = iterator.next();
            SettlementTask task = entry.getValue();
            if (task.status == TaskStatus.CLAIMED && now >= task.expiresAt) {
                if (task.claimedBy != null) ownerTasks.remove(task.claimedBy, task.key);
                task.status = TaskStatus.PENDING;
                task.claimedBy = null;
                iterator.remove();
            }
        }
    }
}
