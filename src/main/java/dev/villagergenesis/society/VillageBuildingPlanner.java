package dev.villagergenesis.society;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Evaluates the lifecycle of existing structures against current settlement needs.
 * It plans transitions only; a world executor must verify blocks, occupancy, inventories,
 * materials, and dependencies before changing any structure.
 */
public final class VillageBuildingPlanner {
    public enum Status {
        PLANNED, CONSTRUCTING, ACTIVE, NEEDS_REPAIR, OBSOLETE, DEMOLITION_READY, DEMOLISHED
    }

    public enum Action {
        CONTINUE_CONSTRUCTION, KEEP, REPAIR, RENOVATE, PREPARE_DEMOLITION,
        DEMOLISH, CLEAR_SITE, REBUILD, NO_ACTION
    }

    public record Building(String id, String type, Status status, int conditionPercent,
                           boolean occupied, boolean containsItems, boolean essential,
                           Set<String> dependentBuildingIds, int demolitionPriority) {
        public Building {
            if (id == null || id.isBlank() || type == null || type.isBlank() || status == null) {
                throw new IllegalArgumentException("Building identity and status are required.");
            }
            if (conditionPercent < 0 || conditionPercent > 100) {
                throw new IllegalArgumentException("Condition must be between 0 and 100.");
            }
            dependentBuildingIds = dependentBuildingIds == null ? Set.of() : Set.copyOf(dependentBuildingIds);
        }
    }

    public record Need(String type, int priority, boolean requiresNewSite, boolean urgent) {
        public Need {
            if (type == null || type.isBlank()) throw new IllegalArgumentException("Need type is required.");
        }
    }

    public record Proposal(String buildingId, Action action, int priority, String reason) {}

    private VillageBuildingPlanner() {}

    public static List<Proposal> plan(List<Building> buildings, List<Need> needs,
                                      Set<String> protectedBuildingIds, int availableBuildingMaterials) {
        if (buildings == null || needs == null) throw new IllegalArgumentException("Buildings and needs are required.");
        if (availableBuildingMaterials < 0) throw new IllegalArgumentException("Materials cannot be negative.");
        Set<String> protectedIds = protectedBuildingIds == null ? Set.of() : Set.copyOf(protectedBuildingIds);
        List<Proposal> proposals = new ArrayList<>();

        for (Building building : buildings) {
            if (building.status() == Status.PLANNED || building.status() == Status.CONSTRUCTING) {
                proposals.add(new Proposal(building.id(), Action.CONTINUE_CONSTRUCTION, 60,
                        "Resume only after verifying the real site and available materials."));
                continue;
            }
            if (building.status() == Status.DEMOLISHED) {
                proposals.add(new Proposal(building.id(), Action.CLEAR_SITE, 20,
                        "Verify the old footprint is clear before reusing the site."));
                continue;
            }
            if (building.status() == Status.NEEDS_REPAIR || building.conditionPercent() < 35) {
                proposals.add(new Proposal(building.id(), Action.REPAIR, 80,
                        "Repair may preserve existing materials and is safer than automatic demolition."));
                continue;
            }
            if (building.status() == Status.OBSOLETE || building.status() == Status.DEMOLITION_READY) {
                if (building.essential() || protectedIds.contains(building.id())) {
                    proposals.add(new Proposal(building.id(), Action.RENOVATE, 65,
                            "The structure is protected or essential; adapt it instead of demolishing it."));
                } else if (building.occupied() || building.containsItems()) {
                    proposals.add(new Proposal(building.id(), Action.PREPARE_DEMOLITION, 70,
                            "Move residents and transfer real stored items before demolition."));
                } else if (hasActiveDependents(building, buildings)) {
                    proposals.add(new Proposal(building.id(), Action.RENOVATE, 72,
                            "Another active structure depends on this building; reroute or replace that dependency first."));
                } else {
                    proposals.add(new Proposal(building.id(), Action.DEMOLISH, 50 + building.demolitionPriority(),
                            "Site can be reclaimed after confirming the structure is empty and no active building depends on it."));
                }
            } else {
                proposals.add(new Proposal(building.id(), Action.KEEP, 10,
                        "Keep the usable structure unless a verified redevelopment plan requires a change."));
            }
        }

        for (Need need : needs) {
            boolean alreadyServed = buildings.stream().anyMatch(b ->
                    b.type().equals(need.type()) && b.status() == Status.ACTIVE
                            && b.conditionPercent() >= 35);
            if (!alreadyServed) {
                Action action = availableBuildingMaterials > 0 ? Action.REBUILD : Action.NO_ACTION;
                proposals.add(new Proposal("new:" + need.type(), action,
                        need.priority() + (need.urgent() ? 100 : 0),
                        availableBuildingMaterials > 0
                                ? "A real construction need is unmet; reserve and consume materials only through verified placement."
                                : "Construction need is unmet, but no real building materials are available."));
            }
        }

        proposals.sort(Comparator.comparingInt(Proposal::priority).reversed()
                .thenComparing(Proposal::buildingId));
        return List.copyOf(proposals);
    }

    private static boolean hasActiveDependents(Building building, List<Building> buildings) {
        return buildings.stream().anyMatch(candidate ->
                candidate.status() == Status.ACTIVE
                        && building.dependentBuildingIds().contains(candidate.id()));
    }
}
