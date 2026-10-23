package dev.villagergenesis.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent abstract civilization graph. Nodes and edges are data only: this class never reads
 * blocks, loads chunks, creates entities, or changes the world.
 */
public final class SettlementNodeGraph extends SavedData {
    private static final String DATA_NAME = "villager_genesis_node_graph";
    private static final Factory<SettlementNodeGraph> FACTORY =
            new Factory<>(SettlementNodeGraph::new, SettlementNodeGraph::load, DataFixTypes.LEVEL);

    public enum Kind { CAMP, VILLAGE, TOWN, CITY, CAPITAL }
    public enum State { ABSTRACT, WAKE_QUEUED, LIVE, SETTLING }
    public enum Relation { NEUTRAL, TRADE, TENSION, WAR, ALLIANCE }

    public static final class Node {
        private final UUID id;
        private final int x;
        private final int z;
        private int estimatedY;
        private String biomeId;
        private int terrainScore;
        private int foodPotential;
        private int timberPotential;
        private int stonePotential;
        private int population;
        private int foodReserve;
        private int materials;
        private int knowledgeFlags;
        private int pendingTasks;
        private Kind kind;
        private State state;
        private String planningRole = "BALANCED";
        private long lastSimulated;
        private long nextSimulation;

        private Node(UUID id, int x, int z, int y, String biomeId, int terrainScore, long gameTime) {
            this.id = id;
            this.x = x;
            this.z = z;
            this.estimatedY = y;
            this.biomeId = biomeId == null ? "unknown" : biomeId;
            this.terrainScore = terrainScore;
            this.kind = Kind.CAMP;
            this.state = State.ABSTRACT;
            this.lastSimulated = gameTime;
            this.nextSimulation = gameTime;
        }
        public UUID id() { return id; }
        public int x() { return x; }
        public int z() { return z; }
        public int estimatedY() { return estimatedY; }
        public String biomeId() { return biomeId; }
        public int terrainScore() { return terrainScore; }
        public int foodPotential() { return foodPotential; }
        public int timberPotential() { return timberPotential; }
        public int stonePotential() { return stonePotential; }
        public int population() { return population; }
        public int foodReserve() { return foodReserve; }
        public int materials() { return materials; }
        public int knowledgeFlags() { return knowledgeFlags; }
        public int pendingTasks() { return pendingTasks; }
        public Kind kind() { return kind; }
        public State state() { return state; }
        public String planningRole() { return planningRole; }
        public long lastSimulated() { return lastSimulated; }
        public long nextSimulation() { return nextSimulation; }
        public BlockPos estimatedPosition() { return new BlockPos(x, estimatedY, z); }

        public void updateTerrain(int y, String biome, int score, int food, int timber, int stone) {
            estimatedY = y;
            biomeId = biome == null ? "unknown" : biome;
            terrainScore = clamp(score, 0, 100);
            foodPotential = clamp(food, 0, 100);
            timberPotential = clamp(timber, 0, 100);
            stonePotential = clamp(stone, 0, 100);
        }
        public void setState(State value) { state = value; }
        public void setKind(Kind value) { kind = value; }
        public void setPlanningRole(String value) {
            planningRole = value == null || value.isBlank() ? "BALANCED" : value;
        }
        public void setEstimates(int people, int food, int supplies, int knowledge, int tasks) {
            population = clamp(people, 0, 1_000_000);
            foodReserve = clamp(food, 0, 1_000_000_000);
            materials = clamp(supplies, 0, 1_000_000_000);
            knowledgeFlags = Math.max(0, knowledge);
            pendingTasks = Math.max(0, tasks);
        }
        public void advance(long now, long next) {
            lastSimulated = Math.max(lastSimulated, now);
            nextSimulation = Math.max(now, next);
        }
    }

    public static final class Edge {
        private final UUID a;
        private final UUID b;
        private int distance;
        private Relation relation;
        private int tradeScore;
        private int tension;
        private long lastUpdated;

        private Edge(UUID a, UUID b, int distance, Relation relation, int tradeScore, int tension, long time) {
            if (a.compareTo(b) <= 0) { this.a = a; this.b = b; }
            else { this.a = b; this.b = a; }
            this.distance = Math.max(0, distance);
            this.relation = relation;
            this.tradeScore = clamp(tradeScore, 0, 100);
            this.tension = clamp(tension, 0, 100);
            this.lastUpdated = time;
        }
        public UUID a() { return a; }
        public UUID b() { return b; }
        public int distance() { return distance; }
        public Relation relation() { return relation; }
        public int tradeScore() { return tradeScore; }
        public int tension() { return tension; }
        public long lastUpdated() { return lastUpdated; }
        public void update(Relation relation, int tradeScore, int tension, long time) {
            this.relation = relation;
            this.tradeScore = clamp(tradeScore, 0, 100);
            this.tension = clamp(tension, 0, 100);
            this.lastUpdated = time;
        }
    }

