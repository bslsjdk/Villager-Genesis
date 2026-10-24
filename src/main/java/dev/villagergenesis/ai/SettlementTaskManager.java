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
 * Claims are runtime-only and do not reserve resources. Goals must revalidate the real world
 * before changing it. The lease registry is deliberately isolated from Minecraft runtime state
 * so its lifecycle rules can be tested without starting a server.
 */
public final class SettlementTaskManager {
    static final long LEASE_TICKS = 100L;
    private static final Map<ServerLevel, SettlementTaskManager> LEVEL_MANAGERS = new WeakHashMap<>();

    private final LeaseRegistry registry = new LeaseRegistry();

    private SettlementTaskManager() {}

    public enum TaskType {
        LOGGING,
        MINING
    }

    record TaskKey(TaskType type, long packedTarget) {}

    private static SettlementTaskManager forLevel(ServerLevel level) {
        synchronized (LEVEL_MANAGERS) {
            return LEVEL_MANAGERS.computeIfAbsent(level, ignored -> new SettlementTaskManager());
        }
    }

    public static boolean tryClaim(ServerLevel level, UUID villagerId, TaskType type,
                                   BlockPos target, long gameTime) {
        if (level == null || villagerId == null || type == null || target == null) return false;
        return forLevel(level).registry.tryClaim(villagerId, type, target, gameTime);
    }

    public static boolean renew(ServerLevel level, UUID villagerId, TaskType type,
                                BlockPos target, long gameTime) {
        if (level == null || villagerId == null || type == null || target == null) return false;
        return forLevel(level).registry.renew(villagerId, type, target, gameTime);
    }

    public static void release(ServerLevel level, UUID villagerId, TaskType type, BlockPos target) {
        if (level == null || villagerId == null || type == null || target == null) return;
        forLevel(level).registry.release(villagerId, new TaskKey(type, target.asLong()));
    }

    /** Death cleanup is immediate; unloaded or unticked workers expire through the lease. */
    public static void onLivingDeath(LivingDeathEvent event) {
        UUID owner = event.getEntity().getUUID();
        synchronized (LEVEL_MANAGERS) {
            for (SettlementTaskManager manager : LEVEL_MANAGERS.values()) {
                manager.registry.releaseOwner(owner);
            }
        }
    }

    /**
     * Pure in-memory lease state machine. It intentionally knows nothing about levels, entities,
     * events, or world mutation. Callers are responsible for invoking it on the server thread.
     */
    static final class LeaseRegistry {
        private final Map<TaskKey, Lease> tasks = new HashMap<>();
        private final Map<UUID, TaskKey> ownerTasks = new HashMap<>();

        private static final class Lease {
            private final TaskKey key;
            private UUID owner;
            private long expiresAt;

            private Lease(TaskKey key, UUID owner, long expiresAt) {
                this.key = key;
                this.owner = owner;
                this.expiresAt = expiresAt;
            }
        }

        boolean tryClaim(UUID owner, TaskType type, BlockPos target, long now) {
            if (owner == null || type == null || target == null) return false;
            pruneExpired(now);
            TaskKey key = new TaskKey(type, target.asLong());
            TaskKey existingOwnerTask = ownerTasks.get(owner);
            if (existingOwnerTask != null && !existingOwnerTask.equals(key)) return false;

            Lease lease = tasks.get(key);
            if (lease != null && !owner.equals(lease.owner)) return false;
            if (lease == null) {
                lease = new Lease(key, owner, now + LEASE_TICKS);
                tasks.put(key, lease);
            } else {
                lease.expiresAt = now + LEASE_TICKS;
            }
            ownerTasks.put(owner, key);
            return true;
        }

        boolean renew(UUID owner, TaskType type, BlockPos target, long now) {
            if (owner == null || type == null || target == null) return false;
            pruneExpired(now);
            TaskKey key = new TaskKey(type, target.asLong());
            Lease lease = tasks.get(key);
            if (lease == null || !owner.equals(lease.owner)) return false;
            lease.expiresAt = now + LEASE_TICKS;
            return true;
        }

        void release(UUID owner, TaskKey key) {
            if (owner == null || key == null) return;
            Lease lease = tasks.get(key);
            if (lease == null || !owner.equals(lease.owner)) return;
            tasks.remove(key);
            ownerTasks.remove(owner, key);
        }

        void releaseOwner(UUID owner) {
            if (owner == null) return;
            TaskKey key = ownerTasks.remove(owner);
            if (key == null) return;
            Lease lease = tasks.get(key);
            if (lease != null && owner.equals(lease.owner)) tasks.remove(key);
        }

        boolean isClaimedBy(UUID owner, TaskType type, BlockPos target) {
            if (owner == null || type == null || target == null) return false;
            Lease lease = tasks.get(new TaskKey(type, target.asLong()));
            return lease != null && owner.equals(lease.owner);
        }

        int activeClaims() {
            return tasks.size();
        }

        private void pruneExpired(long now) {
            Iterator<Map.Entry<TaskKey, Lease>> iterator = tasks.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<TaskKey, Lease> entry = iterator.next();
                Lease lease = entry.getValue();
                if (now >= lease.expiresAt) {
                    ownerTasks.remove(lease.owner, lease.key);
                    iterator.remove();
                }
            }
        }
    }
}
