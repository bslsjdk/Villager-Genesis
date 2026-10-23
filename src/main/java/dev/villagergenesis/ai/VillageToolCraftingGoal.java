package dev.villagergenesis.ai;

import dev.villagergenesis.memory.VillagerMemory;
import dev.villagergenesis.society.VillageRoleService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;

import java.util.EnumSet;
import java.util.List;

/**
 * Crafts and equips basic wooden tools from real inventory materials beside a placed crafting
 * table. It deliberately handles only the founding tool tier; it does not create materials,
 * bypass recipe costs, or replace unrelated held items.
 */
public final class VillageToolCraftingGoal extends Goal {
    private static final String NEXT_TRY = "villager_genesis_next_tool_craft";
    private static final String NEXT_TABLE_SCAN = "villager_genesis_next_tool_table_scan";
    private static final int COOLDOWN_TICKS = 160;
    private static final int TABLE_SCAN_COOLDOWN = 80;
    private final Villager villager;
    private Item desiredTool;
    private boolean finished;

    public VillageToolCraftingGoal(Villager villager) {
        this.villager = villager;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!(villager.level() instanceof ServerLevel level) || !villager.isAlive()
                || villager.isBaby() || villager.getTarget() != null
                || level.getGameTime() < villager.getPersistentData().getLong(NEXT_TRY)) return false;

        VillageRoleService.Role role = VillageRoleService.ensureRole(villager);
        desiredTool = switch (role) {
            case FARMER -> Items.WOODEN_HOE;
            case GATHERER, LUMBERJACK -> Items.WOODEN_AXE;
            case MINER -> Items.WOODEN_PICKAXE;
            default -> null;
        };
        if (desiredTool == null || alreadyHasUsableTool(desiredTool)) return false;

        ItemStack held = villager.getMainHandItem();
        Container inventory = villager.getInventory();
        if (!held.isEmpty() && !isMatchingTool(held, desiredTool)
                && !hasRoomForStack(inventory, held)) return false;

