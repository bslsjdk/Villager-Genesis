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
    private static final String GENIUS_TRAIT = "villager_genesis_genius";
    private static final int TEACHING_COOLDOWN = 200;
    private static final int GENIUS_TEACHING_COOLDOWN = 100;
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
        boolean scanArchive;
        synchronized (NEXT_REGION_SCAN) {
            Map<Long, Long> regions = NEXT_REGION_SCAN.computeIfAbsent(level,
                    ignored -> new HashMap<>());
            scanArchive = level.getGameTime() >= regions.getOrDefault(regionKey, 0L);
            if (scanArchive) {
                regions.put(regionKey, level.getGameTime() + ARCHIVE_SCAN_INTERVAL);
            }
        }

        var sourceMemory = VillagerMemory.forVillager(teacher);
        boolean genius = isGenius(teacher);

        // Throttle expensive physical-container scans, not the whole teaching system. Otherwise
        // the first villager ticking in a region suppresses every other villager's teaching.
        boolean librarian = teacher.getVillagerData().getProfession()
                == net.minecraft.world.entity.npc.VillagerProfession.LIBRARIAN;
        int bookSearchRadius = librarian ? 16 : 8;
        var archivedKnowledge = scanArchive
                ? VillageKnowledgeArchiveService.listArchivedKnowledge(
                        level, teacher.blockPosition(), bookSearchRadius)
                : java.util.Set.<String>of();
        KnowledgeRegistry registry = new KnowledgeRegistry();
        var knownBeforeReading = sourceMemory.knownKnowledge();
        // Physical books are untrusted input: accept only registered lessons whose prerequisites
        // are already satisfied. A forged/old archive must not inject arbitrary IDs or skip a course.
        var eligibleArchived = KnowledgeLearningPlanner.recommendArchivedKnowledge(
                registry, archivedKnowledge, knownBeforeReading, genius ? 3 : 1);
        for (var lesson : eligibleArchived) {
            sourceMemory.learnKnowledge(lesson.id());
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
                level.getGameTime() + (genius ? GENIUS_TEACHING_COOLDOWN : TEACHING_COOLDOWN));

        // Archive knowledge in physical books when available; teaching also works without books.
        String archiveCandidate = known.stream()
                .filter(id -> !KnowledgeBaseline.isBaseline(id))
                .filter(id -> !archivedKnowledge.contains(id))
                .sorted().findFirst().orElse(null);
        if (scanArchive && archiveCandidate != null) {
            VillageKnowledgeArchiveService.archiveOne(level, teacher.blockPosition(), archiveCandidate);
        }

        VillagerMemory.forVillager(student).learnKnowledge(teachable);
        student.getPersistentData().putLong(NEXT_TEACHING,
                level.getGameTime() + (isGenius(student)
                        ? GENIUS_TEACHING_COOLDOWN : TEACHING_COOLDOWN));
    }

    /**
     * A small, stable minority can become natural prodigies regardless of profession.
     * The UUID-based trait survives reloads and does not replace practice or prerequisites.
     */
    public static boolean isGenius(Villager villager) {
        var data = villager.getPersistentData();
        if (!data.contains(GENIUS_TRAIT)) {
            data.putBoolean(GENIUS_TRAIT,
                    Math.floorMod(villager.getUUID().hashCode(), 32) == 0);
        }
        return data.getBoolean(GENIUS_TRAIT);
    }
}
