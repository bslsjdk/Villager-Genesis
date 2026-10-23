package dev.villagergenesis.economy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VillageWorkPlannerTest {
    private VisibleStorageScanner.Snapshot storage(int bread, int seeds) {
        return new VisibleStorageScanner.Snapshot(
                0, 64, 0, 12, List.of(),
                Map.of(VisibleStorageScanner.Resource.BREAD, bread,
                        VisibleStorageScanner.Resource.SEEDS, seeds), 0L);
    }

    @Test
    void prioritizesRealHarvestWhenFoodIsLow() {
        var farm = new VillageWorkPlanner.FarmSurvey(10, 6, 3, 4, 5, 8, 10);
        var proposals = VillageWorkPlanner.plan(storage(2, 8), farm, 0.1, 3);
        assertEquals(VillageWorkPlanner.Work.HARVEST_MATURE_CROPS, proposals.get(0).work());
    }

    @Test
    void neverProposesPlantingWithoutSeeds() {
        var farm = new VillageWorkPlanner.FarmSurvey(10, 4, 0, 6, 5, 0, 8);
        var proposals = VillageWorkPlanner.plan(storage(0, 0), farm, 0, 3);
        assertFalse(proposals.stream().anyMatch(p -> p.work() == VillageWorkPlanner.Work.PLANT_EMPTY_FARMLAND));
        assertFalse(proposals.stream().anyMatch(p -> p.work() == VillageWorkPlanner.Work.EXPAND_FARMLAND),
                "Do not consume land for expansion while empty farmland exists; the real blocker is seed shortage.");
    }

    @Test
    void lowSeedReserveGetsProtected() {
        var farm = new VillageWorkPlanner.FarmSurvey(8, 4, 0, 4, 0, 1, 10);
        var proposals = VillageWorkPlanner.plan(storage(40, 0), farm, 10, 3);
        assertTrue(proposals.stream().anyMatch(p -> p.work() == VillageWorkPlanner.Work.PROTECT_SEEDS));
    }
}