    private final Map<UUID, Node> nodes = new HashMap<>();
    private final Map<String, Edge> edges = new HashMap<>();
    // Persisted cursor prevents low-UUID nodes from monopolizing the bounded graph budget.
    private int edgeCursor = 0;
    private int candidateCursor = 0;

    public static SettlementNodeGraph get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Node addNode(UUID id, int x, int z, int y, String biome, int terrainScore, long time) {
        Node existing = nodes.get(id);
        if (existing != null) return existing;
        Node node = new Node(id, x, z, y, biome, terrainScore, time);
        nodes.put(id, node);
        setDirty();
        return node;
    }

    public Node node(UUID id) { return nodes.get(id); }
    public int candidateCursor() { return candidateCursor; }
    public void advanceCandidateCursor() {
        candidateCursor = candidateCursor == Integer.MAX_VALUE ? 0 : candidateCursor + 1;
        setDirty();
    }
    public List<Node> nodes() { return Collections.unmodifiableList(new ArrayList<>(nodes.values())); }
    public List<Edge> edges() { return Collections.unmodifiableList(new ArrayList<>(edges.values())); }

    public Edge connect(UUID first, UUID second, long time) {
        if (first.equals(second) || !nodes.containsKey(first) || !nodes.containsKey(second)) return null;
        Node a = nodes.get(first);
        Node b = nodes.get(second);
        long dx = (long) a.x - b.x;
        long dz = (long) a.z - b.z;
        int distance = (int) Math.min(Integer.MAX_VALUE, Math.sqrt((double) dx * dx + (double) dz * dz));
        String key = edgeKey(first, second);
        Edge edge = edges.get(key);

        int complement = Math.abs(a.foodPotential() - b.foodPotential())
                + Math.abs(a.timberPotential() - b.timberPotential())
                + Math.abs(a.stonePotential() - b.stonePotential());
        int trade = Math.min(100, complement / 3);
        int proximityPressure = distance < 256 ? Math.max(0, 35 - distance / 8) : 0;
        int tension = edge == null ? proximityPressure
                : clamp(edge.tension() + (proximityPressure > edge.tension() ? 2
                : proximityPressure < edge.tension() ? -1 : 0), 0, 100);
        Relation relation;
        if (edge != null && (edge.relation() == Relation.WAR || edge.relation() == Relation.ALLIANCE)) {
            // Preserve deliberate diplomatic states; only a future diplomacy system changes them.
            relation = edge.relation();
        } else if (tension >= 55) {
            relation = Relation.TENSION;
        } else if (trade >= 35) {
            relation = Relation.TRADE;
        } else {
            relation = tension >= 20 ? Relation.TENSION : Relation.NEUTRAL;
        }

        if (edge == null) {
            edge = new Edge(first, second, distance, relation, trade, tension, time);
            edges.put(key, edge);
            setDirty();
        } else if (edge.distance() != distance || edge.relation() != relation
                || edge.tradeScore() != trade || edge.tension() != tension) {
            edge.distance = distance;
            edge.update(relation, trade, tension, time);
            setDirty();
        }
        return edge;
    }

    /**
     * Adaptive, bounded nearest-neighbour maintenance. A persistent round-robin cursor spreads
     * work across the whole graph instead of repeatedly servicing the same UUID-sorted prefix.
     * The per-call cost is bounded by maxNodes * nodeCount * maxNeighbours and never touches chunks.
     */
    public int rebuildLocalEdges(int maxNodes, int maxNeighbours, long time) {
        if (maxNodes <= 0 || maxNeighbours <= 0 || nodes.size() < 2) return 0;
        List<Node> ordered = new ArrayList<>(nodes.values());
        ordered.sort(Comparator.comparing(n -> n.id().toString()));
        int count = ordered.size();
        int limit = Math.min(maxNodes, count);
        int start = Math.floorMod(edgeCursor, count);
        int added = 0;

        for (int offset = 0; offset < limit; offset++) {
            Node node = ordered.get((start + offset) % count);
            List<Node> nearest = new ArrayList<>(Math.min(maxNeighbours, count - 1));
            for (Node candidate : ordered) {
                if (candidate == node) continue;
                long candidateDistance = squaredDistance(node, candidate);
                int insertAt = 0;
                while (insertAt < nearest.size()
                        && squaredDistance(node, nearest.get(insertAt)) <= candidateDistance) {
                    insertAt++;
                }
                if (insertAt >= maxNeighbours) continue;
                nearest.add(insertAt, candidate);
                if (nearest.size() > maxNeighbours) nearest.remove(nearest.size() - 1);
            }
            for (Node neighbour : nearest) {
                boolean existed = edges.containsKey(edgeKey(node.id(), neighbour.id()));
                connect(node.id(), neighbour.id(), time);
                if (!existed) added++;
            }
        }

        int nextCursor = (start + limit) % count;
        if (edgeCursor != nextCursor) {
            edgeCursor = nextCursor;
            setDirty();
        }
        return added;
    }

