package dev.villagergenesis.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
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

    /**
     * A routine task owns movement while it is active. Vanilla villager Brain social behaviors
     * also write WALK_TARGET and INTERACTION_TARGET, which can continually redirect a worker
     * away from its assigned resource. Clear those transient intents each work tick; the active
     * Goal remains responsible for its own navigation and task completion.
     */
    public static void claimRoutineWork(Villager villager) {
        if (villager == null || !villager.isAlive()) return;
        // Leave the vanilla MEET/SOCIAL schedule while a real work goal owns the villager.
        villager.getBrain().setActiveActivityIfPossible(Activity.WORK);
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.INTERACTION_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
    }

    /** Returns whether the active navigation path still belongs to the current work target. */
    public static boolean navigationTargets(Villager villager, BlockPos target) {
        if (villager == null || target == null) return false;
        Path path = villager.getNavigation().getPath();
        return path != null && target.equals(path.getTarget());
    }

    public static boolean canPerformRoutineWork(Villager villager) {
        return villager != null && villager.isAlive()
                && !villager.isSleeping()
                && villager.getPose() != Pose.SLEEPING
                && !villager.getPersistentData().getBoolean(GROUND_RESTING);
    }
}
