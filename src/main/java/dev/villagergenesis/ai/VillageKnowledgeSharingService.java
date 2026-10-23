package dev.villagergenesis.ai;

import dev.villagergenesis.knowledge.KnowledgeBaseline;
import dev.villagergenesis.memory.VillagerMemory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

import java.util.Comparator;

/**
 * Lets villagers teach nearby residents and preserve learned knowledge in physical books.
 * Books in public storage survive the death of their author and can teach later generations.
 */
public final class VillageKnowledgeSharingService {
    private static final String NEXT_TEACHING = "villager_genesis_next_teaching";
    private static final int TEACHING_COOLDOWN = 200;

    private VillageKnowledgeSharingService() {}

    public static void tryTeachOne(Villager teacher) {
        if (!(teacher.level() instanceof ServerLevel level) || !teacher.isAlive()
                || teacher.isBaby() || teacher.getTarget() != null
                || level.getGameTime() < teacher.getPersistentData().getLong(NEXT_TEACHING)) {
            return;
        }

        var sourceMemory = VillagerMemory.forVillager(teacher);

        // A physical book can outlive its author. Villagers can recover archived knowledge
        // before teaching, so knowledge does not disappear with one particular resident.
        String archived = VillageKnowledgeArchiveService.findUnknownKnowledge(
                level, teacher.blockPosition(), sourceMemory.knownKnowledge());
        if (archived != null) {
            sourceMemory.learnKnowledge(archived);
        }

        String teachable = sourceMemory.knownKnowledge().stream()
                .filter(id -> !KnowledgeBaseline.isBaseline(id))
                .sorted()
                .findFirst().orElse(null);
        if (teachable == null) return;

        teacher.getPersistentData().putLong(NEXT_TEACHING,
                level.getGameTime() + TEACHING_COOLDOWN);

        // If a real book is available in public storage, write the knowledge into it.
        // The archive service never conjures books or modifies virtual inventory.
        VillageKnowledgeArchiveService.archiveOne(level, teacher.blockPosition(), teachable);

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
