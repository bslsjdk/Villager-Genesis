package dev.villagergenesis.memory;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VillagerMemoryActionOutcomeTest {
    @Test
    void recordsSuccessFailureAndInterruptionSeparately() {
        VillagerMemory memory = new VillagerMemory(new CompoundTag());

        memory.recordActionOutcome("explore", "success", "Reached waypoint.", 100L);
        memory.recordActionOutcome("explore", "failure", "Timed out.", 200L);
        memory.recordActionOutcome("explore", "interrupted", "Threat appeared.", 300L);

        assertEquals("explore", memory.lastActionGoal());
        assertEquals("interrupted", memory.lastActionResult());
        assertEquals("Threat appeared.", memory.lastActionReason());
        assertEquals(300L, memory.lastActionAt());
        assertEquals(1, memory.actionSuccesses());
        assertEquals(1, memory.actionFailures());
        assertEquals(1, memory.actionInterrupts());
    }

    @Test
    void perGoalCountersDoNotMixOutcomesFromDifferentGoals() {
        CompoundTag saved = new CompoundTag();
        VillagerMemory memory = new VillagerMemory(saved);
        memory.recordActionOutcome("explore", "failure", "Path ended.", 100L);
        memory.recordActionOutcome("observe", "success", "Observation completed.", 200L);

        VillagerMemory restored = new VillagerMemory(saved.copy());
        assertEquals(1, restored.actionFailuresFor("explore"));
        assertEquals(0, restored.actionSuccessesFor("explore"));
        assertEquals(1, restored.actionSuccessesFor("observe"));
        assertEquals(0, restored.actionFailuresFor("observe"));
        assertEquals(0, restored.actionInterruptsFor("unknown_goal"));
    }

    @Test
    void actionOutcomePersistsAndBoundsReason() {
        CompoundTag saved = new CompoundTag();
        VillagerMemory memory = new VillagerMemory(saved);
        memory.recordActionOutcome("explore", "failure", "x".repeat(300), 500L);

        VillagerMemory restored = new VillagerMemory(saved.copy());
        assertEquals("failure", restored.lastActionResult());
        assertEquals(256, restored.lastActionReason().length());
        assertEquals(1, restored.actionFailures());
        assertThrows(IllegalArgumentException.class,
                () -> restored.recordActionOutcome("explore", "unknown", "bad", 600L));
    }
}
