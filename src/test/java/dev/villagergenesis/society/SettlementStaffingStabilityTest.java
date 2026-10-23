package dev.villagergenesis.society;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SettlementStaffingStabilityTest {
    @Test
    void changedProposalRestartsStabilityWindow() {
        var initial = SettlementStaffingStability.State.initial("A", 100);
        var changed = SettlementStaffingStability.observe(initial, "B", 150, 40, 100);
        assertFalse(changed.readyToApply());
        assertEquals("B", changed.state().observedFingerprint());
        assertEquals(150, changed.state().observedSince());
    }

    @Test
    void requiresStableWindowBeforeApplication() {
        var state = SettlementStaffingStability.State.initial("A", 100);
        assertFalse(SettlementStaffingStability.observe(state, "A", 139, 40, 0).readyToApply());
        assertTrue(SettlementStaffingStability.observe(state, "A", 140, 40, 0).readyToApply());
    }

    @Test
    void cooldownBeginsOnlyAfterSuccessfulApplication() {
        var state = SettlementStaffingStability.State.initial("A", 100);
        state = SettlementStaffingStability.markApplied(state, "A", 140);
        assertFalse(SettlementStaffingStability.observe(state, "A", 239, 40, 100).readyToApply());
        assertTrue(SettlementStaffingStability.observe(state, "A", 240, 40, 100).readyToApply());
    }

    @Test
    void fingerprintIsDeterministicAndIncludesAllRoles() {
        String first = SettlementStaffingStability.fingerprint(
                Map.of(VillageRoleService.Role.FARMER, 2, VillageRoleService.Role.GATHERER, 1));
        String second = SettlementStaffingStability.fingerprint(
                Map.of(VillageRoleService.Role.GATHERER, 1, VillageRoleService.Role.FARMER, 2));
        assertEquals(first, second);
        assertTrue(first.contains("farmer=2"));
        assertTrue(first.contains("guard=0"));
    }

    @Test
    void rejectsBackwardsTimeAndInvalidTargets() {
        var state = SettlementStaffingStability.State.initial("A", 100);
        assertThrows(IllegalArgumentException.class,
                () -> SettlementStaffingStability.observe(state, "A", 99, 10, 10));
        assertThrows(IllegalArgumentException.class,
                () -> SettlementStaffingStability.fingerprint(Map.of(VillageRoleService.Role.FARMER, -1)));
    }
}
