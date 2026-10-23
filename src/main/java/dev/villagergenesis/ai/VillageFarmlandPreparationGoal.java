package dev.villagergenesis.ai;

import dev.villagergenesis.memory.VillagerMemory;
import dev.villagergenesis.society.VillageRoleService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.EnumSet;

/**
 * Creates a small real farmland patch for a founding farmer. It requires an equipped, usable
 * hoe, existing dirt/grass, nearby water and an already-loaded chunk. It never generates seeds
 * or crops, and avoids obvious player infrastructure and already cultivated blocks.
 */
public final class VillageFarmlandPreparationGoal extends Goal {
    private static final String NEXT_TRY = "villager_genesis_next_farmland_prepare";
    private static final int COOLDOWN_TICKS = 200;
    private static final int SEARCH_RADIUS = 5;
    private final Villager villager;
    private BlockPos target;
    private boolean finished;

    public VillageFarmlandPreparationGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.isBaby() || villager.getTarget() != null
                || level.getGameTime() < villager.getPersistentData().getLong(NEXT_TRY)
                || VillageRoleService.ensureRole(villager) != VillageRoleService.Role.FARMER) {
            return false;
        }
        ItemStack tool = villager.getMainHandItem();
        if (!(tool.getItem() instanceof HoeItem) || tool.getDamageValue() >= tool.getMaxDamage()) return false;
        target = findCandidate(level, villager.blockPosition());
        return target != null;
    }

    @Override
    public void start() {
        finished = false;
        villager.getPersistentData().putString("villager_genesis_current_goal", "prepare_farmland");
    }

    @Override
    public void tick() {
        if (finished) return;
        finished = true;
        if (!(villager.level() instanceof ServerLevel level) || target == null
                || !level.hasChunkAt(target)) return;

        ItemStack hoe = villager.getMainHandItem();
        BlockState ground = level.getBlockState(target);
        if (!(hoe.getItem() instanceof HoeItem) || hoe.getDamageValue() >= hoe.getMaxDamage()
                || !isTillableGround(ground) || !level.getBlockState(target.above()).isAir()
                || !hasNearbyWater(level, target) || isProtectedArea(level, target)) return;

        level.setBlock(target, Blocks.FARMLAND.defaultBlockState(), 3);
        hoe.hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
        level.playSound(null, target, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 0.8F, 0.9F + villager.getRandom().nextFloat() * 0.2F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, target.getX() + 0.5D,
                target.getY() + 1.0D, target.getZ() + 0.5D, 2, 0.2D, 0.1D, 0.2D, 0.01D);
        villager.getPersistentData().putLong(NEXT_TRY, level.getGameTime() + COOLDOWN_TICKS);
        VillagerMemory memory = VillagerMemory.forVillager(villager);
        memory.learnKnowledge("farmland_preparation");
        memory.recordActionOutcome("prepare_farmland", "success",
                "Used a real equipped hoe to till existing ground near water.", level.getGameTime());
    }

    @Override
    public boolean canContinueToUse() {
        return !finished && villager.isAlive();
    }

    @Override
    public void stop() {
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
        target = null;
        finished = false;
    }

    private BlockPos findCandidate(ServerLevel level, BlockPos origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos) || !isTillableGround(level.getBlockState(pos))
                            || !level.getBlockState(pos.above()).isAir()
                            || !hasNearbyWater(level, pos) || isProtectedArea(level, pos)) continue;
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

    private boolean isTillableGround(BlockState state) {
        return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK);
    }

    private boolean hasNearbyWater(ServerLevel level, BlockPos pos) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 4) continue;
                BlockPos waterPos = pos.offset(dx, 0, dz);
                if (!level.hasChunkAt(waterPos)) continue;
                BlockState state = level.getBlockState(waterPos);
                if (state.is(Blocks.WATER) || state.getFluidState().getType() == Fluids.WATER) return true;
            }
        }
        return false;
    }

    private boolean isProtectedArea(ServerLevel level, BlockPos pos) {
        // Do not convert ground directly beside containers, beds, workstations, paths or doors.
        for (BlockPos nearby : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 2, 1))) {
            if (!level.hasChunkAt(nearby)) continue;
            BlockState state = level.getBlockState(nearby);
            if (state.is(Blocks.FARMLAND) || state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH)
                    || state.is(Blocks.BEDROCK) || state.is(Blocks.CRAFTING_TABLE)
                    || state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL)
                    || state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER)
                    || state.is(Blocks.BED) || state.is(Blocks.OAK_DOOR) || state.is(Blocks.SPRUCE_DOOR)
                    || state.is(Blocks.BIRCH_DOOR) || state.is(Blocks.JUNGLE_DOOR) || state.is(Blocks.ACACIA_DOOR)
                    || state.is(Blocks.DARK_OAK_DOOR) || state.is(Blocks.MANGROVE_DOOR) || state.is(Blocks.CHERRY_DOOR)
                    || state.is(Blocks.BAMBOO_DOOR) || state.is(Blocks.CRIMSON_DOOR) || state.is(Blocks.WARPED_DOOR)) {
                return true;
            }
            if (state.hasBlockEntity()) return true;
        }
        return false;
    }
}
