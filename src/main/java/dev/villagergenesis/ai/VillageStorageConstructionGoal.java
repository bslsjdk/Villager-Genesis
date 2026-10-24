package dev.villagergenesis.ai;

import dev.villagergenesis.economy.VisibleStorageScanner;
import dev.villagergenesis.society.VillageRoleService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.HashMap;
import java.util.WeakHashMap;
import java.util.UUID;

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
    // WorldBox-inspired single active construction order per local settlement region.
    // This is a reservation only; the actual block and materials still require verification.
    private static final Map<ServerLevel, Map<Long, UUID>> ACTIVE_BUILDERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final int SCAN_INTERVAL = 40;
    private static final int RETRY_AFTER_BUILD = 1_200;
    private static final int MAX_TRAVEL_TICKS = 240;

    private final Villager villager;
    private BlockPos source;
    private BlockPos destination;
    private int sourceSlot = -1;
    private boolean craftBarrel;
    private final Map<Integer, Integer> craftingCosts = new HashMap<>();
    private int travelTicks;
    private int retryTicks;
    private boolean finished;
    private long scanRegionKey;

    public VillageStorageConstructionGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!VillageWorkPolicy.canPerformRoutineWork(villager)) return false;
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.isBaby() || villager.getTarget() != null
                || !villager.getNavigation().isDone()) return false;

        // Storage expansion is a social responsibility, not a job every villager competes for.
        // Keep the mayor as a fallback for small settlements with no available caretaker/crafter.
        VillageRoleService.Role role = VillageRoleService.ensureRole(villager);
        if (role != VillageRoleService.Role.CRAFTER
                && role != VillageRoleService.Role.CARETAKER
                && role != VillageRoleService.Role.MAYOR) return false;

        long now = level.getGameTime();
        scanRegionKey = (((long) (villager.blockPosition().getX() >> 5)) << 32)
                ^ ((villager.blockPosition().getZ() >> 5) & 0xffffffffL);
        synchronized (ACTIVE_BUILDERS) {
            Map<Long, UUID> active = ACTIVE_BUILDERS.computeIfAbsent(level,
                    ignored -> new java.util.HashMap<>());
            UUID owner = active.get(scanRegionKey);
            if (owner != null && !owner.equals(villager.getUUID())) return false;
        }
        synchronized (NEXT_SETTLEMENT_SCAN) {
            Map<Long, Long> regions = NEXT_SETTLEMENT_SCAN.computeIfAbsent(level,
                    ignored -> new java.util.HashMap<>());
            if (now < regions.getOrDefault(scanRegionKey, 0L)) return false;
            regions.put(scanRegionKey, now + SCAN_INTERVAL);
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
        if (slots == 0 || (slots - occupied > 9 && (double) occupied / slots < 0.50D)) {
            return false;
        }

        source = findStorageMaterialSource(level, snapshot);
        if (source == null) return false;
        destination = findSafeSite(level, villager.blockPosition());
        if (destination == null) {
            source = null;
            return false;
        }
        // Long cooldown is applied only after a successful build. Pathing failures or
        // stale materials must be eligible for another attempt after the short scan interval.
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!VillageWorkPolicy.canPerformRoutineWork(villager)) return false;
        return !finished && destination != null && travelTicks < MAX_TRAVEL_TICKS
                && villager.isAlive() && villager.getTarget() == null;
    }

    @Override
    public void start() {
        travelTicks = 0;
        retryTicks = 0;
        finished = false;
        synchronized (ACTIVE_BUILDERS) {
            ACTIVE_BUILDERS.computeIfAbsent((ServerLevel) villager.level(),
                    ignored -> new java.util.HashMap<>()).put(scanRegionKey, villager.getUUID());
        }
        villager.getPersistentData().putString("villager_genesis_current_goal", "expand_storage");
        // Visible intent marker: players can tell the autonomy goal actually started,
        // instead of having to infer behavior from hidden planner state.
        if (villager.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(),
                    villager.getY() + 1.0D, villager.getZ(), 3, 0.25D, 0.25D, 0.25D, 0.01D);
        }
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
        if (villager.level() instanceof ServerLevel level) {
            synchronized (ACTIVE_BUILDERS) {
                Map<Long, UUID> active = ACTIVE_BUILDERS.get(level);
                if (active != null && villager.getUUID().equals(active.get(scanRegionKey))) {
                    active.remove(scanRegionKey);
                    if (active.isEmpty()) ACTIVE_BUILDERS.remove(level);
                }
            }
        }
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
        source = null;
        destination = null;
        sourceSlot = -1;
        craftBarrel = false;
        craftingCosts.clear();
        finished = false;
    }

    private BlockPos findStorageMaterialSource(ServerLevel level, VisibleStorageScanner.Snapshot snapshot) {
        // Prefer an already-crafted barrel; this requires no extra processing.
        for (VisibleStorageScanner.StorageContainer found : snapshot.containers()) {
            BlockPos pos = new BlockPos(found.x(), found.y(), found.z());
            BlockEntity entity = level.getBlockEntity(pos);
            if (!(entity instanceof Container container)) continue;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && stack.is(Items.BARREL)) {
                    sourceSlot = slot;
                    craftBarrel = false;
                    return pos;
                }
            }
        }

        // Vanilla barrel recipe: six planks and two wooden slabs. Ingredients must exist
        // together in one real public container, so there is no invisible cross-container stock.
        for (VisibleStorageScanner.StorageContainer found : snapshot.containers()) {
            BlockPos pos = new BlockPos(found.x(), found.y(), found.z());
            BlockEntity entity = level.getBlockEntity(pos);
            if (!(entity instanceof Container container)) continue;
            Map<Integer, Integer> costs = new HashMap<>();
            int planksNeeded = 6;
            int slabsNeeded = 2;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) continue;
                if (stack.is(ItemTags.PLANKS) && planksNeeded > 0) {
                    int use = Math.min(planksNeeded, stack.getCount());
                    if (use > 0) costs.put(slot, use);
                    planksNeeded -= use;
                } else if (stack.is(ItemTags.WOODEN_SLABS) && slabsNeeded > 0) {
                    int use = Math.min(slabsNeeded, stack.getCount());
                    if (use > 0) costs.put(slot, use);
                    slabsNeeded -= use;
                }
            }
            if (planksNeeded == 0 && slabsNeeded == 0) {
                craftingCosts.clear();
                craftingCosts.putAll(costs);
                sourceSlot = -1;
                craftBarrel = true;
                return pos;
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
                    if (!level.hasChunkAt(pos.north()) || !level.hasChunkAt(pos.south())
                            || !level.hasChunkAt(pos.east()) || !level.hasChunkAt(pos.west())) continue;
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
        if (!(sourceEntity instanceof Container container)) return;
        if (!level.getBlockState(destination).isAir()
                || !level.getBlockState(destination.above()).isAir()
                || !level.getBlockState(destination.below()).isFaceSturdy(level,
                destination.below(), Direction.UP)) return;

        Map<Integer, ItemStack> before = new HashMap<>();
        if (!craftBarrel) {
            if (sourceSlot < 0 || sourceSlot >= container.getContainerSize()) return;
            ItemStack stored = container.getItem(sourceSlot);
            if (stored.isEmpty() || !stored.is(Items.BARREL)) return;
            before.put(sourceSlot, stored.copy());
        } else {
            if (craftingCosts.isEmpty()) return;
            for (Map.Entry<Integer, Integer> cost : craftingCosts.entrySet()) {
                int slot = cost.getKey();
                int amount = cost.getValue();
                if (slot < 0 || slot >= container.getContainerSize() || amount < 1) return;
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || stack.getCount() < amount) return;
                if (stack.is(ItemTags.PLANKS)) {
                    before.put(slot, stack.copy());
                } else if (stack.is(ItemTags.WOODEN_SLABS)) {
                    before.put(slot, stack.copy());
                } else {
                    return;
                }
            }
        }

        if (!level.setBlock(destination, Blocks.BARREL.defaultBlockState(), 3)) return;
        boolean consumedAll = true;
        if (!craftBarrel) {
            ItemStack consumed = container.removeItem(sourceSlot, 1);
            consumedAll = !consumed.isEmpty() && consumed.is(Items.BARREL);
        } else {
            for (Map.Entry<Integer, Integer> cost : craftingCosts.entrySet()) {
                ItemStack consumed = container.removeItem(cost.getKey(), cost.getValue());
                if (consumed.isEmpty() || consumed.getCount() != cost.getValue()) {
                    consumedAll = false;
                    break;
                }
            }
        }
        if (!consumedAll) {
            level.setBlock(destination, Blocks.AIR.defaultBlockState(), 3);
            before.forEach((slot, stack) -> container.setItem(slot, stack.copy()));
            container.setChanged();
            return;
        }
        VillageStructureRegistry.get(level).registerPlacedBlock(destination, "storage_barrel", villager.getUUID(), level.getGameTime());
        synchronized (NEXT_SETTLEMENT_SCAN) {
            NEXT_SETTLEMENT_SCAN.computeIfAbsent(level, ignored -> new HashMap<>())
                    .put(scanRegionKey, level.getGameTime() + RETRY_AFTER_BUILD);
        }
        container.setChanged();
        villager.getPersistentData().putInt("villager_genesis_storage_builds",
                villager.getPersistentData().getInt("villager_genesis_storage_builds") + 1);
        // A real block was placed and registered; mark that successful outcome in-world.
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, destination.getX() + 0.5D,
                destination.getY() + 1.0D, destination.getZ() + 0.5D,
                10, 0.35D, 0.45D, 0.35D, 0.03D);
        level.playSound(null, destination, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS,
                0.7F, 1.15F);
    }
}
