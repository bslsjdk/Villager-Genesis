package dev.villagergenesis.planning;

import dev.villagergenesis.memory.VillagerMemory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Explainable, deterministic goal selection.
 *
 * The planner only proposes intent. It never navigates, changes inventories, or edits the world.
 * Urgent survival needs outrank exploration and learning; a small continuity bonus prevents
 * harmless goal oscillation between otherwise similar choices.
 */
public final class GoalPlanner {
    private static final int URGENT_THREAT = 65;
    private static final int CRITICAL_HEALTH = 25;

    private GoalPlanner() {}

    public record Context(
            int threatLevel,
            int healthPercent,
            double foodDays,
            boolean shelterKnownSafe,
            boolean learningOpportunity,
            boolean nearbySettlement,
            int explorationValue,
            int observationValue
    ) {
        public Context {
            threatLevel = clamp(threatLevel, 0, 100);
            healthPercent = clamp(healthPercent, 0, 100);
            if (!Double.isFinite(foodDays)) foodDays = 3650.0;
            foodDays = Math.max(0.0, Math.min(3650.0, foodDays));
            explorationValue = clamp(explorationValue, 0, 100);
            observationValue = clamp(observationValue, 0, 100);
        }

        /** Neutral context for callers that only have the compact memory snapshot. */
        public static Context memoryOnly() {
            return new Context(0, 100, 3650.0, true, false, false, 0, 0);
        }
    }

    public record Candidate(String goal, int score, String reason) {}

    /**
     * Per-goal observed outcomes. Counters are bounded before they affect ranking so a long-lived
     * villager cannot let old history overwhelm urgent survival needs.
     */
    public record Feedback(
            String goal,
            int successes,
            int failures,
            int interruptions,
            String lastResult
    ) {
        public Feedback {
            goal = goal == null ? "" : goal;
            successes = clamp(successes, 0, 4);
            failures = clamp(failures, 0, 4);
            interruptions = clamp(interruptions, 0, 4);
            if (!"success".equals(lastResult) && !"failure".equals(lastResult)
                    && !"interrupted".equals(lastResult)) {
                lastResult = "";
            }
        }

        public static Feedback none() {
            return new Feedback("", 0, 0, 0, "");
        }

        private int scoreAdjustment() {
            int adjustment = successes * 3 - failures * 5 - interruptions * 2;
            if ("success".equals(lastResult)) adjustment += 3;
            else if ("failure".equals(lastResult)) adjustment -= 4;
            else if ("interrupted".equals(lastResult)) adjustment -= 1;
            return clamp(adjustment, -24, 15);
        }
    }

    public static Optional<String> chooseGoal(VillagerMemory.Snapshot memory) {
        return chooseGoal(memory, Context.memoryOnly());
    }

    public static Optional<String> chooseGoal(VillagerMemory.Snapshot memory, Context context) {
        if (memory == null) return Optional.empty();
        List<Candidate> ranked = rankGoals(memory, context);
        return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked.get(0).goal());
    }

    /** Returns candidates in deterministic score order, including concise reasons for debugging. */
    public static List<Candidate> rankGoals(VillagerMemory.Snapshot memory, Context context) {
        return rankGoals(memory, context, List.of());
    }

    public static List<Candidate> rankGoals(
            VillagerMemory.Snapshot memory, Context context, List<Feedback> feedback) {
        if (memory == null) return List.of();
        Context facts = context == null ? Context.memoryOnly() : context;
        List<Feedback> outcomes = feedback == null ? List.of() : feedback;
        List<Candidate> candidates = new ArrayList<>();

        if (facts.threatLevel() >= URGENT_THREAT || facts.healthPercent() <= CRITICAL_HEALTH) {
            candidates.add(new Candidate("retreat",
                    120 + facts.threatLevel() + (100 - facts.healthPercent()),
                    "Immediate danger or critically low health takes priority."));
        }

        if (facts.foodDays() <= 2.0) {
            int shortage = (int) Math.ceil((2.0 - facts.foodDays()) * 20.0);
            candidates.add(new Candidate("seek_food", 90 + shortage,
                    "Estimated food reserves are below two days."));
        }

        if (!facts.shelterKnownSafe() && (facts.threatLevel() >= 30 || facts.healthPercent() < 60)) {
            candidates.add(new Candidate("seek_shelter", 75 + facts.threatLevel() / 2,
                    "No safe shelter is known under risky conditions."));
        }

        if (facts.learningOpportunity() && facts.threatLevel() < 50) {
            candidates.add(new Candidate("learn", 45 + facts.observationValue() / 4,
                    "A learning opportunity is available and immediate danger is limited."));
        }

        if (facts.nearbySettlement() && facts.threatLevel() < 60) {
            candidates.add(new Candidate("cooperate", 40,
                    "A nearby settlement makes coordination potentially useful."));
        }

        // Movement alone is not evidence of exploration need: vanilla work, panic, trading,
        // and routine wandering all change position. Require an explicit exploration signal.
        if (facts.explorationValue() >= 20 && facts.threatLevel() < URGENT_THREAT) {
            candidates.add(new Candidate("explore",
                    35 + facts.explorationValue() / 2,
                    "An explicit exploration signal justifies checking the surrounding area."));
        }

        if (memory.observations() >= 5) {
            candidates.add(new Candidate("observe", 30 + facts.observationValue() / 2,
                    "Enough prior observations exist to compare new evidence."));
        } else {
            candidates.add(new Candidate("initialize", 25,
                    "Collect a small baseline before proposing complex behavior."));
        }

        if (candidates.isEmpty()) {
            candidates.add(new Candidate("observe", 20,
                    "Gather more evidence because no higher-priority goal is justified."));
        }

        // Apply observed outcomes only to the same goal. This feedback is advisory and bounded;
        // urgent candidates are never penalized by historical failures or interruptions.
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (!isUrgent(candidate.goal())) {
                int adjustment = outcomes.stream()
                        .filter(outcome -> candidate.goal().equals(outcome.goal()))
                        .findFirst().map(Feedback::scoreAdjustment).orElse(0);
                if (adjustment != 0) {
                    candidates.set(i, new Candidate(candidate.goal(), candidate.score() + adjustment,
                            candidate.reason() + " Bounded outcome feedback adjusted this candidate."));
                }
            }
        }

        String current = memory.currentGoal();
        if (current != null && !current.isBlank()) {
            for (int i = 0; i < candidates.size(); i++) {
                Candidate candidate = candidates.get(i);
                if (candidate.goal().equals(current) && !isUrgent(candidate.goal())) {
                    candidates.set(i, new Candidate(candidate.goal(), candidate.score() + 4,
                            candidate.reason() + " A small continuity bonus avoids unnecessary goal switching."));
                }
            }
        }

        candidates.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparing(Candidate::goal));
        return List.copyOf(candidates);
    }

    private static boolean isUrgent(String goal) {
        return "retreat".equals(goal) || "seek_food".equals(goal) || "seek_shelter".equals(goal);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
