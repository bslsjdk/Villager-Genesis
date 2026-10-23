package dev.villagergenesis.ai;

import dev.villagergenesis.society.VillageRoleService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * A small, real-world farming loop: find a mature crop, walk to it, harvest its actual drops,
 * and replant only when the villager has the matching real seed/item in its own inventory.
 * It never creates crops or items from simulation estimates.
 */
public final class VillageFarmWorkGoal extends Goal {
    private static final int SCAN_RADIUS = 6;
    private static final int MAX_TRAVEL_TICKS = 240;
    private static final int COOLDOWN_TICKS = 100;
    private final Villager villager;
    private BlockPos target;
    private int travelTicks;

    public VillageFarmWorkGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.isBaby() || villager.getTarget() != null
                || !villager.getNavigation().isDone()
                || VillageRoleService.roleOf(villager) != VillageRoleService.Role.FARMER
                || level.getGameTime() < villager.getPersistentData().getLong("villager_genesis_next_farm_work")) {
            return false;
        }

        BlockPos origin = villager.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-SCAN_RADIUS, -2, -SCAN_RADIUS),
                origin.offset(SCAN_RADIUS, 2, SCAN_RADIUS))) {
            if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.below())) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) continue;
            Item seed = seedFor(state.getBlock());
            if (seed == null || findSeedSlot(seed) < 0) continue;
            if (!level.getBlockState(pos.below()).is(Blocks.FARMLAND)) continue;
            double distance = villager.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        target = best;
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && travelTicks < MAX_TRAVEL_TICKS && villager.isAlive()
                && villager.getTarget() == null;
    }

    @Override
    public void start() {
        travelTicks = 0;
        villager.getPersistentData().putString("villager_genesis_current_goal", "farm_work");
        villager.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 0.8D);
        if (villager.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(),
                    villager.getY() + 1.0D, villager.getZ(), 2, 0.2D, 0.2D, 0.2D, 0.01D);
        }
    }

    @Override
    public void tick() {
        travelTicks++;
        if (target == null || !(villager.level() instanceof ServerLevel level)) return;
        if (villager.blockPosition().distSqr(target) <= 4.0D) {
            harvestAndReplant(level);
            villager.getPersistentData().putLong("villager_genesis_next_farm_work",
                    level.getGameTime() + COOLDOWN_TICKS);
            target = null;
        } else if (villager.getNavigation().isDone() && travelTicks % 40 == 0) {
            villager.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 0.8D);
        }
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        if (villager.level() instanceof ServerLevel level) {
            if (travelTicks >= MAX_TRAVEL_TICKS) {
                villager.getPersistentData().putLong("villager_genesis_next_farm_work",
                        level.getGameTime() + COOLDOWN_TICKS);
            }
        }
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
        target = null;
        travelTicks = 0;
    }

    private void harvestAndReplant(ServerLevel level) {
        if (target == null || !level.hasChunkAt(target) || !level.hasChunkAt(target.below())) return;
        BlockState state = level.getBlockState(target);
        if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)
                || !level.getBlockState(target.below()).is(Blocks.FARMLAND)) return;

        Item seed = seedFor(state.getBlock());
        int seedSlot = seed == null ? -1 : findSeedSlot(seed);
        if (seedSlot < 0) return;

        // Revalidate immediately before mutation. Normal Minecraft loot produces the harvest;
        // only an item already carried by the villager pays for replanting.
        if (!level.destroyBlock(target, true, villager)) return;
        ItemStack carriedSeed = villager.getInventory().getItem(seedSlot);
        if (carriedSeed.isEmpty() || !carriedSeed.is(seed)) return;
        carriedSeed.shrink(1);
        if (carriedSeed.isEmpty()) villager.getInventory().setItem(seedSlot, ItemStack.EMPTY);
        if (level.getBlockState(target).isAir()
                && level.getBlockState(target.below()).is(Blocks.FARMLAND)) {
            boolean planted = level.setBlock(target, crop.getStateForAge(0), Block.UPDATE_ALL);
            if (planted) {
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, target.getX() + 0.5D,
                        target.getY() + 0.8D, target.getZ() + 0.5D, 5, 0.2D, 0.2D, 0.2D, 0.01D);
                level.playSound(null, target, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 0.35F, 1.0F);
            }
        }
    }

    private int findSeedSlot(Item seed) {
        var inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(seed)) return slot;
        }
        return -1;
    }

    private static Item seedFor(Block block) {
        if (block == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (block == Blocks.CARROTS) return Items.CARROT;
        if (block == Blocks.POTATOES) return Items.POTATO;
        if (block == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        return null;
    }
}
