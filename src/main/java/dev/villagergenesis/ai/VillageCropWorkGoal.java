package dev.villagergenesis.ai;

import dev.villagergenesis.memory.VillagerMemory;
import dev.villagergenesis.society.VillageRoleService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Performs one bounded crop action with real inventory resources. It works only in existing,
 * loaded farmland and requires the villager's equipped hoe. Harvest output is computed by the
 * vanilla block drop system and must fit in the real inventory before the crop is removed.
 */
public final class VillageCropWorkGoal extends Goal {
    private static final String NEXT_TRY = "villager_genesis_next_crop_work";
    private static final String NEXT_SCAN = "villager_genesis_next_crop_scan";
    private static final String MANAGED_CROPS = "villager_genesis_managed_crops";
    private static final int WORK_COOLDOWN = 100;
    private static final int SCAN_COOLDOWN = 80;
    private static final int SEARCH_RADIUS = 6;

    private final Villager villager;
    private BlockPos target;
    private boolean harvest;
    private boolean finished;
    private int travelTicks;
    private static final int MAX_TRAVEL_TICKS = 120;

    public VillageCropWorkGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!VillageWorkPolicy.canPerformRoutineWork(villager)) return false;
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.isBaby() || villager.getTarget() != null || !villager.getNavigation().isDone()
                || VillageRoleService.ensureRole(villager) != VillageRoleService.Role.FARMER
                || level.getGameTime() < villager.getPersistentData().getLong(NEXT_TRY)) return false;
        ItemStack tool = villager.getMainHandItem();
        if (!(tool.getItem() instanceof HoeItem) || tool.getDamageValue() >= tool.getMaxDamage()) return false;
        if (level.getGameTime() < villager.getPersistentData().getLong(NEXT_SCAN)) return false;
        villager.getPersistentData().putLong(NEXT_SCAN, level.getGameTime() + SCAN_COOLDOWN);
        target = findTarget(level, villager.blockPosition());
        return target != null;
    }

    @Override
    public void start() {
        finished = false;
        travelTicks = 0;
        villager.getPersistentData().putString("villager_genesis_current_goal",
                harvest ? "harvest_crop" : "plant_crop");
        navigate();
    }

    @Override
    public void tick() {
        if (finished) return;
        travelTicks++;
        if (!(villager.level() instanceof ServerLevel level) || target == null || !level.hasChunkAt(target)) {
            finished = true;
            return;
        }
        if (villager.blockPosition().distSqr(target) > 4.0D) {
            if (travelTicks >= MAX_TRAVEL_TICKS) finished = true;
            else if (villager.getNavigation().isDone() && travelTicks % 20 == 0) navigate();
            return;
        }
        finished = true;
        ItemStack hoe = villager.getMainHandItem();
        if (!(hoe.getItem() instanceof HoeItem) || hoe.getDamageValue() >= hoe.getMaxDamage()) return;
        Container inventory = villager.getInventory();
        BlockState state = level.getBlockState(target);

        if (harvest) {
            if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) return;
            List<ItemStack> drops = Block.getDrops(state, level, target, level.getBlockEntity(target), villager, hoe);
            if (drops.isEmpty() || !canFitDrops(inventory, drops)) return;
            if (!level.destroyBlock(target, false, villager)) return;
            for (ItemStack drop : drops) insert(inventory, drop.copy());
            untrackCrop(target);
            inventory.setChanged();
            level.playSound(null, target, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 0.7F, 0.9F);
            VillagerMemory.forVillager(villager).recordActionOutcome("harvest_crop", "success",
                    "Harvested a mature crop using vanilla-computed drops stored in the villager inventory.",
                    level.getGameTime());
        } else {
            if (!state.is(Blocks.FARMLAND) || !level.getBlockState(target.above()).isAir()) return;
            Item seed = seedFor(inventory);
            if (seed == null || !isSupportedCrop(seed)) return;
            Block crop = cropFor(seed);
            if (crop == null || !crop.defaultBlockState().canSurvive(level, target.above())) return;
            if (!consumeOne(inventory, seed)) return;
            if (!level.setBlock(target.above(), crop.defaultBlockState(), 3)) {
                insert(inventory, new ItemStack(seed));
                inventory.setChanged();
                return;
            }
            trackCrop(target.above());
            inventory.setChanged();
            level.playSound(null, target.above(), SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 0.7F, 1.0F);
            VillagerMemory.forVillager(villager).recordActionOutcome("plant_crop", "success",
                    "Planted one crop using a real seed item from inventory.", level.getGameTime());
        }

        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, target.getX() + 0.5D,
                target.getY() + 1.0D, target.getZ() + 0.5D, 2, 0.15D, 0.1D, 0.15D, 0.01D);
        villager.getPersistentData().putLong(NEXT_TRY, level.getGameTime() + WORK_COOLDOWN);
        VillagerMemory.forVillager(villager).learnKnowledge("crop_cultivation");
    }

    @Override
    public boolean canContinueToUse() {
        if (!VillageWorkPolicy.canPerformRoutineWork(villager)) return false;
        return !finished && target != null && travelTicks < MAX_TRAVEL_TICKS && villager.isAlive();
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
        target = null;
        harvest = false;
        finished = false;
        travelTicks = 0;
    }

    private void navigate() {
        if (target == null) return;
        double y = harvest ? target.getY() : target.getY() + 1.0D;
        villager.getNavigation().moveTo(target.getX() + 0.5D, y, target.getZ() + 0.5D, 0.8D);
    }

    private BlockPos findTarget(ServerLevel level, BlockPos origin) {
        BlockPos planting = null;
        double plantingDistance = Double.MAX_VALUE;
        BlockPos ripe = null;
        double ripeDistance = Double.MAX_VALUE;
        boolean blockedRipeCrop = false;
        Container inventory = villager.getInventory();
        boolean hasSeed = seedFor(inventory) != null && managedCropCount() < 24;

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    double distance = pos.distSqr(origin);

                    if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)
                            && isManagedCrop(pos)) {
                        List<ItemStack> drops = Block.getDrops(state, level, pos,
                                level.getBlockEntity(pos), villager, villager.getMainHandItem());
                        if (drops.isEmpty() || !canFitDrops(inventory, drops)) {
                            blockedRipeCrop = true;
                        } else if (distance < ripeDistance) {
                            ripe = pos.immutable();
                            ripeDistance = distance;
                        }
                        continue;
                    }

                    if (hasSeed && state.is(Blocks.FARMLAND) && level.getBlockState(pos.above()).isAir()
                            && level.hasChunkAt(pos.above()) && distance < plantingDistance) {
                        planting = pos.immutable();
                        plantingDistance = distance;
                    }
                }
            }
        }
        if (ripe != null) {
            harvest = true;
            return ripe;
        }
        // If a tracked crop is ripe but its drops do not fit, stop instead of expanding the
        // farm and making the inventory pressure worse.
        if (blockedRipeCrop) return null;
        harvest = false;
        return planting;
    }

    private Item seedFor(Container inventory) {
        if (count(inventory, Items.WHEAT_SEEDS) > 0) return Items.WHEAT_SEEDS;
        if (count(inventory, Items.CARROT) > 0) return Items.CARROT;
        if (count(inventory, Items.POTATO) > 0) return Items.POTATO;
        if (count(inventory, Items.BEETROOT_SEEDS) > 0) return Items.BEETROOT_SEEDS;
        return null;
    }

    private boolean isSupportedCrop(Item seed) {
        return seed == Items.WHEAT_SEEDS || seed == Items.CARROT || seed == Items.POTATO
                || seed == Items.BEETROOT_SEEDS;
    }

    private Block cropFor(Item seed) {
        if (seed == Items.WHEAT_SEEDS) return Blocks.WHEAT;
        if (seed == Items.CARROT) return Blocks.CARROTS;
        if (seed == Items.POTATO) return Blocks.POTATOES;
        if (seed == Items.BEETROOT_SEEDS) return Blocks.BEETROOTS;
        return null;
    }

    private int managedCropCount() {
        return villager.getPersistentData().getList(MANAGED_CROPS, Tag.TAG_COMPOUND).size();
    }

    private boolean isManagedCrop(BlockPos pos) {
        ListTag crops = villager.getPersistentData().getList(MANAGED_CROPS, Tag.TAG_COMPOUND);
        for (int i = 0; i < crops.size(); i++) {
            CompoundTag entry = crops.getCompound(i);
            if (entry.getInt("x") == pos.getX() && entry.getInt("y") == pos.getY()
                    && entry.getInt("z") == pos.getZ()) return true;
        }
        return false;
    }

    private void trackCrop(BlockPos pos) {
        if (isManagedCrop(pos)) return;
        ListTag crops = villager.getPersistentData().getList(MANAGED_CROPS, Tag.TAG_COMPOUND);
        // Keep the personal farm bounded. The villager will harvest before adding more once full.
        if (crops.size() >= 24) return;
        CompoundTag entry = new CompoundTag();
        entry.putInt("x", pos.getX());
        entry.putInt("y", pos.getY());
        entry.putInt("z", pos.getZ());
        crops.add(entry);
        villager.getPersistentData().put(MANAGED_CROPS, crops);
    }

    private void untrackCrop(BlockPos pos) {
        ListTag old = villager.getPersistentData().getList(MANAGED_CROPS, Tag.TAG_COMPOUND);
        ListTag kept = new ListTag();
        for (int i = 0; i < old.size(); i++) {
            CompoundTag entry = old.getCompound(i);
            if (entry.getInt("x") != pos.getX() || entry.getInt("y") != pos.getY()
                    || entry.getInt("z") != pos.getZ()) kept.add(entry.copy());
        }
        villager.getPersistentData().put(MANAGED_CROPS, kept);
    }

    private int count(Container inventory, Item item) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private boolean consumeOne(Container inventory, Item item) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.is(item)) continue;
            stack.shrink(1);
            if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
            inventory.setChanged();
            return true;
        }
        return false;
    }

    private boolean canFitDrops(Container inventory, List<ItemStack> drops) {
        List<ItemStack> simulated = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) simulated.add(inventory.getItem(i).copy());
        for (ItemStack drop : drops) {
            ItemStack remaining = drop.copy();
            if (remaining.isEmpty()) continue;
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
