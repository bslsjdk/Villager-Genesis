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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * Moves real ready-to-eat food from a resident's inventory into nearby public storage.
 * Scans only loaded chunks and ordinary chests, trapped chests, or barrels; it never creates
 * items and only removes inventory items after the destination accepted them.
 */
public final class VillageFoodDeliveryGoal extends Goal {
    private static final String NEXT_TRY = "villager_genesis_next_food_delivery";
    private static final int COOLDOWN_TICKS = 400;
    private static final int SEARCH_RADIUS = 6;
    private final Villager villager;
    private BlockPos target;
    private int travelTicks;
    private boolean finished;

    public VillageFoodDeliveryGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.isBaby() || villager.getTarget() != null || !villager.getNavigation().isDone()
                || level.getGameTime() < villager.getPersistentData().getLong(NEXT_TRY)) return false;
        VillageRoleService.Role role = VillageRoleService.ensureRole(villager);
        if (role != VillageRoleService.Role.FARMER && role != VillageRoleService.Role.GATHERER
                && role != VillageRoleService.Role.CARETAKER && role != VillageRoleService.Role.MAYOR) return false;
        if (!hasReadyFood(villager.getInventory())) return false;
        target = findStorage(level, villager.blockPosition());
        return target != null;
    }

    @Override
    public void start() {
        travelTicks = 0;
        finished = false;
        villager.getPersistentData().putString("villager_genesis_current_goal", "deliver_food");
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
            if (travelTicks >= 120) finished = true;
            else if (villager.getNavigation().isDone() && travelTicks % 20 == 0) navigate();
            return;
        }
        finished = true;
        BlockState state = level.getBlockState(target);
        if (!isPublicStorage(state)) return;
        BlockEntity entity = level.getBlockEntity(target);
        if (!(entity instanceof Container destination)) return;
        Container inventory = villager.getInventory();
        boolean movedAny = false;
        for (int sourceSlot = 0; sourceSlot < inventory.getContainerSize(); sourceSlot++) {
            ItemStack source = inventory.getItem(sourceSlot);
            if (source.isEmpty() || !isReadyFood(source.getItem())) continue;
            ItemStack remainder = insertInto(destination, source.copy());
            int moved = source.getCount() - remainder.getCount();
            if (moved <= 0) continue;
            source.shrink(moved);
            if (source.isEmpty()) inventory.setItem(sourceSlot, ItemStack.EMPTY);
            movedAny = true;
        }
        if (movedAny) {
            inventory.setChanged();
            destination.setChanged();
            level.playSound(null, target, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.35F, 0.8F);
            VillagerMemory.forVillager(villager).recordActionOutcome("deliver_food", "success",
                    "Moved real ready-to-eat food into nearby public storage.", level.getGameTime());
            VillagerMemory.forVillager(villager).learnKnowledge("food_distribution");
        }
        villager.getPersistentData().putLong(NEXT_TRY, level.getGameTime() + COOLDOWN_TICKS);
    }

    @Override
    public boolean canContinueToUse() {
        return !finished && target != null && travelTicks < 120 && villager.isAlive()
                && villager.getTarget() == null;
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

    private BlockPos findStorage(ServerLevel level, BlockPos origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    if (dx * dx + dz * dz > SEARCH_RADIUS * SEARCH_RADIUS) continue;
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (!isPublicStorage(state)) continue;
                    BlockEntity entity = level.getBlockEntity(pos);
                    if (!(entity instanceof Container container) || !canAcceptFood(container, villager.getInventory())) continue;
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

    private boolean isPublicStorage(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL);
    }

    private boolean canAcceptFood(Container destination, Container inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack source = inventory.getItem(i);
            if (source.isEmpty() || !isReadyFood(source.getItem())) continue;
            ItemStack remaining = source.copy();
            for (int j = 0; j < destination.getContainerSize() && !remaining.isEmpty(); j++) {
                ItemStack slot = destination.getItem(j);
                if (slot.isEmpty()) return true;
                if (ItemStack.isSameItemSameComponents(slot, remaining)
                        && slot.getCount() < Math.min(slot.getMaxStackSize(), destination.getMaxStackSize())) return true;
            }
        }
        return false;
    }

    private ItemStack insertInto(Container destination, ItemStack remaining) {
        for (int i = 0; i < destination.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = destination.getItem(i);
            if (slot.isEmpty()) {
                int moved = Math.min(remaining.getCount(), Math.min(remaining.getMaxStackSize(), destination.getMaxStackSize()));
                destination.setItem(i, remaining.copyWithCount(moved));
                remaining.shrink(moved);
            } else if (ItemStack.isSameItemSameComponents(slot, remaining)) {
                int limit = Math.min(slot.getMaxStackSize(), destination.getMaxStackSize());
                int moved = Math.min(remaining.getCount(), limit - slot.getCount());
                if (moved > 0) {
                    slot.grow(moved);
                    remaining.shrink(moved);
                }
            }
        }
        return remaining;
    }

    private boolean hasReadyFood(Container inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && isReadyFood(stack.getItem())) return true;
        }
        return false;
    }

    private boolean isReadyFood(Item item) {
        return item == Items.BREAD || item == Items.CARROT || item == Items.POTATO || item == Items.BEETROOT;
    }
}
