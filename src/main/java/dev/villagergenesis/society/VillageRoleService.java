package dev.villagergenesis.society;

import net.minecraft.world.entity.npc.Villager;

import java.util.List;

/**
 * Gives each villager a persistent Villager Genesis social role independent of its vanilla
 * profession. This is a stable identity seed, not the settlement-wide staffing allocator yet:
 * later census/planning passes may propose reassignment when real village needs are available.
 */
public final class VillageRoleService {
    public static final String ROLE_KEY = "villager_genesis_role";
    public static final String ROLE_ASSIGNED_AT_KEY = "villager_genesis_role_assigned_at";

    public enum Role {
        FARMER("farmer"),
        BUILDER("builder"),
        GATHERER("gatherer"),
        CRAFTER("crafter"),
        GUARD("guard"),
        SCOUT("scout"),
        CARETAKER("caretaker");

        private final String id;
        Role(String id) { this.id = id; }
        public String id() { return id; }

        public static Role fromId(String id) {
            for (Role role : values()) if (role.id.equals(id)) return role;
            return null;
        }
    }

    private static final List<Role> INITIAL_ROLES = List.of(
            Role.FARMER, Role.FARMER, Role.BUILDER, Role.GATHERER,
            Role.GATHERER, Role.CRAFTER, Role.GUARD, Role.SCOUT, Role.CARETAKER
    );

    private VillageRoleService() {}

    /** Assign exactly once; never overwrite a valid saved role during ordinary observations. */
    public static Role ensureRole(Villager villager) {
        var data = villager.getPersistentData();
        Role existing = Role.fromId(data.getString(ROLE_KEY));
        if (existing != null) return existing;

        int index = Math.floorMod(villager.getUUID().hashCode(), INITIAL_ROLES.size());
        Role assigned = INITIAL_ROLES.get(index);
        data.putString(ROLE_KEY, assigned.id());
        data.putLong(ROLE_ASSIGNED_AT_KEY, villager.level().getGameTime());
        return assigned;
    }

    public static Role roleOf(Villager villager) {
        return Role.fromId(villager.getPersistentData().getString(ROLE_KEY));
    }
}
