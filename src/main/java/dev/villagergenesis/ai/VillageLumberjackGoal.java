package dev.villagergenesis.ai;

import dev.villagergenesis.economy.VisibleStorageScanner;
import dev.villagergenesis.memory.VillagerMemory;
import dev.villagergenesis.society.VillageRoleService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Lumberjacks harvest one verified natural-looking trunk block at a time and put real drops into
 * an existing nearby public container. The goal never loads chunks, creates items, or breaks a
 * block unless there is storage capacity for its real drops.
 *
 * This intentionally conservative heuristic requires a vertical trunk and nearby leaves, and
 * rejects locations near block entities, doors, beds, and common construction blocks. It is not a
 * perfect ownership detector, so it only touches one log per trip and revalidates immediately
 * before breaking.
 */
public final class VillageLumberjackGoal extends Goal {
    private static final String NEXT_TRY = "villager_genesis_next_lumberjack";
    private static final int COOLDOWN_TICKS = 400;
    private static final int MAX_TRAVEL_TICKS = 160;
    private static final int SEARCH_RADIUS = 7;
    private final Villager villager;
    private BlockPos targetLog;
    private BlockPos targetStorage;
    private int targetSlot = -1;
    private int travelTicks;
    private boolean finished;

    public VillageLumberjackGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.isBaby() || villager.getTarget() != null
                || !villager.getNavigation().isDone()
                || level.getGameTime() < villager.getPersistentData().getLong(NEXT_TRY)) return false;

        VillageRoleService.Role role = VillageRoleService.ensureRole(villager);
        boolean trainedGatherer = role == VillageRoleService.Role.GATHERER
                && VillagerMemory.forVillager(villager).knownKnowledge().contains("wood_harvesting");
        if (role != VillageRoleService.Role.LUMBERJACK && !trainedGatherer) return false;

        VisibleStorageScanner.Snapshot snapshot;
        try {
            snapshot = VisibleStorageScanner.scan(level, villager.blockPosition(), 8);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        targetStorage = findStorageWithSpace(level, snapshot);
        if (targetStorage == null) return false;
        targetLog = findNaturalLookingLog(level, villager.blockPosition());
        if (targetLog == null) {
            targetStorage = null;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !finished && targetLog != null && targetStorage != null
                && travelTicks < MAX_TRAVEL_TICKS && villager.isAlive()
                && villager.getTarget() == null;
    }

    @Override
    public void start() {
        travelTicks = 0;
        finished = false;
        villager.getPersistentData().putString("villager_genesis_current_goal", "harvest_wood");
        if (villager.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(),
                    villager.getY() + 1.0D, villager.getZ(), 2, 0.2D, 0.2D, 0.2D, 0.01D);
        }
        navigate();
    }

    @Override
    public void tick() {
        travelTicks++;
        if (!(villager.level() instanceof ServerLevel level)
                || targetLog == null || targetStorage == null) {
            finished = true;
            return;
        }
        if (villager.blockPosition().distSqr(targetLog) <= 4.0D) {
            harvestOneLog(level);
            finished = true;
        } else if (villager.getNavigation().isDone() && travelTicks % 30 == 0) {
            navigate();
        }
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        if (villager.level() instanceof ServerLevel level) {
            villager.getPersistentData().putLong(NEXT_TRY, level.getGameTime() + COOLDOWN_TICKS);
        }
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
        targetLog = null;
        targetStorage = null;
        targetSlot = -1;
        travelTicks = 0;
        finished = false;
    }

    private void navigate() {
        if (targetLog != null) {
            villager.getNavigation().moveTo(targetLog.getX() + 0.5D,
                    targetLog.getY(), targetLog.getZ() + 0.5D, 0.8D);
        }
    }

    private BlockPos findNaturalLookingLog(ServerLevel level, BlockPos origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -3; dy <= 5; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(BlockTags.LOGS) || isProtectedNeighbourhood(level, pos)
                            || !hasLeavesNearby(level, pos) || !isTrunkSegment(level, pos)) continue;
                    double distance = pos.distToCenterSqr(origin.getX() + 0.5D,
                            origin.getY() + 0.5D, origin.getZ() + 0.5D);
                    if (distance < bestDistance) {
                        best = pos;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private boolean isTrunkSegment(ServerLevel level, BlockPos pos) {
        int verticalLogs = 0;
        for (int dy = -2; dy <= 2; dy++) {
            BlockPos check = pos.offset(0, dy, 0);
            if (!level.hasChunkAt(check)) continue;
            if (level.getBlockState(check).is(BlockTags.LOGS)) verticalLogs++;
        }
        return verticalLogs >= 2;
    }

    private boolean hasLeavesNearby(ServerLevel level, BlockPos pos) {
        int leaves = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 4; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos check = pos.offset(dx, dy, dz);
                    if (!level.hasChunkAt(check)) continue;
                    if (level.getBlockState(check).is(BlockTags.LEAVES) && ++leaves >= 5) return true;
                }
            }
        }
        return false;
    }

    private boolean isProtectedNeighbourhood(ServerLevel level, BlockPos log) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos check = log.offset(dx, dy, dz);
                    if (!level.hasChunkAt(check)) return true;
                    if (level.getBlockEntity(check) != null) return true;
                    BlockState state = level.getBlockState(check);
                    if (state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE)
                            || state.is(Blocks.CHEST) || state.is(Blocks.BARREL)
                            || state.is(Blocks.BEEHIVE) || state.is(Blocks.BEE_NEST)
                            || state.is(Blocks.LADDER) || state.is(Blocks.TORCH)
                            || state.is(Blocks.WALL_TORCH) || state.is(Blocks.LANTERN)) return true;
                }
            }
        }
        return false;
    }

    private BlockPos findStorageWithSpace(ServerLevel level, VisibleStorageScanner.Snapshot snapshot) {
        // Choose an empty slot. The exact block drops are only known after the target is rechecked,
        // so preselecting a partly filled stack could make the inventory transfer non-atomic.
        for (VisibleStorageScanner.StorageContainer found : snapshot.containers()) {
            BlockPos pos = new BlockPos(found.x(), found.y(), found.z());
            if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof Container container)) continue;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (container.getItem(slot).isEmpty()) {
                    targetSlot = slot;
                    return pos;
                }
            }
        }
        return null;
    }

    private void harvestOneLog(ServerLevel level) {
        if (!level.hasChunkAt(targetLog) || !level.hasChunkAt(targetStorage)
                || villager.blockPosition().distSqr(targetLog) > 9.0D
                || !(level.getBlockEntity(targetStorage) instanceof Container container)
                || targetSlot < 0 || targetSlot >= container.getContainerSize()) return;
        BlockState state = level.getBlockState(targetLog);
        if (!state.is(BlockTags.LOGS) || isProtectedNeighbourhood(level, targetLog)
                || !hasLeavesNearby(level, targetLog) || !isTrunkSegment(level, targetLog)) return;

        ItemStack tool = villager.getMainHandItem();
        if (!(tool.getItem() instanceof AxeItem)) tool = ItemStack.EMPTY;
        BlockEntity blockEntity = level.getBlockEntity(targetLog);
        List<ItemStack> drops = Block.getDrops(state, level, targetLog, blockEntity, villager, tool);
        if (drops.isEmpty() || drops.stream().anyMatch(stack -> !stack.isEmpty() && !isLogItem(stack))) return;

        if (!container.getItem(targetSlot).isEmpty()) return;
        int nonEmptyDrops = 0;
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            nonEmptyDrops++;
            if (drop.getCount() > drop.getMaxStackSize()) return;
        }
        if (nonEmptyDrops != 1) return;

        // Break without world drops, then transfer the exact game-computed drops into storage.
        // If the block changed between calculation and mutation, no item is granted.
        if (!level.destroyBlock(targetLog, false, villager)) return;
        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                container.setItem(targetSlot, drop.copy());
                break;
            }
        }
        container.setChanged();
        VillagerMemory memory = VillagerMemory.forVillager(villager);
        memory.recordActionOutcome("harvest_wood", "success",
                "Harvested one real trunk block and stored its computed drops.", level.getGameTime());
        memory.learnKnowledge("wood_harvesting");
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, targetLog.getX() + 0.5D,
                targetLog.getY() + 0.5D, targetLog.getZ() + 0.5D, 5, 0.2D, 0.2D, 0.2D, 0.01D);
    }

    private boolean isLogItem(ItemStack stack) {
        return stack.is(net.minecraft.world.item.Items.OAK_LOG)
                || stack.is(net.minecraft.world.item.Items.SPRUCE_LOG)
                || stack.is(net.minecraft.world.item.Items.BIRCH_LOG)
                || stack.is(net.minecraft.world.item.Items.JUNGLE_LOG)
                || stack.is(net.minecraft.world.item.Items.ACACIA_LOG)
                || stack.is(net.minecraft.world.item.Items.DARK_OAK_LOG)
                || stack.is(net.minecraft.world.item.Items.MANGROVE_LOG)
                || stack.is(net.minecraft.world.item.Items.CHERRY_LOG)
                || stack.is(net.minecraft.world.item.Items.CRIMSON_STEM)
                || stack.is(net.minecraft.world.item.Items.WARPED_STEM)
                || stack.is(net.minecraft.world.item.Items.OAK_WOOD)
                || stack.is(net.minecraft.world.item.Items.SPRUCE_WOOD)
                || stack.is(net.minecraft.world.item.Items.BIRCH_WOOD)
                || stack.is(net.minecraft.world.item.Items.JUNGLE_WOOD)
                || stack.is(net.minecraft.world.item.Items.ACACIA_WOOD)
                || stack.is(net.minecraft.world.item.Items.DARK_OAK_WOOD)
                || stack.is(net.minecraft.world.item.Items.MANGROVE_WOOD)
                || stack.is(net.minecraft.world.item.Items.CHERRY_WOOD);
    }
}
