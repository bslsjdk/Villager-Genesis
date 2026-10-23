package dev.villagergenesis.society;

import java.util.Objects;

/**
 * Determines whether a group of independently observed villagers is becoming a settlement.
 *
 * This model does not search for existing vanilla villages and does not place blocks. Its input
 * must come from a bounded survey of already-loaded villagers and real local conditions. The
 * caller owns discovery, persistence, and the eventual construction/logistics actions.
 */
public final class SettlementEmergenceModel {
    public enum Stage {
        WANDERING_GROUP,
        GATHERING,
        CAMP,
        VILLAGE
    }

    public record Survey(
            int residents,
            int distinctResidentDays,
            int residentsReturningToArea,
            int usableBeds,
            int shelteredResidents,
            int readyFoodItems,
            int foodSources,
            int activeFarmPlots,
            int completedSharedProjects,
            int availableBuildingMaterials,
            boolean safeSite,
            boolean surveyComplete
    ) {
        public Survey {
            if (residents < 0 || distinctResidentDays < 0 || residentsReturningToArea < 0
                    || usableBeds < 0 || shelteredResidents < 0 || readyFoodItems < 0
                    || foodSources < 0 || activeFarmPlots < 0 || completedSharedProjects < 0
                    || availableBuildingMaterials < 0) {
                throw new IllegalArgumentException("Settlement survey values cannot be negative.");
            }
            if (residentsReturningToArea > residents || usableBeds > residents
                    || shelteredResidents > residents) {
                throw new IllegalArgumentException("Resident-related counts cannot exceed population.");
            }
        }
    }

    public record State(Stage stage, long stageSince, int consecutiveQualifyingSurveys) {
        public State {
            Objects.requireNonNull(stage, "stage");
            if (stageSince < 0 || consecutiveQualifyingSurveys < 0) {
                throw new IllegalArgumentException("State time and survey count cannot be negative.");
            }
        }

        public static State initial(long now) {
            if (now < 0) throw new IllegalArgumentException("Time cannot be negative.");
            return new State(Stage.WANDERING_GROUP, now, 0);
        }
    }

    public record Transition(State state, boolean changed, String reason) {
        public Transition {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private SettlementEmergenceModel() {}

    /**
     * Advances at most one stage per completed survey. Requiring repeated qualifying surveys
     * prevents a passing group or a one-time pile of food from instantly becoming a village.
     */
    public static Transition observe(State current, Survey survey, long now) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(survey, "survey");
        if (now < current.stageSince()) {
            throw new IllegalArgumentException("Game time cannot move backwards.");
        }
        if (!survey.surveyComplete()) {
            return new Transition(current, false, "incomplete survey; preserve prior state");
        }

        Stage nextCandidate = candidateStage(survey);
        if (nextCandidate.ordinal() <= current.stage().ordinal()) {
            // Stages do not regress from a single bad survey. Long-term decline/abandonment
            // should be handled by a separate persisted decay policy.
            return new Transition(new State(current.stage(), current.stageSince(), 0),
                    false, "requirements for further growth are not currently met");
        }

        int streak = current.consecutiveQualifyingSurveys() + 1;
        int required = surveysRequired(nextCandidate);
        if (streak < required) {
            return new Transition(new State(current.stage(), current.stageSince(), streak),
                    false, "growth evidence is not stable yet");
        }

        Stage next = Stage.values()[current.stage().ordinal() + 1];
        return new Transition(new State(next, now, 0), true,
                "stable population, local resources, and shared activity support " + next);
    }

    private static Stage candidateStage(Survey s) {
        if (!s.safeSite() || s.residents() < 2 || s.residentsReturningToArea() < 2) {
            return Stage.WANDERING_GROUP;
        }
        if (s.distinctResidentDays() >= 2 && (s.readyFoodItems() >= s.residents() * 2
                || s.foodSources() > 0 || s.activeFarmPlots() > 0)) {
            return Stage.GATHERING;
        }
        if (s.distinctResidentDays() >= 5 && s.residents() >= 3
                && s.residentsReturningToArea() >= Math.max(3, (s.residents() * 2) / 3)
                && (s.usableBeds() > 0 || s.shelteredResidents() > 0)
                && (s.readyFoodItems() >= s.residents() * 3 || s.foodSources() > 0
                    || s.activeFarmPlots() > 0)
                && (s.completedSharedProjects() > 0 || s.availableBuildingMaterials() >= 16)) {
            return Stage.CAMP;
        }
        if (s.distinctResidentDays() >= 12 && s.residents() >= 5
                && s.residentsReturningToArea() >= Math.max(4, (s.residents() * 3) / 4)
                && s.usableBeds() >= Math.max(3, (s.residents() + 1) / 2)
                && (s.foodSources() > 0 || s.activeFarmPlots() >= 2)
                && s.completedSharedProjects() >= 2
                && s.availableBuildingMaterials() >= 32) {
            return Stage.VILLAGE;
        }
        return Stage.WANDERING_GROUP;
    }

    private static int surveysRequired(Stage stage) {
        return switch (stage) {
            case WANDERING_GROUP -> Integer.MAX_VALUE;
            case GATHERING -> 2;
            case CAMP -> 3;
            case VILLAGE -> 4;
        };
    }
}
