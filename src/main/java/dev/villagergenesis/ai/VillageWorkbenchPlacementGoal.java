package dev.villagergenesis.ai;

import dev.villagergenesis.memory.VillagerMemory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

/** Places a carried, genuinely crafted crafting table on safe, empty ground in loaded terrain. */
public final class VillageWorkbenchPlacementGoal extends Goal {
    private static final String NEXT_TRY = "villager_genesis_next_workbench_place";
    private static final int COOLDOWN_TICKS = 200;
    private static final int MAX_TRAVEL_TICKS = 160;
    private final Villager villager;
    private BlockPos site;
    private int travelTicks;
    private boolean finished;

    public VillageWorkbenchPlacementGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.isBaby() || villager.getTarget() != null
                || !villager.getNavigation().isDone()
                || level.getGameTime() < villager.getPersistentData().getLong(NEXT_TRY)
                || !hasCarriedTable()) return false;
        if (hasNearbyWorkbench(level, villager.blockPosition())) return false;
        site = findSite(level, villager.blockPosition());
        return site != null;
    }

    @Override
    public void start() {
        travelTicks = 0;
        finished = false;
        villager.getPersistentData().putString("villager_genesis_current_goal", "place_workbench");
        navigate();
    }

    @Override
    public boolean canContinueToUse() {
        return !finished && site != null && travelTicks < MAX_TRAVEL_TICKS
                && villager.isAlive() && villager.getTarget() == null;
    }

    @Override
    public void tick() {
        travelTicks++;
        if (!(villager.level() instanceof ServerLevel level) || site == null) {
            finished = true;
            return;
        }
        if (villager.blockPosition().distSqr(site) <= 4.0D) {
            place(level);
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
        site = null;
        travelTicks = 0;
        finished = false;
    }

    private boolean hasCarriedTable() {
        for (int i = 0; i < villager.getInventory().getContainerSize(); i++) {
            if (villager.getInventory().getItem(i).is(Items.CRAFTING_TABLE)) return true;
        }
        return false;
    }

    private boolean hasNearbyWorkbench(ServerLevel level, BlockPos origin) {
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-12, -4, -12), origin.offset(12, 4, 12))) {
            if (!level.hasChunkAt(pos)) continue;
            if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) return true;
        }
        return false;
    }

    private BlockPos findSite(ServerLevel level, BlockPos origin) {
        for (int radius = 2; radius <= 6; radius++) {
            for (int attempt = 0; attempt < 24; attempt++) {
                int dx = villager.getRandom().nextInt(radius * 2 + 1) - radius;
                int dz = villager.getRandom().nextInt(radius * 2 + 1) - radius;
                if (Math.max(Math.abs(dx), Math.abs(dz)) < 2) continue;
                BlockPos pos = origin.offset(dx, 0, dz);
                BlockPos floorPos = pos.below();
                if (!level.hasChunkAt(floorPos) || !level.hasChunkAt(pos)) continue;
                BlockState floor = level.getBlockState(floorPos);
                if (!floor.isFaceSturdy(level, floorPos, Direction.UP)
                        || !level.getBlockState(pos).isAir()
                        || !level.getFluidState(pos).isEmpty()) continue;
                if (!level.getEntities(villager, new AABB(pos).inflate(0.35D),
                        entity -> entity.isAlive()).isEmpty()) continue;
                return pos.immutable();
            }
        }
        return null;
    }

    private void navigate() {
        if (site != null) villager.getNavigation().moveTo(
                site.getX() + 0.5D, site.getY(), site.getZ() + 0.5D, 0.7D);
    }

    private void place(ServerLevel level) {
        if (site == null || !level.hasChunkAt(site) || !level.hasChunkAt(site.below())
                || !level.getBlockState(site).isAir()
                || !level.getFluidState(site).isEmpty()
                || !level.getBlockState(site.below()).isFaceSturdy(level, site.below(), Direction.UP)
                || !level.getEntities(villager, new AABB(site).inflate(0.35D),
                entity -> entity.isAlive()).isEmpty()) return;

        int slot = -1;
        for (int i = 0; i < villager.getInventory().getContainerSize(); i++) {
            if (villager.getInventory().getItem(i).is(Items.CRAFTING_TABLE)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) return;
        ItemStack table = villager.getInventory().getItem(slot);
        if (table.isEmpty() || !table.is(Items.CRAFTING_TABLE)) return;
        if (!level.setBlock(site, Blocks.CRAFTING_TABLE.defaultBlockState(), 3)) return;
        table.shrink(1);
        if (table.isEmpty()) villager.getInventory().setItem(slot, ItemStack.EMPTY);
        villager.getInventory().setChanged();
        level.playSound(null, site, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, site.getX() + 0.5D,
                site.getY() + 0.8D, site.getZ() + 0.5D, 8, 0.2D, 0.2D, 0.2D, 0.01D);
        VillagerMemory.forVillager(villager).recordActionOutcome("place_workbench", "success",
                "Placed one carried crafting table on verified empty ground.", level.getGameTime());
        VillagerMemory.forVillager(villager).learnKnowledge("workbench_setup");
    }
}
