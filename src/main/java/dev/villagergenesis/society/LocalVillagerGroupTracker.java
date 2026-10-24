package dev.villagergenesis.society;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persistent, bottom-up tracker for groups formed by live villagers.
 *
 * It only examines entities already present in the ServerLevel entity iteration. It never searches
 * for vanilla villages, requests chunks, or mutates blocks. Membership is persisted by UUID so a
 * restart does not assign a new settlement identity to every group.
 *
 * This first integration deliberately records reliable population/return evidence only. A group
 * cannot be promoted to a camp or village until the live resource/bed/project survey is wired in.
 */
public final class LocalVillagerGroupTracker extends SavedData {
    private static final String DATA_NAME = "villager_genesis_local_groups";
    private static final Factory<LocalVillagerGroupTracker> FACTORY =
            new Factory<>(LocalVillagerGroupTracker::new, LocalVillagerGroupTracker::load,
                    DataFixTypes.LEVEL);

    private static final int SCAN_INTERVAL_TICKS = 40;
    private static final int MAX_GROUP_MEMBERS = 32;
    private static final int MAX_ENTITY_SCAN = 2_048;
    private static final double JOIN_RADIUS_SQR = 24.0D * 24.0D;
    private static final double SPLIT_RADIUS_SQR = 40.0D * 40.0D;
    private static final long FORGET_AFTER_TICKS = 7L * 24_000L;

    public static final class Group {
        private final UUID id;
        private final Set<UUID> members = new HashSet<>();
        private int x;
        private int z;
        private long firstObserved;
        private long lastObserved;
        private long lastResidentDay = -1;
        private int distinctResidentDays;
        private int consecutivePresenceScans;

        private Group(UUID id, int x, int z, long now) {
            this.id = id;
            this.x = x;
            this.z = z;
            this.firstObserved = now;
            this.lastObserved = now;
        }

        public UUID id() { return id; }
        public Set<UUID> members() { return Set.copyOf(members); }
        public int population() { return members.size(); }
        public int x() { return x; }
        public int z() { return z; }
        public long firstObserved() { return firstObserved; }
        public long lastObserved() { return lastObserved; }
        public int distinctResidentDays() { return distinctResidentDays; }
        public int consecutivePresenceScans() { return consecutivePresenceScans; }
    }

    private final Map<UUID, Group> groups = new HashMap<>();
    private final Map<UUID, UUID> groupByResident = new HashMap<>();
    private long nextScanAt;

    public static LocalVillagerGroupTracker get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /**
     * Run on the server-level scheduler. Entity enumeration is bounded by the already loaded
     * entity set; the extra cap prevents a dense test world from causing unbounded group work.
     */
    public int observeLoadedVillagers(ServerLevel level, long now, int maxVillagers) {
        if (maxVillagers <= 0 || now < nextScanAt) return 0;
        nextScanAt = now + SCAN_INTERVAL_TICKS;

        List<Villager> observed = new ArrayList<>();
        int inspectedEntities = 0;
        for (Entity entity : level.getAllEntities()) {
            if (++inspectedEntities > MAX_ENTITY_SCAN) break;
            if (entity instanceof Villager villager && villager.isAlive() && !villager.isRemoved()) {
                observed.add(villager);
                if (observed.size() >= maxVillagers) break;
            }
        }
        observed.sort(Comparator.comparing(v -> v.getUUID().toString()));

        Map<UUID, List<Villager>> assigned = new HashMap<>();
        int processed = 0;
        for (Villager villager : observed) {
            UUID residentId = villager.getUUID();
            Group group = groupForResident(residentId);
            if (group == null || distanceSqr(group.x, group.z,
                    villager.blockPosition().getX(), villager.blockPosition().getZ()) > SPLIT_RADIUS_SQR
                    || group.members.size() >= MAX_GROUP_MEMBERS) {
                group = nearestJoinableGroup(villager, assigned);
            }
            if (group == null) {
                UUID id = UUID.randomUUID();
                group = new Group(id, villager.blockPosition().getX(), villager.blockPosition().getZ(), now);
                groups.put(id, group);
            }
            UUID oldGroup = groupByResident.put(residentId, group.id);
            if (oldGroup != null && !oldGroup.equals(group.id)) {
                Group previous = groups.get(oldGroup);
                if (previous != null) previous.members.remove(residentId);
            }
            group.members.add(residentId);
            assigned.computeIfAbsent(group.id, ignored -> new ArrayList<>()).add(villager);
            processed++;
        }

        long day = now / 24_000L;
        for (Map.Entry<UUID, List<Villager>> entry : assigned.entrySet()) {
            Group group = groups.get(entry.getKey());
            if (group == null) continue;
            List<Villager> members = entry.getValue();
            long sumX = 0, sumZ = 0;
            for (Villager villager : members) {
                sumX += villager.blockPosition().getX();
                sumZ += villager.blockPosition().getZ();
            }
            group.x = (int) (sumX / members.size());
            group.z = (int) (sumZ / members.size());
            group.lastObserved = now;
            group.consecutivePresenceScans = Math.min(1_000_000, group.consecutivePresenceScans + 1);
            if (group.lastResidentDay != day) {
                group.lastResidentDay = day;
                group.distinctResidentDays = Math.min(1_000_000, group.distinctResidentDays + 1);
            }
        }

        // Missing entities may simply be unloaded. Do not immediately delete memberships or
        // count them as a permanent departure; stale records expire only after a long grace period.
        List<UUID> expired = new ArrayList<>();
        for (Group group : groups.values()) {
            if (now - group.lastObserved > FORGET_AFTER_TICKS) expired.add(group.id);
        }
        for (UUID id : expired) {
            Group removed = groups.remove(id);
            if (removed != null) {
                for (UUID member : removed.members) {
                    if (id.equals(groupByResident.get(member))) groupByResident.remove(member);
                }
            }
        }

        if (processed > 0 || !expired.isEmpty()) setDirty();
        return processed;
    }

