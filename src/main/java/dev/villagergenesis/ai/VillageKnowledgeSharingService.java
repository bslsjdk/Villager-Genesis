package dev.villagergenesis.ai;

import dev.villagergenesis.knowledge.KnowledgeBaseline;
import dev.villagergenesis.knowledge.KnowledgeLearningPlanner;
import dev.villagergenesis.knowledge.KnowledgeRegistry;
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
        // Every villager can teach what they know. A librarian is not the only teacher;
        // physical books are the librarian's additional source of knowledge.
        var nearby = level.getEntitiesOfClass(Villager.class,
                teacher.getBoundingBox().inflate(4.0D),
                other -> other != teacher && other.isAlive() && !other.isBaby()
                        && other.getTarget() == null
                        && level.getGameTime() >= other.getPersistentData().getLong(NEXT_TEACHING))
                .stream()
                .sorted(Comparator.comparingDouble(teacher::distanceToSqr))
                .toList();

        KnowledgeRegistry registry = new KnowledgeRegistry();
        Villager student = null;
        String teachable = null;
        for (Villager candidate : nearby) {
            var studentMemory = VillagerMemory.forVillager(candidate);
            var lessons = KnowledgeLearningPlanner.recommendTeaching(
                    registry, known, studentMemory.knownKnowledge(), 1);
            if (!lessons.isEmpty()) {
                student = candidate;
                teachable = lessons.get(0).id();
                break;
            }
        }
        if (student == null || teachable == null) return;

        teacher.getPersistentData().putLong(NEXT_TEACHING,
                level.getGameTime() + TEACHING_COOLDOWN);

        // Archive knowledge in physical books when available; teaching also works without books.
        String archiveCandidate = known.stream()
                .filter(id -> !KnowledgeBaseline.isBaseline(id))
                .filter(id -> !archivedKnowledge.contains(id))
                .sorted().findFirst().orElse(null);
        if (archiveCandidate != null) {
            VillageKnowledgeArchiveService.archiveOne(level, teacher.blockPosition(), archiveCandidate);
        }

        VillagerMemory.forVillager(student).learnKnowledge(teachable);
        student.getPersistentData().putLong(NEXT_TEACHING,
                level.getGameTime() + TEACHING_COOLDOWN);
    }
}
