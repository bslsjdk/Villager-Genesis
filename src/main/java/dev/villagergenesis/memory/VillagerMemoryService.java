package dev.villagergenesis.memory;

import dev.villagergenesis.planning.GoalPlanner;
import dev.villagergenesis.ai.VillageKnowledgeSharingService;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side adapter between live Minecraft villagers and the civilization simulation.
 * A stable UUID-based phase spreads observations across ticks instead of synchronizing every
 * villager's bookkeeping work on the same tick.
 */
public final class VillagerMemoryService {
    private static final int OBSERVATION_INTERVAL = 20;

    private VillagerMemoryService() {}

    public static void onEntityTickPost(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Villager villager) || villager.level().isClientSide) {
            return;
        }

        long gameTime = villager.level().getGameTime();
        int phase = Math.floorMod(villager.getUUID().hashCode(), OBSERVATION_INTERVAL);
        if (Math.floorMod(gameTime, OBSERVATION_INTERVAL) != phase) {
            return;
        }

        VillagerMemory memory = VillagerMemory.forVillager(villager);
        memory.observe(villager);

        // Use only cheap facts already available on the entity. Do not scan nearby chunks/entities
        // just to make a decision; richer threat/food/shelter observations can be injected later.
        float maxHealth = villager.getMaxHealth();
        int healthPercent = maxHealth <= 0.0F ? 100
                : Math.round(100.0F * villager.getHealth() / maxHealth);
        int threatEstimate = healthPercent <= 35 ? 75 : 0;
        GoalPlanner.Context context = new GoalPlanner.Context(
                threatEstimate, healthPercent, 3650.0,
                true, false, false, 0, Math.min(100, memory.observations()));
        // Planner output is a suggestion, not proof that an action started. Keep it separate
        // from the executor-owned active-goal key, and always re-evaluate on scheduled observations
        // so an old "explore" goal cannot suppress an urgent retreat recommendation.
        String activeGoal = villager.getPersistentData().getString("villager_genesis_current_goal");
        GoalPlanner.rankGoals(memory.snapshot(activeGoal), context, outcomeFeedback(memory)).stream()
                .findFirst().ifPresent(candidate ->
                        memory.recordSuggestion(candidate.goal(), candidate.score(), candidate.reason(), gameTime));
        VillageKnowledgeSharingService.tryTeachOne(villager);
    }

    private static List<GoalPlanner.Feedback> outcomeFeedback(VillagerMemory memory) {
        List<GoalPlanner.Feedback> feedback = new ArrayList<>();
        String[] goals = {
                "retreat", "seek_food", "seek_shelter", "learn",
                "cooperate", "explore", "observe", "initialize"
        };
        for (String goal : goals) {
            String lastResult = goal.equals(memory.lastActionGoal()) ? memory.lastActionResult() : "";
            feedback.add(new GoalPlanner.Feedback(goal,
                    memory.actionSuccessesFor(goal),
                    memory.actionFailuresFor(goal),
                    memory.actionInterruptsFor(goal),
                    lastResult));
        }
        return List.copyOf(feedback);
    }
}