    private Group groupForResident(UUID residentId) {
        UUID groupId = groupByResident.get(residentId);
        return groupId == null ? null : groups.get(groupId);
    }

    private Group nearestJoinableGroup(Villager villager, Map<UUID, List<Villager>> assigned) {
        Group best = null;
        long bestDistance = Long.MAX_VALUE;
        int x = villager.blockPosition().getX();
        int z = villager.blockPosition().getZ();
        for (Group group : groups.values()) {
            if (group.members.size() >= MAX_GROUP_MEMBERS) continue;
            // Avoid attaching an entity to an old group whose centre is no longer nearby.
            long distance = distanceSqr(group.x, group.z, x, z);
            if (distance > JOIN_RADIUS_SQR || distance >= bestDistance) continue;
            best = group;
            bestDistance = distance;
        }
        return best;
    }

    public List<Group> groups() {
        return List.copyOf(groups.values());
    }

    public Group groupOf(UUID residentId) {
        return groupForResident(residentId);
    }

    private static long distanceSqr(int ax, int az, int bx, int bz) {
        long dx = (long) ax - bx;
        long dz = (long) az - bz;
        return dx * dx + dz * dz;
    }

    public static LocalVillagerGroupTracker load(CompoundTag tag, HolderLookup.Provider registries) {
        LocalVillagerGroupTracker tracker = new LocalVillagerGroupTracker();
        tracker.nextScanAt = Math.max(0, tag.getLong("next_scan"));
        ListTag list = tag.getList("groups", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("id")) continue;
            Group group = new Group(entry.getUUID("id"), entry.getInt("x"), entry.getInt("z"),
                    Math.max(0, entry.getLong("first")));
            group.lastObserved = Math.max(group.firstObserved, entry.getLong("last"));
            group.lastResidentDay = entry.getLong("resident_day");
            group.distinctResidentDays = Math.max(0, entry.getInt("resident_days"));
            group.consecutivePresenceScans = Math.max(0, entry.getInt("presence_scans"));
            ListTag members = entry.getList("members", Tag.TAG_INT_ARRAY);
            for (int m = 0; m < members.size(); m++) {
                int[] raw = members.getIntArray(m);
                if (raw.length != 4) continue;
                UUID resident = new UUID(((long) raw[0] << 32) | (raw[1] & 0xffffffffL),
                        ((long) raw[2] << 32) | (raw[3] & 0xffffffffL));
                group.members.add(resident);
                tracker.groupByResident.put(resident, group.id);
            }
            tracker.groups.put(group.id, group);
        }
        return tracker;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("next_scan", nextScanAt);
        ListTag list = new ListTag();
        for (Group group : groups.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", group.id);
            entry.putInt("x", group.x);
            entry.putInt("z", group.z);
            entry.putLong("first", group.firstObserved);
            entry.putLong("last", group.lastObserved);
            entry.putLong("resident_day", group.lastResidentDay);
            entry.putInt("resident_days", group.distinctResidentDays);
            entry.putInt("presence_scans", group.consecutivePresenceScans);
            ListTag members = new ListTag();
            for (UUID resident : group.members) {
                int[] raw = {
                        (int) (resident.getMostSignificantBits() >>> 32),
                        (int) resident.getMostSignificantBits(),
                        (int) (resident.getLeastSignificantBits() >>> 32),
                        (int) resident.getLeastSignificantBits()
                };
                members.add(new net.minecraft.nbt.IntArrayTag(raw));
            }
            entry.put("members", members);
            list.add(entry);
        }
        tag.put("groups", list);
        return tag;
    }
}
