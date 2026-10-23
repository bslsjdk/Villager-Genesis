package dev.villagergenesis.society;

import net.minecraft.world.entity.npc.Villager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.particles.ParticleTypes;

import java.util.List;

/**
 * Gives each villager a persistent Villager Genesis social role independent of vanilla profession.
 * Role assignment is deterministic and stable; it is not yet a dynamic staffing allocator.
 */
public final class VillageRoleService {
    public static final String ROLE_KEY = "villager_genesis_role";
    public static final String ROLE_ASSIGNED_AT_KEY = "villager_genesis_role_assigned_at";

    public enum Role {
        FARMER("farmer"),
        BUILDER("builder"),
        GATHERER("gatherer"),
        CRAFTER("crafter"),
        MINER("miner"),
        LUMBERJACK("lumberjack"),
        HUNTER("hunter"),
        HERDER("herder"),
        MAYOR("mayor"),
        MASON("mason"),
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
            Role.GATHERER, Role.CRAFTER, Role.MINER, Role.LUMBERJACK,
            Role.HUNTER, Role.HERDER, Role.MASON, Role.MAYOR, Role.GUARD, Role.SCOUT, Role.CARETAKER
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
        // A one-time, in-world cue makes the otherwise hidden builder assignment testable.
        if (assigned == Role.BUILDER && villager.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(),
                    villager.getY() + 1.0D, villager.getZ(), 4, 0.25D, 0.25D, 0.25D, 0.01D);
        }
        return assigned;
    }

    public static Role roleOf(Villager villager) {
        return Role.fromId(villager.getPersistentData().getString(ROLE_KEY));
    }

    /** Reuse Minecraft professions for smith work instead of inventing a duplicate social role. */
    public static boolean isVanillaSmith(Villager villager) {
        var profession = villager.getVillagerData().getProfession();
        return profession == net.minecraft.world.entity.npc.VillagerProfession.TOOLSMITH
                || profession == net.minecraft.world.entity.npc.VillagerProfession.WEAPONSMITH
                || profession == net.minecraft.world.entity.npc.VillagerProfession.ARMORER;
    }
}
