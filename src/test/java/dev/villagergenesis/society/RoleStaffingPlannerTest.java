package dev.villagergenesis.society;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RoleStaffingPlannerTest {
    private static UUID id(long value) {
        return new UUID(0L, value);
    }

    @Test
    void keepsIncumbentsWithinTargetAndFillsVacancyByFit() {
        var farmer = new RoleStaffingPlanner.Candidate(id(1),
                VillageRoleService.Role.FARMER, VillageRoleService.Role.FARMER,
                Map.of(VillageRoleService.Role.FARMER, 5));
        var builder = new RoleStaffingPlanner.Candidate(id(2),
                VillageRoleService.Role.BUILDER, null,
                Map.of(VillageRoleService.Role.BUILDER, 10));
        var newcomer = new RoleStaffingPlanner.Candidate(id(3), null,
                VillageRoleService.Role.BUILDER,
                Map.of(VillageRoleService.Role.BUILDER, 1));

        var plan = RoleStaffingPlanner.plan(List.of(newcomer, builder, farmer),
                Map.of(VillageRoleService.Role.FARMER, 1, VillageRoleService.Role.BUILDER, 1));

        assertEquals(VillageRoleService.Role.FARMER, plan.assignments().get(id(1)));
        assertEquals(VillageRoleService.Role.BUILDER, plan.assignments().get(id(2)));
        assertFalse(plan.assignments().containsKey(id(3)));
        assertEquals(0, plan.changes().size());
    }

    @Test
    void reassignsOverflowToTheBestUnfilledRoleDeterministically() {
        var first = new RoleStaffingPlanner.Candidate(id(1),
                VillageRoleService.Role.FARMER, null,
                Map.of(VillageRoleService.Role.BUILDER, 4));
        var second = new RoleStaffingPlanner.Candidate(id(2),
                VillageRoleService.Role.FARMER, VillageRoleService.Role.BUILDER,
                Map.of(VillageRoleService.Role.BUILDER, 1));

        var plan = RoleStaffingPlanner.plan(List.of(second, first),
                Map.of(VillageRoleService.Role.FARMER, 1, VillageRoleService.Role.BUILDER, 1));

        assertEquals(VillageRoleService.Role.FARMER, plan.assignments().get(id(1)));
        assertEquals(VillageRoleService.Role.BUILDER, plan.assignments().get(id(2)));
        assertEquals(1, plan.changes().size());
        assertEquals(id(2), plan.changes().get(0).villagerId());
    }

    @Test
    void rejectsDuplicateResidentsAndNegativeTargets() {
        var resident = new RoleStaffingPlanner.Candidate(id(1), null, null, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> RoleStaffingPlanner.plan(List.of(resident, resident), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> RoleStaffingPlanner.plan(List.of(),
                        Map.of(VillageRoleService.Role.GUARD, -1)));
    }

    @Test
    void outputIsImmutable() {
        var plan = RoleStaffingPlanner.plan(List.of(
                new RoleStaffingPlanner.Candidate(id(1), null, null, Map.of())),
                Map.of(VillageRoleService.Role.FARMER, 1));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.assignments().clear());
    }
}
