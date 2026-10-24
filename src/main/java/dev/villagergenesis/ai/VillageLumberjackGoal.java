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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Residents can bootstrap wood supply without a pre-existing chest: real drops go either into an
 * empty public-container slot or into the villager's own inventory. The goal never loads chunks
 * or creates items, and it rechecks capacity immediately before breaking the log.
 *
 * This intentionally conservative heuristic requires a vertical trunk and nearby leaves, and
 * rejects locations near block entities, doors, beds, and common construction blocks. It is not a
 * perfect ownership detector, so it caps the connected tree size and revalidates every log
 * immediately before breaking.
 */
public final class VillageLumberjackGoal extends Goal {
    private static final String NEXT_TRY = "villager_genesis_next_lumberjack";
    private static final String NEXT_SCAN = "villager_genesis_next_lumberjack_scan";
    private static final int SCAN_COOLDOWN_TICKS = 40;
    private static final int COOLDOWN_TICKS = 40;
    private static final int MAX_TRAVEL_TICKS = 160;
    private static final int SEARCH_RADIUS = 9;
    private final Villager villager;
    private BlockPos targetLog;
    private List<BlockPos> targetTreeLogs = List.of();
    private BlockPos targetStorage;
    private int targetSlot = -1;
    private int travelTicks;
    private int breakingTicks;
    private int requiredBreakingTicks = -1;
    private boolean finished;

