package dev.villagergenesis.planning;

import dev.villagergenesis.memory.VillagerMemory;
import java.util.Optional;

/**
 * Deterministic first-pass goal proposal logic.
 *
 * This currently records a proposed goal in memory; it does not replace vanilla navigation or
 * profession AI. Later actions will add explicit preconditions, costs, effects, and failure data.
 */
public final class GoalPlanner {
    private GoalPlanner() {}

    public static Optional<String> chooseGoal(VillagerMemory.Snapshot memory) {
        if (memory == null) {
            return Optional.empty();
        }

        // A proposal is intentionally conservative until the action executor is implemented.
        if (memory.positionChanges() >= 25) {
            return Optional.of("explore");
        }
        if (memory.observations() >= 5) {
            return Optional.of("observe");
        }
        return Optional.of("initialize");
    }
}
