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
    void rejectsInvalidSurvey() {
        assertThrows(IllegalArgumentException.class,
                () -> new SettlementStaffingPolicy.Survey(4, -1, 0, 0, 0, 0, 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> new SettlementStaffingPolicy.Survey(-1, 1, 0, 0, 0, 0, 0, true));
    }
}
