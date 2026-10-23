package dev.villagergenesis.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Plans knowledge acquisition and teaching opportunities.
 *
 * The planner deliberately separates knowing a concept from executing a world action. A future
 * Minecraft adapter must verify practical performance before marking a skill as mastered.
 */
public final class KnowledgeLearningPlanner {
    private KnowledgeLearningPlanner() {}

    /**
     * Select the most useful missing knowledge whose prerequisites are already mastered.
     *
     * @param masteredIds knowledge the learner has demonstrated in practice
     * @param threatLevel current hostile threat from 0 (none) to 100 (immediate danger)
     */
    public static List<KnowledgeDefinition> recommendLearning(
            KnowledgeRegistry registry, Set<String> masteredIds, int threatLevel, int limit) {
        if (registry == null || limit <= 0) {
            return List.of();
        }
        Set<String> mastered = masteredIds == null ? Set.of() : Set.copyOf(masteredIds);
        int threat = Math.max(0, Math.min(100, threatLevel));

        List<KnowledgeDefinition> candidates = new ArrayList<>();
        for (KnowledgeDefinition definition : registry.all()) {
            if (mastered.contains(definition.id())) {
                continue;
            }
            if (!mastered.containsAll(definition.prerequisites())) {
                continue;
            }
            candidates.add(definition);
        }

        candidates.sort(Comparator
                .comparingInt((KnowledgeDefinition d) -> score(d, threat)).reversed()
                .thenComparingInt(KnowledgeDefinition::complexity)
                .thenComparing(KnowledgeDefinition::id));
        return List.copyOf(candidates.subList(0, Math.min(limit, candidates.size())));
    }

    /** A higher threat strongly favors immediate survival knowledge over long-term improvements. */
    static int score(KnowledgeDefinition definition, int threatLevel) {
        int threat = Math.max(0, Math.min(100, threatLevel));
        int urgencyWeight = 2 + threat / 20;
        return definition.survivalUrgency() * urgencyWeight - definition.complexity();
    }

    /**
     * Return knowledge this teacher can pass on that the student does not yet know and whose
     * prerequisites the student already satisfies. Only practically verified knowledge should be
     * included in teacherMasteredIds.
     */
    public static List<KnowledgeDefinition> recommendTeaching(
            KnowledgeRegistry registry,
            Set<String> teacherMasteredIds,
            Set<String> studentMasteredIds,
            int limit) {
        if (registry == null || limit <= 0) {
            return List.of();
        }
        Set<String> teacher = teacherMasteredIds == null ? Set.of() : Set.copyOf(teacherMasteredIds);
        Set<String> student = studentMasteredIds == null ? Set.of() : Set.copyOf(studentMasteredIds);
        List<KnowledgeDefinition> candidates = new ArrayList<>();
        for (KnowledgeDefinition definition : registry.all()) {
            if (!teacher.contains(definition.id()) || student.contains(definition.id())) {
                continue;
            }
            if (!student.containsAll(definition.prerequisites())) {
                continue;
            }
            candidates.add(definition);
        }
        candidates.sort(Comparator
                .comparingInt(KnowledgeDefinition::survivalUrgency).reversed()
                .thenComparingInt(KnowledgeDefinition::complexity)
                .thenComparing(KnowledgeDefinition::id));
        return List.copyOf(candidates.subList(0, Math.min(limit, candidates.size())));
    }

    /** Check whether the learner has prerequisites needed to start a knowledge unit. */
    public static boolean canLearn(KnowledgeDefinition definition, Set<String> masteredIds) {
        if (definition == null) {
            return false;
        }
        return (masteredIds == null ? Set.<String>of() : masteredIds)
                .containsAll(definition.prerequisites());
    }
}
