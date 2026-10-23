package dev.villagergenesis.ai;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Installs the optional low-priority autonomy goal once per villager entity instance. */
public final class VillageAutonomyService {
    private static final String INSTALLED = "villager_genesis_autonomy_installed";

    private VillageAutonomyService() {}

    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Villager villager) || event.getLevel().isClientSide) return;
        var data = villager.getPersistentData();
        if (data.getBoolean(INSTALLED)) return;
        villager.goalSelector.addGoal(8, new VillageExplorationGoal(villager));
        data.putBoolean(INSTALLED, true);
    }
}
