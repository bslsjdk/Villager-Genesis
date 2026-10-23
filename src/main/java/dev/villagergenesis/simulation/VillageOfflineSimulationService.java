package dev.villagergenesis.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map;

/**
 * Data-only macro simulation. Candidate locations are generated from the world seed and spawn
 * lattice, not from player builds or existing villagers. Terrain metadata is sampled without
 * requesting chunks. No code in this service places blocks or creates entities.
 */
public final class VillageOfflineSimulationService {
    private static final long SCHEDULER_INTERVAL = 200L;
    private static final int NODE_SPACING = 512;
    private static final int PLAYER_WAKE_RADIUS = 112;
    private static final int GRAPH_NODE_BUDGET = 2;
    private static final int MAX_ABSTRACT_NODES = 512;
    private static final int GRAPH_NEIGHBOURS = 3;
    private static final Map<ServerLevel, Long> NEXT_LEVEL_STEP = new WeakHashMap<>();
    private static final Map<ServerLevel, Integer> CANDIDATE_CURSOR = new WeakHashMap<>();
    private static final Map<ServerLevel, Integer> ROUND_ROBIN = new WeakHashMap<>();

    private VillageOfflineSimulationService() {}

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

        SettlementNodeGraph graph = SettlementNodeGraph.get(level);
        seedOneCandidate(level, graph, now);
        if (!graph.nodes().isEmpty()) {
            graph.rebuildLocalEdges(GRAPH_NODE_BUDGET, GRAPH_NEIGHBOURS, now);
            advanceOneNode(level, graph, now);
        }
    }

    /**
     * Nodes are selected on a deterministic expanding lattice around world spawn. Sampling a
     * biome uses the biome source; no getChunk/loadChunk/getBlockState or block mutation occurs.
     * Height remains an estimate until a safe non-loading terrain sampler is added.
     */
    private static void seedOneCandidate(ServerLevel level, SettlementNodeGraph graph, long now) {
        if (graph.nodes().size() >= MAX_ABSTRACT_NODES) return;
        int cursor;
        synchronized (CANDIDATE_CURSOR) {
            cursor = CANDIDATE_CURSOR.getOrDefault(level, 0);
            CANDIDATE_CURSOR.put(level, cursor + 1);
        }
        int ring = 1 + cursor / 8;
        int side = cursor % 8;
        int[][] directions = {
                {1, 0}, {1, 1}, {0, 1}, {-1, 1},
                {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
        };
        BlockPos spawn = level.getSharedSpawnPos();
        int x = spawn.getX() + directions[side][0] * ring * NODE_SPACING;
        int z = spawn.getZ() + directions[side][1] * ring * NODE_SPACING;
        String dimension = level.dimension().location().toString();
        String key = level.getSeed() + "|" + dimension + "|" + x + "|" + z;
        UUID id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        if (graph.node(id) != null) return;

        BlockPos samplePos = new BlockPos(x, spawn.getY(), z);
        String biome = level.getBiome(samplePos).unwrapKey()
                .map(holder -> holder.location().toString()).orElse("unknown");
        int terrainScore = scoreBiome(biome);
        if (terrainScore < 20) return;

        int food = biome.contains("plains") || biome.contains("meadow") || biome.contains("river") ? 75 : 40;
        int timber = biome.contains("forest") || biome.contains("taiga") || biome.contains("grove") ? 80 : 35;
        int stone = biome.contains("mountain") || biome.contains("windswept") || biome.contains("stony") ? 80 : 45;
        graph.addNode(id, x, z, spawn.getY(), biome, terrainScore, now)
                .updateTerrain(spawn.getY(), biome, terrainScore, food, timber, stone);
        graph.markChanged();
    }

    private static int scoreBiome(String biome) {
        String id = biome.toLowerCase(java.util.Locale.ROOT);
        if (id.contains("ocean") || id.contains("river") || id.contains("deep_") || id.contains("nether")
                || id.contains("the_end") || id.contains("badlands") || id.contains("ice_spikes")) return 10;
        if (id.contains("plains") || id.contains("meadow") || id.contains("forest")
                || id.contains("savanna") || id.contains("taiga") || id.contains("grove")) return 85;
        if (id.contains("mountain") || id.contains("windswept") || id.contains("stony")) return 55;
        return 45;
    }

    private static void advanceOneNode(ServerLevel level, SettlementNodeGraph graph, long now) {
        List<SettlementNodeGraph.Node> nodes = new ArrayList<>(graph.nodes());
        if (nodes.isEmpty()) return;
        int index;
        synchronized (ROUND_ROBIN) {
            index = Math.floorMod(ROUND_ROBIN.getOrDefault(level, 0), nodes.size());
            ROUND_ROBIN.put(level, (index + 1) % nodes.size());
        }
        SettlementNodeGraph.Node node = nodes.get(index);
        double radiusSqr = (double) PLAYER_WAKE_RADIUS * PLAYER_WAKE_RADIUS;
        boolean nearby = false;
        for (var player : level.players()) {
            if (player.distanceToSqr(node.x() + 0.5D, node.estimatedY() + 0.5D, node.z() + 0.5D)
                    <= radiusSqr) {
                nearby = true;
                break;
            }
        }

        if (nearby) {
            if (node.state() == SettlementNodeGraph.State.ABSTRACT
                    || node.state() == SettlementNodeGraph.State.SETTLING) {
                node.setState(SettlementNodeGraph.State.WAKE_QUEUED);
                graph.markChanged();
            }
            return;
        }

        if (node.state() == SettlementNodeGraph.State.LIVE
                || node.state() == SettlementNodeGraph.State.WAKE_QUEUED) {
            node.setState(SettlementNodeGraph.State.SETTLING);
            graph.markChanged();
            return;
        }
        if (now >= node.nextSimulation()) {
            simulateDataOnly(node, now);
            graph.markChanged();
        }
    }

    /** Bounded, abstract-only progression. No real inventory or world state is modified. */
    private static void simulateDataOnly(SettlementNodeGraph.Node node, long now) {
        int population = node.population();
        int food = node.foodReserve();
        int materials = node.materials();
        if (population == 0) population = 4;
        if (food > 0 && food < population * 8) {
            population = Math.max(1, population - 1);
        } else if (food >= population * 16 && population < 64) {
            population += 1;
            food -= population * 2;
        }
        // Potential influences only abstract planning estimates; these are not real items.
        if (food == 0 && node.foodPotential() >= 60) food = Math.min(100, node.foodPotential() / 3);
        node.setEstimates(population, food, materials, node.knowledgeFlags(), node.pendingTasks());
        node.advance(now, now + SCHEDULER_INTERVAL);
    }
}
