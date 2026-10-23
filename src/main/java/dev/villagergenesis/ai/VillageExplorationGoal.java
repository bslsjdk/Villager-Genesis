package dev.villagergenesis.ai;

import dev.villagergenesis.memory.VillagerMemory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * Low-priority, interruption-safe exploration behavior. Vanilla panic, sleep, work, trading,
 * and navigation goals retain precedence. Exploration only records a waypoint after the villager
 * physically reaches a safe, walkable location.
 */
public final class VillageExplorationGoal extends Goal {
    private static final String NEXT_EXPLORATION = "villager_genesis_next_exploration";
    private static final int COOLDOWN_TICKS = 1_200;
    private static final int MAX_TRAVEL_TICKS = 240;
    private final Villager villager;
    private BlockPos destination;
    private int travelTicks;

    public VillageExplorationGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.isBaby() || villager.getTarget() != null
                || !villager.getNavigation().isDone()) {
            return false;
        }
        if (level.getGameTime() < villager.getPersistentData().getLong(NEXT_EXPLORATION)) {
            return false;
        }
        destination = findSafeDestination(level);
        return destination != null;
    }

    @Override
    public boolean canContinueToUse() {
        return destination != null && travelTicks < MAX_TRAVEL_TICKS
                && villager.isAlive() && villager.getTarget() == null
                && !villager.getNavigation().isDone();
    }

    @Override
    public void start() {
        travelTicks = 0;
        villager.getPersistentData().putString("villager_genesis_current_goal", "explore");
        villager.getNavigation().moveTo(destination.getX() + 0.5, destination.getY(),
                destination.getZ() + 0.5, 0.65D);
    }

    @Override
    public void tick() {
        travelTicks++;
        if (destination != null) {
            villager.getLookControl().setLookAt(destination.getX() + 0.5,
                    destination.getY() + 0.5, destination.getZ() + 0.5);
        }
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        if (villager.level() instanceof ServerLevel level) {
            villager.getPersistentData().putLong(NEXT_EXPLORATION,
                    level.getGameTime() + COOLDOWN_TICKS);
            if (destination != null && villager.blockPosition().distSqr(destination) <= 4.0D) {
                VillagerMemory.forVillager(villager).learnKnowledge("local_exploration");
                rememberWaypoint(villager, destination);
            }
        }
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
        destination = null;
    }

    private BlockPos findSafeDestination(ServerLevel level) {
        BlockPos origin = villager.blockPosition();
        for (int attempt = 0; attempt < 12; attempt++) {
            int dx = villager.getRandom().nextInt(17) - 8;
            int dz = villager.getRandom().nextInt(17) - 8;
            if (Math.abs(dx) + Math.abs(dz) < 5) continue;
            BlockPos column = origin.offset(dx, 0, dz);
            if (!level.hasChunkAt(column)) continue;
            for (int dy = 3; dy >= -3; dy--) {
                BlockPos feet = column.offset(0, dy, 0);
                if (!level.hasChunkAt(feet) || !level.hasChunkAt(feet.below())) continue;
                BlockState floor = level.getBlockState(feet.below());
                BlockState feetState = level.getBlockState(feet);
                BlockState headState = level.getBlockState(feet.above());
                if (!floor.isFaceSturdy(level, feet.below(), Direction.UP)
                        || !feetState.isAir() || !headState.isAir()
                        || level.getFluidState(feet).isSource()
                        || level.getFluidState(feet.above()).isSource()
                        || floor.is(Blocks.MAGMA_BLOCK) || floor.is(Blocks.CAMPFIRE)
                        || floor.is(Blocks.SOUL_CAMPFIRE)) {
                    continue;
                }
                if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, feet) < 7) continue;
                if (!level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                        villager.getBoundingBox().inflate(6.0D),
                        mob -> mob.getType().getCategory()
                                == net.minecraft.world.entity.MobCategory.MONSTER).isEmpty()) {
                    continue;
                }
                return feet.immutable();
            }
        }
        return null;
    }

    private static void rememberWaypoint(Villager villager, BlockPos pos) {
        var data = villager.getPersistentData();
        var points = data.getList("villager_genesis_waypoints", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < points.size(); i++) {
            var point = points.getCompound(i);
            if (point.getInt("x") == pos.getX() && point.getInt("y") == pos.getY()
                    && point.getInt("z") == pos.getZ()) return;
        }
        var point = new net.minecraft.nbt.CompoundTag();
        point.putInt("x", pos.getX());
        point.putInt("y", pos.getY());
        point.putInt("z", pos.getZ());
        point.putString("dimension", villager.level().dimension().location().toString());
        points.add(point);
        while (points.size() > 8) points.remove(0);
        data.put("villager_genesis_waypoints", points);
    }
}
