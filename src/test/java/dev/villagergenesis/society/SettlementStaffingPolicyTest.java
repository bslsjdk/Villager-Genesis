package dev.villagergenesis.society;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettlementStaffingPolicyTest {
    @Test
    void emptySettlementDoesNotInventStaff() {
        var targets = SettlementStaffingPolicy.desiredSlots(
                new SettlementStaffingPolicy.Survey(0, 0, 0, 0, 0, 0, 0, false));
        assertTrue(targets.values().stream().allMatch(count -> count == 0));
    }

    @Test
    void foodAndHousingShortagesIncreaseRelevantStaffingTargets() {
        var targets = SettlementStaffingPolicy.desiredSlots(
                new SettlementStaffingPolicy.Survey(12, 1, 4, 8, 3, 100, 100, true));
        assertTrue(targets.get(VillageRoleService.Role.FARMER) >= 1);
        assertTrue(targets.get(VillageRoleService.Role.BUILDER) >= 1);
        assertEquals(1, targets.get(VillageRoleService.Role.MAYOR));
        assertTrue(targets.values().stream().mapToInt(Integer::intValue).sum() <= 12);
    }

    @Test
    void incompleteStorageSurveyDoesNotTriggerMaterialShortageRoles() {
        var targets = SettlementStaffingPolicy.desiredSlots(
                new SettlementStaffingPolicy.Survey(8, 1, 0, 0, 0, 0, 0, false));
        assertEquals(0, targets.get(VillageRoleService.Role.LUMBERJACK));
        assertEquals(0, targets.get(VillageRoleService.Role.MASON));
    }

    @Test
    void staffingTargetsNeverExceedTinyPopulation() {
        var targets = SettlementStaffingPolicy.desiredSlots(
                new SettlementStaffingPolicy.Survey(1, 0, 0, 0, 1, 0, 0, true));
        assertTrue(targets.values().stream().mapToInt(Integer::intValue).sum() <= 1);
    }

    @Test
    void foundingPairDoesNotSpendOneResidentOnMayorAndNeverOverstaffs() {
        var targets = SettlementStaffingPolicy.desiredSlots(
                new SettlementStaffingPolicy.Survey(2, 0.5, 0, 0, 2, 0, 0, true,
                        3, 0, 0, 0, 0));
        assertEquals(0, targets.get(VillageRoleService.Role.MAYOR));
        assertTrue(targets.values().stream().mapToInt(Integer::intValue).sum() <= 2);
        assertTrue(targets.get(VillageRoleService.Role.FARMER) >= 1);
    }

    @Test
    void allRoleTargetsStayWithinPopulationWhenEveryNeedIsUrgent() {
        var targets = SettlementStaffingPolicy.desiredSlots(
                new SettlementStaffingPolicy.Survey(3, 0, 0, 0, 3, 0, 0, true,
                        8, 4, 0, 0, 0));
        assertTrue(targets.values().stream().mapToInt(Integer::intValue).sum() <= 3);
    }

    @Test
    void animalRolesRequireEvidenceAndScaleWithinPopulation() {
        var noAnimals = SettlementStaffingPolicy.desiredSlots(
                new SettlementStaffingPolicy.Survey(12, 0.5, 0, 0, 0, 100, 100, true));
        assertEquals(0, noAnimals.get(VillageRoleService.Role.HUNTER));
        assertEquals(0, noAnimals.get(VillageRoleService.Role.HERDER));

        var surveyed = SettlementStaffingPolicy.desiredSlots(
                new SettlementStaffingPolicy.Survey(12, 0.5, 0, 0, 0,
                        100, 100, true, 8, 3, 0, 12, 0));
        assertEquals(2, surveyed.get(VillageRoleService.Role.HUNTER));
        assertEquals(1, surveyed.get(VillageRoleService.Role.HERDER));
        assertTrue(surveyed.values().stream().mapToInt(Integer::intValue).sum() <= 12);
    }

    @Test
    void rejectsNegativeAnimalAndHousingEvidence() {
        assertThrows(IllegalArgumentException.class,
                () -> new SettlementStaffingPolicy.Survey(4, 1, 0, 0, 0,
                        0, 0, true, -1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SettlementStaffingPolicy.Survey(4, 1, 0, 0, 0,
                        0, 0, true, 0, 0, 0, 0, -1));
    }

    @Test
    void rejectsInvalidSurvey() {
        assertThrows(IllegalArgumentException.class,
                () -> new SettlementStaffingPolicy.Survey(4, -1, 0, 0, 0, 0, 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> new SettlementStaffingPolicy.Survey(-1, 1, 0, 0, 0, 0, 0, true));
    }
}
