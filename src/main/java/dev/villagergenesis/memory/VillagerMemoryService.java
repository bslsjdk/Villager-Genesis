package dev.villagergenesis.memory;

import dev.villagergenesis.planning.GoalPlanner;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

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
        if (!(entity instanceof Villager villager) || villager.level().isClientSide()) {
            return;
        }

        long gameTime = villager.level().getGameTime();
        int phase = Math.floorMod(villager.getUUID().hashCode(), OBSERVATION_INTERVAL);
        if (Math.floorMod(gameTime, OBSERVATION_INTERVAL) != phase) {
            return;
        }

        VillagerMemory memory = VillagerMemory.forVillager(villager);
        memory.observe(villager);
        GoalPlanner.chooseGoal(memory.snapshot()).ifPresent(memory::setCurrentGoal);
    }
}
