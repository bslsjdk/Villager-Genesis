package dev.villagergenesis.ai;

import dev.villagergenesis.society.VillageRoleService;
import dev.villagergenesis.memory.VillagerMemory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Minimal guard-only melee response. It attacks hostile monsters only, never players or
 * passive/neutral animals, and uses the villager's actual held item and vanilla damage rules.
 */
public final class VillageGuardCombatGoal extends Goal {
    private static final double DETECT_RANGE = 12.0D;
    private static final double ATTACK_RANGE_SQR = 3.2D * 3.2D;
    private static final int ATTACK_COOLDOWN_TICKS = 20;

    private final Villager guard;
    private LivingEntity target;
    private int attackCooldown;
    private int repathCooldown;
    private net.minecraft.core.BlockPos postPosition;

    public VillageGuardCombatGoal(Villager guard) {
        this.guard = guard;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(guard.level() instanceof ServerLevel) || !guard.isAlive() || guard.isBaby()
                || VillageRoleService.ensureRole(guard) != VillageRoleService.Role.GUARD) return false;
        target = findThreat();
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive() && guard.isAlive()
                && guard.distanceToSqr(target) <= DETECT_RANGE * DETECT_RANGE
                && (postPosition == null || guard.blockPosition().distSqr(postPosition) <= 24.0D * 24.0D)
                && target instanceof Monster;
    }

    @Override
    public void start() {
        attackCooldown = 0;
        repathCooldown = 0;
        postPosition = guard.blockPosition();
        guard.setTarget(target);
    }

    @Override
    public void tick() {
        if (target == null || !target.isAlive()) return;
        guard.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (repathCooldown-- <= 0) {
            guard.getNavigation().moveTo(target, 1.05D);
            repathCooldown = 10;
        }
        if (guard.distanceToSqr(target) <= ATTACK_RANGE_SQR && attackCooldown-- <= 0) {
            guard.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (guard.doHurtTarget(target) && guard.level() instanceof ServerLevel level) {
                VillagerMemory.forVillager(guard).recordActionOutcome("guard_combat", "success",
                        "Landed a melee hit on a hostile mob while defending the settlement.", level.getGameTime());
            }
            attackCooldown = ATTACK_COOLDOWN_TICKS;
        }
    }

    @Override
    public void stop() {
        guard.getNavigation().stop();
        if (guard.getTarget() == target) guard.setTarget(null);
        target = null;
        attackCooldown = 0;
        repathCooldown = 0;
    }

    private LivingEntity findThreat() {
        AABB area = guard.getBoundingBox().inflate(DETECT_RANGE);
        List<Monster> threats = guard.level().getEntitiesOfClass(Monster.class, area,
                monster -> monster.isAlive() && !monster.isRemoved());
        // Prefer a hostile mob already attacking a villager, then choose the nearest remaining threat.
        return threats.stream()
                .min(Comparator.<Monster>comparingInt(monster -> monster.getTarget() instanceof Villager ? 0 : 1)
                        .thenComparingDouble(guard::distanceToSqr))
                .orElse(null);
    }
}
