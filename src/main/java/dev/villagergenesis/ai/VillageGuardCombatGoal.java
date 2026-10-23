package dev.villagergenesis.ai;

import dev.villagergenesis.economy.VisibleStorageScanner;
import dev.villagergenesis.memory.VillagerMemory;
import dev.villagergenesis.society.VillageRoleService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Guard-only response to hostile monsters. Uses real equipped weapons and real arrows from
 * loaded public storage; only hostile Monster targets are eligible.
 */
public final class VillageGuardCombatGoal extends Goal {
    private static final double DETECT_RANGE = 12.0D;
    private static final double ATTACK_RANGE_SQR = 3.2D * 3.2D;
    private static final int ATTACK_COOLDOWN_TICKS = 20;

    private final Villager guard;
    private LivingEntity target;
    private int attackCooldown;
    private int repathCooldown;
    private BlockPos postPosition;

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
        if (target == null || !target.isAlive() || !(guard.level() instanceof ServerLevel level)) return;
        guard.getLookControl().setLookAt(target, 30.0F, 30.0F);

        boolean hasBow = guard.getMainHandItem().getItem() instanceof BowItem;
        double distance = guard.distanceToSqr(target);
        if (hasBow && distance <= DETECT_RANGE * DETECT_RANGE && distance >= 4.0D * 4.0D) {
            guard.getNavigation().stop();
            if (attackCooldown-- <= 0) {
                if (fireRealArrow(level, target)) {
                    VillagerMemory.forVillager(guard).recordActionOutcome("guard_ranged_combat", "success",
                            "Fired a real arrow at a hostile mob while defending the settlement.", level.getGameTime());
                }
                attackCooldown = ATTACK_COOLDOWN_TICKS;
            }
            return;
        }

        if (repathCooldown-- <= 0) {
            guard.getNavigation().moveTo(target, 1.05D);
            repathCooldown = 10;
        }
        if (distance <= ATTACK_RANGE_SQR && attackCooldown-- <= 0) {
            guard.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (guard.doHurtTarget(target)) {
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
        postPosition = null;
    }

    private boolean fireRealArrow(ServerLevel level, LivingEntity victim) {
        // The storage scanner is bounded and only inspects already-loaded chunks.
        VisibleStorageScanner.Snapshot snapshot;
        try {
            snapshot = VisibleStorageScanner.scan(level, guard.blockPosition(), 8);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        for (VisibleStorageScanner.StorageContainer found : snapshot.containers()) {
            BlockPos pos = new BlockPos(found.x(), found.y(), found.z());
            if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof Container container)) continue;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stored = container.getItem(slot);
                if (!stored.is(Items.ARROW) || stored.isEmpty()) continue;

                // Consume the real arrow before spawning the projectile to prevent free ammunition.
                ItemStack ammo = container.removeItem(slot, 1);
                if (ammo.isEmpty() || !ammo.is(Items.ARROW)) {
                    if (!ammo.isEmpty()) container.setItem(slot, ammo);
                    container.setChanged();
                    continue;
                }
                container.setChanged();

                ItemStack bow = guard.getMainHandItem();
                AbstractArrow arrow = ((ArrowItem) Items.ARROW).createArrow(level, ammo, guard, bow);
                double dx = victim.getX() - guard.getX();
                double dz = victim.getZ() - guard.getZ();
                double dy = victim.getY(1.0D / 3.0D) - arrow.getY()
                        + Math.sqrt(dx * dx + dz * dz) * 0.2D;
                arrow.shoot(dx, dy, dz, 1.6F, 6.0F);
                level.addFreshEntity(arrow);
                guard.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                return true;
            }
        }
        return false;
    }

    private LivingEntity findThreat() {
        AABB area = guard.getBoundingBox().inflate(DETECT_RANGE);
        List<Monster> threats = guard.level().getEntitiesOfClass(Monster.class, area,
                monster -> monster.isAlive() && !monster.isRemoved());
        return threats.stream()
                .min(Comparator.<Monster>comparingInt(monster -> monster.getTarget() instanceof Villager ? 0 : 1)
                        .thenComparingDouble(guard::distanceToSqr))
                .orElse(null);
    }
}
