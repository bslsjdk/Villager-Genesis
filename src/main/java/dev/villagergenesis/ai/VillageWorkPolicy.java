package dev.villagergenesis.ai;

import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.npc.Villager;

/**
 * Shared guard for routine settlement work.
 *
 * Ground resting uses Pose.SLEEPING without vanilla's bed-backed sleep state. Checking both the
 * pose and the persistent rest marker prevents one-tick goal gaps or unrelated goals from making
 * a resident craft, harvest, equip, or build while visibly lying down.
 */
public final class VillageWorkPolicy {
    private static final String GROUND_RESTING = "villager_genesis_ground_resting";

    private VillageWorkPolicy() {}

    public static boolean canPerformRoutineWork(Villager villager) {
        return villager != null && villager.isAlive()
                && !villager.isSleeping()
                && villager.getPose() != Pose.SLEEPING
                && !villager.getPersistentData().getBoolean(GROUND_RESTING);
    }
}
