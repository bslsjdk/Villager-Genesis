package dev.villagergenesis.simulation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map;

/**
 * Low-cost scheduler for the two-mode settlement simulation.
 *
 * It deliberately processes at most one saved settlement per interval, does not load chunks,
 * does not scan blocks, and never invents inventory or world changes. This first layer records
 * when a settlement is eligible for abstract simulation and switches it to LIVE near players.
 * Actual economy/building steps must be added as bounded, resource-validated jobs.
 */
public final class VillageOfflineSimulationService {
    private static final int SETTLEMENT_REGION_SIZE = 32;
    private static final int PLAYER_ACTIVE_RADIUS = 96;
    private static final long SCHEDULER_INTERVAL = 200L;
    private static final Map<ServerLevel, Long> NEXT_LEVEL_STEP = new WeakHashMap<>();
    private static final Map<ServerLevel, Integer> ROUND_ROBIN = new WeakHashMap<>();

    private VillageOfflineSimulationService() {}

    public static void onVillagerJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Villager villager) || event.getLevel().isClientSide
                || !(event.getLevel() instanceof ServerLevel level)) return;

        int regionX = villager.blockPosition().getX() >> 5;
        int regionZ = villager.blockPosition().getZ() >> 5;
        String dimension = level.dimension().location().toString();
        String key = dimension + ":" + regionX + ":" + regionZ;
        UUID id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        var anchor = villager.blockPosition().offset(
                ((regionX << 5) + 16) - villager.blockPosition().getX(),
                0,
                ((regionZ << 5) + 16) - villager.blockPosition().getZ());
        VillageSimulationData data = VillageSimulationData.get(level);
        data.getOrCreate(id, dimension, anchor, level.getGameTime());
        data.markChanged();
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        synchronized (NEXT_LEVEL_STEP) {
            if (now < NEXT_LEVEL_STEP.getOrDefault(level, 0L)) return;
            NEXT_LEVEL_STEP.put(level, now + SCHEDULER_INTERVAL);
            if (NEXT_LEVEL_STEP.size() > 64) {
                NEXT_LEVEL_STEP.entrySet().removeIf(entry -> entry.getValue() <= now);
            }
        }

        VillageSimulationData data = VillageSimulationData.get(level);
        List<VillageSimulationData.Settlement> settlements = data.settlements();
        if (settlements.isEmpty()) return;

        int index;
        synchronized (ROUND_ROBIN) {
            index = Math.floorMod(ROUND_ROBIN.getOrDefault(level, 0), settlements.size());
            ROUND_ROBIN.put(level, (index + 1) % settlements.size());
        }
        VillageSimulationData.Settlement settlement = settlements.get(index);
        boolean playerNearby = false;
        double radiusSqr = (double) PLAYER_ACTIVE_RADIUS * PLAYER_ACTIVE_RADIUS;
        for (var player : level.players()) {
            if (player.distanceToSqr(settlement.anchor().getX() + 0.5D,
                    settlement.anchor().getY() + 0.5D,
                    settlement.anchor().getZ() + 0.5D) <= radiusSqr) {
                playerNearby = true;
                break;
            }
        }

        if (playerNearby) {
            if (settlement.mode() != VillageSimulationData.Mode.LIVE) {
                settlement.setMode(VillageSimulationData.Mode.LIVE);
                data.markChanged();
            }
            return;
        }

        // Do not fast-forward an unbounded number of days after long absences.
        // The scheduler advances metadata only; real food/material balances remain untouched.
        if (settlement.mode() != VillageSimulationData.Mode.ABSTRACT) {
            settlement.setMode(VillageSimulationData.Mode.ABSTRACT);
        }
        if (now >= settlement.nextWorkGameTime()) {
            settlement.advanceClock(now, now + SCHEDULER_INTERVAL);
            data.markChanged();
        }
    }
}
