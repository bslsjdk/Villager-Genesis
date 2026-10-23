package dev.villagergenesis.memory;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VillagerMemoryGoalSeparationTest {
    @Test
    void plannerSuggestionDoesNotReplaceExecutorGoalInSnapshot() {
        VillagerMemory memory = new VillagerMemory(new CompoundTag());
        memory.setCurrentGoal("observe");
        memory.recordSuggestion("retreat", 250, "Critical health.", 400L);

        VillagerMemory.Snapshot snapshot = memory.snapshot("explore");

        assertEquals("explore", snapshot.currentGoal());
        assertEquals("retreat", memory.suggestedGoal());
        assertEquals("observe", memory.currentGoal());
    }

    @Test
    void nullActiveGoalProducesAnEmptyGoalRatherThanReusingSuggestion() {
        VillagerMemory memory = new VillagerMemory(new CompoundTag());
        memory.recordSuggestion("retreat", 250, "Critical health.", 400L);

        assertEquals("", memory.snapshot(null).currentGoal());
        assertEquals("retreat", memory.suggestedGoal());
    }
}
