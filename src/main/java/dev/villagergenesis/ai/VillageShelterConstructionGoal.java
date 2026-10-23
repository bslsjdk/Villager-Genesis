package dev.villagergenesis.ai;

import dev.villagergenesis.economy.VisibleStorageScanner;
import dev.villagergenesis.memory.VillagerMemory;
import dev.villagergenesis.society.VillageRoleService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * A visibly new mod-owned construction task: build a compact oak-plank shelter from real stored
 * materials. The task only runs in already loaded terrain, reserves no virtual materials, and
 * rolls back its block placements and inventory consumption if any placement fails.
 */
public final class VillageShelterConstructionGoal extends Goal {
    private static final int REQUIRED_PLANKS = 33;
    private static final int SCAN_COOLDOWN = 600;
    private static final int MAX_TRAVEL_TICKS = 240;
    private final Villager villager;
    private BlockPos source;
    private BlockPos site;
    private int sourceSlot = -1;
    private int travelTicks;
    private boolean finished;

    public VillageShelterConstructionGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.isBaby() || villager.getTarget() != null
                || !villager.getNavigation().isDone()
                || VillageRoleService.roleOf(villager) != VillageRoleService.Role.BUILDER
                || level.getGameTime() < villager.getPersistentData().getLong("villager_genesis_next_shelter")) {
            return false;
        }
        // One settlement should not spend 33 planks per builder on duplicate huts.
        // The registry is persistent, so this still works after saving and restarting the world.
        if (hasNearbyRegisteredShelter(level, villager.blockPosition())) return false;

        VisibleStorageScanner.Snapshot snapshot;
        try {
            snapshot = VisibleStorageScanner.scan(level, villager.blockPosition(), 8);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        source = findPlankSource(level, snapshot);
        if (source == null) return false;
        site = findSite(level, villager.blockPosition());
        return site != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !finished && site != null && travelTicks < MAX_TRAVEL_TICKS
                && villager.isAlive() && villager.getTarget() == null;
    }

    @Override
    public void start() {
        travelTicks = 0;
        finished = false;
        villager.getPersistentData().putString("villager_genesis_current_goal", "build_shelter");
        if (villager.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(),
                    villager.getY() + 1.0D, villager.getZ(), 4, 0.25D, 0.2D, 0.25D, 0.01D);
        }
        navigate();
    }

    @Override
    public void tick() {
        travelTicks++;
        if (site == null || !(villager.level() instanceof ServerLevel level)) {
            finished = true;
            return;
        }
        if (villager.blockPosition().distSqr(site) <= 4.0D) {
            build(level);
            finished = true;
        } else if (villager.getNavigation().isDone() && travelTicks % 40 == 0) {
            navigate();
        }
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        if (villager.level() instanceof ServerLevel level) {
            villager.getPersistentData().putLong("villager_genesis_next_shelter",
                    level.getGameTime() + SCAN_COOLDOWN);
        }
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
        source = null;
        site = null;
        sourceSlot = -1;
        travelTicks = 0;
        finished = false;
    }

    private BlockPos findPlankSource(ServerLevel level, VisibleStorageScanner.Snapshot snapshot) {
        for (VisibleStorageScanner.StorageContainer found : snapshot.containers()) {
            BlockPos pos = new BlockPos(found.x(), found.y(), found.z());
            if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof Container container)) continue;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.is(Items.OAK_PLANKS) && stack.getCount() >= REQUIRED_PLANKS) {
                    sourceSlot = slot;
                    return pos;
                }
            }
        }
        return null;
    }

    private BlockPos findSite(ServerLevel level, BlockPos origin) {
        for (int radius = 4; radius <= 8; radius++) {
            for (int attempt = 0; attempt < 32; attempt++) {
                int dx = villager.getRandom().nextInt(radius * 2 + 1) - radius;
                int dz = villager.getRandom().nextInt(radius * 2 + 1) - radius;
                if (Math.max(Math.abs(dx), Math.abs(dz)) < 3) continue;
                BlockPos center = origin.offset(dx, 0, dz);
                BlockPos floorPos = center.below();
                if (!level.hasChunkAt(floorPos) || !level.hasChunkAt(center.offset(-1, 2, -1))
                        || !level.hasChunkAt(center.offset(1, 3, 1))) continue;
                BlockState floor = level.getBlockState(floorPos);
                if (!(floor.is(Blocks.GRASS_BLOCK) || floor.is(Blocks.DIRT)
                        || floor.is(Blocks.COARSE_DIRT) || floor.is(Blocks.PODZOL)
                        || floor.is(Blocks.STONE))) continue;
                if (!floor.isFaceSturdy(level, floorPos, Direction.UP)) continue;
                boolean clear = true;
                for (int x = -1; x <= 1 && clear; x++) {
                    for (int z = -1; z <= 1 && clear; z++) {
                        BlockPos support = center.offset(x, -1, z);
                        if (!level.hasChunkAt(support)
                                || !level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) {
                            clear = false;
                            break;
                        }
                        for (int y = 0; y <= 3; y++) {
                            BlockPos p = center.offset(x, y, z);
                            if (!level.hasChunkAt(p) || !level.getBlockState(p).isAir()
                                    || !level.getFluidState(p).isEmpty()) {
                                clear = false;
                                break;
                            }
                        }
                    }
                }
                if (!clear) continue;
                if (!level.getEntities(villager, new AABB(center.getX() - 1, center.getY(), center.getZ() - 1,
                        center.getX() + 2, center.getY() + 4, center.getZ() + 2), entity -> entity.isAlive()).isEmpty()) continue;
                return center.immutable();
            }
        }
        return null;
    }

    private void navigate() {
        if (site != null) villager.getNavigation().moveTo(site.getX() + 0.5D,
                site.getY(), site.getZ() + 0.5D, 0.7D);
    }

    private void build(ServerLevel level) {
        if (source == null || site == null || !level.hasChunkAt(source)
                || !level.hasChunkAt(site.offset(-1, 2, -1))
                || !level.hasChunkAt(site.offset(1, 3, 1))) return;
        if (!(level.getBlockEntity(source) instanceof Container container)
                || sourceSlot < 0 || sourceSlot >= container.getContainerSize()) return;
        ItemStack stored = container.getItem(sourceSlot);
        if (!stored.is(Items.OAK_PLANKS) || stored.getCount() < REQUIRED_PLANKS) return;

        List<BlockPos> positions = new ArrayList<>(REQUIRED_PLANKS);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) positions.add(site.offset(x, 0, z));
        // Two perimeter wall layers, with a door opening in the south-facing lower wall.
        for (int y = 1; y <= 2; y++) {
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                boolean perimeter = Math.abs(x) == 1 || Math.abs(z) == 1;
                if (!perimeter) continue;
                if (y == 1 && z == 1 && x == 0) continue;
                positions.add(site.offset(x, y, z));
            }
        }
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) positions.add(site.offset(x, 3, z));
        if (positions.size() != REQUIRED_PLANKS) return;
        for (BlockPos pos : positions) {
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos).isAir()) return;
        }

        ItemStack original = stored.copy();
        List<BlockPos> placed = new ArrayList<>();
        BlockState plank = Blocks.OAK_PLANKS.defaultBlockState();
        for (BlockPos pos : positions) {
            if (!level.setBlock(pos, plank, Block.UPDATE_ALL)) {
                for (BlockPos rollback : placed) level.setBlock(rollback, Blocks.AIR.defaultBlockState(), 3);
                container.setItem(sourceSlot, original.copy());
                container.setChanged();
                return;
            }
            placed.add(pos);
        }
        stored.shrink(REQUIRED_PLANKS);
        if (stored.isEmpty()) container.setItem(sourceSlot, ItemStack.EMPTY);
        container.setChanged();
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, site.getX() + 0.5D,
                site.getY() + 1.0D, site.getZ() + 0.5D, 24, 1.0D, 0.7D, 1.0D, 0.03D);
        level.playSound(null, site, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 0.9F);
        villager.getPersistentData().putInt("villager_genesis_shelters_built",
                villager.getPersistentData().getInt("villager_genesis_shelters_built") + 1);
        VillageStructureRegistry registry = VillageStructureRegistry.get(level);
        for (BlockPos pos : placed) {
            registry.registerPlacedBlock(pos, "shelter_oak", villager.getUUID(), level.getGameTime());
        }
        VillagerMemory.forVillager(villager).recordActionOutcome("build_shelter", "success",
                "Built a 3x3 oak shelter from 33 real planks taken from village storage.", level.getGameTime());
        VillagerMemory.forVillager(villager).learnKnowledge("shelter_construction");
    }

    private boolean hasNearbyRegisteredShelter(ServerLevel level, BlockPos origin) {
        long maxDistance = 24L * 24L;
        for (VillageStructureRegistry.OwnedBlock block
                : VillageStructureRegistry.get(level).blocksOfType("shelter_oak")) {
            if (block.pos().distSqr(origin) <= maxDistance) {
                // Ignore stale records if a player has already removed the registered block.
                if (level.hasChunkAt(block.pos())
                        && level.getBlockState(block.pos()).is(Blocks.OAK_PLANKS)) {
                    return true;
                }
            }
        }
        return false;
    }
}
