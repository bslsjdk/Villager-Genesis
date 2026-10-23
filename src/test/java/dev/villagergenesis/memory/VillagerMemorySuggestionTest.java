package dev.villagergenesis.memory;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VillagerMemorySuggestionTest {
    @Test
    void suggestionMetadataSurvivesMemoryReload() {
        CompoundTag saved = new CompoundTag();
        VillagerMemory memory = new VillagerMemory(saved);

        memory.recordSuggestion("retreat", 275, "Immediate danger takes priority.", 1200L);

        VillagerMemory restored = new VillagerMemory(saved.copy());
        assertEquals("retreat", restored.suggestedGoal());
        assertEquals(275, restored.suggestedGoalScore());
        assertEquals("Immediate danger takes priority.", restored.suggestedGoalReason());
        assertEquals(1200L, restored.suggestedGoalAt());
    }

    @Test
    void suggestionMetadataIsBoundedAndRejectsBlankGoals() {
        VillagerMemory memory = new VillagerMemory(new CompoundTag());

        memory.recordSuggestion("observe", Integer.MAX_VALUE, "r".repeat(300), -5L);

        assertEquals(100000, memory.suggestedGoalScore());
        assertEquals(256, memory.suggestedGoalReason().length());
        assertEquals(0L, memory.suggestedGoalAt());
        assertThrows(IllegalArgumentException.class,
                () -> memory.recordSuggestion("  ", 1, "invalid", 10L));
    }
}
