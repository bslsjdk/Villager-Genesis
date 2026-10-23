package dev.villagergenesis.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.minecraft.world.level.levelgen.Heightmap;

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
    private static final int LOCAL_SPACING = 512;
    private static final int GLOBAL_SPACING = 2048;
    private static final int PLAYER_WAKE_RADIUS = 112;
    private static final int GRAPH_NODE_BUDGET = 2;
    private static final int MAX_ABSTRACT_NODES = 512;
    private static final int GRAPH_NEIGHBOURS = 3;
    private static final Map<ServerLevel, Long> NEXT_LEVEL_STEP = new WeakHashMap<>();
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
            int nodeCount = graph.nodes().size();
            // Spend more on graph quality while small; taper work as the graph grows.
            int adaptiveBudget = nodeCount < 32 ? 4 : nodeCount < 128 ? 2 : 1;
            graph.rebuildLocalEdges(adaptiveBudget, GRAPH_NEIGHBOURS, now);
            advanceOneNode(level, graph, now);
        }
    }

    /**
     * Adaptive deterministic probing uses coarse seed-derived global exploration and fine local
     * refinement. No chunk/block access or world mutation occurs. Height remains an estimate.
     */
    private static void seedOneCandidate(ServerLevel level, SettlementNodeGraph graph, long now) {
        if (graph.nodes().size() >= MAX_ABSTRACT_NODES) return;
        int cursor = graph.candidateCursor();
        graph.advanceCandidateCursor();
        int[][] directions = {
                {1, 0}, {1, 1}, {0, 1}, {-1, 1},
                {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
        };
        boolean refine = cursor % 4 == 3 && !graph.nodes().isEmpty();
        int x;
        int z;
        if (refine) {
            SettlementNodeGraph.Node anchor = chooseRefinementAnchor(graph.nodes(), cursor);
            int ring = 1 + (cursor / 32) % 4;
            int side = (cursor / 4) % directions.length;
            x = anchor.x() + directions[side][0] * ring * LOCAL_SPACING;
            z = anchor.z() + directions[side][1] * ring * LOCAL_SPACING;
        } else {
            int globalIndex = cursor - cursor / 4;
            int ring = 1 + globalIndex / directions.length;
            int side = globalIndex % directions.length;
            long seed = level.getSeed() ^ level.dimension().location().toString().hashCode();
            int originX = (int) Math.floorMod(seed, 8192L) - 4096;
            int originZ = (int) Math.floorMod(seed >>> 13, 8192L) - 4096;
            x = originX + directions[side][0] * ring * GLOBAL_SPACING;
            z = originZ + directions[side][1] * ring * GLOBAL_SPACING;
        }
        String dimension = level.dimension().location().toString();
        String key = level.getSeed() + "|" + dimension + "|" + x + "|" + z;
        BlockPos samplePos = new BlockPos(x, level.getSeaLevel(), z);
        UUID id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        if (graph.node(id) != null) return;

        // Global safety gate: if the target chunk is not already loaded, do not call any
        // terrain API (including getBiome). Store only explicitly estimated metadata.
        if (!level.hasChunkAt(samplePos)) {
            String biome = "unobserved";
            int estimatedY = samplePos.getY();
            int terrainScore = 45;
            int food = 40;
            int timber = 35;
            int stone = 45;
            graph.addNode(id, x, z, estimatedY, biome, terrainScore, now)
                    .updateTerrain(estimatedY, biome, terrainScore, food, timber, stone);
            graph.markChanged();
            return;
        }

        // Every terrain query is below the loaded-chunk gate.
        String biome = level.getBiome(samplePos).unwrapKey()
                .map(holder -> holder.location().toString()).orElse("unknown");
        int estimatedY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        int terrainScore = scoreBiome(biome);
        if (terrainScore < 20) return;

        int food = biome.contains("plains") || biome.contains("meadow") || biome.contains("river") ? 75 : 40;
        int timber = biome.contains("forest") || biome.contains("taiga") || biome.contains("grove") ? 80 : 35;
        int stone = biome.contains("mountain") || biome.contains("windswept") || biome.contains("stony") ? 80 : 45;
        graph.addNode(id, x, z, estimatedY, biome, terrainScore, now)
                .updateTerrain(estimatedY, biome, terrainScore, food, timber, stone);
        graph.markChanged();
    }

    /**
     * Pick a refinement anchor using site quality, complementary resources, and local crowding.
     * The cursor rotates deterministic tie-breaking so one high-scoring node cannot monopolize
     * all refinement probes. This examines only the bounded abstract graph, never chunks.
     */
    private static SettlementNodeGraph.Node chooseRefinementAnchor(
            List<SettlementNodeGraph.Node> nodes, int cursor) {
        List<SettlementNodeGraph.Node> ordered = new ArrayList<>(nodes);
        ordered.sort(java.util.Comparator.comparing(n -> n.id().toString()));
        SettlementNodeGraph.Node best = ordered.get(Math.floorMod(cursor / 4, ordered.size()));
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < ordered.size(); i++) {
            SettlementNodeGraph.Node candidate =
                    ordered.get((i + Math.floorMod(cursor / 4, ordered.size())) % ordered.size());
            int nearby = 0;
            for (SettlementNodeGraph.Node other : ordered) {
                if (candidate == other) continue;
                long dx = (long) candidate.x() - other.x();
                long dz = (long) candidate.z() - other.z();
                if (dx * dx + dz * dz <= (long) GLOBAL_SPACING * GLOBAL_SPACING) nearby++;
            }

            int resourceScore = (candidate.foodPotential() + candidate.timberPotential()
                    + candidate.stonePotential()) / 3;
            // Quality/resources attract refinement; crowding redirects it toward less explored space.
            int score = candidate.terrainScore() * 2 + resourceScore - nearby * 18;
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
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
                // Wake-up only queues a data-only planning task; it does not start construction.
                SettlementTaskQueue.get(level).enqueueUnique(
                        node.id(), SettlementTaskQueue.Type.PLAN_SETTLEMENT, now);
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
