package dev.villagergenesis.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VillageEconomyTest {
    @Test
    void detectsFoodShortageAndHarvestsBeforeExpanding() {
        VillageEconomy economy = new VillageEconomy(10, 12, 8, 3, 8, 2, 3);
        economy.setStock(VillageEconomy.Resource.BREAD, 10);
        assertTrue(economy.foodShortageExpected());
        assertEquals(VillageEconomy.Action.HARVEST_MATURE_CROPS, economy.nextAction());
    }

    @Test
    void expandsOnlyWhenFoodIsLowAndAllAvailableFieldsAreUsed() {
        VillageEconomy economy = new VillageEconomy(10, 8, 8, 0, 10, 2, 3);
        assertEquals(VillageEconomy.Action.EXPAND_FARMLAND, economy.nextAction());
    }

    @Test
    void protectsSeedReserveInsteadOfTreatingSeedsAsFood() {
        VillageEconomy economy = new VillageEconomy(2, 10, 2, 0, 1, 0, 3);
        assertEquals(VillageEconomy.Action.PROTECT_SEED_RESERVE, economy.nextAction());
    }

    @Test
    void calculatesAdditionalFieldsNeededForReserveTarget() {
        VillageEconomy economy = new VillageEconomy(5, 10, 5, 0, 10, 2, 4);
        economy.setStock(VillageEconomy.Resource.BREAD, 20);
        assertEquals(2, economy.fieldsNeededForTargetReserve(10));
    }
    @Test
    void prioritizesToolSupplyWhenWoodIsLowAndNoAxesAreStocked() {
        VillageEconomy economy = new VillageEconomy(4, 8, 4, 0, 8, 0, 3);
        economy.setStock(VillageEconomy.Resource.WOOD, 2);
        assertEquals(VillageEconomy.Action.CRAFT_TOOLS, economy.nextAction());
    }

    @Test
    void prioritizesMiningWhenMetalReserveIsLowAfterToolAndFoodNeeds() {
        VillageEconomy economy = new VillageEconomy(4, 8, 4, 0, 8, 0, 3);
        economy.setStock(VillageEconomy.Resource.AXES, 2);
        economy.setStock(VillageEconomy.Resource.PICKAXES, 2);
        economy.setStock(VillageEconomy.Resource.WOOD, 20);
        economy.setStock(VillageEconomy.Resource.STONE, 20);
        assertEquals(VillageEconomy.Action.MINE_ORE, economy.nextAction());
    }

}
