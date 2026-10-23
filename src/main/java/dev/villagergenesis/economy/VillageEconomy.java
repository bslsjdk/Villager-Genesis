package dev.villagergenesis.economy;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Deterministic settlement ledger. Counts are planning facts, not generated items. */
public final class VillageEconomy {
    public static final long TICKS_PER_DAY = 24_000L;

    public enum Resource {
        WHEAT, BREAD, CARROTS, POTATOES, BEETROOT, SEEDS, WOOD, STONE,
        COAL, RAW_IRON, RAW_COPPER, RAW_GOLD, IRON_INGOTS, COPPER_INGOTS, GOLD_INGOTS,
        PICKAXES, AXES, TOOLS
    }

    public enum Action {
        HARVEST_MATURE_CROPS, PLANT_AVAILABLE_FIELDS, EXPAND_FARMLAND,
        PROTECT_SEED_RESERVE, GATHER_BUILDING_MATERIALS, GATHER_WOOD,
        MINE_ORE, SMELT_ORE, CRAFT_TOOLS, NO_ACTION
    }

    private final EnumMap<Resource, Integer> stock = new EnumMap<>(Resource.class);
    private int residents;
    private int farmCapacity;
    private int plantedFields;
    private int matureFields;
    private int seedReserve;
    private int foodUnitsPerResidentPerDay;
    private int targetReserveDays;

    public VillageEconomy(int residents, int farmCapacity, int plantedFields,
                          int matureFields, int seedReserve,
                          int foodUnitsPerResidentPerDay, int targetReserveDays) {
        setResidents(residents);
        setFarmCapacity(farmCapacity);
        setPlantedFields(plantedFields);
        setMatureFields(matureFields);
        setSeedReserve(seedReserve);
        if (foodUnitsPerResidentPerDay < 0 || targetReserveDays < 1) {
            throw new IllegalArgumentException("Food consumption must be non-negative and reserve days positive.");
        }
        this.foodUnitsPerResidentPerDay = foodUnitsPerResidentPerDay;
        this.targetReserveDays = targetReserveDays;
        for (Resource resource : Resource.values()) stock.put(resource, 0);
    }

    /** Replaces the ledger with counts from a real public-container snapshot. */
    public void synchronizeVisibleStorage(VisibleStorageScanner.Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("A real storage snapshot is required.");
        setStock(Resource.BREAD, snapshot.count(VisibleStorageScanner.Resource.BREAD));
        setStock(Resource.CARROTS, snapshot.count(VisibleStorageScanner.Resource.CARROTS));
        setStock(Resource.POTATOES, snapshot.count(VisibleStorageScanner.Resource.POTATOES));
        setStock(Resource.BEETROOT, snapshot.count(VisibleStorageScanner.Resource.BEETROOT));
        setStock(Resource.WHEAT, snapshot.count(VisibleStorageScanner.Resource.WHEAT));
        setStock(Resource.SEEDS, snapshot.count(VisibleStorageScanner.Resource.SEEDS));
        setStock(Resource.WOOD, snapshot.count(VisibleStorageScanner.Resource.WOOD));
        setStock(Resource.STONE, snapshot.count(VisibleStorageScanner.Resource.STONE));
        setStock(Resource.COAL, snapshot.count(VisibleStorageScanner.Resource.COAL));
        setStock(Resource.RAW_IRON, snapshot.count(VisibleStorageScanner.Resource.RAW_IRON));
        setStock(Resource.RAW_COPPER, snapshot.count(VisibleStorageScanner.Resource.RAW_COPPER));
        setStock(Resource.RAW_GOLD, snapshot.count(VisibleStorageScanner.Resource.RAW_GOLD));
        setStock(Resource.IRON_INGOTS, snapshot.count(VisibleStorageScanner.Resource.IRON_INGOTS));
        setStock(Resource.COPPER_INGOTS, snapshot.count(VisibleStorageScanner.Resource.COPPER_INGOTS));
        setStock(Resource.GOLD_INGOTS, snapshot.count(VisibleStorageScanner.Resource.GOLD_INGOTS));
        setStock(Resource.PICKAXES, snapshot.count(VisibleStorageScanner.Resource.PICKAXES));
        setStock(Resource.AXES, snapshot.count(VisibleStorageScanner.Resource.AXES));
    }

