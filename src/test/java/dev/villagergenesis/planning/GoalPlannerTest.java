package dev.villagergenesis.planning;

import dev.villagergenesis.memory.VillagerMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GoalPlannerTest {
    @Test
    void doesNotProposeGoalWithoutMemory() {
        assertTrue(GoalPlanner.chooseGoal(null).isEmpty());
        assertTrue(GoalPlanner.rankGoals(null, GoalPlanner.Context.memoryOnly()).isEmpty());
    }

    @Test
    void proposesExplorationAfterRepeatedMovement() {
        VillagerMemory.Snapshot snapshot =
                new VillagerMemory.Snapshot(0L, 100L, 8, 25, "minecraft:overworld", "observe");

        assertEquals("explore", GoalPlanner.chooseGoal(snapshot).orElseThrow());
    }

    @Test
    void proposesObservationAfterEnoughSamples() {
        VillagerMemory.Snapshot snapshot =
                new VillagerMemory.Snapshot(0L, 100L, 5, 2, "minecraft:overworld", "initialize");

        assertEquals("observe", GoalPlanner.chooseGoal(snapshot).orElseThrow());
    }

    @Test
    void urgentThreatOverridesExploration() {
        VillagerMemory.Snapshot snapshot =
                new VillagerMemory.Snapshot(0L, 100L, 80, 80, "minecraft:overworld", "explore");
        GoalPlanner.Context context = new GoalPlanner.Context(90, 80, 20, true,
                true, true, 100, 100);

        assertEquals("retreat", GoalPlanner.chooseGoal(snapshot, context).orElseThrow());
    }

    @Test
    void foodShortageBeatsLearningAndExploration() {
        VillagerMemory.Snapshot snapshot =
                new VillagerMemory.Snapshot(0L, 100L, 80, 80, "minecraft:overworld", "learn");
        GoalPlanner.Context context = new GoalPlanner.Context(0, 100, 0.5, true,
                true, true, 100, 100);

        assertEquals("seek_food", GoalPlanner.chooseGoal(snapshot, context).orElseThrow());
    }

    @Test
    void doesNotExploreWhenThreatIsUrgent() {
        VillagerMemory.Snapshot snapshot =
                new VillagerMemory.Snapshot(0L, 100L, 80, 80, "minecraft:overworld", "observe");
        GoalPlanner.Context context = new GoalPlanner.Context(75, 70, 30, true,
                false, false, 100, 10);

        assertFalse(GoalPlanner.rankGoals(snapshot, context).stream()
                .anyMatch(candidate -> candidate.goal().equals("explore")));
    }

    @Test
    void contextClampsInvalidRangesAndNonFiniteFoodEstimate() {
        GoalPlanner.Context context = new GoalPlanner.Context(200, -10, Double.NaN,
                true, false, false, 500, -10);

        assertEquals(100, context.threatLevel());
        assertEquals(0, context.healthPercent());
        assertEquals(3650.0, context.foodDays());
        assertEquals(100, context.explorationValue());
        assertEquals(0, context.observationValue());
    }

    @Test
    void goalRankingIsImmutableAndDeterministic() {
        VillagerMemory.Snapshot snapshot =
                new VillagerMemory.Snapshot(0L, 100L, 8, 25, "minecraft:overworld", "observe");
        var first = GoalPlanner.rankGoals(snapshot, GoalPlanner.Context.memoryOnly());
        var second = GoalPlanner.rankGoals(snapshot, GoalPlanner.Context.memoryOnly());

        assertEquals(first, second);
        assertThrows(UnsupportedOperationException.class, () -> first.clear());
    }
}
