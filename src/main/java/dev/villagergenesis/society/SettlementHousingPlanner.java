package dev.villagergenesis.society;

/**
 * Pure, bounded housing-material planner. Inputs must come from a real settlement survey.
 * Wool is the count of the largest compatible same-colour wool group, not all wool summed
 * across colours, because vanilla bed recipes require three matching wool blocks.
 */
public final class SettlementHousingPlanner {
    public record Survey(int residents, int usableBeds, int compatibleWoolItems,
                         int availablePlanks, int maxBedsPerPlanningPass) {
        public Survey {
            if (residents < 0 || usableBeds < 0 || compatibleWoolItems < 0
                    || availablePlanks < 0 || maxBedsPerPlanningPass < 0) {
                throw new IllegalArgumentException("Housing survey values cannot be negative.");
            }
        }
    }

    public record Plan(int bedShortage, int craftableBeds, int bedsToCraft,
                       int woolNeeded, int planksNeeded, boolean waitingForWool,
                       boolean waitingForPlanks) {}

    private SettlementHousingPlanner() {}

    public static Plan plan(Survey survey) {
        if (survey == null) throw new IllegalArgumentException("Housing survey is required.");
        int shortage = Math.max(0, survey.residents() - survey.usableBeds());
        int craftable = Math.min(survey.compatibleWoolItems() / 3,
                survey.availablePlanks() / 3);
        int toCraft = Math.min(shortage, Math.min(craftable, survey.maxBedsPerPlanningPass()));
        return new Plan(shortage, craftable, toCraft, toCraft * 3, toCraft * 3,
                shortage > 0 && survey.compatibleWoolItems() < 3,
                shortage > 0 && survey.availablePlanks() < 3);
    }
}