    public void setStock(Resource resource, int count) {
        if (resource == null || count < 0) throw new IllegalArgumentException("Resource and non-negative count required.");
        stock.put(resource, count);
    }
    public int stock(Resource resource) { return stock.getOrDefault(resource, 0); }
    public Map<Resource, Integer> stockSnapshot() { return Collections.unmodifiableMap(new EnumMap<>(stock)); }
    public int residents() { return residents; }
    public int farmCapacity() { return farmCapacity; }
    public int plantedFields() { return plantedFields; }
    public int matureFields() { return matureFields; }
    public int seedReserve() { return seedReserve; }

    public void setResidents(int value) {
        if (value < 0) throw new IllegalArgumentException("Residents cannot be negative.");
        residents = value;
    }
    public void setFarmCapacity(int value) {
        if (value < 0) throw new IllegalArgumentException("Farm capacity cannot be negative.");
        farmCapacity = value;
        plantedFields = Math.min(plantedFields, farmCapacity);
        matureFields = Math.min(matureFields, plantedFields);
    }
    public void setPlantedFields(int value) {
        if (value < 0 || value > farmCapacity) throw new IllegalArgumentException("Planted fields must fit farm capacity.");
        plantedFields = value;
        matureFields = Math.min(matureFields, plantedFields);
    }
    public void setMatureFields(int value) {
        if (value < 0 || value > plantedFields) throw new IllegalArgumentException("Mature fields must be planted.");
        matureFields = value;
    }
    public void setSeedReserve(int value) {
        if (value < 0) throw new IllegalArgumentException("Seed reserve cannot be negative.");
        seedReserve = value;
    }
    public int availableFoodUnits() {
        return stock(Resource.BREAD) + stock(Resource.CARROTS) + stock(Resource.POTATOES)
                + stock(Resource.BEETROOT);
    }
    public double estimatedFoodDays() {
        long dailyDemand = (long) residents * foodUnitsPerResidentPerDay;
        if (dailyDemand == 0) return Double.POSITIVE_INFINITY;
        return (double) availableFoodUnits() / dailyDemand;
    }
    public boolean foodShortageExpected() { return estimatedFoodDays() < targetReserveDays; }

    /** Prioritized proposal only; execution must be backed by world observations and real items. */
    public Action nextAction() {
        if (foodShortageExpected() && matureFields > 0) return Action.HARVEST_MATURE_CROPS;
        if (foodShortageExpected() && plantedFields < farmCapacity && seedReserve > 0) return Action.PLANT_AVAILABLE_FIELDS;
        if (foodShortageExpected() && plantedFields >= farmCapacity) return Action.EXPAND_FARMLAND;
        if (seedReserve <= Math.max(1, residents / 2)) return Action.PROTECT_SEED_RESERVE;
        if (stock(Resource.AXES) == 0 && stock(Resource.WOOD) < Math.max(8, residents * 2)) {
            return Action.CRAFT_TOOLS;
        }
        if (stock(Resource.WOOD) < Math.max(8, residents * 2)) return Action.GATHER_WOOD;
        if (stock(Resource.PICKAXES) == 0 && stock(Resource.RAW_IRON) + stock(Resource.IRON_INGOTS) > 0) {
            return Action.CRAFT_TOOLS;
        }
        if (stock(Resource.RAW_IRON) + stock(Resource.RAW_COPPER) + stock(Resource.RAW_GOLD) < residents) {
            return Action.MINE_ORE;
        }
        if (stock(Resource.WOOD) < Math.max(8, residents * 2) || stock(Resource.STONE) < residents) {
            return Action.GATHER_BUILDING_MATERIALS;
        }
        return Action.NO_ACTION;
    }

    public int fieldsNeededForTargetReserve(int expectedFoodUnitsPerField) {
        if (expectedFoodUnitsPerField <= 0) throw new IllegalArgumentException("Expected yield must be positive.");
        long targetUnits = (long) residents * foodUnitsPerResidentPerDay * targetReserveDays;
        long shortage = Math.max(0L, targetUnits - availableFoodUnits());
        return (int) Math.min(Integer.MAX_VALUE, (shortage + expectedFoodUnitsPerField - 1) / expectedFoodUnitsPerField);
    }
}
