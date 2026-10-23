package dev.villagergenesis.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Installs the optional low-priority autonomy goal once per live entity instance. */
public final class VillageAutonomyService {
    private static final Map<Villager, Boolean> INSTALLED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private VillageAutonomyService() {}

    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Villager villager) || event.getLevel().isClientSide) return;
        synchronized (INSTALLED) {
            if (INSTALLED.containsKey(villager)) return;
            // Let a concrete, resource-backed build preempt idle wandering, but not urgent
            // vanilla behaviors such as panic, sleeping, or job-site work.
            villager.goalSelector.addGoal(7, new VillageStorageConstructionGoal(villager));
            villager.goalSelector.addGoal(9, new VillageExplorationGoal(villager));
            INSTALLED.put(villager, Boolean.TRUE);
        }
    }
}
