package dev.villagergenesis.society;

import dev.villagergenesis.economy.VisibleStorageScanner;

import java.util.EnumMap;
import java.util.Map;

/**
 * Converts a settlement survey into bounded role targets. Inputs must be measured from loaded,
 * known settlement residents, real visible storage, and surveyed housing/farm facts.
 * This class never loads chunks or changes villager roles.
 */
public final class SettlementStaffingPolicy {
    public record Survey(int residents, double foodDays, int matureCrops,
                         int emptyHydratedFarmland, int unhousedResidents,
                         int availableWoodItems, int availableStoneItems,
                         boolean storageSurveyComplete) {
        public Survey {
            if (residents < 0 || matureCrops < 0 || emptyHydratedFarmland < 0
                    || unhousedResidents < 0 || availableWoodItems < 0
                    || availableStoneItems < 0) {
                throw new IllegalArgumentException("Survey counts cannot be negative.");
            }
            if (Double.isNaN(foodDays) || foodDays < 0) {
                throw new IllegalArgumentException("Food reserve days must be non-negative.");
            }
            if (matureCrops > residents * 64L + 4096L) {
                throw new IllegalArgumentException("Mature crop survey is implausibly large.");
            }
        }
    }

    private static final int FOOD_RESERVE_DAYS = 3;

    private SettlementStaffingPolicy() {}

    public static Map<VillageRoleService.Role, Integer> desiredSlots(Survey survey) {
        if (survey == null) throw new IllegalArgumentException("Settlement survey is required.");
        EnumMap<VillageRoleService.Role, Integer> targets =
                new EnumMap<>(VillageRoleService.Role.class);
        for (VillageRoleService.Role role : VillageRoleService.Role.values()) targets.put(role, 0);
        if (survey.residents() == 0) return Map.copyOf(targets);

        // Keep at least one general gatherer for small settlements; scale slowly with population.
        targets.put(VillageRoleService.Role.GATHERER, Math.max(1, survey.residents() / 8));
        targets.put(VillageRoleService.Role.CARETAKER, survey.residents() >= 8 ? 1 : 0);

        boolean foodShort = survey.foodDays() < FOOD_RESERVE_DAYS;
        if (foodShort) {
            int farmers = Math.max(1, (survey.residents() + 3) / 4);
            if (survey.matureCrops() == 0 && survey.emptyHydratedFarmland() == 0) {
                // Do not staff many farmers when there is no surveyed work to do.
                farmers = 1;
            }
            targets.put(VillageRoleService.Role.FARMER, farmers);
        } else if (survey.matureCrops() > 0 || survey.emptyHydratedFarmland() > 0) {
            targets.put(VillageRoleService.Role.FARMER, Math.max(1, survey.residents() / 6));
        }

        if (survey.unhousedResidents() > 0) {
            targets.put(VillageRoleService.Role.BUILDER,
                    Math.min(Math.max(1, survey.unhousedResidents()), Math.max(1, survey.residents() / 3)));
        }
        if (survey.storageSurveyComplete()) {
            if (survey.availableWoodItems() < Math.max(16, survey.residents() * 4)) {
                targets.put(VillageRoleService.Role.LUMBERJACK, 1);
            }
            if (survey.availableStoneItems() < Math.max(16, survey.residents() * 3)) {
                targets.put(VillageRoleService.Role.MASON, 1);
            }
            if (survey.availableWoodItems() + survey.availableStoneItems() >= 32
                    && survey.residents() >= 6) {
                targets.put(VillageRoleService.Role.CRAFTER, 1);
            }
        }

        targets.put(VillageRoleService.Role.MAYOR, 1);
        // Keep non-violent specialist roles bounded; this policy does not decide combat behavior.
        if (survey.residents() >= 10) targets.put(VillageRoleService.Role.SCOUT, 1);

        // Never request more staff than there are residents. Trim lowest-priority optional slots
        // first, preserving food, housing, and baseline gathering work.
        int total = targets.values().stream().mapToInt(Integer::intValue).sum();
        VillageRoleService.Role[] trimOrder = {
                VillageRoleService.Role.SCOUT, VillageRoleService.Role.CARETAKER,
                VillageRoleService.Role.CRAFTER, VillageRoleService.Role.MASON,
                VillageRoleService.Role.LUMBERJACK, VillageRoleService.Role.MAYOR
        };
        for (VillageRoleService.Role role : trimOrder) {
            if (total <= survey.residents()) break;
            if (targets.get(role) > 0) {
                targets.put(role, targets.get(role) - 1);
                total--;
            }
        }
        return Map.copyOf(targets);
    }
}
