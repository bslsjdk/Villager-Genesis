package dev.villagergenesis.economy;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class StorageExpansionPlannerTest {
    private VisibleStorageScanner.Snapshot snapshot(int occupied, int slots, int containers) {
        var list = new java.util.ArrayList<VisibleStorageScanner.StorageContainer>();
        for (int i = 0; i < containers; i++) {
            list.add(new VisibleStorageScanner.StorageContainer(i, 64, 0, "chest",
                    i == 0 ? occupied : 0, slots / Math.max(1, containers)));
        }
        return new VisibleStorageScanner.Snapshot(0, 64, 0, 12, list, Map.of(), 1L);
    }

    @Test
    void proposesPhysicalExpansionNearCapacity() {
        var p = StorageExpansionPlanner.plan(snapshot(25, 27, 1), 16, 4, 20);
        assertEquals(StorageExpansionPlanner.Action.BUILD_STORAGE, p.action());
        assertEquals(1, p.additionalContainers());
    }

    @Test
    void doesNotInventStorageWhenMaterialsAreMissing() {
        var p = StorageExpansionPlanner.plan(snapshot(27, 27, 1), 7, 4, 20);
        assertEquals(StorageExpansionPlanner.Action.SURVEY_MORE_STORAGE_AREA, p.action());
        assertEquals(0, p.additionalContainers());
    }

    @Test
    void emptySettlementCanPlanItsFirstContainer() {
        var p = StorageExpansionPlanner.plan(snapshot(0, 0, 0), 8, 2, 20);
        assertEquals(StorageExpansionPlanner.Action.BUILD_STORAGE, p.action());
    }
}
