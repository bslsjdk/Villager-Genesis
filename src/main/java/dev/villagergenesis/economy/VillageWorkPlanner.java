package dev.villagergenesis.economy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns a visible-storage snapshot and surveyed farm facts into explainable work proposals.
 * It does not mutate inventories or world blocks; a later executor must perform and verify actions.
 */
public final class VillageWorkPlanner {
    public enum Work {
        HARVEST_MATURE_CROPS,
        REPLANT_HARVESTED_FIELDS,
        PLANT_EMPTY_FARMLAND,
        EXPAND_FARMLAND,
        STORE_SURPLUS_FOOD,
        PROTECT_SEEDS,
        GATHER_BUILDING_MATERIALS,
        NO_ACTION
    }

    public record FarmSurvey(int availableFarmland, int plantedCrops, int matureCrops,
                             int emptyHydratedFarmland, int safeExpansionSites,
                             int seedItems, int residents) {
        public FarmSurvey {
            if (availableFarmland < 0 || plantedCrops < 0 || matureCrops < 0
                    || emptyHydratedFarmland < 0 || safeExpansionSites < 0
                    || seedItems < 0 || residents < 0) {
                throw new IllegalArgumentException("Farm survey values cannot be negative.");
            }
            if (plantedCrops > availableFarmland || matureCrops > plantedCrops
                    || emptyHydratedFarmland > availableFarmland - plantedCrops) {
                throw new IllegalArgumentException("Farm survey counts are inconsistent.");
            }
        }
    }

    public record Proposal(Work work, int priority, String reason) {}

    private VillageWorkPlanner() {}

    public static List<Proposal> plan(VisibleStorageScanner.Snapshot storage, FarmSurvey farm,
                                     double estimatedFoodDays, int targetReserveDays) {
        if (storage == null || farm == null) throw new IllegalArgumentException("Storage and farm survey are required.");
        if (targetReserveDays < 1) throw new IllegalArgumentException("Target reserve must be positive.");

        List<Proposal> proposals = new ArrayList<>();
        boolean foodLow = estimatedFoodDays < targetReserveDays;
        int seeds = Math.max(farm.seedItems(), storage.count(VisibleStorageScanner.Resource.SEEDS));

        if (foodLow && farm.matureCrops() > 0) {
            proposals.add(new Proposal(Work.HARVEST_MATURE_CROPS, 100,
                    "Public food reserves are below target and mature crops are available."));
        }
        if (foodLow && farm.emptyHydratedFarmland() > 0 && seeds > 0) {
            proposals.add(new Proposal(Work.PLANT_EMPTY_FARMLAND, 92,
                    "Food reserves are low and existing watered farmland is empty."));
        }
        if (farm.matureCrops() > 0 && !foodLow) {
            proposals.add(new Proposal(Work.HARVEST_MATURE_CROPS, 55,
                    "Mature crops are ready; harvest only from real crop blocks."));
        }
        if (farm.seedItems() > 0 && farm.plantedCrops() > 0) {
            proposals.add(new Proposal(Work.REPLANT_HARVESTED_FIELDS, 90,
                    "Keep seed stock available for replanting after harvest."));
        }
        if (foodLow && farm.emptyHydratedFarmland() == 0 && farm.safeExpansionSites() > 0) {
            proposals.add(new Proposal(Work.EXPAND_FARMLAND, 80,
                    "Existing usable farmland is insufficient; survey found safe expansion sites."));
        }
        if (seeds <= Math.max(2, farm.residents() / 2)) {
            proposals.add(new Proposal(Work.PROTECT_SEEDS, 88,
                    "Seed reserves are low; do not spend the last seeds on unrelated crafting."));
        }
        if (storage.readyFoodItems() > farm.residents() * targetReserveDays * 2) {
            proposals.add(new Proposal(Work.STORE_SURPLUS_FOOD, 35,
                    "Visible public containers contain food above the configured reserve target."));
        }
        if (proposals.isEmpty()) {
            proposals.add(new Proposal(Work.NO_ACTION, 0,
                    "No urgent farm task can be justified by the current surveyed facts."));
        }

        proposals.sort(Comparator.comparingInt(Proposal::priority).reversed()
                .thenComparing(p -> p.work().name()));
        return List.copyOf(proposals);
    }
}
