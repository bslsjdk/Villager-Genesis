package dev.villagergenesis.ai;

import dev.villagergenesis.memory.VillagerMemory;
import dev.villagergenesis.society.VillageRoleService;
import net.minecraft.core.BlockPos;
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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Lets founding farmers obtain seeds from real grass loot when they have no planting stock.
 * Only nearby grass plants are removed; player structures, crops, containers and workstations
 * are protected. The vanilla loot table determines whether any seeds actually drop.
 */
public final class VillageSeedGatheringGoal extends Goal {
    private static final String NEXT_TRY = "villager_genesis_next_seed_gather";
    private static final String NEXT_SCAN = "villager_genesis_next_seed_scan";
    private static final int COOLDOWN_TICKS = 300;
    private static final int SCAN_COOLDOWN_TICKS = 120;
    private static final int SEARCH_RADIUS = 5;

    private final Villager villager;
    private BlockPos target;
    private int travelTicks;
    private boolean finished;

    public VillageSeedGatheringGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!VillageWorkPolicy.canPerformRoutineWork(villager)) return false;
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.isBaby() || villager.getTarget() != null
                || level.getGameTime() < villager.getPersistentData().getLong(NEXT_TRY)
                || (VillageRoleService.ensureRole(villager) != VillageRoleService.Role.FARMER
                && VillageRoleService.ensureRole(villager) != VillageRoleService.Role.GATHERER)
                || hasPlantingStock(villager.getInventory())) return false;
        if (level.getGameTime() < villager.getPersistentData().getLong(NEXT_SCAN)) return false;
        villager.getPersistentData().putLong(NEXT_SCAN, level.getGameTime() + SCAN_COOLDOWN_TICKS);
        target = findGrass(level, villager.blockPosition());
        return target != null;
    }

    @Override
    public void start() {
        travelTicks = 0;
        finished = false;
        villager.getPersistentData().putString("villager_genesis_current_goal", "gather_seeds");
        navigate();
    }

    @Override
    public void tick() {
        VillageWorkPolicy.claimRoutineWork(villager);
        if (finished) return;
        travelTicks++;
        if (!(villager.level() instanceof ServerLevel level) || target == null || !level.hasChunkAt(target)) {
            finished = true;
            return;
        }
        if (villager.blockPosition().distSqr(target) > 4.0D) {
            if (travelTicks >= 120) finished = true;
            else if (villager.getNavigation().isDone() && travelTicks % 20 == 0) navigate();
            return;
        }
        finished = true;
        BlockState state = level.getBlockState(target);
        if (!isHarvestableGrass(state) || isProtected(level, target)) return;

        Container inventory = villager.getInventory();
        List<ItemStack> drops = Block.getDrops(state, level, target, level.getBlockEntity(target),
                villager, ItemStack.EMPTY);
        if (!drops.isEmpty() && !canFitDrops(inventory, drops)) {
            villager.getPersistentData().putLong(NEXT_TRY, level.getGameTime() + COOLDOWN_TICKS);
            return;
        }
        if (!level.destroyBlock(target, false, villager)) return;
        for (ItemStack drop : drops) insert(inventory, drop.copy());
        inventory.setChanged();
        level.playSound(null, target, SoundEvents.GRASS_BREAK, SoundSource.BLOCKS, 0.5F, 0.9F);
        villager.getPersistentData().putLong(NEXT_TRY, level.getGameTime() + COOLDOWN_TICKS);
        VillagerMemory memory = VillagerMemory.forVillager(villager);
        memory.learnKnowledge("seed_gathering");
        memory.recordActionOutcome("gather_seeds", "success",
                "Collected only actual vanilla grass drops; the grass may yield no seeds.", level.getGameTime());
    }

    @Override
    public boolean canContinueToUse() {
        if (!VillageWorkPolicy.canPerformRoutineWork(villager)) return false;
        return !finished && target != null && travelTicks < 120 && villager.isAlive();
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
        target = null;
        travelTicks = 0;
        finished = false;
    }

    private void navigate() {
        if (target != null) villager.getNavigation().moveTo(
                target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 0.8D);
    }

    private BlockPos findGrass(ServerLevel level, BlockPos origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (pos.distSqr(origin) > SEARCH_RADIUS * SEARCH_RADIUS || !level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (!isHarvestableGrass(state) || isProtected(level, pos)) continue;
                    double distance = pos.distSqr(origin);
                    if (distance < bestDistance) {
                        best = pos.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private boolean isHarvestableGrass(BlockState state) {
        return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS);
    }

    private boolean isProtected(ServerLevel level, BlockPos pos) {
        for (BlockPos nearby : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 2, 1))) {
            if (!level.hasChunkAt(nearby)) return true;
            BlockState state = level.getBlockState(nearby);
            if (state.hasBlockEntity() || state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.CHEST)
                    || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL)
                    || state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER)
                    || state.is(Blocks.DIRT_PATH)
                    || state.is(net.minecraft.tags.BlockTags.BEDS) || state.is(net.minecraft.tags.BlockTags.DOORS)
                    || state.getBlock() instanceof net.minecraft.world.level.block.CropBlock) return true;
        }
        return false;
    }

    private boolean hasPlantingStock(Container inventory) {
        return count(inventory, Items.WHEAT_SEEDS) > 0 || count(inventory, Items.CARROT) > 0
                || count(inventory, Items.POTATO) > 0 || count(inventory, Items.BEETROOT_SEEDS) > 0;
    }

    private int count(Container inventory, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private boolean canFitDrops(Container inventory, List<ItemStack> drops) {
        List<ItemStack> simulated = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) simulated.add(inventory.getItem(i).copy());
        for (ItemStack drop : drops) {
            ItemStack remaining = drop.copy();
            for (ItemStack stack : simulated) {
                if (remaining.isEmpty()) break;
                if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, remaining)
                        && stack.getCount() < stack.getMaxStackSize()) {
                    int moved = Math.min(remaining.getCount(), stack.getMaxStackSize() - stack.getCount());
                    stack.grow(moved);
                    remaining.shrink(moved);
                }
            }
            for (int i = 0; i < simulated.size() && !remaining.isEmpty(); i++) {
                if (!simulated.get(i).isEmpty()) continue;
                int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                simulated.set(i, remaining.copyWithCount(moved));
                remaining.shrink(moved);
            }
            if (!remaining.isEmpty()) return false;
        }
        return true;
    }

    private void insert(Container inventory, ItemStack output) {
        for (int i = 0; i < inventory.getContainerSize() && !output.isEmpty(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                int moved = Math.min(output.getCount(), output.getMaxStackSize());
                inventory.setItem(i, output.copyWithCount(moved));
                output.shrink(moved);
            } else if (ItemStack.isSameItemSameComponents(stack, output)
                    && stack.getCount() < stack.getMaxStackSize()) {
                int moved = Math.min(output.getCount(), stack.getMaxStackSize() - stack.getCount());
                stack.grow(moved);
                output.shrink(moved);
            }
        }
    }
}