    public void markChanged() { setDirty(); }

    private static long squaredDistance(Node a, Node b) {
        long dx = (long) a.x - b.x, dz = (long) a.z - b.z;
        return dx * dx + dz * dz;
    }
    private static String edgeKey(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    public static SettlementNodeGraph load(CompoundTag tag, HolderLookup.Provider registries) {
        SettlementNodeGraph graph = new SettlementNodeGraph();
        ListTag nodes = tag.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag e = nodes.getCompound(i);
            if (!e.hasUUID("id")) continue;
            Node n = new Node(e.getUUID("id"), e.getInt("x"), e.getInt("z"), e.getInt("y"),
                    e.getString("biome"), e.getInt("terrain"), e.getLong("last"));
            n.foodPotential = clamp(e.getInt("food_potential"), 0, 100);
            n.timberPotential = clamp(e.getInt("timber_potential"), 0, 100);
            n.stonePotential = clamp(e.getInt("stone_potential"), 0, 100);
            n.population = clamp(e.getInt("population"), 0, 1_000_000);
            n.foodReserve = clamp(e.getInt("food_reserve"), 0, 1_000_000_000);
            n.materials = clamp(e.getInt("materials"), 0, 1_000_000_000);
            n.knowledgeFlags = Math.max(0, e.getInt("knowledge"));
            n.pendingTasks = Math.max(0, e.getInt("tasks"));
            n.kind = enumValue(Kind.values(), e.getInt("kind"), Kind.CAMP);
            n.state = enumValue(State.values(), e.getInt("state"), State.ABSTRACT);
            n.setPlanningRole(e.getString("planning_role"));
            n.nextSimulation = Math.max(n.lastSimulated, e.getLong("next"));
            graph.nodes.put(n.id, n);
        }
        graph.edgeCursor = Math.max(0, tag.getInt("edge_cursor"));
        graph.candidateCursor = Math.max(0, tag.getInt("candidate_cursor"));
        ListTag edges = tag.getList("edges", Tag.TAG_COMPOUND);
        for (int i = 0; i < edges.size(); i++) {
            CompoundTag e = edges.getCompound(i);
            if (!e.hasUUID("a") || !e.hasUUID("b")) continue;
            UUID a = e.getUUID("a"), b = e.getUUID("b");
            if (!graph.nodes.containsKey(a) || !graph.nodes.containsKey(b) || a.equals(b)) continue;
            Edge edge = new Edge(a, b, e.getInt("distance"),
                    enumValue(Relation.values(), e.getInt("relation"), Relation.NEUTRAL),
                    e.getInt("trade"), e.getInt("tension"), e.getLong("updated"));
            graph.edges.put(edgeKey(a, b), edge);
        }
        return graph;
    }

    private static <T> T enumValue(T[] values, int ordinal, T fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag nodeList = new ListTag();
        for (Node n : nodes.values()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("id", n.id); e.putInt("x", n.x); e.putInt("z", n.z); e.putInt("y", n.estimatedY);
            e.putString("biome", n.biomeId); e.putInt("terrain", n.terrainScore);
            e.putInt("food_potential", n.foodPotential); e.putInt("timber_potential", n.timberPotential);
            e.putInt("stone_potential", n.stonePotential); e.putInt("population", n.population);
            e.putInt("food_reserve", n.foodReserve); e.putInt("materials", n.materials);
            e.putInt("knowledge", n.knowledgeFlags); e.putInt("tasks", n.pendingTasks);
            e.putInt("kind", n.kind.ordinal()); e.putInt("state", n.state.ordinal());
            e.putString("planning_role", n.planningRole);
            e.putLong("last", n.lastSimulated); e.putLong("next", n.nextSimulation);
            nodeList.add(e);
        }
        ListTag edgeList = new ListTag();
        for (Edge edge : edges.values()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("a", edge.a); e.putUUID("b", edge.b); e.putInt("distance", edge.distance);
            e.putInt("relation", edge.relation.ordinal()); e.putInt("trade", edge.tradeScore);
            e.putInt("tension", edge.tension); e.putLong("updated", edge.lastUpdated);
            edgeList.add(e);
        }
        tag.putInt("edge_cursor", edgeCursor);
        tag.putInt("candidate_cursor", candidateCursor);
        tag.put("nodes", nodeList);
        tag.put("edges", edgeList);
        return tag;
    }
}
