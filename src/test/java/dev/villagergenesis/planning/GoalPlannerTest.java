package dev.villagergenesis.planning;

import dev.villagergenesis.memory.VillagerMemory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoalPlannerTest {
    @Test
    void doesNotProposeGoalWithoutMemory() {
        assertTrue(GoalPlanner.chooseGoal(null).isEmpty());
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
}
