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
            villager.goalSelector.addGoal(8, new VillageExplorationGoal(villager));
            INSTALLED.put(villager, Boolean.TRUE);
        }
    }
}
