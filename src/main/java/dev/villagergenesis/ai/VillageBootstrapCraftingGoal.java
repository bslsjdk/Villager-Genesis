package dev.villagergenesis.ai;

import dev.villagergenesis.memory.VillagerMemory;
import dev.villagergenesis.society.VillageRoleService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;
import java.util.List;

/**
 * Minimal, real-inventory bootstrap crafting. It uses only vanilla 1x1 log-to-plank and
 * 2x2 plank-to-crafting-table recipes; it never creates output without consuming ingredients.
 * Tool crafting and placing the table are separate follow-up tasks.
 */
public final class VillageBootstrapCraftingGoal extends Goal {
    private static final String NEXT_TRY = "villager_genesis_next_bootstrap_craft";
    private static final int COOLDOWN_TICKS = 20;
    private final Villager villager;

    public VillageBootstrapCraftingGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!VillageWorkPolicy.canPerformRoutineWork(villager)) return false;
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.isBaby() || villager.getTarget() != null
                || level.getGameTime() < villager.getPersistentData().getLong(NEXT_TRY)) return false;
        VillageRoleService.Role role = VillageRoleService.ensureRole(villager);
        if (role != VillageRoleService.Role.FARMER && role != VillageRoleService.Role.GATHERER
                && role != VillageRoleService.Role.LUMBERJACK && role != VillageRoleService.Role.CRAFTER
                && role != VillageRoleService.Role.BUILDER) return false;
        Container inventory = villager.getInventory();
        return findLogSlot(inventory) >= 0 || (count(inventory, Items.CRAFTING_TABLE) == 0
                && hasFourPlanks(inventory)
                && !hasNearbyWorkbench(level, villager.blockPosition(), 5));
    }

    @Override
    public void start() {
        villager.getPersistentData().putString("villager_genesis_current_goal", "bootstrap_crafting");
    }

    @Override
    public void tick() {
        VillageWorkPolicy.claimRoutineWork(villager);
        if (!(villager.level() instanceof ServerLevel level)) return;
        Container inventory = villager.getInventory();

        // First make one crafting table if the villager does not already carry one.
        // This is a real vanilla recipe: four planks consumed, one table produced.
        if (count(inventory, Items.CRAFTING_TABLE) == 0 && hasFourPlanks(inventory)
                && !hasNearbyWorkbench(level, villager.blockPosition(), 5)
                && hasFreeSlotOrMerge(inventory, Items.CRAFTING_TABLE)) {
            if (consumePlanks(inventory, 4)) {
                insert(inventory, new ItemStack(Items.CRAFTING_TABLE));
                inventory.setChanged();
                VillagerMemory.forVillager(villager).recordActionOutcome("bootstrap_crafting", "success",
                        "Crafted one crafting table from four real plank items.", level.getGameTime());
                VillagerMemory.forVillager(villager).learnKnowledge("basic_crafting");
                finish(level);
                return;
            }
        }

        // Convert one real log into its matching four planks, matching the vanilla shapeless
        // single-log recipe family without mixing wood species.
        int logSlot = findLogSlot(inventory);
        if (logSlot >= 0) {
            ItemStack log = inventory.getItem(logSlot);
            Item plank = planksFor(log.getItem());
            if (plank != null && hasFreeSlotOrMerge(inventory, plank)) {
                log.shrink(1);
                if (log.isEmpty()) inventory.setItem(logSlot, ItemStack.EMPTY);
                insert(inventory, new ItemStack(plank, 4));
                inventory.setChanged();
                VillagerMemory.forVillager(villager).recordActionOutcome("bootstrap_crafting", "success",
                        "Converted one real log into four matching planks.", level.getGameTime());
                VillagerMemory.forVillager(villager).learnKnowledge("basic_crafting");
            }
        }
        finish(level);
    }

    @Override
    public boolean canContinueToUse() {
        if (!VillageWorkPolicy.canPerformRoutineWork(villager)) return false;
        return false;
    }

    @Override
    public void stop() {
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
    }

    private void finish(ServerLevel level) {
        villager.getPersistentData().putLong(NEXT_TRY, level.getGameTime() + COOLDOWN_TICKS);
    }

    private boolean hasNearbyWorkbench(ServerLevel level, BlockPos origin, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius) continue;
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (level.hasChunkAt(pos) && level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int findLogSlot(Container inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            Item item = inventory.getItem(i).getItem();
            if (planksFor(item) != null && isLog(item)) return i;
        }
        return -1;
    }

    private boolean isLog(Item item) {
        return planksFor(item) != null;
    }

    private Item planksFor(Item item) {
        if (item == Items.OAK_LOG || item == Items.OAK_WOOD || item == Items.STRIPPED_OAK_LOG || item == Items.STRIPPED_OAK_WOOD) return Items.OAK_PLANKS;
        if (item == Items.SPRUCE_LOG || item == Items.SPRUCE_WOOD || item == Items.STRIPPED_SPRUCE_LOG || item == Items.STRIPPED_SPRUCE_WOOD) return Items.SPRUCE_PLANKS;
        if (item == Items.BIRCH_LOG || item == Items.BIRCH_WOOD || item == Items.STRIPPED_BIRCH_LOG || item == Items.STRIPPED_BIRCH_WOOD) return Items.BIRCH_PLANKS;
        if (item == Items.JUNGLE_LOG || item == Items.JUNGLE_WOOD || item == Items.STRIPPED_JUNGLE_LOG || item == Items.STRIPPED_JUNGLE_WOOD) return Items.JUNGLE_PLANKS;
        if (item == Items.ACACIA_LOG || item == Items.ACACIA_WOOD || item == Items.STRIPPED_ACACIA_LOG || item == Items.STRIPPED_ACACIA_WOOD) return Items.ACACIA_PLANKS;
        if (item == Items.DARK_OAK_LOG || item == Items.DARK_OAK_WOOD || item == Items.STRIPPED_DARK_OAK_LOG || item == Items.STRIPPED_DARK_OAK_WOOD) return Items.DARK_OAK_PLANKS;
        if (item == Items.MANGROVE_LOG || item == Items.MANGROVE_WOOD || item == Items.STRIPPED_MANGROVE_LOG || item == Items.STRIPPED_MANGROVE_WOOD) return Items.MANGROVE_PLANKS;
        if (item == Items.CHERRY_LOG || item == Items.CHERRY_WOOD || item == Items.STRIPPED_CHERRY_LOG || item == Items.STRIPPED_CHERRY_WOOD) return Items.CHERRY_PLANKS;
        if (item == Items.BAMBOO_BLOCK || item == Items.STRIPPED_BAMBOO_BLOCK) return Items.BAMBOO_PLANKS;
        if (item == Items.CRIMSON_STEM || item == Items.CRIMSON_HYPHAE || item == Items.STRIPPED_CRIMSON_STEM || item == Items.STRIPPED_CRIMSON_HYPHAE) return Items.CRIMSON_PLANKS;
        if (item == Items.WARPED_STEM || item == Items.WARPED_HYPHAE || item == Items.STRIPPED_WARPED_STEM || item == Items.STRIPPED_WARPED_HYPHAE) return Items.WARPED_PLANKS;
        return null;
    }

    private int count(Container inventory, Item item) {
        int result = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) result += stack.getCount();
        }
        return result;
    }

    private boolean hasFourPlanks(Container inventory) {
        return count(inventory, Items.OAK_PLANKS) + count(inventory, Items.SPRUCE_PLANKS)
                + count(inventory, Items.BIRCH_PLANKS) + count(inventory, Items.JUNGLE_PLANKS)
                + count(inventory, Items.ACACIA_PLANKS) + count(inventory, Items.DARK_OAK_PLANKS)
                + count(inventory, Items.MANGROVE_PLANKS) + count(inventory, Items.CHERRY_PLANKS)
                + count(inventory, Items.BAMBOO_PLANKS) + count(inventory, Items.CRIMSON_PLANKS)
                + count(inventory, Items.WARPED_PLANKS) >= 4;
    }

    private boolean consumePlanks(Container inventory, int amount) {
        List<Item> types = List.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
                Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS,
                Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.BAMBOO_PLANKS,
                Items.CRIMSON_PLANKS, Items.WARPED_PLANKS);
        for (Item type : types) {
            for (int i = 0; i < inventory.getContainerSize() && amount > 0; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.is(type)) continue;
                int used = Math.min(amount, stack.getCount());
                stack.shrink(used);
                amount -= used;
                if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
            }
            if (amount == 0) return true;
        }
        return false;
    }

    private boolean hasFreeSlotOrMerge(Container inventory, Item item) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || (stack.is(item) && stack.getCount() < stack.getMaxStackSize())) return true;
        }
        return false;
    }

    private void insert(Container inventory, ItemStack output) {
        for (int i = 0; i < inventory.getContainerSize() && !output.isEmpty(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                inventory.setItem(i, output.copy());
                output.setCount(0);
            } else if (ItemStack.isSameItemSameComponents(stack, output)
                    && stack.getCount() < stack.getMaxStackSize()) {
                int moved = Math.min(output.getCount(), stack.getMaxStackSize() - stack.getCount());
                stack.grow(moved);
                output.shrink(moved);
            }
        }
    }
}
