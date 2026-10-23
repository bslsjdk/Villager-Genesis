package dev.villagergenesis.ai;

import dev.villagergenesis.economy.VisibleStorageScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Physical storage expansion behavior. A villager walks to a safe site, then consumes one real
 * barrel item from a public container and places the corresponding block. The operation rechecks
 * both source and destination immediately before changing the world.
 *
 * This deliberately does not break blocks, move player structures, or synthesize materials.
 */
public final class VillageStorageConstructionGoal extends Goal {
    private static final Map<ServerLevel, Map<Long, Long>> NEXT_SETTLEMENT_SCAN =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final int SCAN_INTERVAL = 100;
    private static final int RETRY_AFTER_BUILD = 1_200;
    private static final int MAX_TRAVEL_TICKS = 240;

    private final Villager villager;
    private BlockPos source;
    private BlockPos destination;
    private int sourceSlot = -1;
    private int travelTicks;
    private int retryTicks;
    private boolean finished;

    public VillageStorageConstructionGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.isBaby() || villager.getTarget() != null
                || !villager.getNavigation().isDone()) return false;

        long now = level.getGameTime();
        long regionKey = (((long) (villager.blockPosition().getX() >> 5)) << 32)
                ^ ((villager.blockPosition().getZ() >> 5) & 0xffffffffL);
        synchronized (NEXT_SETTLEMENT_SCAN) {
            Map<Long, Long> regions = NEXT_SETTLEMENT_SCAN.computeIfAbsent(level,
                    ignored -> new java.util.HashMap<>());
            if (now < regions.getOrDefault(regionKey, 0L)) return false;
            regions.put(regionKey, now + SCAN_INTERVAL);
        }

        VisibleStorageScanner.Snapshot snapshot;
        try {
            snapshot = VisibleStorageScanner.scan(level, villager.blockPosition(), 8);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        int slots = 0;
        int occupied = 0;
        for (var container : snapshot.containers()) {
            slots += container.slots();
            occupied += Math.min(container.occupiedSlots(), container.slots());
        }
        if (slots == 0 || (slots - occupied > 9 && (double) occupied / slots < 0.80D)) {
            return false;
        }

        source = findStoredBarrel(level, snapshot);
        if (source == null) return false;
        destination = findSafeSite(level, villager.blockPosition());
        if (destination == null) {
            source = null;
            return false;
        }
        synchronized (NEXT_SETTLEMENT_SCAN) {
            NEXT_SETTLEMENT_SCAN.getOrDefault(level, java.util.Map.of())
                    .put(regionKey, now + RETRY_AFTER_BUILD);
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !finished && destination != null && travelTicks < MAX_TRAVEL_TICKS
                && villager.isAlive() && villager.getTarget() == null;
    }

    @Override
    public void start() {
        travelTicks = 0;
        retryTicks = 0;
        finished = false;
        villager.getPersistentData().putString("villager_genesis_current_goal", "expand_storage");
        navigateToSite();
    }

    @Override
    public void tick() {
        travelTicks++;
        if (destination == null || !(villager.level() instanceof ServerLevel level)) {
            finished = true;
            return;
        }
        if (villager.blockPosition().distSqr(destination) <= 4.0D) {
            executePlacement(level);
            finished = true;
            return;
        }
        if (villager.getNavigation().isDone() && ++retryTicks % 40 == 0) {
            navigateToSite();
        }
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
        source = null;
        destination = null;
        sourceSlot = -1;
        finished = false;
    }

    private BlockPos findStoredBarrel(ServerLevel level, VisibleStorageScanner.Snapshot snapshot) {
        for (VisibleStorageScanner.StorageContainer found : snapshot.containers()) {
            BlockPos pos = new BlockPos(found.x(), found.y(), found.z());
            BlockEntity entity = level.getBlockEntity(pos);
            if (!(entity instanceof Container container)) continue;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.is(Items.BARREL) && !stack.isEmpty()) {
                    sourceSlot = slot;
                    return pos;
                }
            }
        }
        return null;
    }

    private BlockPos findSafeSite(ServerLevel level, BlockPos origin) {
        for (int radius = 3; radius <= 8; radius++) {
            for (int attempt = 0; attempt < 20; attempt++) {
                int dx = villager.getRandom().nextInt(radius * 2 + 1) - radius;
                int dz = villager.getRandom().nextInt(radius * 2 + 1) - radius;
                if (Math.max(Math.abs(dx), Math.abs(dz)) < 3) continue;
                BlockPos feet = origin.offset(dx, 0, dz);
                for (int dy = 2; dy >= -2; dy--) {
                    BlockPos pos = feet.offset(0, dy, 0);
                    if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.below())) continue;
                    BlockState floor = level.getBlockState(pos.below());
                    if (!(floor.is(Blocks.GRASS_BLOCK) || floor.is(Blocks.DIRT)
                            || floor.is(Blocks.COARSE_DIRT) || floor.is(Blocks.PODZOL))) continue;
                    if (!floor.isFaceSturdy(level, pos.below(), Direction.UP)
                            || !level.getBlockState(pos).isAir()
                            || !level.getBlockState(pos.above()).isAir()
                            || !level.getFluidState(pos).isEmpty()
                            || !level.getFluidState(pos.above()).isEmpty()) continue;
                    if (!level.getEntities(villager, new AABB(pos).inflate(0.25D),
                            entity -> entity.isAlive()).isEmpty()) continue;
                    if (level.getBlockState(pos.north()).is(Blocks.CHEST)
                            || level.getBlockState(pos.south()).is(Blocks.CHEST)
                            || level.getBlockState(pos.east()).is(Blocks.CHEST)
                            || level.getBlockState(pos.west()).is(Blocks.CHEST)
                            || level.getBlockState(pos.north()).is(Blocks.BARREL)
                            || level.getBlockState(pos.south()).is(Blocks.BARREL)
                            || level.getBlockState(pos.east()).is(Blocks.BARREL)
                            || level.getBlockState(pos.west()).is(Blocks.BARREL)) continue;
                    return pos.immutable();
                }
            }
        }
        return null;
    }

    private void navigateToSite() {
        if (destination != null) {
            villager.getNavigation().moveTo(destination.getX() + 0.5D,
                    destination.getY(), destination.getZ() + 0.5D, 0.7D);
        }
    }

    private void executePlacement(ServerLevel level) {
        if (source == null || destination == null || !level.hasChunkAt(source)
                || !level.hasChunkAt(destination)) return;
        BlockEntity sourceEntity = level.getBlockEntity(source);
        if (!(sourceEntity instanceof Container container)
                || sourceSlot < 0 || sourceSlot >= container.getContainerSize()) return;
        ItemStack stored = container.getItem(sourceSlot);
        if (stored.isEmpty() || !stored.is(Items.BARREL)) return;
        if (!level.getBlockState(destination).isAir()
                || !level.getBlockState(destination.above()).isAir()
                || !level.getBlockState(destination.below()).isFaceSturdy(level,
                destination.below(), Direction.UP)) return;

        if (!level.setBlock(destination, Blocks.BARREL.defaultBlockState(), 3)) return;
        ItemStack consumed = container.removeItem(sourceSlot, 1);
        if (consumed.isEmpty() || !consumed.is(Items.BARREL)) {
            level.setBlock(destination, Blocks.AIR.defaultBlockState(), 3);
            return;
        }
        container.setChanged();
        villager.getPersistentData().putInt("villager_genesis_storage_builds",
                villager.getPersistentData().getInt("villager_genesis_storage_builds") + 1);
    }
}
