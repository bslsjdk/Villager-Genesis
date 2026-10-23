package dev.villagergenesis.society;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic, side-effect-free staffing proposal. It does not mutate villager data;
 * callers must provide a settlement-scoped roster and explicitly apply the returned assignments.
 */
public final class RoleStaffingPlanner {
    public record Candidate(UUID id, VillageRoleService.Role currentRole,
                            VillageRoleService.Role vanillaProfessionRole,
                            Map<VillageRoleService.Role, Integer> skillScores) {
        public Candidate {
            if (id == null) throw new IllegalArgumentException("Candidate id cannot be null.");
            skillScores = skillScores == null ? Map.of() : Map.copyOf(skillScores);
            if (skillScores.values().stream().anyMatch(score -> score == null || score < 0)) {
                throw new IllegalArgumentException("Skill scores must be non-negative.");
            }
        }

        int suitability(VillageRoleService.Role role) {
            return skillScores.getOrDefault(role, 0)
                    + (vanillaProfessionRole == role ? 25 : 0);
        }
    }

    public record Change(UUID villagerId, VillageRoleService.Role previousRole,
                         VillageRoleService.Role assignedRole, String reason) {}

    public record Plan(Map<UUID, VillageRoleService.Role> assignments, List<Change> changes) {
        public Plan {
            assignments = Map.copyOf(assignments);
            changes = List.copyOf(changes);
        }
    }

    private static final Comparator<Candidate> STABLE_ID_ORDER =
            Comparator.comparing(Candidate::id);

    private RoleStaffingPlanner() {}

    /**
     * Preserve eligible incumbents first, then fill vacancies by skill/profession fit.
     * Unfilled residents remain unassigned in this proposal; the caller decides whether to
     * preserve them, retrain them, or defer changes. Every target count is a hard upper bound.
     */
    public static Plan plan(List<Candidate> roster,
                            Map<VillageRoleService.Role, Integer> desiredSlots) {
        if (roster == null || desiredSlots == null) {
            throw new IllegalArgumentException("Roster and staffing targets are required.");
        }

        EnumMap<VillageRoleService.Role, Integer> targets =
                new EnumMap<>(VillageRoleService.Role.class);
        for (VillageRoleService.Role role : VillageRoleService.Role.values()) {
            int count = desiredSlots.getOrDefault(role, 0);
            if (count < 0) throw new IllegalArgumentException("Staffing targets cannot be negative.");
            targets.put(role, count);
        }

        Set<UUID> ids = new HashSet<>();
        for (Candidate candidate : roster) {
            if (candidate == null || !ids.add(candidate.id())) {
                throw new IllegalArgumentException("Roster candidates must be non-null with unique UUIDs.");
            }
        }

        List<Candidate> ordered = new ArrayList<>(roster);
        ordered.sort(STABLE_ID_ORDER);
        Map<UUID, VillageRoleService.Role> assignments = new HashMap<>();
        Set<UUID> remaining = new HashSet<>(ids);

        // First retain existing staff, preferring better role-specific skill when over capacity.
        for (VillageRoleService.Role role : VillageRoleService.Role.values()) {
            List<Candidate> incumbents = ordered.stream()
                    .filter(c -> c.currentRole() == role)
                    .sorted(Comparator.<Candidate>comparingInt(c -> c.suitability(role)).reversed()
                            .thenComparing(Candidate::id))
                    .toList();
            int keep = Math.min(targets.get(role), incumbents.size());
            for (int i = 0; i < keep; i++) {
                Candidate candidate = incumbents.get(i);
                assignments.put(candidate.id(), role);
                remaining.remove(candidate.id());
            }
        }

        // Then fill each shortage with the best available fit, using UUID for deterministic ties.
        for (VillageRoleService.Role role : VillageRoleService.Role.values()) {
            int vacancy = targets.get(role) - countAssigned(assignments, role);
            while (vacancy > 0 && !remaining.isEmpty()) {
                Candidate best = ordered.stream()
                        .filter(c -> remaining.contains(c.id()))
                        .max(Comparator.<Candidate>comparingInt(c -> c.suitability(role))
                                .thenComparing(Candidate::id, Comparator.reverseOrder()))
                        .orElse(null);
                if (best == null) break;
                assignments.put(best.id(), role);
                remaining.remove(best.id());
                vacancy--;
            }
        }

        List<Change> changes = new ArrayList<>();
        for (Candidate candidate : ordered) {
            VillageRoleService.Role next = assignments.get(candidate.id());
            if (candidate.currentRole() != next) {
                String reason = next == null
                        ? "No open staffing slot matches the current settlement targets."
                        : candidate.vanillaProfessionRole() == next
                            ? "Filled a settlement vacancy matching the vanilla profession."
                            : "Filled a settlement vacancy using deterministic role-fit ranking.";
                changes.add(new Change(candidate.id(), candidate.currentRole(), next, reason));
            }
        }
        return new Plan(assignments, changes);
    }

    private static int countAssigned(Map<UUID, VillageRoleService.Role> assignments,
                                     VillageRoleService.Role role) {
        return (int) assignments.values().stream().filter(role::equals).count();
    }
}
