package dev.villagergenesis.society;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class VillageBuildingPlannerTest {
    @Test
    void occupiedOrStockedBuildingMustBePreparedBeforeDemolition() {
        var b = new VillageBuildingPlanner.Building("house-1", "house",
                VillageBuildingPlanner.Status.OBSOLETE, 80, true, true, false,
                Set.of(), 0);
        var result = VillageBuildingPlanner.plan(List.of(b), List.of(), Set.of(), 100);
        assertEquals(VillageBuildingPlanner.Action.PREPARE_DEMOLITION, result.get(0).action());
    }

    @Test
    void essentialBuildingIsRenovatedRatherThanDemolished() {
        var b = new VillageBuildingPlanner.Building("store-1", "storage",
                VillageBuildingPlanner.Status.OBSOLETE, 80, false, false, true,
                Set.of(), 0);
        var result = VillageBuildingPlanner.plan(List.of(b), List.of(), Set.of(), 100);
        assertEquals(VillageBuildingPlanner.Action.RENOVATE, result.get(0).action());
    }

    @Test
    void unmetUrgentNeedOutranksRoutineKeeping() {
        var house = new VillageBuildingPlanner.Building("house-1", "house",
                VillageBuildingPlanner.Status.ACTIVE, 90, false, false, true,
                Set.of(), 0);
        var result = VillageBuildingPlanner.plan(List.of(house),
                List.of(new VillageBuildingPlanner.Need("storage", 30, true, true)),
                Set.of(), 100);
        assertEquals("new:storage", result.get(0).buildingId());
        assertEquals(VillageBuildingPlanner.Action.REBUILD, result.get(0).action());
    }
}
