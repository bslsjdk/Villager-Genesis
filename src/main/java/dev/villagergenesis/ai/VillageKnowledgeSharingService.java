package dev.villagergenesis.ai;

import dev.villagergenesis.knowledge.KnowledgeBaseline;
import dev.villagergenesis.memory.VillagerMemory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

import java.util.Comparator;

/** Transfers one learned, non-baseline fact during a nearby, peaceful villager interaction. */
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
        String teachable = sourceMemory.knownKnowledge().stream()
                .filter(id -> !KnowledgeBaseline.isBaseline(id))
                .sorted()
                .findFirst().orElse(null);
        if (teachable == null) return;
        teacher.getPersistentData().putLong(NEXT_TEACHING,
                level.getGameTime() + TEACHING_COOLDOWN);

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