    public VillageLumberjackGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!VillageWorkPolicy.canPerformRoutineWork(villager)) return false;
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.isBaby() || villager.getTarget() != null
                || level.getGameTime() < villager.getPersistentData().getLong(NEXT_TRY)
                || level.getGameTime() < villager.getPersistentData().getLong(NEXT_SCAN)) return false;
        villager.getPersistentData().putLong(NEXT_SCAN, level.getGameTime() + SCAN_COOLDOWN_TICKS);

        VillageRoleService.Role role = VillageRoleService.ensureRole(villager);
        boolean trainedGatherer = role == VillageRoleService.Role.GATHERER
                && VillagerMemory.forVillager(villager).knownKnowledge().contains("wood_harvesting");
        // Founding residents must be able to acquire the first wood before a chest or specialist
        // exists. Farmers and gatherers may do this bounded starter task; later staffing can
        // specialize the work without making the initial economy circular.
        boolean starterResident = role == VillageRoleService.Role.FARMER
                || role == VillageRoleService.Role.GATHERER;
        if (role != VillageRoleService.Role.LUMBERJACK && !trainedGatherer && !starterResident) return false;

        VisibleStorageScanner.Snapshot snapshot;
        try {
            snapshot = VisibleStorageScanner.scan(level, villager.blockPosition(), 8);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        targetStorage = findStorageWithSpace(level, snapshot);
        // No public storage is normal at the founding stage. The villager's real inventory is
        // the fallback; harvestOneLog checks that the exact computed drop fits before breaking.
        targetLog = findNaturalLookingLog(level, villager.blockPosition());
        if (targetLog == null) {
            targetStorage = null;
            targetTreeLogs = List.of();
            return false;
        }
        targetTreeLogs = collectTreeLogs(level, targetLog);
        if (targetTreeLogs.isEmpty() || !canFitTreeDrops(level, targetTreeLogs)) {
            targetLog = null;
            targetStorage = null;
            targetSlot = -1;
            targetTreeLogs = List.of();
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!VillageWorkPolicy.canPerformRoutineWork(villager)) return false;
        return !finished && targetLog != null
                && travelTicks < MAX_TRAVEL_TICKS && villager.isAlive()
                && villager.getTarget() == null;
    }

    @Override
    public void start() {
        travelTicks = 0;
        breakingTicks = 0;
        requiredBreakingTicks = -1;
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
        VillageWorkPolicy.claimRoutineWork(villager);
        travelTicks++;
        if (!(villager.level() instanceof ServerLevel level) || targetLog == null) {
            finished = true;
            return;
        }
        if (villager.blockPosition().distSqr(targetLog) <= 4.0D) {
            villager.getNavigation().stop();
            BlockState state = level.getBlockState(targetLog);
            if (!state.is(BlockTags.LOGS)) {
                finished = true;
                return;
            }
            if (requiredBreakingTicks < 0) {
                requiredBreakingTicks = calculateTreeBreakingTicks(level);
            }
            breakingTicks++;
            // Give the resident a visible work animation and a survival-mode-like mining duration.
            if (breakingTicks % 4 == 0) villager.swing(InteractionHand.MAIN_HAND);
            if (breakingTicks >= requiredBreakingTicks) {
                harvestOneLog(level);
                finished = true;
            }
        } else if (travelTicks % 10 == 0
                && (villager.getNavigation().isDone()
                || !VillageWorkPolicy.navigationTargets(villager, targetLog))) {
            // Reclaim the path if vanilla Brain tried to redirect the worker to a social target.
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
        targetTreeLogs = List.of();
        targetStorage = null;
        targetSlot = -1;
        travelTicks = 0;
        breakingTicks = 0;
        requiredBreakingTicks = -1;
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

    /**
     * Approximate vanilla survival block-breaking time in ticks:
     * hardness / tool speed / (30 when the held tool harvests logs, otherwise 100).
     * This avoids instant harvesting while keeping a wooden axe close to player speed.
     */
    private int calculateBreakingTicks(ServerLevel level, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) return 20 * 60;
        ItemStack held = villager.getMainHandItem();
        float speed = held.isEmpty() ? 1.0F : held.getDestroySpeed(state);
        if (speed <= 0.0F) speed = 1.0F;
        boolean correctTool = held.getItem() instanceof AxeItem;
        float divisor = correctTool ? 30.0F : 100.0F;
        return Math.max(1, (int) Math.ceil(hardness * divisor / speed));
    }

    /**
     * Fell the connected natural-looking tree as one planned job. All drops and a destination
     * are validated before the first block changes; each log is still destroyed individually
     * through the level API and each successfully felled log consumes real axe durability.
     */
    private void harvestOneLog(ServerLevel level) {
        if (targetTreeLogs.isEmpty() || !level.hasChunkAt(targetLog)
                || villager.blockPosition().distSqr(targetLog) > 9.0D) return;

        List<ItemStack> plannedDrops = new ArrayList<>();
        ItemStack tool = villager.getMainHandItem();
        ItemStack dropTemplate = ItemStack.EMPTY;
        int plannedCount = 0;
        for (BlockPos log : targetTreeLogs) {
            if (!level.hasChunkAt(log)) return;
            BlockState state = level.getBlockState(log);
            if (!state.is(BlockTags.LOGS) || isProtectedNeighbourhood(level, log)
                    || !hasLeavesNearby(level, log)) return;
            ItemStack drop = singleLogDrop(level, log, state, tool);
            if (drop.isEmpty() || !isLogItem(drop)) return;
            if (dropTemplate.isEmpty()) dropTemplate = drop.copyWithCount(1);
            else if (!ItemStack.isSameItemSameComponents(dropTemplate, drop)) return;
            plannedCount += drop.getCount();
            if (plannedCount > drop.getMaxStackSize()) return;
            plannedDrops.add(drop);
        }
        if (plannedDrops.isEmpty() || dropTemplate.isEmpty()) return;

        Container container = null;
        int inventorySlot = -1;
        if (targetStorage != null) {
            if (!level.hasChunkAt(targetStorage)
                    || !(level.getBlockEntity(targetStorage) instanceof Container found)
                    || targetSlot < 0 || targetSlot >= found.getContainerSize()
                    || !found.getItem(targetSlot).isEmpty()) return;
            container = found;
        } else {
            inventorySlot = findInventorySlot(dropTemplate, plannedCount);
            if (inventorySlot < 0) return;
        }

        int actuallyStored = 0;
        int brokenLogs = 0;
        for (int i = 0; i < targetTreeLogs.size(); i++) {
            BlockPos log = targetTreeLogs.get(i);
            if (!level.hasChunkAt(log)) break;
            BlockState current = level.getBlockState(log);
            if (!current.is(BlockTags.LOGS) || isProtectedNeighbourhood(level, log)) break;
            ItemStack currentTool = villager.getMainHandItem();
            ItemStack actualDrop = singleLogDrop(level, log, current, currentTool);
            if (actualDrop.isEmpty() || !isLogItem(actualDrop)
                    || !ItemStack.isSameItemSameComponents(dropTemplate, actualDrop)
                    || actuallyStored + actualDrop.getCount() > dropTemplate.getMaxStackSize()) break;
            if (!level.destroyBlock(log, false, villager)) break;

            actuallyStored += actualDrop.getCount();
            brokenLogs++;
            if (currentTool.getItem() instanceof AxeItem) {
                currentTool.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
            }
            // If the axe broke, stop rather than applying axe-calculated drops to later logs.
            if (villager.getMainHandItem().isEmpty() && i + 1 < targetTreeLogs.size()) break;
        }

        if (actuallyStored <= 0) return;
        ItemStack output = dropTemplate.copyWithCount(actuallyStored);
        if (container != null) {
            if (!container.getItem(targetSlot).isEmpty()) {
                // The reserved slot changed while working. Preserve the real drops locally if possible.
                insertRealDropsIntoInventory(output);
            } else {
                container.setItem(targetSlot, output);
                container.setChanged();
            }
        } else {
            Container inventory = villager.getInventory();
            ItemStack existing = inventory.getItem(inventorySlot);
            if (existing.isEmpty()) inventory.setItem(inventorySlot, output);
            else if (ItemStack.isSameItemSameComponents(existing, output)
                    && existing.getCount() + output.getCount() <= existing.getMaxStackSize()) {
                existing.grow(output.getCount());
            } else {
                // Defensive fallback: the destination changed during the job.
                insertRealDropsIntoInventory(output);
            }
            inventory.setChanged();
        }

        VillagerMemory memory = VillagerMemory.forVillager(villager);
        memory.recordActionOutcome("harvest_wood", brokenLogs == targetTreeLogs.size() ? "success" : "partial",
                "Felled " + brokenLogs + " of " + targetTreeLogs.size()
                        + " connected tree logs and stored only their real computed drops.", level.getGameTime());
        memory.learnKnowledge("wood_harvesting");
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, targetLog.getX() + 0.5D,
                targetLog.getY() + 0.5D, targetLog.getZ() + 0.5D, 5, 0.2D, 0.2D, 0.2D, 0.01D);
    }

    private ItemStack singleLogDrop(ServerLevel level, BlockPos pos, BlockState state, ItemStack tool) {
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), villager, tool);
        ItemStack result = ItemStack.EMPTY;
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            if (!result.isEmpty()) return ItemStack.EMPTY;
            result = drop.copy();
        }
        return result;
    }

    private int calculateTreeBreakingTicks(ServerLevel level) {
        int total = 0;
        for (BlockPos log : targetTreeLogs) {
            if (!level.hasChunkAt(log)) return 20 * 60;
            BlockState state = level.getBlockState(log);
            if (!state.is(BlockTags.LOGS)) return 1;
            total += calculateBreakingTicks(level, log, state);
            if (total >= 20 * 60) return 20 * 60;
        }
        return Math.max(1, total);
    }

    private List<BlockPos> collectTreeLogs(ServerLevel level, BlockPos seed) {
        final int maxLogs = 24;
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> logs = new ArrayList<>();
        pending.add(seed.immutable());
        while (!pending.isEmpty()) {
            BlockPos current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (!level.hasChunkAt(current)) return List.of();
            if (!level.getBlockState(current).is(BlockTags.LOGS)) continue;
            logs.add(current.immutable());
            if (logs.size() > maxLogs) return List.of();

            for (Direction direction : Direction.values()) {
                BlockPos neighbour = current.relative(direction);
                if (Math.abs(neighbour.getX() - seed.getX()) > 5
                        || Math.abs(neighbour.getZ() - seed.getZ()) > 5
                        || neighbour.getY() < seed.getY() - 3
                        || neighbour.getY() > seed.getY() + 18) continue;
                if (!level.hasChunkAt(neighbour)) return List.of();
                if (!visited.contains(neighbour) && level.getBlockState(neighbour).is(BlockTags.LOGS)) {
                    pending.addLast(neighbour.immutable());
                }
            }
        }
        if (logs.size() < 2) return List.of();
        logs.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY())
                .thenComparingInt(pos -> pos.getX()).thenComparingInt(pos -> pos.getZ()));
        return List.copyOf(logs);
    }

    private boolean canFitTreeDrops(ServerLevel level, List<BlockPos> logs) {
        ItemStack held = villager.getMainHandItem();
        ItemStack template = ItemStack.EMPTY;
        int count = 0;
        for (BlockPos log : logs) {
            if (!level.hasChunkAt(log)) return false;
            ItemStack drop = singleLogDrop(level, log, level.getBlockState(log), held);
            if (drop.isEmpty() || !isLogItem(drop)) return false;
            if (template.isEmpty()) template = drop.copyWithCount(1);
            else if (!ItemStack.isSameItemSameComponents(template, drop)) return false;
            count += drop.getCount();
            if (count > drop.getMaxStackSize()) return false;
        }
        if (template.isEmpty()) return false;
        if (targetStorage != null && level.hasChunkAt(targetStorage)
                && level.getBlockEntity(targetStorage) instanceof Container container
                && targetSlot >= 0 && targetSlot < container.getContainerSize()
                && container.getItem(targetSlot).isEmpty()) return true;
        return findInventorySlot(template, count) >= 0;
    }

    private void insertRealDropsIntoInventory(ItemStack output) {
        Container inventory = villager.getInventory();
        int slot = findInventorySlot(output, output.getCount());
        if (slot < 0) return;
        ItemStack existing = inventory.getItem(slot);
        if (existing.isEmpty()) inventory.setItem(slot, output.copy());
        else existing.grow(output.getCount());
        inventory.setChanged();
    }

    private int findInventorySlot(ItemStack drop) {
        return findInventorySlot(drop, drop.getCount());
    }

    private int findInventorySlot(ItemStack drop, int requiredCount) {
        Container inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing.isEmpty()) return slot;
            if (ItemStack.isSameItemSameComponents(existing, drop)
                    && existing.getCount() + requiredCount <= Math.min(existing.getMaxStackSize(),
                    inventory.getMaxStackSize())) return slot;
        }
        return -1;
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
