package dev.villagergenesis.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Plans physical expansion of player-visible storage. Capacity is derived only from real
 * containers in the latest world snapshot; this class never creates virtual slots or items.
 */
public final class StorageExpansionPlanner {
    public static final double EXPANSION_THRESHOLD = 0.80;
    public static final int WOOD_ITEMS_PER_CHEST = 8;

    public enum Action {
        BUILD_STORAGE,
        SURVEY_MORE_STORAGE_AREA,
        NO_ACTION
    }

    public record Proposal(Action action, int priority, int currentSlots, int occupiedSlots,
                           int containers, int additionalContainers, String reason) {}

    private StorageExpansionPlanner() {}

    public static Proposal plan(VisibleStorageScanner.Snapshot snapshot, int availableWoodItems,
                                int safeBuildSites, int desiredFreeSlots) {
        if (snapshot == null) throw new IllegalArgumentException("A real storage snapshot is required.");
        if (availableWoodItems < 0 || safeBuildSites < 0 || desiredFreeSlots < 0) {
            throw new IllegalArgumentException("Counts cannot be negative.");
        }

        int slots = 0;
        int occupied = 0;
        for (VisibleStorageScanner.StorageContainer container : snapshot.containers()) {
            slots += Math.max(0, container.slots());
            occupied += Math.max(0, Math.min(container.occupiedSlots(), container.slots()));
        }
        int free = Math.max(0, slots - occupied);
        double utilization = slots == 0 ? 1.0 : (double) occupied / slots;

        if (free >= desiredFreeSlots && utilization < EXPANSION_THRESHOLD) {
            return new Proposal(Action.NO_ACTION, 0, slots, occupied,
                    snapshot.publicContainerCount(), 0,
                    "Real public containers have enough verified free slots.");
        }
        if (safeBuildSites == 0) {
            return new Proposal(Action.SURVEY_MORE_STORAGE_AREA, 70, slots, occupied,
                    snapshot.publicContainerCount(), 0,
                    "Storage is missing or near capacity, but no safe construction site is currently available.");
        }

        int slotsPerContainer = 27;
        int shortage = Math.max(1, desiredFreeSlots - free);
        int containersNeeded = Math.max(1, (shortage + slotsPerContainer - 1) / slotsPerContainer);
        containersNeeded = Math.min(containersNeeded, safeBuildSites);
        int affordable = availableWoodItems / WOOD_ITEMS_PER_CHEST;
        int buildable = Math.min(containersNeeded, affordable);
        if (buildable == 0) {
            return new Proposal(Action.SURVEY_MORE_STORAGE_AREA, 75, slots, occupied,
                    snapshot.publicContainerCount(), 0,
                    "Storage expansion is needed, but the settlement lacks eight real wood/plank items per planned chest.");
        }
        return new Proposal(Action.BUILD_STORAGE, 85, slots, occupied,
                snapshot.publicContainerCount(), buildable,
                "Build real chest containers using existing materials; capacity increases only after the placed containers are verified by a later world scan.");
    }
}
