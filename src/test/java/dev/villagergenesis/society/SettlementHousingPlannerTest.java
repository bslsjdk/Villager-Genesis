package dev.villagergenesis.society;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementHousingPlannerTest {
    @Test
    void calculatesBedsFromRealCompatibleWoolAndPlankStocks() {
        var plan = SettlementHousingPlanner.plan(
                new SettlementHousingPlanner.Survey(20, 12, 15, 24, 4));
        assertEquals(8, plan.bedShortage());
        assertEquals(5, plan.craftableBeds());
        assertEquals(4, plan.bedsToCraft());
        assertEquals(12, plan.woolNeeded());
        assertEquals(12, plan.planksNeeded());
    }

    @Test
    void doesNotTreatMixedWoolColoursAsCompatibleStock() {
        // Caller supplies the largest matching colour group (2), not the total of mixed colours.
        var plan = SettlementHousingPlanner.plan(
                new SettlementHousingPlanner.Survey(8, 2, 2, 30, 8));
        assertEquals(0, plan.craftableBeds());
        assertEquals(0, plan.bedsToCraft());
        assertTrue(plan.waitingForWool());
        assertFalse(plan.waitingForPlanks());
    }

    @Test
    void limitsConstructionPerPassAndNeverOverbuildsPopulationNeed() {
        var capped = SettlementHousingPlanner.plan(
                new SettlementHousingPlanner.Survey(100, 98, 300, 300, 1));
        assertEquals(2, capped.bedShortage());
        assertEquals(1, capped.bedsToCraft());

        var noShortage = SettlementHousingPlanner.plan(
                new SettlementHousingPlanner.Survey(10, 12, 30, 30, 10));
        assertEquals(0, noShortage.bedShortage());
        assertEquals(0, noShortage.bedsToCraft());
    }

    @Test
    void rejectsNegativeSurveyValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new SettlementHousingPlanner.Survey(1, 0, -1, 0, 1));
    }
}
