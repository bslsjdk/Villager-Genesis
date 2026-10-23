package dev.villagergenesis.ai;

import dev.villagergenesis.memory.VillagerMemory;
import dev.villagergenesis.society.VillageRoleService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Non-combat emergency behavior for ordinary residents. It creates no items, does not attack,
 * and only chooses destinations in already-loaded chunks with safe footing and clear space.
 */
public final class VillageResidentFleeGoal extends Goal {
    private static final double DETECT_RANGE = 10.0D;
    private static final double EXTENDED_THREAT_RANGE = 15.0D;
    private static final double ESCAPE_DISTANCE_SQR = 100.0D;
    private static final int RECHECK_TICKS = 5;
    private static final int MAX_TRAVEL_TICKS = 100;

    private final Villager villager;
    private Monster threat;
    private BlockPos destination;
    private long nextScanTick;
    private int travelTicks;
    private int repathTicks;
    private boolean escaped;

    public VillageResidentFleeGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || VillageRoleService.ensureRole(villager) == VillageRoleService.Role.GUARD) {
            return false;
        }
        if (level.getGameTime() < nextScanTick) return false;
        nextScanTick = level.getGameTime() + RECHECK_TICKS;

        threat = findThreat();
        if (threat == null) return false;
        destination = findEscapeDestination(level, threat);
        return destination != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (threat == null || !threat.isAlive() || !villager.isAlive()
                || destination == null || travelTicks >= MAX_TRAVEL_TICKS) return false;
        return villager.distanceToSqr(threat) < ESCAPE_DISTANCE_SQR
                && threat.distanceToSqr(villager) < EXTENDED_THREAT_RANGE * EXTENDED_THREAT_RANGE;
    }

    @Override
    public void start() {
        travelTicks = 0;
        repathTicks = 0;
        escaped = false;
        VillagerMemory.forVillager(villager).setCurrentGoal("flee_threat");
        villager.getPersistentData().putString("villager_genesis_current_goal", "flee_threat");
        navigateAway();
    }

    @Override
    public void tick() {
        travelTicks++;
        if (!(villager.level() instanceof ServerLevel level) || threat == null || !threat.isAlive()) {
            escaped = true;
            return;
        }

        double distance = villager.distanceToSqr(threat);
        if (distance >= ESCAPE_DISTANCE_SQR) {
            escaped = true;
            return;
        }

        if (destination == null || !level.hasChunkAt(destination)
                || !isSafeDestination(level, destination)) {
            destination = findEscapeDestination(level, threat);
        }
        if (villager.getNavigation().isDone() && repathTicks++ % 10 == 0) {
            navigateAway();
        }
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        if (villager.level() instanceof ServerLevel level) {
            VillagerMemory memory = VillagerMemory.forVillager(villager);
            if (escaped || (threat != null && (!threat.isAlive()
                    || villager.distanceToSqr(threat) >= ESCAPE_DISTANCE_SQR))) {
                memory.recordActionOutcome("flee_threat", "success",
                        "Reached a safer distance from a nearby hostile mob.", level.getGameTime());
                memory.learnKnowledge("hostile_awareness");
            } else {
                memory.recordActionOutcome("flee_threat", "interrupted",
                        "Escape ended before a safe distance was confirmed.", level.getGameTime());
            }
            memory.setCurrentGoal("observe");
        }
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
        threat = null;
        destination = null;
        travelTicks = 0;
        repathTicks = 0;
        escaped = false;
    }

    private Monster findThreat() {
        AABB area = villager.getBoundingBox().inflate(DETECT_RANGE);
        List<Monster> threats = villager.level().getEntitiesOfClass(Monster.class, area,
                monster -> monster.isAlive() && !monster.isRemoved()
                        && (monster.getTarget() == villager
                        || monster.distanceToSqr(villager) <= DETECT_RANGE * DETECT_RANGE));
        return threats.stream()
                .min(Comparator.<Monster>comparingInt(monster -> monster.getTarget() == villager ? 0 : 1)
                        .thenComparingDouble(villager::distanceToSqr))
                .orElse(null);
    }

    private BlockPos findEscapeDestination(ServerLevel level, Monster danger) {
        Vec3 away = new Vec3(villager.getX() - danger.getX(), 0.0D,
                villager.getZ() - danger.getZ());
        if (away.lengthSqr() < 0.001D) away = new Vec3(1.0D, 0.0D, 0.0D);
        away = away.normalize();

        double[] angles = {0.0D, Math.PI / 4.0D, -Math.PI / 4.0D,
                Math.PI / 2.0D, -Math.PI / 2.0D, Math.PI * 3.0D / 4.0D,
                -Math.PI * 3.0D / 4.0D, Math.PI};
        BlockPos origin = villager.blockPosition();
        for (double angle : angles) {
            double dx = away.x * Math.cos(angle) - away.z * Math.sin(angle);
            double dz = away.x * Math.sin(angle) + away.z * Math.cos(angle);
            for (int distance = 8; distance >= 5; distance--) {
                int x = origin.getX() + (int) Math.round(dx * distance);
                int z = origin.getZ() + (int) Math.round(dz * distance);
                for (int dy : new int[]{0, 1, -1, 2, -2}) {
                    BlockPos candidate = new BlockPos(x, origin.getY() + dy, z);
                    if (!isSafeDestination(level, candidate)) continue;
                    if (candidate.distSqr(danger.blockPosition()) <= 36.0D) continue;
                    return candidate.immutable();
                }
            }
        }
        return null;
    }

    private boolean isSafeDestination(ServerLevel level, BlockPos feet) {
        BlockPos floorPos = feet.below();
        BlockPos headPos = feet.above();
        if (!level.hasChunkAt(floorPos) || !level.hasChunkAt(feet) || !level.hasChunkAt(headPos)) return false;
        BlockState floor = level.getBlockState(floorPos);
        return floor.isFaceSturdy(level, floorPos, Direction.UP)
                && level.getBlockState(feet).isAir()
                && level.getBlockState(headPos).isAir()
                && level.getFluidState(feet).isEmpty()
                && level.getFluidState(headPos).isEmpty();
    }

    private void navigateAway() {
        if (destination != null) {
            villager.getNavigation().moveTo(destination.getX() + 0.5D,
                    destination.getY(), destination.getZ() + 0.5D, 1.15D);
        }
    }
}
