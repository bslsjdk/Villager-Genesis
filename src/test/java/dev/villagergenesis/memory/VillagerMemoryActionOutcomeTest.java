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
