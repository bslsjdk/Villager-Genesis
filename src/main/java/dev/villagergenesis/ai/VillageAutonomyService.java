package dev.villagergenesis.ai;

import dev.villagergenesis.society.VillageRoleService;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Installs the optional low-priority autonomy goals once per live entity instance. */
public final class VillageAutonomyService {
    private static final Map<Villager, Boolean> INSTALLED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private VillageAutonomyService() {}

    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Villager villager) || event.getLevel().isClientSide) return;
        synchronized (INSTALLED) {
            if (INSTALLED.containsKey(villager)) return;
            // Social identity is mod-owned and independent of the vanilla profession.
            VillageRoleService.ensureRole(villager);
            // Ordinary residents prioritize escape over routine work when a hostile mob is close.
            villager.goalSelector.addGoal(0, new VillageResidentFleeGoal(villager));
            // Only assigned guards receive the mod-owned hostile-mob combat behavior.
            villager.goalSelector.addGoal(2, new VillageGuardCombatGoal(villager));
            // Until a real bed is available, residents rest on safe ground at night.
            villager.goalSelector.addGoal(3, new VillageGroundRestGoal(villager));
            // Real construction may preempt farming, but urgent vanilla behaviors retain priority.
            villager.goalSelector.addGoal(6, new VillageEquipmentGoal(villager));
            // Founders can build a tiny lean-to from nine real carried planks before a full house exists.
            villager.goalSelector.addGoal(6, new VillageStarterShelterGoal(villager));
            // Founding residents turn real carried logs into planks and then a crafting table.
            villager.goalSelector.addGoal(4, new VillageBootstrapCraftingGoal(villager));
            // Basic tools require real planks/sticks and an actually placed crafting table.
            villager.goalSelector.addGoal(4, new VillageToolCraftingGoal(villager));
            // Farmers can only create farmland when they actually hold a usable hoe.
            villager.goalSelector.addGoal(7, new VillageFarmlandPreparationGoal(villager));
            // Plant only real seeds and harvest only crops this villager recorded planting.
            villager.goalSelector.addGoal(7, new VillageCropWorkGoal(villager));
            // Founders with no seeds may gather only actual grass loot before the crop loop starts.
            villager.goalSelector.addGoal(7, new VillageSeedGatheringGoal(villager));
            // Transfer real food to nearby public storage so individual inventories feed the settlement.
            villager.goalSelector.addGoal(7, new VillageFoodDeliveryGoal(villager));
            villager.goalSelector.addGoal(4, new VillageWorkbenchPlacementGoal(villager));
            villager.goalSelector.addGoal(6, new VillageStorageConstructionGoal(villager));
            // Lumberjacks turn naturally grown trees into real stored building material.
            villager.goalSelector.addGoal(4, new VillageLumberjackGoal(villager));
            // The shelter is a mod-owned construction behavior, not a duplicate of vanilla farming.
            villager.goalSelector.addGoal(6, new VillageShelterConstructionGoal(villager));
            villager.goalSelector.addGoal(9, new VillageExplorationGoal(villager));
            INSTALLED.put(villager, Boolean.TRUE);
        }
    }
}
