package dev.villagergenesis.ai;

import dev.villagergenesis.economy.VisibleStorageScanner;
import dev.villagergenesis.memory.VillagerMemory;
import dev.villagergenesis.society.VillageRoleService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;

import java.util.EnumSet;

/**
 * Equips residents from real public storage. Guards are always eligible; a stable minority of
 * ordinary residents are willing to learn basic self-defence. No equipment is created.
 */
public final class VillageEquipmentGoal extends Goal {
    private static final String NEXT_TRY = "villager_genesis_next_equipment_try";
    private static final int COOLDOWN = 200;
    private static final int MAX_TRAVEL_TICKS = 160;

    private final Villager villager;
    private BlockPos source;
    private int sourceSlot = -1;
    private EquipmentSlot targetSlot;
    private int travelTicks;
    private boolean finished;

    public VillageEquipmentGoal(Villager villager) {
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
        boolean guard = role == VillageRoleService.Role.GUARD;
        boolean willingResident = Math.floorMod(villager.getUUID().hashCode(), 3) == 0;
        if (!guard && !willingResident
                && !VillagerMemory.forVillager(villager).knownKnowledge().contains("basic_defense")) {
            return false;
        }

        VisibleStorageScanner.Snapshot snapshot;
        try {
            snapshot = VisibleStorageScanner.scan(level, villager.blockPosition(), 8);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        EquipmentSlot[] order = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : order) {
            if (!villager.getItemBySlot(slot).isEmpty()) continue;
            for (VisibleStorageScanner.StorageContainer found : snapshot.containers()) {
                BlockPos pos = new BlockPos(found.x(), found.y(), found.z());
                if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof Container container)) continue;
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (matchesSlot(stack, slot)) {
                        source = pos;
                        sourceSlot = i;
                        targetSlot = slot;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void start() {
        travelTicks = 0;
        finished = false;
        villager.getPersistentData().putString("villager_genesis_current_goal", "equip_for_safety");
        navigate();
    }

    @Override
    public boolean canContinueToUse() {
        return !finished && source != null && targetSlot != null
                && travelTicks < MAX_TRAVEL_TICKS && villager.isAlive()
                && villager.getTarget() == null;
    }

    @Override
    public void tick() {
        travelTicks++;
        if (!(villager.level() instanceof ServerLevel level) || source == null || targetSlot == null) {
            finished = true;
            return;
        }
        if (villager.blockPosition().distSqr(source) <= 4.0D) {
            equipFromStorage(level);
            finished = true;
        } else if (villager.getNavigation().isDone() && travelTicks % 30 == 0) {
            navigate();
        }
    }

    @Override
    public void stop() {
        villager.getNavigation().stop();
        if (villager.level() instanceof ServerLevel level) {
            villager.getPersistentData().putLong(NEXT_TRY, level.getGameTime() + COOLDOWN);
        }
        villager.getPersistentData().putString("villager_genesis_current_goal", "observe");
        source = null;
        sourceSlot = -1;
        targetSlot = null;
        travelTicks = 0;
        finished = false;
    }

    private void navigate() {
        if (source != null) {
            villager.getNavigation().moveTo(source.getX() + 0.5D, source.getY(),
                    source.getZ() + 0.5D, 0.75D);
        }
    }

    private void equipFromStorage(ServerLevel level) {
        if (!level.hasChunkAt(source) || !(level.getBlockEntity(source) instanceof Container container)
                || sourceSlot < 0 || sourceSlot >= container.getContainerSize()
                || !villager.getItemBySlot(targetSlot).isEmpty()) return;
        ItemStack stored = container.getItem(sourceSlot);
        if (!matchesSlot(stored, targetSlot)) return;

        ItemStack equipped = container.removeItem(sourceSlot, 1);
        if (equipped.isEmpty() || !matchesSlot(equipped, targetSlot)) {
            if (!equipped.isEmpty()) container.setItem(sourceSlot, equipped.copy());
            container.setChanged();
            return;
        }
        villager.setItemSlot(targetSlot, equipped);
        villager.setDropChance(targetSlot, 0.0F);
        container.setChanged();

        VillagerMemory memory = VillagerMemory.forVillager(villager);
        memory.learnKnowledge("hostile_awareness");
        memory.learnKnowledge("basic_defense");
        memory.recordActionOutcome("equip_for_safety", "success",
                "Equipped one real item taken from village storage: " + targetSlot.getName() + ".", level.getGameTime());
    }

    private boolean matchesSlot(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return false;
        if (slot == EquipmentSlot.MAINHAND) {
            return stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem
                    || (VillageRoleService.ensureRole(villager) == VillageRoleService.Role.GUARD
                    && stack.getItem() instanceof BowItem);
        }
        if (slot == EquipmentSlot.OFFHAND) return stack.is(Items.SHIELD);
        return stack.getItem() instanceof ArmorItem armor && armor.getEquipmentSlot() == slot;
    }
}
