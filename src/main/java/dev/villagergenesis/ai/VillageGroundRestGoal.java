package dev.villagergenesis.ai;

import dev.villagergenesis.memory.VillagerMemory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

/**
 * Lets residents rest on the ground before a bed supply exists. It prefers a dry, roofed,
 * safe spot but can use open ground as a fallback. It never places blocks or consumes items.
 * When a real nearby bed exists, vanilla villager sleep behavior is left alone.
 */
public final class VillageGroundRestGoal extends Goal {
    private static final String NEXT_SCAN = "villager_genesis_next_ground_rest_scan";
    private static final String RESTING = "villager_genesis_ground_resting";
    private static final int SCAN_COOLDOWN = 100;
    private static final int SEARCH_RADIUS = 8;

    private final Villager villager;
    private BlockPos restSite;
    private boolean resting;
    private int travelTicks;

    public VillageGroundRestGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.getTarget() != null || villager.isSleeping()
                || !isNight(level) || level.getGameTime() < villager.getPersistentData().getLong(NEXT_SCAN)) {
            return false;
        }
        villager.getPersistentData().putLong(NEXT_SCAN, level.getGameTime() + SCAN_COOLDOWN);
        // Existing beds belong to vanilla sleep behavior; the ground-rest fallback is only for
        // settlements that do not yet have a bed within a modest local radius.
        if (hasNearbyBed(level, villager.blockPosition())) return false;
        restSite = findSafeRestSite(level, villager.blockPosition());
        return restSite != null;
    }

    @Override
    public void start() {
        resting = false;
        travelTicks = 0;
        villager.getPersistentData().putString("villager_genesis_current_goal", "rest_on_ground");
        navigate();
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level) || restSite == null) return;
        if (!isNight(level) || villager.getTarget() != null || isThreatNearby(level, villager.blockPosition(), 8)) {
            wake();
            return;
        }
        if (resting) {
            villager.getNavigation().stop();
            villager.setDeltaMovement(0.0D, villager.getDeltaMovement().y, 0.0D);
            return;
        }
        travelTicks++;
        if (!level.hasChunkAt(restSite) || travelTicks > 160) {
            wake();
            restSite = null;
            return;
        }
        if (hasNearbyBed(level, villager.blockPosition())) {
            wake();
            restSite = null;
            return;
        }
        if (villager.blockPosition().distSqr(restSite) <= 2.25D) {
            if (!isSafeRestSite(level, restSite)) return;
            villager.getNavigation().stop();
            villager.setPose(Pose.SLEEPING);
            resting = true;
            villager.getPersistentData().putBoolean(RESTING, true);
            villager.getPersistentData().putLong("villager_genesis_ground_rest_started", level.getGameTime());
            level.playSound(null, restSite, SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 0.25F, 0.65F);
            VillagerMemory.forVillager(villager).recordActionOutcome("rest_on_ground", "success",
                    "Rested on safe ground because no nearby bed was available.", level.getGameTime());
            VillagerMemory.forVillager(villager).learnKnowledge("ground_resting");
        } else if (villager.getNavigation().isDone() && travelTicks % 20 == 0) {
            navigate();
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || restSite == null || !isNight(level) || villager.getTarget() != null
                || hasNearbyBed(level, villager.blockPosition())) return false;
        return !isThreatNearby(level, villager.blockPosition(), 8);
    }

    @Override
    public void stop() {
        wake();
        restSite = null;
        travelTicks = 0;
    }

    private void wake() {
        if (villager.getPose() == Pose.SLEEPING) villager.setPose(Pose.STANDING);
        if (villager.getNavigation() != null) villager.getNavigation().stop();
        villager.getPersistentData().putBoolean(RESTING, false);
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
        resting = false;
    }

    private void navigate() {
        if (restSite != null) villager.getNavigation().moveTo(
                restSite.getX() + 0.5D, restSite.getY(), restSite.getZ() + 0.5D, 0.7D);
    }

    private boolean isNight(ServerLevel level) {
        long dayTime = level.getDayTime() % 24000L;
        return dayTime >= 12500L && dayTime < 23500L;
    }

    private boolean hasNearbyBed(ServerLevel level, BlockPos origin) {
        for (int dx = -12; dx <= 12; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -12; dz <= 12; dz++) {
                    if (dx * dx + dz * dz > 144) continue;
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (level.hasChunkAt(pos) && level.getBlockState(pos).is(net.minecraft.tags.BlockTags.BEDS)) return true;
                }
            }
        }
        return false;
    }

    private BlockPos findSafeRestSite(ServerLevel level, BlockPos origin) {
        BlockPos bestRoofed = null;
        double bestRoofedDistance = Double.MAX_VALUE;
        BlockPos bestOpen = null;
        double bestOpenDistance = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    if (dx * dx + dz * dz > SEARCH_RADIUS * SEARCH_RADIUS) continue;
                    BlockPos feet = origin.offset(dx, dy, dz);
                    if (!isSafeRestSite(level, feet)) continue;
                    double distance = feet.distSqr(origin);
                    if (level.getBlockState(feet.above(2)).isSolidRender(level, feet.above(2))) {
                        if (distance < bestRoofedDistance) {
                            bestRoofed = feet.immutable();
                            bestRoofedDistance = distance;
                        }
                    } else if (distance < bestOpenDistance) {
                        bestOpen = feet.immutable();
                        bestOpenDistance = distance;
                    }
                }
            }
        }
        return bestRoofed != null ? bestRoofed : bestOpen;
    }

    private boolean isSafeRestSite(ServerLevel level, BlockPos feet) {
        BlockPos floor = feet.below();
        if (!level.hasChunkAt(floor) || !level.hasChunkAt(feet.above(1))) return false;
        BlockState floorState = level.getBlockState(floor);
        if (!floorState.isFaceSturdy(level, floor, net.minecraft.core.Direction.UP)
                || !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                || !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) return false;
        if (floorState.is(Blocks.MAGMA_BLOCK) || floorState.is(Blocks.CAMPFIRE)
                || floorState.is(Blocks.SOUL_CAMPFIRE) || floorState.is(Blocks.FIRE)
                || floorState.is(Blocks.SOUL_FIRE)) return false;
        if (isThreatNearby(level, feet, 8)) return false;
        AABB body = new AABB(feet.getX(), feet.getY(), feet.getZ(),
                feet.getX() + 1.0D, feet.getY() + 2.0D, feet.getZ() + 1.0D);
        // Nearby residents may share a resting area; only non-villager collidable entities block it.
        return level.getEntities(villager, body,
                entity -> entity.isAlive() && entity.isPickable() && !(entity instanceof Villager)).isEmpty();
    }

    private boolean isThreatNearby(ServerLevel level, BlockPos pos, int radius) {
        AABB area = new AABB(pos).inflate(radius, 4, radius);
        return !level.getEntities(villager, area, entity -> entity instanceof Monster && entity.isAlive()).isEmpty();
    }
}