        if (findToolSlot(inventory, desiredTool) >= 0) return true;
        if (countPlanks(inventory) < plankCost(desiredTool)) return false;
        if (count(inventory, Items.STICK) < 2 && countPlanks(inventory) < plankCost(desiredTool) + 2) return false;
        if (level.getGameTime() < villager.getPersistentData().getLong(NEXT_TABLE_SCAN)) return false;
        villager.getPersistentData().putLong(NEXT_TABLE_SCAN, level.getGameTime() + TABLE_SCAN_COOLDOWN);
        return hasNearbyWorkbench(level, villager.blockPosition(), 5);
    }

    @Override
    public void start() {
        finished = false;
        villager.getPersistentData().putString("villager_genesis_current_goal", "craft_and_equip_tool");
    }

    @Override
    public void tick() {
        if (!(villager.level() instanceof ServerLevel level) || desiredTool == null) {
            finished = true;
            return;
        }
        Container inventory = villager.getInventory();
        int existingToolSlot = findToolSlot(inventory, desiredTool);
        if (existingToolSlot >= 0) {
            equipFromInventory(inventory, existingToolSlot, level);
            finish(level);
            finished = true;
            return;
        }

        // Preserve held seeds, food, and other items before equipping a crafted tool.
        ItemStack held = villager.getMainHandItem();
        if (!held.isEmpty() && !isMatchingTool(held, desiredTool)) {
            if (!hasRoomForStack(inventory, held) || !insertFully(inventory, held.copy())) {
                finish(level);
                finished = true;
                return;
            }
            villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            inventory.setChanged();
        }

        int planksNeeded = plankCost(desiredTool);
        if (count(inventory, Items.STICK) < 2) {
            if (countPlanks(inventory) < planksNeeded + 2 || !hasRoomForSticks(inventory)) {
                finish(level);
                finished = true;
                return;
            }
            if (!consumePlanks(inventory, 2)) {
                finish(level);
                finished = true;
                return;
            }
            insert(inventory, new ItemStack(Items.STICK, 4));
            inventory.setChanged();
            VillagerMemory.forVillager(villager).recordActionOutcome("craft_sticks", "success",
                    "Crafted four sticks from two real planks beside a crafting table.", level.getGameTime());
            VillagerMemory.forVillager(villager).learnKnowledge("basic_tool_crafting");
            // Wait for the next goal cycle to craft the tool; avoid performing a whole chain invisibly.
            finish(level);
            finished = true;
            return;
        }

        if (countPlanks(inventory) < planksNeeded || count(inventory, Items.STICK) < 2) {
            finish(level);
            finished = true;
            return;
        }
        if (!consumePlanks(inventory, planksNeeded) || !consume(inventory, Items.STICK, 2)) {
            finish(level);
            finished = true;
            return;
        }
        ItemStack tool = new ItemStack(desiredTool);
        villager.setItemSlot(EquipmentSlot.MAINHAND, tool);
        villager.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        inventory.setChanged();
        VillagerMemory memory = VillagerMemory.forVillager(villager);
        memory.learnKnowledge("basic_tool_crafting");
        memory.learnKnowledge("equipped_" + desiredTool.toString());
        memory.recordActionOutcome("craft_and_equip_tool", "success",
                "Crafted and equipped one wooden tool using real planks and sticks.", level.getGameTime());
        finish(level);
        finished = true;
    }

    @Override
    public boolean canContinueToUse() {
        return !finished && villager.isAlive();
    }

    @Override
    public void stop() {
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
        finished = false;
        desiredTool = null;
    }

    private void finish(ServerLevel level) {
        villager.getPersistentData().putLong(NEXT_TRY, level.getGameTime() + COOLDOWN_TICKS);
    }

    private boolean alreadyHasUsableTool(Item desired) {
        // Inventory tools are handled by the next branch so they can be equipped, not ignored.
        return isMatchingTool(villager.getMainHandItem(), desired);
    }

    private int findToolSlot(Container inventory, Item desired) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && isMatchingTool(stack, desired) && stack.getDamageValue() < stack.getMaxDamage()) {
                return i;
            }
        }
        return -1;
    }

    private boolean isMatchingTool(ItemStack stack, Item desired) {
        if (stack.isEmpty() || stack.getDamageValue() >= stack.getMaxDamage()) return false;
        if (desired == Items.WOODEN_HOE) return stack.getItem() instanceof HoeItem;
        if (desired == Items.WOODEN_AXE) return stack.getItem() instanceof AxeItem;
        if (desired == Items.WOODEN_PICKAXE) return stack.getItem() instanceof PickaxeItem;
        return false;
    }

    private void equipFromInventory(Container inventory, int slot, ServerLevel level) {
        ItemStack held = villager.getMainHandItem();
        if (!held.isEmpty() && !isMatchingTool(held, desiredTool)) return;
        ItemStack selected = inventory.getItem(slot).copy();
        if (selected.isEmpty() || !isMatchingTool(selected, desiredTool)) return;
        if (!held.isEmpty()) {
            inventory.setItem(slot, held.copy());
        } else {
            inventory.setItem(slot, ItemStack.EMPTY);
        }
        villager.setItemSlot(EquipmentSlot.MAINHAND, selected);
        villager.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        inventory.setChanged();
        VillagerMemory.forVillager(villager).recordActionOutcome("equip_crafted_tool", "success",
                "Equipped a tool from the villager's real inventory.", level.getGameTime());
    }

    private boolean hasNearbyWorkbench(ServerLevel level, BlockPos origin, int radius) {
        for (int dy = -2; dy <= 3; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius) continue;
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (level.hasChunkAt(pos) && level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int plankCost(Item tool) {
        return tool == Items.WOODEN_HOE ? 2 : 3;
    }

    private int countPlanks(Container inventory) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            Item item = inventory.getItem(i).getItem();
            if (isPlank(item)) total += inventory.getItem(i).getCount();
        }
        return total;
    }

    private boolean isPlank(Item item) {
        return item == Items.OAK_PLANKS || item == Items.SPRUCE_PLANKS || item == Items.BIRCH_PLANKS
                || item == Items.JUNGLE_PLANKS || item == Items.ACACIA_PLANKS || item == Items.DARK_OAK_PLANKS
                || item == Items.MANGROVE_PLANKS || item == Items.CHERRY_PLANKS || item == Items.BAMBOO_PLANKS
                || item == Items.CRIMSON_PLANKS || item == Items.WARPED_PLANKS;
    }

    private int count(Container inventory, Item item) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private boolean hasRoomForStack(Container inventory, ItemStack candidate) {
        int remaining = candidate.getCount();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(stack, candidate)) {
                remaining -= Math.max(0, stack.getMaxStackSize() - stack.getCount());
                if (remaining <= 0) return true;
            }
        }
        return false;
    }

    private boolean insertFully(Container inventory, ItemStack output) {
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
        return output.isEmpty();
    }

    private boolean hasRoomForSticks(Container inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || (stack.is(Items.STICK) && stack.getCount() <= stack.getMaxStackSize() - 4)) return true;
        }
        return false;
    }

    private boolean consumePlanks(Container inventory, int amount) {
        for (Item type : plankTypes()) {
            for (int i = 0; i < inventory.getContainerSize() && amount > 0; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.is(type)) continue;
                int used = Math.min(amount, stack.getCount());
                stack.shrink(used);
                amount -= used;
                if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
            }
            if (amount == 0) {
                inventory.setChanged();
                return true;
            }
        }
        return false;
    }

    private boolean consume(Container inventory, Item item, int amount) {
        if (count(inventory, item) < amount) return false;
        for (int i = 0; i < inventory.getContainerSize() && amount > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.is(item)) continue;
            int used = Math.min(amount, stack.getCount());
            stack.shrink(used);
            amount -= used;
            if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
        }
        inventory.setChanged();
        return amount == 0;
    }

    private List<Item> plankTypes() {
        return List.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
                Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS,
                Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.BAMBOO_PLANKS,
                Items.CRIMSON_PLANKS, Items.WARPED_PLANKS);
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
