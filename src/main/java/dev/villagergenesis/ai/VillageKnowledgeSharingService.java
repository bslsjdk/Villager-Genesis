package dev.villagergenesis.ai;

import dev.villagergenesis.knowledge.KnowledgeBaseline;
import dev.villagergenesis.memory.VillagerMemory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Lets villagers teach nearby residents and preserve learned knowledge in physical books.
 * Books in public storage survive the death of their author and can teach later generations.
 */
public final class VillageKnowledgeSharingService {
    private static final String NEXT_TEACHING = "villager_genesis_next_teaching";
    private static final int TEACHING_COOLDOWN = 200;
    private static final int ARCHIVE_SCAN_INTERVAL = 40;
    private static final Map<ServerLevel, Map<Long, Long>> NEXT_REGION_SCAN =
            Collections.synchronizedMap(new WeakHashMap<>());

    private VillageKnowledgeSharingService() {}

    public static void tryTeachOne(Villager teacher) {
        if (!(teacher.level() instanceof ServerLevel level) || !teacher.isAlive()
                || teacher.isBaby() || teacher.getTarget() != null
                || level.getGameTime() < teacher.getPersistentData().getLong(NEXT_TEACHING)) {
            return;
        }

        long regionKey = (((long) (teacher.blockPosition().getX() >> 5)) << 32)
                ^ ((teacher.blockPosition().getZ() >> 5) & 0xffffffffL);
        synchronized (NEXT_REGION_SCAN) {
            Map<Long, Long> regions = NEXT_REGION_SCAN.computeIfAbsent(level,
                    ignored -> new HashMap<>());
            if (level.getGameTime() < regions.getOrDefault(regionKey, 0L)) return;
            regions.put(regionKey, level.getGameTime() + ARCHIVE_SCAN_INTERVAL);
        }

        var sourceMemory = VillagerMemory.forVillager(teacher);

        // A physical book can outlive its author. Villagers can recover archived knowledge
        // before teaching, so knowledge does not disappear with one particular resident.
        var archivedKnowledge = VillageKnowledgeArchiveService.listArchivedKnowledge(
                level, teacher.blockPosition());
        String archived = archivedKnowledge.stream()
                .filter(id -> !sourceMemory.knownKnowledge().contains(id))
                .sorted()
                .findFirst().orElse(null);
        if (archived != null) {
            sourceMemory.learnKnowledge(archived);
        }

        var known = sourceMemory.knownKnowledge();
        String teachable = known.stream()
                .filter(id -> !KnowledgeBaseline.isBaseline(id))
                .sorted()
                .findFirst().orElse(null);
        if (teachable == null) return;

        teacher.getPersistentData().putLong(NEXT_TEACHING,
                level.getGameTime() + TEACHING_COOLDOWN);

        // Archive one new fact per teaching cycle. This avoids rewriting the same first fact
        // forever while keeping all storage and item counts physical and player-visible.
        for (String candidate : known.stream()
                .filter(id -> !KnowledgeBaseline.isBaseline(id))
                .filter(id -> !archivedKnowledge.contains(id))
                .sorted().toList()) {
            if (VillageKnowledgeArchiveService.archiveOne(level, teacher.blockPosition(), candidate)) {
                break;
            }
        }

        var nearby = level.getEntitiesOfClass(Villager.class,
                teacher.getBoundingBox().inflate(4.0D),
                other -> other != teacher && other.isAlive() && !other.isBaby()
                        && other.getTarget() == null
                        && !VillagerMemory.forVillager(other).knownKnowledge().contains(teachable));
        Villager student = nearby.stream()
                .min(Comparator.comparingDouble(teacher::distanceToSqr))
                .orElse(null);
        if (student == null) return;

        VillagerMemory.forVillager(student).learnKnowledge(teachable);
        student.getPersistentData().putLong(NEXT_TEACHING,
                level.getGameTime() + TEACHING_COOLDOWN);
    }
}
