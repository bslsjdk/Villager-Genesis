package dev.villagergenesis.knowledge;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeLearningPlannerTest {
    private final KnowledgeRegistry registry = new KnowledgeRegistry();

    @Test
    void prioritizesImmediateSurvivalWhenThreatIsHigh() {
        var recommendations = KnowledgeLearningPlanner.recommendLearning(registry, Set.of(), 100, 3);

        assertEquals("hostile_awareness", recommendations.get(0).id());
        assertEquals("basic_shelter", recommendations.get(1).id());
        assertFalse(recommendations.stream().anyMatch(d -> d.id().equals("perimeter_patrol")));
    }

    @Test
    void advancedKnowledgeWaitsForPrerequisites() {
        var recommendations = KnowledgeLearningPlanner.recommendLearning(
                registry, Set.of("basic_shelter", "basic_tools", "basic_crafting", "basic_food_production", "basic_equipment", "village_cooperation", "hostile_awareness"), 100, 10);
        var ids = recommendations.stream().map(KnowledgeDefinition::id).collect(Collectors.toSet());

        assertTrue(ids.contains("basic_defense"));
        assertTrue(ids.contains("food_reserves"));
        assertFalse(ids.contains("equipment_care"));
        assertFalse(ids.contains("perimeter_patrol"));
    }

    @Test
    void studentsCanLearnFromTeachersOnlyAfterPrerequisites() {
        var lessons = KnowledgeLearningPlanner.recommendTeaching(
                registry,
                Set.of("basic_shelter", "basic_tools", "basic_crafting", "basic_food_production", "basic_equipment", "village_cooperation", "hostile_awareness", "basic_defense"),
                Set.of("basic_shelter", "basic_tools", "basic_crafting", "basic_food_production", "basic_equipment", "village_cooperation", "hostile_awareness"),
                10);

        assertTrue(lessons.stream().anyMatch(d -> d.id().equals("basic_defense")));
        assertFalse(lessons.stream().anyMatch(d -> d.id().equals("equipment_care")));
    }

    @Test
    void archivedBooksCannotInjectUnknownIdsOrSkipPrerequisites() {
        var archived = Set.of("workbench_crafting", "basic_crafting", "not_a_real_lesson");
        var withoutPrerequisites = KnowledgeLearningPlanner.recommendArchivedKnowledge(
                registry, archived, Set.of(), 10);

        assertEquals(Set.of(), withoutPrerequisites.stream()
                .map(KnowledgeDefinition::id).collect(Collectors.toSet()));

        var withPrerequisites = KnowledgeLearningPlanner.recommendArchivedKnowledge(
                registry, archived, Set.of("basic_tools"), 10);
        var learnedIds = withPrerequisites.stream()
                .map(KnowledgeDefinition::id).collect(Collectors.toSet());

        assertTrue(learnedIds.contains("basic_crafting"));
        assertFalse(learnedIds.contains("workbench_crafting"));
        assertFalse(learnedIds.contains("not_a_real_lesson"));
    }

    @Test
    void geniusArchiveLimitStillRespectsPrerequisites() {
        var archived = Set.of("basic_crafting", "workbench_crafting");
        var lessons = KnowledgeLearningPlanner.recommendArchivedKnowledge(
                registry, archived, Set.of("basic_tools"), 3);

        assertEquals(1, lessons.size());
        assertEquals("basic_crafting", lessons.get(0).id());
    }

    @Test
    void rejectsIndirectPrerequisiteCycles() {
        registry.register(new KnowledgeDefinition(
                "cycle_a", "Cycle A", "Temporary dependency", Set.of("cycle_b"), 50, 5));

        assertThrows(IllegalArgumentException.class, () -> registry.register(
                new KnowledgeDefinition(
                        "cycle_b", "Cycle B", "Would close a cycle", Set.of("cycle_a"), 50, 5)));
    }

    @Test
    void clampsThreatAndReturnsImmutableResults() {
        var recommendations = KnowledgeLearningPlanner.recommendLearning(registry, Set.of(), 1000, 2);

        assertEquals(2, recommendations.size());
        assertThrows(UnsupportedOperationException.class, () -> recommendations.clear());
    }

    @Test
    void rejectsSelfPrerequisite() {
        assertThrows(IllegalArgumentException.class, () ->
                registry.register(new KnowledgeDefinition(
                        "loop", "Loop", "Invalid", Set.of("loop"), 50, 5)));
    }
}
